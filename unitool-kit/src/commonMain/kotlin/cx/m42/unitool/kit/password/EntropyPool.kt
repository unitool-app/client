package cx.m42.unitool.kit.password

import cx.m42.unitool.kit.crypto.Sha256
import cx.m42.unitool.kit.crypto.hkdfExpand
import cx.m42.unitool.kit.crypto.hkdfExtract
import cx.m42.unitool.kit.crypto.secureRandomBytes
import kotlin.math.abs
import kotlin.math.log2

/**
 * Entropy from a person's hand: where a finger drew, a mouse moved, and when, each point stirred
 * into a SHA-256 chain. It never replaces the system's random, it is mixed into it: [source] keys
 * HKDF with the pool and salts it with fresh system bytes, so a password drawn from it is at least
 * as strong as one from the system alone — and stays strong if either of the two turns out weak.
 *
 * [bits] is a cautious guess, not a measurement: a point counts only by how far it bends the line
 * (the second difference of its coordinates), at most one bit, and a point that repeats the last
 * one counts nothing. The timing goes into the pool too but is never counted.
 *
 * Kept in memory only; nothing of it is ever written down.
 */
public class EntropyPool {
    private var state = ByteArray(32)
    private val pending = ByteArray(BATCH)
    private var filled = 0

    private var last: Point? = null
    private var beforeLast: Point? = null

    /** Points taken, the repeated ones included. */
    public var samples: Int = 0
        private set

    /** The guessed entropy, in bits. */
    public var bits: Double = 0.0
        private set

    /** Whether [bits] has reached [TARGET_BITS]. */
    public val full: Boolean get() = bits >= TARGET_BITS

    /** One point: [x] and [y] in whatever units the screen or terminal has, [nanos] from any monotonic clock. */
    public fun add(x: Int, y: Int, nanos: Long) {
        put(x)
        put(y)
        put((nanos ushr 32).toInt())
        put(nanos.toInt())
        samples++
        val p = last
        if (p != null && p.x == x && p.y == y) return
        val pp = beforeLast
        if (p != null && pp != null) {
            val bendX = abs((x - p.x) - (p.x - pp.x))
            val bendY = abs((y - p.y) - (p.y - pp.y))
            bits += ((log2(1.0 + bendX) + log2(1.0 + bendY)) / 4).coerceAtMost(1.0)
        }
        beforeLast = p
        last = Point(x, y)
    }

    /**
     * Random bytes for [PasswordGenerator.generate]: HKDF over SHA-256 with the pool as the key
     * material and 32 bytes of [system] as the salt, one Expand per request.
     */
    public fun source(system: (Int) -> ByteArray = ::secureRandomBytes): (Int) -> ByteArray {
        stir()
        val key = hkdfExtract(salt = system(32), ikm = state)
        var counter = 0L
        return { size -> hkdfExpand(key, "unitool password ${counter++}".encodeToByteArray(), size) }
    }

    /** Forgets everything: the next drawing starts from nothing. */
    public fun clear() {
        state.fill(0)
        pending.fill(0)
        filled = 0
        last = null
        beforeLast = null
        samples = 0
        bits = 0.0
    }

    private fun put(value: Int) {
        if (filled + 4 > BATCH) stir()
        pending[filled++] = (value ushr 24).toByte()
        pending[filled++] = (value ushr 16).toByte()
        pending[filled++] = (value ushr 8).toByte()
        pending[filled++] = value.toByte()
    }

    /** Folds the points taken since the last time into the state. */
    private fun stir() {
        if (filled == 0) return
        state = Sha256.digest(state + pending.copyOf(filled))
        pending.fill(0)
        filled = 0
    }

    private class Point(val x: Int, val y: Int)

    public companion object {
        /** As much as a 256-bit key holds: enough for any password the generator makes. */
        public const val TARGET_BITS: Int = 256

        private const val BATCH = 1024
    }
}
