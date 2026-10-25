package cx.m42.services.link

import cx.m42.unitool.kit.link.LinkWire
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSNetService
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.AF_INET6
import platform.posix.IPPROTO_IPV6
import platform.posix.IPV6_V6ONLY
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.memset
import platform.posix.read
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr_in6
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.SHUT_RDWR

/**
 * The phone's listener on iOS (D538, D551): a POSIX socket (one IPv6 socket that takes IPv4 too) and
 * an `NSNetService` per name. Info.plist carries `NSBonjourServices = _unitool._tcp` and
 * `NSLocalNetworkUsageDescription`; the system asks "Local Network" the first time this publishes —
 * at the first pairing, since nothing listens before there is something to pair (D551).
 *
 * The app is in the foreground whenever this is open: the Passwords screen closes it when it leaves
 * the screen, and iOS would suspend the socket anyway.
 */
@OptIn(ExperimentalForeignApi::class)
public class IosLocalLink : LocalLink {
    override fun open(scope: CoroutineScope, names: List<String>, accept: suspend (LinkPipe) -> Unit): LinkListener =
        IosListener(scope, names, accept)
}

@OptIn(ExperimentalForeignApi::class)
private class IosListener(
    private val scope: CoroutineScope,
    names: List<String>,
    private val accept: suspend (LinkPipe) -> Unit,
) : LinkListener {
    private val _state = MutableStateFlow<LinkState>(LinkState.Starting)
    override val state: StateFlow<LinkState> get() = _state.asStateFlow()

    private var fd = -1
    private var port = 0
    private var names: List<String> = names
    private val services = LinkedHashMap<String, NSNetService>()
    private val job: Job = scope.launch(Dispatchers.IO) { run() }
    private var closed = false

    private fun run() {
        val listening = open(LinkWire.PORT) ?: open(0)
        if (listening == null) {
            _state.value = LinkState.Failed("bind")
            return
        }
        fd = listening.first
        port = listening.second
        dispatch_async(dispatch_get_main_queue()) { publish(this.names) }
        _state.value = LinkState.Listening(port)
        while (scope.isActive && !closed) {
            val client = accept(fd, null, null)
            if (client < 0) break
            memScoped {
                val one = alloc<IntVar>().apply { value = 1 }
                setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().convert())
            }
            scope.launch(Dispatchers.IO) {
                val pipe = FdPipe(client)
                try {
                    accept(pipe)
                } catch (_: Exception) {
                } finally {
                    pipe.close()
                }
            }
        }
    }

    /** A listening socket on [wanted] (0: any) and the port it got, or null. */
    private fun open(wanted: Int): Pair<Int, Int>? = memScoped {
        val s = socket(AF_INET6, SOCK_STREAM, 0)
        if (s < 0) return@memScoped null
        val one = alloc<IntVar>().apply { value = 1 }
        val zero = alloc<IntVar>().apply { value = 0 }
        setsockopt(s, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
        setsockopt(s, IPPROTO_IPV6, IPV6_V6ONLY, zero.ptr, sizeOf<IntVar>().convert())
        val address = alloc<sockaddr_in6>()
        memset(address.ptr, 0, sizeOf<sockaddr_in6>().convert())
        address.sin6_len = sizeOf<sockaddr_in6>().convert()
        address.sin6_family = AF_INET6.convert()
        address.sin6_port = networkOrder(wanted)
        if (bind(s, address.ptr.reinterpret(), sizeOf<sockaddr_in6>().convert()) != 0 || listen(s, 8) != 0) {
            close(s)
            return@memScoped null
        }
        val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in6>().convert() }
        getsockname(s, address.ptr.reinterpret(), length.ptr)
        s to hostOrder(address.sin6_port.toInt())
    }

    private fun networkOrder(port: Int): UShort = (((port and 0xff) shl 8) or ((port shr 8) and 0xff)).toUShort()
    private fun hostOrder(value: Int): Int = ((value and 0xff) shl 8) or ((value shr 8) and 0xff)

    /** On the main queue: NSNetService publishes on the run loop it was made on. */
    private fun publish(names: List<String>) {
        if (closed) return
        (services.keys - names.toSet()).forEach { services.remove(it)?.stop() }
        (names - services.keys).forEach { name ->
            val service = NSNetService(domain = "local.", type = "${LinkWire.SERVICE_TYPE}.", name = name, port = port)
            service.publish()
            services[name] = service
        }
    }

    override fun rename(names: List<String>) {
        this.names = names
        if (port != 0) dispatch_async(dispatch_get_main_queue()) { publish(names) }
    }

    override fun close() {
        closed = true
        dispatch_async(dispatch_get_main_queue()) {
            services.values.forEach { it.stop() }
            services.clear()
        }
        if (fd >= 0) {
            shutdown(fd, SHUT_RDWR)
            close(fd)
            fd = -1
        }
        job.cancel()
        _state.value = LinkState.Closed
    }
}

@OptIn(ExperimentalForeignApi::class)
private class FdPipe(private var fd: Int) : LinkPipe {
    override val remote: String = "fd $fd"

    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
        if (fd < 0) return@withContext -1
        val n = buffer.usePinned { read(fd, it.addressOf(0), buffer.size.convert()) }
        if (n <= 0) -1 else n.toInt()
    }

    override suspend fun write(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            var at = 0
            bytes.usePinned { pinned ->
                while (at < bytes.size && fd >= 0) {
                    val n = send(fd, pinned.addressOf(at), (bytes.size - at).convert(), 0)
                    if (n <= 0) error("send failed")
                    at += n.toInt()
                }
            }
        }
    }

    override fun close() {
        if (fd >= 0) {
            shutdown(fd, SHUT_RDWR)
            close(fd)
            fd = -1
        }
    }
}
