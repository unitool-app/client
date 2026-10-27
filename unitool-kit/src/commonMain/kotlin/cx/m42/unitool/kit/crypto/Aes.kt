package cx.m42.unitool.kit.crypto

/**
 * AES-128/192/256 (FIPS 197): the encrypting direction for the ciphers `ssh-keygen` puts private
 * keys under, `aes*-ctr` and `aes256-gcm@openssh.com` (02 §8), and since cr-restore (D610) the
 * decrypting one too, for `openssl enc`'s CBC and ECB.
 *
 * Byte-oriented and table-driven. That is a timing channel in principle; here the operation runs
 * once per import or unlock, on the device, over a file the person handed over (04 step 2), and
 * there is nobody on the other side of a network to time it. The S-box is computed, not typed, so
 * there is no table of 256 constants to get one digit of wrong.
 *
 * Checked against the JDK's `Cipher` and the NIST SP 800-38A / GCM vectors in `desktopTest`.
 */
public class Aes(key: ByteArray) : BlockCipher {

    override val blockSize: Int get() = 16

    private val rounds: Int
    private val w: IntArray

    init {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "AES key of ${key.size} bytes" }
        val nk = key.size / 4
        rounds = nk + 6
        w = IntArray(4 * (rounds + 1))
        for (i in 0 until nk) w[i] = key.intBe(4 * i)
        var rcon = 1
        for (i in nk until w.size) {
            var temp = w[i - 1]
            if (i % nk == 0) {
                temp = subWord((temp shl 8) or (temp ushr 24)) xor (rcon shl 24)
                rcon = xtime(rcon)
            } else if (nk > 6 && i % nk == 4) {
                temp = subWord(temp)
            }
            w[i] = w[i - nk] xor temp
        }
    }

    /** One 16-byte block from [input] at [inOffset] to [output] at [outOffset]. */
    override fun encryptBlock(input: ByteArray, inOffset: Int, output: ByteArray, outOffset: Int) {
        val s = IntArray(16) { input[inOffset + it].toInt() and 0xff }
        addRoundKey(s, 0)
        for (round in 1 until rounds) {
            subBytes(s)
            shiftRows(s)
            mixColumns(s)
            addRoundKey(s, round)
        }
        subBytes(s)
        shiftRows(s)
        addRoundKey(s, rounds)
        for (i in 0 until 16) output[outOffset + i] = s[i].toByte()
    }

    /** FIPS 197 §5.3, the inverse cipher: the rounds backwards with the inverse steps. */
    override fun decryptBlock(input: ByteArray, inOffset: Int, output: ByteArray, outOffset: Int) {
        val s = IntArray(16) { input[inOffset + it].toInt() and 0xff }
        addRoundKey(s, rounds)
        for (round in rounds - 1 downTo 1) {
            invShiftRows(s)
            invSubBytes(s)
            addRoundKey(s, round)
            invMixColumns(s)
        }
        invShiftRows(s)
        invSubBytes(s)
        addRoundKey(s, 0)
        for (i in 0 until 16) output[outOffset + i] = s[i].toByte()
    }

    /** The key schedule, gone. */
    override fun wipe(): Unit = w.fill(0)

    private fun invSubBytes(s: IntArray) {
        for (i in 0 until 16) s[i] = INV_SBOX[s[i]]
    }

    private fun invShiftRows(s: IntArray) {
        for (r in 1 until 4) {
            val row = IntArray(4) { s[r + 4 * it] }
            for (c in 0 until 4) s[r + 4 * ((c + r) % 4)] = row[c]
        }
    }

    /** Each column times {0b}x³ + {0d}x² + {09}x + {0e} (§5.3.3). */
    private fun invMixColumns(s: IntArray) {
        for (c in 0 until 4) {
            val a0 = s[4 * c]; val a1 = s[4 * c + 1]; val a2 = s[4 * c + 2]; val a3 = s[4 * c + 3]
            s[4 * c] = mul(a0, 14) xor mul(a1, 11) xor mul(a2, 13) xor mul(a3, 9)
            s[4 * c + 1] = mul(a0, 9) xor mul(a1, 14) xor mul(a2, 11) xor mul(a3, 13)
            s[4 * c + 2] = mul(a0, 13) xor mul(a1, 9) xor mul(a2, 14) xor mul(a3, 11)
            s[4 * c + 3] = mul(a0, 11) xor mul(a1, 13) xor mul(a2, 9) xor mul(a3, 14)
        }
    }

    private fun addRoundKey(s: IntArray, round: Int) {
        for (c in 0 until 4) {
            val k = w[4 * round + c]
            s[4 * c] = s[4 * c] xor (k ushr 24)
            s[4 * c + 1] = s[4 * c + 1] xor ((k ushr 16) and 0xff)
            s[4 * c + 2] = s[4 * c + 2] xor ((k ushr 8) and 0xff)
            s[4 * c + 3] = s[4 * c + 3] xor (k and 0xff)
        }
    }

    private fun subBytes(s: IntArray) {
        for (i in 0 until 16) s[i] = SBOX[s[i]]
    }

    /** Row r (bytes r, r+4, r+8, r+12 of the column-major state) rotated left by r. */
    private fun shiftRows(s: IntArray) {
        for (r in 1 until 4) {
            val row = IntArray(4) { s[r + 4 * it] }
            for (c in 0 until 4) s[r + 4 * c] = row[(c + r) % 4]
        }
    }

    private fun mixColumns(s: IntArray) {
        for (c in 0 until 4) {
            val a0 = s[4 * c]; val a1 = s[4 * c + 1]; val a2 = s[4 * c + 2]; val a3 = s[4 * c + 3]
            val all = a0 xor a1 xor a2 xor a3
            s[4 * c] = a0 xor all xor xtime(a0 xor a1)
            s[4 * c + 1] = a1 xor all xor xtime(a1 xor a2)
            s[4 * c + 2] = a2 xor all xor xtime(a2 xor a3)
            s[4 * c + 3] = a3 xor all xor xtime(a3 xor a0)
        }
    }

    private fun subWord(x: Int): Int =
        (SBOX[x ushr 24] shl 24) or (SBOX[(x ushr 16) and 0xff] shl 16) or (SBOX[(x ushr 8) and 0xff] shl 8) or SBOX[x and 0xff]

    private companion object {
        /** FIPS 197 §5.1.1, generated: walk the multiplicative group by 3 and its inverse by 1/3 together. */
        private val SBOX: IntArray = IntArray(256).also { box ->
            var p = 1
            var q = 1
            do {
                p = (p xor (p shl 1) xor (if (p and 0x80 != 0) 0x1b else 0)) and 0xff
                q = q xor (q shl 1)
                q = q xor (q shl 2)
                q = q xor (q shl 4)
                q = q and 0xff
                if (q and 0x80 != 0) q = q xor 0x09
                val x = q xor rotl8(q, 1) xor rotl8(q, 2) xor rotl8(q, 3) xor rotl8(q, 4)
                box[p] = (x xor 0x63) and 0xff
            } while (p != 1)
            box[0] = 0x63
        }

        private val INV_SBOX: IntArray = IntArray(256).also { inv -> for (i in 0 until 256) inv[SBOX[i]] = i }

        /** [a] · [b] in GF(2⁸) with AES's polynomial, [b] small. */
        private fun mul(a: Int, b: Int): Int {
            var x = a
            var y = b
            var r = 0
            while (y != 0) {
                if (y and 1 != 0) r = r xor x
                x = xtime(x)
                y = y ushr 1
            }
            return r
        }

        private fun rotl8(x: Int, n: Int): Int = ((x shl n) or (x ushr (8 - n))) and 0xff

        private fun xtime(x: Int): Int = ((x shl 1) xor (if (x and 0x80 != 0) 0x1b else 0)) and 0xff
    }
}

/** CTR with a 128-bit big-endian counter that carries through the whole block, as OpenSSH's `aes*-ctr` does. */
public object AesCtr {
    public fun apply(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        require(iv.size == 16)
        val aes = Aes(key)
        val counter = iv.copyOf()
        val stream = ByteArray(16)
        val out = ByteArray(data.size)
        var offset = 0
        while (offset < data.size) {
            aes.encryptBlock(counter, 0, stream, 0)
            val n = minOf(16, data.size - offset)
            for (i in 0 until n) out[offset + i] = (data[offset + i].toInt() xor stream[i].toInt()).toByte()
            for (i in 15 downTo 0) {
                counter[i] = (counter[i] + 1).toByte()
                if (counter[i] != 0.toByte()) break
            }
            offset += 16
        }
        aes.wipe()
        stream.fill(0)
        return out
    }
}

/** GCM (NIST SP 800-38D) with a 12-byte IV and a 16-byte tag — `aes256-gcm@openssh.com`. */
public object AesGcm {

    public const val TAG_SIZE: Int = 16

    /** Ciphertext followed by the tag. */
    public fun seal(key: ByteArray, iv: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val aes = Aes(key)
        try {
            val ciphertext = ctr(aes, iv, plaintext)
            return ciphertext + tag(aes, iv, aad, ciphertext)
        } finally {
            aes.wipe()
        }
    }

    /** The plaintext, or null when the tag does not match. */
    public fun open(key: ByteArray, iv: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        if (sealed.size < TAG_SIZE) return null
        val aes = Aes(key)
        try {
            val ciphertext = sealed.copyOfRange(0, sealed.size - TAG_SIZE)
            val expected = tag(aes, iv, aad, ciphertext)
            var diff = 0
            for (i in 0 until TAG_SIZE) diff = diff or (expected[i].toInt() xor sealed[ciphertext.size + i].toInt())
            if (diff != 0) return null
            return ctr(aes, iv, ciphertext)
        } finally {
            aes.wipe()
        }
    }

    private fun j0(iv: ByteArray): ByteArray {
        require(iv.size == 12) { "GCM IV of ${iv.size} bytes" }
        return iv.copyOf(16).also { it[15] = 1 }
    }

    /** GCTR from inc32(J0). */
    private fun ctr(aes: Aes, iv: ByteArray, data: ByteArray): ByteArray {
        val counter = j0(iv)
        val stream = ByteArray(16)
        val out = ByteArray(data.size)
        var offset = 0
        while (offset < data.size) {
            inc32(counter)
            aes.encryptBlock(counter, 0, stream, 0)
            val n = minOf(16, data.size - offset)
            for (i in 0 until n) out[offset + i] = (data[offset + i].toInt() xor stream[i].toInt()).toByte()
            offset += 16
        }
        return out
    }

    private fun inc32(block: ByteArray) {
        for (i in 15 downTo 12) {
            block[i] = (block[i] + 1).toByte()
            if (block[i] != 0.toByte()) break
        }
    }

    private fun tag(aes: Aes, iv: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val h = ByteArray(16).also { aes.encryptBlock(it, 0, it, 0) }
        val hHi = h.longBe(0)
        val hLo = h.longBe(8)
        var yHi = 0L
        var yLo = 0L
        fun absorb(data: ByteArray) {
            var offset = 0
            while (offset < data.size) {
                val block = ByteArray(16)
                data.copyInto(block, 0, offset, minOf(offset + 16, data.size))
                yHi = yHi xor block.longBe(0)
                yLo = yLo xor block.longBe(8)
                val (hi, lo) = multiply(yHi, yLo, hHi, hLo)
                yHi = hi
                yLo = lo
                offset += 16
            }
        }
        absorb(aad)
        absorb(ciphertext)
        val lengths = ByteArray(16)
        lengths.putLongBe(0, aad.size.toLong() * 8)
        lengths.putLongBe(8, ciphertext.size.toLong() * 8)
        absorb(lengths)
        val s = ByteArray(16)
        s.putLongBe(0, yHi)
        s.putLongBe(8, yLo)
        val ek = ByteArray(16)
        aes.encryptBlock(j0(iv), 0, ek, 0)
        return ByteArray(16) { (s[it].toInt() xor ek[it].toInt()).toByte() }
    }

    /** X · Y in GF(2¹²⁸) with GCM's bit order (SP 800-38D, algorithm 1). */
    private fun multiply(xHi: Long, xLo: Long, yHi: Long, yLo: Long): Pair<Long, Long> {
        var zHi = 0L
        var zLo = 0L
        var vHi = yHi
        var vLo = yLo
        for (i in 0 until 128) {
            val bit = if (i < 64) (xHi ushr (63 - i)) and 1 else (xLo ushr (127 - i)) and 1
            val mask = -bit
            zHi = zHi xor (vHi and mask)
            zLo = zLo xor (vLo and mask)
            val lsb = vLo and 1
            vLo = (vLo ushr 1) or (vHi shl 63)
            vHi = (vHi ushr 1) xor (R and -lsb)
        }
        return zHi to zLo
    }

    /** R = 11100001 ‖ 0¹²⁰, the high half. */
    private const val R: Long = -0x1f00000000000000L
}

internal fun ByteArray.intBe(i: Int): Int =
    ((this[i].toInt() and 0xff) shl 24) or ((this[i + 1].toInt() and 0xff) shl 16) or
        ((this[i + 2].toInt() and 0xff) shl 8) or (this[i + 3].toInt() and 0xff)

internal fun ByteArray.longBe(i: Int): Long = (intBe(i).toLong() shl 32) or (intBe(i + 4).toLong() and 0xffffffffL)

internal fun ByteArray.putLongBe(i: Int, value: Long) {
    for (k in 0 until 8) this[i + k] = (value ushr (56 - 8 * k)).toByte()
}
