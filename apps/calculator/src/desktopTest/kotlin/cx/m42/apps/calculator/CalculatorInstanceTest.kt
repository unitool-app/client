package cx.m42.apps.calculator

import cx.m42.superizer.app.AppConfig
import cx.m42.superizer.testing.runAppTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The app as a host runs it: launched on fakes, composed, tapped.
 *
 * No `Superizer`, no registry, no shell — `runAppTest` is the whole harness (04). An app that
 * needed a host to be testable would be an app that cannot ship on its own, so this file is
 * evidence as much as it is a test.
 */
class CalculatorInstanceTest {

    private fun config(json: String) = AppConfig(Json.parseToJsonElement(json) as JsonObject)

    @Test
    fun tappingKeysMovesTheDisplay() = runAppTest(CalculatorApp()) {
        click("calculator:key-7")
        click("calculator:key-×")
        click("calculator:key-6")
        click("calculator:key-=")
        idle()

        // By tag, not by text: the display's separator is a comma in Russian and a full stop in
        // English, and a test that cared would be testing the language (D39).
        assertEquals("42", (instance as CalculatorInstance).state.value.entry)
    }

    @Test
    fun theScientificRowIsAbsentByDefaultAndPresentWhenTheConfigAsks() {
        runAppTest(CalculatorApp()) {
            assertAbsent("calculator:key-√")
        }
        runAppTest(CalculatorApp(), config("""{"mode":"scientific"}""")) {
            assertShown("calculator:key-√")
        }
    }

    @Test
    fun theLastResultOutlivesTheSessionWhileTheSumBeingTypedDoesNot() = runAppTest(CalculatorApp()) {
        click("calculator:key-8")
        click("calculator:key-+")
        click("calculator:key-1")
        click("calculator:key-=")
        idle()

        // `storage` is the app's own data and survives anything short of a reset (D35)…
        assertEquals("9", runtime.storage.entries[CalculatorInstance.KEY_LAST])
        // …while the snapshot is the session, and the host is what holds it (D16).
        val snapshot = instance.saveState()
        assertTrue(snapshot != null && snapshot.containsKey("entry"))
    }

    @Test
    fun dividingByZeroIsTheOneThingWorthReporting() = runAppTest(CalculatorApp()) {
        listOf("5", "÷", "0", "=").forEach { key -> click("calculator:key-$key") }
        idle()

        // One screen view at launch and one error. Analytics that reported every key press would
        // be analytics nobody reads.
        assertEquals(listOf("calculator"), runtime.analytics.screens)
        assertEquals(listOf("error"), runtime.analytics.events.map { it.first })
    }

    @Test
    fun aHalfTypedSumComesBackAfterTheProcessDies() {
        var saved: JsonObject? = null
        runAppTest(CalculatorApp()) {
            click("calculator:key-1")
            click("calculator:key-2")
            click("calculator:key-+")
            idle()
            saved = instance.saveState()
        }
        runAppTest(CalculatorApp(), restore = saved) {
            assertEquals("12", (instance as CalculatorInstance).state.value.entry)
            assertEquals("12 +", (instance as CalculatorInstance).state.value.ribbon)
        }
    }
}
