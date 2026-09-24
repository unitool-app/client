package cx.m42.services.vault

import cx.m42.superizer.runtime.ServiceKey

/**
 * Encrypts what an app keeps, under a key the app never holds.
 *
 * Text in, text out, as every service contract is (Services.kt). The app stores what [seal]
 * returns in its own storage exactly as it would have stored the plain text; the vault holds only
 * the key. On Android that key is generated inside the Keystore — in secure hardware where the
 * device has it — and cannot be read out of it, by this app or by anyone who copies the app's
 * files.
 */
public interface SecretVault {

    /**
     * [plaintext], encrypted and authenticated. A fresh nonce every call, so the same text never
     * seals the same way twice. Throws when the platform's keystore cannot be used; the caller
     * must then keep nothing rather than keep it in the clear.
     */
    public suspend fun seal(plaintext: String): String

    /**
     * What [seal] was given, or null when this vault cannot open [sealed]: changed by even a bit,
     * sealed on another device or a previous install, or the key is gone.
     */
    public suspend fun open(sealed: String): String?

    public companion object {
        public val Key: ServiceKey<SecretVault> = ServiceKey("secret-vault")
    }
}
