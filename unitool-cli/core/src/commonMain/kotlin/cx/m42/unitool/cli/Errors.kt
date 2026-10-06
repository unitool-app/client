package cx.m42.unitool.cli

/** The call itself is wrong: exit code 2, one line and a pointer to the help (D270). */
public class UsageError(message: String) : RuntimeException(message)

/** The command could not do it. Exit [code], one line, and no stack trace unless `UNITOOL_DEBUG=1`. */
public class CommandFailure(message: String, public val code: Int = 1) : RuntimeException(message) {
    init {
        require(code in 1..125) { "an exit code for a failure is 1..125, not $code" }
    }
}

public fun fail(message: String, code: Int = 1): Nothing = throw CommandFailure(message, code)

public fun usage(message: String): Nothing = throw UsageError(message)

internal class HelpRequested : RuntimeException()

/** The person left the menu: Ctrl-D at a question. */
internal class Cancelled : RuntimeException()

/**
 * Whoever read stdout went away (`| head`). That is not the command's failure, so the run ends
 * quietly with 141, the code a shell reports for a process SIGPIPE killed, as `yes | head` does.
 */
public class StdoutClosed : RuntimeException("stdout closed")
