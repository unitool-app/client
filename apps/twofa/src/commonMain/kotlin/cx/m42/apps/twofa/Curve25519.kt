package cx.m42.apps.twofa

/**
 * X25519 (RFC 7748), plus the one step from an Ed25519 public key to its X25519 twin that age needs
 * to encrypt to an `ssh-ed25519` key.
 *
 * TweetNaCl's field arithmetic, ported line for line: sixteen 16-bit limbs held in `Long`s, so a
 * product of two limbs and the sum of sixteen of them never leave 64 bits on any target, wasm
 * included. It is the smallest well-reviewed implementation there is, which matters more here
 * than speed — the export runs this three times per key.
 *
 * Checked in `desktopTest` against the JVM's own X25519 and against RFC 7748's vectors.
 */
internal object Curve25519 {

    /** The u-coordinate 9: the curve's base point. */
    val BASE: ByteArray = ByteArray(32).also { it[0] = 9 }

    /** (A − 2) / 4 for A = 486662, the Montgomery ladder's constant. */
    private val A24 = gf(0xDB41, 1)

    /** `scalar · point`, both 32 bytes little-endian; the scalar is clamped as RFC 7748 says. */
    fun x25519(scalar: ByteArray, point: ByteArray): ByteArray {
        val z = scalar.copyOf(32)
        z[31] = ((z[31].toInt() and 127) or 64).toByte()
        z[0] = (z[0].toInt() and 248).toByte()
        val x = unpack(point)
        val a = gf(1)
        val b = x.copyOf()
        val c = gf()
        val d = gf(1)
        val e = gf()
        val f = gf()
        // (a, c) is (x_2, z_2) and (b, d) is (x_3, z_3); the comments are RFC 7748 §5's names.
        for (i in 254 downTo 0) {
            val r = ((z[i ushr 3].toInt() and 0xff) ushr (i and 7)) and 1
            swap(a, b, r)
            swap(c, d, r)
            add(e, a, c) // A
            sub(a, a, c) // B
            add(c, b, d) // C
            sub(b, b, d) // D
            mul(d, e, e) // AA
            mul(f, a, a) // BB
            mul(a, c, a) // CB
            mul(c, b, e) // DA
            add(e, a, c) // DA + CB
            sub(a, a, c) // CB − DA
            mul(b, a, a) // (DA − CB)²
            sub(c, d, f) // E = AA − BB
            mul(a, c, A24)
            add(a, a, d) // AA + a24·E
            mul(c, c, a) // z_2 = E·(AA + a24·E)
            mul(a, d, f) // x_2 = AA·BB
            mul(d, b, x) // z_3 = x_1·(DA − CB)²
            mul(b, e, e) // x_3 = (DA + CB)²
            swap(a, b, r)
            swap(c, d, r)
        }
        invert(c, c)
        mul(a, a, c)
        return pack(a)
    }

    /**
     * The birational map from the Edwards curve to the Montgomery one: u = (1 + y) / (1 − y).
     *
     * The x sign bit is dropped, because u does not depend on it. The point is not checked to be
     * on the curve: GitHub validates a key when it is uploaded, and a bad one would only make a
     * file nobody can open, never one somebody else can.
     */
    fun edwardsToMontgomery(publicKey: ByteArray): ByteArray {
        val y = unpack(publicKey)
        val one = gf(1)
        val numerator = gf()
        val denominator = gf()
        add(numerator, one, y)
        sub(denominator, one, y)
        invert(denominator, denominator)
        mul(numerator, numerator, denominator)
        return pack(numerator)
    }

    private fun gf(vararg init: Long): LongArray = LongArray(16).also { init.copyInto(it) }

    /** Carries each limb into the next; the top one wraps round as ×38, since 2^256 ≡ 38. */
    private fun carry(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
            o[i] -= c shl 16
        }
    }

    /** Swaps [p] and [q] when [bit] is 1, without a branch that would time which it was. */
    private fun swap(p: LongArray, q: LongArray, bit: Int) {
        val mask = (bit - 1).toLong().inv()
        for (i in 0 until 16) {
            val t = mask and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun pack(n: LongArray): ByteArray {
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

    private fun unpack(n: ByteArray): LongArray {
        val o = LongArray(16) { (n[2 * it].toLong() and 0xff) or ((n[2 * it + 1].toLong() and 0xff) shl 8) }
        o[15] = o[15] and 0x7fff
        return o
    }

    private fun add(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] + b[i]
    }

    private fun sub(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] - b[i]
    }

    private fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        for (i in 0 until 16) o[i] = t[i]
        carry(o)
        carry(o)
    }

    /** `i^(p−2)`: Fermat's inverse, by the fixed chain of squarings TweetNaCl uses. */
    private fun invert(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            mul(c, c, c)
            if (a != 2 && a != 4) mul(c, c, i)
        }
        c.copyInto(o)
    }
}
