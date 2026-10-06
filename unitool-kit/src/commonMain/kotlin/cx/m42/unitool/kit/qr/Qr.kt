package cx.m42.unitool.kit.qr

/**
 * QR codes for whoever needs one: the CLI prints them, the phone could draw them.
 *
 * ```kotlin
 * val code = Qr.encode("https://m42.cx") ?: error("too long")
 * QrImage.png(code)          // a picture: PNG bytes
 * QrImage.svg(code)          // a picture: SVG text
 * QrTerminal.ansi(code)      // terminal output
 * code.isDark(x, y)          // or draw it yourself, e.g. on a Compose Canvas
 * ```
 */
public object Qr {

    /** [text] as a QR code, or null when it does not fit even the largest symbol (version 40). */
    public fun encode(text: String, correction: ErrorCorrection = ErrorCorrection.L): QrCode? =
        QrEncoder.encode(text, correction)

    /**
     * The most bytes one code holds at [correction]: 2953 at L, as the 2FA export assumes. Text with
     * anything but ASCII ([utf8]) holds one byte less, the cost of its UTF-8 marker.
     */
    public fun capacity(correction: ErrorCorrection = ErrorCorrection.L, utf8: Boolean = false): Int =
        QrEncoder.byteCapacity(40, correction, utf8)

    /**
     * [text] cut into as few codes as it takes, on line breaks only. 2FA's import joins `otpauth://`
     * lines from several scans, and a cut inside a line would break a link. Joining the parts with
     * `\n` gives back [text] exactly.
     *
     * @throws LineTooLong when a single line does not fit one code.
     */
    public fun split(text: String, correction: ErrorCorrection = ErrorCorrection.L): List<String> {
        if (fits(text, correction)) return listOf(text)
        val parts = mutableListOf<String>()
        var current: String? = null
        text.split('\n').forEachIndexed { index, line ->
            val longer = if (current == null) line else current + "\n" + line
            if (fits(longer, correction)) {
                current = longer
                return@forEachIndexed
            }
            if (!fits(line, correction)) {
                throw LineTooLong(index + 1, line.encodeToByteArray().size, capacity(correction, isUtf8(line)))
            }
            current?.let { parts += it }
            current = line
        }
        current?.let { parts += it }
        return parts
    }

    /** By length alone: the encoder picks the version from the byte count, so this is exact. */
    private fun fits(text: String, correction: ErrorCorrection): Boolean =
        text.encodeToByteArray().size <= capacity(correction, isUtf8(text))

    private fun isUtf8(text: String) = text.any { it.code >= 128 }
}

/** How much of the code can be lost and still read: about 7%, 15%, 25% and 30%. Higher holds less. */
public enum class ErrorCorrection(internal val formatBits: Int) { L(1), M(0), Q(3), H(2) }

/**
 * A finished symbol: [size]×[size] modules, without the quiet zone (the renderers add it).
 * [version] is 1..40, and [mask] is the mask pattern the encoder chose.
 */
public class QrCode internal constructor(
    public val size: Int,
    private val modules: BooleanArray,
    public val version: Int,
    public val mask: Int,
) {
    /** Outside the matrix is the quiet zone, so it is light. */
    public fun isDark(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && modules[y * size + x]
}

/** Line [line] (1-based) is [bytes] bytes, and one code holds at most [capacity]. */
public class LineTooLong(public val line: Int, public val bytes: Int, public val capacity: Int) :
    IllegalArgumentException("line $line is $bytes bytes; one QR code holds at most $capacity")
