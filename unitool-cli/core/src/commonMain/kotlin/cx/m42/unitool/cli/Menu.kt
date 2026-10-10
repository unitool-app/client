package cx.m42.unitool.cli

/**
 * `unitool` with no arguments at a terminal (D271): pick an app, then a command, then answer for
 * each parameter. The answers become an ordinary argv that goes through the same parser as typed
 * arguments, so the menu cannot do anything the command line cannot. At the end it prints that
 * command line, which is how a person learns to skip the menu next time.
 *
 * Numbered lines and a typed number: no arrow keys, so it works in any terminal and the tests can
 * script it.
 */
internal class Menu(private val cli: Cli, private val io: Io) {

    fun run(app: CliApp?): Int {
        val chosen = app ?: cli.apps.singleOrNull()
            ?: pick("${cli.title} ${cli.version}: what would you like to do?", cli.apps) { it.id to it.summary }
        chosen.wizard?.let { return cli.runWizard(chosen, it, io) }
        val command = chosen.commands.singleOrNull()
            ?: pick("${chosen.title}: which command?", chosen.commands) { it.name to it.summary }
        io.say("${chosen.title} · ${command.name}: ${command.summary}")
        val argv = answers(command)
        val code = cli.runCommand(chosen, command, argv, io)
        val line = listOf(cli.name, chosen.id, command.name) + argv
        io.say("The same as one command: " + line.joinToString(" ") { shellQuote(it) })
        return code
    }

    private fun <T> pick(title: String, items: List<T>, row: (T) -> Pair<String, String>): T {
        io.say(title)
        val rows = items.map(row)
        val width = rows.maxOf { it.first.length }
        rows.forEachIndexed { index, (left, right) -> io.say("  ${index + 1}) ${left.padEnd(width)}  $right") }
        while (true) {
            val answer = ask("> ").trim()
            val byNumber = answer.toIntOrNull()?.let { items.getOrNull(it - 1) }
            val byName = rows.indexOfFirst { it.first == answer }.takeIf { it >= 0 }?.let { items[it] }
            (byNumber ?: byName)?.let { return it }
            io.say("  no such item: type a number from 1 to ${items.size}")
        }
    }

    /** The argv the answers make: options first, then `--` if a value looks like an option, then the arguments. */
    private fun answers(command: Command): List<String> {
        val options = mutableListOf<String>()
        val positional = mutableListOf<String>()
        for (param in command.params) {
            if (param.advanced) continue
            when (param.kind) {
                ParamKind.Argument -> positional += argument(param)
                ParamKind.Flag -> if (yes(param)) options += param.displayName
                ParamKind.Option -> value(param)?.let { options += listOf(param.displayName, it) }
            }
        }
        val separator = if (positional.any { it.startsWith("-") && it != "-" }) listOf("--") else emptyList()
        return options + separator + positional
    }

    /**
     * An argument is asked until it has a value, optional or not: an optional argument usually
     * means "read stdin", and at a terminal stdin is this very keyboard.
     */
    private fun argument(param: Param<*>): String {
        while (true) {
            val answer = ask("${param.metavar} (${param.help}): ")
            if (answer.isEmpty()) {
                io.say("  a value is required")
                continue
            }
            if (valid(param, answer)) return answer
        }
    }

    private fun value(param: Param<*>): String? {
        val choices = param.choices
        val fallback = param.defaultText?.let { "default $it" } ?: "skip"
        if (choices != null) {
            io.say("${param.displayName} (${param.help})")
            io.say("  " + choices.mapIndexed { index, it -> "${index + 1}) $it" }.joinToString("  "))
        }
        while (true) {
            val answer = ask(if (choices != null) "  number or value [Enter: $fallback]: "
                else "${param.displayName} ${param.metavar} (${param.help}) [Enter: $fallback]: ").trim()
            if (answer.isEmpty()) return null
            val value = choices?.let { list -> answer.toIntOrNull()?.let { list.getOrNull(it - 1) } } ?: answer
            if (valid(param, value)) return value
        }
    }

    private fun yes(param: Param<*>): Boolean {
        while (true) {
            when (ask("${param.displayName} (${param.help}) [y/N]: ").trim().lowercase()) {
                "", "n", "no" -> return false
                "y", "yes" -> return true
                else -> io.say("  answer y or n")
            }
        }
    }

    /** The parameter's own converter decides, so the menu accepts exactly what argv would. */
    private fun valid(param: Param<*>, value: String): Boolean = try {
        param.convert(value)
        true
    } catch (e: UsageError) {
        io.say("  ${e.message}")
        false
    }

    private fun ask(prompt: String): String = io.ask(prompt) ?: throw Cancelled()
}

/** A word the shell reads back as itself. */
internal fun shellQuote(word: String): String =
    if (word.isNotEmpty() && word.all { it in SAFE }) word else "'" + word.replace("'", "'\\''") + "'"

private val SAFE: Set<Char> = (('a'..'z') + ('A'..'Z') + ('0'..'9') + "-_./:=@%+,".toList()).toSet()
