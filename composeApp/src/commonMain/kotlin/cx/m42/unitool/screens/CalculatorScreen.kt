package cx.m42.unitool.screens

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.unitool.calculator.CalculatorState
import cx.m42.unitool.calculator.Key
import cx.m42.unitool.calculator.Op
import cx.m42.unitool.calculator.press
import cx.m42.unitool.components.AppScaffold
import cx.m42.unitool.components.BackspaceIcon
import cx.m42.unitool.components.MenuEntry
import cx.m42.unitool.i18n.strings
import cx.m42.unitool.platform.hapticActionComplete
import cx.m42.unitool.theme.AppTheme

/**
 * The one thing this app does: a four-function calculator.
 *
 * The screen owns nothing but the [CalculatorState] value and hands every press to
 * [cx.m42.unitool.calculator.press]. All the arithmetic — chaining, percent, what an error latches
 * — lives in that pure function and is tested without a UI; here there is only layout.
 */
@Composable
fun CalculatorScreen(
    menu: List<MenuEntry>,
    onOpenSettings: () -> Unit,
) {
    var state by remember { mutableStateOf(CalculatorState()) }

    AppScaffold(
        title = strings.calculator,
        menu = menu,
        onOpenSettings = onOpenSettings,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Display(state = state, modifier = Modifier.fillMaxWidth().weight(1f))
            Keypad(
                onKey = { key ->
                    hapticActionComplete()
                    state = state.press(key)
                },
                modifier = Modifier.fillMaxWidth().weight(2.2f),
            )
        }
    }
}

/**
 * The answer, and above it the sum that is waiting for one. Right-aligned and single-line: a number
 * that has run out of room scales down rather than wrapping, because a calculator whose display
 * grows a second line moves every key under the reader's thumb.
 */
@Composable
private fun Display(state: CalculatorState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = state.ribbon.localizeDecimals(),
            style = AppTheme.Body,
            color = AppTheme.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
        )
        Text(
            text = if (state.error) strings.calculatorError else state.entry.localizeDecimals(),
            style = AppTheme.Display,
            // The one place this screen uses colour, and it is for a state: a sum with no answer.
            color = if (state.error) AppTheme.Danger else AppTheme.Foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * The ASCII full stop the calculator works in, rewritten as the mark this language reads.
 *
 * Done here, at the edge, rather than inside [CalculatorState]: the state and the arithmetic stay
 * a pure function over plain strings that parse with `toDouble`, and switching the language
 * repaints the display without touching the number being held. Nothing is parsed back — the
 * separator travels one way, out.
 */
private fun String.localizeDecimals(): String = replace(".", strings.decimalSeparator)

/**
 * The keypad: four columns, five rows, every key the same size except the one that does the sum.
 *
 * The rows are laid out by weight rather than by a fixed height, so the same grid fills a phone, a
 * desktop window and a browser tab without a breakpoint — the same reason the rest of the app has
 * no separate mobile layout.
 */
@Composable
private fun Keypad(onKey: (Key) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(bottom = 12.dp)) {
        KeyRow {
            FlatKey("C", onClick = { onKey(Key.Clear) }, tone = KeyTone.Secondary)
            IconKey(label = strings.calculatorBackspace, onClick = { onKey(Key.Backspace) })
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
 * How loud a key is. The vocabulary is [cx.m42.unitool.components.AppButton]'s, kept monochrome
 * for the same reason: the number on the display has to stay the loudest thing on the screen, so
 * only `=` — the key that produces it — is filled.
 */
private enum class KeyTone { Digit, Operator, Secondary, Primary }

@Composable
private fun RowScope.FlatKey(
    label: String,
    onClick: () -> Unit,
    tone: KeyTone = KeyTone.Digit,
) {
    KeySurface(tone = tone, onClick = onClick, label = label) { foreground ->
        Text(
            text = label,
            style = AppTheme.KeyLabel,
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
    val background = when (tone) {
        KeyTone.Digit, KeyTone.Secondary -> AppTheme.Surface
        KeyTone.Operator -> AppTheme.MenuBackground
        KeyTone.Primary -> AppTheme.Primary
    }
    val foreground = when (tone) {
        KeyTone.Primary -> AppTheme.PrimaryForeground
        KeyTone.Secondary -> AppTheme.Muted
        else -> AppTheme.Foreground
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(4.dp)
            .clip(RoundedCornerShape(AppTheme.Radius))
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        content(foreground)
    }
}
