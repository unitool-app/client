package cx.m42.unitool.cli.browser

/**
 * What the browser app needs of the computer beyond [cx.m42.unitool.cli.Io]: the clock, the network,
 * a few file-system facts and the raw stdio of native messaging. [systemBrowser] is the real one —
 * POSIX in the binary, java.net on the JVM for the tests.
 */
public interface BrowserSystem {
    /** Epoch milliseconds. */
    public fun now(): Long

    public fun hostname(): String

    /** The absolute path of the running `unitool`, for the wrapper the browser starts (D566). */
    public fun executable(): String?

    /** Creates [path] and its parents, each new one 0700. */
    public fun mkdirs(path: String)

    /** Writes a file with [mode] (e.g. `0x1C0` for 0700), replacing it. */
    public fun writeExecutable(path: String, bytes: ByteArray)

    public fun isDirectory(path: String): Boolean

    /** This computer's IPv4 addresses in the network, loopback left out: a hint in the QR code (D557). */
    public fun localAddresses(): List<String>

    /** A TCP connection within [timeoutMillis], or null. */
    public fun connect(address: String, port: Int, timeoutMillis: Int): Connection?

    /**
     * mDNS (D547): sends [query] to 224.0.0.251:5353 — from port 5353 joined to the group where it
     * can, and from a port of its own as a one-shot querier as well — and collects every datagram
     * that arrives for [millis].
     */
    public fun multicastQuery(query: ByteArray, millis: Int): List<Datagram>

    /** The browser's end of native messaging: stdin and stdout, unbuffered. */
    public val stdio: Stdio
}

public class Datagram(public val from: String, public val bytes: ByteArray)

public interface Connection : AutoCloseable {
    public val remote: String

    public fun write(bytes: ByteArray)

    /** Up to [buffer]`.size` bytes; 0 when [timeoutMillis] passed with nothing, -1 when the other side closed. */
    public fun read(buffer: ByteArray, timeoutMillis: Int): Int
}

public interface Stdio {
    /** Blocks; -1 at the end (the browser closed the port). */
    public fun read(buffer: ByteArray): Int

    public fun write(bytes: ByteArray)

    /** Whether a read would not block — a message or the end has come — within [timeoutMillis]. */
    public fun readable(timeoutMillis: Int): Boolean
}

public expect fun systemBrowser(): BrowserSystem
