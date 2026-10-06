package cx.m42.unitool.kit.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Version
import com.google.zxing.qrcode.encoder.Encoder
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The hand-written encoder against ZXing, the library the phone's scanner reads with. ZXing encodes
 * the same text, and both matrices must agree module for module: the version, the blocks, the
 * placement and the mask choice all have to match. A second ZXing, the decoder, reads every output
 * format back to the text.
 */
class QrZxingTest {

    @Test
    fun theTablesAreZxingsForEveryVersionAndLevel() {
        for (version in 1..40) {
            val zxing = Version.getVersionForNumber(version)
            assertEquals(zxing.totalCodewords, QrEncoder.rawDataModules(version) / 8, "total codewords of version $version")
            for (ecc in ErrorCorrection.entries) {
                val blocks = zxing.getECBlocksForLevel(ErrorCorrectionLevel.valueOf(ecc.name))
                assertEquals(blocks.ecCodewordsPerBlock, QrEncoder.eccPerBlock(version, ecc), "ecc per block, $version-$ecc")
                assertEquals(blocks.numBlocks, QrEncoder.blocks(version, ecc), "blocks, $version-$ecc")
            }
        }
    }

    @Test
    fun everyVersionAndLevelMatchesZxingModuleForModule() {
        val random = Random(42)
        for (ecc in ErrorCorrection.entries) {
            for (version in 1..40) {
                val shortest = if (version == 1) 1 else QrEncoder.byteCapacity(version - 1, ecc) + 1
                for (length in setOf(shortest, QrEncoder.byteCapacity(version, ecc))) {
                    val text = bytes(random, length)
                    val ours = QrEncoder.encode(text, ecc)!!
                    assertEquals(version, ours.version, "$length bytes at $ecc")
                    assertSame(text, ours, ecc)
                }
            }
        }
    }

    @Test
    fun utf8TextCarriesTheEciMarkerAsInZxing() {
        for (text in listOf("Привет, мир", "ёжик — 🦔", "otpauth://totp/Банк:я?secret=JBSWY3DPEHPK3PXP")) {
            for (ecc in ErrorCorrection.entries) assertSame(text, QrEncoder.encode(text, ecc)!!, ecc)
        }
    }

    @Test
    fun everyForcedMaskMatchesZxing() {
        val text = "otpauth://totp/Unitool:me?secret=JBSWY3DPEHPK3PXP&issuer=Unitool"
        for (mask in 0..7) {
            val ours = QrEncoder.encode(text, ErrorCorrection.M, mask)!!
            val theirs = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.QR_MASK_PATTERN to mask))
            assertEquals(theirs.matrix.toString(), ours.render(), "mask $mask")
        }
    }

    @Test
    fun everyRenderingScans() {
        val text = "https://m42.cx/unitool?from=kit&lang=ru — и по-русски"
        val code = Qr.encode(text)!!
        assertEquals(text, decode(Grids.fromAnsi(QrTerminal.ansi(code))))
        assertEquals(text, decode(ImageIO.read(ByteArrayInputStream(QrImage.png(code)))))
        assertEquals(text, decode(Grids.fromSvg(QrImage.svg(code))))
    }

    @Test
    fun splitPartsScanAndJoinBackIntoTheText() {
        val text = Samples.accounts(60)
        val parts = Qr.split(text).map { decode(ImageIO.read(ByteArrayInputStream(QrImage.png(Qr.encode(it)!!)))) }
        assertEquals(text, parts.joinToString("\n"))
    }

    @Test
    fun pngIsReadByImageIoExactly() {
        val code = QrEncoder.encode("png", ErrorCorrection.H)!!
        val image = ImageIO.read(ByteArrayInputStream(QrImage.png(code, margin = 4, scale = 3)))
        val side = (code.size + 8) * 3
        assertEquals(side, image.width)
        for (y in 0 until side) for (x in 0 until side) {
            val dark = (image.getRGB(x, y) and 0xFFFFFF) == 0
            assertEquals(code.isDark(x / 3 - 4, y / 3 - 4), dark, "pixel $x,$y")
        }
    }

    /** ZXing's own encoding of [text], rendered as ZXing's `ByteMatrix.toString()`, against ours. */
    private fun assertSame(text: String, ours: QrCode, ecc: ErrorCorrection) {
        val hints = if (text.all { it.code < 128 }) emptyMap() else mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
        val theirs = Encoder.encode(text, ErrorCorrectionLevel.valueOf(ecc.name), hints)
        assertEquals(theirs.version.versionNumber, ours.version, "version for ${text.length} chars at $ecc")
        assertEquals(theirs.maskPattern, ours.mask, "mask for ${text.length} chars at $ecc")
        assertEquals(theirs.matrix.toString(), ours.render(), "matrix for ${text.length} chars at $ecc")
    }

    /** The format of ZXing's `ByteMatrix.toString()`: " 1" for dark, " 0" for light, one row per line. */
    private fun QrCode.render(): String = buildString {
        for (y in 0 until size) {
            for (x in 0 until size) append(if (isDark(x, y)) " 1" else " 0")
            append('\n')
        }
    }

    /** Lower case in the mix, so ZXing chooses byte mode too. */
    private fun bytes(random: Random, length: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789:/?&=.-_"
        return "a" + String(CharArray(length - 1) { alphabet[random.nextInt(alphabet.length)] })
    }

    private fun decode(grid: List<List<Boolean>>): String = ZxingDecode.grid(grid)

    private fun decode(image: BufferedImage): String = ZxingDecode.image(image)
}

/** ZXing's reader: the independent check that a code scans. */
internal object ZxingDecode {
    fun grid(grid: List<List<Boolean>>, scale: Int = 4): String {
        val side = grid.size * scale
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until side) for (x in 0 until side) {
            image.setRGB(x, y, if (grid[y / scale][x / scale]) 0x000000 else 0xFFFFFF)
        }
        return image(image)
    }

    fun image(image: BufferedImage): String {
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true)).text
    }
}
