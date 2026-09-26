package cx.m42.unitool.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cx.m42.unitool.MainActivity
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I13 on a device (03 §1): while "Add a key" is open the window carries `FLAG_SECURE`, and the
 * system's screenshot of it shows nothing of it. Leaving the wizard takes the flag off again.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SecureWindowTest {

    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes().decodeToString() }

    /** The window the system has in front: what a capture that is not blank is a capture of. */
    private fun front(): String = shell("dumpsys window").lineSequence().firstOrNull { "mCurrentFocus" in it }?.trim().orEmpty()

    /**
     * The ssh tests before this one set a PIN, clear it and put the screen to sleep; a fresh emulator
     * then wakes to its swipe keyguard, and the capture is of that rather than of us. An ANR dialog
     * from System UI on a slow emulator does the same.
     */
    @Before
    fun clearTheScreen() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
    }

    @Test
    fun theImportWizardCannotBeCapturedAndSettingsCan() = runAndroidComposeUiTest<MainActivity> {
        waitForIdle()
        // Whatever the emulator's language, Unitool speaks one of its two.
        onNode(hasContentDescription("Open menu").or(hasContentDescription("Открыть меню"))).performClick()
        waitForIdle()
        onNode(hasContentDescription("Settings").or(hasContentDescription("Настройки"))).performClick()
        waitForIdle()
        onNodeWithTag("keys:add").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("host:key-import").assertExists()

        val flags = { activity!!.window.attributes.flags }
        assertTrue(flags() and WindowManager.LayoutParams.FLAG_SECURE != 0, "the wizard's window is secure")
        assertTrue(blank(screencap()), "a screenshot of the wizard shows nothing of it; in front: ${front()}")

        onNode(hasContentDescription("Back").or(hasContentDescription("Назад"))).performClick()
        waitForIdle()
        onNodeWithTag("settings:host:ssh-keys").assertExists()
        assertFalse(flags() and WindowManager.LayoutParams.FLAG_SECURE != 0, "Settings is not")
        assertFalse(blank(screencap()), "and Settings can be captured; in front: ${front()}")
    }

    /**
     * The system's `screencap`, which is what a screen recorder and `adb` see. Not
     * `UiAutomation.takeScreenshot`: the test harness is allowed secure layers, and would see
     * everything.
     */
    private fun screencap(): Bitmap? {
        val png = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p")).use { it.readBytes() }
        return BitmapFactory.decodeByteArray(png, 0, png.size)
    }

    /** The middle of the screen, away from the system bars, is one flat colour: what a secure layer becomes in a capture. */
    private fun blank(shot: Bitmap?): Boolean {
        shot ?: return false
        val first = shot.getPixel(shot.width / 2, shot.height / 2)
        val step = 8
        for (y in shot.height / 4 until shot.height * 3 / 4 step step) {
            for (x in shot.width / 8 until shot.width * 7 / 8 step step) {
                if (shot.getPixel(x, y) != first) return false
            }
        }
        return true
    }
}
