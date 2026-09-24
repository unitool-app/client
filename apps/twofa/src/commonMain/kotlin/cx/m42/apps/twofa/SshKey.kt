package cx.m42.apps.twofa

import kotlin.io.encoding.Base64

/**
 * One line of `authorized_keys` — `ssh-ed25519 AAAAC3Nza… comment` — which is how GitHub hands out
 * every account's public keys, read as far as encrypting to it needs.
 *
 * Two types are keys age can encrypt to; everything else (ECDSA, the `sk-` hardware keys) is kept
 * as [Other], so the export page can list it and say why it is not ticked instead of pretending
 * the account has fewer keys than GitHub shows.
 */
internal sealed class SshKey(val type: String, val blob: ByteArray) {

    /** `SHA256:…`, exactly as `ssh-keygen -lf` and GitHub's settings page print it. */
    val fingerprint: String = "SHA256:" + Base64Raw.encode(Sha256.digest(blob))

    /** What `ssh-keygen -l` puts in brackets: `ED25519`, `RSA`, `ECDSA-SK`. */
    abstract val label: String

    class Ed25519(blob: ByteArray, val point: ByteArray) : SshKey(TYPE, blob) {
        override val label: String get() = "ED25519"

        companion object {
            const val TYPE = "ssh-ed25519"
        }
    }

    class Rsa(blob: ByteArray, val exponent: ByteArray, val modulus: ByteArray) : SshKey(TYPE, blob) {
        val bits: Int = modulus.dropWhile { it == 0.toByte() }.let { n ->
            if (n.isEmpty()) 0 else (n.size - 1) * 8 + (32 - (n[0].toInt() and 0xff).countLeadingZeroBits())
        }
        override val label: String get() = "RSA $bits"

        companion object {
            const val TYPE = "ssh-rsa"
        }
    }

    class Other(type: String, blob: ByteArray) : SshKey(type, blob) {
        override val label: String get() = when {
            type == "sk-ssh-ed25519@openssh.com" -> "ED25519-SK"
            type.startsWith("sk-ecdsa-") -> "ECDSA-SK"
            type.startsWith("ecdsa-") -> "ECDSA"
            type == "ssh-dss" -> "DSA"
            else -> type
        }
    }

    companion object {
        /** Null for a line that is not a key at all; a key of a type this cannot use is [Other]. */
        fun parse(line: String): SshKey? {
            val parts = line.trim().split(' ', '\t').filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            val blob = runCatching { Base64.Default.decode(parts[1]) }.getOrNull() ?: return null
            val reader = WireReader(blob)
            val type = reader.string()?.decodeToString() ?: return null
            // The type is written twice, outside and inside the blob. A line where they disagree
            // was edited by hand, and neither half can be trusted.
            if (type != parts[0]) return null
            return when (type) {
                Ed25519.TYPE -> reader.string()?.takeIf { it.size == 32 && reader.done }?.let { Ed25519(blob, it) }
                Rsa.TYPE -> {
                    val e = reader.string() ?: return null
                    val n = reader.string() ?: return null
                    if (!reader.done) null else Rsa(blob, e, n)
                }
                else -> Other(type, blob)
            }
        }
    }
}

/** The SSH wire format's one shape: a 32-bit big-endian length, then that many bytes. */
private class WireReader(private val bytes: ByteArray) {
    private var position = 0

    val done: Boolean get() = position == bytes.size

    fun string(): ByteArray? {
        if (position + 4 > bytes.size) return null
        val length = ((bytes[position].toInt() and 0xff) shl 24) or ((bytes[position + 1].toInt() and 0xff) shl 16) or
            ((bytes[position + 2].toInt() and 0xff) shl 8) or (bytes[position + 3].toInt() and 0xff)
        position += 4
        if (length < 0 || position + length > bytes.size) return null
        return bytes.copyOfRange(position, position + length).also { position += length }
    }
}

/** Standard Base64 without the `=` padding: how both ssh-keygen and age write their short values. */
internal val Base64Raw: Base64 = Base64.Default.withPadding(Base64.PaddingOption.ABSENT)
