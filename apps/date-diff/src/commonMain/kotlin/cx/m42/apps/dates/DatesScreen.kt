package cx.m42.apps.dates

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import cx.m42.superizer.ui.components.RowDivider
import cx.m42.superizer.ui.components.Section
import cx.m42.superizer.ui.components.ToggleRow

private const val TAG = DatesApp.ID

/**
 * Two tools on tabs: the difference between two dates, and a date plus or minus an amount.
 *
 * The answer is recomputed on every keystroke rather than behind a "Calculate" button: there is
 * nothing expensive to wait for, and a figure that follows the typing is its own confirmation that
 * the date was read.
 */
@Composable
internal fun DatesScreen(instance: DatesInstance) {
    val state by instance.state.collectAsState()
    val strings = LocalAppRuntime.current.locale.pick(DatesStringTables, DatesStringsEn)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    ) {
        Box(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp)) {
            Segmented(
                options = listOf(DatesMode.Difference to strings.tabDifference, DatesMode.Shift to strings.tabShift),
                selected = state.mode,
                onSelect = instance::setMode,
                tag = { "$TAG:tab-${it.name.lowercase()}" },
            )
        }
        when (state.mode) {
            DatesMode.Difference -> DifferencePage(instance, state, strings)
            DatesMode.Shift -> ShiftPage(instance, state, strings)
        }
    }
}

@Composable
private fun DifferencePage(instance: DatesInstance, state: DatesState, strings: DatesStrings) {
    val tokens = AppTheme
    DateField(
        label = strings.start,
        text = state.start,
        date = state.startDate,
        strings = strings,
        onChange = instance::setStart,
        onToday = instance::startToday,
        tag = "start",
    )
    DateField(
        label = strings.end,
        text = state.end,
        date = state.endDate,
        strings = strings,
        onChange = instance::setEnd,
        onToday = instance::endToday,
        tag = "end",
    )
    ToggleRow(
        label = strings.includeStart,
        checked = state.includeStart,
        onCheckedChange = instance::setIncludeStart,
        modifier = Modifier.padding(horizontal = 4.dp).testTag("$TAG:include-start"),
    )
    ToggleRow(
        label = strings.includeEnd,
        checked = state.includeEnd,
        onCheckedChange = instance::setIncludeEnd,
        modifier = Modifier.padding(horizontal = 4.dp).testTag("$TAG:include-end"),
    )
    Spacer(Modifier.height(12.dp))

    val span = state.span
    if (span == null) {
        Empty(strings.enterBoth)
        return
    }

    Text(
        text = strings.days(span.days),
        style = tokens.title,
        color = tokens.foreground,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("$TAG:days"),
    )
    if (span.reversed) {
        Text(
            text = strings.reversed,
            style = tokens.footnote,
            color = tokens.warning,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp)
                .testTag("$TAG:reversed"),
        )
    }

    Section(title = strings.result, hint = null) {
        // Which days went into the count, in plain dates: the switches above are easy to misread,
        // the dates are not.
        val counted = span.counted
        ValueRow(
            strings.countedDays,
            if (counted == null) strings.noDayCounted
            else if (counted.first == counted.second) DatesInstance.format(counted.first)
            else "${DatesInstance.format(counted.first)} – ${DatesInstance.format(counted.second)}",
            "counted",
        )
        RowDivider()
        ValueRow(strings.inWeeks, weeksText(span, strings), "weeks")
        RowDivider()
        ValueRow(strings.inMonths, monthsText(span, strings), "ymd")
        RowDivider()
        ValueRow(strings.weekdays, strings.days(span.weekdays), "weekdays")
        RowDivider()
        ValueRow(strings.weekends, strings.days(span.weekendDays), "weekends")
    }
    Footnote(strings.holidaysHint)
}

/** Amounts a person reaches for most: a month, two, a quarter, half a year, a year of days. */
private val QuickAmounts = listOf(30, 60, 90, 180, 365)

@Composable
private fun ShiftPage(instance: DatesInstance, state: DatesState, strings: DatesStrings) {
    val tokens = AppTheme
    DateField(
        label = strings.base,
        text = state.base,
        date = state.baseDate,
        strings = strings,
        onChange = instance::setBase,
        onToday = instance::baseToday,
        tag = "base",
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Segmented(
            options = listOf(false to strings.add, true to strings.subtract),
            selected = state.subtract,
            onSelect = instance::setSubtract,
            tag = { if (it) "$TAG:subtract" else "$TAG:add" },
        )
        Spacer(Modifier.height(12.dp))
        Text(text = strings.amount, style = tokens.label, color = tokens.muted, modifier = Modifier.padding(bottom = 4.dp))
        AppTextField(
            value = state.amount,
            onValueChange = instance::setAmount,
            placeholder = "60",
            label = strings.amount,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            modifier = Modifier.testTag("$TAG:amount"),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuickAmounts.forEach { n ->
                AppButton(
                    text = n.toString(),
                    onClick = { instance.setAmount(n.toString()) },
                    variant = if (state.amountValue == n) ButtonVariant.Default else ButtonVariant.Secondary,
                    size = ButtonSize.Sm,
                    modifier = Modifier.weight(1f).testTag("$TAG:amount-$n"),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Segmented(
            options = ShiftUnit.entries.map { it to strings.unit(it) },
            selected = state.unit,
            onSelect = instance::setUnit,
            tag = { "$TAG:unit-${it.name.lowercase()}" },
        )
    }
    ToggleRow(
        label = strings.countStart,
        checked = state.countStart,
        onCheckedChange = instance::setCountStart,
        modifier = Modifier.padding(horizontal = 4.dp).testTag("$TAG:count-start"),
    )
    Spacer(Modifier.height(12.dp))

    val base = state.baseDate
    val result = state.shifted
    if (base == null || result == null) {
        if (state.amount.isNotBlank() && state.amountValue == null) {
            Text(
                text = strings.notAnAmount,
                style = tokens.footnote,
                color = tokens.danger,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("$TAG:amount-error"),
            )
        }
        Empty(strings.enterAmount)
        return
    }

    Text(
        text = strings.longDate(result),
        style = tokens.title,
        color = tokens.foreground,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp).testTag("$TAG:result"),
    )
    Text(
        text = "${DatesInstance.format(result)} · ${strings.distance(result.epochDay - base.epochDay)}",
        style = tokens.body,
        color = tokens.muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).testTag("$TAG:distance"),
    )
    Spacer(Modifier.height(8.dp))
    AppButton(
        text = strings.continueFrom,
        onClick = instance::continueFromResult,
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Default,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("$TAG:continue"),
    )
    if (state.unit == ShiftUnit.Weekdays) Footnote(strings.weekdaysHint)
}

@Composable
private fun Empty(text: String) {
    Text(
        text = text,
        style = AppTheme.body,
        color = AppTheme.muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(20.dp).testTag("$TAG:empty"),
    )
}

@Composable
private fun Footnote(text: String) {
    Text(
        text = text,
        style = AppTheme.footnote,
        color = AppTheme.muted,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

/** Tabs, and any other one-of-few choice: the same pill row the 2FA app draws for its options. */
@Composable
private fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    tag: (T) -> String,
) {
    val tokens = AppTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tokens.border)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) tokens.background else tokens.border)
                    .clickable(role = Role.RadioButton) { onSelect(value) }
                    .testTag(tag(value))
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = tokens.label,
                    color = if (on) tokens.foreground else tokens.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "4 недели 2 дня", or just "4 недели" — a zero tail is noise. */
internal fun weeksText(span: DateSpan, strings: DatesStrings): String = when {
    span.weeks == 0L -> strings.days(span.restOfWeek.toLong())
    span.restOfWeek == 0 -> strings.weeks(span.weeks)
    else -> "${strings.weeks(span.weeks)} ${strings.days(span.restOfWeek.toLong())}"
}

/** "1 год 2 месяца 3 дня", leaving out the parts that are zero; "0 дней" when all are. */
internal fun monthsText(span: DateSpan, strings: DatesStrings): String {
    val parts = buildList {
        if (span.years > 0) add(strings.years(span.years))
        if (span.months > 0) add(strings.months(span.months))
        if (span.restDays > 0 || isEmpty()) add(strings.days(span.restDays.toLong()))
    }
    return parts.joinToString(" ")
}

/**
 * A label, the field, a Today button, and under them the date as it was read — or why it was not.
 * The echo is what makes a lenient parser safe: whatever was guessed is in plain words on screen.
 */
@Composable
private fun DateField(
    label: String,
    text: String,
    date: CalendarDate?,
    strings: DatesStrings,
    onChange: (String) -> Unit,
    onToday: () -> Unit,
    tag: String,
) {
    val tokens = AppTheme
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(text = label, style = tokens.label, color = tokens.muted, modifier = Modifier.padding(bottom = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                AppTextField(
                    value = text,
                    onValueChange = onChange,
                    placeholder = strings.placeholder,
                    label = label,
                    // Digits with a separator key; eight bare digits work too, for a keypad with none.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    modifier = Modifier.testTag("$TAG:$tag"),
                )
            }
            AppButton(
                text = strings.today,
                onClick = onToday,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("$TAG:$tag-today"),
            )
        }
        val echo = when {
            date != null -> strings.longDate(date)
            text.isNotBlank() -> strings.notADate
            else -> null
        }
        if (echo != null) {
            Text(
                text = echo,
                style = tokens.footnote,
                color = if (date != null) tokens.muted else tokens.danger,
                modifier = Modifier.padding(top = 4.dp).testTag("$TAG:$tag-echo"),
            )
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String, tag: String) {
    val tokens = AppTheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = tokens.body, color = tokens.muted, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = tokens.body,
            color = tokens.foreground,
            textAlign = TextAlign.End,
            modifier = Modifier.testTag("$TAG:$tag"),
        )
    }
}
