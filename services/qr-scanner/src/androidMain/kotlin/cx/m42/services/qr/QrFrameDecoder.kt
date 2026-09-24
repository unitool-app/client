package cx.m42.services.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * One camera frame in, the QR code's text out — or null, which for a live camera is the usual
 * answer and not an error.
 *
 * Pure ZXing, with no CameraX and no Android type in it, so the one part of the scanner that can
 * be wrong in a way nobody sees on a demo — row padding, a code shown white on black — is tested on
 * the JVM against codes ZXing itself drew.
 *
 * Not thread-safe: the reader keeps state between calls. One per analyzer, which CameraX calls on
 * a single thread.
 */
internal class QrFrameDecoder {

    // QRCodeReader, not MultiFormatReader: a 2FA setup is always a QR code, and naming the one
    // format keeps R8 from packing the fourteen others.
    private val reader = QRCodeReader()

    // Harder, because a code photographed off a monitor is small, moiré'd and a little skewed.
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    /**
     * [luminance] is the Y plane as CameraX hands it over: [rowStride] bytes per row, of which the
     * first [width] are the picture and the rest padding.
     */
    fun decode(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        // Then the negative: dark-themed sites draw their setup code light on dark, and ZXing only
        // looks for dark finder patterns on a light background.
        return read(source) ?: read(InvertedLuminanceSource(source))
    }

    private fun read(source: LuminanceSource): String? =
        try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
}
