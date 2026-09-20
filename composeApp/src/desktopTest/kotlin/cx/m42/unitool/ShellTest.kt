package cx.m42.unitool

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

/**
 * Unitool as a user meets it: a home screen of apps, a drawer, and an app that opens inside the
 * host's frame.
 *
 * These are the tests that say the *product* still works. The drawer assertions are the ones that
 * came across from the starter unchanged — same Russian strings, same gestures — because the
 * migration was supposed to leave the app looking exactly as it did.
 */
@OptIn(ExperimentalTestApi::class)
class ShellTest : UnitoolTest() {

    @Test
    fun homeListsEveryVisibleAppAndHidesTheBenchApp() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        onNodeWithTag(CALCULATOR_TILE).assertIsDisplayed()
        onNodeWithTag(CONVERTER_TILE).assertIsDisplayed()
        // Hidden until a promo code or a QR payload unlocks it (D8). A tile saying "you cannot
        // have this" would be an advertisement, so there is no tile at all.
        onNodeWithTag("home:tile-test-app").assertDoesNotExist()
    }

    @Test
    fun tappingATileOpensTheAppInsideTheHostsFrame() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        onNodeWithTag(CALCULATOR_TILE).performClick()
        waitForIdle()

        // The root tag is put on by the container, not by the app (D39) — which is what makes it
        // present for an app the host has never seen.
        onNodeWithTag("calculator:root").assertIsDisplayed()
        onNodeWithTag("calculator:key-=").assertIsDisplayed()
        // The frame is still the host's: the burger is there, over the app.
        onNodeWithContentDescription("Открыть меню").assertIsDisplayed()
    }

    @Test
    fun swipingInFromTheLeftEdgeOpensTheDrawer() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        // "Меню" is the drawer's own heading, so it exists only while the drawer does.
        onNodeWithText("Меню").assertDoesNotExist()

        onRoot().performTouchInput {
            // Hard against the left edge: that strip is the only part of a closed screen that
            // belongs to the drawer.
            swipeRight(startX = 1f, endX = centerX)
        }
        waitForIdle()

        onNodeWithText("Меню").assertIsDisplayed()
    }

    @Test
    fun theDrawerListsTheAppsAndTheBuildItWasMadeFrom() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()

        onNodeWithText("Все приложения").assertIsDisplayed()
        // Twice: the tile on the home screen behind the drawer, and the drawer's own row. The
        // menu lists every visible app from the registry, so the two always agree.
        onAllNodesWithText("Калькулятор").assertCountEquals(2)
        onAllNodesWithText("Конвертер валют").assertCountEquals(2)
        onNodeWithText("Версия ${BuildInfo.VERSION}").assertIsDisplayed()
    }

    @Test
    fun theCogOpensSettingsAndTheDrawerLetsGoOnTheWayThrough() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        onNodeWithContentDescription("Настройки").performClick()
        waitForIdle()

        onNodeWithText("Язык").assertIsDisplayed()
        // The drawer let go rather than being left standing over the page it opened.
        onNodeWithText("Меню").assertDoesNotExist()
    }

    @Test
    fun choosingEnglishRepaintsTheHostAndTheApps() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        onNodeWithContentDescription("Настройки").performClick()
        waitForIdle()
        onNodeWithText("English").performClick()
        waitForIdle()

        // The host's own table turned over…
        onNodeWithText("Settings").assertIsDisplayed()
        onNodeWithContentDescription("Back").performClick()
        waitForIdle()
        // …and so did the app's, which is a different table in a different module (D11).
        onNodeWithText("Calculator").assertIsDisplayed()
    }

    @Test
    fun backFromAnAppReturnsToAllApps() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        onNodeWithTag(CALCULATOR_TILE).performClick()
        waitForIdle()
        onNodeWithContentDescription("Назад").performClick()
        waitForIdle()

        onNodeWithTag(CONVERTER_TILE).assertIsDisplayed()
    }

    @Test
    fun theConverterShowsARateAndSaysWhereItCameFrom() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        onNodeWithTag(CONVERTER_TILE).performClick()
        waitForIdle()

        onNodeWithTag("currency-converter:result").assertIsDisplayed()
        onNodeWithText("Курс ЕЦБ на 2026-09-19 · сеть").assertIsDisplayed()
    }

    @Test
    fun anAppsOwnBlockAppearsOnTheHostsSettingsPage() = runComposeUiTest {
        // `ctx.settingsSection` (03): the host draws the page and the app's title, the app draws
        // what is inside, and neither knows what the other put there.
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        onNodeWithContentDescription("Настройки").performClick()
        waitForIdle()

        onNodeWithTag("settings:section-currency-converter").assertExists()
        onNodeWithTag("currency-converter:settings").assertExists()
        // Under the app's own title, which comes from its manifest rather than from the host.
        onNodeWithText("Конвертер валют").assertExists()
    }

    @Test
    fun anOfflineConverterSaysSoWithoutMakingARequest() = runComposeUiTest {
        // D32: "offline" comes from the connectivity signal, not from a failed request — the user
        // is told before waiting out a fifteen-second timeout.
        val network = offlineNetwork()
        setContent { UnitoolApp(testSuperizer(network = network)) }
        waitForIdle()

        onNodeWithTag(CONVERTER_TILE).performClick()
        waitForIdle()

        onNodeWithText("Офлайн, и в кэше пока пусто").assertIsDisplayed()
        kotlin.test.assertEquals(emptyList(), network.calls)
    }
}
