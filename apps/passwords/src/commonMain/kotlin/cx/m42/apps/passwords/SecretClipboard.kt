package cx.m42.apps.passwords

import androidx.compose.runtime.Composable

/**
 * The clipboard, as a copied password needs it (06 §7; 2FA's, copied): in with the platform's
 * "sensitive" mark, and out again a minute later if nothing else has been put there since.
 *
 * Its own small interface rather than Compose's clipboard, because the two things it adds are
 * platform facts. Android 13+ has `EXTRA_IS_SENSITIVE`, which keeps the password out of the paste
 * preview and — for keyboards that honour it — out of their clipboard history. And whether the
 * clipboard can be *read* depends on the platform: Android allows it only to the app in front.
 */
internal interface SecretClipboard {

    /** Puts [text] on the clipboard, marked sensitive where the platform has such a mark. */
    fun put(text: String)

    /**
     * Clears the clipboard if it still holds what [put] put there. True when that is settled —
     * cleared, or holding something else now. Null when it cannot be told right now (Android, with
     * another app in front): ask again when this one is back.
     */
    fun clearIfStill(text: String): Boolean?
}

@Composable
internal expect fun rememberSecretClipboard(): SecretClipboard
