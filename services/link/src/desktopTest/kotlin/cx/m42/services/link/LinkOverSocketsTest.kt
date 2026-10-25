package cx.m42.services.link

import cx.m42.unitool.kit.crypto.secureRandomBytes
import cx.m42.unitool.kit.link.FrameReader
import cx.m42.unitool.kit.link.LinkMessage
import cx.m42.unitool.kit.link.LinkWire
import cx.m42.unitool.kit.link.Mdns
import cx.m42.unitool.kit.link.NoiseHandshake
import cx.m42.unitool.kit.link.NoiseKeyPair
import cx.m42.unitool.kit.link.NoisePattern
import cx.m42.unitool.kit.link.NoiseTransport
import cx.m42.unitool.kit.link.PairingCode
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The desktop listener on real sockets: this test is the computer. It finds the phone by an mDNS
 * query to the group — the kit's codec on a plain UDP socket, as the CLI does — connects, pairs with
 * XXpsk3, then opens a KK session and exchanges a request (passwords-browser 01).
 */
class LinkOverSocketsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun stop() = scope.cancel()

    private fun query(name: String): Mdns.Service? {
        val socket = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
            soTimeout = 500
        }
        socket.use {
            val q = Mdns.query(listOf(Mdns.Question(Mdns.serviceName(LinkWire.SERVICE_TYPE), Mdns.TYPE_PTR)))
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline) {
                it.send(DatagramPacket(q, q.size, InetAddress.getByName(Mdns.GROUP_V4), Mdns.PORT))
                val buffer = ByteArray(9000)
                try {
                    while (true) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        it.receive(packet)
                        val message = Mdns.parse(buffer.copyOf(packet.length)) ?: continue
                        Mdns.services(message.records, LinkWire.SERVICE_TYPE).firstOrNull { s -> s.instance == name }?.let { found -> return found }
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
            return null
        }
    }

    private class Wire(val socket: Socket) {
        private val frames = FrameReader()
        fun send(bytes: ByteArray) = socket.getOutputStream().apply { write(bytes); flush() }
        fun frame(): ByteArray? {
            val buffer = ByteArray(4096)
            while (true) {
                frames.next()?.let { return it }
                val n = socket.getInputStream().read(buffer)
                if (n < 0) return null
                frames.push(buffer, 0, n)
            }
        }
    }

    @Test
    fun aComputerFindsThePhonePairsAndAsks() = runBlocking {
        val phone = NoiseKeyPair.generate()
        val computer = NoiseKeyPair.generate()
        val psk = secureRandomBytes(32)
        val linkId = secureRandomBytes(8)
        val code = PairingCode(computer.public, psk, "laptop", ByteArray(51))
        val paired = ArrayList<ByteArray>()
        val requests = ArrayList<String>()

        val listener = DesktopLocalLink(preferredPort = 0).open(scope, listOf(code.pairingInstance)) { pipe ->
            val expected = LinkResponder.Expected(phone, paired.toList(), psk, computer.public)
            when (val accepted = LinkResponder(pipe).accept(expected)) {
                is LinkResponder.Accepted.Pairing -> accepted.channel.use { ch ->
                    ch.send(LinkMessage.Welcome("Test phone", linkId))
                    if (ch.receive(5000)?.message is LinkMessage.Paired) paired += ch.remoteStatic
                }
                is LinkResponder.Accepted.Session -> accepted.channel.use { ch ->
                    val req = ch.receive(5000)?.message as LinkMessage.PasswordRequest
                    requests += req.origin
                    ch.send(LinkMessage.PasswordShown(req.id))
                    ch.send(LinkMessage.PasswordResponse(req.id, LinkMessage.DENY, ByteArray(0)))
                }
                null -> Unit
            }
        }
        val port = withTimeout(5000) { listener.state.first { it is LinkState.Listening } as LinkState.Listening }.port

        // Pairing: find the phone under the name the psk gives.
        val found = assertNotNull(query(code.pairingInstance), "mDNS found nothing")
        assertEquals(port, found.port)
        val address = found.addresses.firstOrNull()?.let { InetAddress.getByAddress(it) } ?: InetAddress.getLoopbackAddress()
        Wire(Socket(address, port)).let { w ->
            w.send(LinkWire.preamble(LinkWire.Mode.Pair))
            val hs = NoiseHandshake.initiator(NoisePattern.XXpsk3, computer, psk = psk, prologue = LinkWire.prologue(LinkWire.Mode.Pair))
            w.send(LinkWire.frame(hs.write()))
            assertNotNull(hs.read(assertNotNull(w.frame())))
            w.send(LinkWire.frame(hs.write()))
            val t = hs.split()
            assertContentEquals(phone.public, t.remoteStatic)
            val welcome = assertIs<LinkMessage.Welcome>(LinkMessage.decode(t.open(assertNotNull(w.frame()))!!))
            assertContentEquals(linkId, welcome.linkId)
            w.send(LinkWire.frame(t.seal(LinkMessage.encode(LinkMessage.Paired))))
            w.socket.close()
        }
        withTimeout(5000) { while (paired.isEmpty()) kotlinx.coroutines.delay(20) }

        // The phone now announces the link id instead.
        listener.rename(listOf(PairingCode.linkInstance(linkId)))
        val again = assertNotNull(query(PairingCode.linkInstance(linkId)))
        Wire(Socket(address, again.port)).let { w ->
            w.send(LinkWire.preamble(LinkWire.Mode.Session))
            val hs = NoiseHandshake.initiator(NoisePattern.KK, computer, phone.public, prologue = LinkWire.prologue(LinkWire.Mode.Session))
            w.send(LinkWire.frame(hs.write()))
            assertNotNull(hs.read(assertNotNull(w.frame())))
            val t: NoiseTransport = hs.split()
            w.send(LinkWire.frame(t.seal(LinkMessage.encode(LinkMessage.PasswordRequest(ByteArray(16), "https://github.com", "test", System.currentTimeMillis())))))
            assertIs<LinkMessage.PasswordShown>(LinkMessage.decode(t.open(assertNotNull(w.frame()))!!))
            val res = assertIs<LinkMessage.PasswordResponse>(LinkMessage.decode(t.open(assertNotNull(w.frame()))!!))
            assertEquals(LinkMessage.DENY, res.decision)
            w.socket.close()
        }
        assertEquals(listOf("https://github.com"), requests)

        // A stranger with its own key gets nothing: the connection just closes.
        Wire(Socket(address, port)).let { w ->
            w.send(LinkWire.preamble(LinkWire.Mode.Session))
            w.send(LinkWire.frame(NoiseHandshake.initiator(NoisePattern.KK, NoiseKeyPair.generate(), phone.public, prologue = LinkWire.prologue(LinkWire.Mode.Session)).write()))
            assertNull(w.frame())
        }
        // Nor does something that is not the protocol at all.
        Wire(Socket(address, port)).let { w ->
            w.send("GET / HTTP/1.1\r\n\r\n".toByteArray())
            assertNull(w.frame())
        }
        listener.close()
        assertTrue(listener.state.value is LinkState.Closed)
    }
}
