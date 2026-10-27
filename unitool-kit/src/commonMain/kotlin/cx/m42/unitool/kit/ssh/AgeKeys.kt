package cx.m42.unitool.kit.ssh

import cx.m42.unitool.kit.crypto.Bech32
import cx.m42.unitool.kit.crypto.ChaCha20Poly1305
import cx.m42.unitool.kit.crypto.Curve25519
import cx.m42.unitool.kit.crypto.hkdfSha256
import cx.m42.unitool.kit.crypto.scrypt
import cx.m42.unitool.kit.crypto.secureRandomBytes

/**
 * age's own recipients next to the SSH ones in [Age] (cr-restore D604): `X25519` (`age1…` and
 * `AGE-SECRET-KEY-1…`, what `age-keygen` makes) and `scrypt` (`age -p`). The recipes are age's
 * `x25519.go` and `scrypt.go`, step for step; `desktopTest` checks both ways against the real
 * `age` and `age-keygen`.
 *
 * Plugins (`age-plugin-…`) are programs on a computer and cannot be here (D617); a stanza of theirs
 * is reported by [recipients] for what it is.
 */
public object AgeKeys {

    private const val X25519_LABEL = "age-encryption.org/v1/X25519"
    private const val SCRYPT_LABEL = "age-encryption.org/v1/scrypt"
    private const val SECRET_HRP = "age-secret-key-"
    private const val RECIPIENT_HRP = "age"

    /** `age -p`'s work factor: 2¹⁸. */
    public const val DEFAULT_LOG_N: Int = 18

    /** What `age` itself refuses above, unless told otherwise: 2²². */
    public const val MAX_LOG_N: Int = 22

    /** An `AGE-SECRET-KEY-1…`: 32 bytes of X25519 scalar, and the `age1…` it answers to. */
    public class X25519Identity internal constructor(private val secret: ByteArray) {
        public val recipient: String = Bech32.encode(RECIPIENT_HRP, Curve25519.x25519(secret, Curve25519.BASE))

        internal fun unwrap(stanza: Age.Stanza): ByteArray? {
            if (stanza.type != "X25519" || stanza.args.size != 1 || stanza.body.size != 32) return null
            val ephemeral = runCatching { Base64Raw.decode(stanza.args[0]) }.getOrNull() ?: return null
            if (ephemeral.size != 32) return null
            val shared = Curve25519.x25519(secret, ephemeral)
            if (shared.all { it == 0.toByte() }) return null
            val ours = Curve25519.x25519(secret, Curve25519.BASE)
            val wrappingKey = hkdfSha256(shared, ephemeral + ours, X25519_LABEL.encodeToByteArray(), 32)
            shared.fill(0)
            return ChaCha20Poly1305.open(wrappingKey, ByteArray(12), stanza.body).also { wrappingKey.fill(0) }
        }

        public fun wipe(): Unit = secret.fill(0)

        override fun toString(): String = "X25519Identity($recipient)"
    }

    /** A fresh identity, as `age-keygen` makes one. */
    public fun generate(): X25519Identity = X25519Identity(secureRandomBytes(32))

    /** One `AGE-SECRET-KEY-1…`, or null. */
    public fun parseIdentity(text: String): X25519Identity? {
        val decoded = Bech32.decode(text.trim()) ?: return null
        if (decoded.hrp != SECRET_HRP || decoded.data.size != 32) return null
        return X25519Identity(decoded.data)
    }

    /**
     * An identity file as `age-keygen -o` writes it: one key per line, `#` comments and blank lines
     * skipped. Null when a line is neither, or there are no keys at all — like `age -i`, which
     * refuses such a file rather than guessing.
     */
    public fun parseIdentities(text: String): List<X25519Identity>? {
        val keys = mutableListOf<X25519Identity>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            keys += parseIdentity(line) ?: return null
        }
        return keys.takeIf { it.isNotEmpty() }
    }

    /** The `age1…` text, or null. */
    public fun parseRecipient(text: String): ByteArray? {
        val decoded = Bech32.decode(text.trim()) ?: return null
        if (decoded.hrp != RECIPIENT_HRP || decoded.data.size != 32) return null
        return decoded.data
    }

    // ------------------------------------------------------------ what a file is for

    /** One stanza of a file as the screen describes it, before any key (D605). */
    public sealed interface Recipient {
        public class Ssh(public val type: String, public val tag: String) : Recipient
        public data object X25519 : Recipient
        public class Scrypt(public val logN: Int) : Recipient

        /** `mlkem768x25519` and other stanzas this build does not open. */
        public class Other(public val type: String) : Recipient
    }

    /** What [file] is encrypted for, or null when it is not age. */
    public fun recipients(file: ByteArray): List<Recipient>? = Age.stanzas(file)?.map { stanza ->
        when (stanza.type) {
            "ssh-ed25519", "ssh-rsa" -> Recipient.Ssh(stanza.type, stanza.args.firstOrNull().orEmpty())
            "X25519" -> Recipient.X25519
            "scrypt" -> Recipient.Scrypt(logN(stanza) ?: -1)
            else -> Recipient.Other(stanza.type)
        }
    }

    // ------------------------------------------------------------ opening

    public sealed interface Opened {
        public class Ok(public val plaintext: ByteArray) : Opened

        /** Not age at all. */
        public data object NotAge : Opened

        /** No stanza is for any key offered; with a passphrase, the passphrase is wrong. */
        public data object NoIdentity : Opened

        /** `scrypt` and something else in one file — `age` refuses it, so do we. */
        public data object ScryptNotAlone : Opened

        /** `scrypt` asks for 2^[logN], more than [MAX_LOG_N] or than this device was allowed. */
        public class TooHard(public val logN: Int) : Opened

        /** A key opened its stanza and then the MAC or a chunk did not check out. */
        public class Damaged(public val code: String) : Opened
    }

    /**
     * `age -d` with whatever the person has: age keys, SSH keys held in memory, a passphrase. The
     * first stanza something opens decides, as in `age`. [maxLogN] is the most this device may spend
     * on scrypt (D615).
     */
    public fun decrypt(
        file: ByteArray,
        identities: List<X25519Identity> = emptyList(),
        ssh: List<AgeIdentity> = emptyList(),
        passphrase: ByteArray? = null,
        maxLogN: Int = MAX_LOG_N,
    ): Opened {
        val header = Age.header(file) ?: return Opened.NotAge
        val stanzas = header.stanzas
        if (stanzas.any { it.type == "scrypt" } && stanzas.size != 1) return Opened.ScryptNotAlone
        for (stanza in stanzas) {
            val fileKey: ByteArray? = when (stanza.type) {
                "X25519" -> identities.firstNotNullOfOrNull { it.unwrap(stanza) }
                "scrypt" -> {
                    if (passphrase == null) null
                    else {
                        val n = logN(stanza) ?: return Opened.Damaged("header")
                        if (n > minOf(maxLogN, MAX_LOG_N)) return Opened.TooHard(n)
                        unwrapScrypt(stanza, passphrase, n)
                    }
                }
                else -> ssh.firstOrNull { it.matches(stanza) }?.let { identity ->
                    identity.unwrap(stanza) ?: return Opened.Damaged("unwrap")
                }
            }
            if (fileKey != null) {
                return when (val body = Age.openBody(header, fileKey)) {
                    is Age.Body.Ok -> Opened.Ok(body.plaintext)
                    is Age.Body.Failed -> Opened.Damaged(body.code)
                }
            }
        }
        return Opened.NoIdentity
    }

    /** `-> scrypt <salt> <logN>`: logN as `age` writes it, decimal with no leading zero. */
    private fun logN(stanza: Age.Stanza): Int? {
        if (stanza.args.size != 2) return null
        val text = stanza.args[1]
        if (text.isEmpty() || text.length > 2 || (text.length > 1 && text[0] == '0') || text.any { it !in '0'..'9' }) return null
        return text.toInt().takeIf { it in 1..30 }
    }

    private fun unwrapScrypt(stanza: Age.Stanza, passphrase: ByteArray, logN: Int): ByteArray? {
        val salt = runCatching { Base64Raw.decode(stanza.args[0]) }.getOrNull() ?: return null
        if (salt.size != 16 || stanza.body.size != 32) return null
        val key = scrypt(passphrase, SCRYPT_LABEL.encodeToByteArray() + salt, 1 shl logN, 8, 1, 32)
        return ChaCha20Poly1305.open(key, ByteArray(12), stanza.body).also { key.fill(0) }
    }

    // ------------------------------------------------------------ writing, for tests and later

    /** `age -r age1…` for each of [recipients] (32-byte X25519 public keys). */
    public fun encryptTo(plaintext: ByteArray, recipients: List<ByteArray>, random: (Int) -> ByteArray = ::secureRandomBytes): ByteArray =
        Age.write(plaintext, random) { fileKey ->
            recipients.map { theirs ->
                val ephemeral = random(32)
                val share = Curve25519.x25519(ephemeral, Curve25519.BASE)
                val shared = Curve25519.x25519(ephemeral, theirs)
                ephemeral.fill(0)
                check(shared.any { it != 0.toByte() }) { "low-order recipient" }
                val wrappingKey = hkdfSha256(shared, share + theirs, X25519_LABEL.encodeToByteArray(), 32)
                shared.fill(0)
                Age.stanzaText("X25519", listOf(Base64Raw.encode(share)), ChaCha20Poly1305.seal(wrappingKey, ByteArray(12), fileKey))
                    .also { wrappingKey.fill(0) }
            }
        }

    /** `age -p`, with [logN] = 18 as `age` does. */
    public fun encryptWithPassphrase(plaintext: ByteArray, passphrase: ByteArray, logN: Int = DEFAULT_LOG_N, random: (Int) -> ByteArray = ::secureRandomBytes): ByteArray =
        Age.write(plaintext, random) { fileKey ->
            val salt = random(16)
            val key = scrypt(passphrase, SCRYPT_LABEL.encodeToByteArray() + salt, 1 shl logN, 8, 1, 32)
            listOf(Age.stanzaText("scrypt", listOf(Base64Raw.encode(salt), logN.toString()), ChaCha20Poly1305.seal(key, ByteArray(12), fileKey)))
                .also { key.fill(0) }
        }
}
