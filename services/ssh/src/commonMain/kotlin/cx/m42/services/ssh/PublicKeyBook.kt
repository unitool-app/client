package cx.m42.services.ssh

import cx.m42.superizer.runtime.ServiceKey
import cx.m42.superizer.ssh.KeyId
import kotlinx.coroutines.flow.StateFlow

/**
 * Someone else's public key, kept to encrypt to (.ai-notes/public-keys, D506): another phone's key
 * read off its screen, a `.pub` file, a pasted line. Only the public half, so only encryption — the
 * owner of the private half opens what is sent.
 */
public class KnownKey(
    public val key: SshPublicKey,
    /** What the person called it; the key's comment unless they changed it. */
    public val name: String,
    public val addedAt: Long,
) {
    public val id: KeyId get() = key.id

    /** `type base64 name`, for age and for the copy button. */
    public val line: String get() = key.authorizedKeysLine(name)
}

/**
 * The saved public keys, as an app sees them (D512): a list to choose recipients from, nothing to
 * change it with. An app that could add a key could have the person encrypt to someone else.
 */
public interface PublicKeyBook {
    public val keys: StateFlow<List<KnownKey>>

    public companion object {
        public val Key: ServiceKey<PublicKeyBook> = ServiceKey("public-keys")
    }
}

/** The same list as the host's own screens see it: they alone add, rename and remove (D512, D513). */
public interface PublicKeyBookAdmin : PublicKeyBook {

    /**
     * What [text] would be if added: one public key line, or `.pub` file contents. Nothing is
     * stored; the screen shows the answer and asks for a name.
     */
    public fun inspect(text: String): AddKnownOutcome

    /** Stores [key] under [name] (the key's own comment when blank), after the checks [inspect] makes. */
    public suspend fun add(key: SshPublicKey, name: String): AddKnownOutcome

    public suspend fun rename(id: KeyId, name: String)

    public suspend fun remove(id: KeyId)

    /** The list as an app gets it: the same flow, without the methods that change it. */
    public val readOnly: PublicKeyBook
}

/** Why a key is or is not added (D508, D509). */
public sealed interface AddKnownOutcome {
    /** [inspect]: it can be added. [add]: it was. */
    public class Ok(public val key: SshPublicKey, public val comment: String) : AddKnownOutcome

    /** Not one public key line. A private key file lands here too — it belongs in "Add a key". */
    public data object NotAPublicKey : AddKnownOutcome

    /** A key age cannot encrypt to: ECDSA (the iPhone's Secure Enclave key among them), RSA under 2048 bits. */
    public class CannotEncryptTo(public val key: SshPublicKey) : AddKnownOutcome

    /** One of this device's own keys: already here, with its private half. */
    public class OwnKey(public val comment: String) : AddKnownOutcome

    /** Saved before, under [name]. */
    public class AlreadySaved(public val name: String) : AddKnownOutcome
}
