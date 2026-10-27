package cx.m42.unitool.kit.crypto

/**
 * PBKDF2 (RFC 8018 §5.2) over any [Hash]: what `openssl enc -pbkdf2` derives the key and IV with
 * (cr-restore D610), and what scrypt is built on.
 *
 * The HMAC key is the password for every one of the [iterations], so its padded inner and outer
 * blocks are made once rather than per round. Each round still hashes them in full; a saved
 * midstate would halve that, and is worth doing if 600 000 rounds prove slow on a phone (D616).
 */
public fun pbkdf2(hash: Hash, password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
    require(iterations >= 1) { "PBKDF2 needs at least one iteration" }
    require(length >= 0)
    val mac = Hmac(hash, password)
    val out = ByteArray(length)
    var filled = 0
    var index = 1
    try {
        while (filled < length) {
            val counter = byteArrayOf((index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte())
            var u = mac.of(salt + counter)
            val t = u.copyOf()
            for (round in 2..iterations) {
                val next = mac.of(u)
                u.fill(0)
                u = next
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            u.fill(0)
            val n = minOf(t.size, length - filled)
            t.copyInto(out, filled, 0, n)
            t.fill(0)
            filled += n
            index++
        }
    } finally {
        mac.wipe()
    }
    return out
}

/**
 * OpenSSL's `EVP_BytesToKey` with a count of one — what `openssl enc` without `-pbkdf2` does, and
 * the reason it warns "deprecated key derivation": D₁ = H(password ‖ salt), Dᵢ = H(Dᵢ₋₁ ‖ password
 * ‖ salt), concatenated until there is enough. An empty [salt] is `-nosalt`.
 */
public fun evpBytesToKey(hash: Hash, password: ByteArray, salt: ByteArray, length: Int): ByteArray {
    val out = ByteArray(length)
    var filled = 0
    var previous = ByteArray(0)
    while (filled < length) {
        val next = hash.digest(previous + password + salt)
        previous.fill(0)
        previous = next
        val n = minOf(next.size, length - filled)
        next.copyInto(out, filled, 0, n)
        filled += n
    }
    previous.fill(0)
    return out
}

/** HMAC with its key's padded blocks made once (RFC 2104), for the loops above. */
private class Hmac(private val hash: Hash, key: ByteArray) {
    private val inner: ByteArray
    private val outer: ByteArray

    init {
        val block = hash.blockSize
        val shortKey = if (key.size > block) hash.digest(key) else key
        val padded = shortKey.copyOf(block)
        inner = ByteArray(block) { (padded[it].toInt() xor 0x36).toByte() }
        outer = ByteArray(block) { (padded[it].toInt() xor 0x5c).toByte() }
        padded.fill(0)
        if (shortKey !== key) shortKey.fill(0)
    }

    fun of(message: ByteArray): ByteArray {
        val innerInput = inner + message
        val innerHash = hash.digest(innerInput)
        innerInput.fill(0)
        val outerInput = outer + innerHash
        innerHash.fill(0)
        return hash.digest(outerInput).also { outerInput.fill(0) }
    }

    fun wipe() {
        inner.fill(0)
        outer.fill(0)
    }
}
