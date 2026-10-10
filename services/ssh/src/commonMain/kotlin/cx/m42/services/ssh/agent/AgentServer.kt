package cx.m42.services.ssh.agent

import cx.m42.services.ssh.SshPublicKey
import cx.m42.services.ssh.key.PrivateKey
import cx.m42.services.ssh.wire.WireReader
import cx.m42.services.ssh.wire.WireWriter
import cx.m42.superizer.ssh.AgentConstraint
import cx.m42.superizer.ssh.ImportOutcome
import cx.m42.superizer.ssh.SshAgent
import cx.m42.superizer.ssh.SshOutcome
import cx.m42.unitool.kit.crypto.SecretBytes

/**
 * The ssh-agent protocol's bytes (draft-ietf-sshm-ssh-agent), with no transport: a message in, a
 * message out. A UNIX socket in the tests (so the real `ssh-add` can talk to it, 03 §4), a future
 * client or bridge later (02 §2). Framing — a `uint32` length before each message — is [serve]'s.
 */
internal class AgentServer(private val agent: SshAgent) {

    /** One request message (type byte and body) to one response message. */
    suspend fun handle(message: ByteArray): ByteArray {
        if (message.isEmpty()) return FAILURE
        val reader = WireReader(message, 1)
        return when (message[0].toInt() and 0xff) {
            REQUEST_IDENTITIES -> {
                val ids = agent.identities()
                WireWriter().byte(IDENTITIES_ANSWER).uint32(ids.size.toLong()).apply {
                    ids.forEach { string(it.publicBlob); string(it.comment) }
                }.toByteArray()
            }
            SIGN_REQUEST -> {
                val blob = reader.string() ?: return FAILURE
                val data = reader.string() ?: return FAILURE
                val flags = reader.uint32()?.toInt() ?: return FAILURE
                when (val r = agent.sign(blob, data, flags)) {
                    is SshOutcome.Ok -> WireWriter().byte(SIGN_RESPONSE).string(r.value).toByteArray()
                    else -> FAILURE
                }
            }
            ADD_IDENTITY, ADD_ID_CONSTRAINED -> {
                val key = message.copyOfRange(1, message.size)
                try {
                    val end = AgentCodec.keyEnd(key) ?: return FAILURE
                    val constraints = if (message[0].toInt() == ADD_ID_CONSTRAINED) AgentCodec.constraints(key, end) ?: return FAILURE else emptySet()
                    val body = key.copyOfRange(0, end)
                    if (agent.add(body, null, constraints) is ImportOutcome.Imported) SUCCESS else FAILURE
                } finally {
                    key.fill(0)
                }
            }
            REMOVE_IDENTITY -> {
                val blob = reader.string() ?: return FAILURE
                if (agent.remove(blob) is SshOutcome.Ok) SUCCESS else FAILURE
            }
            REMOVE_ALL_IDENTITIES -> if (agent.removeAll() is SshOutcome.Ok) SUCCESS else FAILURE
            LOCK, UNLOCK -> {
                val bytes = reader.string() ?: return FAILURE
                val chars = AgentCodec.chars(bytes)
                bytes.fill(0)
                val ok = if (message[0].toInt() == LOCK) agent.lock(chars) else agent.unlock(chars)
                if (ok) SUCCESS else FAILURE
            }
            EXTENSION -> {
                val name = reader.text() ?: return FAILURE
                val body = reader.raw(reader.remaining) ?: ByteArray(0)
                when (val r = agent.extension(name, body)) {
                    is SshOutcome.Ok -> if (r.value.isEmpty()) SUCCESS else byteArrayOf(SUCCESS_CODE.toByte()) + r.value
                    else -> byteArrayOf(EXTENSION_FAILURE.toByte())
                }
            }
            else -> FAILURE // smartcards (20, 21, 26) and anything newer
        }
    }

    /** Length-framed messages until [read] runs dry. */
    suspend fun serve(read: suspend (Int) -> ByteArray?, write: suspend (ByteArray) -> Unit) {
        while (true) {
            val header = read(4) ?: return
            val length = ((header[0].toInt() and 0xff) shl 24) or ((header[1].toInt() and 0xff) shl 16) or
                ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
            if (length <= 0 || length > MAX_MESSAGE) return
            val message = read(length) ?: return
            val response = try {
                handle(message)
            } finally {
                message.fill(0)
            }
            write(WireWriter().uint32(response.size.toLong()).raw(response).toByteArray())
        }
    }

    companion object {
        const val FAILURE_CODE = 5
        const val SUCCESS_CODE = 6
        const val REQUEST_IDENTITIES = 11
        const val IDENTITIES_ANSWER = 12
        const val SIGN_REQUEST = 13
        const val SIGN_RESPONSE = 14
        const val ADD_IDENTITY = 17
        const val REMOVE_IDENTITY = 18
        const val REMOVE_ALL_IDENTITIES = 19
        const val LOCK = 22
        const val UNLOCK = 23
        const val ADD_ID_CONSTRAINED = 25
        const val EXTENSION = 27
        const val EXTENSION_FAILURE = 28
        const val MAX_MESSAGE = 256 * 1024

        private val FAILURE get() = byteArrayOf(FAILURE_CODE.toByte())
        private val SUCCESS get() = byteArrayOf(SUCCESS_CODE.toByte())
    }
}

/** The agent's private-key encoding — the same fields `openssh-key-v1` carries after the type (02 §2). */
internal object AgentCodec {

    /** Where the key ends in an `ADD_IDENTITY` body: after the comment; constraints may follow. */
    fun keyEnd(body: ByteArray): Int? {
        val reader = WireReader(body)
        val type = reader.text() ?: return null
        val strings = when {
            type == SshPublicKey.ED25519 -> 2
            type == SshPublicKey.RSA -> 6
            type.startsWith("ecdsa-sha2-") -> 3
            else -> return null
        }
        repeat(strings) { reader.string()?.fill(0) ?: return null }
        reader.string() ?: return null // comment
        return reader.position
    }

    /** A key from an `ADD_IDENTITY` body (type, fields, comment), or null. The caller zeroes [body]. */
    fun readKey(body: ByteArray): PrivateKey? {
        val reader = WireReader(body)
        val type = reader.text() ?: return null
        val fieldsStart = reader.position
        val public: SshPublicKey = when {
            type == SshPublicKey.ED25519 -> {
                val pub = reader.string() ?: return null
                val priv = reader.string() ?: return null
                val ok = pub.size == 32 && priv.size == 64 && priv.copyOfRange(32, 64).contentEquals(pub)
                priv.fill(0)
                if (!ok) return null
                SshPublicKey.fromBlob(WireWriter().string(type).string(pub).toByteArray()) ?: return null
            }
            type == SshPublicKey.RSA -> {
                val n = reader.mpint() ?: return null
                val e = reader.mpint() ?: return null
                repeat(4) { reader.mpint()?.fill(0) ?: return null }
                SshPublicKey.fromBlob(WireWriter().string(type).string(e).string(n).toByteArray()) ?: return null
            }
            type.startsWith("ecdsa-sha2-") -> {
                val curve = reader.text() ?: return null
                val q = reader.string() ?: return null
                reader.mpint()?.fill(0) ?: return null
                SshPublicKey.fromBlob(WireWriter().string(type).string(curve).string(q).toByteArray()) ?: return null
            }
            else -> return null
        }
        val fields = body.copyOfRange(fieldsStart, reader.position)
        val comment = reader.text() ?: run { fields.fill(0); return null }
        return PrivateKey(public, comment, SecretBytes(fields))
    }

    /** `ssh-add -t`, `-c`, `-h`: what follows the key in `ADD_ID_CONSTRAINED`. */
    fun constraints(body: ByteArray, from: Int): Set<AgentConstraint>? {
        val reader = WireReader(body, from)
        val out = mutableSetOf<AgentConstraint>()
        while (!reader.done) {
            when (reader.byte()) {
                1 -> out += AgentConstraint.Lifetime(reader.uint32() ?: return null)
                2 -> out += AgentConstraint.Confirm
                255 -> {
                    val name = reader.text() ?: return null
                    // Every extension OpenSSH sends carries one string after its name.
                    out += AgentConstraint.Extension(name, reader.string() ?: return null)
                }
                else -> return null
            }
        }
        return out
    }

    /** UTF-8 to a `CharArray` without passing through a `String`, which could not be wiped. */
    fun chars(bytes: ByteArray): CharArray {
        val out = CharArray(bytes.size)
        var n = 0
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xff
            val (code, width) = when {
                b < 0x80 -> b to 1
                b < 0xE0 && i + 1 < bytes.size -> (((b and 0x1F) shl 6) or (bytes[i + 1].toInt() and 0x3F)) to 2
                b < 0xF0 && i + 2 < bytes.size -> (((b and 0x0F) shl 12) or ((bytes[i + 1].toInt() and 0x3F) shl 6) or (bytes[i + 2].toInt() and 0x3F)) to 3
                i + 3 < bytes.size -> (((b and 0x07) shl 18) or ((bytes[i + 1].toInt() and 0x3F) shl 12) or ((bytes[i + 2].toInt() and 0x3F) shl 6) or (bytes[i + 3].toInt() and 0x3F)) to 4
                else -> 0xFFFD to 1
            }
            if (code >= 0x10000) {
                val v = code - 0x10000
                out[n++] = (0xD800 + (v shr 10)).toChar()
                out[n++] = (0xDC00 + (v and 0x3FF)).toChar()
            } else {
                out[n++] = code.toChar()
            }
            i += width
        }
        val result = out.copyOf(n)
        out.fill('\u0000')
        return result
    }

    fun nameList(names: List<String>): ByteArray = WireWriter().apply { names.forEach { string(it) } }.toByteArray()
}
