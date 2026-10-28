package cx.m42.services.ssh.keyring

import cx.m42.superizer.app.AppId
import cx.m42.superizer.ssh.KeyId
import cx.m42.superizer.ssh.KeyOperation
import cx.m42.superizer.ssh.KeyPurpose
import cx.m42.superizer.ssh.SshKeyInfo

/**
 * What an operation is, in data: who asks, with which key, to do what (01 §5.2). The sheet the
 * person sees is drawn from this by the host's UI, in its words — this module has none (07 §2.2).
 * [digest] is the first 8 hex of the SHA-256 of what is signed or decrypted, to recognise it by.
 * [action]: what the host itself is doing, where [operation] only names the private step it takes.
 */
public data class UseReason(
    val app: AppId?,
    val key: SshKeyInfo,
    val operation: KeyOperation,
    val namespace: String? = null,
    val digest: String? = null,
    val action: HostAction? = null,
)

/**
 * What the host does with a key that the contract's [KeyOperation] has no word for (.ai-notes/key-share):
 * the operations are Superizer's and closed, so the action rides on the nearest one, and the sheet and
 * the journal read it first. In the journal it is the namespace — one in `@m42.cx`, which no app may
 * sign under, so an app's line is never taken for it.
 */
public sealed interface HostAction {
    public val namespace: String

    /** The key's file, sealed with age to [to] — another device of the person's — to be sent there. */
    public data class Export(val to: String) : HostAction {
        override val namespace: String get() = EXPORT
    }

    /** Something encrypted to the key and opened by it, or signed and checked: does its lock still open it? */
    public data object Verify : HostAction {
        override val namespace: String get() = VERIFY
    }

    public companion object {
        public const val EXPORT: String = "export@m42.cx"
        public const val VERIFY: String = "verify@m42.cx"
    }
}

/**
 * The words on the device's own prompt for one operation (01 §5.2) — `BiometricPrompt` on Android,
 * the `LAContext` reason on iOS: the host's, from [UseReason]. This module has none of its own (07 §2.2).
 * [cancel] labels the button Android requires when the screen lock is no way out — a key only a
 * strong biometric opens (09, D245).
 */
public class PromptText(
    public val title: String,
    public val subtitle: String?,
    public val description: String?,
    public val cancel: String = "Cancel",
)

/**
 * The person, as the keyring asks them things. The host's UI implements it — a sheet over the shell
 * on every platform — and on Android and iOS the device's own prompt comes from the chip backend
 * instead, because only it can be bound to the key (01 §5.1).
 */
public interface KeyPrompter {

    /** An app asks for a key it holds no grant for: pick one of [candidates] for [purpose], or null for no. */
    public suspend fun choose(app: AppId, purpose: KeyPurpose, candidates: List<SshKeyInfo>): KeyId?

    /** An app asks to use [key] for [purpose] and holds no grant: yes or no. */
    public suspend fun grant(app: AppId, key: SshKeyInfo, purpose: KeyPurpose): Boolean

    /**
     * The key's passphrase for [reason] — level 3's lock (05 §2.2). [wrong] counts the attempts
     * already refused. Null is "cancel". The caller zeroes what it gets.
     */
    public suspend fun passphrase(reason: UseReason, wrong: Int): CharArray?

    /** A confirmation that is not a use of the key: delete (D148), revoke. */
    public suspend fun confirm(reason: UseReason): Boolean
}
