package cx.m42.apps.dates

import androidx.compose.runtime.Composable
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.runtime.InstanceRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The two tools on the screen: the days between two dates, and a date plus or minus an amount. */
internal enum class DatesMode { Difference, Shift }

/** The fields as typed, and the switches. What they mean is derived, never stored. */
internal data class DatesState(
    val mode: DatesMode = DatesMode.Difference,
    val start: String = "",
    val end: String = "",
    val includeStart: Boolean = true,
    val includeEnd: Boolean = false,
    val base: String = "",
    val subtract: Boolean = false,
    val amount: String = "",
    val unit: ShiftUnit = ShiftUnit.Days,
    val countStart: Boolean = false,
) {
    val startDate: CalendarDate? get() = CalendarDate.parse(start)
    val endDate: CalendarDate? get() = CalendarDate.parse(end)
    val baseDate: CalendarDate? get() = CalendarDate.parse(base)

    /** Digits only and no more than five of them: 99 999 years is already past any calendar's use. */
    val amountValue: Int? get() = amount.trim().takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isDigit) }?.toInt()

    val span: DateSpan?
        get() {
            val from = startDate ?: return null
            val to = endDate ?: return null
            return DateSpan.between(from, to, includeStart, includeEnd)
        }

    val shifted: CalendarDate?
        get() {
            val from = baseDate ?: return null
            val n = amountValue ?: return null
            return from.shift(if (subtract) -n else n, unit, countStart)
        }
}

/**
 * One open calculator of dates.
 *
 * The fields are the session (D16): `saveState` carries them across a process death, and a fresh
 * launch starts from today. The boundary switches are a habit, so they live in `runtime.storage`
 * and are still set next week.
 */
internal class DatesInstance(
    private val runtime: InstanceRuntime,
    config: DatesConfig,
    private val utcOffset: (Long) -> Long,
) : AppInstance() {

    private val _state = MutableStateFlow(
        DatesState(start = format(today()), base = format(today()), includeEnd = config.inclusive),
    )
    val state: StateFlow<DatesState> get() = _state.asStateFlow()

    override suspend fun onLaunch() {
        val storage = runtime.storage
        var current = _state.value
        storage.get(KEY_INCLUSIVE)?.let { current = current.copy(includeEnd = it == "true") }
        storage.get(KEY_INCLUDE_START)?.let { current = current.copy(includeStart = it == "true") }
        storage.get(KEY_COUNT_START)?.let { current = current.copy(countStart = it == "true") }
        _state.value = current
        runtime.analytics.screen(DatesApp.ID)
    }

    override fun saveState(): JsonObject = buildJsonObject {
        val current = _state.value
        put("mode", JsonPrimitive(current.mode.name))
        put("start", JsonPrimitive(current.start))
        put("end", JsonPrimitive(current.end))
        put("includeStart", JsonPrimitive(current.includeStart))
        put("inclusive", JsonPrimitive(current.includeEnd))
        put("base", JsonPrimitive(current.base))
        put("subtract", JsonPrimitive(current.subtract))
        put("amount", JsonPrimitive(current.amount))
        put("unit", JsonPrimitive(current.unit.name))
        put("countStart", JsonPrimitive(current.countStart))
    }

    override fun restore(state: JsonObject) {
        val current = _state.value
        fun text(key: String) = state[key]?.jsonPrimitive?.content
        fun flag(key: String) = text(key)?.let { it == "true" }
        _state.value = DatesState(
            mode = DatesMode.entries.firstOrNull { it.name == text("mode") } ?: current.mode,
            start = text("start") ?: current.start,
            end = text("end") ?: current.end,
            includeStart = flag("includeStart") ?: current.includeStart,
            // Named before there were two switches; it has always meant the last day.
            includeEnd = flag("inclusive") ?: current.includeEnd,
            base = text("base") ?: current.base,
            subtract = flag("subtract") ?: current.subtract,
            amount = text("amount") ?: current.amount,
            unit = ShiftUnit.entries.firstOrNull { it.name == text("unit") } ?: current.unit,
            countStart = flag("countStart") ?: current.countStart,
        )
    }

    private fun update(change: DatesState.() -> DatesState) {
        _state.value = _state.value.change()
    }

    fun setMode(mode: DatesMode) = update { copy(mode = mode) }

    fun setStart(text: String) = update { copy(start = text) }

    fun setEnd(text: String) = update { copy(end = text) }

    fun startToday() = setStart(format(today()))

    fun endToday() = setEnd(format(today()))

    fun setIncludeStart(value: Boolean) {
        update { copy(includeStart = value) }
        remember(KEY_INCLUDE_START, value)
    }

    fun setIncludeEnd(value: Boolean) {
        update { copy(includeEnd = value) }
        remember(KEY_INCLUSIVE, value)
    }

    fun setBase(text: String) = update { copy(base = text) }

    fun baseToday() = setBase(format(today()))

    fun setSubtract(value: Boolean) = update { copy(subtract = value) }

    /** Whatever is typed is kept, so the field never fights the keyboard; [DatesState.amountValue] decides. */
    fun setAmount(text: String) = update { copy(amount = text) }

    fun setUnit(unit: ShiftUnit) = update { copy(unit = unit) }

    fun setCountStart(value: Boolean) {
        update { copy(countStart = value) }
        remember(KEY_COUNT_START, value)
    }

    /** The answer becomes the next question: plus 60 days, then minus 90 from there. */
    fun continueFromResult() {
        val result = _state.value.shifted ?: return
        update { copy(base = format(result)) }
    }

    private fun remember(key: String, value: Boolean) {
        runtime.scope.launch { runtime.storage.set(key, value.toString()) }
    }

    private fun today(): CalendarDate {
        val now = runtime.clock.now()
        return CalendarDate.today(now, utcOffset(now))
    }

    @Composable
    override fun Content() {
        DatesScreen(this)
    }

    companion object {
        /** The last-day switch; the key predates the first-day one. */
        const val KEY_INCLUSIVE = "inclusive"
        const val KEY_INCLUDE_START = "includeStart"
        const val KEY_COUNT_START = "countStart"

        /** Day first with dots — what the field is filled with, in either language. */
        fun format(date: CalendarDate): String =
            "${date.day.toString().padStart(2, '0')}.${date.month.toString().padStart(2, '0')}.${date.year}"
    }
}
