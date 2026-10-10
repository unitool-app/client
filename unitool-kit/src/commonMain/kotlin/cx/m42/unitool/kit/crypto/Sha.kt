package cx.m42.unitool.kit.crypto

/**
 * SHA-1, SHA-256, SHA-384, SHA-512, HMAC and HKDF, by hand. They came here from `services/ssh` so the
 * phone and the console tool share one copy (.ai-notes/done/cli 02 §3.1). The 2FA app still has its own
 * copy for TOTP until 02 §3.2 moves it here too.
 *
 * `commonMain` has no `MessageDigest` on wasm. Each function is checked against the JDK and the
 * NIST/RFC vectors in `desktopTest`.
 */
public enum class Hash(public val blockSize: Int, public val size: Int) {
    SHA1(64, 20),
    SHA256(64, 32),
    SHA384(128, 48),
    SHA512(128, 64),
    ;

    public fun digest(message: ByteArray): ByteArray = when (this) {
        SHA1 -> Sha1.digest(message)
        SHA256 -> Sha256.digest(message)
        SHA384 -> Sha512.digest384(message)
        SHA512 -> Sha512.digest(message)
    }
}

/** RFC 2104. */
public fun hmac(hash: Hash, key: ByteArray, message: ByteArray): ByteArray {
    val block = hash.blockSize
    val shortKey = if (key.size > block) hash.digest(key) else key
    val padded = shortKey.copyOf(block)
    val inner = ByteArray(block) { (padded[it].toInt() xor 0x36).toByte() }
    val outer = ByteArray(block) { (padded[it].toInt() xor 0x5c).toByte() }
    padded.fill(0)
    val innerHash = hash.digest(inner + message)
    inner.fill(0)
    return hash.digest(outer + innerHash).also { outer.fill(0) }
}

/** RFC 5869 over SHA-256, Extract and Expand in one call. An empty [salt] is the RFC's "not provided". */
public fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val prk = hkdfExtract(salt, ikm)
    return hkdfExpand(prk, info, length).also { prk.fill(0) }
}

/** RFC 5869 §2.2 over SHA-256: the pseudorandom key, 32 bytes. Separate so one PRK can feed several [hkdfExpand]s. */
public fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(Hash.SHA256, salt, ikm)

/** RFC 5869 §2.3 over SHA-256: [length] bytes, at most 255 × 32, from [prk] for the purpose [info] names. */
public fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length in 0..255 * 32) { "HKDF-Expand gives at most 8160 bytes, not $length" }
    val out = ByteArray(length)
    var block = ByteArray(0)
    var filled = 0
    var counter = 1
    while (filled < length) {
        block = hmac(Hash.SHA256, prk, block + info + byteArrayOf(counter.toByte()))
        val n = minOf(block.size, length - filled)
        block.copyInto(out, filled, 0, n)
        filled += n
        counter++
    }
    block.fill(0)
    return out
}

/**
 * The Merkle–Damgård padding all three share: a 1 bit, zeros, and the length in bits at the end of
 * the last block. SHA-512 has a 16-byte length field of which only the low eight can ever be set.
 */
private fun pad(message: ByteArray, block: Int, lengthBytes: Int): ByteArray {
    val bitLength = message.size.toLong() * 8
    var size = message.size + 1
    while (size % block != block - lengthBytes) size++
    val out = message.copyOf(size + lengthBytes)
    out[message.size] = 0x80.toByte()
    for (i in 0 until 8) out[out.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
    return out
}

private fun ByteArray.intAt(i: Int): Int =
    ((this[i].toInt() and 0xff) shl 24) or ((this[i + 1].toInt() and 0xff) shl 16) or
        ((this[i + 2].toInt() and 0xff) shl 8) or (this[i + 3].toInt() and 0xff)

private fun ByteArray.longAt(i: Int): Long =
    (intAt(i).toLong() shl 32) or (intAt(i + 4).toLong() and 0xffffffffL)

private fun IntArray.toBytes(): ByteArray = ByteArray(size * 4) { (this[it / 4] ushr (24 - 8 * (it % 4))).toByte() }

private fun LongArray.toBytes(): ByteArray = ByteArray(size * 8) { (this[it / 8] ushr (56 - 8 * (it % 8))).toByte() }

/** Hex words in a string rather than 64 literals, because half of them do not fit a signed `Int`. */
private fun words32(hex: String): IntArray = hex.split(' ', '\n').filter { it.isNotBlank() }
    .map { it.toUInt(16).toInt() }.toIntArray()

private fun words64(hex: String): LongArray = hex.split(' ', '\n').filter { it.isNotBlank() }
    .map { it.toULong(16).toLong() }.toLongArray()

public object Sha1 {
    public fun digest(message: ByteArray): ByteArray {
        val h = words32("67452301 efcdab89 98badcfe 10325476 c3d2e1f0")
        val data = pad(message, 64, 8)
        val w = IntArray(80)
        for (chunk in data.indices step 64) {
            for (i in 0 until 16) w[i] = data.intAt(chunk + 4 * i)
            for (i in 16 until 80) w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]; var e = h[4]
            for (i in 0 until 80) {
                val (f, k) = when (i) {
                    in 0..19 -> ((b and c) or (b.inv() and d)) to 0x5a827999
                    in 20..39 -> (b xor c xor d) to 0x6ed9eba1
                    in 40..59 -> ((b and c) or (b and d) or (c and d)) to 0x8f1bbcdc.toInt()
                    else -> (b xor c xor d) to 0xca62c1d6.toInt()
                }
                val t = a.rotateLeft(5) + f + e + k + w[i]
                e = d; d = c; c = b.rotateLeft(30); b = a; a = t
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e
        }
        return h.toBytes()
    }
}

public object Sha256 {
    private val K = words32(
        """
        428a2f98 71374491 b5c0fbcf e9b5dba5 3956c25b 59f111f1 923f82a4 ab1c5ed5
        d807aa98 12835b01 243185be 550c7dc3 72be5d74 80deb1fe 9bdc06a7 c19bf174
        e49b69c1 efbe4786 0fc19dc6 240ca1cc 2de92c6f 4a7484aa 5cb0a9dc 76f988da
        983e5152 a831c66d b00327c8 bf597fc7 c6e00bf3 d5a79147 06ca6351 14292967
        27b70a85 2e1b2138 4d2c6dfc 53380d13 650a7354 766a0abb 81c2c92e 92722c85
        a2bfe8a1 a81a664b c24b8b70 c76c51a3 d192e819 d6990624 f40e3585 106aa070
        19a4c116 1e376c08 2748774c 34b0bcb5 391c0cb3 4ed8aa4a 5b9cca4f 682e6ff3
        748f82ee 78a5636f 84c87814 8cc70208 90befffa a4506ceb bef9a3f7 c67178f2
        """,
    )

    public fun digest(message: ByteArray): ByteArray {
        val h = words32("6a09e667 bb67ae85 3c6ef372 a54ff53a 510e527f 9b05688c 1f83d9ab 5be0cd19")
        val data = pad(message, 64, 8)
        val w = IntArray(64)
        for (chunk in data.indices step 64) {
            for (i in 0 until 16) w[i] = data.intAt(chunk + 4 * i)
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        return h.toBytes()
    }
}

public object Sha512 {
    private val IV512 = words64(
        "6a09e667f3bcc908 bb67ae8584caa73b 3c6ef372fe94f82b a54ff53a5f1d36f1 " +
            "510e527fade682d1 9b05688c2b3e6c1f 1f83d9abfb41bd6b 5be0cd19137e2179",
    )

    /** SHA-384 is SHA-512 from another starting point, cut to 48 bytes (FIPS 180-4 §5.3.4). */
    private val IV384 = words64(
        "cbbb9d5dc1059ed8 629a292a367cd507 9159015a3070dd17 152fecd8f70e5939 " +
            "67332667ffc00b31 8eb44a8768581511 db0c2e0d64f98fa7 47b5481dbefa4fa4",
    )

    public fun digest(message: ByteArray): ByteArray = compress(message, IV512.copyOf())

    public fun digest384(message: ByteArray): ByteArray = compress(message, IV384.copyOf()).copyOf(48)

    private val K = words64(
        """
        428a2f98d728ae22 7137449123ef65cd b5c0fbcfec4d3b2f e9b5dba58189dbbc
        3956c25bf348b538 59f111f1b605d019 923f82a4af194f9b ab1c5ed5da6d8118
        d807aa98a3030242 12835b0145706fbe 243185be4ee4b28c 550c7dc3d5ffb4e2
        72be5d74f27b896f 80deb1fe3b1696b1 9bdc06a725c71235 c19bf174cf692694
        e49b69c19ef14ad2 efbe4786384f25e3 0fc19dc68b8cd5b5 240ca1cc77ac9c65
        2de92c6f592b0275 4a7484aa6ea6e483 5cb0a9dcbd41fbd4 76f988da831153b5
        983e5152ee66dfab a831c66d2db43210 b00327c898fb213f bf597fc7beef0ee4
        c6e00bf33da88fc2 d5a79147930aa725 06ca6351e003826f 142929670a0e6e70
        27b70a8546d22ffc 2e1b21385c26c926 4d2c6dfc5ac42aed 53380d139d95b3df
        650a73548baf63de 766a0abb3c77b2a8 81c2c92e47edaee6 92722c851482353b
        a2bfe8a14cf10364 a81a664bbc423001 c24b8b70d0f89791 c76c51a30654be30
        d192e819d6ef5218 d69906245565a910 f40e35855771202a 106aa07032bbd1b8
        19a4c116b8d2d0c8 1e376c085141ab53 2748774cdf8eeb99 34b0bcb5e19b48a8
        391c0cb3c5c95a63 4ed8aa4ae3418acb 5b9cca4f7763e373 682e6ff3d6b2b8a3
        748f82ee5defb2fc 78a5636f43172f60 84c87814a1f0ab72 8cc702081a6439ec
        90befffa23631e28 a4506cebde82bde9 bef9a3f7b2c67915 c67178f2e372532b
        ca273eceea26619c d186b8c721c0c207 eada7dd6cde0eb1e f57d4f7fee6ed178
        06f067aa72176fba 0a637dc5a2c898a6 113f9804bef90dae 1b710b35131c471b
        28db77f523047d84 32caab7b40c72493 3c9ebe0a15c9bebc 431d67c49c100d4c
        4cc5d4becb3e42b6 597f299cfc657e2a 5fcb6fab3ad6faec 6c44198c4a475817
        """,
    )

    private fun compress(message: ByteArray, h: LongArray): ByteArray {
        val data = pad(message, 128, 16)
        val w = LongArray(80)
        for (chunk in data.indices step 128) {
            for (i in 0 until 16) w[i] = data.longAt(chunk + 8 * i)
            for (i in 16 until 80) {
                val s0 = w[i - 15].rotateRight(1) xor w[i - 15].rotateRight(8) xor (w[i - 15] ushr 7)
                val s1 = w[i - 2].rotateRight(19) xor w[i - 2].rotateRight(61) xor (w[i - 2] ushr 6)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (i in 0 until 80) {
                val s1 = e.rotateRight(14) xor e.rotateRight(18) xor e.rotateRight(41)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[i] + w[i]
                val s0 = a.rotateRight(28) xor a.rotateRight(34) xor a.rotateRight(39)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        return h.toBytes()
    }
}
