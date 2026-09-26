package cx.m42.services.ssh

import cx.m42.services.ssh.crypto.Ed25519
import cx.m42.services.ssh.crypto.Hash
import cx.m42.services.ssh.crypto.PlatformEcdsa
import cx.m42.services.ssh.crypto.Rsa
import cx.m42.services.ssh.wire.WireReader
import cx.m42.services.ssh.wire.WireWriter
import cx.m42.services.ssh.wire.wire
import kotlin.io.encoding.Base64

/**
 * SSHSIG — what `ssh-keygen -Y sign` makes and `-Y verify` checks (OpenSSH `PROTOCOL.sshsig`).
 *
 * The signed data starts with the six bytes `SSHSIG`, and that is the point of it for this module
 * (02 §3.1, D152): an SSH login signs a structure that starts with a 32-bit length, so no SSHSIG an
 * app obtained can ever be replayed as a login. Verifying is a pure function of the public key and
 * works everywhere; ECDSA verification needs the platform ([PlatformEcdsa]) and is [SigCheck.Unsupported]
 * in the browser.
 */
public object SshSig {

    private val MAGIC = "SSHSIG".encodeToByteArray()
    private const val VERSION = 1L
    private const val BEGIN = "-----BEGIN SSH SIGNATURE-----"
    private const val END = "-----END SSH SIGNATURE-----"

    /** `[A-Za-z0-9@._-]{1,64}` — what the keyring lets an app sign under (02 §3.2). */
    public val NAMESPACE: Regex = Regex("[A-Za-z0-9@._-]{1,64}")

    public sealed interface SigCheck {
        public data object Valid : SigCheck
        public data object Invalid : SigCheck
        /** Well-formed, but this platform cannot check the key type (ECDSA in the browser). */
        public data object Unsupported : SigCheck
        /** Not an SSH signature, or for another namespace, or by another key. */
        public data class Mismatch(val reason: String) : SigCheck
    }

    /**
     * The bytes the key signs: `"SSHSIG" ‖ string namespace ‖ string reserved ‖ string hash ‖ string H(message)`.
     * Internal: only the keyring's backends sign, and only this.
     */
    internal fun signedData(namespace: String, hash: Hash, message: ByteArray): ByteArray = WireWriter()
        .raw(MAGIC)
        .string(namespace)
        .string(ByteArray(0))
        .string(hashName(hash))
        .string(hash.digest(message))
        .toByteArray()

    /** The SSH signature blob for [signature] made by [key] over [signedData]: `string type ‖ string bytes`. */
    internal fun signatureBlob(key: SshPublicKey, signature: ByteArray): ByteArray = when (val k = key.kind) {
        is SshPublicKey.Kind.Ed25519 -> wire { string(SshPublicKey.ED25519); string(signature) }
        is SshPublicKey.Kind.Rsa -> wire { string(RSA_SHA512); string(signature) }
        is SshPublicKey.Kind.Ecdsa -> error("use ecdsaSignatureBlob for ${k.curve}")
        SshPublicKey.Kind.Other -> error("cannot sign with ${key.type}")
    }

    internal fun ecdsaSignatureBlob(key: SshPublicKey, r: ByteArray, s: ByteArray): ByteArray =
        wire { string(key.type); string(WireWriter().mpint(r).mpint(s).toByteArray()) }

    /** The armored `-----BEGIN SSH SIGNATURE-----` block, 70 columns, as `ssh-keygen` writes it. */
    internal fun armor(key: SshPublicKey, namespace: String, hash: Hash, signatureBlob: ByteArray): String {
        val blob = WireWriter()
            .raw(MAGIC)
            .uint32(VERSION)
            .string(key.blob)
            .string(namespace)
            .string(ByteArray(0))
            .string(hashName(hash))
            .string(signatureBlob)
            .toByteArray()
        return buildString {
            append(BEGIN).append('\n')
            Base64.Default.encode(blob).chunked(70).forEach { append(it).append('\n') }
            append(END).append('\n')
        }
    }

    /** `ssh-keygen -Y check-novalidate`'s first half: who signed [signature], without checking it. */
    public fun signer(signature: String): SshPublicKey? = parse(signature)?.key

    /** `ssh-keygen -Y verify`: [signature] is by [signer], over [message], under [namespace]. */
    public fun verify(signature: String, message: ByteArray, namespace: String, signer: SshPublicKey): SigCheck {
        val parsed = parse(signature) ?: return SigCheck.Mismatch("not an SSH signature")
        if (parsed.key != signer) return SigCheck.Mismatch("signed by another key")
        if (parsed.namespace != namespace) return SigCheck.Mismatch("signed for namespace '${parsed.namespace}'")
        val hash = when (parsed.hash) {
            "sha256" -> Hash.SHA256
            "sha512" -> Hash.SHA512
            else -> return SigCheck.Mismatch("hash ${parsed.hash}")
        }
        val data = signedData(namespace, hash, message)
        val reader = WireReader(parsed.signature)
        val type = reader.text() ?: return SigCheck.Invalid
        val bytes = reader.string() ?: return SigCheck.Invalid
        if (!reader.done) return SigCheck.Invalid
        return when (val k = signer.kind) {
            is SshPublicKey.Kind.Ed25519 ->
                if (type == SshPublicKey.ED25519 && Ed25519.verify(k.point, data, bytes)) SigCheck.Valid else SigCheck.Invalid
            is SshPublicKey.Kind.Rsa -> {
                val rsaHash = when (type) {
                    RSA_SHA256 -> Hash.SHA256
                    RSA_SHA512 -> Hash.SHA512
                    else -> return SigCheck.Invalid // SHA-1 `ssh-rsa` is refused (D144)
                }
                if (Rsa.verify(k.modulus, k.exponent, rsaHash, data, bytes)) SigCheck.Valid else SigCheck.Invalid
            }
            is SshPublicKey.Kind.Ecdsa -> {
                if (type != signer.type) return SigCheck.Invalid
                val rs = WireReader(bytes)
                val r = rs.mpint() ?: return SigCheck.Invalid
                val s = rs.mpint() ?: return SigCheck.Invalid
                when (PlatformEcdsa.verify(k.curve.bits, k.point, data, r, s)) {
                    true -> SigCheck.Valid
                    false -> SigCheck.Invalid
                    null -> SigCheck.Unsupported
                }
            }
            SshPublicKey.Kind.Other -> SigCheck.Unsupported
        }
    }

    private class Parsed(val key: SshPublicKey, val namespace: String, val hash: String, val signature: ByteArray)

    private fun parse(armored: String): Parsed? {
        val text = armored.trim()
        if (!text.startsWith(BEGIN) || !text.endsWith(END)) return null
        val body = text.substring(BEGIN.length, text.length - END.length).filterNot { it.isWhitespace() }
        val blob = runCatching { Base64.Default.decode(body) }.getOrNull() ?: return null
        if (blob.size < MAGIC.size || !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val reader = WireReader(blob, MAGIC.size)
        if (reader.uint32() != VERSION) return null
        val key = SshPublicKey.fromBlob(reader.string() ?: return null) ?: return null
        val namespace = reader.text() ?: return null
        reader.string() ?: return null // reserved
        val hash = reader.text() ?: return null
        val signature = reader.string() ?: return null
        if (!reader.done) return null
        return Parsed(key, namespace, hash, signature)
    }

    private fun hashName(hash: Hash): String = when (hash) {
        Hash.SHA256 -> "sha256"
        Hash.SHA512 -> "sha512"
        else -> throw IllegalArgumentException("SSHSIG hashes with sha256 or sha512")
    }

    internal const val RSA_SHA256 = "rsa-sha2-256"
    internal const val RSA_SHA512 = "rsa-sha2-512"
}
