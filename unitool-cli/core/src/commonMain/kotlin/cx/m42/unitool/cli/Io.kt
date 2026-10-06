package cx.m42.unitool.cli

/**
 * Everything a command sees of the outside world. [SystemIo] is the real process (POSIX, in
 * `linuxX64Main`), and `FakeIo` in `:unitool-cli:testing` is a scripted one.
 *
 * Output follows D269: [write] carries only the result (a code, bytes, an ID), and every word for
 * the person goes through [say] to stderr. That way `… | unitool qr encode` and `unitool … > file`
 * behave.
 */
public interface Io {
    public val stdinIsTerminal: Boolean
    public val stdoutIsTerminal: Boolean
    public val stderrIsTerminal: Boolean

    public fun env(name: String): String?

    /** All of stdin, up to its end. */
    public fun readStdin(): ByteArray

    /** Bytes to stdout. Throws [StdoutClosed] when the reader has gone away. */
    public fun write(bytes: ByteArray)

    public fun flush()

    /** A line to stderr. */
    public fun say(text: String)

    /**
     * Shows [prompt] on stderr with no line break and returns the line typed at the terminal, or
     * null at its end (Ctrl-D). The menu asks through this.
     */
    public fun ask(prompt: String): String?

    /**
     * Shows [prompt] and waits for Enter, on the terminal even when stdin is a pipe. Returns false
     * when there is no terminal to wait on.
     */
    public fun pause(prompt: String): Boolean

    /**
     * Replaces the file at [path] (relative to the working directory) with [bytes]. On failure it
     * throws [CommandFailure] carrying the system's reason.
     */
    public fun writeFile(path: String, bytes: ByteArray)
}

/** Someone is there to answer: the menu runs only then (D271). */
public val Io.interactive: Boolean get() = stdinIsTerminal && stderrIsTerminal

public fun Io.write(text: String) {
    write(text.encodeToByteArray())
}
