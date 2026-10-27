package cx.m42.apps.dates

import cx.m42.superizer.app.AppConfig
import cx.m42.superizer.testing.runAppTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class DatesInstanceTest {

    /** Moscow, pinned: the fake clock's 2023-11-14 22:13 UTC is already the 15th there. */
    private fun app() = DatesApp { 3 * 3_600_000L }

    private fun config(json: String) = AppConfig(Json.parseToJsonElement(json) as JsonObject)

    @Test
    fun startsAtTodayAndWaitsForTheEnd() = runAppTest(app()) {
        val instance = instance as DatesInstance
        assertEquals("15.11.2023", instance.state.value.start)
        assertShown("date-diff:empty")
        assertEquals(listOf("date-diff"), runtime.analytics.screens)
    }

    @Test
    fun typingTheEndShowsTheDifference() = runAppTest(app()) {
        type("date-diff:end", "25.11.2023")
        assertShown("date-diff:days")
        val span = (instance as DatesInstance).state.value.span!!
        assertEquals(10, span.days)
        assertEquals(8, span.weekdays)
        assertShown("date-diff:end-echo")
    }

    @Test
    fun aWrongDateSaysSoUnderTheField() = runAppTest(app()) {
        type("date-diff:end", "31.02.2023")
        assertShown("date-diff:end-echo")
        assertShown("date-diff:empty")
    }

    @Test
    fun theSwitchesAreRememberedAndTheConfigIsOnlyTheDefault() {
        runAppTest(app(), config("""{"inclusive":true}""")) {
            assertEquals(true, (instance as DatesInstance).state.value.includeEnd)
            click("date-diff:include-end")
            click("date-diff:include-start")
            assertEquals("false", runtime.storage.entries[DatesInstance.KEY_INCLUSIVE])
            assertEquals("false", runtime.storage.entries[DatesInstance.KEY_INCLUDE_START])
        }
        runAppTest(app(), config("""{"inclusive":true}"""), prepare = {
            it.storage.entries[DatesInstance.KEY_INCLUSIVE] = "false"
            it.storage.entries[DatesInstance.KEY_INCLUDE_START] = "false"
        }) {
            val state = (instance as DatesInstance).state.value
            assertEquals(false, state.includeEnd)
            assertEquals(false, state.includeStart)
        }
    }

    @Test
    fun plusSixtyDaysThenMinusNinetyFromThere() = runAppTest(app()) {
        val instance = instance as DatesInstance
        click("date-diff:tab-shift")
        assertShown("date-diff:empty")
        click("date-diff:amount-60")
        assertEquals(CalendarDate(2024, 1, 14), instance.state.value.shifted)
        assertShown("date-diff:result")
        click("date-diff:continue")
        assertEquals("14.01.2024", instance.state.value.base)
        click("date-diff:subtract")
        click("date-diff:amount-90")
        assertEquals(CalendarDate(2023, 10, 16), instance.state.value.shifted)
    }

    @Test
    fun aBadAmountSaysSo() = runAppTest(app()) {
        click("date-diff:tab-shift")
        type("date-diff:amount", "6O")
        assertShown("date-diff:amount-error")
        assertEquals(null, (instance as DatesInstance).state.value.shifted)
    }

    @Test
    fun theFieldsComeBackAfterTheProcessDies() {
        var saved: JsonObject? = null
        runAppTest(app()) {
            click("date-diff:end-today")
            type("date-diff:start", "")
            saved = instance.saveState()
        }
        runAppTest(app(), restore = saved) {
            val state = (instance as DatesInstance).state.value
            assertEquals("15.11.2023", state.end)
            assertEquals(0, state.span!!.days)
        }
        runAppTest(app()) {
            click("date-diff:tab-shift")
            click("date-diff:unit-months")
            type("date-diff:amount", "3")
            saved = instance.saveState()
        }
        runAppTest(app(), restore = saved) {
            val state = (instance as DatesInstance).state.value
            assertEquals(DatesMode.Shift, state.mode)
            assertEquals(CalendarDate(2024, 2, 15), state.shifted)
        }
    }
}
