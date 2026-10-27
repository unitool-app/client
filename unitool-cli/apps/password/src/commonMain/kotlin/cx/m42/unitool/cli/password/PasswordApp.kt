package cx.m42.unitool.cli.password

import cx.m42.unitool.cli.CliApp
import cx.m42.unitool.cli.Command
import cx.m42.unitool.cli.Io
import cx.m42.unitool.cli.advanced
import cx.m42.unitool.cli.choice
import cx.m42.unitool.cli.default
import cx.m42.unitool.cli.int
import cx.m42.unitool.cli.usage
import cx.m42.unitool.cli.write
import cx.m42.unitool.kit.crypto.secureRandomBytes
import cx.m42.unitool.kit.password.EntropyPool
import cx.m42.unitool.kit.password.PasswordGenerator
import cx.m42.unitool.kit.password.PasswordMode
import cx.m42.unitool.kit.password.PasswordRules
import kotlin.time.TimeSource

/**
 * `unitool password`: the command-line face of the kit's generator (`cx.m42.unitool.kit.password`),
 * the same one the phone's Passwords app uses. Here are only the parameters and the messages.
 */
public class PasswordApp : CliApp(id = "password", title = "Passwords", summary = "Generate passwords from the device's entropy: characters, words or both") {
    private val generate = GenerateCommand()
    override val commands: List<Command> = listOf(generate)
    override val default: Command = generate
}

internal class GenerateCommand : Command("generate", "Generate a password from the system's secure random") {
    private val mode by option("-m", "--mode", help = "chars: random characters; words: BIP-39 words; mixed: words joined by digits and symbols", metavar = "MODE")
        .choice("chars", "words", "mixed").default("chars")
    private val length by option("-l", "--length", help = "characters, for --mode chars", metavar = "N")
        .int(PasswordGenerator.LENGTHS).default(PasswordGenerator.DEFAULT_LENGTH)
    private val words by option("-w", "--words", help = "words, for --mode words and mixed", metavar = "N")
        .int(PasswordGenerator.WORD_COUNTS).default(PasswordGenerator.DEFAULT_WORDS)
    private val count by option("-n", "--count", help = "how many passwords, one per line", metavar = "N")
        .int(1..1000).default(1)
    private val noLower by flag("--no-lower", help = "no lower-case letters").advanced()
    private val noUpper by flag("--no-upper", help = "no upper-case letters").advanced()
    private val noDigits by flag("--no-digits", help = "no digits").advanced()
    private val noSymbols by flag("--no-symbols", help = "no symbols, for sites that refuse them")
    private val mouse by flag("--mouse", help = "mix in the mouse moving over the terminal, for as long as you like; Enter ends")
    private val lookAlike by flag("--look-alike", help = "allow l, I, O, 0 and 1, which are left out so a password can be typed off a screen").advanced()

    override fun run(io: Io): Int {
        val rules = PasswordRules(
            mode = when (mode) {
                "words" -> PasswordMode.Words
                "mixed" -> PasswordMode.Mixed
                else -> PasswordMode.Characters
            },
            length = length,
            words = words,
            lower = !noLower,
            upper = !noUpper,
            digits = !noDigits,
            symbols = !noSymbols,
            lookAlike = lookAlike,
        )
        rules.problem?.let { usage(it) }
        val random = if (mouse) drawn(io).source() else ::secureRandomBytes
        repeat(count) { io.write(PasswordGenerator.generate(rules, random) + "\n") }
        // For the person at the terminal; a script reading stdout or a log never sees it.
        if (io.stderrIsTerminal) io.say("${PasswordGenerator.entropyBits(rules).toInt()} bits of entropy each")
        return 0
    }

    /**
     * The mouse over the terminal, into a pool: the terminal reports cells, coarse as they are, and
     * the time of each report goes in with it. The system's random stays the base (EntropyPool).
     */
    private fun drawn(io: Io): EntropyPool {
        val pool = EntropyPool()
        val clock = TimeSource.Monotonic.markNow()
        io.say("Move the mouse over this window — the longer and the less even, the more. Enter ends, Ctrl-C cancels.")
        val tracked = io.trackMouse(
            point = { x, y -> pool.add(x, y, clock.elapsedNow().inWholeNanoseconds) },
            status = {
                val bits = minOf(pool.bits.toInt(), EntropyPool.TARGET_BITS)
                val seconds = clock.elapsedNow().inWholeSeconds
                val enough = if (pool.full) " — enough, Enter ends" else ""
                "≈ $bits of ${EntropyPool.TARGET_BITS} bits from ${pool.samples} moves · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}$enough"
            },
        )
        if (!tracked) usage("--mouse needs a terminal to move the mouse over")
        if (pool.samples == 0) io.say("No mouse moves came; the system's random alone it is.")
        return pool
    }
}
