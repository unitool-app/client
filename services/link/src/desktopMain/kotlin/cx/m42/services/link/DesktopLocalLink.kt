package cx.m42.services.link

import cx.m42.unitool.kit.crypto.secureRandomBytes
import cx.m42.unitool.kit.link.LinkWire
import cx.m42.unitool.kit.link.Mdns
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import kotlinx.coroutines.CoroutineScope

/**
 * The desktop build's listener: the JVM server socket and an mDNS responder of its own on the
 * kit's codec (no JmDNS, no Avahi). Also what the end-to-end test runs as "the phone".
 */
public class DesktopLocalLink(private val preferredPort: Int = LinkWire.PORT) : LocalLink {
    override fun open(scope: CoroutineScope, names: List<String>, accept: suspend (LinkPipe) -> Unit): LinkListener =
        SocketListener(scope, names, accept, { port, current -> MdnsResponder(port, current) }, preferredPort)
}

/**
 * Answers `_unitool._tcp.local` for the names it holds (RFC 6762): PTR in the answer, SRV, TXT and
 * the host's IPv4 addresses beside it. A query from port 5353 is answered to the group; one from any
 * other port (a "legacy" one-shot querier) straight back to it, with the question echoed and a short
 * TTL (§6.7). Two announcements on start, a goodbye on close.
 */
internal class MdnsResponder(private val port: Int, names: List<String>) : Announcer {
    @Volatile private var names: List<String> = names
    private val host = "unitool-" + secureRandomBytes(3).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') } + ".local"
    private val group = InetAddress.getByName(Mdns.GROUP_V4)
    private val socket = MulticastSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(Mdns.PORT))
        timeToLive = 255
    }
    private val thread = Thread(::loop, "mdns-responder").apply { isDaemon = true }

    init {
        interfaces().forEach { runCatching { socket.joinGroup(InetSocketAddress(group, Mdns.PORT), it) } }
        thread.start()
        announce(TTL)
    }

    override fun rename(names: List<String>) {
        val gone = this.names - names.toSet()
        if (gone.isNotEmpty()) send(Mdns.response(gone.map { ptr(it, 0) }), group, Mdns.PORT)
        this.names = names
        announce(TTL)
    }

    override fun close() {
        runCatching { send(Mdns.response(names.map { ptr(it, 0) }), group, Mdns.PORT) }
        socket.close()
    }

    private fun announce(ttl: Long) {
        if (names.isEmpty()) return
        send(Mdns.response(names.map { ptr(it, ttl) }, additional(names, ttl)), group, Mdns.PORT)
    }

    private fun loop() {
        val buffer = ByteArray(9000)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketException) {
                return
            }
            val message = Mdns.parse(buffer.copyOf(packet.length)) ?: continue
            if (message.isResponse) continue
            val current = names
            val service = Mdns.serviceName(LinkWire.SERVICE_TYPE)
            val wanted = message.questions.filter { q ->
                q.name.equals(service, true) || current.any { q.name.equals(full(it), true) } || q.name.equals(host, true)
            }
            if (wanted.isEmpty() || current.isEmpty()) continue
            val legacy = packet.port != Mdns.PORT
            val ttl = if (legacy) 10L else TTL
            val answers = wanted.flatMap { q ->
                when {
                    q.name.equals(service, true) -> current.map { ptr(it, ttl) }
                    q.name.equals(host, true) -> addresses(ttl)
                    else -> current.filter { q.name.equals(full(it), true) }.flatMap { listOf(srv(it, ttl), txt(it, ttl)) }
                }
            }
            val bytes = Mdns.response(answers, additional(current, ttl), if (legacy) wanted else emptyList(), if (legacy) message.id else 0)
            if (legacy) send(bytes, packet.address, packet.port) else send(bytes, group, Mdns.PORT)
        }
    }

    private fun additional(current: List<String>, ttl: Long): List<Mdns.Record> =
        current.flatMap { listOf(srv(it, ttl), txt(it, ttl)) } + addresses(ttl)

    private fun full(name: String) = Mdns.instanceName(name, LinkWire.SERVICE_TYPE)
    private fun ptr(name: String, ttl: Long) = Mdns.Record.Ptr(Mdns.serviceName(LinkWire.SERVICE_TYPE), ttl, full(name))
    private fun srv(name: String, ttl: Long) = Mdns.Record.Srv(full(name), ttl, port, host)
    private fun txt(name: String, ttl: Long) = Mdns.Record.Txt(full(name), ttl, listOf("v=${LinkWire.VERSION}"))

    private fun addresses(ttl: Long): List<Mdns.Record> = interfaces().flatMap { nic ->
        nic.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.map { Mdns.Record.A(host, ttl, it.address) }
    }.distinctBy { (it as Mdns.Record.A).address.toList() }

    private fun send(bytes: ByteArray, to: InetAddress, port: Int) {
        runCatching { socket.send(DatagramPacket(bytes, bytes.size, to, port)) }
    }

    private fun interfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
    }.getOrDefault(emptyList())

    private companion object {
        const val TTL = 120L
    }
}
