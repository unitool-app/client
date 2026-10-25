package cx.m42.unitool.cli.browser

import cx.m42.unitool.cli.CommandFailure
import cx.m42.unitool.cli.Io
import cx.m42.unitool.kit.crypto.secureRandomBytes
import cx.m42.unitool.kit.json.FlatJson
import cx.m42.unitool.kit.json.JsonWriter
import cx.m42.unitool.kit.link.NoiseKeyPair
import cx.m42.unitool.kit.link.PairingCode
import cx.m42.unitool.kit.qr.ErrorCorrection
import cx.m42.unitool.kit.qr.Qr
import cx.m42.unitool.kit.qr.QrImage
import cx.m42.unitool.kit.ssh.SshPublicKey

/** Everything pairing needs before the QR goes up (README §3): the link key, the SSH key, the code. */
public class PairingStart(public val code: PairingCode, public val computer: NoiseKeyPair, public val sshKey: SshPublicKey, public val sshPath: String)

public object Pairing {
    /** Throws [CommandFailure] with the reason when the SSH key will not do (D545) — before any QR. */
    public fun start(io: Io, system: BrowserSystem, store: LinkStore, keyPath: String?, name: String?): PairingStart {
        val chosen = when (val r = SshKeyChoice.choose(io, store, keyPath)) {
            is SshKeyChoice.Result.Refused -> throw CommandFailure(r.message)
            is SshKeyChoice.Result.Ok -> r
        }
        val computer = store.key(create = true)!!
        val label = (name ?: system.hostname()).trim().ifEmpty { "computer" }.take(PairingCode.MAX_NAME)
        val code = PairingCode(computer.public, secureRandomBytes(32), label, chosen.publicKey.blob, system.localAddresses())
        return PairingStart(code, computer, chosen.publicKey, chosen.path)
    }

    /** Keeps [phone], replacing an earlier pairing with the same phone, and the SSH key that was paired. */
    public fun keep(store: LinkStore, start: PairingStart, phone: Phone) {
        store.savePhones(store.phones().filterNot { it.key.contentEquals(phone.key) } + phone)
        store.saveSshKeyPath(start.sshPath)
    }
}

/**
 * `unitool browser host`: what the browser starts for the extension (README §6, 01 §5). Messages are
 * 4 bytes of length in the machine's order (little-endian here) and JSON; one operation at a time,
 * and any message that arrives during one — `cancel`, or the port closing — ends it.
 *
 * The password exists in clear in this process only between the phone's answer and the write to the
 * browser, in bytes, wiped after (D535). Nothing is written to disk but the link key and the phones.
 */
public class NativeHost(private val io: Io, private val system: BrowserSystem) {
    private val store = LinkStore(io, system)
    private val client = PhoneClient(system) { io.say("unitool browser host: $it") }
    private val stdio = system.stdio
    private var pending: Map<String, String>? = null

    public fun run(): Int {
        while (true) {
            val message = next() ?: return 0
            try {
                handle(message)
            } catch (e: CommandFailure) {
                error("internal", e.message)
            } catch (e: Exception) {
                io.say("unitool browser host: ${e::class.simpleName}")
                error("internal", null)
            }
        }
    }

    private fun next(): Map<String, String>? {
        pending?.let { pending = null; return it }
        return read()
    }

    private fun handle(m: Map<String, String>) {
        when (m["type"]) {
            "status" -> status()
            "fill" -> fill(m["origin"].orEmpty(), m["browser"].orEmpty())
            "pair" -> pair(m["name"])
            "devices" -> devices()
            "forget" -> forget(m["id"].orEmpty())
            "cancel" -> send { name("type").string("done").name("decision").string("cancelled") }
            else -> error("unknown", null)
        }
    }

    /** True once a message (or the end) has come during an operation: it ends the operation. */
    private var interrupted = false

    private fun cancelled(): Boolean {
        if (interrupted) return true
        if (!stdio.readable(0)) return false
        interrupted = true
        val m = read()
        if (m != null && m["type"] != "cancel") pending = m
        return true
    }

    private fun status() {
        val phones = store.phones()
        val key = store.sshKeyPath()
        send {
            name("type").string("status")
            name("paired").bool(phones.isNotEmpty())
            name("phones").number(phones.size.toLong())
            key?.let { name("key").string(it) }
        }
    }

    private fun fill(origin: String, browser: String) {
        interrupted = false
        if (!origin.startsWith("https://") && !origin.startsWith("http://")) return error("origin", null)
        val computer = store.key(create = false) ?: return error("not-paired", null)
        val phones = store.phones()
        if (phones.isEmpty()) return error("not-paired", null)
        val keyPath = store.sshKeyPath() ?: return error("key", null)
        when (val check = SshKeyChoice.check(io, keyPath)) {
            is SshKeyChoice.Result.Refused -> return error("key", check.message)
            is SshKeyChoice.Result.Ok -> Unit
        }
        val answer = client.ask(
            phones = phones,
            computer = computer,
            origin = origin,
            browser = browser.take(80),
            key = { SshKeyChoice.open(io, keyPath) },
            cancelled = ::cancelled,
            state = { s -> send { name("type").string("state").name("state").string(s) } },
            seen = { phone, address -> remember(phone, address) },
        )
        when (answer) {
            is PhoneClient.Answer.Fill -> answer.password.use { password ->
                val w = JsonWriter().beginObject()
                    .name("type").string("fill")
                    .name("username").string(answer.username)
                    .name("password").string(password.bytes)
                    .name("phone").string(answer.phone.name)
                    .endObject()
                val bytes = w.toByteArray()
                w.wipe()
                try {
                    write(bytes)
                } finally {
                    bytes.fill(0)
                }
            }
            is PhoneClient.Answer.Done -> send { name("type").string("done").name("decision").string(answer.decision) }
            is PhoneClient.Answer.Failed -> error(answer.code, null)
            PhoneClient.Answer.Cancelled -> send { name("type").string("done").name("decision").string("cancelled") }
        }
    }

    private fun remember(phone: Phone, address: String) {
        if (phone.lastAddress == address) return
        val all = store.phones()
        store.savePhones(all.map { if (it.key.contentEquals(phone.key)) Phone(it.key, it.name, it.linkId, it.pairedAt, address) else it })
    }

    private fun pair(name: String?) {
        interrupted = false
        val start = try {
            Pairing.start(io, system, store, null, name)
        } catch (e: CommandFailure) {
            return error("key", e.message)
        }
        val uri = start.code.format()
        val qr = Qr.encode(uri, ErrorCorrection.L) ?: return error("internal", "the code does not fit a QR")
        val expires = system.now() + PhoneClient.PAIR_MILLIS
        send {
            name("type").string("pair")
            name("uri").string(uri)
            name("svg").string(QrImage.svg(qr, margin = 4, scale = 4))
            name("fingerprint").string(start.sshKey.fingerprint)
            name("computer").string(start.code.name)
            name("expires").number(expires)
        }
        when (val r = client.pair(start.code, start.computer, expires, ::cancelled) { Pairing.keep(store, start, it) }) {
            is PhoneClient.Paired.Ok -> send { name("type").string("paired").name("phone").string(r.phone.name) }
            PhoneClient.Paired.NotFound -> error("no-phone", null)
            PhoneClient.Paired.Refused -> error("phone-refused", null)
            PhoneClient.Paired.Cancelled -> send { name("type").string("done").name("decision").string("cancelled") }
        }
        start.code.psk.fill(0)
    }

    private fun devices() {
        val phones = store.phones()
        val path = store.sshKeyPath()
        val fingerprint = path?.let { (SshKeyChoice.check(io, it) as? SshKeyChoice.Result.Ok)?.publicKey?.fingerprint }
        send {
            name("type").string("devices")
            name("phones").beginArray()
            phones.forEach { p ->
                beginObject().name("id").string(p.id).name("name").string(p.name).name("pairedAt").number(p.pairedAt).endObject()
            }
            endArray()
            if (path != null) {
                name("key").beginObject().name("path").string(path)
                fingerprint?.let { name("fingerprint").string(it) }
                endObject()
            }
        }
    }

    private fun forget(id: String) {
        val phones = store.phones()
        store.savePhones(phones.filterNot { it.id == id })
        devices()
    }

    private fun error(code: String, message: String?) {
        send {
            name("type").string("error").name("code").string(code)
            message?.let { name("message").string(it) }
        }
    }

    // ------------------------------------------------------------ native messaging framing

    private fun send(fields: JsonWriter.() -> Unit) {
        val w = JsonWriter().beginObject()
        w.fields()
        w.endObject()
        write(w.toByteArray())
        w.wipe()
    }

    private fun write(json: ByteArray) {
        val n = json.size
        stdio.write(byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte()) + json)
    }

    /** The next message, or null at the end or on anything that is not one (the browser is not talking to us any more). */
    private fun read(): Map<String, String>? {
        val header = readExactly(4) ?: return null
        val n = (header[0].toInt() and 0xff) or ((header[1].toInt() and 0xff) shl 8) or ((header[2].toInt() and 0xff) shl 16) or ((header[3].toInt() and 0xff) shl 24)
        if (n < 0 || n > MAX_INPUT) return null
        val body = readExactly(n) ?: return null
        return FlatJson.parseText(body).also { body.fill(0) }
    }

    /** Exactly [n] bytes, never more: the next message's bytes stay in the pipe for the next read. */
    private fun readExactly(n: Int): ByteArray? {
        val out = ByteArray(n)
        var have = 0
        while (have < n) {
            val chunk = ByteArray(minOf(4096, n - have))
            val got = stdio.read(chunk)
            if (got <= 0) return null
            chunk.copyInto(out, have, 0, got)
            chunk.fill(0)
            have += got
        }
        return out
    }

    public companion object {
        /** The extension sends a few hundred bytes; anything far larger is not ours. */
        public const val MAX_INPUT: Int = 64 * 1024
    }
}
