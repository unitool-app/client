package cx.m42.unitool.cli

/** xterm's any-motion mouse tracking with SGR reports (`ESC [ < b ; x ; y M`), and back off. */
internal const val MOUSE_ON: String = "\u001b[?1003h\u001b[?1006h"
internal const val MOUSE_OFF: String = "\u001b[?1006l\u001b[?1003l"

/**
 * Reads the terminal byte by byte while the mouse is tracked: an SGR report goes to [point] as the
 * cell's column and row, Enter and Ctrl-C come back as keys, everything else is let go.
 */
internal class MouseReports(private val point: (x: Int, y: Int) -> Unit) {
    enum class Key { None, Enter, Cancel }

    private enum class State { Text, Escape, Csi, Report }

    private var state = State.Text
    private val numbers = ArrayList<Int>()
    private var number = 0

    fun feed(byte: Int): Key {
        when (state) {
            State.Text -> when (byte) {
                0x1b -> state = State.Escape
                0x0d, 0x0a -> return Key.Enter
                0x03 -> return Key.Cancel
            }
            State.Escape -> state = if (byte == '['.code) State.Csi else State.Text
            State.Csi -> if (byte == '<'.code) {
                numbers.clear()
                number = 0
                state = State.Report
            } else {
                state = State.Text
            }
            State.Report -> when (byte) {
                in '0'.code..'9'.code -> number = (number * 10 + (byte - '0'.code)).coerceAtMost(1_000_000)
                ';'.code -> {
                    numbers += number
                    number = 0
                }
                'M'.code, 'm'.code -> {
                    numbers += number
                    if (numbers.size == 3) point(numbers[1], numbers[2])
                    state = State.Text
                }
                else -> state = State.Text
            }
        }
        return Key.None
    }
}
