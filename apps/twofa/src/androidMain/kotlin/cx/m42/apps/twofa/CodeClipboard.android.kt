package cx.m42.apps.twofa

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.random.Random

@Composable
internal actual fun rememberCodeClipboard(): CodeClipboard {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidCodeClipboard(context) }
}

/**
 * Recognises its own clip by the label, not by the text: reading the text is what makes Android 12+
 * show "Unitool pasted from your clipboard", and the description can be read without that.
 */
private class AndroidCodeClipboard(context: Context) : CodeClipboard {
    private val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var label: String? = null

    override fun put(text: String) {
        val tag = "2FA " + Random.nextLong().toULong().toString(36)
        val clip = ClipData.newPlainText(tag, text)
        // The constant is API 33's; the key itself is honoured wherever it is known and ignored
        // where it is not, so it is set on every version.
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        manager.setPrimaryClip(clip)
        label = tag
    }

    override fun clearIfStill(text: String): Boolean? {
        val ours = label ?: return true
        // Null for an app that is not in front (Android 10+), and for an empty clipboard.
        val description = manager.primaryClipDescription ?: return null
        if (description.label?.toString() != ours) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.clearPrimaryClip()
        } else {
            manager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        label = null
        return true
    }

    private companion object {
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
