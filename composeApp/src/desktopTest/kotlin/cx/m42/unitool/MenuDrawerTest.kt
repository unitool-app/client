package cx.m42.unitool

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

/**
 * The drawer is opened and closed by dragging as well as by tapping, and it carries the build stamp
 * a bug report gets quoted from. It is the app's only navigation, so these are the tests that say
 * the starter still works.
 */
class MenuDrawerTest : RussianUiTest() {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun swipingInFromTheLeftEdgeOpensTheDrawer() = runComposeUiTest {
        setContent { App() }
        waitForIdle()

        // "Меню" is the drawer's own heading, so it exists only while the drawer does.
        onNodeWithText("Меню").assertDoesNotExist()

        onRoot().performTouchInput {
            // Starting hard against the left edge: that strip is the only part of a closed screen
            // that belongs to the drawer.
            swipeRight(startX = 1f, endX = centerX)
        }
        waitForIdle()

        onNodeWithText("Меню").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun swipingBackLeftClosesTheDrawer() = runComposeUiTest {
        setContent { App() }
        waitForIdle()

        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        onNodeWithText("Меню").assertIsDisplayed()

        onRoot().performTouchInput { swipeLeft() }
        waitForIdle()

        onNodeWithText("Меню").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aHalfOpenDrawerSettlesBackClosed() = runComposeUiTest {
        setContent { App() }
        waitForIdle()

        // A short, slow drag that neither crosses the halfway mark nor flicks: the drawer should
        // fall back rather than stay stranded part-way out.
        onRoot().performTouchInput {
            down(Offset(1f, centerY))
            moveTo(Offset(24f, centerY))
            up()
        }
        waitForIdle()

        onNodeWithText("Меню").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theDrawerShowsTheVersionAndCommitItWasBuiltFrom() = runComposeUiTest {
        setContent { App() }
        waitForIdle()

        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()

        onNodeWithText("Версия ${BuildInfo.VERSION}").assertIsDisplayed()
        // Substring match: the stamp appends a "*" when the tree was dirty at build time.
        onNodeWithText(BuildInfo.COMMIT, substring = true).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theEntryForTheScreenYouAreOnIsListedRatherThanHidden() = runComposeUiTest {
        setContent { App() }
        waitForIdle()

        // Only the screen's own title carries the word while the drawer is shut.
        onAllNodesWithText("Калькулятор").assertCountEquals(1)

        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()

        // Now the menu row says it too: the entry for where you already are is disabled, not
        // dropped, so the menu reads the same from every screen.
        onAllNodesWithText("Калькулятор").assertCountEquals(2)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theFooterCogOpensSettingsAndClosesTheDrawer() = runComposeUiTest {
        setContent { App() }
        waitForIdle()

        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()

        onNodeWithContentDescription("Настройки").performClick()
        waitForIdle()

        onNodeWithText("Язык").assertIsDisplayed()
        // The drawer let go on the way through, rather than being left over the page it opened.
        onNodeWithText("Меню").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun backFromSettingsReturnsToTheScreenItWasOpenedFrom() = runComposeUiTest {
        setContent { App() }
        waitForIdle()

        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        onNodeWithContentDescription("Настройки").performClick()
        waitForIdle()

        onNodeWithContentDescription("Назад").performClick()
        waitForIdle()

        onNodeWithText("Язык").assertDoesNotExist()
        // The calculator's own display is back: its keys are what only that screen has.
        onNodeWithText("=").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theDrawerLeadsBackToTheCalculatorFromSettings() = runComposeUiTest {
        setContent { App() }
        waitForIdle()

        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        onNodeWithContentDescription("Настройки").performClick()
        waitForIdle()

        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        onNodeWithText("Калькулятор").performClick()
        waitForIdle()

        onNodeWithText("=").assertIsDisplayed()
        onNodeWithText("Меню").assertDoesNotExist()
    }
}
