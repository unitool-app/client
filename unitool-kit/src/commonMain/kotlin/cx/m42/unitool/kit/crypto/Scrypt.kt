package cx.m42.unitool.kit.crypto

/**
 * scrypt (RFC 7914): what `age -p` derives its key with (cr-restore D615). Salsa20/8 inside
 * BlockMix inside ROMix, over PBKDF2-HMAC-SHA256 at both ends.
 *
 * ROMix needs 128 · r · N bytes at once — 256 MiB for age's default N = 2¹⁸, r = 8 — so the caller
 * decides beforehand whether the device can afford [n]; this function only refuses what is not a
 * power of two.
 */
public fun scrypt(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, length: Int): ByteArray {
    require(n > 1 && n and (n - 1) == 0) { "scrypt N must be a power of two above 1, not $n" }
    require(r >= 1 && p >= 1)
    val blockInts = 32 * r
    val b = pbkdf2(Hash.SHA256, password, salt, 1, p * 128 * r)
    val x = IntArray(blockInts)
    val y = IntArray(blockInts)
    val v = IntArray(n * blockInts)
    try {
        for (i in 0 until p) {
            val offset = i * 128 * r
            for (k in 0 until blockInts) x[k] = b.intLe(offset + 4 * k)
            for (j in 0 until n) {
                x.copyInto(v, j * blockInts)
                blockMix(x, y, r)
            }
            for (j in 0 until n) {
                val index = x[(2 * r - 1) * 16] and (n - 1)
                for (k in 0 until blockInts) x[k] = x[k] xor v[index * blockInts + k]
                blockMix(x, y, r)
            }
            for (k in 0 until blockInts) b.putIntLe(offset + 4 * k, x[k])
        }
        return pbkdf2(Hash.SHA256, password, b, 1, length)
    } finally {
        b.fill(0)
        x.fill(0)
        y.fill(0)
        v.fill(0)
    }
}

/** RFC 7914 §4: [x] becomes BlockMix(x); [y] is scratch of the same size. */
private fun blockMix(x: IntArray, y: IntArray, r: Int) {
    val t = IntArray(16)
    x.copyInto(t, 0, (2 * r - 1) * 16, 2 * r * 16)
    for (i in 0 until 2 * r) {
        for (k in 0 until 16) t[k] = t[k] xor x[i * 16 + k]
        salsa208(t)
        // Even blocks to the first half, odd ones to the second.
        val to = (if (i % 2 == 0) i / 2 else r + i / 2) * 16
        t.copyInto(y, to)
    }
    y.copyInto(x)
    t.fill(0)
}

/** Salsa20/8 core (RFC 7914 §3), in place. */
private fun salsa208(b: IntArray) {
    val x = b.copyOf()
    for (round in 0 until 4) {
        x[4] = x[4] xor (x[0] + x[12]).rotateLeft(7); x[8] = x[8] xor (x[4] + x[0]).rotateLeft(9)
        x[12] = x[12] xor (x[8] + x[4]).rotateLeft(13); x[0] = x[0] xor (x[12] + x[8]).rotateLeft(18)
        x[9] = x[9] xor (x[5] + x[1]).rotateLeft(7); x[13] = x[13] xor (x[9] + x[5]).rotateLeft(9)
        x[1] = x[1] xor (x[13] + x[9]).rotateLeft(13); x[5] = x[5] xor (x[1] + x[13]).rotateLeft(18)
        x[14] = x[14] xor (x[10] + x[6]).rotateLeft(7); x[2] = x[2] xor (x[14] + x[10]).rotateLeft(9)
        x[6] = x[6] xor (x[2] + x[14]).rotateLeft(13); x[10] = x[10] xor (x[6] + x[2]).rotateLeft(18)
        x[3] = x[3] xor (x[15] + x[11]).rotateLeft(7); x[7] = x[7] xor (x[3] + x[15]).rotateLeft(9)
        x[11] = x[11] xor (x[7] + x[3]).rotateLeft(13); x[15] = x[15] xor (x[11] + x[7]).rotateLeft(18)
        x[1] = x[1] xor (x[0] + x[3]).rotateLeft(7); x[2] = x[2] xor (x[1] + x[0]).rotateLeft(9)
        x[3] = x[3] xor (x[2] + x[1]).rotateLeft(13); x[0] = x[0] xor (x[3] + x[2]).rotateLeft(18)
        x[6] = x[6] xor (x[5] + x[4]).rotateLeft(7); x[7] = x[7] xor (x[6] + x[5]).rotateLeft(9)
        x[4] = x[4] xor (x[7] + x[6]).rotateLeft(13); x[5] = x[5] xor (x[4] + x[7]).rotateLeft(18)
        x[11] = x[11] xor (x[10] + x[9]).rotateLeft(7); x[8] = x[8] xor (x[11] + x[10]).rotateLeft(9)
        x[9] = x[9] xor (x[8] + x[11]).rotateLeft(13); x[10] = x[10] xor (x[9] + x[8]).rotateLeft(18)
        x[12] = x[12] xor (x[15] + x[14]).rotateLeft(7); x[13] = x[13] xor (x[12] + x[15]).rotateLeft(9)
        x[14] = x[14] xor (x[13] + x[12]).rotateLeft(13); x[15] = x[15] xor (x[14] + x[13]).rotateLeft(18)
    }
    for (i in 0 until 16) b[i] += x[i]
    x.fill(0)
}

private fun ByteArray.intLe(i: Int): Int =
    (this[i].toInt() and 0xff) or ((this[i + 1].toInt() and 0xff) shl 8) or
        ((this[i + 2].toInt() and 0xff) shl 16) or ((this[i + 3].toInt() and 0xff) shl 24)

private fun ByteArray.putIntLe(i: Int, value: Int) {
    for (k in 0 until 4) this[i + k] = (value ushr (8 * k)).toByte()
}
