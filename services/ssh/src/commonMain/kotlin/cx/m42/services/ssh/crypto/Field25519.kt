package cx.m42.services.ssh.crypto

/**
 * Arithmetic in GF(2²⁵⁵ − 19), TweetNaCl's representation: sixteen 16-bit limbs held in `Long`s,
 * so a product of two limbs and the sum of sixteen of them never leave 64 bits on any target, wasm
 * included. Shared by [Curve25519] and [Ed25519]; nothing here branches on a secret.
 */
internal object Field25519 {

    fun gf(vararg init: Long): LongArray = LongArray(16).also { init.copyInto(it) }

    /** Carries each limb into the next; the top one wraps round as ×38, since 2^256 ≡ 38. */
    fun carry(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
            o[i] -= c shl 16
        }
    }

    /** Swaps [p] and [q] when [bit] is 1, without a branch that would time which it was. */
    fun swap(p: LongArray, q: LongArray, bit: Int) {
        val mask = (bit - 1).toLong().inv()
        for (i in 0 until 16) {
            val t = mask and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    fun pack(n: LongArray): ByteArray {
        val m = gf()
        val t = n.copyOf()
        carry(t)
        carry(t)
        carry(t)
        repeat(2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val borrow = ((m[15] shr 16) and 1).toInt()
            m[14] = m[14] and 0xffff
            swap(t, m, 1 - borrow)
        }
        return ByteArray(32) { i -> (if (i % 2 == 0) t[i / 2] else t[i / 2] shr 8).toByte() }
    }

    fun unpack(n: ByteArray): LongArray {
        val o = LongArray(16) { (n[2 * it].toLong() and 0xff) or ((n[2 * it + 1].toLong() and 0xff) shl 8) }
        o[15] = o[15] and 0x7fff
        return o
    }

    fun add(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] + b[i]
    }

    fun sub(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] - b[i]
    }

    fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        for (i in 0 until 16) o[i] = t[i]
        carry(o)
        carry(o)
    }

    fun sqr(o: LongArray, a: LongArray) = mul(o, a, a)

    /** `i^(p−2)`: Fermat's inverse, by the fixed chain of squarings TweetNaCl uses. */
    fun invert(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            sqr(c, c)
            if (a != 2 && a != 4) mul(c, c, i)
        }
        c.copyInto(o)
    }

    /** `i^((p−5)/8)`, the square-root helper of Ed25519's point decoding. */
    fun pow2523(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 250 downTo 0) {
            sqr(c, c)
            if (a != 1) mul(c, c, i)
        }
        c.copyInto(o)
    }

    /** 0 when equal, as packed field elements; constant time over the 32 bytes. */
    fun differ(a: LongArray, b: LongArray): Boolean {
        val x = pack(a)
        val y = pack(b)
        var d = 0
        for (i in 0 until 32) d = d or (x[i].toInt() xor y[i].toInt())
        return d != 0
    }

    fun parity(a: LongArray): Int = pack(a)[0].toInt() and 1
}
