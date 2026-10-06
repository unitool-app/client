package cx.m42.unitool.kit.qr

/**
 * A 1-bit grayscale PNG, the smallest thing a QR code needs. It is written by hand, deflate included
 * ([Deflate]), because the kit runs where there is neither ImageIO nor a zlib to borrow: the browser.
 */
internal object Png {

    /** [width]×[height] pixels, [dark] true for black. */
    fun encode(width: Int, height: Int, dark: (x: Int, y: Int) -> Boolean): ByteArray {
        val rowBytes = (width + 7) / 8
        val raw = ByteArray((rowBytes + 1) * height)
        for (y in 0 until height) {
            val row = y * (rowBytes + 1)
            raw[row] = 0 // filter: none
            for (x in 0 until width) {
                // Grayscale at bit depth 1: a set bit is white.
                if (!dark(x, y)) {
                    val index = row + 1 + x / 8
                    raw[index] = (raw[index].toInt() or (0x80 ushr (x % 8))).toByte()
                }
            }
        }
        val header = ByteArray(13).also {
            it.putInt(0, width)
            it.putInt(4, height)
            it[8] = 1 // bit depth
            it[9] = 0 // colour type: grayscale
            it[10] = 0 // compression: deflate
            it[11] = 0 // filter method
            it[12] = 0 // no interlace
        }
        return SIGNATURE + chunk("IHDR", header) + chunk("IDAT", Deflate.zlib(raw)) + chunk("IEND", ByteArray(0))
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typed = type.encodeToByteArray() + data
        val out = ByteArray(4 + typed.size + 4)
        out.putInt(0, data.size)
        typed.copyInto(out, 4)
        out.putInt(4 + typed.size, Crc32.of(typed))
        return out
    }

    private fun ByteArray.putInt(at: Int, value: Int) {
        this[at] = (value ushr 24).toByte()
        this[at + 1] = (value ushr 16).toByte()
        this[at + 2] = (value ushr 8).toByte()
        this[at + 3] = value.toByte()
    }

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
}

/** CRC-32 as PNG and zlib use it (polynomial 0xEDB88320, reflected). */
internal object Crc32 {
    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if ((c and 1) != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun of(bytes: ByteArray): Int {
        var crc = -1
        for (b in bytes) crc = table[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
        return crc.inv()
    }
}
