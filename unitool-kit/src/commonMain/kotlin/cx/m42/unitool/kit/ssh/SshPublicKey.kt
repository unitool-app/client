package cx.m42.unitool.kit.ssh

import cx.m42.unitool.kit.crypto.Hash
import kotlin.io.encoding.Base64

/**
 * The public half of an SSH key: a line of `authorized_keys`, as `github.com/<login>.keys` serves
 * them (02 §4). A pure value — no service, no prompt, no private material anywhere near it — so it
 * works the same on every platform, the browser included.
 *
 * Moved from the 2FA app's `SshKey` (D156). ECDSA is a type of its own now rather than "other",
 * because the keyring holds ECDSA keys and SSHSIG verifies them. Then from `services/ssh` to the kit,
 * so the console tool reads the same keys (passwords-browser D545); the keyring's `KeyId` stays there.
 */
public class SshPublicKey private constructor(
    /** `ssh-ed25519`, `ssh-rsa`, `ecdsa-sha2-nistp256`, … as written in the blob. */
    public val type: String,
    blob: ByteArray,
    public val kind: Kind,
) {
    private val encoded: ByteArray = blob.copyOf()

    /** The wire encoding: what a fingerprint, an age tag and an SSHSIG are computed over. */
    public val blob: ByteArray get() = encoded.copyOf()

    /** `SHA256:…`, exactly as `ssh-keygen -l` and GitHub's settings page print it. */
    public val fingerprint: String = "SHA256:" + Base64Raw.encode(Hash.SHA256.digest(encoded))

    /** What `ssh-keygen -l` puts in brackets: `ED25519`, `RSA 3072`, `ECDSA 256`, `ECDSA-SK`. */
    public val label: String get() = when (val k = kind) {
        is Kind.Ed25519 -> "ED25519"
        is Kind.Rsa -> "RSA ${k.bits}"
        is Kind.Ecdsa -> "ECDSA ${k.curve.bits}"
        Kind.Other -> when {
            type == "sk-ssh-ed25519@openssh.com" -> "ED25519-SK"
            type.startsWith("sk-ecdsa-") -> "ECDSA-SK"
            type == "ssh-dss" -> "DSA"
            else -> type
        }
    }

    /** `type base64 comment` — the line to paste into `authorized_keys` or GitHub. */
    public fun authorizedKeysLine(comment: String = ""): String =
        "$type ${Base64.Default.encode(encoded)}" + if (comment.isBlank()) "" else " ${comment.trim()}"

    override fun equals(other: Any?): Boolean = other is SshPublicKey && other.encoded.contentEquals(encoded)

    override fun hashCode(): Int = encoded.contentHashCode()

    override fun toString(): String = "SshPublicKey($label $fingerprint)"

    public sealed class Kind {
        public class Ed25519(public val point: ByteArray) : Kind()
        public class Rsa(public val exponent: ByteArray, public val modulus: ByteArray) : Kind() {
            public val bits: Int = cx.m42.unitool.kit.crypto.Rsa.bits(modulus)
        }
        public class Ecdsa(public val curve: Curve, public val point: ByteArray) : Kind()
        public data object Other : Kind()
    }

    /** The three NIST curves OpenSSH signs with, by their SSH names. */
    public enum class Curve(public val sshName: String, public val bits: Int, public val hash: Hash, public val fieldBytes: Int) {
        P256("nistp256", 256, Hash.SHA256, 32),
        P384("nistp384", 384, Hash.SHA384, 48),
        P521("nistp521", 521, Hash.SHA512, 66),
        ;

        public val keyType: String get() = "ecdsa-sha2-$sshName"

        public companion object {
            public fun of(name: String): Curve? = entries.firstOrNull { it.sshName == name }
        }
    }

    public companion object {
        public const val ED25519: String = "ssh-ed25519"
        public const val RSA: String = "ssh-rsa"

        /** Null for a line that is not a key at all; a key of a type nothing here can use is still a key. */
        public fun parse(line: String): SshPublicKey? {
            val parts = line.trim().split(' ', '\t').filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            val blob = runCatching { Base64.Default.decode(parts[1]) }.getOrNull() ?: return null
            val key = fromBlob(blob) ?: return null
            // The type is written twice, outside and inside the blob. A line where they disagree
            // was edited by hand, and neither half can be trusted.
            return key.takeIf { it.type == parts[0] }
        }

        /** Every key in an `authorized_keys` file or a `github.com/<login>.keys` answer; comments and blanks skipped. */
        public fun parseAll(text: String): List<SshPublicKey> =
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.mapNotNull { parse(it) }.toList()

        /** From the wire encoding; null when it does not parse as the type it names. */
        public fun fromBlob(blob: ByteArray): SshPublicKey? {
            val reader = WireReader(blob)
            val type = reader.text() ?: return null
            val kind: Kind = when {
                type == ED25519 -> reader.string()?.takeIf { it.size == 32 && reader.done }?.let { Kind.Ed25519(it) } ?: return null
                type == RSA -> {
                    val e = reader.mpint() ?: return null
                    val n = reader.mpint() ?: return null
                    if (!reader.done || n.isEmpty() || (n[0].toInt() and 0x80) != 0) return null
                    Kind.Rsa(e, n)
                }
                type.startsWith("ecdsa-sha2-") -> {
                    val curve = Curve.of(reader.text() ?: return null) ?: return null
                    if (curve.keyType != type) return null
                    val q = reader.string() ?: return null
                    if (!reader.done || q.size != 1 + 2 * curve.fieldBytes || q[0] != 4.toByte()) return null
                    Kind.Ecdsa(curve, q)
                }
                else -> Kind.Other
            }
            return SshPublicKey(type, blob, kind)
        }

        public fun ed25519(point: ByteArray): SshPublicKey =
            requireNotNull(fromBlob(wire { string(ED25519); string(point) }))

        public fun rsa(exponent: ByteArray, modulus: ByteArray): SshPublicKey =
            requireNotNull(fromBlob(wire { string(RSA); mpint(exponent); mpint(modulus) }))

        public fun ecdsa(curve: Curve, point: ByteArray): SshPublicKey =
            requireNotNull(fromBlob(wire { string(curve.keyType); string(curve.sshName); string(point) }))
    }
}

/** Standard Base64 without the `=` padding: how both ssh-keygen and age write their short values. */
public val Base64Raw: Base64 = Base64.Default.withPadding(Base64.PaddingOption.ABSENT)
