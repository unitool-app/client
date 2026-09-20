package cx.m42.unitool

import cx.m42.unitool.calculator.CalculatorState
import cx.m42.unitool.calculator.Key
import cx.m42.unitool.calculator.Op
import cx.m42.unitool.calculator.format
import cx.m42.unitool.calculator.press
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arithmetic, tested without a UI — which is the point of keeping it in a pure function. Every
 * case here is a rule a person would notice if it broke.
 */
class CalculatorTest {

    /** Types a whole sequence, so a case reads as the keys a person pressed. */
    private fun keys(vararg keys: Key): CalculatorState =
        keys.fold(CalculatorState()) { state, key -> state.press(key) }

    private fun digits(text: String): List<Key> = text.map { char ->
        if (char == '.') Key.Dot else Key.Digit(char.digitToInt())
    }

    private fun type(text: String, vararg rest: Key): CalculatorState =
        (digits(text) + rest.toList()).fold(CalculatorState()) { state, key -> state.press(key) }

    @Test
    fun typingDigitsReplacesTheLeadingZero() {
        assertEquals("12", type("12").entry)
    }

    @Test
    fun addingTwoNumbers() {
        val state = (digits("2") + Key.Operator(Op.Plus) + digits("3") + Key.Equals)
            .fold(CalculatorState()) { s, key -> s.press(key) }
        assertEquals("5", state.entry)
    }

    @Test
    fun aChainResolvesLeftToRightWithNoPrecedence() {
        // 2 + 3 × 4 is (2 + 3) × 4 on a pocket calculator, not 14: the display cannot show an
        // expression, so there is nothing for precedence to apply to.
        val state = (
            digits("2") + Key.Operator(Op.Plus) +
                digits("3") + Key.Operator(Op.Times) +
                digits("4") + Key.Equals
            ).fold(CalculatorState()) { s, key -> s.press(key) }
        assertEquals("20", state.entry)
    }

    @Test
    fun pressingAnOperatorTwiceReplacesIt() {
        val state = (digits("8") + Key.Operator(Op.Plus) + Key.Operator(Op.Minus) + digits("3") + Key.Equals)
            .fold(CalculatorState()) { s, key -> s.press(key) }
        assertEquals("5", state.entry)
    }

    @Test
    fun theRibbonShowsTheSumWaitingForItsAnswer() {
        val state = (digits("12") + Key.Operator(Op.Times)).fold(CalculatorState()) { s, k -> s.press(k) }
        assertEquals("12 ×", state.ribbon)
    }

    @Test
    fun dividingByZeroLatchesAnErrorThatOnlyClearClears() {
        val failed = (digits("5") + Key.Operator(Op.Divide) + digits("0") + Key.Equals)
            .fold(CalculatorState()) { s, key -> s.press(key) }
        assertTrue(failed.error)

        // Every other key is inert while the error stands.
        assertTrue(failed.press(Key.Digit(7)).error)
        assertEquals("0", failed.press(Key.Clear).entry)
    }

    @Test
    fun binaryNoiseNeverReachesTheDisplay() {
        val state = (digits("0.1") + Key.Operator(Op.Plus) + digits("0.2") + Key.Equals)
            .fold(CalculatorState()) { s, key -> s.press(key) }
        assertEquals("0.3", state.entry)
    }

    @Test
    fun aSecondDotIsIgnoredRatherThanBreakingTheNumber() {
        assertEquals("1.5", type("1.5", Key.Dot).entry)
    }

    @Test
    fun aResultIsReplacedByTheNextNumberTyped() {
        val result = (digits("2") + Key.Operator(Op.Plus) + digits("2") + Key.Equals)
            .fold(CalculatorState()) { s, key -> s.press(key) }
        assertEquals("7", result.press(Key.Digit(7)).entry)
    }

    @Test
    fun backspaceTakesBackOneTypedCharacterAndStopsAtZero() {
        assertEquals("12", type("123", Key.Backspace).entry)
        assertEquals("0", type("1", Key.Backspace).entry)
    }

    @Test
    fun signFlipsTheNumberButLeavesZeroAlone() {
        assertEquals("-5", type("5", Key.Sign).entry)
        assertEquals("5", type("5", Key.Sign, Key.Sign).entry)
        assertEquals("0", keys(Key.Sign).entry)
    }

    @Test
    fun percentOfAPendingSumIsAFractionOfItsLeftHandSide() {
        // 200 + 10 % is 10% *of the 200*, which is what a person adding a tip means.
        val state = (digits("200") + Key.Operator(Op.Plus) + digits("10") + Key.Percent + Key.Equals)
            .fold(CalculatorState()) { s, key -> s.press(key) }
        assertEquals("220", state.entry)
    }

    @Test
    fun aBarePercentIsAHundredthOfTheNumber() {
        assertEquals("0.5", type("50", Key.Percent).entry)
    }

    @Test
    fun aWholeResultLosesItsDecimalPoint() {
        assertEquals("4", format(4.0))
        assertEquals("-2.5", format(-2.5))
        assertEquals("0", format(0.0))
    }
}
