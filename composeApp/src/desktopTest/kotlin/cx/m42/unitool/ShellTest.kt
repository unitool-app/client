package cx.m42.unitool

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.v2.runComposeUiTest
import cx.m42.apps.testapp.TestApp
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun aFreshInstallHasTheCalculatorOnHomeAndNothingElse() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        // Home is chosen, not "everything" (D48): the host names what a new install starts with,
        // and that is the calculator, because that is what Unitool was.
        onNodeWithTag(CALCULATOR_TILE).assertIsDisplayed()
        onNodeWithTag(CONVERTER_TILE).assertDoesNotExist()
        // Hidden until a promo code or a QR payload unlocks it (D8). A tile saying "you cannot
        // have this" would be an advertisement, so there is no tile at all.
        onNodeWithTag("home:tile-test-app").assertDoesNotExist()
        onNodeWithTag("home:add").assertIsDisplayed()
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

        onNodeWithText("Главная").assertIsDisplayed()
        onNodeWithText("Все приложения").assertIsDisplayed()
        // Twice: the tile on the home screen behind the drawer, and the drawer's own row. The
        // menu lists what Home lists (D48), so the two always agree — and the converter, which is
        // not on Home yet, is in neither.
        onAllNodesWithText("Калькулятор").assertCountEquals(2)
        onAllNodesWithText("Конвертер валют").assertCountEquals(0)
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
    fun backFromAnAppReturnsHome() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        onNodeWithTag(CALCULATOR_TILE).performClick()
        waitForIdle()
        onNodeWithContentDescription("Назад").performClick()
        waitForIdle()

        onNodeWithTag(CALCULATOR_TILE).assertIsDisplayed()
    }

    @Test
    fun theConverterShowsARateAndSaysWhereItCameFrom() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        addFromCatalog("currency-converter")
        onNodeWithTag(CONVERTER_TILE).performClick()
        waitForIdle()

        onNodeWithTag("currency-converter:pair-USD-EUR:result").assertIsDisplayed()
        onNodeWithText("Курс от Exchange Rate API на 2026-09-19 · сеть").assertIsDisplayed()
    }

    /**
     * D48: Home is chosen. The catalog adds a tile, a long press takes it off, and the choice is
     * stored — a tile that came back on the next launch would make the gesture a lie.
     */
    @Test
    fun theCatalogAddsAnAppAndTheChoiceSurvivesARestart() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        addFromCatalog("currency-converter")
        onNodeWithTag(CONVERTER_TILE).assertIsDisplayed()
        onNodeWithTag(CALCULATOR_TILE).assertIsDisplayed()

        // A second host on the same preferences directory — which is what the next process is.
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()
        onNodeWithTag(CONVERTER_TILE).assertIsDisplayed()
    }

    @Test
    fun aLongPressTakesATileOffHomeAndTheCatalogStillOffersIt() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        onNodeWithTag(CALCULATOR_TILE).performTouchInput { longClick() }
        waitForIdle()

        onNodeWithTag(CALCULATOR_TILE).assertDoesNotExist()
        onNodeWithText("На главной пока пусто. Добавьте приложение из списка.").assertIsDisplayed()
        // Nothing was lost: the same gesture is one tap away from being undone.
        onNodeWithTag("home:add").performClick()
        waitForIdle()
        onNodeWithTag("catalog:row-calculator").performClick()
        waitForIdle()
        onNodeWithContentDescription("Назад").performClick()
        waitForIdle()
        onNodeWithTag(CALCULATOR_TILE).assertIsDisplayed()
    }

    @Test
    fun activatingTheHiddenAppPutsItOnHomeAndRemovingItKeepsItUnlocked() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        // Before the code: not on Home and not in the catalog either (D8).
        onNodeWithTag("home:add").performClick()
        waitForIdle()
        onNodeWithTag("catalog:row-test-app").assertDoesNotExist()
        onNodeWithContentDescription("Назад").performClick()
        waitForIdle()

        unlockBenchApp()
        openHome()
        onNodeWithTag("home:tile-test-app").assertIsDisplayed()

        onNodeWithTag("home:tile-test-app").performTouchInput { longClick() }
        waitForIdle()
        onNodeWithTag("home:tile-test-app").assertDoesNotExist()
        // The apps that were never touched are untouched: this is one tile leaving, not a reset.
        onNodeWithTag(CALCULATOR_TILE).assertIsDisplayed()

        // Unlocked is unlocked: the catalog lists it, and no second promo code is needed.
        onNodeWithTag("home:add").performClick()
        waitForIdle()
        onNodeWithTag("catalog:row-test-app").performScrollTo().assertIsDisplayed()
    }

    private fun ComposeUiTest.addFromCatalog(id: String) {
        onNodeWithTag("home:add").performClick()
        waitForIdle()
        onNodeWithTag("catalog:row-$id").performScrollTo().performClick()
        waitForIdle()
        onNodeWithContentDescription("Назад").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.unlockBenchApp() {
        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        onNodeWithContentDescription("Премиум").performClick()
        waitForIdle()
        onNodeWithTag("activate:have-code").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("activate:promo").performTextInput(TestApp.PROMO_CODE)
        onNodeWithTag("activate:apply").performScrollTo().performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.openHome() {
        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        onNodeWithText("Главная").performClick()
        waitForIdle()
    }

    @Test
    fun anOfflineConverterSaysSoWithoutMakingARequest() = runComposeUiTest {
        // D32: "offline" comes from the connectivity signal, not from a failed request — the user
        // is told before waiting out a fifteen-second timeout.
        val network = offlineNetwork()
        setContent { UnitoolApp(testSuperizer(network = network)) }
        waitForIdle()

        addFromCatalog("currency-converter")
        onNodeWithTag(CONVERTER_TILE).performClick()
        waitForIdle()

        onNodeWithText("Офлайн, и в кэше пока пусто").assertIsDisplayed()
        assertEquals(emptyList(), network.calls)
    }
}
