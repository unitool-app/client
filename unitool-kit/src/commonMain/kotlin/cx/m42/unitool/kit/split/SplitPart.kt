package cx.m42.unitool.kit.split

import cx.m42.unitool.kit.crypto.Hash
import cx.m42.unitool.kit.crypto.SecretBytes
import cx.m42.unitool.kit.crypto.secureRandomBytes

/**
 * One part of a split key (.ai-notes/done/split 02 §1): 48 bytes that say which set they belong to, how
 * many parts the set has and how many it takes, which part this is, and the share itself.
 *
 * ```
 * version 1 · set_id 8 · k 1 · n 1 · x 1 · share 32 · check 4
 * ```
 *
 * `check` is the first four bytes of SHA-256 over the rest, under a label. It catches a typo and a
 * link cut short. It does not catch a forgery — whoever forges recomputes it — and does not need to:
 * a wrong share gives a wrong key, and the container's MAC refuses it.
 *
 * The share is secret material, so it lives in [SecretBytes] and [close] zeroes it.
 */
public class SplitPart internal constructor(
    setId: ByteArray,
    /** How many parts it takes to open the set. */
    public val k: Int,
    /** How many parts the set has. */
    public val n: Int,
    /** This part's number, 1..[n]: the point its share was taken at. */
    public val x: Int,
    internal val share: SecretBytes,
) : AutoCloseable {

    private val id: ByteArray = setId.copyOf()

    init {
        require(id.size == SET_ID_SIZE) { "a set id is $SET_ID_SIZE bytes" }
        require(k in 2..n && n <= Shamir.MAX_PARTS) { "k from 2 to n, n at most ${Shamir.MAX_PARTS}: $k of $n" }
        require(x in 1..n) { "part number from 1 to $n, not $x" }
        require(share.size == SHARE_SIZE) { "a share is $SHARE_SIZE bytes" }
    }

    /** Which set this part belongs to; the container carries the same 8 bytes. */
    public val setId: ByteArray get() = id.copyOf()

    /** [setId] as 16 hex digits, the way people compare it. */
    public val setIdHex: String get() = id.toHex()

    /** The 48-byte wire form, check included. */
    public fun bytes(): ByteArray {
        val out = ByteArray(SIZE)
        out[0] = VERSION.toByte()
        id.copyInto(out, 1)
        out[9] = k.toByte()
        out[10] = n.toByte()
        out[11] = x.toByte()
        share.bytes.copyInto(out, 12)
        check(out).copyInto(out, CHECKED)
        return out
    }

    override fun close() {
        share.close()
    }

    override fun toString(): String = "SplitPart($setIdHex, $x of $n, any $k)"

    public companion object {
        public const val VERSION: Int = 1
        public const val SIZE: Int = 48
        public const val SET_ID_SIZE: Int = 8
        public const val SHARE_SIZE: Int = 32

        /** Bytes the check covers: everything before it. */
        private const val CHECKED = 44
        private val LABEL = "ucrypt/1 split part".encodeToByteArray()

        /** What 48 bytes are: a part, damaged bytes, or a version this code does not read. */
        public fun read(bytes: ByteArray): PartReading {
            if (bytes.size != SIZE) return PartReading.NotAPart
            val version = bytes[0].toInt() and 0xff
            // The check is defined per version, so an unknown version cannot be checked at all.
            if (version != VERSION) return PartReading.Unreadable(version)
            if (!check(bytes).contentEquals(bytes.copyOfRange(CHECKED, SIZE))) return PartReading.Damaged
            val k = bytes[9].toInt() and 0xff
            val n = bytes[10].toInt() and 0xff
            val x = bytes[11].toInt() and 0xff
            if (k < 2 || k > n || x < 1 || x > n) return PartReading.Damaged
            return PartReading.Ok(SplitPart(bytes.copyOfRange(1, 9), k, n, x, SecretBytes(bytes.copyOfRange(12, CHECKED))))
        }

        /** A fresh random set id. */
        internal fun newSetId(): ByteArray = secureRandomBytes(SET_ID_SIZE)

        private fun check(bytes: ByteArray): ByteArray = Hash.SHA256.digest(LABEL + bytes.copyOf(CHECKED)).copyOf(4)
    }
}

/** What [SplitPart.read] and [SplitLink.read] found. */
public sealed interface PartReading {
    public class Ok(public val part: SplitPart) : PartReading

    /** Not 48 bytes, or no part-shaped text at all. */
    public data object NotAPart : PartReading

    /** The right shape, but the check fails or a field is out of range: a typo, or a link cut short. */
    public data object Damaged : PartReading

    /** A version byte this code does not know: a part from a newer Unitool, or not a part at all. */
    public class Unreadable(public val version: Int) : PartReading
}

/**
 * Splitting a key into parts and putting it back together (.ai-notes/done/split D360–D362): [Shamir]
 * for the arithmetic, [SplitPart] for the form, a random set id so parts of different sets never mix.
 */
public object Split {

    /** [key] as [n] parts of a new set, any [k] of which give it back. [key] is left as it was. */
    public fun make(key: SecretBytes, k: Int, n: Int): List<SplitPart> {
        require(key.size == SplitPart.SHARE_SIZE) { "a split key is ${SplitPart.SHARE_SIZE} bytes" }
        val setId = SplitPart.newSetId()
        return Shamir.split(key.bytes, k, n).mapIndexed { index, share -> SplitPart(setId, k, n, index + 1, SecretBytes(share)) }
    }

    /**
     * The key from parts of the set [setId] that needs [k] of [n], or the reason it cannot be had
     * yet ([SplitPlan.check]). Uses the first [k] parts; whether the key is the right one only the
     * container can say.
     */
    public fun join(setId: ByteArray, k: Int, n: Int, parts: List<SplitPart>): Joined {
        SplitPlan.check(setId, k, n, parts)?.let { return Joined.Problem(it) }
        val used = parts.take(k)
        return Joined.Ok(SecretBytes(Shamir.combine(used.map { it.x }.toIntArray(), used.map { it.share.bytes })))
    }

    /**
     * The key from every combination of [k] of [parts] that [fits] accepts, trying them in order:
     * with one damaged or forged part among more than [k], some combination leaves it out. Returns
     * the key and the parts that disagree with it, or null when no combination fits.
     */
    public fun search(k: Int, parts: List<SplitPart>, fits: (SecretBytes) -> Boolean): Found? {
        for (combination in combinations(parts.indices.toList(), k)) {
            val used = combination.map { parts[it] }
            val xs = used.map { it.x }.toIntArray()
            val ys = used.map { it.share.bytes }
            val key = SecretBytes(Shamir.combine(xs, ys))
            if (!fits(key)) {
                key.close()
                continue
            }
            val disagreeing = parts.indices.filter { it !in combination }.filter { i ->
                !Shamir.evaluate(xs, ys, parts[i].x).contentEquals(parts[i].share.bytes)
            }
            return Found(key, disagreeing)
        }
        return null
    }

    /** At most this many combinations are tried: C(16, 8) is 12 870, and five parts give at most 10. */
    private const val MAX_COMBINATIONS = 20_000

    private fun combinations(items: List<Int>, size: Int): Sequence<List<Int>> = sequence {
        val chosen = IntArray(size) { it }
        var count = 0
        while (count++ < MAX_COMBINATIONS) {
            yield(chosen.map { items[it] })
            var i = size - 1
            while (i >= 0 && chosen[i] == items.size - size + i) i--
            if (i < 0) return@sequence
            chosen[i]++
            for (j in i + 1 until size) chosen[j] = chosen[j - 1] + 1
        }
    }

    public sealed interface Joined {
        public class Ok(public val key: SecretBytes) : Joined
        public class Problem(public val problem: SplitProblem) : Joined
    }

    /** [key] fits; the parts at [disagreeing] (indices into the list given) do not belong to it. */
    public class Found(public val key: SecretBytes, public val disagreeing: List<Int>)
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
