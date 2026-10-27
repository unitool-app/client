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
 * The person's pairs, one card each — an amount, the two currencies and the answer under them —
 * then «Добавить пару», and one grey line saying where the numbers came from.
 *
 * That last line is the screen's real subject. A converter that silently shows yesterday's rate is
 * indistinguishable from one that is working, so "· кэш, офлайн" is not a detail — it is what makes
 * the offline path honest. It is said once, because every card is answered by the same table.
 */
@Composable
internal fun ConverterScreen(instance: ConverterInstance) {
    val tokens = AppTheme
    val strings = LocalAppRuntime.current.locale.pick(ConverterStringTables, ConverterStringsEn)
    val rows by instance.rows.collectAsState()
    val state by instance.rates.collectAsState()
    val online by instance.runtime.network.online.collectAsState()

    var picking by remember { mutableStateOf<Picking?>(null) }
    var editing by remember { mutableStateOf(false) }

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
        rows.forEach { row ->
            PairCard(
                row = row,
                result = instance.result(row),
                ready = state is RatesState.Ready,
                editing = editing,
                strings = strings,
                onAmount = { instance.setAmount(row.id, it) },
                onFrom = { picking = Picking.Row(row.id, Side.From) },
                onTo = { picking = Picking.Row(row.id, Side.To) },
                onSwap = { instance.swap(row.id) },
                onRemove = { instance.remove(row.id) },
            )
            val current = picking
            if (current is Picking.Row && current.id == row.id) {
                Spacer(Modifier.height(8.dp))
                CurrencyPicker(
                    title = if (current.side == Side.From) strings.fromLabel else strings.toLabel,
                    codes = instance.currencies(),
                    selected = if (current.side == Side.From) row.from else row.to,
                    onPick = { code ->
                        if (current.side == Side.From) instance.pickFrom(row.id, code) else instance.pickTo(row.id, code)
                        picking = null
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // Two steps, the same list both times: first what to convert from, then into.
        when (val current = picking) {
            Picking.NewFrom -> CurrencyPicker(
                title = strings.fromLabel,
                codes = instance.currencies(),
                selected = null,
                onPick = { picking = Picking.NewTo(it) },
            )
            is Picking.NewTo -> CurrencyPicker(
                title = "${current.from} → ${strings.toLabel}",
                codes = instance.currencies().filter { it != current.from },
                selected = null,
                onPick = {
                    instance.add(current.from, it)
                    picking = null
                },
            )
            else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton(
                    text = "+ ${strings.addPair}",
                    onClick = {
                        editing = false
                        picking = Picking.NewFrom
                    },
                    size = ButtonSize.Sm,
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.testTag("currency-converter:add"),
                )
                if (rows.isNotEmpty()) {
                    AppButton(
                        text = if (editing) strings.done else strings.edit,
                        onClick = { editing = !editing },
                        size = ButtonSize.Sm,
                        variant = ButtonVariant.Ghost,
                        modifier = Modifier.testTag("currency-converter:edit"),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        when (val current = state) {
            is RatesState.Ready -> Text(
                text = strings.source(current.rates.date, current.fromCache || !online, current.rates.credit),
                style = tokens.footnote,
                color = tokens.muted,
                modifier = Modifier.testTag("currency-converter:source"),
            )

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
    }
}

private enum class Side { From, To }

/** Which list of currencies is open, and what a tap in it will change. */
private sealed interface Picking {
    data class Row(val id: Int, val side: Side) : Picking
    data object NewFrom : Picking
    data class NewTo(val from: String) : Picking
}

@Composable
private fun PairCard(
    row: PairRow,
    result: Double?,
    ready: Boolean,
    editing: Boolean,
    strings: ConverterStrings,
    onAmount: (String) -> Unit,
    onFrom: () -> Unit,
    onTo: () -> Unit,
    onSwap: () -> Unit,
    onRemove: () -> Unit,
) {
    val tokens = AppTheme
    val tag = "currency-converter:pair-${row.from}-${row.to}"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radius))
            .background(tokens.background)
            .testTag(tag)
            .padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The field fills whatever it is given, so it is given only what the chips leave.
            Box(modifier = Modifier.weight(1f)) {
                AppTextField(
                    value = row.amount,
                    onValueChange = onAmount,
                    placeholder = "0",
                    textStyle = tokens.header,
                    modifier = Modifier.testTag("$tag:amount"),
                )
            }
            CurrencyChip(row.from, "$tag:from", onFrom)
            AppButton(
                text = "⇄",
                onClick = onSwap,
                size = ButtonSize.Sm,
                variant = ButtonVariant.Ghost,
                modifier = Modifier.testTag("$tag:swap"),
            )
            CurrencyChip(row.to, "$tag:to", onTo)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (ready && result != null) "${formatMoney(result)} ${row.to}" else "—",
                style = tokens.title,
                color = tokens.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("$tag:result"),
            )
            if (editing) {
                AppButton(
                    text = strings.removePair,
                    onClick = onRemove,
                    size = ButtonSize.Sm,
                    variant = ButtonVariant.Destructive,
                    modifier = Modifier.testTag("$tag:remove"),
                )
            }
        }
    }
}

@Composable
private fun CurrencyChip(code: String, tag: String, onClick: () -> Unit) {
    val tokens = AppTheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(tokens.radius))
            .background(tokens.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text = code, style = tokens.label, color = tokens.foreground)
    }
}

@Composable
private fun CurrencyPicker(
    title: String,
    codes: List<String>,
    selected: String?,
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
                Box(modifier = Modifier.testTag("currency-converter:pick-$code")) {
                    ChoiceRow(label = code, selected = code == selected, onClick = { onPick(code) })
                }
            }
        }
    }
}

/**
 * Two decimals, by hand — and, below a cent, enough of them to keep three significant digits:
 * one dong is 0.0000385 dollars, and "0.00" would say the rate is nothing.
 *
 * `commonMain` has no number formatter, and the same string has to come out on all three targets
 * — the desktop test suite included. The calculator's `format` solves the same problem for the
 * same reason; this one is the money-shaped version of it.
 */
internal fun formatMoney(value: Double): String {
    if (!value.isFinite()) return "—"
    if (value != 0.0 && abs(value) < 0.005) return formatSmall(value)
    val rounded = round(abs(value) * 100) / 100
    val whole = rounded.toLong()
    val cents = round((rounded - whole) * 100).toLong()
    val sign = if (value < 0) "-" else ""
    return "$sign$whole.${cents.toString().padStart(2, '0')}"
}

private fun formatSmall(value: Double): String {
    var decimals = 2
    var scaled = abs(value) * 100
    while (scaled < 100 && decimals < 12) {
        scaled *= 10
        decimals++
    }
    val digits = round(scaled).toLong().toString().padStart(decimals, '0').trimEnd('0')
    val sign = if (value < 0) "-" else ""
    return if (digits.isEmpty()) "0.00" else "${sign}0.$digits"
}
