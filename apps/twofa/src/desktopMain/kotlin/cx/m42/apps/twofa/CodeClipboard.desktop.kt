package cx.m42.apps.twofa

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

@Composable
internal actual fun rememberCodeClipboard(): CodeClipboard = remember { AwtCodeClipboard }

/** The system clipboard, which a desktop has no "sensitive" mark for. Headless, it throws; the callers swallow that. */
private object AwtCodeClipboard : CodeClipboard {
    override fun put(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override fun clearIfStill(text: String): Boolean {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val current = runCatching { clipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull()
        if (current == text) clipboard.setContents(StringSelection(""), null)
        return true
    }
}
