package cx.m42.unitool.cli

import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

public enum class ParamKind { Argument, Option, Flag }

/**
 * A declared parameter. The command gets it through `by`, and the framework reads the description
 * to parse, to write the help and to ask in the menu.
 *
 * Every builder below returns a new [Param]. Only the last one in a chain is registered, when `by`
 * calls [provideDelegate], so a chain never leaves half-built copies in [Command.params].
 */
public class Param<T> internal constructor(
    public val kind: ParamKind,
    /** `["--format", "-f"]` for an option or a flag, the long name first; `["TEXT"]` for an argument. */
    public val names: List<String>,
    public val help: String,
    /** How the help names the value: `FILE`, `LEVEL`, `N`. Empty for a flag. */
    public val metavar: String,
    /** The only accepted values, when there is such a list. The menu offers them by number. */
    public val choices: List<String>? = null,
    public val required: Boolean = false,
    /** The default as the help and the menu show it, or null when there is none. */
    public val defaultText: String? = null,
    /** Skipped by the menu but still parsed and listed in the help: for the knobs few people turn. */
    public val advanced: Boolean = false,
    internal val convert: (String) -> Any? = { it },
    internal val absent: Any? = null,
    /** Given more than once, every value counts, in order: `-p a -p b`. Otherwise the last one wins. */
    public val repeated: Boolean = false,
) {
    internal var value: Any? = absent
        private set

    /** Whether this run's argv named it. Used for `required`, and by the parser for nothing else. */
    internal var given: Boolean = false
        private set

    /** What errors and the menu call it: `--format` or `TEXT`. */
    public val displayName: String get() = names.first()

    internal fun reset() {
        value = absent
        given = false
    }

    internal fun accept(raw: String) {
        val converted = convert(raw)
        @Suppress("UNCHECKED_CAST")
        value = if (repeated) (value as List<Any?>) + converted else converted
        given = true
    }

    public operator fun provideDelegate(thisRef: Command, property: KProperty<*>): ReadOnlyProperty<Command, T> {
        thisRef.register(this)
        @Suppress("UNCHECKED_CAST")
        return ReadOnlyProperty { _, _ -> value as T }
    }

    internal fun <R> copy(
        metavar: String = this.metavar,
        choices: List<String>? = this.choices,
        required: Boolean = this.required,
        defaultText: String? = this.defaultText,
        advanced: Boolean = this.advanced,
        convert: (String) -> Any? = this.convert,
        absent: Any? = this.absent,
        repeated: Boolean = this.repeated,
    ): Param<R> = Param(kind, names, help, metavar, choices, required, defaultText, advanced, convert, absent, repeated)
}

/**
 * Only these values are accepted, case aside: `--ec m` is `M`. The value the command gets is always
 * spelled the way it is spelled here.
 */
public fun Param<String?>.choice(vararg values: String): Param<String?> {
    val list = values.toList()
    require(list.isNotEmpty()) { "$displayName: a choice of nothing" }
    val name = displayName
    return copy(choices = list, convert = { raw ->
        list.firstOrNull { it.equals(raw, ignoreCase = true) }
            ?: throw UsageError("$name: expected ${list.orList()}, not '$raw'")
    })
}

/** An integer, within [range] when one is given. */
public fun Param<String?>.int(range: IntRange? = null): Param<Int?> {
    val name = displayName
    val bounds = range?.let { " from ${it.first} to ${it.last}" }.orEmpty()
    return copy(convert = { raw ->
        raw.toIntOrNull()?.takeIf { range == null || it in range }
            ?: throw UsageError("$name: expected an integer$bounds, not '$raw'")
    })
}

/** The value when argv does not name it. [shown] is how the help and the menu print it. */
public fun <T : Any> Param<T?>.default(value: T, shown: String = value.toString()): Param<T> =
    copy(defaultText = shown, absent = value)

/** An option that may be given several times; the command gets every value, in order (D305). */
public fun Param<String?>.repeated(): Param<List<String>> {
    require(kind == ParamKind.Option) { "$displayName: only an option repeats" }
    return copy(absent = emptyList<String>(), repeated = true)
}

/** A run without it is a usage error (exit code 2). */
public fun <T : Any> Param<T?>.required(): Param<T> = copy(required = true)

/** Not asked in the menu. */
public fun <T> Param<T>.advanced(): Param<T> = copy(advanced = true)

/** `L, M, Q or H`. */
internal fun List<String>.orList(): String =
    if (size == 1) single() else dropLast(1).joinToString(", ") + " or " + last()
