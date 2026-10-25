package cx.m42.services.link

import cx.m42.unitool.kit.link.LinkWire
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What announces the port: `NsdManager` on Android, the desktop's own responder. */
internal interface Announcer : AutoCloseable {
    fun rename(names: List<String>)
}

/**
 * The server socket, on Android and the desktop alike: port 47370, or any free one when something
 * else holds it — mDNS carries the port, so the computer finds either (D538). One coroutine accepts;
 * every connection gets its own, and is closed when [accept] returns.
 */
internal class SocketListener(
    private val scope: CoroutineScope,
    names: List<String>,
    private val accept: suspend (LinkPipe) -> Unit,
    private val announce: (port: Int, names: List<String>) -> Announcer,
    private val preferredPort: Int = LinkWire.PORT,
) : LinkListener {

    private val _state = MutableStateFlow<LinkState>(LinkState.Starting)
    override val state: StateFlow<LinkState> get() = _state.asStateFlow()

    private var server: ServerSocket? = null
    private var announcer: Announcer? = null
    private var names: List<String> = names
    private val job: Job
    private val open = HashSet<Socket>()

    init {
        job = scope.launch(Dispatchers.IO) { run() }
    }

    private suspend fun run() {
        val socket = try {
            bind()
        } catch (e: IOException) {
            _state.value = LinkState.Failed("bind: ${e.message}")
            return
        }
        server = socket
        val port = socket.localPort
        try {
            synchronized(this) { announcer = announce(port, names) }
        } catch (e: Exception) {
            _state.value = LinkState.Failed("announce: ${e.message}")
            runCatching { socket.close() }
            return
        }
        _state.value = LinkState.Listening(port)
        while (scope.isActive && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            synchronized(open) { open += client }
            scope.launch(Dispatchers.IO) {
                val pipe = SocketPipe(client)
                try {
                    accept(pipe)
                } catch (_: Exception) {
                    // A computer that misbehaves loses its connection, never the listener.
                } finally {
                    pipe.close()
                    synchronized(open) { open -= client }
                }
            }
        }
    }

    private fun bind(): ServerSocket = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(preferredPort))
        }
    } catch (_: IOException) {
        ServerSocket().apply { bind(InetSocketAddress(0)) }
    }

    override fun rename(names: List<String>) {
        synchronized(this) {
            this.names = names
            announcer?.rename(names)
        }
    }

    override fun close() {
        synchronized(this) {
            announcer?.close()
            announcer = null
        }
        runCatching { server?.close() }
        synchronized(open) { open.forEach { runCatching { it.close() } } }
        job.cancel()
        _state.value = LinkState.Closed
    }
}

internal class SocketPipe(private val socket: Socket) : LinkPipe {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override val remote: String = socket.remoteSocketAddress?.toString().orEmpty()

    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
        try {
            input.read(buffer)
        } catch (_: IOException) {
            -1
        }
    }

    override suspend fun write(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            output.write(bytes)
            output.flush()
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
