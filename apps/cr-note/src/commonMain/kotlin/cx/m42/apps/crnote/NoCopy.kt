package cx.m42.apps.crnote

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSeparator
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * Text that can be pasted into and never copied out of (D660): the clipboard handed to the text
 * field takes nothing in, the selection menu offers neither Copy nor Cut — nor Share, Translate or
 * anything else that would carry the text to another app — and Ctrl/⌘ + C or X does nothing.
 * Paste and Select all stay.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NoCopy(content: @Composable () -> Unit) {
    val clipboard = LocalClipboard.current
    val toolbar = LocalTextToolbar.current
    val pasteOnly = remember(clipboard) { PasteOnlyClipboard(clipboard) }
    val noCopyToolbar = remember(toolbar) { NoCopyToolbar(toolbar) }
    CompositionLocalProvider(LocalClipboard provides pasteOnly, LocalTextToolbar provides noCopyToolbar) {
        content()
    }
}

/** For the text field itself: the newer context menu, filtered, and the copy shortcuts swallowed. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.noCopy(): Modifier = this
    .filterTextContextMenuComponents { component ->
        component is TextContextMenuSeparator ||
            component.key == TextContextMenuKeys.PasteKey ||
            component.key == TextContextMenuKeys.SelectAllKey
    }
    .onPreviewKeyEvent { event ->
        (event.isCtrlPressed || event.isMetaPressed) && (event.key == Key.C || event.key == Key.X || event.key == Key.Insert)
    }

private class PasteOnlyClipboard(private val real: Clipboard) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = real.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit

    override val nativeClipboard get() = real.nativeClipboard
}

private class NoCopyToolbar(private val real: TextToolbar) : TextToolbar {
    override val status: TextToolbarStatus get() = real.status

    override fun hide() = real.hide()

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        if (onPasteRequested == null && onSelectAllRequested == null) return real.hide()
        real.showMenu(rect, null, onPasteRequested, null, onSelectAllRequested)
    }

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
        onAutofillRequested: (() -> Unit)?,
    ) {
        if (onPasteRequested == null && onSelectAllRequested == null) return real.hide()
        real.showMenu(rect, null, onPasteRequested, null, onSelectAllRequested, null)
    }
}
