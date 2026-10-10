package cx.m42.unitool.cli

/*
 * Questions for wizards (D370), in the menu's manner: numbered lines and typed answers, stderr for
 * every word, Ctrl-D leaves with 130 like the menu does.
 */

/** The answer to [prompt], trimmed. Ctrl-D leaves the run. */
public fun Io.answer(prompt: String): String = (ask(prompt) ?: throw Cancelled()).trim()

/** The answer, or [default] when the person just presses Enter. */
public fun Io.answer(prompt: String, default: String): String = answer("$prompt [$default]: ").ifEmpty { default }

/** A secret line, hidden while typed. Ctrl-D leaves the run; with no terminal to read, the command fails. */
public fun Io.hidden(prompt: String): String {
    val line = secret(prompt) ?: if (stdinIsTerminal || stderrIsTerminal) throw Cancelled() else fail("no terminal to ask on")
    return line.trim()
}

/** y or n, asked again until it is one of them. Enter is [default] when there is one. */
public fun Io.yes(question: String, default: Boolean? = null): Boolean {
    val hint = when (default) {
        true -> "[Y/n]"
        false -> "[y/N]"
        null -> "[y/n]"
    }
    while (true) {
        when (answer("$question $hint: ").lowercase()) {
            "y", "yes" -> return true
            "n", "no" -> return false
            "" -> default?.let { return it }
        }
        say("  answer y or n")
    }
}

/** One of [options], by number or by its first word; the index into [options]. */
public fun Io.pick(title: String, options: List<Pair<String, String>>): Int {
    say(title)
    val width = options.maxOf { it.first.length }
    options.forEachIndexed { index, (left, right) -> say("  ${index + 1}) ${left.padEnd(width)}  $right") }
    while (true) {
        val reply = answer("> ")
        reply.toIntOrNull()?.let { if (it in 1..options.size) return it - 1 }
        options.indexOfFirst { it.first == reply }.takeIf { it >= 0 }?.let { return it }
        say("  type a number from 1 to ${options.size}")
    }
}

/** A whole number in [range]; Enter is [default]. */
public fun Io.number(prompt: String, range: IntRange, default: Int): Int {
    while (true) {
        val reply = answer("$prompt (${range.first}-${range.last}) [$default]: ")
        if (reply.isEmpty()) return default
        reply.toIntOrNull()?.takeIf { it in range }?.let { return it }
        say("  a number from ${range.first} to ${range.last}")
    }
}

/** [words] as one line the shell reads back as the same words: the wizard's "same as one command". */
public fun shellLine(words: List<String>): String = words.joinToString(" ") { shellQuote(it) }
