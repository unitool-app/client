package cx.m42.unitool.cli

/** Puts the cursor home and clears the screen. */
public const val CLEAR_SCREEN: String = "\u001b[H\u001b[2J"

/**
 * Shows [screens] one at a time: QR codes too long for one, pages of anything.
 *
 * When stdout is a terminal and there is a keyboard to wait on, each screen replaces the last after
 * Enter, which [pause] reads from `/dev/tty` even when stdin is a pipe. Otherwise (a file, a pipe,
 * nobody at the keyboard) the screens follow each other with an empty line between. [title] goes to
 * stderr before each one; [next] is the prompt between them.
 */
public fun Io.showScreens(
    screens: List<ByteArray>,
    next: String = "Press Enter for the next one",
    title: (number: Int, count: Int) -> String,
) {
    screens.forEachIndexed { index, screen ->
        if (index > 0) {
            val onScreen = stdoutIsTerminal && pause(next)
            write(if (onScreen) CLEAR_SCREEN else "\n")
        }
        say(title(index + 1, screens.size))
        write(screen)
    }
}
