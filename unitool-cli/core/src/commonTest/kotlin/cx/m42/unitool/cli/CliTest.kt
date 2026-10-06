package cx.m42.unitool.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class Greet : Command("greet", "Say hello") {
    val who by argument("WHO", "whom").required()
    val times by option("-n", "--times", help = "how many times", metavar = "N").int(1..5).default(1)
    val style by option("-s", "--style", help = "how", metavar = "STYLE").choice("plain", "loud")
    val dry by flag("--dry", help = "print nothing")
    val tail by option("--tail", help = "what to append", metavar = "TEXT").advanced()

    override fun run(io: Io): Int {
        if (dry) return 0
        val word = if (style == "loud") who.uppercase() else who
        repeat(times) { io.write(word + tail.orEmpty() + "\n") }
        return 0
    }
}

private class Echo : Command("echo", "Repeat the text") {
    val text by argument("TEXT", "the text; without it, stdin is read")

    override fun run(io: Io): Int {
        io.write(text ?: io.readStdin().decodeToString())
        return 0
    }
}

private class Boom : Command("boom", "Fail") {
    val how by option("--how", help = "how to fail", metavar = "HOW").choice("fail", "usage", "crash").default("fail")

    override fun run(io: Io): Int = when (how) {
        "fail" -> fail("it did not work", 3)
        "usage" -> usage("not like this")
        else -> error("unexpected")
    }
}

private class Demo : CliApp("demo", "Demo", "An example app") {
    private val echo = Echo()
    override val commands: List<Command> = listOf(Greet(), echo, Boom())
    override val default: Command = echo
}

private class Other : CliApp("other", "Other", "One more app") {
    override val commands: List<Command> = listOf(Greet())
}

class CliTest {
    private val cli = Cli("unitool", "Unitool", "1.2.3", listOf(Demo(), Other()))

    private fun ok(vararg args: String, io: FakeIo = FakeIo()): String {
        val run = cli.exec(*args, io = io)
        assertEquals(0, run.code, run.toString())
        return run.out
    }

    private fun usageError(vararg args: String): String {
        val run = cli.exec(*args)
        assertEquals(2, run.code, run.toString())
        assertEquals("", run.out, "a usage error writes nothing to stdout")
        return run.err
    }

    @Test
    fun everyGNUSpellingOfAnOptionMeansTheSame() {
        for (spelling in listOf(listOf("-n", "2"), listOf("-n2"), listOf("--times", "2"), listOf("--times=2"))) {
            assertEquals("hi\nhi\n", ok("demo", "greet", *spelling.toTypedArray(), "hi"), "$spelling")
        }
        assertEquals("hi\nhi\n", ok("demo", "greet", "hi", "-n", "2"), "options may follow arguments")
        assertEquals("hi\n", ok("demo", "greet", "-n", "5", "-n", "1", "hi"), "the last occurrence wins")
    }

    @Test
    fun choicesIgnoreCaseAndComeBackSpelledAsDeclared() {
        assertEquals("HI\n", ok("demo", "greet", "--style", "LOUD", "hi"))
        assertTrue("expected plain or loud, not 'quiet'" in usageError("demo", "greet", "--style=quiet", "hi"))
    }

    @Test
    fun aDoubleDashEndsTheOptionsAndALoneDashIsAValue() {
        assertEquals("-n\n", ok("demo", "greet", "--", "-n"))
        assertEquals("-\n", ok("demo", "greet", "-"))
    }

    @Test
    fun flagsTakeNoValue() {
        assertEquals("", ok("demo", "greet", "--dry", "hi"))
        assertTrue("--dry takes no value" in usageError("demo", "greet", "--dry=yes", "hi"))
    }

    @Test
    fun usageErrorsNameTheProblemAndPointAtTheHelp() {
        val missing = usageError("demo", "greet")
        assertTrue("unitool demo greet: missing WHO" in missing, missing)
        assertTrue("Help: unitool demo greet --help" in missing, missing)
        assertTrue("unknown option --loud" in usageError("demo", "greet", "--loud", "hi"))
        assertTrue("unexpected argument 'b'" in usageError("demo", "greet", "a", "b"))
        assertTrue("--times needs a value: N" in usageError("demo", "greet", "hi", "--times"))
        assertTrue("expected an integer from 1 to 5, not '9'" in usageError("demo", "greet", "-n", "9", "hi"))
        assertTrue("unknown app 'nope'; available: demo, other" in usageError("nope"))
        assertTrue("unknown option --nope" in usageError("--nope"))
    }

    @Test
    fun theDefaultCommandTakesAWordThatIsNotACommand() {
        assertEquals("hello", ok("demo", "hello"))
        assertEquals("greet", ok("demo", "echo", "greet"), "a command's name wins, so this is how to say it")
        assertEquals("from stdin", ok("demo", io = FakeIo(stdin = "from stdin".encodeToByteArray())),
            "an app with a default reads its pipe when called bare")
        assertTrue("'other' has no command 'hello'; available: greet" in usageError("other", "hello"))
    }

    @Test
    fun failuresExitWithTheirCodeAndOneLine() {
        val failed = cli.exec("demo", "boom")
        assertEquals(3, failed.code)
        assertEquals("unitool demo boom: it did not work\n", failed.err)

        assertEquals(2, cli.exec("demo", "boom", "--how", "usage").code, "a command may refuse a call as usage")

        val crashed = cli.exec("demo", "boom", "--how", "crash")
        assertEquals(1, crashed.code)
        assertTrue("internal error: IllegalStateException: unexpected" in crashed.err, crashed.err)
        assertEquals(3, crashed.err.lines().size, "two lines and no stack trace without UNITOOL_DEBUG: ${crashed.err}")
        val debug = cli.exec("demo", "boom", "--how", "crash", io = FakeIo(environment = mapOf("UNITOOL_DEBUG" to "1")))
        assertTrue(debug.err.lines().size > 5, "the stack trace with UNITOOL_DEBUG=1: ${debug.err}")
    }

    @Test
    fun aReaderThatWentAwayEndsTheRunQuietlyWith141() {
        val inner = FakeIo()
        val closed = object : Io by inner {
            override fun write(bytes: ByteArray) = throw StdoutClosed()
        }
        assertEquals(141, cli.run(listOf("demo", "greet", "hi"), closed))
        assertEquals("", inner.errText, "no error message for a closed pipe")
        assertEquals(141, cli.run(listOf("--help"), closed), "also when it is the host that writes")
    }

    @Test
    fun theHelpIsWrittenFromTheDeclarations() {
        assertEquals(
            """
            |Usage: unitool demo greet [OPTIONS] WHO
            |
            |Say hello.
            |
            |Arguments:
            |  WHO                whom (required)
            |
            |Options:
            |  -n, --times N      how many times (default 1)
            |  -s, --style STYLE  how (plain, loud)
            |      --dry          print nothing
            |      --tail TEXT    what to append
            |  -h, --help         show this help
            |
            """.trimMargin(),
            ok("demo", "greet", "--help"),
        )
        assertEquals(ok("demo", "greet", "--help"), ok("demo", "greet", "hi", "-h"), "-h anywhere among the options")

        val app = ok("demo", "--help")
        assertTrue("Usage: unitool demo COMMAND [OPTIONS]" in app, app)
        assertTrue("  greet  Say hello\n  echo   Repeat the text\n  boom   Fail\n" in app, app)
        assertTrue("Without a command, echo runs: unitool demo TEXT = unitool demo echo TEXT." in app, app)

        val top = ok("--help")
        assertTrue(top.startsWith("Unitool 1.2.3: "), top)
        assertTrue("  demo   An example app\n  other  One more app\n" in top, top)
        assertEquals("unitool 1.2.3\n", ok("--version"))
    }

    @Test
    fun bareCallsAtAPipePrintTheHelpAndExit2() {
        val bare = cli.exec()
        assertEquals(2, bare.code)
        assertTrue("Apps:" in bare.err)
        val app = cli.exec("other")
        assertEquals(2, app.code, "no default and no terminal: nothing sensible to run")
        assertTrue("Commands:" in app.err)
    }

    @Test
    fun theMenuWalksAppCommandAndParametersThenShowsTheSameCall() {
        val io = FakeIo(answers = listOf("1", "greet", "", "world", "", "2", "y"))
        val run = cli.exec(io = io)
        assertEquals(0, run.code, run.toString())
        assertEquals("", run.out, "--dry was chosen")
        assertTrue("  a value is required" in run.err, "an argument is asked until it has a value")
        assertTrue("The same as one command: unitool demo greet --style loud --dry world" in run.err, run.err)
        assertTrue(io.prompts.none { "--tail" in it }, "advanced parameters are not asked")

        val again = cli.exec("demo", "greet", "--style", "loud", "--dry", "world")
        assertEquals(run.code, again.code)
        assertEquals(run.out, again.out)
    }

    @Test
    fun theMenuOutputEqualsTheTypedCall() {
        val run = cli.exec(io = FakeIo(answers = listOf("2", "-here it's", "3", "", "")))
        assertEquals(0, run.code, run.toString())
        val line = run.err.lines().single { it.startsWith("The same as one command: ") }
        assertEquals("The same as one command: unitool other greet --times 3 -- '-here it'\\''s'", line)
        assertEquals("-here it's\n-here it's\n-here it's\n", run.out)
        assertEquals(run.out, ok("other", "greet", "--times", "3", "--", "-here it's"))
    }

    @Test
    fun theMenuReAsksABadAnswerThroughTheParametersOwnCheck() {
        val run = cli.exec("other", io = FakeIo(answers = listOf("x", "9", "2", "1", "")))
        assertEquals(0, run.code, run.toString())
        assertTrue("--times: expected an integer from 1 to 5, not '9'" in run.err, run.err)
        assertEquals("x\nx\n", run.out)
    }

    @Test
    fun leavingTheMenuWithCtrlDExits130() {
        val run = cli.exec(io = FakeIo(answers = listOf("1")))
        assertEquals(130, run.code)
        assertTrue("Cancelled." in run.err)
    }

    @Test
    fun declarationsThatCannotWorkFailAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            object : Command("bad", "x") {
                val a by option("--same", help = "a")
                val b by flag("--same", help = "b")
                override fun run(io: Io) = 0
            }
        }
        assertFailsWith<IllegalArgumentException> {
            object : Command("bad", "x") {
                val a by argument("A", "a")
                val b by argument("B", "b").required()
                override fun run(io: Io) = 0
            }
        }
        assertFailsWith<IllegalArgumentException> {
            object : Command("bad", "x") {
                val h by flag("-h", help = "help")
                override fun run(io: Io) = 0
            }
        }
        assertFailsWith<IllegalArgumentException> { Cli("u", "U", "1", listOf(Demo(), Demo())) }
        assertFailsWith<IllegalArgumentException> { object : CliApp("Bad_Id", "x", "y") { override val commands = listOf(Echo()) } }
    }
}
