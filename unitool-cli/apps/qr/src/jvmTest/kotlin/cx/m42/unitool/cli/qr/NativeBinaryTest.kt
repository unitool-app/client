package cx.m42.unitool.cli.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import cx.m42.unitool.kit.qr.Qr
import cx.m42.unitool.kit.qr.QrImage
import cx.m42.unitool.kit.qr.QrTerminal
import org.junit.Assume
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real native binary, run as a process and read back by ZXing. The other tests run the code;
 * this one runs what ships: libc's streams, libz's deflate, argv in UTF-8, the exit codes.
 *
 * It needs `:unitool-cli:app:install` first. `scripts/verify.sh` runs that before the tests. Without
 * the binary the test is skipped, and verify.sh reports the skip.
 */
class NativeBinaryTest {
    private val binary = File(System.getProperty("unitool.binary") ?: "unitool")
    private val dir = Files.createTempDirectory("unitool-native").toFile()

    @AfterTest
    fun clean() {
        dir.deleteRecursively()
    }

    private class Result(val code: Int, val out: ByteArray, val err: String)

    private fun run(vararg args: String, stdin: String? = null): Result {
        Assume.assumeTrue("no binary at $binary: run :unitool-cli:app:install", binary.canExecute())
        val process = ProcessBuilder(listOf(binary.path) + args).directory(dir).start()
        if (stdin != null) process.outputStream.use { it.write(stdin.toByteArray()) } else process.outputStream.close()
        val out = process.inputStream.readBytes()
        val err = process.errorStream.readBytes().decodeToString()
        return Result(process.waitFor(), out, err)
    }

    @Test
    fun eachFormatOfTheBinaryScans() {
        val text = "Привет из нативного бинаря: https://m42.cx/?q=1&r=2"
        val code = Qr.encode(text)!!
        val ansi = run("qr", text)
        assertEquals(0, ansi.code, ansi.err)
        assertEquals(QrTerminal.ansi(code), ansi.out.decodeToString(), "native and JVM print the same terminal output")

        assertEquals(0, run("qr", text, "-o", "code.png").code)
        assertEquals(text, decode(ImageIO.read(File(dir, "code.png"))), "the native PNG scans")
        assertEquals(QrImage.png(code).toList(), File(dir, "code.png").readBytes().toList(), "and is the JVM's, byte for byte")

        assertEquals(0, run("qr", text, "-o", "code.svg").code)
        assertEquals(QrImage.svg(code), File(dir, "code.svg").readText(), "native and JVM write the same SVG")
    }

    @Test
    fun splitFromStdinIntoFiles() {
        val text = QrTest.accounts(60)
        val result = run("qr", "--split", "-o", "export.png", stdin = text + "\n")
        assertEquals(0, result.code, result.err)
        val parts = generateSequence(1) { it + 1 }.map { File(dir, "export-$it.png") }.takeWhile { it.exists() }.toList()
        assertTrue(parts.size >= 2, result.err)
        assertEquals(text, parts.joinToString("\n") { decode(ImageIO.read(it)) })
    }

    @Test
    fun exitCodesOfTheBinary() {
        assertEquals(2, run("nope").code)
        assertEquals(2, run("qr", "hi", "--ec", "X").code)
        val tooLong = run("qr", stdin = "a".repeat(2954))
        assertEquals(1, tooLong.code)
        assertTrue("2954 bytes" in tooLong.err, tooLong.err)
        assertEquals(1, run("qr", "hi", "-o", "no/such/dir/x.png").code)
        assertEquals("unitool 0.1.0\n", run("--version").out.decodeToString())
    }

    private fun decode(image: BufferedImage): String {
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true)).text
    }
}
