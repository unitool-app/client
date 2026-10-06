package cx.m42.unitool.cli.qr

import cx.m42.unitool.cli.CliApp
import cx.m42.unitool.cli.Command
import cx.m42.unitool.cli.Io
import cx.m42.unitool.cli.advanced
import cx.m42.unitool.cli.choice
import cx.m42.unitool.cli.default
import cx.m42.unitool.cli.fail
import cx.m42.unitool.cli.int
import cx.m42.unitool.cli.showScreens
import cx.m42.unitool.cli.usage
import cx.m42.unitool.kit.qr.ErrorCorrection
import cx.m42.unitool.kit.qr.LineTooLong
import cx.m42.unitool.kit.qr.Qr
import cx.m42.unitool.kit.qr.QrCode
import cx.m42.unitool.kit.qr.QrImage
import cx.m42.unitool.kit.qr.QrTerminal

/**
 * `unitool qr`: the command-line face of the kit's QR service (`cx.m42.unitool.kit.qr`). What a QR
 * code is and how it is drawn lives there, shared with the phone. Here are only the parameters,
 * where the result goes, and the messages.
 */
public class QrApp : CliApp(id = "qr", title = "QR codes", summary = "Make a QR code from text: to the terminal, PNG or SVG") {
    private val encode = EncodeCommand()
    override val commands: List<Command> = listOf(encode)
    override val default: Command = encode
}

internal enum class Format(val id: String) {
    Ansi("ansi"), Png("png"), Svg("svg");

    fun render(code: QrCode, margin: Int, scale: Int): ByteArray = when (this) {
        Ansi -> QrTerminal.ansi(code, margin).encodeToByteArray()
        Png -> QrImage.png(code, margin, scale)
        Svg -> QrImage.svg(code, margin, scale).encodeToByteArray()
    }

    companion object {
        fun of(id: String): Format = entries.first { it.id == id }

        fun byExtension(path: String): Format? = when (path.substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
            "png" -> Png
            "svg" -> Svg
            else -> null
        }
    }
}

internal class EncodeCommand : Command("encode", "Make a QR code from text") {
    private val text by argument("TEXT", "the text; without it, stdin is read")
    private val out by option("-o", "--out", help = "write to a file instead of stdout", metavar = "FILE")
    private val format by option("-f", "--format", help = "the format; without it, the extension of FILE, otherwise ansi", metavar = "FORMAT")
        .choice("ansi", "png", "svg")
    private val ec by option("--ec", help = "error correction: the higher, the less fits", metavar = "LEVEL")
        .choice("L", "M", "Q", "H").default("L")
    private val split by flag("--split", help = "cut text longer than one code into several, on line breaks").advanced()
    private val margin by option("--margin", help = "quiet zone in modules; without it, 2 for ansi and 4 for png and svg", metavar = "N")
        .int(0..40).advanced()
    private val scale by option("--scale", help = "pixels per module in PNG and SVG", metavar = "N")
        .int(1..100).default(8).advanced()

    override fun run(io: Io): Int {
        val content = text ?: readStdin(io)
        if (content.isEmpty()) fail("empty text: nothing to encode")
        val target = out
        val kind = format?.let(Format::of) ?: target?.let(Format::byExtension) ?: Format.Ansi
        if (kind == Format.Png && target == null && io.stdoutIsTerminal) {
            usage("PNG is not printed to a terminal: give -o FILE or redirect stdout")
        }
        val level = ErrorCorrection.valueOf(ec)
        val parts = if (split) splitByLines(content, level) else listOf(content)
        val codes = parts.map { Qr.encode(it, level) ?: tooLong(it, level) }
        val margin = margin ?: if (kind == Format.Ansi) 2 else 4
        val images = codes.map { kind.render(it, margin, scale) }

        when {
            target != null -> writeFiles(io, target, images)
            images.size == 1 -> io.write(images.single())
            kind != Format.Ansi -> usage("${images.size} codes go to stdout only as ansi; for ${kind.id} give -o FILE")
            // As the 2FA export did with `split --filter='clear; qrencode …; read _ </dev/tty'`, without GNU.
            else -> io.showScreens(images, next = "Press Enter for the next code") { number, count -> "Code $number of $count" }
        }
        return 0
    }

    /** stdin as text, minus the one line break `echo` and editors put at the end. */
    private fun readStdin(io: Io): String {
        if (io.stdinIsTerminal) io.say("Type the text, then Ctrl-D:")
        return io.readStdin().decodeToString().removeSuffix("\n").removeSuffix("\r")
    }

    private fun splitByLines(text: String, level: ErrorCorrection): List<String> = try {
        Qr.split(text, level)
    } catch (e: LineTooLong) {
        fail("line ${e.line} does not fit a QR code even on its own: ${e.bytes} bytes, and at --ec $level one holds at most ${e.capacity}")
    }

    private fun tooLong(text: String, level: ErrorCorrection): Nothing {
        val bytes = text.encodeToByteArray().size
        val hint = if (split) "" else " --split cuts it into several codes on line breaks."
        fail("the text does not fit one QR code: $bytes bytes, and at --ec $level one holds at most ${Qr.capacity(level)}.$hint")
    }

    /** One file, or `name-1.png`, `name-2.png`… for several parts. */
    private fun writeFiles(io: Io, target: String, images: List<ByteArray>) {
        val paths = if (images.size == 1) listOf(target) else images.indices.map { numbered(target, it + 1) }
        paths.zip(images).forEach { (path, bytes) -> io.writeFile(path, bytes) }
        if (paths.size > 1) io.say("${paths.size} codes: ${paths.joinToString(", ") { it.substringAfterLast('/') }}")
    }

    private fun numbered(path: String, number: Int): String {
        val slash = path.lastIndexOf('/')
        val dot = path.lastIndexOf('.').takeIf { it > slash + 1 } ?: path.length
        return path.substring(0, dot) + "-$number" + path.substring(dot)
    }
}
