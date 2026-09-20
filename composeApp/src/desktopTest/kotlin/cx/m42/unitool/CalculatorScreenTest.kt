package cx.m42.unitool

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import cx.m42.unitool.i18n.AppLocale
import cx.m42.unitool.i18n.Lang
import kotlin.test.Test

/**
 * The keypad is wired to the arithmetic. The rules themselves are [CalculatorTest]'s business —
 * what is checked here is that a tap reaches them and that the answer reaches the display.
 */
class CalculatorScreenTest : RussianUiTest() {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tappingASumShowsItsAnswer() = runComposeUiTest {
        setContent { App() }
        waitForIdle()

        listOf("7", "×", "8", "=").forEach { key ->
            onNodeWithText(key).performClick()
        }
        waitForIdle()

        onNodeWithText("56").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun anImpossibleSumSaysSoInWords() = runComposeUiTest {
        setContent { App() }
        waitForIdle()

        listOf("5", "÷", "0", "=").forEach { key ->
            onNodeWithText(key).performClick()
        }
        waitForIdle()

        onNodeWithText("Ошибка").assertIsDisplayed()
    }

    /**
     * The decimal key and the display have to agree, and what they agree on is the language's own
     * mark — a Russian reader pressing «,» must not be shown a full stop. The arithmetic
     * underneath still works in ASCII; see [CalculatorTest], which types "1.5" throughout.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theDecimalKeyTypesTheMarkItIsLabelledWith() = runComposeUiTest {
        setContent { App() }
        waitForIdle()

        listOf("1", ",", "5").forEach { key ->
            onNodeWithText(key).performClick()
        }
        waitForIdle()

        onNodeWithText("1,5").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theSameKeyIsAFullStopInEnglish() = runComposeUiTest {
        // Overrides the Russian the base class pins: the point of the test is the other table.
        AppLocale.useForTesting(Lang.En)
        setContent { App() }
        waitForIdle()

        listOf("1", ".", "5").forEach { key ->
            onNodeWithText(key).performClick()
        }
        waitForIdle()

        onNodeWithText("1.5").assertIsDisplayed()
    }
}
