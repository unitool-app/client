package cx.m42.unitool.kit.ssh

import cx.m42.unitool.kit.crypto.ChaCha20Poly1305
import cx.m42.unitool.kit.crypto.Curve25519
import cx.m42.unitool.kit.crypto.Hash
import cx.m42.unitool.kit.crypto.Rsa
import cx.m42.unitool.kit.crypto.hkdfSha256
import cx.m42.unitool.kit.crypto.hmac
import cx.m42.unitool.kit.crypto.Sha512
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
 *
 * Moved here from `services/ssh` (passwords-browser D545) so the console tool's browser host opens
 * the phone's answer with the computer's key file. Decrypting here is synchronous ([AgeIdentity]);
 * the keyring, whose private step may wait for the chip's prompt, puts its own suspending loop on
 * top of [header], [ed25519FileKey], [rsaFileKey] and [openPayload].
 */
public object Age {

    private const val INTRO = "age-encryption.org/v1"
    public const val ED25519_LABEL: String = "age-encryption.org/v1/ssh-ed25519"
    public const val RSA_LABEL: String = "age-encryption.org/v1/ssh-rsa"
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

    public fun encrypt(plaintext: ByteArray, recipients: List<SshPublicKey>, random: (Int) -> ByteArray): ByteArray {
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
    public class Stanza internal constructor(public val type: String, public val args: List<String>, public val body: ByteArray)

    /** The recipients' key tags of [file] by stanza — what "which of my keys opens this" is answered from. */
    public fun stanzas(file: ByteArray): List<Stanza>? = header(file)?.stanzas

    /** The first four bytes of the key's SHA-256: lets a reader skip stanzas meant for other keys. */
    public fun tag(key: SshPublicKey): String = Base64Raw.encode(Hash.SHA256.digest(key.blob).copyOf(4))

    public sealed interface Opened {
        public class Ok(public val plaintext: ByteArray, public val identity: SshPublicKey) : Opened
        /** No stanza is for any of the identities offered. */
        public data object NoIdentity : Opened
        /** [code]: `header`, `unwrap`, `mac`, `payload` — what failed, never what was inside. */
        public class Failed(public val code: String) : Opened
    }

    /** age -d: the first identity a stanza is for unwraps the file key, then the MAC and every chunk are checked. */
    public fun decrypt(file: ByteArray, identities: List<AgeIdentity>): Opened {
        val header = header(file) ?: return Opened.Failed("header")
        for (stanza in header.stanzas) {
            for (identity in identities) {
                if (!identity.matches(stanza)) continue
                val fileKey = identity.unwrap(stanza) ?: return Opened.Failed("unwrap")
                return openPayload(header, fileKey, identity.publicKey)
            }
        }
        return Opened.NoIdentity
    }

    /** A file's header, parsed strictly, with what [openPayload] needs; null when it is not age. */
    public class Header internal constructor(
        public val stanzas: List<Stanza>,
        internal val macInput: ByteArray,
        internal val mac: ByteArray,
        internal val binary: ByteArray,
        internal val payloadStart: Int,
    )

    public fun header(file: ByteArray): Header? {
        val binary = dearmor(file) ?: return null
        return parseHeader(binary)
    }

    /** With the file key a stanza gave: the MAC, then every chunk. [fileKey] is wiped either way. */
    public fun openPayload(header: Header, fileKey: ByteArray, opener: SshPublicKey): Opened {
        try {
            if (fileKey.size != 16) return Opened.Failed("unwrap")
            val macKey = hkdfSha256(fileKey, ByteArray(0), "header".encodeToByteArray(), 32)
            val mac = hmac(Hash.SHA256, macKey, header.macInput)
            if (!constantTimeEquals(mac, header.mac)) return Opened.Failed("mac")
            val binary = header.binary
            if (binary.size - header.payloadStart < 16) return Opened.Failed("payload")
            val nonce = binary.copyOfRange(header.payloadStart, header.payloadStart + 16)
            val payloadKey = hkdfSha256(fileKey, nonce, "payload".encodeToByteArray(), 32)
            val plaintext = open(payloadKey, binary, header.payloadStart + 16)
            payloadKey.fill(0)
            return if (plaintext == null) Opened.Failed("payload") else Opened.Ok(plaintext, opener)
        } finally {
            fileKey.fill(0)
        }
    }

    /** Whether [stanza] is addressed to [key]: its type and the tag of the key's blob. */
    public fun matches(key: SshPublicKey, stanza: Stanza): Boolean = when (key.kind) {
        is SshPublicKey.Kind.Ed25519 -> stanza.type == "ssh-ed25519" && stanza.args.size == 2 && stanza.args[0] == tag(key)
        is SshPublicKey.Kind.Rsa -> stanza.type == "ssh-rsa" && stanza.args.size == 1 && stanza.args[0] == tag(key)
        else -> false
    }

    /** The ephemeral X25519 key of an `ssh-ed25519` stanza — what the private step agrees with. */
    public fun ed25519Ephemeral(stanza: Stanza): ByteArray? {
        val ephemeral = runCatching { Base64Raw.decode(stanza.args[1]) }.getOrNull()
        if (ephemeral == null || ephemeral.size != 32 || stanza.body.size != 32) return null
        return ephemeral
    }

    /**
     * age's `agessh` unwrapping after the private step: [shared] is X25519 of the key's scalar with
     * [ed25519Ephemeral]. The tweak, HKDF and ChaCha20-Poly1305 are here, once. [shared] is wiped.
     */
    public fun ed25519FileKey(key: SshPublicKey, stanza: Stanza, shared: ByteArray): ByteArray? {
        val point = (key.kind as? SshPublicKey.Kind.Ed25519)?.point ?: return null
        val ephemeral = ed25519Ephemeral(stanza) ?: return null
        if (shared.all { it == 0.toByte() }) return null
        val label = ED25519_LABEL.encodeToByteArray()
        val ours = Curve25519.edwardsToMontgomery(point)
        val tweak = hkdfSha256(ByteArray(0), key.blob, label, 32)
        val tweaked = Curve25519.x25519(tweak, shared)
        shared.fill(0)
        val wrappingKey = hkdfSha256(tweaked, ephemeral + ours, label, 32)
        tweaked.fill(0)
        return ChaCha20Poly1305.open(wrappingKey, ByteArray(12), stanza.body).also { wrappingKey.fill(0) }
    }

    /** The file key from an `ssh-rsa` stanza's OAEP block after the raw private operation; [em] is wiped. */
    public fun rsaFileKey(em: ByteArray): ByteArray? =
        Rsa.oaepDecode(em, RSA_LABEL.encodeToByteArray()).also { em.fill(0) }

    /**
     * An identity for a key held in memory — the console tool's key file (D545): Ed25519's X25519
     * scalar is the first half of SHA-512 of the seed, as `age -d -i ~/.ssh/id_ed25519` does; RSA is
     * the private operation. Valid while [key] is open. Null for a type age has no recipe for.
     */
    public fun identity(key: PrivateKey): AgeIdentity? = when (val k = key.publicKey.kind) {
        is SshPublicKey.Kind.Ed25519 -> StanzaUnwrapper(key.publicKey, agree = { ephemeral ->
            key.ed25519Seed().use { seed ->
                val h = Sha512.digest(seed.bytes)
                try {
                    Curve25519.x25519(h.copyOfRange(0, 32), ephemeral)
                } finally {
                    h.fill(0)
                }
            }
        })
        is SshPublicKey.Kind.Rsa -> StanzaUnwrapper(key.publicKey, rsaPrivate = { ciphertext ->
            key.rsaPrivateExponent().use { d -> runCatching { Rsa.private(k.modulus, d.bytes, ciphertext) }.getOrNull() }
        })
        else -> null
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
                return Header(stanzas, file.copyOfRange(0, lineStart + 3), mac, file, position)
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
 * Whatever can open an age stanza meant for [publicKey], synchronously: a key held in memory for the
 * length of one operation. The keyring's chip-backed identities are its own, and suspend.
 */
public interface AgeIdentity {
    public val publicKey: SshPublicKey

    public fun matches(stanza: Age.Stanza): Boolean = Age.matches(publicKey, stanza)

    /** The 16-byte file key, or null when the stanza does not open (tampered, or not really ours). */
    public fun unwrap(stanza: Age.Stanza): ByteArray?
}

/** [AgeIdentity] with the private step pluggable: [agree] is X25519 with the key's scalar, [rsaPrivate] the raw RSA operation. */
public class StanzaUnwrapper(
    override val publicKey: SshPublicKey,
    private val agree: ((ByteArray) -> ByteArray?)? = null,
    private val rsaPrivate: ((ByteArray) -> ByteArray?)? = null,
) : AgeIdentity {

    override fun unwrap(stanza: Age.Stanza): ByteArray? = when (publicKey.kind) {
        is SshPublicKey.Kind.Ed25519 -> Age.ed25519Ephemeral(stanza)?.let { ephemeral ->
            agree?.invoke(ephemeral)?.let { shared -> Age.ed25519FileKey(publicKey, stanza, shared) }
        }
        is SshPublicKey.Kind.Rsa -> rsaPrivate?.invoke(stanza.body)?.let(Age::rsaFileKey)
        else -> null
    }
}
