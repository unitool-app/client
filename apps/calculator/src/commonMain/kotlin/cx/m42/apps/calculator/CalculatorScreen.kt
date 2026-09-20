package cx.m42.apps.calculator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.BackspaceIcon

/**
 * The calculator's screen, and nothing around it.
 *
 * There is no `AppScaffold` here and no back handler: the host draws the frame and owns navigation
 * (§14). That is the single largest difference between this file and the one it was migrated from,
 * and it is what lets the same screen appear inside Unitool, inside the fixture, or standalone.
 */
@Composable
internal fun CalculatorScreen(instance: CalculatorInstance) {
    val state by instance.state.collectAsState()
    val strings = LocalAppRuntime.current.locale.pick(CalculatorStringTables, CalculatorStringsEn)

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Display(
            state = state,
            strings = strings,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Keypad(
            strings = strings,
            scientific = instance.scientific,
            onKey = instance::press,
            modifier = Modifier.fillMaxWidth().weight(if (instance.scientific) 2.6f else 2.2f),
        )
    }
}

/**
 * The answer, and above it the sum waiting for one. Right-aligned and single-line: a number that
 * has run out of room shrinks rather than wrapping, because a display that grows a second line
 * moves every key under the reader's thumb.
 */
@Composable
private fun Display(state: CalculatorState, strings: CalculatorStrings, modifier: Modifier = Modifier) {
    val tokens = AppTheme
    Column(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = state.ribbon.localizeDecimals(strings),
            style = tokens.body,
            color = tokens.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().testTag("calculator:ribbon"),
            textAlign = TextAlign.End,
        )
        Text(
            text = if (state.error) strings.error else state.entry.localizeDecimals(strings),
            style = tokens.display,
            // The one place this screen uses colour, and it is for a state: a sum with no answer.
            color = if (state.error) tokens.danger else tokens.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().testTag("calculator:display"),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * The ASCII full stop the arithmetic works in, rewritten as the mark this language reads.
 *
 * Done at the edge rather than inside the state: the reducer stays a pure function over strings
 * that parse with `toDouble`, and switching language repaints the display without touching the
 * number being held. Nothing is parsed back — the separator travels one way, out.
 */
private fun String.localizeDecimals(strings: CalculatorStrings): String =
    replace(".", strings.decimalSeparator)

/**
 * Four columns; five rows, or six with the scientific row on top.
 *
 * Laid out by weight rather than by a fixed height, so the same grid fills a phone, a desktop
 * window and a browser tab without a breakpoint.
 */
@Composable
private fun Keypad(
    strings: CalculatorStrings,
    scientific: Boolean,
    onKey: (Key) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(bottom = 12.dp)) {
        if (scientific) {
            KeyRow {
                UnaryOp.entries.forEach { op ->
                    FlatKey(op.symbol, onClick = { onKey(Key.Unary(op)) }, tone = KeyTone.Secondary)
                }
                // The fourth cell keeps the row on the same four-column grid as the rest.
                Box(modifier = Modifier.weight(1f))
            }
        }
        KeyRow {
            FlatKey("C", onClick = { onKey(Key.Clear) }, tone = KeyTone.Secondary)
            IconKey(label = strings.backspace, onClick = { onKey(Key.Backspace) })
            FlatKey("%", onClick = { onKey(Key.Percent) }, tone = KeyTone.Secondary)
            FlatKey(Op.Divide.symbol, onClick = { onKey(Key.Operator(Op.Divide)) }, tone = KeyTone.Operator)
        }
        KeyRow {
            (7..9).forEach { digit -> FlatKey("$digit", onClick = { onKey(Key.Digit(digit)) }) }
            FlatKey(Op.Times.symbol, onClick = { onKey(Key.Operator(Op.Times)) }, tone = KeyTone.Operator)
        }
        KeyRow {
            (4..6).forEach { digit -> FlatKey("$digit", onClick = { onKey(Key.Digit(digit)) }) }
            FlatKey(Op.Minus.symbol, onClick = { onKey(Key.Operator(Op.Minus)) }, tone = KeyTone.Operator)
        }
        KeyRow {
            (1..3).forEach { digit -> FlatKey("$digit", onClick = { onKey(Key.Digit(digit)) }) }
            FlatKey(Op.Plus.symbol, onClick = { onKey(Key.Operator(Op.Plus)) }, tone = KeyTone.Operator)
        }
        KeyRow {
            FlatKey("±", onClick = { onKey(Key.Sign) }, tone = KeyTone.Secondary)
            FlatKey("0", onClick = { onKey(Key.Digit(0)) })
            FlatKey(strings.decimalSeparator, onClick = { onKey(Key.Dot) })
            FlatKey("=", onClick = { onKey(Key.Equals) }, tone = KeyTone.Primary)
        }
    }
}

@Composable
private fun ColumnScope.KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().weight(1f), content = content)
}

/**
 * How loud a key is. Monochrome, for the same reason the rest of the design is: the number on the
 * display has to stay the loudest thing on the screen, so only `=` — the key that produces it — is
 * filled.
 */
private enum class KeyTone { Digit, Operator, Secondary, Primary }

@Composable
private fun RowScope.FlatKey(label: String, onClick: () -> Unit, tone: KeyTone = KeyTone.Digit) {
    KeySurface(tone = tone, onClick = onClick, label = label) { foreground ->
        Text(
            text = label,
            style = AppTheme.keyLabel,
            color = foreground,
            fontWeight = if (tone == KeyTone.Digit) FontWeight.Normal else FontWeight.Medium,
        )
    }
}

/** The one key drawn as a glyph, so it needs a spoken label of its own. */
@Composable
private fun RowScope.IconKey(label: String, onClick: () -> Unit) {
    KeySurface(tone = KeyTone.Secondary, onClick = onClick, label = label) { foreground ->
        BackspaceIcon(foreground, size = 24.dp)
    }
}

@Composable
private fun RowScope.KeySurface(
    tone: KeyTone,
    onClick: () -> Unit,
    label: String,
    content: @Composable (Color) -> Unit,
) {
    val tokens = AppTheme
    val background = when (tone) {
        KeyTone.Digit, KeyTone.Secondary -> tokens.surface
        KeyTone.Operator -> tokens.menuBackground
        KeyTone.Primary -> tokens.primary
    }
    val foreground = when (tone) {
        KeyTone.Primary -> tokens.primaryForeground
        KeyTone.Secondary -> tokens.muted
        else -> tokens.foreground
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(4.dp)
            .clip(RoundedCornerShape(tokens.radius))
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            // D39: `<appId>:<element>`, so a test finds a key by what it is rather than by the
            // label it happens to carry in whatever language the test ran in.
            .testTag("calculator:key-$label")
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        content(foreground)
    }
}
