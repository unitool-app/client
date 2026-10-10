package cx.m42.services.ssh.key

import cx.m42.services.ssh.AgeIdentity
import cx.m42.services.ssh.SshPublicKey
import cx.m42.services.ssh.SshSig
import cx.m42.services.ssh.StanzaUnwrapper
import cx.m42.services.ssh.crypto.PlatformEcdsa
import cx.m42.services.ssh.wire.wire
import cx.m42.unitool.kit.crypto.Curve25519
import cx.m42.unitool.kit.crypto.Ed25519
import cx.m42.unitool.kit.crypto.Hash
import cx.m42.unitool.kit.crypto.Rsa
import cx.m42.unitool.kit.crypto.Sha512

/**
 * The private operations on a key this module holds in memory — levels 2 and 3, for the length of
 * one operation (05 §2). Each opens the fields it needs into [cx.m42.services.ssh.SecretBytes] and
 * closes them before returning. Level 1 keys never come here: the Keystore does the same steps
 * itself.
 */
internal object SoftwareOps {

    /** `ssh-keygen -Y sign -n [namespace]`, SHA-512 as `ssh-keygen` does. Null when the platform cannot sign this type. */
    fun sshsig(key: PrivateKey, namespace: String, message: ByteArray): String? {
        val data = SshSig.signedData(namespace, Hash.SHA512, message)
        val blob = signatureBlob(key, data, rsaHash = Hash.SHA512) ?: return null
        return SshSig.armor(key.publicKey, namespace, Hash.SHA512, blob)
    }

    /**
     * A raw SSH signature blob over [data] — the agent's `SIGN_REQUEST` (02 §2). [rsaHash] is what
     * the request's flags asked for; SHA-1 is never offered.
     */
    fun signatureBlob(key: PrivateKey, data: ByteArray, rsaHash: Hash): ByteArray? = when (val k = key.publicKey.kind) {
        is SshPublicKey.Kind.Ed25519 -> key.ed25519Seed().use { seed ->
            wire { string(SshPublicKey.ED25519); string(Ed25519.sign(seed.bytes, data)) }
        }
        is SshPublicKey.Kind.Rsa -> key.rsaPrivateExponent().use { d ->
            val name = if (rsaHash == Hash.SHA256) SshSig.RSA_SHA256 else SshSig.RSA_SHA512
            wire { string(name); string(Rsa.sign(k.modulus, d.bytes, rsaHash, data)) }
        }
        is SshPublicKey.Kind.Ecdsa -> key.ecdsaPrivateScalar().use { d ->
            val (r, s) = PlatformEcdsa.sign(k.curve.bits, d.bytes, k.point, data) ?: return null
            SshSig.ecdsaSignatureBlob(key.publicKey, r, s)
        }
        SshPublicKey.Kind.Other -> null
    }

    /**
     * An age identity for [key], valid while [key] is open: Ed25519's X25519 scalar is SHA-512 of
     * the seed, first half (what `age -d -i ~/.ssh/id_ed25519` does); RSA is the private operation.
     */
    fun ageIdentity(key: PrivateKey): AgeIdentity? = when (val k = key.publicKey.kind) {
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
}
