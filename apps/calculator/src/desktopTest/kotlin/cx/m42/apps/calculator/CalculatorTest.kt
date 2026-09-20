package cx.m42.apps.calculator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arithmetic, with no UI in sight.
 *
 * These came across from Unitool unchanged, which is the point: `press` is a pure function over a
 * value, so moving the calculator into a framework could not have altered what `2 + 3 ×` means —
 * and if it had, these would say so.
 */
class CalculatorTest {

    private fun state(vararg keys: Key): CalculatorState =
        keys.fold(CalculatorState()) { acc, key -> acc.press(key) }

    private fun digits(text: String): List<Key> = text.map { char ->
        if (char == '.') Key.Dot else Key.Digit(char.digitToInt())
    }

    @Test
    fun typingDigitsBuildsANumber() {
        assertEquals("120", state(*digits("120").toTypedArray()).entry)
    }

    @Test
    fun aResultIsReplacedByTheNextNumberRatherThanGrown() {
        val after = state(Key.Digit(1), Key.Operator(Op.Plus), Key.Digit(2), Key.Equals, Key.Digit(5))
        assertEquals("5", after.entry)
    }

    @Test
    fun chainingResolvesLeftToRightWithNoPrecedence() {
        // What a pocket calculator does: `2 + 3 ×` shows 5 and goes on multiplying. Precedence
        // would need an expression the display cannot show.
        val after = state(
            Key.Digit(2), Key.Operator(Op.Plus), Key.Digit(3), Key.Operator(Op.Times),
            Key.Digit(4), Key.Equals,
        )
        assertEquals("20", after.entry)
    }

    @Test
    fun aSecondOperatorReplacesTheFirst() {
        val after = state(Key.Digit(6), Key.Operator(Op.Plus), Key.Operator(Op.Times), Key.Digit(2), Key.Equals)
        assertEquals("12", after.entry)
    }

    @Test
    fun dividingByZeroLatchesAnErrorUntilItIsCleared() {
        val failed = state(Key.Digit(5), Key.Operator(Op.Divide), Key.Digit(0), Key.Equals)
        assertTrue(failed.error)
        // Every key but Clear is inert: resuming from a number the reader never saw is worse than
        // saying nothing.
        assertTrue(failed.press(Key.Digit(9)).error)
        assertEquals("0", failed.press(Key.Clear).entry)
    }

    @Test
    fun percentAfterAnAdditionIsAPercentageOfTheLeftHandSide() {
        // What a person adding VAT means by it.
        val after = state(
            *digits("200").toTypedArray(), Key.Operator(Op.Plus), *digits("10").toTypedArray(),
            Key.Percent, Key.Equals,
        )
        assertEquals("220", after.entry)
    }

    @Test
    fun aBareNumberWithPercentIsAHundredthOfItself() {
        assertEquals("0.5", state(*digits("50").toTypedArray(), Key.Percent).entry)
    }

    @Test
    fun theScientificKeysActOnWhatIsOnTheDisplay() {
        assertEquals("81", state(*digits("9").toTypedArray(), Key.Unary(UnaryOp.Square)).entry)
        assertEquals("5", state(*digits("25").toTypedArray(), Key.Unary(UnaryOp.Root)).entry)
        assertEquals("0.25", state(*digits("4").toTypedArray(), Key.Unary(UnaryOp.Reciprocal)).entry)
    }

    @Test
    fun anImpossibleScientificKeyLatchesTheSameErrorADivisionByZeroDoes() {
        assertTrue(state(*digits("9").toTypedArray(), Key.Sign, Key.Unary(UnaryOp.Root)).error)
        assertTrue(state(Key.Digit(0), Key.Unary(UnaryOp.Reciprocal)).error)
    }

    @Test
    fun backspaceOnAResultClearsItRatherThanEditingIt() {
        // A result has no characters to take back — the sum that produced it is gone.
        val after = state(Key.Digit(8), Key.Operator(Op.Plus), Key.Digit(4), Key.Equals, Key.Backspace)
        assertEquals("0", after.entry)
    }

    @Test
    fun floatingPointNoiseIsRoundedOutOfTheDisplay() {
        // The whole reason `format` exists: 0.1 + 0.2 has to read as 0.3.
        val after = state(*digits("0.1").toTypedArray(), Key.Operator(Op.Plus), *digits("0.2").toTypedArray(), Key.Equals)
        assertEquals("0.3", after.entry)
    }

    @Test
    fun aWholeResultLosesItsDecimalPoint() {
        assertEquals("4", format(4.0))
        assertEquals("-2.5", format(-2.5))
        assertEquals("0", format(0.0))
    }
}
