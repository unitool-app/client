package cx.m42.apps.twofa

/**
 * RSAES-OAEP (RFC 8017 §7.1.1) with SHA-256 and MGF1-SHA-256: what age wraps its file key in for
 * an `ssh-rsa` key.
 *
 * Encryption only, so the one big-integer operation needed is `m^e mod n` with a public `e` — a
 * few dozen Montgomery multiplications, written here rather than borrowed, because `commonMain`
 * has no `BigInteger` and the wasm target has no JCA.
 */
internal object RsaOaep {

    private const val HASH_SIZE = 32

    /**
     * [modulus] and [exponent] big-endian, as an SSH key carries them (a leading zero byte is
     * fine). [seed] is OAEP's 32 random bytes — a parameter so a test can pin it.
     */
    fun encrypt(modulus: ByteArray, exponent: ByteArray, message: ByteArray, label: ByteArray, seed: ByteArray): ByteArray {
        val k = modulus.dropWhile { it == 0.toByte() }.size
        require(message.size <= k - 2 * HASH_SIZE - 2) { "message too long for a ${k * 8}-bit key" }
        require(seed.size == HASH_SIZE)

        val db = ByteArray(k - HASH_SIZE - 1)
        Sha256.digest(label).copyInto(db, 0)
        db[db.size - message.size - 1] = 1
        message.copyInto(db, db.size - message.size)
        val dbMask = mgf1(seed, db.size)
        for (i in db.indices) db[i] = (db[i].toInt() xor dbMask[i].toInt()).toByte()
        val seedMask = mgf1(db, HASH_SIZE)
        val maskedSeed = ByteArray(HASH_SIZE) { (seed[it].toInt() xor seedMask[it].toInt()).toByte() }

        // EM = 0x00 ‖ maskedSeed ‖ maskedDB: the leading zero is what keeps it below n.
        val em = ByteArray(k)
        maskedSeed.copyInto(em, 1)
        db.copyInto(em, 1 + HASH_SIZE)
        return Montgomery(modulus).modPow(em, exponent)
    }

    private fun mgf1(seed: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var filled = 0
        var counter = 0
        while (filled < length) {
            val c = byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte())
            val block = Sha256.digest(seed + c)
            val n = minOf(block.size, length - filled)
            block.copyInto(out, filled, 0, n)
            filled += n
            counter++
        }
        return out
    }
}

/**
 * Arithmetic modulo an odd [modulus] in Montgomery form, on little-endian 32-bit limbs.
 *
 * `ULong` for the inner products: a limb times a limb plus two carries is at most 2^64 − 1, which
 * an unsigned 64-bit value holds exactly and a signed one does not.
 */
internal class Montgomery(modulus: ByteArray) {

    /** Without the sign byte an mpint or `BigInteger.toByteArray()` puts in front. */
    private val stripped: ByteArray = modulus.dropWhile { it == 0.toByte() }.toByteArray()
    private val size: Int = stripped.size
    private val n: IntArray = limbs(stripped)
    private val s: Int = n.size

    /** −n⁻¹ mod 2^32, by Newton's iteration: each step doubles the correct low bits. */
    private val nPrime: Int = run {
        var x = n[0]
        repeat(5) { x *= 2 - n[0] * x }
        -x
    }

    /** R² mod n for R = 2^(32s): what moves a number into Montgomery form. */
    private val r2: IntArray = run {
        var x = IntArray(s).also { it[0] = 1 }
        repeat(64 * s) { x = doubled(x) }
        x
    }

    init {
        require(n[0] and 1 == 1) { "modulus must be odd" }
    }

    /** `base^exponent mod n`, [base] and the result big-endian and as long as the modulus. */
    fun modPow(base: ByteArray, exponent: ByteArray): ByteArray {
        val one = IntArray(s).also { it[0] = 1 }
        val b = multiply(limbs(base), r2)
        var acc = multiply(one, r2)
        for (byte in exponent) {
            for (bit in 7 downTo 0) {
                acc = multiply(acc, acc)
                if ((byte.toInt() ushr bit) and 1 == 1) acc = multiply(acc, b)
            }
        }
        return bytes(multiply(acc, one))
    }

    /** a · b · R⁻¹ mod n — CIOS, from Koç, Acar and Kaliski. */
    private fun multiply(a: IntArray, b: IntArray): IntArray {
        val t = IntArray(s + 2)
        val nPrimeU = nPrime.toUInt()
        for (i in 0 until s) {
            var c = 0uL
            val ai = a[i].u()
            for (j in 0 until s) {
                val x = t[j].u() + ai * b[j].u() + c
                t[j] = x.toInt()
                c = x shr 32
            }
            var x = t[s].u() + c
            t[s] = x.toInt()
            t[s + 1] = (x shr 32).toInt()

            val m = (t[0].toUInt() * nPrimeU).toULong()
            x = t[0].u() + m * n[0].u()
            c = x shr 32
            for (j in 1 until s) {
                x = t[j].u() + m * n[j].u() + c
                t[j - 1] = x.toInt()
                c = x shr 32
            }
            x = t[s].u() + c
            t[s - 1] = x.toInt()
            t[s] = t[s + 1] + (x shr 32).toInt()
        }
        return reduced(t)
    }

    /** 2x mod n for x < n. */
    private fun doubled(x: IntArray): IntArray {
        val t = IntArray(s + 1)
        var carry = 0
        for (i in 0 until s) {
            t[i] = (x[i] shl 1) or carry
            carry = x[i] ushr 31
        }
        t[s] = carry
        return reduced(t)
    }

    /** [t] (s + 1 limbs, below 2n) brought under n with at most one subtraction. */
    private fun reduced(t: IntArray): IntArray {
        if (!atLeastN(t)) return t.copyOf(s)
        val out = IntArray(s)
        var borrow = 0L
        for (i in 0 until s) {
            val d = (t[i].toLong() and 0xffffffffL) - (n[i].toLong() and 0xffffffffL) - borrow
            out[i] = d.toInt()
            borrow = if (d < 0) 1 else 0
        }
        return out
    }

    private fun atLeastN(t: IntArray): Boolean {
        if (t[s] != 0) return true
        for (i in s - 1 downTo 0) {
            if (t[i] != n[i]) return t[i].toUInt() > n[i].toUInt()
        }
        return true
    }

    private fun limbs(bigEndian: ByteArray): IntArray {
        val out = IntArray((size + 3) / 4)
        for (i in bigEndian.indices) {
            val position = bigEndian.size - 1 - i
            out[position / 4] = out[position / 4] or ((bigEndian[i].toInt() and 0xff) shl (8 * (position % 4)))
        }
        return out
    }

    private fun bytes(limbs: IntArray): ByteArray = ByteArray(size) { i ->
        val position = size - 1 - i
        (limbs[position / 4] ushr (8 * (position % 4))).toByte()
    }

    private fun Int.u(): ULong = toUInt().toULong()
}
