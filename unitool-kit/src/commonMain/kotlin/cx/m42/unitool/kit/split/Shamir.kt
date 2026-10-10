package cx.m42.unitool.kit.split

import cx.m42.unitool.kit.crypto.secureRandomBytes

/**
 * Shamir's secret sharing over GF(2⁸), one byte of the secret at a time (.ai-notes/done/split 01 §3).
 *
 * Byte j of the secret is f_j(0) for a random polynomial f_j of degree k − 1; part x holds f_j(x)
 * for every j. Any k parts give the polynomials back by Lagrange interpolation, and k − 1 parts are
 * consistent with every possible secret, each equally likely: there is nothing to break, however
 * much computing one has. The field is AES's, `x⁸ + x⁴ + x³ + x + 1`, as in HashiCorp Vault and
 * SLIP-39.
 *
 * Only the arithmetic lives here. Which parts belong together, and how they are written down, is
 * [SplitPart]'s business.
 */
public object Shamir {

    /** The format's limit: x is one byte and 0 is the secret itself. */
    public const val MAX_PARTS: Int = 255

    /**
     * The values at x = 1..[n] of polynomials whose value at 0 is [secret]: any [k] of them give
     * [secret] back through [combine]. The coefficients come from the system's CSPRNG.
     */
    public fun split(secret: ByteArray, k: Int, n: Int): List<ByteArray> = split(secret, k, n, ::secureRandomBytes)

    /** [split] with the coefficients from [random]: the tests sweep them to see each part is uniform. */
    internal fun split(secret: ByteArray, k: Int, n: Int, random: (Int) -> ByteArray): List<ByteArray> {
        require(k in 2..n) { "a threshold from 2 to the number of parts, not $k of $n" }
        require(n <= MAX_PARTS) { "at most $MAX_PARTS parts, not $n" }
        // coefficients[c][j]: coefficient of x^c in the polynomial of byte j; c = 0 is the secret.
        val coefficients = Array(k) { c -> if (c == 0) secret.copyOf() else random(secret.size) }
        try {
            return (1..n).map { x ->
                ByteArray(secret.size) { j ->
                    // Horner from the top coefficient down.
                    var y = 0
                    for (c in k - 1 downTo 0) y = Gf256.mul(y, x) xor (coefficients[c][j].toInt() and 0xff)
                    y.toByte()
                }
            }
        } finally {
            coefficients.forEach { it.fill(0) }
        }
    }

    /** The secret from parts at the distinct non-zero points [xs]; [ys] are their values, all one length. */
    public fun combine(xs: IntArray, ys: List<ByteArray>): ByteArray = evaluate(xs, ys, 0)

    /**
     * The polynomials through ([xs], [ys]) evaluated at [at]. At 0 that is the secret; at another x it
     * is what that part should hold, which is how a part that does not fit the others is found.
     */
    public fun evaluate(xs: IntArray, ys: List<ByteArray>, at: Int): ByteArray {
        require(xs.isNotEmpty() && xs.size == ys.size) { "as many points as values, and at least one" }
        require(xs.all { it in 1..MAX_PARTS } && xs.toSet().size == xs.size) { "points are distinct, from 1 to $MAX_PARTS" }
        require(at in 0..MAX_PARTS) { "a point from 0 to $MAX_PARTS, not $at" }
        val length = ys.first().size
        require(ys.all { it.size == length }) { "every value has the same length" }
        // Lagrange basis at [at]: l_i = Π_{m≠i} (at − x_m) / (x_i − x_m). Subtraction in GF(2⁸) is XOR.
        // The points are public, so the inversions may take any time; only the multiplications below
        // touch the secret bytes, and Gf256.mul takes the same time for every value.
        val basis = IntArray(xs.size) { i ->
            var numerator = 1
            var denominator = 1
            for (m in xs.indices) {
                if (m == i) continue
                numerator = Gf256.mul(numerator, at xor xs[m])
                denominator = Gf256.mul(denominator, xs[i] xor xs[m])
            }
            Gf256.mul(numerator, Gf256.inverse(denominator))
        }
        return ByteArray(length) { j ->
            var y = 0
            for (i in xs.indices) y = y xor Gf256.mul(basis[i], ys[i][j].toInt() and 0xff)
            y.toByte()
        }
    }
}

/** GF(2⁸) with AES's reduction polynomial `0x11B`. Bytes travel as `Int` in 0..255. */
internal object Gf256 {

    /**
     * Shift-and-add with masks instead of branches or tables, so the time does not depend on [a] or
     * [b] (01 §3): eight rounds, each adding a or nothing and doubling a modulo the polynomial.
     */
    fun mul(a: Int, b: Int): Int {
        var x = a
        var y = b
        var product = 0
        repeat(8) {
            product = product xor (x and -(y and 1))
            val carry = -((x ushr 7) and 1)
            x = ((x shl 1) xor (0x1b and carry)) and 0xff
            y = y ushr 1
        }
        return product
    }

    /** a²⁵⁴ = a⁻¹ for a ≠ 0, by square and multiply over the fixed exponent. */
    fun inverse(a: Int): Int {
        require(a in 1..255) { "0 has no inverse" }
        var result = 1
        var power = a
        var exponent = 254
        while (exponent > 0) {
            if (exponent and 1 == 1) result = mul(result, power)
            power = mul(power, power)
            exponent = exponent ushr 1
        }
        return result
    }
}
