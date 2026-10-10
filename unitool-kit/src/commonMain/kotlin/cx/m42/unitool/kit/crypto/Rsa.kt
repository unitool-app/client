package cx.m42.unitool.kit.crypto

/**
 * RSA as SSH and age use it, by hand (02 §10): OAEP with SHA-256 both ways (age's `ssh-rsa`
 * stanza), PKCS #1 v1.5 signatures with SHA-256 and SHA-512 (`rsa-sha2-256`, `rsa-sha2-512`), and
 * the private operation for a key the keyring holds at level 2 or 3. `commonMain` has no
 * `BigInteger` and wasm has no JCA, so the one big-number operation — `x^y mod n` — is [Montgomery].
 *
 * SHA-1 signatures are not made here at all: `ssh-rsa` with SHA-1 is refused (D144).
 */
public object Rsa {

    private const val HASH_SIZE = 32

    /** DigestInfo prefixes of RFC 8017 §9.2, note 1. */
    private val SHA256_INFO = hex("3031300d060960864801650304020105000420")
    private val SHA512_INFO = hex("3051300d060960864801650304020305000440")

    /** RSAES-OAEP-ENCRYPT, SHA-256 and MGF1-SHA-256. [seed] is OAEP's 32 random bytes — a parameter so a test can pin it. */
    public fun oaepEncrypt(modulus: ByteArray, exponent: ByteArray, message: ByteArray, label: ByteArray, seed: ByteArray): ByteArray {
        val k = stripped(modulus).size
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

    /**
     * RSAES-OAEP-DECRYPT's decoding half (RFC 8017 §7.1.2 step 3), for an [em] the private operation
     * produced — in this module or in the Keystore (P4). Null for anything malformed, with every
     * check made before the answer so that which one failed is not timed out of it.
     */
    public fun oaepDecode(em: ByteArray, label: ByteArray): ByteArray? {
        val k = em.size
        if (k < 2 * HASH_SIZE + 2) return null
        val maskedSeed = em.copyOfRange(1, 1 + HASH_SIZE)
        val maskedDb = em.copyOfRange(1 + HASH_SIZE, k)
        val seedMask = mgf1(maskedDb, HASH_SIZE)
        val seed = ByteArray(HASH_SIZE) { (maskedSeed[it].toInt() xor seedMask[it].toInt()).toByte() }
        val dbMask = mgf1(seed, maskedDb.size)
        val db = ByteArray(maskedDb.size) { (maskedDb[it].toInt() xor dbMask[it].toInt()).toByte() }
        seed.fill(0)
        val lHash = Sha256.digest(label)

        var bad = em[0].toInt() and 0xff
        for (i in 0 until HASH_SIZE) bad = bad or (db[i].toInt() xor lHash[i].toInt())
        // The first 0x01 after the hash, through zeros only; found without leaving the loop early.
        var index = -1
        var inPadding = 1
        for (i in HASH_SIZE until db.size) {
            val b = db[i].toInt() and 0xff
            val isOne = if (b == 1) 1 else 0
            val isZero = if (b == 0) 1 else 0
            if (inPadding == 1 && isOne == 1) index = i
            bad = bad or (inPadding and (1 - isOne) and (1 - isZero))
            inPadding = inPadding and isZero
        }
        if (index < 0) bad = 1
        val out = if (bad == 0) db.copyOfRange(index + 1, db.size) else null
        db.fill(0)
        return out
    }

    /** EMSA-PKCS1-v1_5 for [hash] of [message], `k` bytes long. */
    public fun pkcs1Encode(hash: Hash, message: ByteArray, k: Int): ByteArray {
        val info = when (hash) {
            Hash.SHA256 -> SHA256_INFO
            Hash.SHA512 -> SHA512_INFO
            else -> throw IllegalArgumentException("no RSA signatures with $hash")
        }
        val t = info + hash.digest(message)
        require(k >= t.size + 11) { "key too short for $hash" }
        val em = ByteArray(k)
        em[1] = 1
        for (i in 2 until k - t.size - 1) em[i] = 0xff.toByte()
        t.copyInto(em, k - t.size)
        return em
    }

    /** RSASSA-PKCS1-v1_5-VERIFY. */
    public fun verify(modulus: ByteArray, exponent: ByteArray, hash: Hash, message: ByteArray, signature: ByteArray): Boolean {
        val n = Montgomery(modulus)
        if (signature.size != n.length || !below(signature, stripped(modulus))) return false
        val em = n.modPow(signature, exponent)
        return runCatching { pkcs1Encode(hash, message, n.length) }.getOrNull()?.contentEquals(em) == true
    }

    /** `x^d mod n`: the private operation, for signing ([pkcs1Encode] first) and for decrypting. */
    public fun private(modulus: ByteArray, privateExponent: ByteArray, x: ByteArray): ByteArray {
        val n = Montgomery(modulus)
        val input = pad(x, n.length)
        require(below(input, stripped(modulus))) { "input not below the modulus" }
        return n.modPow(input, privateExponent).also { input.fill(0) }
    }

    public fun sign(modulus: ByteArray, privateExponent: ByteArray, hash: Hash, message: ByteArray): ByteArray {
        val k = stripped(modulus).size
        val em = pkcs1Encode(hash, message, k)
        return private(modulus, privateExponent, em).also { em.fill(0) }
    }

    public fun bits(modulus: ByteArray): Int {
        val n = stripped(modulus)
        return if (n.isEmpty()) 0 else (n.size - 1) * 8 + (32 - (n[0].toInt() and 0xff).countLeadingZeroBits())
    }

    /** [x] as exactly [length] bytes: left-padded with zeros, or its leading zeros dropped — never a non-zero byte. */
    private fun pad(x: ByteArray, length: Int): ByteArray {
        if (x.size <= length) return ByteArray(length - x.size) + x
        require((0 until x.size - length).all { x[it] == 0.toByte() }) { "input longer than the modulus" }
        return x.copyOfRange(x.size - length, x.size)
    }

    private fun stripped(bytes: ByteArray): ByteArray = bytes.dropWhile { it == 0.toByte() }.toByteArray()

    /** Big-endian [x] (same length as [n]) is below [n]. */
    private fun below(x: ByteArray, n: ByteArray): Boolean {
        val a = stripped(x)
        if (a.size != n.size) return a.size < n.size
        for (i in a.indices) {
            val p = a[i].toInt() and 0xff
            val q = n[i].toInt() and 0xff
            if (p != q) return p < q
        }
        return false
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

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

/**
 * Arithmetic modulo an odd [modulus] in Montgomery form, on little-endian 32-bit limbs.
 *
 * `ULong` for the inner products: a limb times a limb plus two carries is at most 2^64 − 1, which
 * an unsigned 64-bit value holds exactly and a signed one does not.
 */
public class Montgomery(modulus: ByteArray) {

    /** Bytes of the modulus without its sign byte: the length of every result. */
    public val length: Int get() = size


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
    public fun modPow(base: ByteArray, exponent: ByteArray): ByteArray {
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
