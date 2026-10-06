package cx.m42.unitool.cli

/**
 * One thing an app does, with its parameters declared as properties:
 *
 * ```kotlin
 * class Encode : Command("encode", "Make a QR code from text") {
 *     private val text by argument("TEXT", "the text; without it, stdin is read")
 *     private val ec by option("--ec", help = "error correction", metavar = "LEVEL").choice("L", "M").default("L")
 *     override fun run(io: Io): Int { … }
 * }
 * ```
 *
 * The declarations are collected in order into [params]. The parser, the help and the menu read
 * that list and nothing else, so a parameter that parses is also documented and offered (D265).
 */
public abstract class Command(
    public val name: String,
    public val summary: String,
) {
    init {
        require(WORD.matches(name)) { "a command name is kebab-case: '$name'" }
    }

    private val declared = mutableListOf<Param<*>>()

    /** Every parameter in declaration order. */
    public val params: List<Param<*>> get() = declared

    internal fun register(param: Param<*>) {
        param.names.forEach { name ->
            require(name !in HELP_NAMES) { "$name is the help, a command cannot take it" }
            require(declared.none { name in it.names }) { "$name is declared twice in '${this.name}'" }
        }
        if (param.kind == ParamKind.Argument && param.required) {
            require(declared.none { it.kind == ParamKind.Argument && !it.required }) {
                "${param.metavar}: a required argument cannot follow an optional one in '$name'"
            }
        }
        declared += param
    }

    /** A positional value, `TEXT` in the help. Optional until [required] or [default]. */
    protected fun argument(name: String, help: String): Param<String?> {
        require(name.isNotBlank() && !name.startsWith("-")) { "an argument is named like TEXT: '$name'" }
        return Param(ParamKind.Argument, listOf(name), help, metavar = name)
    }

    /** `-o FILE`, `--out FILE`, `--out=FILE`. Names are one short (`-o`) and/or long ones (`--out`). */
    protected fun option(vararg names: String, help: String, metavar: String = "VALUE"): Param<String?> =
        Param(ParamKind.Option, checkNames(names), help, metavar = metavar)

    /** A switch with no value: `--split`. */
    protected fun flag(vararg names: String, help: String): Param<Boolean> =
        Param(ParamKind.Flag, checkNames(names), help, metavar = "", convert = { true }, absent = false)

    /**
     * Does the work and returns the exit code. Results go to [Io.write] and everything meant for the
     * person goes to [Io.say] (D269). For a failure, call [fail]; for a call that makes no sense,
     * call [usage].
     */
    public abstract fun run(io: Io): Int

    private fun checkNames(names: Array<out String>): List<String> {
        require(names.isNotEmpty()) { "an option needs a name" }
        names.forEach {
            require(LONG.matches(it) || SHORT.matches(it)) { "an option is named like --out or -o: '$it'" }
        }
        // The long name first: it is the one errors and the menu use.
        return names.sortedByDescending { it.startsWith("--") }
    }

    private companion object {
        val LONG = Regex("--[a-z0-9][a-z0-9-]*")
        val SHORT = Regex("-[a-zA-Z0-9]")
    }
}

internal val HELP_NAMES = setOf("-h", "--help")
