package cx.m42.unitool.cli

/**
 * One word on the command line: `unitool qr …`. It has an id, a title for the menu, one sentence for
 * `unitool --help`, and the commands that do the actual work.
 *
 * Only the shape comes from Superizer's apps (D263). There is no manifest, no activation and no
 * lifecycle: an app here is just a namespace for its commands.
 */
public abstract class CliApp(
    public val id: String,
    public val title: String,
    public val summary: String,
) {
    init {
        require(WORD.matches(id)) { "an app id is kebab-case: '$id'" }
    }

    public abstract val commands: List<Command>

    /**
     * Runs when the word after the app is not one of its commands, so `unitool qr hello` means
     * `unitool qr encode hello`. A command's name always wins: to encode the word "encode" itself,
     * write `unitool qr encode encode`.
     */
    public open val default: Command? get() = null

    /**
     * What `unitool APP` alone does at a terminal, instead of the generic menu of commands (D271):
     * an app whose choices depend on each other walks the person through them itself
     * (.ai-notes/done/split D370). It must not do anything its commands cannot.
     */
    public open val wizard: Wizard? get() = null
}

/** A guided run of an app. [command] is how the app is called, `unitool split`, for the closing "same as one command" line. */
public fun interface Wizard {
    public fun run(io: Io, command: String): Int
}

/** What an app id and a command name may look like: they are typed, so lower case and dashes. */
internal val WORD = Regex("[a-z0-9][a-z0-9-]*")
