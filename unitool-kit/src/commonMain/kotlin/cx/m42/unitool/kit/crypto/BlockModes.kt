package cx.m42.unitool.kit.crypto

/**
 * A block cipher as the modes below see it: one block in, one block out, both ways. AES now; ARIA,
 * Camellia, SM4, DES and the rest of `openssl enc -list` later (cr-restore D609, stage B).
 */
public interface BlockCipher {
    public val blockSize: Int

    public fun encryptBlock(input: ByteArray, inOffset: Int, output: ByteArray, outOffset: Int)

    public fun decryptBlock(input: ByteArray, inOffset: Int, output: ByteArray, outOffset: Int)

    /** The key schedule, gone. */
    public fun wipe()
}

/**
 * The modes `openssl enc` offers over a block cipher (SP 800-38A), with OpenSSL's choices where the
 * standard leaves one: CFB-1 takes bits most significant first, and CTR counts over the whole block
 * as one big-endian number (`CRYPTO_ctr128_encrypt`).
 *
 * ECB and CBC work on whole blocks and need padding; the rest are streams and take any length.
 */
public enum class BlockMode(public val padded: Boolean, public val needsIv: Boolean) {
    ECB(padded = true, needsIv = false),
    CBC(padded = true, needsIv = true),
    CFB(padded = false, needsIv = true),
    CFB1(padded = false, needsIv = true),
    CFB8(padded = false, needsIv = true),
    OFB(padded = false, needsIv = true),
    CTR(padded = false, needsIv = true),
    ;

    /** [data] encrypted; for ECB and CBC its size must be a whole number of blocks. */
    public fun encrypt(cipher: BlockCipher, iv: ByteArray, data: ByteArray): ByteArray = run(cipher, iv, data, encrypting = true)

    /** [data] decrypted; for ECB and CBC its size must be a whole number of blocks. */
    public fun decrypt(cipher: BlockCipher, iv: ByteArray, data: ByteArray): ByteArray = run(cipher, iv, data, encrypting = false)

    private fun run(cipher: BlockCipher, iv: ByteArray, data: ByteArray, encrypting: Boolean): ByteArray {
        val b = cipher.blockSize
        if (padded) require(data.size % b == 0) { "$name needs whole blocks, not ${data.size} bytes" }
        if (needsIv) require(iv.size == b) { "$name needs a ${b}-byte IV, not ${iv.size}" }
        val out = ByteArray(data.size)
        val register = iv.copyOf()
        val block = ByteArray(b)
        try {
            when (this) {
                ECB -> for (o in data.indices step b) {
                    if (encrypting) cipher.encryptBlock(data, o, out, o) else cipher.decryptBlock(data, o, out, o)
                }
                CBC -> for (o in data.indices step b) {
                    if (encrypting) {
                        for (i in 0 until b) block[i] = (data[o + i].toInt() xor register[i].toInt()).toByte()
                        cipher.encryptBlock(block, 0, out, o)
                        out.copyInto(register, 0, o, o + b)
                    } else {
                        cipher.decryptBlock(data, o, block, 0)
                        for (i in 0 until b) out[o + i] = (block[i].toInt() xor register[i].toInt()).toByte()
                        data.copyInto(register, 0, o, o + b)
                    }
                }
                CFB -> for (o in data.indices step b) {
                    cipher.encryptBlock(register, 0, block, 0)
                    val n = minOf(b, data.size - o)
                    for (i in 0 until n) out[o + i] = (data[o + i].toInt() xor block[i].toInt()).toByte()
                    // The ciphertext is fed back, whichever way we are going.
                    (if (encrypting) out else data).copyInto(register, 0, o, o + n)
                }
                CFB8 -> for (o in data.indices) {
                    cipher.encryptBlock(register, 0, block, 0)
                    out[o] = (data[o].toInt() xor block[0].toInt()).toByte()
                    val fed = if (encrypting) out[o] else data[o]
                    register.copyInto(register, 0, 1, b)
                    register[b - 1] = fed
                }
                CFB1 -> for (bit in 0 until data.size * 8) {
                    cipher.encryptBlock(register, 0, block, 0)
                    val shift = 7 - bit % 8
                    val inBit = (data[bit / 8].toInt() ushr shift) and 1
                    val outBit = inBit xor ((block[0].toInt() ushr 7) and 1)
                    if (outBit == 1) out[bit / 8] = (out[bit / 8].toInt() or (1 shl shift)).toByte()
                    shiftLeftOneBit(register, if (encrypting) outBit else inBit)
                }
                OFB -> for (o in data.indices step b) {
                    cipher.encryptBlock(register, 0, register, 0)
                    val n = minOf(b, data.size - o)
                    for (i in 0 until n) out[o + i] = (data[o + i].toInt() xor register[i].toInt()).toByte()
                }
                CTR -> for (o in data.indices step b) {
                    cipher.encryptBlock(register, 0, block, 0)
                    val n = minOf(b, data.size - o)
                    for (i in 0 until n) out[o + i] = (data[o + i].toInt() xor block[i].toInt()).toByte()
                    for (i in b - 1 downTo 0) {
                        register[i] = (register[i] + 1).toByte()
                        if (register[i] != 0.toByte()) break
                    }
                }
            }
        } finally {
            register.fill(0)
            block.fill(0)
        }
        return out
    }

    private fun shiftLeftOneBit(register: ByteArray, bit: Int) {
        for (i in register.indices) {
            val next = if (i + 1 < register.size) (register[i + 1].toInt() ushr 7) and 1 else bit
            register[i] = ((register[i].toInt() shl 1) or next).toByte()
        }
    }
}

/** PKCS#7 (RFC 5652 §6.3): what `openssl enc` pads ECB and CBC with unless told `-nopad`. */
public object Pkcs7 {

    public fun pad(data: ByteArray, blockSize: Int): ByteArray {
        val n = blockSize - data.size % blockSize
        return data.copyOf(data.size + n).also { it.fill(n.toByte(), data.size) }
    }

    /**
     * [data] without its padding, or null when there is none to take off — with a wrong password,
     * almost always. Every byte of the last block is looked at whatever it holds.
     */
    public fun unpad(data: ByteArray, blockSize: Int): ByteArray? {
        if (data.isEmpty() || data.size % blockSize != 0) return null
        val n = data.last().toInt() and 0xff
        var bad = if (n == 0 || n > blockSize) 1 else 0
        for (i in 1..blockSize) {
            val inPad = if (i <= n) 1 else 0
            bad = bad or (inPad and (if ((data[data.size - i].toInt() and 0xff) != n) 1 else 0))
        }
        return if (bad != 0) null else data.copyOf(data.size - n)
    }
}
