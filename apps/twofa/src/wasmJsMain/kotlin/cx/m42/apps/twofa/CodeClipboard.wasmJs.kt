package cx.m42.apps.twofa

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/**
 * The browser's clipboard. It can be written to, and read only with a permission this app does not
 * ask for, so a copied code is left for the browser to forget.
 */
@Composable
internal actual fun rememberCodeClipboard(): CodeClipboard {
    @Suppress("DEPRECATION")
    val manager = LocalClipboardManager.current
    return remember(manager) {
        object : CodeClipboard {
            override fun put(text: String) {
                manager.setText(AnnotatedString(text))
            }

            override fun clearIfStill(text: String): Boolean = true
        }
    }
}
