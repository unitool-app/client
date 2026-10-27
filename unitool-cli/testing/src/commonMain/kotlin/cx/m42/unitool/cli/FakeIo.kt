package cx.m42.unitool.cli

/**
 * A scripted process. With [answers] it is someone at a terminal: stdin and stderr are terminals,
 * and every [ask] takes the next answer (null when they run out, which is Ctrl-D). Without them it
 * is a pipe. Files are kept in [files], keyed by the path exactly as the command wrote it.
 */
public class FakeIo(
    private val stdin: ByteArray = ByteArray(0),
    answers: List<String>? = null,
    override val stdoutIsTerminal: Boolean = false,
    /** Whether [pause] finds a keyboard: a terminal on stdin, or `/dev/tty` behind a pipe. */
    private val keyboard: Boolean = answers != null,
    private val environment: Map<String, String> = emptyMap(),
    /** Whether a path can be written; false makes [writeFile] fail the way a missing directory does. */
    private val writable: (String) -> Boolean = { true },
    /** What the mouse does over the terminal, cell by cell, before Enter; null when there is no terminal to track. */
    private val mouse: List<Pair<Int, Int>>? = null,
) : Io {
    private val queue = ArrayDeque(answers.orEmpty())
    private val out = ArrayList<Byte>()
    private val err = StringBuilder()

    override val stdinIsTerminal: Boolean = answers != null
    override val stderrIsTerminal: Boolean = answers != null

    /** Every prompt [ask] showed, in order. */
    public val prompts: MutableList<String> = mutableListOf()

    /** Every prompt [pause] showed, in order. */
    public val pauses: MutableList<String> = mutableListOf()

    /** Every prompt [secret] showed, in order. The answers themselves never reach [errText]. */
    public val secretPrompts: MutableList<String> = mutableListOf()

    public val files: MutableMap<String, ByteArray> = linkedMapOf()

    /** Paths last written private (0600). */
    public val privateFiles: MutableSet<String> = linkedSetOf()

    public val outBytes: ByteArray get() = out.toByteArray()
    public val outText: String get() = outBytes.decodeToString()

    /** stderr: every [say] line and every prompt with its answer, as a person would have seen them. */
    public val errText: String get() = err.toString()

    /** Empties stdout, stderr and the prompts, keeping the files: the next command in the same directory. */
    public fun clearOutput() {
        out.clear()
        err.clear()
        prompts.clear()
        pauses.clear()
        secretPrompts.clear()
    }

    override fun env(name: String): String? = environment[name]

    override fun readStdin(): ByteArray = stdin

    override fun write(bytes: ByteArray) {
        bytes.forEach { out += it }
    }

    override fun flush() {}

    override fun say(text: String) {
        err.append(text).append('\n')
    }

    override fun ask(prompt: String): String? {
        prompts += prompt
        err.append(prompt)
        return queue.removeFirstOrNull()?.also { err.append(it).append('\n') }
    }

    override fun pause(prompt: String): Boolean {
        if (!keyboard) return false
        pauses += prompt
        err.append(prompt).append('\n')
        return true
    }

    /** Takes the next answer like [ask], and shows only the prompt, as a terminal with echo off would. */
    override fun secret(prompt: String): String? {
        if (!keyboard) return null
        secretPrompts += prompt
        err.append(prompt).append('\n')
        return queue.removeFirstOrNull()
    }

    override fun readStdinHidden(): ByteArray = stdin

    /** Feeds [mouse] to [point], then shows the last [status] as the line left on the screen. */
    override fun trackMouse(point: (x: Int, y: Int) -> Unit, status: () -> String): Boolean {
        val moves = mouse ?: return false
        moves.forEach { (x, y) -> point(x, y) }
        err.append(status()).append('\n')
        return true
    }

    override fun readFile(path: String): ByteArray =
        files[path]?.copyOf() ?: fail("cannot read $path: No such file or directory")

    override fun exists(path: String): Boolean = path in files

    override fun writeFile(path: String, bytes: ByteArray, private: Boolean, replace: Boolean) {
        if (!writable(path)) fail("cannot write $path: No such file or directory")
        if (!replace && path in files) fail("$path exists; it was left as it was")
        files[path] = bytes.copyOf()
        if (private) privateFiles += path else privateFiles -= path
    }
}

public class Run(public val code: Int, public val io: FakeIo) {
    public val out: String get() = io.outText
    public val err: String get() = io.errText
    override fun toString(): String = "exit $code\n--- stdout\n$out\n--- stderr\n$err"
}

/** Runs [args] the way the shell would, in [io]. */
public fun Cli.exec(vararg args: String, io: FakeIo = FakeIo()): Run = Run(run(args.toList(), io), io)
