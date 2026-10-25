package cx.m42.unitool.cli.browser

import cx.m42.unitool.kit.crypto.SecretBytes
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
import cx.m42.unitool.kit.ssh.PrivateKey

/**
 * The computer calls, the phone answers (D538): finding it by mDNS, pairing with `XXpsk3`, and one
 * password request over `KK` (passwords-browser 01). Blocking and single-threaded — the host is one
 * process per browser port — but never deaf: every wait checks [cancelled] at least five times a
 * second, so closing the popup stops it.
 */
public class PhoneClient(private val system: BrowserSystem, private val log: (String) -> Unit = {}) {

    /** Where a phone was found. */
    public class Found(public val instance: String, public val address: String, public val port: Int, internal val connection: Connection)

    /**
     * The phone answering to one of [names], connected: an mDNS round a second, the [fallback]
     * addresses (`host:port`) tried between them, until [deadline] or [cancelled].
     */
    public fun find(names: Set<String>, fallback: List<String>, deadline: Long, cancelled: () -> Boolean): Found? {
        val query = Mdns.query(listOf(Mdns.Question(Mdns.serviceName(LinkWire.SERVICE_TYPE), Mdns.TYPE_PTR)))
        while (system.now() < deadline && !cancelled()) {
            val answers = system.multicastQuery(query, ROUND_MILLIS)
            val records = answers.mapNotNull { Mdns.parse(it.bytes) }.filter { it.isResponse }.flatMap { it.records }
            for (service in Mdns.services(records, LinkWire.SERVICE_TYPE)) {
                if (service.instance.lowercase() !in names) continue
                val addresses = service.addresses.map(::address).ifEmpty { resolve(service.host) }
                for (a in addresses) {
                    system.connect(a, service.port, CONNECT_MILLIS)?.let { return Found(service.instance, a, service.port, it) }
                }
            }
            for (hint in fallback) {
                if (cancelled()) return null
                val host = hint.substringBeforeLast(':')
                val port = hint.substringAfterLast(':').toIntOrNull() ?: continue
                system.connect(host, port, CONNECT_MILLIS)?.let { return Found("", host, port, it) }
            }
        }
        return null
    }

    /** A, for a responder that left the addresses out of its answer. */
    private fun resolve(host: String): List<String> {
        val answers = system.multicastQuery(Mdns.query(listOf(Mdns.Question(host, Mdns.TYPE_A))), 500)
        return answers.mapNotNull { Mdns.parse(it.bytes) }.flatMap { it.records }
            .filterIsInstance<Mdns.Record.A>().filter { it.name.equals(host, ignoreCase = true) }.map { address(it.address) }
    }

    private fun address(bytes: ByteArray): String =
        if (bytes.size == 4) bytes.joinToString(".") { (it.toInt() and 0xff).toString() } else
            (0 until 8).joinToString(":") { i -> (((bytes[2 * i].toInt() and 0xff) shl 8) or (bytes[2 * i + 1].toInt() and 0xff)).toString(16) }

    // ---------------------------------------------------------------- pairing (01 §2)

    public sealed interface Paired {
        public class Ok(public val phone: Phone) : Paired
        public data object NotFound : Paired
        public data object Cancelled : Paired
        /** The handshake failed: the psk or the key was not what the phone expected. */
        public data object Refused : Paired
    }

    public fun pair(
        code: PairingCode,
        computer: NoiseKeyPair,
        deadline: Long,
        cancelled: () -> Boolean,
        keep: (Phone) -> Unit,
    ): Paired {
        val found = find(setOf(code.pairingInstance), emptyList(), deadline, cancelled)
            ?: return if (cancelled()) Paired.Cancelled else Paired.NotFound
        found.connection.use { c ->
            val wire = Wire(c, cancelled)
            c.write(LinkWire.preamble(LinkWire.Mode.Pair))
            val hs = NoiseHandshake.initiator(NoisePattern.XXpsk3, computer, psk = code.psk, prologue = LinkWire.prologue(LinkWire.Mode.Pair))
            c.write(LinkWire.frame(hs.write()))
            hs.read(wire.frame(MESSAGE_MILLIS) ?: return Paired.Refused) ?: return Paired.Refused
            c.write(LinkWire.frame(hs.write()))
            val transport = hs.split()
            transport.use { t ->
                val welcome = wire.message(t, MESSAGE_MILLIS) as? LinkMessage.Welcome ?: return Paired.Refused
                val phone = Phone(t.remoteStatic, welcome.phoneName.take(64), PairingCode.linkInstance(welcome.linkId), system.now(), "${found.address}:${found.port}")
                // Kept before PAIRED goes: a lost PAIRED leaves an extra record here, never a phone that thinks it is paired alone (01 §2).
                keep(phone)
                c.write(LinkWire.frame(t.seal(LinkMessage.encode(LinkMessage.Paired))))
                return Paired.Ok(phone)
            }
        }
    }

    // ---------------------------------------------------------------- a password (01 §3–§4)

    public sealed interface Answer {
        /** The login, the password in [SecretBytes] — closed by whoever passes it on. */
        public class Fill(public val username: String, public val password: SecretBytes, public val phone: Phone) : Answer
        public data class Done(val decision: String) : Answer
        /** [code]: `no-phone`, `phone-refused`, `bad-response`, `key`, or what the phone said (`clock`, `busy`, …). */
        public data class Failed(val code: String) : Answer
        public data object Cancelled : Answer
    }

    /**
     * Asks the first paired phone that answers for [origin]'s password. [state] hears `searching`,
     * `found` and `shown` as they happen; [key] opens the answer and is closed after.
     */
    public fun ask(
        phones: List<Phone>,
        computer: NoiseKeyPair,
        origin: String,
        browser: String,
        key: () -> PrivateKey?,
        cancelled: () -> Boolean,
        state: (String) -> Unit,
        seen: (Phone, String) -> Unit = { _, _ -> },
    ): Answer {
        if (phones.isEmpty()) return Answer.Failed("not-paired")
        state("searching")
        val byName = phones.associateBy { it.linkId.lowercase() }
        val found = find(byName.keys, phones.mapNotNull { it.lastAddress }, system.now() + FIND_MILLIS, cancelled)
            ?: return if (cancelled()) Answer.Cancelled else Answer.Failed("no-phone")
        state("found")
        found.connection.use { c ->
            val wire = Wire(c, cancelled)
            // Which phone this is: by its name, or — reached at a remembered address — by trying them in turn.
            val candidates = byName[found.instance.lowercase()]?.let { listOf(it) } ?: phones.filter { it.lastAddress == "${found.address}:${found.port}" }.ifEmpty { phones }
            val phone = candidates.first()
            c.write(LinkWire.preamble(LinkWire.Mode.Session))
            val hs = NoiseHandshake.initiator(NoisePattern.KK, computer, phone.key, prologue = LinkWire.prologue(LinkWire.Mode.Session))
            c.write(LinkWire.frame(hs.write()))
            val reply = wire.frame(MESSAGE_MILLIS) ?: return if (cancelled()) Answer.Cancelled else Answer.Failed("phone-refused")
            hs.read(reply) ?: return Answer.Failed("phone-refused")
            seen(phone, "${found.address}:${found.port}")
            return hs.split().use { t -> request(wire, c, t, phone, origin, browser, key, cancelled, state) }
        }
    }

    private fun request(
        wire: Wire,
        c: Connection,
        t: NoiseTransport,
        phone: Phone,
        origin: String,
        browser: String,
        key: () -> PrivateKey?,
        cancelled: () -> Boolean,
        state: (String) -> Unit,
    ): Answer {
        val id = requestId()
        val request = LinkMessage.encode(LinkMessage.PasswordRequest(id, origin, browser, system.now()))
        c.write(LinkWire.frame(t.seal(request)))
        val deadline = system.now() + ANSWER_MILLIS
        while (true) {
            val left = (deadline - system.now()).toInt()
            if (left <= 0) return Answer.Done(LinkMessage.TIMEOUT)
            val message = wire.message(t, left) ?: return when {
                cancelled() -> Answer.Cancelled
                system.now() >= deadline -> Answer.Done(LinkMessage.TIMEOUT)
                else -> Answer.Failed("bad-response")
            }
            when (message) {
                is LinkMessage.PasswordShown -> if (message.id.contentEquals(id)) state("shown")
                is LinkMessage.Error -> return Answer.Failed(message.code)
                is LinkMessage.PasswordResponse -> {
                    if (!message.id.contentEquals(id)) {
                        log("an answer not to this request, dropped")
                        continue
                    }
                    if (message.decision != LinkMessage.FILL) return Answer.Done(message.decision)
                    return open(message.box, request, origin, phone, key) ?: Answer.Failed("bad-response")
                }
                else -> log("unexpected message from the phone, dropped")
            }
        }
    }

    /** age → the box → the request and the site checked (README §4, D542); anything else is not an answer. */
    private fun open(box: ByteArray, request: ByteArray, origin: String, phone: Phone, key: () -> PrivateKey?): Answer? {
        val opened = (key() ?: return Answer.Failed("key")).use { privateKey ->
            val identity = Age.identity(privateKey) ?: return Answer.Failed("key")
            Age.decrypt(box, listOf(identity))
        }
        val plain = (opened as? Age.Opened.Ok)?.plaintext ?: run {
            log("the answer did not open with this computer's key")
            return null
        }
        try {
            val parsed = PasswordBox.open(plain) ?: return null
            if (parsed.request != PasswordBox.requestDigest(request) || parsed.origin != origin) {
                parsed.close()
                log("an answer not to this request, dropped")
                return null
            }
            return Answer.Fill(parsed.username, parsed.password, phone)
        } finally {
            plain.fill(0)
        }
    }

    /** Frames off a connection, in slices short enough to notice [cancelled]. */
    private class Wire(private val c: Connection, private val cancelled: () -> Boolean) {
        private val frames = FrameReader()
        private val buffer = ByteArray(8192)

        fun frame(timeoutMillis: Int): ByteArray? {
            var waited = 0
            while (true) {
                frames.next()?.let { return it }
                if (cancelled() || waited >= timeoutMillis) return null
                val slice = minOf(SLICE_MILLIS, timeoutMillis - waited)
                val n = c.read(buffer, slice)
                when {
                    n < 0 -> return null
                    n == 0 -> waited += slice
                    else -> frames.push(buffer, 0, n)
                }
            }
        }

        fun message(t: NoiseTransport, timeoutMillis: Int): LinkMessage? {
            val frame = frame(timeoutMillis) ?: return null
            val plain = t.open(frame) ?: return null
            return LinkMessage.decode(plain).also { plain.fill(0) }
        }
    }

    public companion object {
        /** README §4: the computer looks for the phone up to 60 s, the sheet lives 60 s. */
        public const val FIND_MILLIS: Long = 60_000
        public const val ANSWER_MILLIS: Long = 65_000
        public const val PAIR_MILLIS: Long = 5 * 60_000
        internal const val MESSAGE_MILLIS = 10_000
        internal const val ROUND_MILLIS = 1_000
        internal const val CONNECT_MILLIS = 1_500
        internal const val SLICE_MILLIS = 200
    }
}
