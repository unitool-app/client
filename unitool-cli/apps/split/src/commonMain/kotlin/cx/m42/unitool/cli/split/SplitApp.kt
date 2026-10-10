package cx.m42.unitool.cli.split

import cx.m42.unitool.cli.CliApp
import cx.m42.unitool.cli.Command
import cx.m42.unitool.cli.Io
import cx.m42.unitool.cli.Wizard
import cx.m42.unitool.cli.choice
import cx.m42.unitool.cli.default
import cx.m42.unitool.cli.int
import cx.m42.unitool.cli.repeated
import cx.m42.unitool.cli.required
import cx.m42.unitool.cli.usage
import cx.m42.unitool.kit.split.SplitPlan

/**
 * `unitool split`: a secret kept in parts (.ai-notes/done/split). The text is encrypted into a container
 * under a random key, and the key is split so that any k of n parts open it and fewer tell nothing.
 * Each part is a QR code of an ordinary-looking link with the part after '#'.
 *
 * At a terminal, `unitool split` alone is a wizard (D370). The commands below do the same for
 * scripts, and the wizard ends by printing the command that repeats it.
 */
public class SplitApp : CliApp(id = "split", title = "Split secret", summary = "Keep a secret in parts: any K of N open it, fewer tell nothing") {
    override val commands: List<Command> = listOf(MakeCommand(), JoinCommand(), InspectCommand())
    override val wizard: Wizard = SplitWizard()
}

internal class MakeCommand : Command("make", "Encrypt a secret from stdin and split its key into parts") {
    private val total by option("-n", "--total", help = "how many parts to make", metavar = "N")
        .int(SplitPlan.PARTS).required()
    private val need by option("-k", "--need", help = "how many parts open the secret, from 2 to N", metavar = "K")
        .int(SplitPlan.PARTS).required()
    private val base by option("--base", help = "the link address the parts go after, e.g. https://example.com/p", metavar = "URL")
        .required()
    private val out by option("-o", "--out", help = "the encrypted container; without it, stdout", metavar = "FILE")
    private val armor by flag("-a", "--armor", help = "write the container as text even to a file or a pipe")
    private val show by option("--show", help = "how to hand out the parts; without it, screen at a terminal", metavar = "HOW")
        .choice(*Show.entries.map { it.id }.toTypedArray())
    private val outDir by option("-d", "--out-dir", help = "the folder for part files", metavar = "DIR").default(".")

    override fun run(io: Io): Int {
        if (need > total) usage("--need: from 2 to $total parts, not $need")
        val how = show?.let(Show::of) ?: if (io.stdoutIsTerminal) Show.Screen else usage("--show: give png, svg or txt with --out-dir when stdout is not a terminal")
        val plan = MakePlan(total, need, base, out, armor, how, outDir)
        plan.check(io)
        if (io.stdinIsTerminal) io.say("Type the secret, then Ctrl-D. It does not show as you type.")
        val text = io.readStdinHidden()
        return plan.make(io, text)
    }
}

internal class JoinCommand : Command("join", "Open a container with its parts and print the secret") {
    private val file by argument("FILE", "the container; without it, stdin")
    private val parts by option("-p", "--part", help = "a file with one part: the link or its 64 characters", metavar = "FILE").repeated()
    private val out by option("-o", "--out", help = "write the secret to a file (0600) instead of stdout", metavar = "FILE")

    override fun run(io: Io): Int = JoinPlan(file, parts, out).join(io)
}

internal class InspectCommand : Command("inspect", "Say what a container or a part is, without opening anything") {
    private val file by argument("FILE", "a container or a file with a part; without it, stdin or a hidden question")

    override fun run(io: Io): Int = inspect(io, file)
}

/** How the parts are handed out. */
internal enum class Show(val id: String, val extension: String?, val label: String) {
    Screen("screen", null, "here, one per screen"),
    Png("png", "png", "PNG files"),
    Svg("svg", "svg", "SVG files"),
    Txt("txt", "txt", "text files with the link"),
    ;

    companion object {
        fun of(id: String): Show = entries.first { it.id == id }
    }
}
