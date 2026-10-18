package cx.m42.apps.dates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
 * Two fields, a switch, and the answer under them.
 *
 * The answer is recomputed on every keystroke rather than behind a "Calculate" button: there is
 * nothing expensive to wait for, and a figure that follows the typing is its own confirmation that
 * the date was read.
 */
@Composable
internal fun DatesScreen(instance: DatesInstance) {
    val state by instance.state.collectAsState()
    val strings = LocalAppRuntime.current.locale.pick(DatesStringTables, DatesStringsEn)
    val tokens = AppTheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
    ) {
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
            label = strings.inclusive,
            checked = state.inclusive,
            onCheckedChange = instance::setInclusive,
            modifier = Modifier.padding(horizontal = 4.dp).testTag("$TAG:inclusive"),
        )
        Spacer(Modifier.height(12.dp))

        val span = state.span
        if (span == null) {
            Text(
                text = strings.enterBoth,
                style = tokens.body,
                color = tokens.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(20.dp).testTag("$TAG:empty"),
            )
            return@Column
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
            ValueRow(strings.inWeeks, weeksText(span, strings), "weeks")
            RowDivider()
            ValueRow(strings.inMonths, monthsText(span, strings), "ymd")
            RowDivider()
            ValueRow(strings.weekdays, strings.days(span.weekdays), "weekdays")
            RowDivider()
            ValueRow(strings.weekends, strings.days(span.weekendDays), "weekends")
        }
        Text(
            text = strings.holidaysHint,
            style = tokens.footnote,
            color = tokens.muted,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
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
