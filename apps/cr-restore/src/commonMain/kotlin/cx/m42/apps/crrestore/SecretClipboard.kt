package cx.m42.apps.crrestore

import androidx.compose.runtime.Composable

/**
 * The clipboard, as copied plaintext needs it (D597): in with the platform's "sensitive" mark, and
 * out again a minute later if nothing else has been put there since. The 2FA app's `CodeClipboard`
 * (06 §7), word for word: the same platform facts, kept next to the app that relies on them.
 */
internal interface SecretClipboard {

    /** Puts [text] on the clipboard, marked sensitive where the platform has such a mark. */
    fun put(text: String)

    /**
     * Clears the clipboard if it still holds what [put] put there. True when that is settled; null
     * when it cannot be told right now (Android, with another app in front).
     */
    fun clearIfStill(text: String): Boolean?
}

/** How long a copy stays on the clipboard, as in 2FA. */
internal const val CLIPBOARD_SECONDS: Int = 60

@Composable
internal expect fun rememberSecretClipboard(): SecretClipboard
