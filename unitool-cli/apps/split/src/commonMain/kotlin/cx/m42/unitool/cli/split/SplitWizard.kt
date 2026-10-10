package cx.m42.unitool.cli.split

import cx.m42.unitool.cli.Io
import cx.m42.unitool.cli.Wizard
import cx.m42.unitool.cli.answer
import cx.m42.unitool.cli.fail
import cx.m42.unitool.cli.number
import cx.m42.unitool.cli.pick
import cx.m42.unitool.cli.shellLine
import cx.m42.unitool.cli.write
import cx.m42.unitool.cli.yes
import cx.m42.unitool.kit.split.SplitLink
import cx.m42.unitool.kit.split.SplitPlan

/**
 * `unitool split` at a terminal (.ai-notes/done/split 03 §2.1): numbered steps, each explained and
 * checked, a summary, and at the end the command that does the same. The limits and checks are the
 * kit's ([SplitPlan], [SplitLink.checkBase]); the work is [MakePlan] and [JoinPlan], the very code
 * the commands run.
 */
internal class SplitWizard : Wizard {

    override fun run(io: Io, command: String): Int {
        io.say("Split: keep a secret in parts. Any K of N parts open it; fewer tell nothing.")
        return when (
            io.pick(
                "What would you like to do?",
                listOf(
                    "make" to "Encrypt a secret and split its key into parts",
                    "join" to "Put parts together and decrypt",
                    "inspect" to "Say what a container or a part is",
                ),
            )
        ) {
            0 -> make(io, command)
            1 -> join(io, command)
            else -> inspect(io, command)
        }
    }

    private fun make(io: Io, command: String): Int {
        val steps = 5
        val n = io.number("Step 1 of $steps · How many parts in all?", SplitPlan.PARTS, SplitPlan.SUGGESTED_N)
        val k = io.number("Step 2 of $steps · How many parts open the secret?", SplitPlan.thresholds(n), minOf(SplitPlan.SUGGESTED_K, n))
        io.say("  ${meaning(n, k)}")

        var base: String
        while (true) {
            base = io.answer("Step 3 of $steps · Link address for the parts, e.g. https://example.com/p: ")
            val problem = SplitLink.checkBase(base) ?: break
            io.say("  ${words(problem)}")
        }
        io.say("  The page at this address must not run scripts: a script there can read the part after '#'.")

        var out: String
        while (true) {
            out = io.answer("Step 4 of $steps · Where to save the encrypted container?", "secret.ucrypt")
            if (!io.exists(out)) break
            io.say("  $out exists. A container is never replaced: it may be the only copy. Choose another name.")
        }

        val show = Show.entries[io.pick("Step 5 of $steps · How to hand out the parts?", Show.entries.map { it.id to it.label })]
        val outDir = if (show == Show.Screen) "." else io.answer("  Folder for the part files", ".")
        if (show == Show.Screen && !io.stdoutIsTerminal) fail("the parts go to the screen only when stdout is a terminal")

        val plan = MakePlan(n, k, base, out, armor = false, show = show, outDir = outDir)
        io.say("")
        io.say("  $k of $n parts · links on $base · container $out · parts ${if (show == Show.Screen) "on screen" else "as ${show.id} in $outDir"}")
        if (!io.yes("Go on?")) {
            io.say("Nothing was done.")
            return 0
        }
        plan.check(io)
        io.say("Type the secret, then Ctrl-D. It does not show as you type.")
        val code = plan.make(io, io.readStdinHidden())
        val words = mutableListOf("make", "-n", "$n", "-k", "$k", "--base", base, "-o", out, "--show", show.id)
        if (show != Show.Screen && outDir != ".") words += listOf("-d", outDir)
        io.say("The same as one command: " + shellLine(command.split(' ') + words))
        return code
    }

    private fun join(io: Io, command: String): Int {
        var file: String
        while (true) {
            file = io.answer("Step 1 of 3 · The encrypted container", "secret.ucrypt")
            if (io.exists(file)) break
            io.say("  there is no $file")
        }
        val (header, _) = readContainer(io, io.readFile(file), file)
        val split = header.split ?: fail("$file is for SSH keys, not opened by parts")
        io.say("  It takes ${split.k} of its ${split.n} parts. They are asked one at a time and do not show as you type.")

        var out: String? = null
        while (true) {
            val answer = io.answer("Step 2 of 3 · Save the secret to a file? A name, or Enter to show it here: ")
            if (answer.isEmpty()) break
            if (!io.exists(answer)) {
                out = answer
                break
            }
            io.say("  $answer exists; choose another name")
        }

        io.say("Step 3 of 3 · The parts")
        if (out == null && io.stdoutIsTerminal) io.say("The secret will show below. Make sure nobody is looking.")
        val code = JoinPlan(file, emptyList(), out).join(io)
        if (out != null) {
            io.say("The secret is in $out, readable by you only.")
        } else if (io.stdoutIsTerminal) {
            io.pause("Press Enter to clear the screen")
            io.write(CLEAR_ALL)
        }
        io.say("The same as one command: " + shellLine(command.split(' ') + listOf("join", file) + listOfNotNull(out?.let { "-o" }, out)))
        return code
    }

    private fun inspect(io: Io, command: String): Int {
        val what = io.pick("Inspect what?", listOf("container" to "a container file", "part" to "a part: the link, typed hidden"))
        if (what == 1) return inspect(io, null)
        var file: String
        while (true) {
            file = io.answer("The container", "secret.ucrypt")
            if (io.exists(file)) break
            io.say("  there is no $file")
        }
        val code = inspect(io, file)
        io.say("The same as one command: " + shellLine(command.split(' ') + listOf("inspect", file)))
        return code
    }
}
