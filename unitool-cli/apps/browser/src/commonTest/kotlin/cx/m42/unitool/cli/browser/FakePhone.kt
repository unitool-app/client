package cx.m42.unitool.cli.browser

import cx.m42.unitool.kit.crypto.secureRandomBytes
import cx.m42.unitool.kit.json.FlatJson
import cx.m42.unitool.kit.link.FrameReader
import cx.m42.unitool.kit.link.LinkMessage
import cx.m42.unitool.kit.link.LinkWire
import cx.m42.unitool.kit.link.Mdns
import cx.m42.unitool.kit.link.NoiseHandshake
import cx.m42.unitool.kit.link.NoiseKeyPair
import cx.m42.unitool.kit.link.NoisePattern
import cx.m42.unitool.kit.link.NoiseTransport
import cx.m42.unitool.kit.link.PairingCode
import cx.m42.unitool.kit.link.PasswordBox
import cx.m42.unitool.kit.ssh.Age
import cx.m42.unitool.kit.ssh.SshPublicKey

/**
 * A phone made of the kit, sans-IO like the kit: bytes the computer writes go in, bytes for the
 * computer come out at once. Enough of "Passwords" (01 §2–§4) to pair, to answer, and to misbehave on
 * purpose — the tests set [behaviour].
 */
internal class FakePhone {
    val key = NoiseKeyPair.generate()
    val linkId = secureRandomBytes(8)
    var name = "Test phone"

    /** Set when the host's pairing message shows its QR: the phone "scanned" it. */
    var scanned: PairingCode? = null
    var paired: ByteArray? = null
    var sshKey: SshPublicKey? = null

    sealed interface Behaviour {
        class Fill(val username: String, val password: String) : Behaviour
        data object Deny : Behaviour
        class Error(val code: String) : Behaviour
        /** The answer of another request, then the right one. */
        class WrongIdFirst(val username: String, val password: String) : Behaviour
        /** A box whose `req` is not the request's: what a spoofed answer would carry. */
        data object WrongDigest : Behaviour
        /** Says nothing after SHOWN: the host must give up by itself or on cancel. */
        data object Silent : Behaviour
    }

    var behaviour: Behaviour = Behaviour.Fill("octocat", "s3cret\"\\pass")
    val requests = ArrayList<LinkMessage.PasswordRequest>()

    /** The mDNS answer for the names it announces now. */
    fun mdns(): ByteArray {
        val names = listOfNotNull(scanned?.pairingInstance, paired?.let { PairingCode.linkInstance(linkId) })
        val type = LinkWire.SERVICE_TYPE
        return Mdns.response(
            names.map { Mdns.Record.Ptr(Mdns.serviceName(type), 120, Mdns.instanceName(it, type)) },
            names.flatMap { listOf(Mdns.Record.Srv(Mdns.instanceName(it, type), 120, LinkWire.PORT, "phone.local")) } +
                Mdns.Record.A("phone.local", 120, byteArrayOf(10, 0, 0, 2)),
        )
    }

    /** One connection's state. */
    inner class Session {
        private val frames = FrameReader()
        private var preamble = ByteArray(0)
        private var mode: LinkWire.Mode? = null
        private var hs: NoiseHandshake? = null
        private var t: NoiseTransport? = null
        val out = ArrayList<Byte>()
        var closed = false

        fun push(bytes: ByteArray) {
            var rest = bytes
            if (mode == null) {
                val need = 4 - preamble.size
                preamble += rest.copyOf(minOf(need, rest.size))
                rest = rest.copyOfRange(minOf(need, rest.size), rest.size)
                if (preamble.size < 4) return
                mode = LinkWire.mode(preamble) ?: return close()
            }
            frames.push(rest)
            while (!closed) handle(frames.next() ?: break)
        }

        private fun close() {
            closed = true
        }

        private fun send(m: LinkMessage) {
            out += LinkWire.frame(t!!.seal(LinkMessage.encode(m))).toList()
        }

        private fun handle(frame: ByteArray) {
            val transport = t
            if (transport == null) return handshake(frame)
            val plain = transport.open(frame) ?: return close()
            when (val m = LinkMessage.decode(plain)) {
                is LinkMessage.Paired -> paired = transport.remoteStatic
                is LinkMessage.PasswordRequest -> answer(m, plain)
                else -> close()
            }
        }

        private fun handshake(frame: ByteArray) {
            when (mode) {
                LinkWire.Mode.Pair -> {
                    val code = scanned ?: return close()
                    val h = hs ?: NoiseHandshake.responder(NoisePattern.XXpsk3, key, psk = code.psk, prologue = LinkWire.prologue(LinkWire.Mode.Pair)).also { hs = it }
                    h.read(frame) ?: return close()
                    if (h.isMyTurn) {
                        out += LinkWire.frame(h.write()).toList()
                    } else if (h.isComplete) {
                        if (!h.remoteStatic!!.contentEquals(code.computerKey)) return close()
                        t = h.split()
                        sshKey = SshPublicKey.fromBlob(code.sshKey)
                        send(LinkMessage.Welcome(name, linkId))
                    }
                }
                LinkWire.Mode.Session -> {
                    val computer = paired ?: return close()
                    val h = NoiseHandshake.responder(NoisePattern.KK, key, computer, prologue = LinkWire.prologue(LinkWire.Mode.Session))
                    h.read(frame) ?: return close()
                    out += LinkWire.frame(h.write()).toList()
                    t = h.split()
                }
                null -> close()
            }
        }

        private fun answer(req: LinkMessage.PasswordRequest, bytes: ByteArray) {
            requests += req
            send(LinkMessage.PasswordShown(req.id))
            fun box(request: ByteArray, username: String, password: String) =
                Age.encrypt(PasswordBox.plaintext(request, req.origin, username, password.encodeToByteArray()), listOf(sshKey!!))
            when (val b = behaviour) {
                is Behaviour.Fill -> send(LinkMessage.PasswordResponse(req.id, LinkMessage.FILL, box(bytes, b.username, b.password)))
                Behaviour.Deny -> send(LinkMessage.PasswordResponse(req.id, LinkMessage.DENY, ByteArray(0)))
                is Behaviour.Error -> send(LinkMessage.Error(b.code))
                is Behaviour.WrongIdFirst -> {
                    send(LinkMessage.PasswordResponse(ByteArray(16), LinkMessage.FILL, box(bytes, "intruder", "nope")))
                    send(LinkMessage.PasswordResponse(req.id, LinkMessage.FILL, box(bytes, b.username, b.password)))
                }
                Behaviour.WrongDigest -> send(LinkMessage.PasswordResponse(req.id, LinkMessage.FILL, box(ByteArray(10), "x", "y")))
                Behaviour.Silent -> Unit
            }
        }
    }
}

/**
 * The computer around the host in a test: stdin is the extension's messages, stdout what the host
 * says back, the network is [phone]. [clock] moves on its own a little at each wait, so a timeout
 * that is reached is reached quickly.
 */
internal class FakeBrowserSystem(val phone: FakePhone?, input: List<String> = emptyList()) : BrowserSystem {
    var time = 1_792_000_000_000
    private val stdin = ArrayDeque<Byte>().apply { input.forEach { addAll(frame(it).toList()) } }
    private var stdinEnded = false
    val stdout = ArrayList<Byte>()
    val dirs = LinkedHashSet<String>()
    val executables = LinkedHashMap<String, String>()
    val existing = HashSet<String>()
    var connects = 0

    /** The host's messages so far. */
    fun messages(): List<Map<String, String>> {
        val bytes = stdout.toByteArray()
        val out = ArrayList<Map<String, String>>()
        var at = 0
        while (at + 4 <= bytes.size) {
            val n = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8) or ((bytes[at + 2].toInt() and 0xff) shl 16)
            out += FlatJson.parseText(bytes.copyOfRange(at + 4, at + 4 + n)) ?: mapOf("raw" to bytes.copyOfRange(at + 4, at + 4 + n).decodeToString())
            at += 4 + n
        }
        return out
    }

    fun rawStdout(): String = stdout.toByteArray().decodeToString()

    override fun now(): Long = time
    override fun hostname(): String = "laptop"
    override fun executable(): String = "/opt/unitool/unitool"
    override fun mkdirs(path: String) {
        dirs += path
    }
    override fun writeExecutable(path: String, bytes: ByteArray) {
        executables[path] = bytes.decodeToString()
    }
    override fun isDirectory(path: String): Boolean = path in existing
    override fun localAddresses(): List<String> = listOf("10.0.0.5")

    override fun connect(address: String, port: Int, timeoutMillis: Int): Connection? {
        time += timeoutMillis / 10
        val p = phone ?: return null
        if (address != "10.0.0.2" || port != LinkWire.PORT) return null
        connects++
        val session = p.Session()
        return object : Connection {
            override val remote = "$address:$port"
            override fun write(bytes: ByteArray) {
                if (session.closed) error("closed")
                session.push(bytes)
            }
            override fun read(buffer: ByteArray, timeoutMillis: Int): Int {
                if (session.out.isEmpty()) {
                    time += timeoutMillis
                    return if (session.closed) -1 else 0
                }
                val n = minOf(buffer.size, session.out.size)
                for (i in 0 until n) buffer[i] = session.out.removeAt(0)
                return n
            }
            override fun close() = Unit
        }
    }

    override fun multicastQuery(query: ByteArray, millis: Int): List<Datagram> {
        time += millis
        // The phone "scans" the QR the host has just shown the extension.
        val p = phone ?: return emptyList()
        messages().lastOrNull { it["type"] == "pair" }?.get("uri")?.let { uri -> if (p.scanned == null) p.scanned = PairingCode.parse(uri) }
        val asked = Mdns.parse(query) ?: return emptyList()
        if (asked.questions.none { it.type == Mdns.TYPE_PTR }) return emptyList()
        return listOf(Datagram("10.0.0.2", p.mdns()))
    }

    override val stdio: Stdio = object : Stdio {
        override fun read(buffer: ByteArray): Int {
            if (stdin.isEmpty()) {
                stdinEnded = true
                return -1
            }
            val n = minOf(buffer.size, stdin.size)
            for (i in 0 until n) buffer[i] = stdin.removeFirst()
            return n
        }
        override fun write(bytes: ByteArray) {
            stdout.addAll(bytes.toList())
        }
        override fun readable(timeoutMillis: Int): Boolean = stdin.isNotEmpty()
    }

    /** Adds a message the extension sends while the host is busy. */
    fun later(json: String) {
        stdin.addAll(frame(json).toList())
    }

    companion object {
        fun frame(json: String): ByteArray {
            val b = json.encodeToByteArray()
            val n = b.size
            return byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte()) + b
        }
    }
}
