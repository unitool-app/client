package cx.m42.unitool.kit.qr

/**
 * zlib (RFC 1950) around one deflate block (RFC 1951) with the fixed Huffman codes and greedy LZ77
 * matching. That is far from the best compressor, but a QR picture repeats every row [scale] times
 * and every module run is the same bytes, so back-references do nearly all the work. It is written
 * here because the browser has no zlib to borrow; `desktopTest` reads the output with
 * `java.util.zip.Inflater`.
 */
internal object Deflate {
    private const val WINDOW = 32768
    private const val MAX_MATCH = 258
    private const val MIN_MATCH = 3
    private const val MAX_CHAIN = 128
    private const val HASH_SIZE = 1 shl 15

    fun zlib(data: ByteArray): ByteArray {
        val out = BitWriter(data.size / 4 + 64)
        out.bits(0x78, 8) // CMF: deflate, 32K window
        out.bits(0x9C, 8) // FLG: default level; (0x78 * 256 + 0x9C) % 31 == 0
        out.bits(1, 1) // BFINAL
        out.bits(1, 2) // BTYPE 01: fixed Huffman codes

        val head = IntArray(HASH_SIZE) { -1 }
        val previous = IntArray(WINDOW)
        fun insert(position: Int) {
            if (position + MIN_MATCH > data.size) return
            val hash = hash(data, position)
            previous[position and (WINDOW - 1)] = head[hash]
            head[hash] = position
        }

        var i = 0
        while (i < data.size) {
            var bestLength = 0
            var bestDistance = 0
            if (i + MIN_MATCH <= data.size) {
                val limit = minOf(MAX_MATCH, data.size - i)
                var candidate = head[hash(data, i)]
                var chain = 0
                // Older positions only: an entry that a newer position overwrote lies outside the window.
                while (candidate >= 0 && i - candidate <= WINDOW && chain++ < MAX_CHAIN) {
                    var length = 0
                    while (length < limit && data[candidate + length] == data[i + length]) length++
                    if (length > bestLength) {
                        bestLength = length
                        bestDistance = i - candidate
                        if (length == limit) break
                    }
                    candidate = previous[candidate and (WINDOW - 1)]
                }
            }
            if (bestLength >= MIN_MATCH) {
                out.length(bestLength)
                out.distance(bestDistance)
                for (k in i until i + bestLength) insert(k)
                i += bestLength
            } else {
                out.literal(data[i].toInt() and 0xFF)
                insert(i)
                i++
            }
        }
        out.symbol(256) // end of block
        out.align()
        val adler = adler32(data)
        for (shift in intArrayOf(24, 16, 8, 0)) out.bits((adler ushr shift) and 0xFF, 8)
        return out.toByteArray()
    }

    private fun hash(data: ByteArray, at: Int): Int =
        (((data[at].toInt() and 0xFF) shl 10) xor ((data[at + 1].toInt() and 0xFF) shl 5) xor (data[at + 2].toInt() and 0xFF)) and
            (HASH_SIZE - 1)

    private fun adler32(data: ByteArray): Int {
        var a = 1
        var b = 0
        for (byte in data) {
            a = (a + (byte.toInt() and 0xFF)) % 65521
            b = (b + a) % 65521
        }
        return (b shl 16) or a
    }

    private val LENGTH_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LENGTH_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DISTANCE_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073,
        4097, 6145, 8193, 12289, 16385, 24577,
    )
    private val DISTANCE_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)

    /** Bits go out least significant first; Huffman codes go most significant first (RFC 1951 §3.1.1). */
    private class BitWriter(capacity: Int) {
        private var bytes = ByteArray(maxOf(capacity, 16))
        private var size = 0
        private var buffer = 0
        private var count = 0

        fun bits(value: Int, length: Int) {
            buffer = buffer or (value shl count)
            count += length
            while (count >= 8) {
                put(buffer.toByte())
                buffer = buffer ushr 8
                count -= 8
            }
        }

        fun align() {
            if (count > 0) put(buffer.toByte())
            buffer = 0
            count = 0
        }

        fun literal(byte: Int) = symbol(byte)

        /** A literal/length symbol in the fixed code: 8, 9, 7 or 8 bits by range. */
        fun symbol(symbol: Int) = when {
            symbol < 144 -> huffman(0x30 + symbol, 8)
            symbol < 256 -> huffman(0x190 + symbol - 144, 9)
            symbol < 280 -> huffman(symbol - 256, 7)
            else -> huffman(0xC0 + symbol - 280, 8)
        }

        fun length(length: Int) {
            val index = LENGTH_BASE.indexOfLast { it <= length }
            symbol(257 + index)
            bits(length - LENGTH_BASE[index], LENGTH_EXTRA[index])
        }

        fun distance(distance: Int) {
            val index = DISTANCE_BASE.indexOfLast { it <= distance }
            huffman(index, 5)
            bits(distance - DISTANCE_BASE[index], DISTANCE_EXTRA[index])
        }

        private fun huffman(code: Int, length: Int) {
            var reversed = 0
            for (i in 0 until length) reversed = (reversed shl 1) or ((code ushr i) and 1)
            bits(reversed, length)
        }

        private fun put(byte: Byte) {
            if (size == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
            bytes[size++] = byte
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)
    }
}
