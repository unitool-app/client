package cx.m42.unitool.cli.split

import cx.m42.unitool.cli.Cli
import cx.m42.unitool.cli.FakeIo
import cx.m42.unitool.cli.Run
import cx.m42.unitool.cli.exec
import cx.m42.unitool.kit.crypto.Hash
import cx.m42.unitool.kit.crypt.Ucrypt
import cx.m42.unitool.kit.split.PartReading
import cx.m42.unitool.kit.split.SplitLink
import cx.m42.unitool.kit.split.SplitPart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `unitool split` as a person and a script use it (.ai-notes/done/split 03 §3), on the JVM and natively:
 * the commands, the refusals before anything is typed, the hidden questions, the screens, the
 * wizard and the line it ends with.
 */
class SplitAppTest {
    private val cli = Cli("unitool", "Unitool", "test", listOf(SplitApp()))
    private val secret = "my seed phrase: correct horse battery staple\nсекрет\n"
    private val base = "https://example.com/p"

    private fun run(vararg args: String, io: FakeIo = FakeIo()): Run = cli.exec(*args, io = io)

    private fun ok(vararg args: String, io: FakeIo = FakeIo()): Run = run(*args, io = io).also { assertEquals(0, it.code, it.toString()) }

    /** `make` with text parts into [io]'s files; returns the links of parts 1..n. */
    private fun make(io: FakeIo, n: Int = 3, k: Int = 2, out: String = "s.ucrypt"): List<String> {
        ok("split", "make", "-n", "$n", "-k", "$k", "--base", base, "-o", out, "--show", "txt", io = io)
        return (1..n).map { io.files.getValue("part-$it-of-$n.txt").decodeToString().trim() }
    }

    private fun stdinIo(text: String = secret) = FakeIo(stdin = text.encodeToByteArray())

    @Test
    fun makeWritesAPrivateContainerAndOnePrivateFilePerPart() {
        val io = stdinIo()
        val links = make(io)
        assertEquals(setOf("s.ucrypt", "part-1-of-3.txt", "part-2-of-3.txt", "part-3-of-3.txt"), io.files.keys)
        assertEquals(io.files.keys, io.privateFiles)
        assertTrue(Ucrypt.isUcrypt(io.files.getValue("s.ucrypt")))
        links.forEach { link ->
            assertTrue(link.startsWith("$base#"), link)
            assertIs<PartReading.Ok>(SplitLink.read(link))
        }
        assertEquals(1, links.map { (SplitLink.read(it) as PartReading.Ok).part.setIdHex }.toSet().size)
        assertTrue("Any 2 of the 3 open the secret. 1 part may be lost; losing 2 loses the secret for good." in io.errText, io.errText)
        assertEquals("", io.outText)
    }

    @Test
    fun anyTwoPartFilesOpenTheContainer() {
        val io = stdinIo()
        make(io)
        for (pair in listOf(1 to 2, 3 to 1, 2 to 3)) {
            io.clearOutput()
            val joined = ok("split", "join", "s.ucrypt", "-p", "part-${pair.first}-of-3.txt", "-p", "part-${pair.second}-of-3.txt", io = io)
            assertEquals(secret, joined.out, "$pair")
        }
    }

    @Test
    fun joinAsksForTheMissingPartsHiddenAndExplainsBadOnes() {
        val made = stdinIo()
        val links = make(made)
        val other = stdinIo("other").also { make(it) }.files.getValue("part-1-of-3.txt").decodeToString().trim()
        val typo = links[1].dropLast(1) + (if (links[1].last() == 'A') 'B' else 'A')
        val io = FakeIo(answers = listOf("hello", typo, other, links[1], links[1], links[2]))
        io.files["s.ucrypt"] = made.files.getValue("s.ucrypt")
        val run = ok("split", "join", "s.ucrypt", io = io)
        assertEquals(secret, run.out)
        val prompt = { n: Int -> "Part $n of 2 (the link, or its 64 characters): " }
        assertEquals(List(4) { prompt(1) } + List(2) { prompt(2) }, io.secretPrompts)
        assertTrue("not a part: a part is a link ending in '#' and 64 characters" in run.err, run.err)
        assertTrue("not a whole part: a typo, or the link was cut short" in run.err, run.err)
        assertTrue("this part is from another set" in run.err, run.err)
        assertTrue("this part is part 2 again, like part 1" in run.err, run.err)
        assertFalse(links[1].substringAfter('#') in run.err, "a part showed on the screen")
    }

    @Test
    fun theContainerGoesToStdoutAsBinaryOrText() {
        val pipe = stdinIo()
        ok("split", "make", "-n", "2", "-k", "2", "--base", base, "--show", "txt", io = pipe)
        assertTrue(Ucrypt.isUcrypt(pipe.outBytes))
        val text = stdinIo()
        ok("split", "make", "-n", "2", "-k", "2", "--base", base, "--show", "txt", "-a", io = text)
        assertTrue(text.outText.startsWith("-----BEGIN UCRYPT FILE-----\n"), text.outText)

        // The armored container on stdin opens like the file.
        val join = FakeIo(stdin = text.outBytes)
        join.files.putAll(text.files)
        assertEquals(secret, ok("split", "join", "-p", "part-1-of-2.txt", "-p", "part-2-of-2.txt", io = join).out)
    }

    @Test
    fun mistakesAreRefusedBeforeTheSecretIsTypedAndNothingIsWritten() {
        fun refused(code: Int, message: String, vararg args: String, io: FakeIo = stdinIo()) {
            val run = run("split", "make", *args, io = io)
            assertEquals(code, run.code, run.toString())
            assertTrue(message in run.err, run.err)
        }
        val good = arrayOf("--base", base, "-o", "s.ucrypt", "--show", "txt")
        refused(2, "--need: from 2 to 2 parts, not 3", "-n", "2", "-k", "3", *good)
        refused(2, "--total: expected an integer from 2 to 5, not '6'", "-n", "6", "-k", "2", *good)
        refused(2, "--need: expected an integer from 2 to 5, not '1'", "-n", "3", "-k", "1", *good)
        refused(2, "--base: the address starts with https:// or http://", "-n", "3", "-k", "2", "--base", "example.com", "--show", "txt")
        refused(2, "--base: the address has a '#' of its own", "-n", "3", "-k", "2", "--base", "$base#x", "--show", "txt")
        refused(2, "--show screen needs a terminal on stdout", "-n", "3", "-k", "2", "--base", base, "-o", "s.ucrypt", "--show", "screen")
        refused(2, "--show: give png, svg or txt", "-n", "3", "-k", "2", "--base", base)
        val screen = FakeIo(stdin = secret.encodeToByteArray(), stdoutIsTerminal = true)
        refused(2, "with --show screen the container goes to a file", "-n", "3", "-k", "2", "--base", base, io = screen)

        val taken = stdinIo().also { it.files["part-2-of-3.txt"] = "mine".encodeToByteArray() }
        refused(1, "part-2-of-3.txt exists. Nothing was written", "-n", "3", "-k", "2", *good, io = taken)
        assertEquals(setOf("part-2-of-3.txt"), taken.files.keys)
        assertEquals("mine", taken.files.getValue("part-2-of-3.txt").decodeToString())

        refused(1, "empty secret", "-n", "3", "-k", "2", *good, io = FakeIo())
    }

    @Test
    fun onTheScreenEachPartIsAloneAndTheScreenIsClearedAfter() {
        val io = FakeIo(stdin = secret.encodeToByteArray(), stdoutIsTerminal = true, keyboard = true)
        ok("split", "make", "-n", "3", "-k", "2", "--base", base, "-o", "s.ucrypt", "--show", "screen", io = io)
        val screens = io.outText.split(CLEAR_ALL)
        assertEquals("", screens.first())
        assertEquals("", screens.last(), "the last part stays on the screen")
        val shown = screens.drop(1).dropLast(1)
        assertEquals(3, shown.size)
        shown.forEach { screen -> assertEquals(1, Regex("""https://example\.com/p#[A-Za-z0-9_-]{64}""").findAll(screen).count(), screen) }
        assertEquals(4, io.pauses.size)
        assertTrue(io.pauses.first().startsWith("Ready to show part 1 of 3?"))

        // The parts on the screen open the container.
        val links = shown.map { Regex("""https://example\.com/p#[A-Za-z0-9_-]{64}""").find(it)!!.value }
        val join = FakeIo(answers = links.drop(1))
        join.files["s.ucrypt"] = io.files.getValue("s.ucrypt")
        assertEquals(secret, ok("split", "join", "s.ucrypt", io = join).out)
    }

    @Test
    fun withoutAKeyboardTheScreenRefusesRatherThanShowAllPartsAtOnce() {
        val io = FakeIo(stdin = secret.encodeToByteArray(), stdoutIsTerminal = true, keyboard = false)
        val run = run("split", "make", "-n", "3", "-k", "2", "--base", base, "-o", "s.ucrypt", "--show", "screen", io = io)
        assertEquals(1, run.code)
        assertTrue("no keyboard to wait on between parts" in run.err, run.err)
        assertFalse(base in run.out)
    }

    @Test
    fun aForgedPartAmongMoreThanItTakesIsNamedAndTheRestOpen() {
        val io = stdinIo()
        make(io)
        val bytes = (SplitLink.read(io.files.getValue("part-2-of-3.txt").decodeToString()) as PartReading.Ok).part.bytes()
        bytes[30] = (bytes[30].toInt() xor 1).toByte()
        // Recompute the check, as a forger would.
        val forged = forge(bytes)
        io.files["part-2-of-3.txt"] = (SplitLink.format(forged, base) + "\n").encodeToByteArray()
        val run = ok("split", "join", "s.ucrypt", "-p", "part-1-of-3.txt", "-p", "part-2-of-3.txt", "-p", "part-3-of-3.txt", io = io)
        assertEquals(secret, run.out)
        assertTrue("Warning: part-2-of-3.txt does not belong with the others" in run.err, run.err)

        io.clearOutput()
        val failed = run("split", "join", "s.ucrypt", "-p", "part-1-of-3.txt", "-p", "part-2-of-3.txt", io = io)
        assertEquals(1, failed.code)
        assertTrue("the parts do not open s.ucrypt" in failed.err, failed.err)
        assertTrue("With one more part, unitool can tell which." in failed.err)
    }

    @Test
    fun joinNamesWhatIsWrongWithAContainer() {
        val io = stdinIo()
        make(io)
        val file = io.files.getValue("s.ucrypt")
        io.files["cut.ucrypt"] = file.copyOf(file.size - 1)
        io.files["text.txt"] = "hello".encodeToByteArray()
        fun fails(message: String, vararg args: String) {
            val run = run("split", "join", *args, "-p", "part-1-of-3.txt", "-p", "part-2-of-3.txt", io = io)
            assertEquals(1, run.code, run.toString())
            assertTrue(message in run.err, run.err)
            io.clearOutput()
        }
        fails("cut.ucrypt is cut short", "cut.ucrypt")
        fails("text.txt is not an encrypted container", "text.txt")
        fails("cannot read nothing.ucrypt", "nothing.ucrypt")
        val terminal = FakeIo(answers = emptyList())
        val run = run("split", "join", io = terminal)
        assertEquals(2, run.code)
        assertTrue("give the container FILE" in run.err)
    }

    @Test
    fun inspectSaysWhatAContainerAndAPartAre() {
        val io = stdinIo()
        make(io)
        val container = ok("split", "inspect", "s.ucrypt", io = io).out
        assertTrue(container.startsWith("ucrypt 1.0 container\n"), container)
        assertTrue("opened by:    any 2 of 3 parts of set " in container, container)
        assertTrue("decrypts to:  ${secret.encodeToByteArray().size} bytes" in container, container)
        io.clearOutput()
        val part = ok("split", "inspect", "part-3-of-3.txt", io = io).out
        assertTrue(part.startsWith("part 3 of 3; any 2 open the secret\n"), part)
        assertFalse(io.files.getValue("part-3-of-3.txt").decodeToString().substringAfter('#').trim() in part)
    }

    @Test
    fun theWizardMakesWhatItsClosingCommandWouldMake() {
        val io = FakeIo(stdin = secret.encodeToByteArray(), answers = listOf("make", "", "", "nope", base, "", "txt", "", "y"))
        val run = cli.exec("split", io = io)
        assertEquals(0, run.code, run.toString())
        assertTrue("the address starts with https:// or http://" in run.err, run.err)
        assertTrue("  Any 2 of the 3 open the secret." in run.err)
        val line = run.err.lines().first { it.startsWith("The same as one command: ") }.substringAfter(": ")
        assertEquals("unitool split make -n 3 -k 2 --base $base -o secret.ucrypt --show txt", line)
        assertEquals(setOf("secret.ucrypt", "part-1-of-3.txt", "part-2-of-3.txt", "part-3-of-3.txt"), io.files.keys)

        // The same line, run as a command, makes the same files.
        val again = stdinIo()
        ok(*line.split(' ').drop(1).toTypedArray(), io = again)
        assertEquals(io.files.keys, again.files.keys)
    }

    @Test
    fun theWizardAsksForANewNameRatherThanReplaceAContainer() {
        val io = FakeIo(stdin = secret.encodeToByteArray(), answers = listOf("1", "2", "2", base, "", "mine.ucrypt", "4", "", "y"))
        io.files["secret.ucrypt"] = "old".encodeToByteArray()
        assertEquals(0, cli.exec("split", io = io).code)
        assertEquals("old", io.files.getValue("secret.ucrypt").decodeToString())
        assertTrue("secret.ucrypt exists. A container is never replaced" in io.errText, io.errText)
        assertTrue("mine.ucrypt" in io.files)
        assertTrue("All 2 parts are needed: losing any one loses the secret for good." in io.errText, io.errText)
    }

    @Test
    fun theWizardJoinsWithHiddenParts() {
        val made = stdinIo()
        val links = make(made, out = "secret.ucrypt")
        val io = FakeIo(answers = listOf("join", "", "", links[2], links[0]))
        io.files.putAll(made.files)
        val run = cli.exec("split", io = io)
        assertEquals(0, run.code, run.toString())
        assertEquals(secret, run.out)
        assertTrue("It takes 2 of its 3 parts." in run.err, run.err)
        assertEquals(2, io.secretPrompts.size)
        assertTrue("The same as one command: unitool split join secret.ucrypt" in run.err, run.err)
    }

    @Test
    fun ctrlDInTheWizardLeavesWith130AndWritesNothing() {
        val io = FakeIo(stdin = secret.encodeToByteArray(), answers = listOf("make", "3", "2"))
        assertEquals(130, cli.exec("split", io = io).code)
        assertTrue(io.files.isEmpty())
    }

    @Test
    fun theWizardCanBeDeclinedAtTheSummary() {
        val io = FakeIo(stdin = secret.encodeToByteArray(), answers = listOf("make", "", "", base, "", "txt", "", "n"))
        assertEquals(0, cli.exec("split", io = io).code)
        assertTrue("Nothing was done." in io.errText)
        assertTrue(io.files.isEmpty())
    }

    @Test
    fun theHelpListsTheCommandsAndRepeatableParts() {
        val help = ok("split", "--help").out
        listOf("make", "join", "inspect").forEach { assertTrue("  $it" in help, help) }
        val join = ok("split", "join", "--help").out
        assertTrue("-p, --part FILE" in join && "repeatable" in join, join)
        val atPipe = run("split")
        assertEquals(2, atPipe.code)
    }

    private fun forge(bytes: ByteArray): SplitPart {
        val check = Hash.SHA256.digest("ucrypt/1 split part".encodeToByteArray() + bytes.copyOf(44)).copyOf(4)
        check.copyInto(bytes, 44)
        return (SplitPart.read(bytes) as PartReading.Ok).part
    }
}
