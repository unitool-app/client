package cx.m42.unitool.cli.password

import cx.m42.unitool.cli.Cli
import cx.m42.unitool.cli.FakeIo
import cx.m42.unitool.cli.Run
import cx.m42.unitool.cli.exec
import cx.m42.unitool.kit.password.PasswordGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The command as a person uses it. Whether the passwords are random is the kit's tests' job. */
class PasswordAppTest {
    private val cli = Cli("unitool", "Unitool", "test", listOf(PasswordApp()))

    private fun ok(vararg args: String, io: FakeIo = FakeIo()): Run =
        cli.exec(*args, io = io).also { assertEquals(0, it.code, it.toString()) }

    private fun lines(run: Run) = run.out.removeSuffix("\n").split('\n')

    @Test
    fun aBarePasswordIsTwentyCharactersOnOneLine() {
        val run = ok("password")
        val password = lines(run).single()
        assertEquals(PasswordGenerator.DEFAULT_LENGTH, password.length)
        assertEquals("", run.err, "nothing on stderr for a script")
        assertEquals(PasswordGenerator.DEFAULT_LENGTH, lines(ok("password", "generate")).single().length)
    }

    @Test
    fun lengthKindsAndCount() {
        val many = lines(ok("password", "-l", "32", "--no-symbols", "-n", "5"))
        assertEquals(5, many.size)
        assertTrue(many.all { it.length == 32 && it.all(Char::isLetterOrDigit) }, many.toString())
        assertEquals(5, many.toSet().size)
        val digits = lines(ok("password", "--length=12", "--no-lower", "--no-upper", "--no-symbols", "--look-alike")).single()
        assertTrue(digits.all(Char::isDigit), digits)
    }

    @Test
    fun wordsAndMixed() {
        val words = lines(ok("password", "--mode", "words", "-w", "12")).single().split(' ')
        assertEquals(12, words.size)
        assertTrue(words.all { it in PasswordGenerator.wordList })
        val mixed = lines(ok("password", "-m", "mixed", "-w", "4", "--no-symbols")).single()
        assertEquals(4, mixed.split(Regex("[2-9]")).filter { it in PasswordGenerator.wordList }.size, mixed)
    }

    @Test
    fun theBitsAreSaidOnlyToAPerson() {
        val run = ok("password", "-m", "words", io = FakeIo(answers = emptyList()))
        assertEquals("66 bits of entropy each", run.err.trim())
    }

    @Test
    fun theMouseIsMixedIn() {
        val moves = List(400) { i -> (i * 7 + (i * i) % 11) % 200 to (i * 13 % 50) }
        val run = ok("password", "--mouse", "-n", "2", io = FakeIo(mouse = moves))
        assertEquals(2, lines(run).size)
        assertTrue(lines(run).all { it.length == PasswordGenerator.DEFAULT_LENGTH })
        assertTrue("from 400 moves" in run.err, run.err)
        assertEquals(2, cli.exec("password", "--mouse", io = FakeIo()).code, "no terminal: a usage error")
    }

    @Test
    fun impossibleRulesAreAUsageError() {
        assertEquals(2, cli.exec("password", "--no-lower", "--no-upper", "--no-digits", "--no-symbols", io = FakeIo()).code)
        assertEquals(2, cli.exec("password", "-m", "mixed", "--no-digits", "--no-symbols", io = FakeIo()).code)
        assertEquals(2, cli.exec("password", "-l", "3", io = FakeIo()).code)
    }
}
