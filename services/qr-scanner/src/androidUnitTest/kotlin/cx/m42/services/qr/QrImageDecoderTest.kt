package cx.m42.services.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The picture decoder against pictures the way a picker hands them over: ARGB pixels, a code or a
 * few on a screenshot-sized canvas. Codes drawn by ZXing's own writer, as in [QrFrameDecoderTest].
 */
class QrImageDecoderTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private class Picture(val width: Int, val height: Int, background: Int) {
        val pixels = IntArray(width * height) { background }
    }

    private fun Picture.draw(text: String, left: Int, top: Int, side: Int, dark: Int, light: Int) {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, side, side, mapOf(EncodeHintType.MARGIN to 4))
        for (y in 0 until side) for (x in 0 until side) {
            pixels[(top + y) * width + left + x] = if (matrix[x, y]) dark else light
        }
    }

    @Test
    fun aCodeOnAScreenshot() {
        val picture = Picture(1080, 2400, 0xFFF2F2F2.toInt())
        picture.draw("https://unitool.m42.cx/", 240, 900, 600, black, white)
        assertEquals(listOf("https://unitool.m42.cx/"), QrImageDecoder.decode(picture.pixels, 1080, 2400))
    }

    @Test
    fun everyCodeInOnePicture() {
        // A page of `unitool qr encode --file -o`, several pieces side by side.
        val picture = Picture(1600, 900, white)
        val pieces = listOf("UQR1:0A1B2C3D:1:3:AAAA", "UQR1:0A1B2C3D:2:3:BBBB", "UQR1:0A1B2C3D:3:3:CCCC")
        pieces.forEachIndexed { i, text -> picture.draw(text, 60 + i * 520, 200, 440, black, white) }
        assertEquals(pieces.toSet(), QrImageDecoder.decode(picture.pixels, 1600, 900).toSet())
    }

    @Test
    fun aLightOnDarkCode() {
        val picture = Picture(800, 800, 0xFF202020.toInt())
        picture.draw("WIFI:T:WPA;S:home;P:secret;;", 150, 150, 500, white, 0xFF202020.toInt())
        assertEquals(listOf("WIFI:T:WPA;S:home;P:secret;;"), QrImageDecoder.decode(picture.pixels, 800, 800))
    }

    @Test
    fun aPictureWithoutACodeIsEmpty() {
        assertTrue(QrImageDecoder.decode(IntArray(400 * 300) { 0xFF8090A0.toInt() }, 400, 300).isEmpty())
    }
}
