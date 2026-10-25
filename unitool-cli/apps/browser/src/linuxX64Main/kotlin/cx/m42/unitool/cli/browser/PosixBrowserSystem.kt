package cx.m42.unitool.cli.browser

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.linux.freeifaddrs
import platform.linux.getifaddrs
import platform.linux.ifaddrs
import platform.posix.AF_INET
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.IFF_LOOPBACK
import platform.posix.IFF_UP
import platform.posix.INADDR_ANY
import platform.posix.IPPROTO_IP
import platform.posix.IP_ADD_MEMBERSHIP
import platform.posix.IP_MULTICAST_IF
import platform.posix.IP_MULTICAST_TTL
import platform.posix.MSG_NOSIGNAL
import platform.posix.O_CREAT
import platform.posix.O_NONBLOCK
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.POLLIN
import platform.posix.POLLOUT
import platform.posix.SOCK_DGRAM
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.SO_REUSEADDR
import platform.posix.SO_REUSEPORT
import platform.posix.STDIN_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.close
import platform.posix.closedir
import platform.posix.connect
import platform.posix.errno
import platform.posix.fchmod
import platform.posix.fcntl
import platform.posix.gethostname
import platform.posix.getsockopt
import platform.posix.gettimeofday
import platform.posix.in_addr
import platform.posix.ip_mreq
import platform.posix.mkdir
import platform.posix.open
import platform.posix.opendir
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.read
import platform.posix.readlink
import platform.posix.recvfrom
import platform.posix.send
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.timeval
import platform.posix.write

public actual fun systemBrowser(): BrowserSystem = PosixBrowserSystem()

/**
 * The binary's computer: libc's sockets, `poll` for every wait, `getifaddrs` for the addresses.
 * mDNS is a plain UDP socket (D547) — no Avahi: one on 5353 in the group on each interface, and one
 * on a port of its own whose answers come back as unicast (RFC 6762 §6.7).
 */
@OptIn(ExperimentalForeignApi::class)
internal class PosixBrowserSystem : BrowserSystem {

    override fun now(): Long = memScoped {
        val tv = alloc<timeval>()
        gettimeofday(tv.ptr, null)
        tv.tv_sec * 1000L + tv.tv_usec / 1000
    }

    override fun hostname(): String = memScoped {
        val buffer = allocArray<ByteVar>(256)
        if (gethostname(buffer, 255u) == 0) buffer.toKString().substringBefore('.') else "computer"
    }

    override fun executable(): String? = memScoped {
        val buffer = allocArray<ByteVar>(4096)
        val n = readlink("/proc/self/exe", buffer, 4095u)
        if (n <= 0) null else buffer.readBytes(n.toInt()).decodeToString()
    }

    override fun mkdirs(path: String) {
        var at = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            at += "/$part"
            mkdir(at, 0x1C0u) // 0700; an existing one stays as it is
        }
    }

    override fun writeExecutable(path: String, bytes: ByteArray) {
        val fd = open(path, O_WRONLY or O_CREAT or O_TRUNC, 0x1C0)
        if (fd < 0) error("cannot write $path")
        try {
            fchmod(fd, 0x1C0u)
            bytes.usePinned { write(fd, it.addressOf(0), bytes.size.convert()) }
        } finally {
            close(fd)
        }
    }

    override fun isDirectory(path: String): Boolean {
        val dir = opendir(path) ?: return false
        closedir(dir)
        return true
    }

    override fun localAddresses(): List<String> = interfaces().map { it.second }

    /** (address as in_addr's 32 bits, dotted) of every up IPv4 interface but loopback. */
    private fun interfaces(): List<Pair<UInt, String>> = memScoped {
        val list = alloc<kotlinx.cinterop.CPointerVar<ifaddrs>>()
        if (getifaddrs(list.ptr) != 0) return@memScoped emptyList()
        val out = ArrayList<Pair<UInt, String>>()
        var p: CPointer<ifaddrs>? = list.value
        while (p != null) {
            val ifa = p.pointed
            val addr = ifa.ifa_addr
            val flags = ifa.ifa_flags.toInt()
            if (addr != null && addr.pointed.sa_family.toInt() == AF_INET && flags and IFF_UP != 0 && flags and IFF_LOOPBACK == 0) {
                val sin = addr.reinterpret<sockaddr_in>().pointed
                out += sin.sin_addr.s_addr to dotted(sin.sin_addr.s_addr)
            }
            p = ifa.ifa_next
        }
        freeifaddrs(list.value)
        out.distinctBy { it.first }
    }

    override fun connect(address: String, port: Int, timeoutMillis: Int): Connection? = memScoped {
        val sin = alloc<sockaddr_in>()
        sin.sin_family = AF_INET.convert()
        sin.sin_port = swap16(port)
        sin.sin_addr.s_addr = ipv4(address) ?: return@memScoped null
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) return@memScoped null
        val flags = fcntl(fd, F_GETFL, 0)
        fcntl(fd, F_SETFL, flags or O_NONBLOCK)
        val r = connect(fd, sin.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert())
        if (r != 0 && errno != EINPROGRESS) {
            close(fd)
            return@memScoped null
        }
        if (r != 0) {
            val p = alloc<pollfd>()
            p.fd = fd
            p.events = POLLOUT.convert()
            if (poll(p.ptr, 1u, timeoutMillis) <= 0) {
                close(fd)
                return@memScoped null
            }
            val error = alloc<IntVar>()
            val length = alloc<socklen_tVar>().apply { value = sizeOf<IntVar>().convert() }
            getsockopt(fd, SOL_SOCKET, SO_ERROR, error.ptr, length.ptr)
            if (error.value != 0) {
                close(fd)
                return@memScoped null
            }
        }
        fcntl(fd, F_SETFL, flags)
        PosixConnection(fd, "$address:$port")
    }

    override fun multicastQuery(query: ByteArray, millis: Int): List<Datagram> = memScoped {
        val sockets = ArrayList<Int>()
        val nics = interfaces()
        val group = alloc<in_addr>()
        group.s_addr = ipv4("224.0.0.251")!!
        val to = alloc<sockaddr_in>()
        to.sin_family = AF_INET.convert()
        to.sin_port = swap16(5353)
        to.sin_addr.s_addr = group.s_addr
        val one = alloc<IntVar>().apply { value = 1 }
        val ttl = alloc<IntVar>().apply { value = 255 }

        fun udp(port: Int, join: Boolean): Int? {
            val fd = socket(AF_INET, SOCK_DGRAM, 0)
            if (fd < 0) return null
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, one.ptr, sizeOf<IntVar>().convert())
            setsockopt(fd, IPPROTO_IP, IP_MULTICAST_TTL, ttl.ptr, sizeOf<IntVar>().convert())
            val local = alloc<sockaddr_in>()
            local.sin_family = AF_INET.convert()
            local.sin_port = swap16(port)
            local.sin_addr.s_addr = INADDR_ANY
            if (platform.posix.bind(fd, local.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
                close(fd)
                return null
            }
            if (join) {
                val targets = nics.map { it.first }.ifEmpty { listOf(INADDR_ANY) }
                for (nic in targets) {
                    val mreq = alloc<ip_mreq>()
                    mreq.imr_multiaddr.s_addr = group.s_addr
                    mreq.imr_interface.s_addr = nic
                    setsockopt(fd, IPPROTO_IP, IP_ADD_MEMBERSHIP, mreq.ptr, sizeOf<ip_mreq>().convert())
                }
            }
            return fd
        }

        udp(5353, join = true)?.let { sockets += it }
        udp(0, join = false)?.let { sockets += it }
        // The query out of every interface, from each socket.
        for (fd in sockets) {
            val targets = nics.map { it.first }.ifEmpty { listOf(INADDR_ANY) }
            for (nic in targets) {
                val via = alloc<in_addr>().apply { s_addr = nic }
                setsockopt(fd, IPPROTO_IP, IP_MULTICAST_IF, via.ptr, sizeOf<in_addr>().convert())
                query.usePinned { sendto(fd, it.addressOf(0), query.size.convert(), 0, to.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) }
            }
        }
        val out = ArrayList<Datagram>()
        val deadline = now() + millis
        val fds = allocArray<pollfd>(sockets.size)
        sockets.forEachIndexed { i, fd -> fds[i].fd = fd; fds[i].events = POLLIN.convert() }
        val buffer = ByteArray(9000)
        while (sockets.isNotEmpty()) {
            val left = (deadline - now()).toInt()
            if (left <= 0) break
            if (poll(fds, sockets.size.convert(), left) <= 0) continue
            for (i in sockets.indices) {
                if (fds[i].revents.toInt() and POLLIN == 0) continue
                val from = alloc<sockaddr_in>()
                val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
                val n = buffer.usePinned { recvfrom(sockets[i], it.addressOf(0), buffer.size.convert(), 0, from.ptr.reinterpret(), length.ptr) }
                if (n > 0) {
                    out += Datagram(dotted(from.sin_addr.s_addr), buffer.copyOf(n.toInt()))
                }
            }
        }
        sockets.forEach { close(it) }
        out
    }

    override val stdio: Stdio = object : Stdio {
        override fun read(buffer: ByteArray): Int {
            while (true) {
                val n = buffer.usePinned { read(STDIN_FILENO, it.addressOf(0), buffer.size.convert()) }
                if (n < 0 && errno == EINTR) continue
                return if (n <= 0) -1 else n.toInt()
            }
        }

        override fun write(bytes: ByteArray) {
            var at = 0
            bytes.usePinned { pinned ->
                while (at < bytes.size) {
                    val n = write(STDOUT_FILENO, pinned.addressOf(at), (bytes.size - at).convert())
                    if (n < 0 && errno == EINTR) continue
                    if (n <= 0) error("stdout closed")
                    at += n.toInt()
                }
            }
        }

        override fun readable(timeoutMillis: Int): Boolean = memScoped {
            val p = alloc<pollfd>()
            p.fd = STDIN_FILENO
            p.events = POLLIN.convert()
            poll(p.ptr, 1u, timeoutMillis) > 0
        }
    }

    /** `a.b.c.d` as `in_addr.s_addr` holds it: network order, which on this little-endian machine puts `a` lowest. */
    private fun ipv4(text: String): UInt? {
        val parts = text.split('.').map { it.toIntOrNull() ?: return null }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return null
        return (parts[0] or (parts[1] shl 8) or (parts[2] shl 16) or (parts[3] shl 24)).toUInt()
    }

    private fun dotted(address: UInt): String = (0 until 4).joinToString(".") { ((address shr (8 * it)) and 0xffu).toString() }

    private fun swap16(port: Int): UShort = (((port and 0xff) shl 8) or ((port shr 8) and 0xff)).toUShort()
}

@OptIn(ExperimentalForeignApi::class)
private class PosixConnection(private var fd: Int, override val remote: String) : Connection {
    override fun write(bytes: ByteArray) {
        var at = 0
        bytes.usePinned { pinned ->
            while (at < bytes.size) {
                val n = send(fd, pinned.addressOf(at), (bytes.size - at).convert(), MSG_NOSIGNAL)
                if (n < 0 && errno == EINTR) continue
                if (n <= 0) error("the phone closed the connection")
                at += n.toInt()
            }
        }
    }

    override fun read(buffer: ByteArray, timeoutMillis: Int): Int = memScoped {
        val p = alloc<pollfd>()
        p.fd = fd
        p.events = POLLIN.convert()
        val ready = poll(p.ptr, 1u, timeoutMillis)
        if (ready == 0) return@memScoped 0
        if (ready < 0) return@memScoped if (errno == EINTR) 0 else -1
        val n = buffer.usePinned { read(fd, it.addressOf(0), buffer.size.convert()) }
        if (n <= 0) -1 else n.toInt()
    }

    override fun close() {
        if (fd >= 0) {
            close(fd)
            fd = -1
        }
    }
}
