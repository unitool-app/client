package cx.m42.unitool.kit.crypto

/**
 * ChaCha20-Poly1305 (RFC 8439): the cipher age wraps its keys and its payload in. Moved from the
 * 2FA app (D156), which only sealed; opening is new, for `age -d` on the device (02 §6.1).
 *
 * Held to RFC 8439's vectors and to the JDK's implementation in `desktopTest`.
 */
public object ChaCha20Poly1305 {

    public const val TAG_SIZE: Int = 16

    /** Ciphertext followed by the 16-byte tag. */
    public fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        val oneTimeKey = block(key, 0, nonce).copyOf(32)
        val ciphertext = xor(key, 1, nonce, plaintext)
        val macData = ByteArray(padded(aad.size) + padded(ciphertext.size) + 16)
        aad.copyInto(macData, 0)
        ciphertext.copyInto(macData, padded(aad.size))
        macData.putLongLe(macData.size - 16, aad.size.toLong())
        macData.putLongLe(macData.size - 8, ciphertext.size.toLong())
        return ciphertext + poly1305(oneTimeKey, macData)
    }

    /** The plaintext, or null when the tag does not match — nothing of an unauthenticated message comes back. */
    public fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        require(key.size == 32 && nonce.size == 12)
        if (sealed.size < TAG_SIZE) return null
        val ciphertext = sealed.copyOfRange(0, sealed.size - TAG_SIZE)
        val oneTimeKey = block(key, 0, nonce).copyOf(32)
        val macData = ByteArray(padded(aad.size) + padded(ciphertext.size) + 16)
        aad.copyInto(macData, 0)
        ciphertext.copyInto(macData, padded(aad.size))
        macData.putLongLe(macData.size - 16, aad.size.toLong())
        macData.putLongLe(macData.size - 8, ciphertext.size.toLong())
        val expected = poly1305(oneTimeKey, macData)
        oneTimeKey.fill(0)
        var diff = 0
        for (i in 0 until TAG_SIZE) diff = diff or (expected[i].toInt() xor sealed[sealed.size - TAG_SIZE + i].toInt())
        if (diff != 0) return null
        return xor(key, 1, nonce, ciphertext)
    }

    private fun padded(size: Int): Int = (size + 15) / 16 * 16

    /** One 64-byte keystream block. */
    internal fun block(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = key.intLe(4 * i)
        state[12] = counter
        for (i in 0 until 3) state[13 + i] = nonce.intLe(4 * i)
        val w = state.copyOf()
        repeat(10) {
            quarterRound(w, 0, 4, 8, 12)
            quarterRound(w, 1, 5, 9, 13)
            quarterRound(w, 2, 6, 10, 14)
            quarterRound(w, 3, 7, 11, 15)
            quarterRound(w, 0, 5, 10, 15)
            quarterRound(w, 1, 6, 11, 12)
            quarterRound(w, 2, 7, 8, 13)
            quarterRound(w, 3, 4, 9, 14)
        }
        for (i in 0 until 16) w[i] += state[i]
        return ByteArray(64) { (w[it / 4] ushr (8 * (it % 4))).toByte() }
    }

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(7)
    }

    private fun xor(key: ByteArray, counter: Int, nonce: ByteArray, input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        var offset = 0
        var n = counter
        while (offset < input.size) {
            val stream = block(key, n++, nonce)
            for (i in 0 until minOf(64, input.size - offset)) {
                out[offset + i] = (input[offset + i].toInt() xor stream[i].toInt()).toByte()
            }
            offset += 64
        }
        return out
    }

    /**
     * Poly1305 in five 26-bit limbs (poly1305-donna-32): every product fits a signed `Long` with
     * room to spare, which is what keeps it the same code on the JVM and on wasm.
     */
    internal fun poly1305(key: ByteArray, message: ByteArray): ByteArray {
        val mask = 0x3ffffffL
        val r0 = key.uintLe(0) and 0x3ffffff
        val r1 = (key.uintLe(3) ushr 2) and 0x3ffff03
        val r2 = (key.uintLe(6) ushr 4) and 0x3ffc0ff
        val r3 = (key.uintLe(9) ushr 6) and 0x3f03fff
        val r4 = (key.uintLe(12) ushr 8) and 0x00fffff
        val s1 = r1 * 5
        val s2 = r2 * 5
        val s3 = r3 * 5
        val s4 = r4 * 5
        var h0 = 0L
        var h1 = 0L
        var h2 = 0L
        var h3 = 0L
        var h4 = 0L

        var offset = 0
        while (offset < message.size) {
            val n = minOf(16, message.size - offset)
            val m = ByteArray(16)
            message.copyInto(m, 0, offset, offset + n)
            // A short last block gets its 1 bit right after the message instead of at 2^128.
            val hibit = if (n == 16) 1L shl 24 else 0L
            if (n < 16) m[n] = 1

            h0 += m.uintLe(0) and mask
            h1 += (m.uintLe(3) ushr 2) and mask
            h2 += (m.uintLe(6) ushr 4) and mask
            h3 += (m.uintLe(9) ushr 6) and mask
            h4 += (m.uintLe(12) ushr 8) or hibit

            val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            val d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            val d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            val d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            val d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0

            var c = d0 ushr 26
            h0 = d0 and mask
            var t = d1 + c
            c = t ushr 26
            h1 = t and mask
            t = d2 + c
            c = t ushr 26
            h2 = t and mask
            t = d3 + c
            c = t ushr 26
            h3 = t and mask
            t = d4 + c
            c = t ushr 26
            h4 = t and mask
            h0 += c * 5
            c = h0 ushr 26
            h0 = h0 and mask
            h1 += c

            offset += 16
        }

        var c = h1 ushr 26
        h1 = h1 and mask
        h2 += c; c = h2 ushr 26; h2 = h2 and mask
        h3 += c; c = h3 ushr 26; h3 = h3 and mask
        h4 += c; c = h4 ushr 26; h4 = h4 and mask
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and mask
        h1 += c

        // h − p, and whichever of the two is the reduced one, chosen by mask rather than by branch.
        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and mask
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and mask
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and mask
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and mask
        val g4 = h4 + c - (1L shl 26)
        val keepH = g4 shr 63
        val takeG = keepH.inv()
        h0 = (h0 and keepH) or (g0 and takeG)
        h1 = (h1 and keepH) or (g1 and takeG)
        h2 = (h2 and keepH) or (g2 and takeG)
        h3 = (h3 and keepH) or (g3 and takeG)
        h4 = (h4 and keepH) or (g4 and takeG)

        val words = 0xffffffffL
        val w0 = (h0 or (h1 shl 26)) and words
        val w1 = ((h1 ushr 6) or (h2 shl 20)) and words
        val w2 = ((h2 ushr 12) or (h3 shl 14)) and words
        val w3 = ((h3 ushr 18) or (h4 shl 8)) and words

        val tag = ByteArray(16)
        var f = w0 + key.uintLe(16)
        tag.putIntLe(0, f.toInt())
        f = w1 + key.uintLe(20) + (f ushr 32)
        tag.putIntLe(4, f.toInt())
        f = w2 + key.uintLe(24) + (f ushr 32)
        tag.putIntLe(8, f.toInt())
        f = w3 + key.uintLe(28) + (f ushr 32)
        tag.putIntLe(12, f.toInt())
        return tag
    }
}

private fun ByteArray.intLe(i: Int): Int =
    (this[i].toInt() and 0xff) or ((this[i + 1].toInt() and 0xff) shl 8) or
        ((this[i + 2].toInt() and 0xff) shl 16) or ((this[i + 3].toInt() and 0xff) shl 24)

private fun ByteArray.uintLe(i: Int): Long = intLe(i).toLong() and 0xffffffffL

private fun ByteArray.putIntLe(i: Int, value: Int) {
    for (k in 0 until 4) this[i + k] = (value ushr (8 * k)).toByte()
}

private fun ByteArray.putLongLe(i: Int, value: Long) {
    for (k in 0 until 8) this[i + k] = (value ushr (8 * k)).toByte()
}
