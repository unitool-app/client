package cx.m42.apps.passwords

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.UIKit.UIPasteboard
import platform.UIKit.UIPasteboardOptionExpirationDate
import platform.UIKit.UIPasteboardOptionLocalOnly

@Composable
internal actual fun rememberSecretClipboard(): SecretClipboard = remember { IosSecretClipboard }

/**
 * iOS does both halves of 06 §7 itself: an item put on the pasteboard with an expiration date is
 * removed by the system when it comes, whether or not this app is running, and a local-only item
 * never reaches the Mac or iPad next to the phone through Universal Clipboard.
 *
 * Recognised by `changeCount`, not by reading it back: reading the pasteboard is what makes iOS 16+
 * ask the person "Allow paste?", and a count changes whenever anyone else writes.
 */
private object IosSecretClipboard : SecretClipboard {
    private var ours: Long? = null

    override fun put(text: String) {
        val pasteboard = UIPasteboard.generalPasteboard
        pasteboard.setItems(
            listOf(mapOf(PLAIN_TEXT to text)),
            options = mapOf<Any?, Any?>(
                UIPasteboardOptionExpirationDate to NSDate.dateWithTimeIntervalSinceNow(CLIPBOARD_SECONDS.toDouble()),
                UIPasteboardOptionLocalOnly to true,
            ),
        )
        ours = pasteboard.changeCount
    }

    override fun clearIfStill(text: String): Boolean {
        val pasteboard = UIPasteboard.generalPasteboard
        if (ours != null && pasteboard.changeCount == ours) pasteboard.setItems(emptyList<Any>())
        ours = null
        return true
    }

    private const val PLAIN_TEXT = "public.utf8-plain-text"
}
