package cx.m42.unitool.kit.password

import cx.m42.unitool.kit.crypto.Sha256
import cx.m42.unitool.kit.crypto.hex
import kotlin.math.abs
import kotlin.math.log2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordGeneratorTest {
    private fun near(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 1e-6, "$expected ≠ $actual")

    @Test
    fun theWordListIsBip39English() {
        val file = PasswordGenerator.wordList.joinToString("") { "$it\n" }
        assertEquals("2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda", Sha256.digest(file.encodeToByteArray()).hex())
        assertEquals(2048, PasswordGenerator.wordList.toSet().size)
    }

    @Test
    fun charactersHaveTheLengthAndEveryChosenKind() {
        val rules = PasswordRules()
        repeat(200) {
            val password = PasswordGenerator.generate(rules)
            assertEquals(PasswordGenerator.DEFAULT_LENGTH, password.length)
            rules.kinds.forEach { kind -> assertTrue(password.any { it in kind }, "$password lacks one of $kind") }
            assertTrue(password.none { it in "lIO01\"',\\ " }, password)
        }
        repeat(50) { assertEquals(4, PasswordGenerator.generate(PasswordRules(length = 4)).length) }
    }

    @Test
    fun onlyTheChosenKindsAreUsed() {
        val digits = PasswordGenerator.generate(PasswordRules(length = 40, lower = false, upper = false, symbols = false, lookAlike = true))
        assertTrue(digits.all { it.isDigit() }, digits)
        val letters = PasswordGenerator.generate(PasswordRules(length = 64, digits = false, symbols = false))
        assertTrue(letters.all { it.isLetter() }, letters)
    }

    @Test
    fun wordsAreAPassphraseAndMixedJoinsThemWithCharacters() {
        val words = PasswordGenerator.generate(PasswordRules(mode = PasswordMode.Words, words = 12)).split(' ')
        assertEquals(12, words.size)
        assertTrue(words.all { it in PasswordGenerator.wordList }, words.toString())

        val rules = PasswordRules(mode = PasswordMode.Mixed, words = 5, lower = false, upper = false, symbols = false)
        val mixed = PasswordGenerator.generate(rules)
        val parts = mixed.split(Regex("[2-9]"))
        assertEquals(5, parts.size, mixed)
        assertTrue(parts.all { it in PasswordGenerator.wordList }, mixed)
        assertEquals(4, mixed.count { it.isDigit() }, mixed)

        // Letters never join words: they would glue two into one.
        val glued = PasswordGenerator.generate(PasswordRules(mode = PasswordMode.Mixed, words = 8, digits = false))
        assertEquals(8, glued.split(Regex("[^a-z]")).size, glued)
        assertNotEquals(null, PasswordRules(mode = PasswordMode.Mixed, digits = false, symbols = false).problem)
    }

    @Test
    fun entropyIsCountedExactly() {
        val digits = PasswordRules(length = 10, lower = false, upper = false, symbols = false)
        near(30.0, PasswordGenerator.entropyBits(digits))
        near(66.0, PasswordGenerator.entropyBits(PasswordRules(mode = PasswordMode.Words, words = 6)))
        near(5 * 11 + 4 * log2(8.0), PasswordGenerator.entropyBits(PasswordRules(mode = PasswordMode.Mixed, words = 5, lower = false, upper = false, symbols = false)))
        // "Every kind at least once" takes a little off 20 free draws from 70 characters.
        val all = PasswordGenerator.entropyBits(PasswordRules(length = 20))
        assertTrue(all < 20 * log2(70.0) && all > 20 * log2(70.0) - 0.5, "$all")
        assertTrue(PasswordGenerator.entropyBits(PasswordRules(length = 128)).isFinite())
    }

    @Test
    fun drawsAreUniform() {
        // A fake source that gives every 16-bit value once, in order: each digit must come up
        // exactly as often as every other, which a modulo bias would break.
        var v = 0
        val everyValue: (Int) -> ByteArray = { size ->
            ByteArray(size) { i -> (if (i % 2 == 0) v shr 8 else v++).toByte() }
        }
        // 128 characters are 256 bytes, whole batches, so no value is fetched and left unused.
        val rules = PasswordRules(length = 128, lower = false, upper = false, symbols = false)
        val seen = List(65536 / 128) { PasswordGenerator.generate(rules, everyValue) }.joinToString("").groupingBy { it }.eachCount()
        assertEquals(8, seen.size)
        assertEquals(setOf(8192), seen.values.toSet(), seen.toString())

        // And the real source, roughly: 13 symbols over 39 000 draws.
        val symbols = PasswordRules(length = 128, lower = false, upper = false, digits = false)
        val counts = List(300) { PasswordGenerator.generate(symbols) }.joinToString("").groupingBy { it }.eachCount().values
        assertEquals(13, counts.size)
        assertTrue(counts.max() - counts.min() < counts.average() * 0.15, counts.toString())
    }

    @Test
    fun badRulesAreRefused() {
        assertNotEquals(null, PasswordRules(lower = false, upper = false, digits = false, symbols = false).problem)
        assertNull(PasswordRules(mode = PasswordMode.Words, lower = false, upper = false, digits = false, symbols = false).problem)
        assertFailsWith<IllegalArgumentException> { PasswordGenerator.generate(PasswordRules(length = 3)) }
        assertFailsWith<IllegalArgumentException> { PasswordGenerator.generate(PasswordRules(mode = PasswordMode.Words, words = 2)) }
    }
}
