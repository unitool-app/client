package cx.m42.unitool.cli.split

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import cx.m42.unitool.kit.crypt.Ucrypt
import org.junit.Assume
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shipped binary with real files: what the other tests cannot see through FakeIo — libc's
 * open with 0600 and O_EXCL, reading files and stdin, and parts as PNGs a scanner reads.
 *
 * It needs `:unitool-cli:app:install` first; without the binary it is skipped, and verify.sh says so.
 */
class NativeBinaryTest {
    private val binary = File(System.getProperty("unitool.binary") ?: "unitool")
    private val dir = Files.createTempDirectory("unitool-split").toFile()
    private val secret = "native secret\nнативный секрет\n"

    @AfterTest
    fun clean() {
        dir.deleteRecursively()
    }

    private class Result(val code: Int, val out: ByteArray, val err: String)

    private fun run(vararg args: String, stdin: ByteArray? = null): Result {
        Assume.assumeTrue("no binary at $binary: run :unitool-cli:app:install", binary.canExecute())
        val process = ProcessBuilder(listOf(binary.path) + args).directory(dir).start()
        // A run that refuses early exits before reading stdin; the pipe closing then is its answer, not an error.
        try {
            process.outputStream.use { if (stdin != null) it.write(stdin) }
        } catch (_: IOException) {
        }
        val out = process.inputStream.readBytes()
        val err = process.errorStream.readBytes().decodeToString()
        return Result(process.waitFor(), out, err)
    }

    private fun mode(name: String): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(File(dir, name).toPath()))

    @Test
    fun makeAndJoinWithFilesThatOnlyTheOwnerReads() {
        val made = run("split", "make", "-n", "3", "-k", "2", "--base", "https://example.com/p", "-o", "s.ucrypt", "--show", "txt", stdin = secret.toByteArray())
        assertEquals(0, made.code, made.err)
        for (name in listOf("s.ucrypt", "part-1-of-3.txt", "part-2-of-3.txt", "part-3-of-3.txt")) assertEquals("rw-------", mode(name), name)
        assertTrue(Ucrypt.isUcrypt(File(dir, "s.ucrypt").readBytes()))

        val joined = run("split", "join", "s.ucrypt", "-p", "part-3-of-3.txt", "-p", "part-1-of-3.txt", "-o", "back.txt")
        assertEquals(0, joined.code, joined.err)
        assertEquals(secret, File(dir, "back.txt").readText())
        assertEquals("rw-------", mode("back.txt"))

        // The container on stdin, the secret on stdout.
        val piped = run("split", "join", "-p", "part-2-of-3.txt", "-p", "part-3-of-3.txt", stdin = File(dir, "s.ucrypt").readBytes())
        assertEquals(0, piped.code, piped.err)
        assertEquals(secret, piped.out.decodeToString())
    }

    @Test
    fun nothingIsEverReplaced() {
        File(dir, "s.ucrypt").writeText("an older container")
        val made = run("split", "make", "-n", "2", "-k", "2", "--base", "https://example.com/p", "-o", "s.ucrypt", "--show", "txt", stdin = secret.toByteArray())
        assertEquals(1, made.code)
        assertTrue("s.ucrypt exists" in made.err, made.err)
        assertEquals("an older container", File(dir, "s.ucrypt").readText())
        assertTrue(!File(dir, "part-1-of-2.txt").exists())
    }

    @Test
    fun pngPartsScanToLinksThatOpenTheContainer() {
        val made = run("split", "make", "-n", "2", "-k", "2", "--base", "https://example.com/p", "-o", "s.ucrypt", "--show", "png", stdin = secret.toByteArray())
        assertEquals(0, made.code, made.err)
        val links = (1..2).map { scan(File(dir, "part-$it-of-2.png")) }
        links.forEachIndexed { index, link -> File(dir, "scanned-$index.txt").writeText(link) }
        assertTrue(links.all { it.startsWith("https://example.com/p#") }, links.toString())
        val joined = run("split", "join", "s.ucrypt", "-p", "scanned-0.txt", "-p", "scanned-1.txt")
        assertEquals(secret, joined.out.decodeToString(), joined.err)
    }

    private fun scan(file: File): String {
        val image = ImageIO.read(file)
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true)).text
    }
}
