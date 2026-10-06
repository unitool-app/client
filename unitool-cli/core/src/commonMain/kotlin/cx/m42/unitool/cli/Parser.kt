package cx.m42.unitool.cli

/**
 * GNU conventions, and only the ones people type (D265): `--out=FILE`, `--out FILE`, `-o FILE`,
 * `-oFILE`, `--` to end the options, and `-` as an ordinary value (stdin by custom). Short flags
 * are not bundled (`-ab`): no command has two of them yet. The last occurrence of an option wins.
 */
internal fun Command.parse(args: List<String>) {
    params.forEach { it.reset() }
    val options = params.filter { it.kind != ParamKind.Argument }
    val arguments = params.filter { it.kind == ParamKind.Argument }
    fun option(name: String): Param<*> = options.firstOrNull { name in it.names }
        ?: usage("unknown option $name")

    val positional = mutableListOf<String>()
    var onlyPositional = false
    var i = 0
    while (i < args.size) {
        val arg = args[i++]
        when {
            onlyPositional || arg == "-" || !arg.startsWith("-") -> positional += arg
            arg == "--" -> onlyPositional = true
            arg in HELP_NAMES -> throw HelpRequested()
            arg.startsWith("--") -> {
                val name = arg.substringBefore('=')
                val inline = if ('=' in arg) arg.substringAfter('=') else null
                val param = option(name)
                if (param.kind == ParamKind.Flag) {
                    if (inline != null) usage("$name takes no value")
                    param.accept("")
                } else {
                    param.accept(inline ?: args.getOrNull(i++) ?: usage("$name needs a value: ${param.metavar}"))
                }
            }
            else -> {
                val name = arg.take(2)
                val rest = arg.drop(2)
                val param = option(name)
                if (param.kind == ParamKind.Flag) {
                    if (rest.isNotEmpty()) usage("$name takes no value: '$arg'")
                    param.accept("")
                } else {
                    param.accept(rest.ifEmpty { args.getOrNull(i++) ?: usage("$name needs a value: ${param.metavar}") })
                }
            }
        }
    }

    if (positional.size > arguments.size) usage("unexpected argument '${positional[arguments.size]}'")
    positional.forEachIndexed { index, value -> arguments[index].accept(value) }
    params.firstOrNull { it.required && !it.given }?.let { usage("missing ${it.displayName}") }
}
