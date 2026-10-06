package cx.m42.unitool.kit.qr

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The kit's QR service on every target it builds for. Whether the modules themselves are right is
 * `QrZxingTest`'s job (desktopTest); here each rendering is read back and compared with the code.
 */
class QrKitTest {

    @Test
    fun terminalOutputIsTheCodeWithItsMargin() {
        val code = Qr.encode("https://m42.cx/unitool")!!
        val ansi = QrTerminal.ansi(code)
        assertEquals(Grids.of(code, margin = 2), Grids.fromAnsi(ansi))
        assertEquals(Grids.of(code, margin = 0), Grids.fromAnsi(QrTerminal.ansi(code, margin = 0)))
        assertTrue(ansi.lines().filter { it.isNotEmpty() }.all { it.endsWith("\u001b[0m") }, "every line resets the colours")
    }

    @Test
    fun svgIsTheCodeWithItsMarginAndSize() {
        val code = Qr.encode("Привет, мир", ErrorCorrection.Q)!!
        val svg = QrImage.svg(code, margin = 3, scale = 5)
        assertEquals(Grids.of(code, margin = 3), Grids.fromSvg(svg))
        assertTrue("width=\"${(code.size + 6) * 5}\"" in svg, svg.take(200))
    }

    @Test
    fun pngHasItsHeaderAndSize() {
        val code = Qr.encode("hello")!!
        val png = QrImage.png(code, margin = 4, scale = 5)
        assertContentEquals(Samples.PNG_MAGIC, png.copyOf(8))
        fun int(at: Int) = (png[at].toInt() and 0xFF shl 24) or (png[at + 1].toInt() and 0xFF shl 16) or
            (png[at + 2].toInt() and 0xFF shl 8) or (png[at + 3].toInt() and 0xFF)
        assertEquals((code.size + 8) * 5, int(16), "width")
        assertEquals((code.size + 8) * 5, int(20), "height")
        assertEquals(1, png[24].toInt(), "bit depth 1")
    }

    @Test
    fun capacityIsWhatThe2faExportAssumes() {
        for ((level, bytes) in listOf(ErrorCorrection.L to 2953, ErrorCorrection.M to 2331, ErrorCorrection.Q to 1663, ErrorCorrection.H to 1273)) {
            assertEquals(bytes, Qr.capacity(level))
            assertNotNull(Qr.encode("a".repeat(bytes), level), "$level holds $bytes bytes")
            assertNull(Qr.encode("a".repeat(bytes + 1), level), "$level does not hold ${bytes + 1}")
        }
        assertEquals(2952, Qr.capacity(ErrorCorrection.L, utf8 = true))
        assertNotNull(Qr.encode("ё" + "a".repeat(2950)), "2952 bytes with the UTF-8 marker")
        assertNull(Qr.encode("ё" + "a".repeat(2951)), "the marker costs a byte")
    }

    @Test
    fun versionGrowsWithTheText() {
        assertEquals(1, Qr.encode("a")!!.version)
        assertEquals(21, Qr.encode("a")!!.size)
        assertEquals(40, Qr.encode("a".repeat(2953))!!.version)
        assertEquals(177, Qr.encode("a".repeat(2953))!!.size)
    }

    @Test
    fun splitCutsOnLineBreaksOnly() {
        val text = Samples.accounts(60)
        val parts = Qr.split(text)
        assertTrue(parts.size >= 2, "60 accounts do not fit one code")
        assertEquals(text, parts.joinToString("\n"))
        assertTrue(parts.all { part -> part.lines().all { it.startsWith("otpauth://") } }, "no line is cut")
        assertTrue(parts.all { Qr.encode(it) != null }, "every part fits")
        assertEquals(listOf("short"), Qr.split("short"))
    }

    @Test
    fun splitRefusesALineTooLongForAnyCode() {
        val error = assertFailsWith<LineTooLong> { Qr.split("short\n" + "x".repeat(3000)) }
        assertEquals(2, error.line)
        assertEquals(3000, error.bytes)
        assertEquals(2953, error.capacity)
    }

    @Test
    fun renderersRefuseImpossibleLayouts() {
        val code = Qr.encode("x")!!
        assertFailsWith<IllegalArgumentException> { QrImage.png(code, scale = 0) }
        assertFailsWith<IllegalArgumentException> { QrImage.svg(code, margin = -1) }
        assertFailsWith<IllegalArgumentException> { QrTerminal.ansi(code, margin = -1) }
    }
}

internal object Samples {
    val PNG_MAGIC = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

    fun accounts(count: Int): String = (1..count).joinToString("\n") {
        "otpauth://totp/Issuer$it:user$it@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Issuer$it&digits=6&period=30"
    }
}

/** Codes as rows of booleans (true is dark), read back from each rendering. */
internal object Grids {

    fun of(code: QrCode, margin: Int): List<List<Boolean>> =
        (-margin until code.size + margin).map { y -> (-margin until code.size + margin).map { x -> code.isDark(x, y) } }

    /** Undoes [QrTerminal.ansi]: each `▀` cell is the upper module in its foreground, the lower in its background. */
    fun fromAnsi(text: String): List<List<Boolean>> {
        val rows = mutableListOf<List<Boolean>>()
        for (line in text.lines().filter { it.isNotEmpty() }) {
            val top = mutableListOf<Boolean>()
            val bottom = mutableListOf<Boolean>()
            var fg = "97"
            var bg = "107"
            var i = 0
            while (i < line.length) {
                if (line[i] == '\u001b') {
                    val end = line.indexOf('m', i)
                    for (code in line.substring(i + 2, end).split(';')) when (code) {
                        "30", "97" -> fg = code
                        "40", "107" -> bg = code
                    }
                    i = end + 1
                    continue
                }
                check(line[i] == '▀') { "unexpected character '${line[i]}'" }
                top += fg == "30"
                bottom += bg == "40"
                i++
            }
            if (top.isNotEmpty()) {
                rows += top
                rows += bottom
            }
        }
        // An odd side ends in a half-filled last line: its lower half is padding, not the code.
        return rows.take(rows.first().size)
    }

    fun fromSvg(svg: String): List<List<Boolean>> {
        val side = Regex("""viewBox="0 0 (\d+) (\d+)"""").find(svg)!!.groupValues[1].toInt()
        val grid = List(side) { MutableList(side) { false } }
        val path = Regex("""<path fill="#000000" d="([^"]*)"""").find(svg)!!.groupValues[1]
        Regex("""M(\d+) (\d+)h(\d+)v1h-(\d+)z""").findAll(path).forEach { match ->
            val (x, y, length, back) = match.destructured
            check(length == back)
            repeat(length.toInt()) { grid[y.toInt()][x.toInt() + it] = true }
        }
        return grid
    }
}
