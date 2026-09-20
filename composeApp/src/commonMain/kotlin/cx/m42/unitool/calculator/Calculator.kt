package cx.m42.unitool.calculator

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/** The four operations the keypad offers. [symbol] is what the key and the ribbon show. */
enum class Op(val symbol: String) {
    Plus("+"),
    Minus("−"),
    Times("×"),
    Divide("÷"),
}

/**
 * One press. A closed set rather than a string, so the reducer below is total: every key the UI can
 * offer is a case the compiler insists is handled.
 */
sealed interface Key {
    data class Digit(val value: Int) : Key
    data object Dot : Key
    data class Operator(val op: Op) : Key
    data object Equals : Key
    data object Clear : Key
    data object Backspace : Key
    data object Sign : Key
    data object Percent : Key
}

/** The longest number a person may type. Past this the display would have to shrink to fit. */
private const val MaxDigits = 12

/**
 * Everything the calculator knows, as immutable data with no Compose types in sight.
 *
 * It is a plain value class on purpose: the whole of the arithmetic is [press], a pure function
 * from one state to the next, so the rules are testable without a UI and the screen is left with
 * nothing to decide. This is the same split the rest of the app uses — a screen renders state and
 * forwards events, and never carries logic of its own.
 *
 * @property entry the number being typed, exactly as the display shows it — a string, so a trailing
 *   dot and the leading zero the user typed survive until the number is actually used.
 * @property accumulator the left-hand side of a pending operation.
 * @property pending the operation waiting for its right-hand side.
 * @property ribbon what the line above the display reads: "12 +" while an operation waits.
 * @property error set by an impossible sum; every key but [Key.Clear] is inert until it clears.
 * @property fresh true when [entry] is a *result* rather than something typed, so the next digit
 *   replaces it instead of appending to it.
 */
data class CalculatorState(
    val entry: String = "0",
    val accumulator: Double? = null,
    val pending: Op? = null,
    val ribbon: String = "",
    val error: Boolean = false,
    val fresh: Boolean = true,
) {
    /** The typed number as arithmetic sees it. A lone "-" or "0." is still zero. */
    val value: Double get() = entry.toDoubleOrNull() ?: 0.0
}

/**
 * The next state after one key. Pure, total and the only place the rules live.
 *
 * An error latches: once a division by zero has happened, the display says so until it is cleared,
 * rather than quietly resuming from a number the reader never saw.
 */
fun CalculatorState.press(key: Key): CalculatorState {
    if (error && key != Key.Clear) return this

    return when (key) {
        is Key.Digit -> typeDigit(key.value)
        Key.Dot -> typeDot()
        is Key.Operator -> applyOperator(key.op)
        Key.Equals -> equals()
        Key.Clear -> CalculatorState()
        Key.Backspace -> backspace()
        Key.Sign -> negate()
        Key.Percent -> percent()
    }
}

private fun CalculatorState.typeDigit(digit: Int): CalculatorState {
    // A result is overwritten by the next number rather than grown: tapping 5 after "= 12" starts
    // a new number, which is what every calculator does and what a reader expects.
    val base = if (fresh) "" else entry.takeIf { it != "0" }.orEmpty()
    if (base.count { it.isDigit() } >= MaxDigits) return this
    return copy(entry = base + digit, fresh = false)
}

private fun CalculatorState.typeDot(): CalculatorState {
    if (fresh) return copy(entry = "0.", fresh = false)
    // A second dot is not an error, it is a no-op: the number the user is holding stays valid.
    if (entry.contains('.')) return this
    return copy(entry = "$entry.", fresh = false)
}

/**
 * An operator key. Pressing one with a sum already waiting *resolves* it first, so `2 + 3 ×` shows
 * 5 and goes on multiplying — chained arithmetic left to right, no precedence. That is what a
 * pocket calculator does, and precedence would need an expression the display cannot show.
 *
 * Pressing two operators in a row replaces the first: the user changed their mind, they did not ask
 * for an operation on nothing.
 */
private fun CalculatorState.applyOperator(op: Op): CalculatorState {
    if (pending != null && fresh && accumulator != null) {
        return copy(pending = op, ribbon = ribbon(accumulator, op))
    }
    val left = when {
        pending == null || accumulator == null -> value
        else -> combine(accumulator, value, pending) ?: return failed()
    }
    return copy(
        entry = format(left),
        accumulator = left,
        pending = op,
        ribbon = ribbon(left, op),
        fresh = true,
    )
}

private fun CalculatorState.equals(): CalculatorState {
    val op = pending ?: return copy(ribbon = "", fresh = true)
    val left = accumulator ?: return copy(ribbon = "", fresh = true)
    val result = combine(left, value, op) ?: return failed()
    return CalculatorState(entry = format(result), fresh = true)
}

private fun CalculatorState.backspace(): CalculatorState {
    // A result has no characters to take back — the sum that produced it is gone. Clearing the
    // entry is the honest answer, and it is what the key visibly does.
    if (fresh) return copy(entry = "0")
    val shortened = entry.dropLast(1)
    return if (shortened.isEmpty() || shortened == "-") {
        copy(entry = "0", fresh = true)
    } else {
        copy(entry = shortened)
    }
}

private fun CalculatorState.negate(): CalculatorState = when {
    entry == "0" -> this
    entry.startsWith("-") -> copy(entry = entry.removePrefix("-"))
    else -> copy(entry = "-$entry")
}

/**
 * Percent, read the way a pocket calculator reads it: a bare number becomes a hundredth of itself,
 * while `200 + 10 %` is ten percent *of the 200* — which is what a person adding VAT means.
 */
private fun CalculatorState.percent(): CalculatorState {
    val base = accumulator
    val result = when {
        pending == Op.Plus || pending == Op.Minus -> if (base != null) base * value / 100 else value / 100
        else -> value / 100
    }
    return copy(entry = format(result), fresh = false)
}

private fun CalculatorState.failed(): CalculatorState = CalculatorState(entry = "0", error = true)

/** The arithmetic itself. Null means "no answer exists" — division by zero, or an overflow. */
private fun combine(left: Double, right: Double, op: Op): Double? {
    val result = when (op) {
        Op.Plus -> left + right
        Op.Minus -> left - right
        Op.Times -> left * right
        Op.Divide -> if (right == 0.0) return null else left / right
    }
    return result.takeIf { it.isFinite() }
}

private fun ribbon(left: Double, op: Op): String = "${format(left)} ${op.symbol}"

/**
 * A number as the display writes it.
 *
 * Rounded to ten decimals before anything else, which is what keeps `0.1 + 0.2` reading as `0.3`
 * rather than as the binary truth about it; a whole number loses its `.0`, and a trailing zero left
 * by the rounding is dropped. Deliberately hand-rolled rather than platform number formatting:
 * `commonMain` has none, and the same string has to come out on all targets — including the test
 * suite, which runs on the desktop one.
 */
fun format(value: Double): String {
    if (!value.isFinite()) return "0"
    if (value == 0.0) return "0"

    val magnitude = abs(value)
    // Past twelve digits a fixed-point rendering no longer fits the display, and below a billionth
    // it would be all zeros; the platform's own exponent form is the readable answer at both ends.
    if (magnitude >= 1e12 || magnitude < 1e-9) return value.toString()

    val rounded = round(value * 1e10) / 1e10
    if (rounded == floor(rounded)) return rounded.toLong().toString()
    return rounded.toString().trimEnd('0').trimEnd('.')
}
