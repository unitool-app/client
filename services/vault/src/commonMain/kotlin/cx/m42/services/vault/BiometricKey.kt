package cx.m42.services.vault

import cx.m42.superizer.runtime.ServiceKey

/**
 * A key in the chip that opens only after a fresh strong biometric — a face or a finger, never the
 * device's passcode — and dies when the set of faces or fingers changes (.ai-notes/cr-note D654,
 * D656). A slice of vault-auth's guarded tier (D569, D570), for one app and with no recovery slot.
 *
 * What it guards is a short secret of the app's — cr-note's root key. [create] seals it to a new
 * chip key and asks nothing: the sealing half is public (RSA-OAEP in Android's Keystore, ECIES to a
 * Secure Enclave P-256). [open] puts the system's biometric sheet up and has the chip open it.
 *
 * Caller-bound: the host hands every app a key of its own, under a name of its own.
 */
public interface BiometricKey {

    /** A strong biometric is enrolled and there is a chip to hold the key. Cheap; asked on every visit. */
    public fun available(): Boolean

    /** The chip still holds this app's key. False after [destroy], and after a change of faces or fingers. */
    public fun exists(): Boolean

    /**
     * A new chip key in place of whatever was there, and [secret] sealed to it. Asks nothing. Throws
     * when the chip refuses; then no key is left behind.
     */
    public suspend fun create(secret: ByteArray): ByteArray

    /** The sheet with [reason] on it, then the chip opens [sealed]. [cancel] is the sheet's one button. */
    public suspend fun open(sealed: ByteArray, reason: String, cancel: String): BiometricOpened

    /** The chip key gone, and with it everything ever sealed to it. */
    public fun destroy()

    public companion object {
        public val Key: ServiceKey<BiometricKey> = ServiceKey("biometric-key")
    }
}

public sealed interface BiometricOpened {
    /** Whoever holds [secret] zeroes it when done. A class, not a data class: no `toString` of the bytes. */
    public class Opened(public val secret: ByteArray) : BiometricOpened {
        override fun toString(): String = "Opened(${secret.size} bytes)"
    }

    /** The person closed the sheet, or the system did. */
    public data object Cancelled : BiometricOpened

    /** The faces or fingers changed, or every one was removed: the key is gone for good (D656). */
    public data object Invalidated : BiometricOpened

    /** Too many tries, no sheet to show, the chip refused: [code] for the log, never for the screen. */
    public data class Failed(val code: String) : BiometricOpened
}
