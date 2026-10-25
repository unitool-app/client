package cx.m42.services.link

import cx.m42.superizer.runtime.ServiceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Listening for a computer in the same network (passwords-browser D538): a TCP port — 47370 when it
 * is free — and an mDNS announcement of `_unitool._tcp` under the names the app gives.
 *
 * The computer always calls; the phone only answers, and only while a screen holds a [LinkListener]
 * open. Closing it closes the port and withdraws the names. Nothing here knows what is said over a
 * connection: that is [LinkResponder] and the app.
 */
public interface LocalLink {

    /**
     * Starts listening and announcing [names] (mDNS instance names, see `PairingCode`). Every
     * connection a computer makes goes to [accept] in a coroutine of [scope], which closes the pipe
     * when [accept] returns. Never throws: a port or an announcement that fails shows in
     * [LinkListener.state].
     */
    public fun open(scope: CoroutineScope, names: List<String>, accept: suspend (LinkPipe) -> Unit): LinkListener

    public companion object {
        public val Key: ServiceKey<LocalLink> = ServiceKey("local-link")
    }
}

public interface LinkListener : AutoCloseable {
    public val state: StateFlow<LinkState>

    /** Announces [names] instead of what was announced: a pairing begins or ends, a computer is forgotten. */
    public fun rename(names: List<String>)
}

public sealed interface LinkState {
    public data object Starting : LinkState

    public data class Listening(val port: Int) : LinkState

    /** [reason] is for the log and the tests, never shown as is. */
    public data class Failed(val reason: String) : LinkState

    public data object Closed : LinkState
}

/** One accepted connection, as bytes. */
public interface LinkPipe {
    /** Up to [buffer]`.size` bytes into [buffer]; -1 when the computer has closed. */
    public suspend fun read(buffer: ByteArray): Int

    public suspend fun write(bytes: ByteArray)

    public fun close()

    /** The computer's address, for the log only. */
    public val remote: String
}
