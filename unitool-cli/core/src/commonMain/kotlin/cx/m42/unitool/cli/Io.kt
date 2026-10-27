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
     * Like [ask], but nothing typed shows on the screen: for a secret, a line at a time (D272). It
     * reads the terminal itself (`/dev/tty`), so stdin may be a pipe. Null at Ctrl-D, or when there
     * is no terminal to read.
     */
    public fun secret(prompt: String): String? = ask(prompt)

    /** [readStdin], with the typing hidden when stdin is a terminal: secret text up to Ctrl-D. */
    public fun readStdinHidden(): ByteArray = readStdin()

    /**
     * Reports the mouse moving over the terminal window (xterm's any-motion tracking, which every
     * common terminal speaks) until Enter: [point] gets the cell's column and row for each report,
     * and [status] is redrawn on one line of stderr as it goes. Ctrl-C cancels. False when there
     * is no terminal to track on.
     */
    public fun trackMouse(point: (x: Int, y: Int) -> Unit, status: () -> String): Boolean = false

    /** The whole file at [path]. On failure it throws [CommandFailure] carrying the system's reason. */
    public fun readFile(path: String): ByteArray

    public fun exists(path: String): Boolean

    /**
     * Writes [bytes] to the file at [path] (relative to the working directory). [private] makes it
     * readable by its owner only (0600) even when it existed before; without [replace] an existing
     * file is a failure and stays as it was. On failure it throws [CommandFailure] carrying the
     * system's reason.
     */
    public fun writeFile(path: String, bytes: ByteArray, private: Boolean = false, replace: Boolean = true)
}

/** Someone is there to answer: the menu runs only then (D271). */
public val Io.interactive: Boolean get() = stdinIsTerminal && stderrIsTerminal

public fun Io.write(text: String) {
    write(text.encodeToByteArray())
}
