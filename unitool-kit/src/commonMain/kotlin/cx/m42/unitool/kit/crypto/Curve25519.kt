package cx.m42.unitool.kit.crypto

import cx.m42.unitool.kit.crypto.Field25519.add
import cx.m42.unitool.kit.crypto.Field25519.gf
import cx.m42.unitool.kit.crypto.Field25519.invert
import cx.m42.unitool.kit.crypto.Field25519.mul
import cx.m42.unitool.kit.crypto.Field25519.pack
import cx.m42.unitool.kit.crypto.Field25519.sub
import cx.m42.unitool.kit.crypto.Field25519.swap
import cx.m42.unitool.kit.crypto.Field25519.unpack

/**
 * X25519 (RFC 7748), plus the one step from an Ed25519 public key to its X25519 twin that age needs
 * to encrypt to an `ssh-ed25519` key — moved here from the 2FA app (D156), unchanged but for the
 * field arithmetic now shared with [Ed25519].
 *
 * Checked in `desktopTest` against the JVM's own X25519 and against RFC 7748's vectors.
 */
public object Curve25519 {

    /** The u-coordinate 9: the curve's base point. */
    public val BASE: ByteArray = ByteArray(32).also { it[0] = 9 }

    /** (A − 2) / 4 for A = 486662, the Montgomery ladder's constant. */
    private val A24 = gf(0xDB41, 1)

    /** `scalar · point`, both 32 bytes little-endian; the scalar is clamped as RFC 7748 says. */
    public fun x25519(scalar: ByteArray, point: ByteArray): ByteArray {
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
        z.fill(0)
        return pack(a)
    }

    /**
     * The birational map from the Edwards curve to the Montgomery one: u = (1 + y) / (1 − y). The
     * x sign bit is dropped, because u does not depend on it.
     */
    public fun edwardsToMontgomery(publicKey: ByteArray): ByteArray {
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
}
