package cx.m42.unitool.cli.qr

import cx.m42.unitool.cli.CLEAR_SCREEN
import cx.m42.unitool.cli.Cli
import cx.m42.unitool.cli.FakeIo
import cx.m42.unitool.cli.Run
import cx.m42.unitool.cli.exec
import cx.m42.unitool.kit.qr.ErrorCorrection
import cx.m42.unitool.kit.qr.Qr
import cx.m42.unitool.kit.qr.QrImage
import cx.m42.unitool.kit.qr.QrTerminal
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The command as a person uses it, on every target, the native binary's included. What it prints is
 * exactly what the kit's QR service renders; whether that is right is the kit's own tests' job.
 */
class QrTest {
    private val cli = Cli("unitool", "Unitool", "test", listOf(QrApp()))

    private fun run(vararg args: String, io: FakeIo = FakeIo()): Run = cli.exec(*args, io = io)

    private fun ok(vararg args: String, io: FakeIo = FakeIo()): Run =
        run(*args, io = io).also { assertEquals(0, it.code, it.toString()) }

    private fun code(text: String, level: ErrorCorrection = ErrorCorrection.L) = Qr.encode(text, level)!!

    @Test
    fun terminalOutputIsTheKitsAnsi() {
        val text = "https://m42.cx/unitool?from=cli"
        assertEquals(QrTerminal.ansi(code(text)), ok("qr", "encode", text).out)
        assertEquals(QrTerminal.ansi(code(text), margin = 0), ok("qr", text, "--margin", "0").out)
    }

    @Test
    fun filesAreTheKitsPictures() {
        val io = FakeIo()
        ok("qr", "encode", "Привет", "-o", "code.svg", "--ec", "q", io = io)
        ok("qr", "encode", "hello", "-o", "code.png", "--scale", "5", io = io)
        assertEquals(QrImage.svg(code("Привет", ErrorCorrection.Q)), io.files.getValue("code.svg").decodeToString())
        assertContentEquals(QrImage.png(code("hello"), margin = 4, scale = 5), io.files.getValue("code.png"))
    }

    @Test
    fun formatFollowsTheFileNameUnlessGiven() {
        val io = FakeIo()
        ok("qr", "hello", "-o", "a.svg", io = io)
        ok("qr", "hello", "-o", "b.png", io = io)
        ok("qr", "hello", "-o", "c.txt", io = io)
        ok("qr", "hello", "-o", "d.svg", "--format", "png", io = io)
        ok("qr", "hello", "-o", "dir.v2/plain", io = io)
        val png = QrImage.png(code("hello"))
        assertEquals(QrImage.svg(code("hello")), io.files.getValue("a.svg").decodeToString())
        assertContentEquals(png, io.files.getValue("b.png"))
        assertEquals(QrTerminal.ansi(code("hello")), io.files.getValue("c.txt").decodeToString(), "an unknown extension is ansi")
        assertContentEquals(png, io.files.getValue("d.svg"), "--format wins over the name")
        assertTrue(io.files.getValue("dir.v2/plain").decodeToString().startsWith("\u001b["), "a dot in a directory is not an extension")
    }

    @Test
    fun stdinIsTheTextWithoutItsFinalLineBreak() {
        assertEquals(QrTerminal.ansi(code("from a pipe")), ok("qr", io = FakeIo(stdin = "from a pipe\n".encodeToByteArray())).out)
    }

    @Test
    fun pngGoesToAPipeButNotOntoTheScreen() {
        assertContentEquals(QrImage.png(code("hello")), ok("qr", "hello", "-f", "png").io.outBytes)
        val screen = run("qr", "hello", "-f", "png", io = FakeIo(stdoutIsTerminal = true))
        assertEquals(2, screen.code)
        assertTrue("PNG is not printed to a terminal" in screen.err, screen.err)
        assertEquals("", screen.out)
    }

    @Test
    fun tooLongWithoutSplitFailsWithTheNumbersAndTheWayOut() {
        val result = run("qr", "a".repeat(2954))
        assertEquals(1, result.code)
        assertTrue("2954 bytes, and at --ec L one holds at most 2953. --split cuts" in result.err, result.err)
        assertEquals("", result.out)
    }

    @Test
    fun splitWritesNumberedFiles() {
        val text = accounts(60)
        val parts = Qr.split(text)
        assertTrue(parts.size >= 2)
        val io = FakeIo(stdin = text.encodeToByteArray())
        ok("qr", "--split", "-o", "out/export.png", io = io)
        assertEquals(parts.indices.map { "out/export-${it + 1}.png" }, io.files.keys.toList())
        parts.forEachIndexed { i, part -> assertContentEquals(QrImage.png(code(part)), io.files.getValue("out/export-${i + 1}.png")) }
        assertTrue("${parts.size} codes: export-1.png" in io.errText, io.errText)
    }

    @Test
    fun splitOnTheScreenWaitsForEnterAndClearsBetweenCodes() {
        val text = accounts(60)
        val parts = Qr.split(text)
        val io = FakeIo(stdin = text.encodeToByteArray(), stdoutIsTerminal = true, keyboard = true)
        val result = ok("qr", "encode", "--split", io = io)
        assertEquals(List(parts.size - 1) { "Press Enter for the next code" }, io.pauses, "a pause between codes, none after the last")
        assertEquals(parts.map { QrTerminal.ansi(code(it)) }, result.out.split(CLEAR_SCREEN))
        assertTrue("Code 1 of ${parts.size}" in result.err)
    }

    @Test
    fun splitIntoAPipeOrAFilePrintsTheCodesOneAfterAnother() {
        val parts = Qr.split(accounts(60))
        val piped = ok("qr", "--split", io = FakeIo(stdin = accounts(60).encodeToByteArray()))
        assertFalse(CLEAR_SCREEN in piped.out, "nothing to clear when nobody watches")
        assertEquals(parts.joinToString("\n") { QrTerminal.ansi(code(it)) }, piped.out, "an empty line between codes")

        val toFile = FakeIo(stdin = accounts(60).encodeToByteArray(), keyboard = true)
        val redirected = ok("qr", "--split", io = toFile)
        assertEquals(emptyList(), toFile.pauses, "stdout is a file: nobody looks at it, nothing to wait for")
        assertEquals(piped.out, redirected.out)

        val several = run("qr", "--split", "-f", "svg", io = FakeIo(stdin = accounts(60).encodeToByteArray()))
        assertEquals(2, several.code, "several SVGs cannot share stdout")
    }

    @Test
    fun aLineTooLongForAnyCodeFailsEvenWithSplit() {
        val result = run("qr", "--split", io = FakeIo(stdin = ("short\n" + "x".repeat(3000)).encodeToByteArray()))
        assertEquals(1, result.code)
        assertTrue("line 2 does not fit a QR code even on its own: 3000 bytes, and at --ec L one holds at most 2953" in result.err, result.err)
    }

    @Test
    fun badInputIsRefused() {
        assertEquals(1, run("qr", io = FakeIo(stdin = ByteArray(0))).code)
        assertEquals(2, run("qr", "hello", "--ec", "X").code)
        assertEquals(2, run("qr", "hello", "--scale", "0").code)
        val missing = run("qr", "hello", "-o", "no/such/code.png", io = FakeIo(writable = { !it.startsWith("no/") }))
        assertEquals(1, missing.code)
        assertTrue("cannot write no/such/code.png" in missing.err, missing.err)
    }

    @Test
    fun theMenuAsksTheEverydayParametersAndPrintsTheSameCall() {
        val io = FakeIo(answers = listOf("hello menu", "", "", ""))
        val result = ok(io = io)
        assertEquals(4, io.prompts.size, "TEXT, --out, --format, --ec; the advanced ones are not asked: ${io.prompts}")
        assertTrue("The same as one command: unitool qr encode 'hello menu'" in result.err, result.err)
        assertEquals(QrTerminal.ansi(code("hello menu")), result.out)
    }

    @Test
    fun theHelpListsEveryParameter() {
        val help = ok("qr", "encode", "--help").out
        for (name in listOf("TEXT", "-o, --out FILE", "-f, --format FORMAT", "--ec LEVEL", "--split", "--margin N", "--scale N")) {
            assertTrue(name in help, "$name in\n$help")
        }
        assertTrue("(ansi, png, svg)" in help, help)
        assertTrue("(L, M, Q, H; default L)" in help, help)
    }

    internal companion object {
        fun accounts(count: Int): String = (1..count).joinToString("\n") {
            "otpauth://totp/Issuer$it:user$it@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Issuer$it&digits=6&period=30"
        }
    }
}
