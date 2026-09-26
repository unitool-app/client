package cx.m42.unitool.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.superizer.Superizer
import cx.m42.superizer.app.AppId
import cx.m42.superizer.ssh.KeyId
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.unitool.i18n.isoDate

/*
 * The pieces the host's own screens are made of, on top of the library's components (07 §2.4), so
 * that SSH keys and Storage & security read like the rest of Settings.
 */

/** A whole screen's scrolling column on the page background. */
@Composable
internal fun Page(tag: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.pageBackground)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp)
            .testTag(tag),
        content = content,
    )
}

/** A block of rows without a heading — a library `Section` whose title the screen's bar already says. */
@Composable
internal fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 20.dp)
            .clip(RoundedCornerShape(AppTheme.radius))
            .background(AppTheme.background),
        content = content,
    )
}

/** A line of text inside a card. */
@Composable
internal fun RowText(text: String, modifier: Modifier = Modifier, color: Color = AppTheme.foreground, mono: Boolean = false, small: Boolean = false) {
    val tokens = AppTheme
    Text(
        text = text,
        style = when {
            mono -> tokens.mono
            small -> tokens.footnote
            else -> tokens.body
        },
        color = color,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** A row that does something when tapped, with an optional line under it. */
@Composable
internal fun ActionRow(label: String, tag: String, hint: String? = null, onClick: () -> Unit) {
    val tokens = AppTheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(text = label, style = tokens.body, color = tokens.foreground)
        if (hint != null) {
            Spacer(Modifier.height(2.dp))
            Text(text = hint, style = tokens.footnote, color = tokens.muted)
        }
    }
}

/** Words above a field. */
@Composable
internal fun FieldLabel(text: String) {
    Text(
        text = text,
        style = AppTheme.label,
        color = AppTheme.foreground,
        modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp),
    )
}

/** A footnote between blocks, in the page's margins. */
@Composable
internal fun Note(text: String, color: Color = AppTheme.muted, tag: String? = null) {
    Text(
        text = text,
        style = AppTheme.footnote,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp)
            .let { if (tag != null) it.testTag(tag) else it },
    )
}

/** The page's own buttons, full width, one under another. */
@Composable
internal fun PageButton(
    text: String,
    tag: String,
    enabled: Boolean = true,
    variant: ButtonVariant = ButtonVariant.Default,
    onClick: () -> Unit,
) {
    AppButton(
        text = text,
        onClick = onClick,
        enabled = enabled,
        variant = variant,
        size = ButtonSize.Default,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp).testTag(tag),
    )
}

/** Two small buttons side by side, inside a card. */
@Composable
internal fun SmallButtons(vararg buttons: Triple<String, String, () -> Unit>) {
    Row(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        buttons.forEachIndexed { i, (text, tag, onClick) ->
            if (i > 0) Spacer(Modifier.width(8.dp))
            AppButton(text, onClick, variant = ButtonVariant.Outline, size = ButtonSize.Sm, modifier = Modifier.testTag(tag))
        }
    }
}

/** `SHA256:` and the first twelve characters: enough to tell keys apart, short enough for a row. */
internal fun shortFingerprint(id: KeyId): String {
    val body = id.fingerprint.removePrefix("SHA256:")
    return "SHA256:" + if (body.length > 12) body.take(12) + "…" else body
}

/** An app's name in the host's language, or its id when the build no longer has it. */
internal fun appTitle(superizer: Superizer, app: AppId, langTag: String): String =
    superizer.registry.get(app)?.metadata?.title?.resolve(langTag) ?: app.value

/** A moment to the minute, in UTC and saying so: nothing here knows the device's time zone. */
internal fun isoMinute(millis: Long): String {
    val minuteOfDay = millis.mod(86_400_000L) / 60_000
    val hh = (minuteOfDay / 60).toString().padStart(2, '0')
    val mm = (minuteOfDay % 60).toString().padStart(2, '0')
    return "${isoDate(millis)} $hh:$mm UTC"
}
