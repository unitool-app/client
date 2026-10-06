package cx.m42.unitool.kit.qr

/**
 * A QR code encoder in plain Kotlin (ISO/IEC 18004), because the kit runs where ZXing cannot: in the
 * CLI's native binary, on iOS and in the browser. This is the same reason the phone's age and SHA code
 * are written by hand: wasm has no JCA.
 *
 * It does byte mode only, plus an ECI marker for UTF-8. Byte mode can encode any text, so the only
 * cost is density for text made purely of digits or capitals, which nobody here encodes. Everything
 * else follows ZXing step by step: the version choice, the padding, the block interleaving, where
 * the bits go and the mask penalty. `jvmTest` checks that both produce the same matrix, module for
 * module, for every version and level.
 *
 * The layout and the tables follow Project Nayuki's QR Code generator (MIT), which describes the
 * symbol most compactly. The penalty rules follow ZXing's `MaskUtil`, so the same mask wins.
 *
 * [Qr] is the public way in.
 */

internal object QrEncoder {

    /**
     * [text] at [ecc], with the mask that scores lowest (or [mask] when given), or null when the text
     * does not fit even in version 40. ASCII is written without the ECI marker so it reads the same on
     * every scanner. Anything else is marked UTF-8, otherwise a scanner guessing ISO-8859-1 would
     * show mojibake.
     */
    fun encode(text: String, ecc: ErrorCorrection, mask: Int? = null): QrCode? {
        require(mask == null || mask in 0..7) { "a mask is 0..7, not $mask" }
        val data = text.encodeToByteArray()
        val eci = text.any { it.code >= 128 }
        val version = (1..40).firstOrNull { bitsNeeded(data.size, eci, it) <= dataCodewords(it, ecc) * 8 } ?: return null
        val codewords = interleave(dataCodewords(data, eci, version, ecc), version, ecc)

        var best: Symbol? = null
        var bestPenalty = Int.MAX_VALUE
        for (candidate in mask?.let { listOf(it) } ?: (0..7).toList()) {
            val symbol = Symbol(version)
            symbol.drawFunctionPatterns()
            symbol.drawCodewords(codewords)
            symbol.applyMask(candidate)
            symbol.drawFormatBits(ecc, candidate)
            val penalty = symbol.penalty()
            // Strictly lower, as in ZXing: on a tie the lower mask number stays.
            if (penalty < bestPenalty) {
                best = symbol
                bestPenalty = penalty
            }
        }
        return best!!.let { QrCode(it.size, it.modules, version, it.mask) }
    }

    /** The bytes version [version] holds in byte mode, with the ECI marker or without. */
    fun byteCapacity(version: Int, ecc: ErrorCorrection, eci: Boolean = false): Int =
        (dataCodewords(version, ecc) * 8 - header(eci, version)) / 8

    private fun header(eci: Boolean, version: Int): Int = (if (eci) 12 else 0) + 4 + if (version < 10) 8 else 16

    private fun bitsNeeded(bytes: Int, eci: Boolean, version: Int): Int = header(eci, version) + 8 * bytes

    /** Modules left for data and error correction once every function pattern is drawn. */
    internal fun rawDataModules(version: Int): Int {
        var result = (16 * version + 128) * version + 64
        if (version >= 2) {
            val alignments = version / 7 + 2
            result -= (25 * alignments - 10) * alignments - 55
            if (version >= 7) result -= 36
        }
        return result
    }

    internal fun dataCodewords(version: Int, ecc: ErrorCorrection): Int =
        rawDataModules(version) / 8 - ECC_PER_BLOCK[ecc.ordinal][version] * BLOCKS[ecc.ordinal][version]

    /** Mode, count, bytes, then the terminator, the padding to a byte and the 0xEC 0x11 filler. */
    private fun dataCodewords(data: ByteArray, eci: Boolean, version: Int, ecc: ErrorCorrection): ByteArray {
        val bits = BitBuffer()
        if (eci) {
            bits.append(0b0111, 4)
            bits.append(26, 8) // ECI 26 is UTF-8
        }
        bits.append(0b0100, 4)
        bits.append(data.size, if (version < 10) 8 else 16)
        data.forEach { bits.append(it.toInt() and 0xFF, 8) }
        val capacity = dataCodewords(version, ecc) * 8
        bits.append(0, minOf(4, capacity - bits.size))
        bits.append(0, (8 - bits.size % 8) % 8)
        var pad = 0xEC
        while (bits.size < capacity) {
            bits.append(pad, 8)
            pad = pad xor (0xEC xor 0x11)
        }
        return bits.toBytes()
    }

    /** Splits into blocks, adds each block's Reed–Solomon bytes, then takes column by column. */
    private fun interleave(data: ByteArray, version: Int, ecc: ErrorCorrection): ByteArray {
        val blocks = BLOCKS[ecc.ordinal][version]
        val eccLength = ECC_PER_BLOCK[ecc.ordinal][version]
        val raw = rawDataModules(version) / 8
        val shortBlocks = blocks - raw % blocks
        val shortLength = raw / blocks
        val divisor = ReedSolomon.divisor(eccLength)

        val rows = ArrayList<ByteArray>(blocks)
        var offset = 0
        for (i in 0 until blocks) {
            val length = shortLength - eccLength + if (i < shortBlocks) 0 else 1
            val block = data.copyOfRange(offset, offset + length)
            offset += length
            val parity = ReedSolomon.remainder(block, divisor)
            // A short block gets a placeholder so every row has the same length; it is skipped below.
            rows += (if (i < shortBlocks) block + 0.toByte() else block) + parity
        }
        val out = ArrayList<Byte>(raw)
        for (i in rows[0].indices) {
            for (j in rows.indices) {
                if (i != shortLength - eccLength || j >= shortBlocks) out += rows[j][i]
            }
        }
        return out.toByteArray()
    }

    /** Error correction codewords per block, by level then version (index 0 unused). */
    private val ECC_PER_BLOCK: Array<IntArray> = arrayOf(
        intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
        intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
    )

    /** Error correction blocks, by level then version (index 0 unused). */
    private val BLOCKS: Array<IntArray> = arrayOf(
        intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
        intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
        intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
        intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
    )

    internal fun eccPerBlock(version: Int, ecc: ErrorCorrection): Int = ECC_PER_BLOCK[ecc.ordinal][version]

    internal fun blocks(version: Int, ecc: ErrorCorrection): Int = BLOCKS[ecc.ordinal][version]
}

/** One symbol under construction: the modules, and which of them belong to function patterns. */
private class Symbol(val version: Int) {
    val size = version * 4 + 17
    val modules = BooleanArray(size * size)
    private val function = BooleanArray(size * size)
    var mask = -1
        private set

    private fun set(x: Int, y: Int, dark: Boolean) {
        modules[y * size + x] = dark
        function[y * size + x] = true
    }

    fun drawFunctionPatterns() {
        for (i in 0 until size) {
            set(6, i, i % 2 == 0)
            set(i, 6, i % 2 == 0)
        }
        finder(3, 3)
        finder(size - 4, 3)
        finder(3, size - 4)
        val positions = alignmentPositions()
        val last = positions.size - 1
        for (i in positions.indices) {
            for (j in positions.indices) {
                // The three corners already hold finder patterns.
                if (i == 0 && j == 0 || i == 0 && j == last || i == last && j == 0) continue
                alignment(positions[i], positions[j])
            }
        }
        drawFormatBits(ErrorCorrection.L, 0) // reserves the area; drawn for real once the mask is known
        drawVersion()
    }

    /** A 7×7 finder with its light separator, clipped at the symbol's edge. */
    private fun finder(cx: Int, cy: Int) {
        for (dy in -4..4) for (dx in -4..4) {
            val x = cx + dx
            val y = cy + dy
            if (x in 0 until size && y in 0 until size) {
                val ring = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                set(x, y, ring != 2 && ring != 4)
            }
        }
    }

    private fun alignment(cx: Int, cy: Int) {
        for (dy in -2..2) for (dx in -2..2) {
            set(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
        }
    }

    private fun alignmentPositions(): IntArray {
        if (version == 1) return IntArray(0)
        val count = version / 7 + 2
        val step = (version * 8 + count * 3 + 5) / (count * 4 - 4) * 2
        val result = IntArray(count)
        result[0] = 6
        var position = size - 7
        for (i in count - 1 downTo 1) {
            result[i] = position
            position -= step
        }
        return result
    }

    /** 15 bits of level and mask under a BCH code, twice, plus the dark module beside the bottom-left finder. */
    fun drawFormatBits(ecc: ErrorCorrection, mask: Int) {
        val data = ecc.formatBits shl 3 or mask
        var remainder = data
        repeat(10) { remainder = (remainder shl 1) xor ((remainder ushr 9) * 0x537) }
        val bits = (data shl 10 or remainder) xor 0x5412
        fun bit(i: Int) = ((bits ushr i) and 1) != 0

        for (i in 0..5) set(8, i, bit(i))
        set(8, 7, bit(6))
        set(8, 8, bit(7))
        set(7, 8, bit(8))
        for (i in 9 until 15) set(14 - i, 8, bit(i))
        for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
        for (i in 8 until 15) set(8, size - 15 + i, bit(i))
        set(8, size - 8, true)
    }

    /** Versions 7 and up carry their number under a BCH code in two 6×3 blocks. */
    private fun drawVersion() {
        if (version < 7) return
        var remainder = version
        repeat(12) { remainder = (remainder shl 1) xor ((remainder ushr 11) * 0x1F25) }
        val bits = version shl 12 or remainder
        for (i in 0 until 18) {
            val dark = ((bits ushr i) and 1) != 0
            val a = size - 11 + i % 3
            val b = i / 3
            set(a, b, dark)
            set(b, a, dark)
        }
    }

    /** Up and down two-module columns from the right, skipping the vertical timing pattern. */
    fun drawCodewords(codewords: ByteArray) {
        var i = 0
        val total = codewords.size * 8
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vertical in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vertical else vertical
                    if (!function[y * size + x] && i < total) {
                        modules[y * size + x] = ((codewords[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                        i++
                    }
                }
            }
            right -= 2
        }
    }

    fun applyMask(mask: Int) {
        this.mask = mask
        for (y in 0 until size) for (x in 0 until size) {
            if (function[y * size + x]) continue
            val flip = when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> x * y % 2 + x * y % 3 == 0
                6 -> (x * y % 2 + x * y % 3) % 2 == 0
                else -> ((x + y) % 2 + x * y % 3) % 2 == 0
            }
            if (flip) modules[y * size + x] = !modules[y * size + x]
        }
    }

    /** ZXing's `MaskUtil`, rule for rule, so both pick the same mask. */
    fun penalty(): Int = runs(horizontal = true) + runs(horizontal = false) + blocks() + finderLike() + balance()

    private fun dark(x: Int, y: Int) = modules[y * size + x]

    /** N1: a run of five or more modules of one colour costs 3, plus 1 per module beyond five. */
    private fun runs(horizontal: Boolean): Int {
        var penalty = 0
        for (i in 0 until size) {
            var run = 0
            var previous: Boolean? = null
            for (j in 0 until size) {
                val bit = if (horizontal) dark(j, i) else dark(i, j)
                if (bit == previous) {
                    run++
                } else {
                    if (run >= 5) penalty += 3 + (run - 5)
                    run = 1
                    previous = bit
                }
            }
            if (run >= 5) penalty += 3 + (run - 5)
        }
        return penalty
    }

    /** N2: every 2×2 square of one colour costs 3. */
    private fun blocks(): Int {
        var count = 0
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val value = dark(x, y)
            if (value == dark(x + 1, y) && value == dark(x, y + 1) && value == dark(x + 1, y + 1)) count++
        }
        return 3 * count
    }

    /** N3: 1:1:3:1:1 with four light modules on either side looks like a finder and costs 40. */
    private fun finderLike(): Int {
        var count = 0
        for (y in 0 until size) for (x in 0 until size) {
            if (x + 6 < size && pattern { dark(x + it, y) } &&
                (light(x - 4, x) { dark(it, y) } || light(x + 7, x + 11) { dark(it, y) })
            ) count++
            if (y + 6 < size && pattern { dark(x, y + it) } &&
                (light(y - 4, y) { dark(x, it) } || light(y + 7, y + 11) { dark(x, it) })
            ) count++
        }
        return 40 * count
    }

    private inline fun pattern(at: (Int) -> Boolean): Boolean =
        at(0) && !at(1) && at(2) && at(3) && at(4) && !at(5) && at(6)

    /** ZXing's `isWhite…`: false when the range leaves the symbol. */
    private inline fun light(from: Int, to: Int, at: (Int) -> Boolean): Boolean {
        if (from < 0 || size < to) return false
        for (i in from until to) if (at(i)) return false
        return true
    }

    /** N4: 10 for every 5% the dark share strays from half. */
    private fun balance(): Int {
        val darkCount = modules.count { it }
        val total = size * size
        return kotlin.math.abs(darkCount * 2 - total) * 10 / total * 10
    }
}

private object ReedSolomon {
    /** The generator polynomial of [degree] over GF(256)/0x11D, roots α⁰…α^(degree-1), leading 1 dropped. */
    fun divisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        repeat(degree) {
            for (j in result.indices) {
                result[j] = multiply(result[j], root)
                if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
            }
            root = multiply(root, 0x02)
        }
        return result
    }

    fun remainder(data: ByteArray, divisor: IntArray): ByteArray {
        val result = IntArray(divisor.size)
        for (byte in data) {
            val factor = (byte.toInt() and 0xFF) xor result[0]
            result.copyInto(result, 0, 1)
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor multiply(divisor[i], factor)
        }
        return ByteArray(result.size) { result[it].toByte() }
    }

    private fun multiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }
}

private class BitBuffer {
    private val bits = ArrayList<Boolean>()
    val size: Int get() = bits.size

    fun append(value: Int, length: Int) {
        for (i in length - 1 downTo 0) bits += ((value ushr i) and 1) != 0
    }

    fun toBytes(): ByteArray = ByteArray(bits.size / 8) { index ->
        var byte = 0
        for (i in 0 until 8) byte = byte shl 1 or if (bits[index * 8 + i]) 1 else 0
        byte.toByte()
    }
}
