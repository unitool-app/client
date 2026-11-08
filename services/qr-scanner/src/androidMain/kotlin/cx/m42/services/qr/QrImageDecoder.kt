package cx.m42.services.qr

import com.google.zxing.Binarizer
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader

/**
 * A picture in — a photo or a screenshot picked on the scanner's screen — and every QR code in it
 * out, in the order ZXing finds them, each text once. Empty when there is none.
 *
 * Every code rather than the first: a screenshot of `unitool qr encode --file -o` may hold several
 * pieces of one file, and a series takes them all at once.
 *
 * Pure ZXing on ARGB pixels, so it is tested on the JVM like [QrFrameDecoder]; loading the bitmap
 * is the activity's.
 */
internal object QrImageDecoder {

    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    fun decode(pixels: IntArray, width: Int, height: Int): List<String> {
        val source = RGBLuminanceSource(width, height, pixels)
        // Hybrid first, as for a camera frame; then the negative for light-on-dark codes; then the
        // global binarizer, which copes better with a flat, evenly lit screenshot at large sizes.
        val tries = listOf<() -> Binarizer>(
            { HybridBinarizer(source) },
            { HybridBinarizer(InvertedLuminanceSource(source)) },
            { GlobalHistogramBinarizer(source) },
        )
        for (binarizer in tries) {
            val found = read(binarizer())
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun read(binarizer: Binarizer): List<String> =
        try {
            QRCodeMultiReader().decodeMultiple(BinaryBitmap(binarizer), hints)
                .mapNotNull { it.text }
                .distinct()
        } catch (_: ReaderException) {
            emptyList()
        }
}
