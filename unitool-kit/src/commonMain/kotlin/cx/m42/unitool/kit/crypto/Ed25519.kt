package cx.m42.unitool.kit.crypto

import cx.m42.unitool.kit.crypto.Field25519.add
import cx.m42.unitool.kit.crypto.Field25519.differ
import cx.m42.unitool.kit.crypto.Field25519.gf
import cx.m42.unitool.kit.crypto.Field25519.invert
import cx.m42.unitool.kit.crypto.Field25519.mul
import cx.m42.unitool.kit.crypto.Field25519.pack
import cx.m42.unitool.kit.crypto.Field25519.parity
import cx.m42.unitool.kit.crypto.Field25519.pow2523
import cx.m42.unitool.kit.crypto.Field25519.sqr
import cx.m42.unitool.kit.crypto.Field25519.sub
import cx.m42.unitool.kit.crypto.Field25519.swap
import cx.m42.unitool.kit.crypto.Field25519.unpack

/**
 * Ed25519 (RFC 8032): TweetNaCl's `crypto_sign` and `crypto_sign_open`, ported line for line onto
 * the field arithmetic [Curve25519] already uses (02 §10). Verification also rejects `S ≥ L`, as
 * RFC 8032 §5.1.7 and the JDK do and TweetNaCl does not.
 *
 * `desktopTest` holds it to RFC 8032 §7.1 and to a thousand random seeds against the JDK's
 * `Signature("Ed25519")`, byte for byte.
 */
public object Ed25519 {

    private val D = gf(0x78a3, 0x1359, 0x4dca, 0x75eb, 0xd8ab, 0x4141, 0x0a4d, 0x0070, 0xe898, 0x7779, 0x4079, 0x8cc7, 0xfe73, 0x2b6f, 0x6cee, 0x5203)
    private val D2 = gf(0xf159, 0x26b2, 0x9b94, 0xebd6, 0xb156, 0x8283, 0x149a, 0x00e0, 0xd130, 0xeef3, 0x80f2, 0x198e, 0xfce7, 0x56df, 0xd9dc, 0x2406)
    private val X = gf(0xd51a, 0x8f25, 0x2d60, 0xc956, 0xa7b2, 0x9525, 0xc760, 0x692c, 0xdc5c, 0xfdd6, 0xe231, 0xc0a4, 0x53fe, 0xcd6e, 0x36d3, 0x2169)
    private val Y = gf(0x6658, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666)
    private val I = gf(0xa0b0, 0x4a0e, 0x1b27, 0xc4ee, 0xe478, 0xad2f, 0x1806, 0x2f43, 0xd7a7, 0x3dfb, 0x0099, 0x2b4d, 0xdf0b, 0x4fc1, 0x2480, 0x2b83)

    /** The group order ℓ = 2²⁵² + 27742317777372353535851937790883648493, little-endian. */
    private val L = longArrayOf(
        0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10,
    )

    /** The public key of [seed]: the encoding of [a]B with a from SHA-512(seed). */
    public fun publicKey(seed: ByteArray): ByteArray {
        require(seed.size == 32)
        val d = Sha512.digest(seed)
        clamp(d)
        val p = point()
        scalarBase(p, d)
        d.fill(0)
        return encode(p)
    }

    /** The 64-byte signature of [message] by [seed] — deterministic, as RFC 8032 has it. */
    public fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        require(seed.size == 32)
        val d = Sha512.digest(seed)
        clamp(d)
        val publicKey = run {
            val p = point()
            scalarBase(p, d)
            encode(p)
        }
        val prefixed = d.copyOfRange(32, 64) + message
        val r = Sha512.digest(prefixed)
        prefixed.fill(0)
        reduce(r)
        val big = point()
        scalarBase(big, r)
        val encodedR = encode(big)

        val h = Sha512.digest(encodedR + publicKey + message)
        reduce(h)
        val x = LongArray(64)
        for (i in 0 until 32) x[i] = r[i].toLong() and 0xff
        for (i in 0 until 32) for (j in 0 until 32) x[i + j] += (h[i].toLong() and 0xff) * (d[j].toLong() and 0xff)
        val s = ByteArray(32)
        modL(s, x)
        d.fill(0)
        r.fill(0)
        return encodedR + s
    }

    public fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        if (!lessThanL(signature, 32)) return false
        val q = point()
        if (!unpackNeg(q, publicKey)) return false
        val h = Sha512.digest(signature.copyOfRange(0, 32) + publicKey + message)
        reduce(h)
        val p = point()
        scalarMult(p, q, h)
        val sb = point()
        scalarBase(sb, signature.copyOfRange(32, 64))
        addPoints(p, sb)
        val t = encode(p)
        var diff = 0
        for (i in 0 until 32) diff = diff or (t[i].toInt() xor signature[i].toInt())
        return diff == 0
    }

    // ------------------------------------------------------------ TweetNaCl, point by point

    private fun point(): Array<LongArray> = Array(4) { gf() }

    private fun clamp(d: ByteArray) {
        d[0] = (d[0].toInt() and 248).toByte()
        d[31] = (d[31].toInt() and 127).toByte()
        d[31] = (d[31].toInt() or 64).toByte()
    }

    private fun addPoints(p: Array<LongArray>, q: Array<LongArray>) {
        val a = gf(); val b = gf(); val c = gf(); val d = gf(); val t = gf()
        val e = gf(); val f = gf(); val g = gf(); val h = gf()
        sub(a, p[1], p[0])
        sub(t, q[1], q[0])
        mul(a, a, t)
        add(b, p[0], p[1])
        add(t, q[0], q[1])
        mul(b, b, t)
        mul(c, p[3], q[3])
        mul(c, c, D2)
        mul(d, p[2], q[2])
        add(d, d, d)
        sub(e, b, a)
        sub(f, d, c)
        add(g, d, c)
        add(h, b, a)
        mul(p[0], e, f)
        mul(p[1], h, g)
        mul(p[2], g, f)
        mul(p[3], e, h)
    }

    private fun cswap(p: Array<LongArray>, q: Array<LongArray>, bit: Int) {
        for (i in 0 until 4) swap(p[i], q[i], bit)
    }

    private fun encode(p: Array<LongArray>): ByteArray {
        val tx = gf(); val ty = gf(); val zi = gf()
        invert(zi, p[2])
        mul(tx, p[0], zi)
        mul(ty, p[1], zi)
        val r = pack(ty)
        r[31] = (r[31].toInt() xor (parity(tx) shl 7)).toByte()
        return r
    }

    private fun scalarMult(p: Array<LongArray>, q: Array<LongArray>, s: ByteArray) {
        gf().copyInto(p[0]); gf(1).copyInto(p[1]); gf(1).copyInto(p[2]); gf().copyInto(p[3])
        for (i in 255 downTo 0) {
            val b = ((s[i / 8].toInt() and 0xff) ushr (i and 7)) and 1
            cswap(p, q, b)
            addPoints(q, p)
            addPoints(p, p)
            cswap(p, q, b)
        }
    }

    private fun scalarBase(p: Array<LongArray>, s: ByteArray) {
        val q = point()
        X.copyInto(q[0])
        Y.copyInto(q[1])
        gf(1).copyInto(q[2])
        mul(q[3], X, Y)
        scalarMult(p, q, s)
    }

    private fun modL(r: ByteArray, x: LongArray) {
        var carry: Long
        for (i in 63 downTo 32) {
            carry = 0
            var j = i - 32
            while (j < i - 12) {
                x[j] += carry - 16 * x[i] * L[j - (i - 32)]
                carry = (x[j] + 128) shr 8
                x[j] -= carry shl 8
                j++
            }
            x[j] += carry
            x[i] = 0
        }
        carry = 0
        for (j in 0 until 32) {
            x[j] += carry - (x[31] shr 4) * L[j]
            carry = x[j] shr 8
            x[j] = x[j] and 255
        }
        for (j in 0 until 32) x[j] -= carry * L[j]
        for (i in 0 until 32) {
            x[i + 1] += x[i] shr 8
            r[i] = (x[i] and 255).toByte()
        }
    }

    /** [r] (64 bytes) reduced mod ℓ into its first 32; the rest zeroed. */
    private fun reduce(r: ByteArray) {
        val x = LongArray(64) { r[it].toLong() and 0xff }
        r.fill(0)
        val out = ByteArray(32)
        modL(out, x)
        out.copyInto(r)
    }

    /** Decodes [p] as a point and negates it — TweetNaCl's `unpackneg`. False when it is not on the curve. */
    private fun unpackNeg(r: Array<LongArray>, p: ByteArray): Boolean {
        val t = gf(); val chk = gf(); val num = gf(); val den = gf()
        val den2 = gf(); val den4 = gf(); val den6 = gf()
        gf(1).copyInto(r[2])
        unpack(p).copyInto(r[1])
        sqr(num, r[1])
        mul(den, num, D)
        sub(num, num, r[2])
        add(den, r[2], den)

        sqr(den2, den)
        sqr(den4, den2)
        mul(den6, den4, den2)
        mul(t, den6, num)
        mul(t, t, den)

        pow2523(t, t)
        mul(t, t, num)
        mul(t, t, den)
        mul(t, t, den)
        mul(r[0], t, den)

        sqr(chk, r[0])
        mul(chk, chk, den)
        if (differ(chk, num)) mul(r[0], r[0], I)

        sqr(chk, r[0])
        mul(chk, chk, den)
        if (differ(chk, num)) return false

        if (parity(r[0]) == ((p[31].toInt() and 0xff) ushr 7)) sub(r[0], gf(), r[0])
        mul(r[3], r[0], r[1])
        return true
    }

    /** The 32 little-endian bytes at [offset] of [bytes] are below ℓ (RFC 8032 §5.1.7's check on S). */
    private fun lessThanL(bytes: ByteArray, offset: Int): Boolean {
        for (i in 31 downTo 0) {
            val b = bytes[offset + i].toLong() and 0xff
            if (b < L[i]) return true
            if (b > L[i]) return false
        }
        return false
    }
}
