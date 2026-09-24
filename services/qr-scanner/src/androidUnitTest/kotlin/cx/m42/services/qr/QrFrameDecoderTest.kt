package cx.m42.services.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The decoder against frames shaped the way CameraX delivers them: a Y plane, rows padded to a
 * stride, the code somewhere in the middle of a larger picture.
 *
 * The codes are drawn by ZXing's own writer, so a failure here is the frame handling, not a
 * disagreement about what a QR code is.
 */
class QrFrameDecoderTest {

    private val link =
        "otpauth://totp/GitHub:octocat?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&algorithm=SHA1&digits=6&period=30"

    /** A camera-sized frame with the code drawn at [codeSide] px, grey rather than pure black/white. */
    private fun frame(
        text: String = link,
        width: Int = 640,
        height: Int = 480,
        codeSide: Int = 300,
        rowStride: Int = width,
        inverted: Boolean = false,
        noise: Int = 0,
        rotate: Boolean = false,
    ): ByteArray {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, codeSide, codeSide, mapOf(EncodeHintType.MARGIN to 4),
        )
        val random = Random(1)
        val bytes = ByteArray(rowStride * height) { 0x55 } // padding holds junk on real devices
        val left = (width - codeSide) / 2
        val top = (height - codeSide) / 2
        for (y in 0 until height) for (x in 0 until width) {
            val inCode = x - left in 0 until codeSide && y - top in 0 until codeSide
            var dark = inCode && if (rotate) matrix[y - top, codeSide - 1 - (x - left)] else matrix[x - left, y - top]
            if (inverted && inCode) dark = !dark
            // A photographed screen is never 0 and 255: dark is ~40, light ~200, background mid-grey.
            val base = when {
                !inCode -> 120
                dark -> 40
                else -> 200
            }
            val value = (base + if (noise > 0) random.nextInt(-noise, noise + 1) else 0).coerceIn(0, 255)
            bytes[y * rowStride + x] = value.toByte()
        }
        return bytes
    }

    @Test
    fun readsACodeInAPlainFrame() {
        assertEquals(link, QrFrameDecoder().decode(frame(), 640, 640, 480))
    }

    @Test
    fun rowPaddingIsSkippedNotRead() {
        // 640 wide in a 704-byte stride: what many devices hand CameraX for a 640×480 analysis.
        assertEquals(link, QrFrameDecoder().decode(frame(rowStride = 704), 704, 640, 480))
    }

    @Test
    fun aLightOnDarkCodeIsReadToo() {
        assertEquals(link, QrFrameDecoder().decode(frame(inverted = true), 640, 640, 480))
    }

    @Test
    fun aSmallNoisyTurnedCodeInAWideFrame() {
        val bytes = frame(width = 1280, height = 720, codeSide = 260, noise = 25, rotate = true)
        assertEquals(link, QrFrameDecoder().decode(bytes, 1280, 1280, 720))
    }

    @Test
    fun cyrillicSurvivesTheWayALinkCarriesIt() {
        // Links percent-encode their labels, so the code itself only ever holds ASCII.
        val cyrillic = "otpauth://totp/%D0%93%D0%BE%D1%81%D1%83%D1%81%D0%BB%D1%83%D0%B3%D0%B8:ivan?secret=JBSWY3DPEHPK3PXP"
        assertEquals(cyrillic, QrFrameDecoder().decode(frame(text = cyrillic), 640, 640, 480))
    }

    @Test
    fun aWholeExportInOneDenseCodeComesBackLineForLine() {
        // What `age -d … | qrencode` puts on a computer screen: two dozen links, one per line, in a
        // code of 150-odd modules — held up to the phone so it fills most of the frame.
        val export = (1..24).joinToString("") {
            "otpauth://totp/Google:ivan.petrov$it%40gmail.com?secret=ABCDEFGHIJKLMNOPQRSTUVWXYZ234567&issuer=Google\n"
        }
        val bytes = frame(text = export, width = 1280, height = 720, codeSide = 680, noise = 10)
        assertEquals(export, QrFrameDecoder().decode(bytes, 1280, 1280, 720))
    }

    @Test
    fun aFrameWithoutACodeIsNullAndTheDecoderStaysUsable() {
        val decoder = QrFrameDecoder()
        val blank = ByteArray(640 * 480) { 120 }
        assertNull(decoder.decode(blank, 640, 640, 480))
        assertEquals(link, decoder.decode(frame(), 640, 640, 480))
    }
}
