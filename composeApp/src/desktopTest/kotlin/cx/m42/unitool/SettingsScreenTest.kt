package cx.m42.unitool

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import cx.m42.unitool.data.AppSettings
import cx.m42.unitool.i18n.Lang
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Choosing a language repaints the whole app, and the choice is remembered on this device. */
class SettingsScreenTest : RussianUiTest() {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun choosingEnglishRepaintsTheAppAndIsRemembered() = runComposeUiTest {
        setContent { App() }
        waitForIdle()

        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        onNodeWithContentDescription("Настройки").performClick()
        waitForIdle()

        onNodeWithText("English").performClick()
        waitForIdle()

        // The page title came from the same table as everything else, so it turned over too.
        onNodeWithText("Settings").assertIsDisplayed()
        assertEquals(Lang.En.tag, AppSettings.language)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun turningHapticsOffIsWrittenStraightAway() = runComposeUiTest {
        setContent { App() }
        waitForIdle()

        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        onNodeWithContentDescription("Настройки").performClick()
        waitForIdle()

        // Both the section heading and the row carry the word; the row is the one that toggles.
        onNodeWithContentDescription("Вибрация").performClick()
        waitForIdle()

        assertFalse(AppSettings.haptics)
    }
}
