package cx.m42.apps.converter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.AppTextField
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.components.ChoiceRow
import cx.m42.superizer.ui.components.RowDivider
import kotlin.math.abs
import kotlin.math.round

/**
 * Amount at the top, the two currencies under it, the answer below, and one grey line saying where
 * the number came from.
 *
 * That last line is the screen's real subject. A converter that silently shows yesterday's rate is
 * indistinguishable from one that is working, so "· кэш, офлайн" is not a detail — it is what makes
 * the offline path honest.
 */
@Composable
internal fun ConverterScreen(instance: ConverterInstance) {
    val tokens = AppTheme
    val strings = LocalAppRuntime.current.locale.pick(ConverterStringTables, ConverterStringsEn)
    val amount by instance.amount.collectAsState()
    val from by instance.from.collectAsState()
    val to by instance.to.collectAsState()
    val state by instance.rates.collectAsState()
    val online by instance.runtime.network.online.collectAsState()

    var picking by remember { mutableStateOf<Side?>(null) }

    // The first request of the app's life happens here and not in `onLaunch`: the container gets a
    // frame immediately, and the contract test can hold `onLaunch` to its 200 ms budget.
    LaunchedEffect(instance) { instance.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.pageBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        AppTextField(
            value = amount,
            onValueChange = instance::setAmount,
            placeholder = "0",
            label = strings.amount,
            textStyle = tokens.display,
            minHeight = 88.dp,
            modifier = Modifier.testTag("currency-converter:amount"),
        )

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CurrencyTile(
                label = strings.fromLabel,
                code = from,
                tag = "currency-converter:from",
                onClick = { picking = Side.From },
                modifier = Modifier.weight(1f),
            )
            CurrencyTile(
                label = strings.toLabel,
                code = to,
                tag = "currency-converter:to",
                onClick = { picking = Side.To },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))
        AppButton(
            text = strings.swap,
            onClick = instance::swap,
            size = ButtonSize.Sm,
            variant = ButtonVariant.Secondary,
            modifier = Modifier.testTag("currency-converter:swap"),
        )

        Spacer(Modifier.height(20.dp))

        when (val current = state) {
            is RatesState.Ready -> {
                val result = instance.result
                Text(
                    text = result?.let { "${formatMoney(it)} $to" } ?: "—",
                    style = tokens.title,
                    color = tokens.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().testTag("currency-converter:result"),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = strings.source(current.rates.date, current.fromCache || !online, current.rates.credit),
                    style = tokens.footnote,
                    color = tokens.muted,
                    modifier = Modifier.testTag("currency-converter:source"),
                )
            }

            RatesState.Loading -> Text(
                text = if (online) strings.loading else strings.offline,
                style = tokens.body,
                color = tokens.muted,
                modifier = Modifier.testTag("currency-converter:source"),
            )

            RatesState.Failed -> Column(modifier = Modifier.testTag("currency-converter:error")) {
                // Offline with nothing cached is a different sentence from "the request failed":
                // one is a state the user can fix by walking outside, the other is ours (D32).
                Text(
                    text = if (online) strings.failed else strings.offline,
                    style = tokens.body,
                    color = tokens.danger,
                )
                Spacer(Modifier.height(12.dp))
                AppButton(
                    text = strings.retry,
                    onClick = { instance.refresh(force = true) },
                    size = ButtonSize.Default,
                    modifier = Modifier.testTag("currency-converter:retry"),
                )
            }
        }

        val side = picking
        if (side != null) {
            Spacer(Modifier.height(20.dp))
            CurrencyPicker(
                title = if (side == Side.From) strings.fromLabel else strings.toLabel,
                codes = instance.currencies(),
                selected = if (side == Side.From) from else to,
                onPick = { code ->
                    if (side == Side.From) instance.from.value = code else instance.to.value = code
                    picking = null
                },
            )
        }
    }
}

private enum class Side { From, To }

@Composable
private fun CurrencyTile(
    label: String,
    code: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = AppTheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(tokens.radius))
            .background(tokens.background)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(16.dp),
    ) {
        Text(text = label, style = tokens.footnote, color = tokens.muted)
        Spacer(Modifier.height(4.dp))
        Text(text = code, style = tokens.header, color = tokens.foreground)
    }
}

@Composable
private fun CurrencyPicker(
    title: String,
    codes: List<String>,
    selected: String,
    onPick: (String) -> Unit,
) {
    val tokens = AppTheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radius))
            .background(tokens.background)
            .testTag("currency-converter:picker"),
    ) {
        Text(
            text = title,
            style = tokens.label,
            color = tokens.muted,
            modifier = Modifier.padding(16.dp),
        )
        Column(modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
            codes.forEachIndexed { index, code ->
                if (index > 0) RowDivider()
                ChoiceRow(label = code, selected = code == selected, onClick = { onPick(code) })
            }
        }
    }
}

/**
 * Two decimals, by hand.
 *
 * `commonMain` has no number formatter, and the same string has to come out on all three targets
 * — the desktop test suite included. The calculator's `format` solves the same problem for the
 * same reason; this one is the money-shaped version of it.
 */
internal fun formatMoney(value: Double): String {
    if (!value.isFinite()) return "—"
    val rounded = round(abs(value) * 100) / 100
    val whole = rounded.toLong()
    val cents = round((rounded - whole) * 100).toLong()
    val sign = if (value < 0) "-" else ""
    return "$sign$whole.${cents.toString().padStart(2, '0')}"
}
