package cx.m42.unitool.cli

/**
 * The host: the apps it was given, what each word of argv means, and the exit codes (D270):
 * 0 for success, 1 when a command fails (or another code it chose), 2 for a call that makes no
 * sense, 130 when the person leaves the menu, 141 when the reader of stdout went away.
 *
 * ```kotlin
 * exitProcess(Cli("unitool", "Unitool", version, listOf(QrApp())).run(args.toList(), SystemIo()))
 * ```
 *
 * Registration is an explicit list, as in Unitool: nothing is found on the classpath.
 */
public class Cli(
    public val name: String,
    public val title: String,
    public val version: String,
    public val apps: List<CliApp>,
) {
    init {
        require(apps.isNotEmpty()) { "a CLI with no apps" }
        apps.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            throw IllegalArgumentException("two apps with the id '$it'")
        }
        apps.forEach { app ->
            require(app.commands.isNotEmpty()) { "app '${app.id}' has no commands" }
            app.commands.groupBy { it.name }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
                throw IllegalArgumentException("two commands named '$it' in '${app.id}'")
            }
            app.default?.let { require(it in app.commands) { "the default of '${app.id}' is not one of its commands" } }
        }
    }

    public fun run(args: List<String>, io: Io): Int {
        val code = try {
            dispatch(args, io)
        } catch (_: Cancelled) {
            io.say("")
            io.say("Cancelled.")
            130
        } catch (e: UsageError) {
            io.say("$name: ${e.message}")
            io.say("Help: $name --help")
            2
        } catch (_: StdoutClosed) {
            return PIPE_CLOSED
        }
        return try {
            io.flush()
            code
        } catch (_: StdoutClosed) {
            PIPE_CLOSED
        }
    }

    private fun dispatch(args: List<String>, io: Io): Int {
        if (args.isEmpty()) {
            if (io.interactive) return Menu(this, io).run(app = null)
            io.say(Help.top(this))
            return 2
        }
        val first = args.first()
        when (first) {
            in HELP_NAMES -> return printed(io, Help.top(this))
            "--version" -> return printed(io, "$name $version\n")
        }
        if (first.startsWith("-")) usage("unknown option $first")
        val app = apps.firstOrNull { it.id == first }
            ?: usage("unknown app '$first'; available: ${apps.joinToString(", ") { it.id }}")

        val rest = args.drop(1)
        if (rest.isEmpty()) {
            if (io.interactive) return Menu(this, io).run(app)
            app.default?.let { return runCommand(app, it, rest, io) }
            io.say(Help.app(this, app))
            return 2
        }
        if (rest.first() in HELP_NAMES) return printed(io, Help.app(this, app))
        app.commands.firstOrNull { it.name == rest.first() }?.let { return runCommand(app, it, rest.drop(1), io) }
        app.default?.let { return runCommand(app, it, rest, io) }
        usage("'${app.id}' has no command '${rest.first()}'; available: ${app.commands.joinToString(", ") { it.name }}")
    }

    /** Parses [argv] into [command] and runs it. The menu ends up here too. */
    internal fun runCommand(app: CliApp, command: Command, argv: List<String>, io: Io): Int {
        val path = "$name ${app.id} ${command.name}"
        try {
            command.parse(argv)
        } catch (_: HelpRequested) {
            return printed(io, Help.command(this, app, command))
        } catch (e: UsageError) {
            io.say("$path: ${e.message}")
            io.say("Help: $path --help")
            return 2
        }
        return try {
            command.run(io)
        } catch (e: UsageError) {
            io.say("$path: ${e.message}")
            io.say("Help: $path --help")
            2
        } catch (e: CommandFailure) {
            io.say("$path: ${e.message}")
            e.code
        } catch (e: Cancelled) {
            throw e
        } catch (e: StdoutClosed) {
            throw e
        } catch (e: Exception) {
            io.say("$path: internal error: ${e::class.simpleName}: ${e.message}")
            if (io.env("UNITOOL_DEBUG") == "1") io.say(e.stackTraceToString()) else io.say("Details: UNITOOL_DEBUG=1")
            1
        }
    }

    private fun printed(io: Io, text: String): Int {
        io.write(text)
        return 0
    }

    private companion object {
        const val PIPE_CLOSED = 141
    }
}
