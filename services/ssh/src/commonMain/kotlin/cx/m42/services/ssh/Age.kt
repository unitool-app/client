package cx.m42.services.ssh

import cx.m42.unitool.kit.crypto.ChaCha20Poly1305
import cx.m42.unitool.kit.crypto.Curve25519
import cx.m42.unitool.kit.crypto.Hash
import cx.m42.unitool.kit.crypto.Rsa
import cx.m42.unitool.kit.crypto.hkdfSha256
import cx.m42.unitool.kit.crypto.hmac
import cx.m42.unitool.kit.crypto.secureRandomBytes
import kotlin.io.encoding.Base64

/**
 * age v1 (age-encryption.org/v1) for `ssh-ed25519` and `ssh-rsa` keys — both directions now.
 *
 * Encrypting is a pure function of public keys (02 §4, D150): the 2FA export and the host's backup
 * go to `github.com/<login>.keys` with nothing else, on every platform. Decrypting needs a private
 * key, and the keyring holds those; [decrypt] takes [AgeIdentity]s, so the private operation itself
 * happens wherever the key lives — in this module for levels 2–3, in the Keystore for level 1 (P2,
 * P4).
 *
 * The stanza recipes are age's `agessh` package, step for step. `desktopTest` opens what this writes
 * with an independent JDK reader and with the real `age`, and opens what the real `age` writes.
 */
public object Age {

    private const val INTRO = "age-encryption.org/v1"
    internal const val ED25519_LABEL = "age-encryption.org/v1/ssh-ed25519"
    internal const val RSA_LABEL = "age-encryption.org/v1/ssh-rsa"
    private const val CHUNK = 64 * 1024
    private const val ARMOR_BEGIN = "-----BEGIN AGE ENCRYPTED FILE-----"
    private const val ARMOR_END = "-----END AGE ENCRYPTED FILE-----"

    /** age refuses anything shorter, and a file it cannot open is worse than no file. */
    public const val MIN_RSA_BITS: Int = 2048

    /** Whether age can encrypt to [key] at all: Ed25519, and RSA of at least 2048 bits. ECDSA never. */
    public fun supports(key: SshPublicKey): Boolean = when (val k = key.kind) {
        is SshPublicKey.Kind.Ed25519 -> true
        is SshPublicKey.Kind.Rsa -> k.bits >= MIN_RSA_BITS
        else -> false
    }

    /** The binary file; any one of [recipients] opens it. Throws when none of them is [supports]ed. */
    public fun encrypt(plaintext: ByteArray, recipients: List<SshPublicKey>): ByteArray =
        encrypt(plaintext, recipients, ::secureRandomBytes)

    internal fun encrypt(plaintext: ByteArray, recipients: List<SshPublicKey>, random: (Int) -> ByteArray): ByteArray {
        val usable = recipients.filter(::supports)
        require(usable.isNotEmpty()) { "no recipient age can encrypt to" }
        val fileKey = random(16)
        try {
            val header = buildString {
                append(INTRO).append('\n')
                usable.forEach { append(stanza(it, fileKey, random)) }
                append("---")
            }
            val macKey = hkdfSha256(fileKey, ByteArray(0), "header".encodeToByteArray(), 32)
            val mac = hmac(Hash.SHA256, macKey, header.encodeToByteArray())
            val nonce = random(16)
            val payloadKey = hkdfSha256(fileKey, nonce, "payload".encodeToByteArray(), 32)
            return (header + " " + Base64Raw.encode(mac) + "\n").encodeToByteArray() + nonce + seal(payloadKey, plaintext)
        } finally {
            fileKey.fill(0)
        }
    }

    /**
     * age's ASCII armor: PEM, standard Base64, 64 columns. `age -d` recognises it by itself, and
     * text survives every channel a share sheet leads to.
     */
    public fun armor(file: ByteArray): String = buildString {
        append(ARMOR_BEGIN).append('\n')
        Base64.Default.encode(file).chunked(64).forEach { append(it).append('\n') }
        append(ARMOR_END).append('\n')
    }

    /** The binary form of [file]: itself, or what its armor holds. Null when armored but broken. */
    public fun dearmor(file: ByteArray): ByteArray? {
        val start = file.indexOfFirst { it != ' '.code.toByte() && it != '\n'.code.toByte() && it != '\r'.code.toByte() && it != '\t'.code.toByte() }
        if (start < 0) return null
        val prefix = ARMOR_BEGIN.encodeToByteArray()
        if (file.size - start < prefix.size || !file.copyOfRange(start, start + prefix.size).contentEquals(prefix)) return file
        val text = file.decodeToString()
        val end = text.indexOf(ARMOR_END)
        if (end < 0) return null
        val body = text.substring(text.indexOf(ARMOR_BEGIN) + ARMOR_BEGIN.length, end).filterNot { it.isWhitespace() }
        return runCatching { Base64.Default.decode(body) }.getOrNull()
    }

    /** One `-> type args…` stanza and its body. */
    public class Stanza internal constructor(public val type: String, public val args: List<String>, internal val body: ByteArray)

    /** The recipients' key tags of [file] by stanza — what "which of my keys opens this" is answered from. */
    public fun stanzas(file: ByteArray): List<Stanza>? = parseHeader(dearmor(file) ?: return null)?.stanzas

    /** The first four bytes of the key's SHA-256: lets a reader skip stanzas meant for other keys. */
    internal fun tag(key: SshPublicKey): String = Base64Raw.encode(Hash.SHA256.digest(key.blob).copyOf(4))

    internal sealed interface Opened {
        class Ok(val plaintext: ByteArray, val identity: SshPublicKey) : Opened
        /** No stanza is for any of the identities offered. */
        data object NoIdentity : Opened
        /** [code]: `header`, `unwrap`, `mac`, `payload` — what failed, never what was inside. */
        class Failed(val code: String) : Opened
    }

    /** age -d: the first identity a stanza is for unwraps the file key, then the MAC and every chunk are checked. */
    internal suspend fun decrypt(file: ByteArray, identities: List<AgeIdentity>): Opened {
        val binary = dearmor(file) ?: return Opened.Failed("header")
        val header = parseHeader(binary) ?: return Opened.Failed("header")
        var fileKey: ByteArray? = null
        var opener: SshPublicKey? = null
        loop@ for (stanza in header.stanzas) {
            for (identity in identities) {
                if (!identity.matches(stanza)) continue
                fileKey = identity.unwrap(stanza) ?: return Opened.Failed("unwrap")
                opener = identity.publicKey
                break@loop
            }
        }
        val key = fileKey ?: return Opened.NoIdentity
        try {
            if (key.size != 16) return Opened.Failed("unwrap")
            val macKey = hkdfSha256(key, ByteArray(0), "header".encodeToByteArray(), 32)
            val mac = hmac(Hash.SHA256, macKey, header.macInput)
            if (!constantTimeEquals(mac, header.mac)) return Opened.Failed("mac")
            if (binary.size - header.payloadStart < 16) return Opened.Failed("payload")
            val nonce = binary.copyOfRange(header.payloadStart, header.payloadStart + 16)
            val payloadKey = hkdfSha256(key, nonce, "payload".encodeToByteArray(), 32)
            val plaintext = open(payloadKey, binary, header.payloadStart + 16) ?: return Opened.Failed("payload")
            payloadKey.fill(0)
            return Opened.Ok(plaintext, opener!!)
        } finally {
            key.fill(0)
        }
    }

    // ------------------------------------------------------------ writing

    private fun stanza(key: SshPublicKey, fileKey: ByteArray, random: (Int) -> ByteArray): String = when (val k = key.kind) {
        is SshPublicKey.Kind.Ed25519 -> {
            val label = ED25519_LABEL.encodeToByteArray()
            val theirs = Curve25519.edwardsToMontgomery(k.point)
            val ephemeral = random(32)
            val ours = Curve25519.x25519(ephemeral, Curve25519.BASE)
            val shared = Curve25519.x25519(ephemeral, theirs)
            ephemeral.fill(0)
            check(shared.any { it != 0.toByte() }) { "low-order key ${key.fingerprint}" }
            // The tweak binds the secret to this exact SSH key, not merely to its curve point.
            val tweak = hkdfSha256(ByteArray(0), key.blob, label, 32)
            val tweaked = Curve25519.x25519(tweak, shared)
            val wrappingKey = hkdfSha256(tweaked, ours + theirs, label, 32)
            stanzaText("ssh-ed25519", listOf(tag(key), Base64Raw.encode(ours)), ChaCha20Poly1305.seal(wrappingKey, ByteArray(12), fileKey))
        }
        is SshPublicKey.Kind.Rsa -> stanzaText(
            "ssh-rsa",
            listOf(tag(key)),
            Rsa.oaepEncrypt(k.modulus, k.exponent, fileKey, RSA_LABEL.encodeToByteArray(), random(32)),
        )
        else -> throw IllegalArgumentException("age cannot encrypt to ${key.type}")
    }

    /** `-> type args…`, then the body in 64-column lines, the last always shorter — empty if need be. */
    private fun stanzaText(type: String, args: List<String>, body: ByteArray): String = buildString {
        append("-> ").append(type)
        args.forEach { append(' ').append(it) }
        append('\n')
        val encoded = Base64Raw.encode(body)
        var start = 0
        while (true) {
            val end = minOf(start + 64, encoded.length)
            append(encoded.substring(start, end)).append('\n')
            if (end - start < 64) break
            start = end
        }
    }

    /** STREAM: nonce = 11-byte big-endian chunk counter ‖ 1 on the last chunk, 0 before it. */
    private fun seal(key: ByteArray, plaintext: ByteArray): ByteArray {
        val chunks = ArrayList<ByteArray>()
        var offset = 0
        var counter = 0L
        do {
            val end = minOf(offset + CHUNK, plaintext.size)
            val last = end == plaintext.size
            chunks += ChaCha20Poly1305.seal(key, nonce(counter, last), plaintext.copyOfRange(offset, end))
            offset = end
            counter++
        } while (!last)
        val out = ByteArray(chunks.sumOf { it.size })
        var position = 0
        chunks.forEach { it.copyInto(out, position); position += it.size }
        return out
    }

    // ------------------------------------------------------------ reading

    private class Header(val stanzas: List<Stanza>, val macInput: ByteArray, val mac: ByteArray, val payloadStart: Int)

    /** The header up to the MAC line, strictly: anything unexpected is not a file this reads. */
    private fun parseHeader(file: ByteArray): Header? {
        var position = 0
        fun line(): String? {
            var end = position
            while (end < file.size && file[end] != '\n'.code.toByte()) end++
            if (end >= file.size) return null
            val text = runCatching { file.decodeToString(position, end, throwOnInvalidSequence = true) }.getOrNull() ?: return null
            position = end + 1
            return text
        }
        if (line() != INTRO) return null
        val stanzas = mutableListOf<Stanza>()
        while (true) {
            val lineStart = position
            val text = line() ?: return null
            if (text.startsWith("--- ")) {
                val mac = runCatching { Base64Raw.decode(text.substring(4)) }.getOrNull() ?: return null
                if (mac.size != 32) return null
                // The MAC covers the header through "---", not the space or the MAC after it.
                return Header(stanzas, file.copyOfRange(0, lineStart + 3), mac, position)
            }
            if (!text.startsWith("-> ")) return null
            val parts = text.substring(3).split(' ')
            if (parts.isEmpty() || parts[0].isEmpty()) return null
            val body = StringBuilder()
            while (true) {
                val chunk = line() ?: return null
                if (chunk.length > 64) return null
                body.append(chunk)
                if (chunk.length < 64) break
            }
            val decoded = runCatching { Base64Raw.decode(body.toString()) }.getOrNull() ?: return null
            stanzas += Stanza(parts[0], parts.drop(1), decoded)
        }
    }

    private fun open(key: ByteArray, file: ByteArray, from: Int): ByteArray? {
        val out = ArrayList<ByteArray>()
        var offset = from
        var counter = 0L
        val sealedChunk = CHUNK + ChaCha20Poly1305.TAG_SIZE
        while (true) {
            val remaining = file.size - offset
            if (remaining < ChaCha20Poly1305.TAG_SIZE) return null
            val size = minOf(sealedChunk, remaining)
            val last = size == remaining
            // An empty chunk may only be the whole payload; a full last chunk is fine.
            if (last && size == ChaCha20Poly1305.TAG_SIZE && counter > 0) return null
            val plain = ChaCha20Poly1305.open(key, nonce(counter, last), file.copyOfRange(offset, offset + size)) ?: return null
            out += plain
            offset += size
            counter++
            if (last) break
        }
        val result = ByteArray(out.sumOf { it.size })
        var position = 0
        out.forEach { it.copyInto(result, position); position += it.size; it.fill(0) }
        return result
    }

    private fun nonce(counter: Long, last: Boolean): ByteArray {
        val nonce = ByteArray(12)
        for (i in 0 until 8) nonce[10 - i] = (counter ushr (8 * i)).toByte()
        nonce[11] = if (last) 1 else 0
        return nonce
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}

/**
 * Whatever can open an age stanza meant for [publicKey]: a key the module holds for the length of
 * one operation, or a key in the Keystore that performs the private step itself.
 */
internal interface AgeIdentity {
    val publicKey: SshPublicKey

    fun matches(stanza: Age.Stanza): Boolean = when (publicKey.kind) {
        is SshPublicKey.Kind.Ed25519 -> stanza.type == "ssh-ed25519" && stanza.args.size == 2 && stanza.args[0] == Age.tag(publicKey)
        is SshPublicKey.Kind.Rsa -> stanza.type == "ssh-rsa" && stanza.args.size == 1 && stanza.args[0] == Age.tag(publicKey)
        else -> false
    }

    /**
     * The 16-byte file key, or null when the stanza does not open (tampered, or not really ours).
     * Suspends because the private step may be the chip's, behind the device's prompt.
     */
    suspend fun unwrap(stanza: Age.Stanza): ByteArray?
}

/**
 * age's `agessh` unwrapping with the private step pluggable: [agree] is X25519 with the key's scalar
 * (for Ed25519), [rsaPrivate] the raw RSA operation (for RSA). Everything around them — the tweak,
 * HKDF, ChaCha20-Poly1305, OAEP — is here, once, for every backend.
 */
internal class StanzaUnwrapper(
    override val publicKey: SshPublicKey,
    private val agree: (suspend (ByteArray) -> ByteArray?)? = null,
    private val rsaPrivate: (suspend (ByteArray) -> ByteArray?)? = null,
) : AgeIdentity {

    override suspend fun unwrap(stanza: Age.Stanza): ByteArray? = when (val k = publicKey.kind) {
        is SshPublicKey.Kind.Ed25519 -> {
            val ephemeral = runCatching { Base64Raw.decode(stanza.args[1]) }.getOrNull()
            if (ephemeral == null || ephemeral.size != 32 || stanza.body.size != 32) {
                null
            } else {
                val label = Age.ED25519_LABEL.encodeToByteArray()
                val ours = Curve25519.edwardsToMontgomery(k.point)
                val shared = agree?.invoke(ephemeral)
                if (shared == null || shared.all { it == 0.toByte() }) {
                    null
                } else {
                    val tweak = hkdfSha256(ByteArray(0), publicKey.blob, label, 32)
                    val tweaked = Curve25519.x25519(tweak, shared)
                    shared.fill(0)
                    val wrappingKey = hkdfSha256(tweaked, ephemeral + ours, label, 32)
                    tweaked.fill(0)
                    ChaCha20Poly1305.open(wrappingKey, ByteArray(12), stanza.body).also { wrappingKey.fill(0) }
                }
            }
        }
        is SshPublicKey.Kind.Rsa -> {
            val em = rsaPrivate?.invoke(stanza.body)
            em?.let { Rsa.oaepDecode(it, Age.RSA_LABEL.encodeToByteArray()).also { _ -> it.fill(0) } }
        }
        else -> null
    }
}
