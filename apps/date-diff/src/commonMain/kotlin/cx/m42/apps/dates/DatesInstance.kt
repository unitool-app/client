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

/** The two fields as typed, and the switch. What they mean is derived, never stored. */
internal data class DatesState(
    val start: String = "",
    val end: String = "",
    val inclusive: Boolean = false,
) {
    val startDate: CalendarDate? get() = CalendarDate.parse(start)
    val endDate: CalendarDate? get() = CalendarDate.parse(end)

    val span: DateSpan?
        get() {
            val from = startDate ?: return null
            val to = endDate ?: return null
            return DateSpan.between(from, to, inclusive)
        }
}

/**
 * One open calculator of dates.
 *
 * The fields are the session (D16): `saveState` carries them across a process death, and a fresh
 * launch starts from today. The last-day switch is a habit, so it lives in `runtime.storage` and is
 * still set next week.
 */
internal class DatesInstance(
    private val runtime: InstanceRuntime,
    config: DatesConfig,
    private val utcOffset: (Long) -> Long,
) : AppInstance() {

    private val _state = MutableStateFlow(DatesState(start = format(today()), inclusive = config.inclusive))
    val state: StateFlow<DatesState> get() = _state.asStateFlow()

    override suspend fun onLaunch() {
        runtime.storage.get(KEY_INCLUSIVE)?.let { stored ->
            _state.value = _state.value.copy(inclusive = stored == "true")
        }
        runtime.analytics.screen(DatesApp.ID)
    }

    override fun saveState(): JsonObject = buildJsonObject {
        val current = _state.value
        put("start", JsonPrimitive(current.start))
        put("end", JsonPrimitive(current.end))
        put("inclusive", JsonPrimitive(current.inclusive))
    }

    override fun restore(state: JsonObject) {
        val current = _state.value
        _state.value = DatesState(
            start = state["start"]?.jsonPrimitive?.content ?: current.start,
            end = state["end"]?.jsonPrimitive?.content ?: current.end,
            inclusive = state["inclusive"]?.jsonPrimitive?.content?.let { it == "true" } ?: current.inclusive,
        )
    }

    fun setStart(text: String) {
        _state.value = _state.value.copy(start = text)
    }

    fun setEnd(text: String) {
        _state.value = _state.value.copy(end = text)
    }

    fun startToday() = setStart(format(today()))

    fun endToday() = setEnd(format(today()))

    fun setInclusive(value: Boolean) {
        _state.value = _state.value.copy(inclusive = value)
        runtime.scope.launch { runtime.storage.set(KEY_INCLUSIVE, value.toString()) }
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
        const val KEY_INCLUSIVE = "inclusive"

        /** Day first with dots — what the field is filled with, in either language. */
        fun format(date: CalendarDate): String =
            "${date.day.toString().padStart(2, '0')}.${date.month.toString().padStart(2, '0')}.${date.year}"
    }
}
