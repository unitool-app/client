package cx.m42.services.ssh

import cx.m42.unitool.kit.ssh.SshPublicKey
import cx.m42.unitool.kit.crypto.secureRandomBytes
import cx.m42.unitool.kit.ssh.Age as KitAge

/**
 * age v1 for `ssh-ed25519` and `ssh-rsa` keys. The format moved to the kit (passwords-browser D545);
 * this is the name the apps and the host already use, plus the one thing the kit cannot do — open a
 * file with a key whose private step suspends, because it is the chip's behind the device's prompt
 * (P2, P4). That loop is here, on the kit's [KitAge.header], [KitAge.ed25519FileKey],
 * [KitAge.rsaFileKey] and [KitAge.openPayload].
 */
public object Age {

    public const val MIN_RSA_BITS: Int = KitAge.MIN_RSA_BITS

    public fun supports(key: SshPublicKey): Boolean = KitAge.supports(key)

    public fun encrypt(plaintext: ByteArray, recipients: List<SshPublicKey>): ByteArray =
        KitAge.encrypt(plaintext, recipients, ::secureRandomBytes)

    internal fun encrypt(plaintext: ByteArray, recipients: List<SshPublicKey>, random: (Int) -> ByteArray): ByteArray =
        KitAge.encrypt(plaintext, recipients, random)

    public fun armor(file: ByteArray): String = KitAge.armor(file)

    public fun dearmor(file: ByteArray): ByteArray? = KitAge.dearmor(file)

    public fun stanzas(file: ByteArray): List<KitAge.Stanza>? = KitAge.stanzas(file)

    internal fun tag(key: SshPublicKey): String = KitAge.tag(key)

    internal sealed interface Opened {
        class Ok(val plaintext: ByteArray, val identity: SshPublicKey) : Opened
        data object NoIdentity : Opened
        class Failed(val code: String) : Opened
    }

    /** age -d with identities that may suspend: the first one a stanza is for unwraps the file key. */
    internal suspend fun decrypt(file: ByteArray, identities: List<AgeIdentity>): Opened {
        val header = KitAge.header(file) ?: return Opened.Failed("header")
        for (stanza in header.stanzas) {
            for (identity in identities) {
                if (!identity.matches(stanza)) continue
                val fileKey = identity.unwrap(stanza) ?: return Opened.Failed("unwrap")
                return when (val opened = KitAge.openPayload(header, fileKey, identity.publicKey)) {
                    is KitAge.Opened.Ok -> Opened.Ok(opened.plaintext, opened.identity)
                    is KitAge.Opened.Failed -> Opened.Failed(opened.code)
                    KitAge.Opened.NoIdentity -> Opened.NoIdentity
                }
            }
        }
        return Opened.NoIdentity
    }
}

/**
 * Whatever can open an age stanza meant for [publicKey]: a key the module holds for the length of
 * one operation, or a key in the Keystore that performs the private step itself.
 */
internal interface AgeIdentity {
    val publicKey: SshPublicKey

    fun matches(stanza: KitAge.Stanza): Boolean = KitAge.matches(publicKey, stanza)

    /**
     * The 16-byte file key, or null when the stanza does not open (tampered, or not really ours).
     * Suspends because the private step may be the chip's, behind the device's prompt.
     */
    suspend fun unwrap(stanza: KitAge.Stanza): ByteArray?
}

/**
 * age's `agessh` unwrapping with the private step pluggable: [agree] is X25519 with the key's scalar
 * (for Ed25519), [rsaPrivate] the raw RSA operation (for RSA). The rest is the kit's, once.
 */
internal class StanzaUnwrapper(
    override val publicKey: SshPublicKey,
    private val agree: (suspend (ByteArray) -> ByteArray?)? = null,
    private val rsaPrivate: (suspend (ByteArray) -> ByteArray?)? = null,
) : AgeIdentity {

    override suspend fun unwrap(stanza: KitAge.Stanza): ByteArray? = when (publicKey.kind) {
        is cx.m42.unitool.kit.ssh.SshPublicKey.Kind.Ed25519 -> KitAge.ed25519Ephemeral(stanza)?.let { ephemeral ->
            agree?.invoke(ephemeral)?.let { shared -> KitAge.ed25519FileKey(publicKey, stanza, shared) }
        }
        is cx.m42.unitool.kit.ssh.SshPublicKey.Kind.Rsa -> rsaPrivate?.invoke(stanza.body)?.let(KitAge::rsaFileKey)
        else -> null
    }
}
