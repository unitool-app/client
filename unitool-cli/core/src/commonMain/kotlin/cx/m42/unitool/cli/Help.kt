package cx.m42.unitool.cli

/** The three levels of `--help`, all written from the declarations and nothing hand-kept. */
internal object Help {

    fun top(cli: Cli): String = buildString {
        appendLine("${cli.title} ${cli.version}: Unitool's tools in the terminal.")
        appendLine()
        appendLine("Usage: ${cli.name} APP [COMMAND] [OPTIONS]")
        appendLine("With no arguments at a terminal, a menu opens.")
        appendLine()
        appendLine("Apps:")
        append(table(cli.apps.map { it.id to it.summary }))
        appendLine()
        append(table(listOf("-h, --help" to "show this help", "    --version" to "show the version")))
        appendLine()
        appendLine("Help on an app: ${cli.name} APP --help")
    }

    fun app(cli: Cli, app: CliApp): String = buildString {
        appendLine("Usage: ${cli.name} ${app.id} COMMAND [OPTIONS]")
        appendLine()
        appendLine("${app.title}. ${app.summary}.")
        appendLine()
        appendLine("Commands:")
        append(table(app.commands.map { it.name to it.summary }))
        app.default?.let { command ->
            val args = command.params.filter { it.kind == ParamKind.Argument }.joinToString("") { " " + it.metavar }
            appendLine()
            appendLine("Without a command, ${command.name} runs: ${cli.name} ${app.id}$args = ${cli.name} ${app.id} ${command.name}$args.")
        }
        appendLine()
        appendLine("Help on a command: ${cli.name} ${app.id} COMMAND --help")
    }

    fun command(cli: Cli, app: CliApp, command: Command): String = buildString {
        val arguments = command.params.filter { it.kind == ParamKind.Argument }
        val options = command.params.filter { it.kind != ParamKind.Argument }
        val synopsis = arguments.joinToString("") { if (it.required) " ${it.metavar}" else " [${it.metavar}]" }
        appendLine("Usage: ${cli.name} ${app.id} ${command.name} [OPTIONS]$synopsis")
        appendLine()
        appendLine("${command.summary}.")
        val rows = arguments.map { it.metavar to describe(it) } +
            options.map { left(it) to describe(it) } +
            ("-h, --help" to "show this help")
        val width = rows.maxOf { it.first.length }
        if (arguments.isNotEmpty()) {
            appendLine()
            appendLine("Arguments:")
            append(table(rows.take(arguments.size), width))
        }
        appendLine()
        appendLine("Options:")
        append(table(rows.drop(arguments.size), width))
    }

    /** `-o, --out FILE`, or `    --ec LEVEL` so long names line up under the short ones. */
    private fun left(param: Param<*>): String {
        val short = param.names.firstOrNull { !it.startsWith("--") }
        val long = param.names.filter { it.startsWith("--") }
        val names = listOfNotNull(short) + long
        val value = if (param.kind == ParamKind.Flag) "" else " ${param.metavar}"
        return (if (short == null) "    " else "") + names.joinToString(", ") + value
    }

    private fun describe(param: Param<*>): String {
        val notes = listOfNotNull(
            param.choices?.joinToString(", "),
            param.defaultText?.let { "default $it" },
            "required".takeIf { param.required },
        )
        return if (notes.isEmpty()) param.help else "${param.help} (${notes.joinToString("; ")})"
    }

    private fun table(rows: List<Pair<String, String>>, width: Int = rows.maxOf { it.first.length }): String =
        rows.joinToString("") { (left, right) -> "  ${left.padEnd(width)}  $right\n" }
}
