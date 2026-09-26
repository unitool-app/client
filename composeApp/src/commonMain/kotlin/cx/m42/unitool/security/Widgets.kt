package cx.m42.unitool.security

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.superizer.diagnostics.Finding
import cx.m42.superizer.diagnostics.Status
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.i18n.LocalDiagnosticsStrings
import cx.m42.unitool.i18n.isoDate

/**
 * A passphrase field (T10, I13): the text is hidden, the keyboard is told it is a password so it
 * neither suggests nor learns, and the owner turns the text into a `CharArray` the moment it is
 * used and clears the field. Compose keeps a field's text as a `String`, which nothing can wipe —
 * written down in 01 §4.5 rather than pretended away.
 */
@Composable
internal fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val tokens = AppTheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, tokens.border, RoundedCornerShape(8.dp))
            .background(tokens.background)
            .padding(12.dp),
    ) {
        if (value.isEmpty()) Text(text = label, style = tokens.body, color = tokens.muted)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = tokens.body.copy(color = tokens.foreground),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            cursorBrush = SolidColor(tokens.foreground),
            modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
        )
    }
}

/** A modal panel over everything, with a scrim that does nothing when tapped — a sheet is answered, not dismissed by accident. */
@Composable
internal fun Sheet(tag: String, content: @Composable () -> Unit) {
    val tokens = AppTheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.foreground.copy(alpha = 0.35f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .padding(20.dp)
                .clip(RoundedCornerShape(tokens.radius))
                .background(tokens.background)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) { content() }
    }
}

/** A finding in the host's words (D179): the status, the title, the detail with its facts. */
@Composable
internal fun FindingRow(finding: Finding, modifier: Modifier = Modifier) {
    val tokens = AppTheme
    val words = LocalDiagnosticsStrings.current
    val color = statusColor(finding.status)
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("finding:${finding.code}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = words.status(finding.status), style = tokens.label, color = color)
        }
        Spacer(Modifier.height(4.dp))
        Text(text = words.title(finding.code).fill(finding.facts), style = tokens.body, color = tokens.foreground)
        val detail = words.detail(finding.code, finding.facts + (finding.evidence?.let { mapOf("evidence" to it) } ?: emptyMap()))
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(text = detail, style = tokens.footnote, color = tokens.muted)
        }
    }
}

@Composable
internal fun statusColor(status: Status): Color {
    val tokens = AppTheme
    return when (status) {
        Status.Ok -> tokens.foreground
        Status.Info, Status.Attention -> tokens.muted
        Status.Unsafe, Status.Missing, Status.Broken -> tokens.danger
    }
}

/** Titles carry `{…}` too (06 §6.1): the same facts fill them, a date as a date. */
internal fun String.fill(facts: Map<String, String>): String =
    Regex("\\{([a-z]+)\\}").replace(this) { m ->
        val name = m.groupValues[1]
        val value = facts[name] ?: return@replace m.value
        if (name == "date") value.toLongOrNull()?.let(::isoDate) ?: value else value
    }
