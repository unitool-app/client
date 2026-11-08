package cx.m42.unitool.security

import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cx.m42.unitool.MainActivity
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * cr-restore on a real Android (.ai-notes/cr-restore D598, D613): the code opens it with no lock,
 * its window cannot be captured, a file the real `openssl enc` wrote opens to hidden text, and
 * leaving with that text on screen brings the curtain — or, on a phone with no screen lock, closes
 * the app instead.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CrRestoreOnDeviceTest {

    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes().decodeToString() }

    @Before
    fun clearTheScreen() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
    }

    @After
    fun noPinLeftBehind() {
        shell("locksettings clear --old $PIN")
    }

    private fun AndroidComposeUiTest<MainActivity>.openByCode() {
        waitForIdle()
        onNode(hasContentDescription("Open menu").or(hasContentDescription("Открыть меню"))).performClick()
        waitForIdle()
        onNode(hasContentDescription("Premium").or(hasContentDescription("Премиум"))).performClick()
        waitForIdle()
        onNodeWithTag("activate:have-code").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("activate:promo").performTextInput("[cr-restore]")
        onNodeWithTag("activate:apply").performScrollTo().performClick()
        waitForIdle()
    }

    private fun AndroidComposeUiTest<MainActivity>.decryptTheFixture() {
        onNodeWithTag("cr-restore:choose-openssl").performClick()
        waitForIdle()
        onNodeWithTag("cr-restore:paste-box").performTextInput(OPENSSL_FILE)
        onNodeWithTag("cr-restore:paste").performClick()
        waitForIdle()
        onNodeWithTag("cr-restore:password").performTextInput("pw-1")
        onNodeWithTag("cr-restore:decrypt").performScrollTo().performClick()
        waitUntil(timeoutMillis = 30_000) { runCatching { onNodeWithTag("cr-restore:start-over").assertExists() }.isSuccess }
        onNodeWithTag("cr-restore:hidden", useUnmergedTree = true).assertExists()
    }

    @Test
    fun theCodeOpensItWithNoLockAndTheWindowCannotBeCaptured() = runAndroidComposeUiTest<MainActivity>(testTimeout = SLOW) {
        openByCode()
        onNodeWithTag("cr-restore:choose-openssl").assertExists()
        assertTrue(activity!!.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0, "the window is secure")
        assertTrue(settles { blankCapture() }, "a screenshot shows nothing of it")
        decryptTheFixture()
        onNodeWithTag("cr-restore:reveal").performClick()
        waitForIdle()
        onNodeWithTag("cr-restore:text", useUnmergedTree = true).assertExists()
    }

    /**
     * Away and back the way a phone is: the screen goes off (the activity stops, the process goes
     * to the background), then on again and past the keyguard. Not HOME: an ATD emulator has no
     * launcher, and HOME there only pauses the activity.
     */
    private fun screenOffAndBack(pin: String?) {
        shell("input keyevent KEYCODE_SLEEP")
        Thread.sleep(3_000)
        shell("input keyevent KEYCODE_WAKEUP")
        Thread.sleep(1_000)
        shell("wm dismiss-keyguard")
        if (pin != null) {
            Thread.sleep(1_000)
            shell("input text $pin")
            shell("input keyevent KEYCODE_ENTER")
        }
        Thread.sleep(2_000)
    }

    @Test
    fun withAScreenLockLeavingWithTheTextOnScreenBringsTheCurtain() {
        // An ATD image has no lock screen at all ("The device does not support lock screen"): there
        // the curtain cannot come, and the other test is what such a phone does. Skipped, by name.
        shell("locksettings set-pin $PIN")
        val keyguard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.app.KeyguardManager::class.java)
        Assume.assumeTrue("SKIP: this device has no lock screen", keyguard.isDeviceSecure)
        curtainAfterLeaving()
    }

    private fun curtainAfterLeaving() = runAndroidComposeUiTest<MainActivity>(testTimeout = SLOW) {
        openByCode()
        decryptTheFixture()
        screenOffAndBack(PIN)
        waitUntil(timeoutMillis = 15_000) { runCatching { onNodeWithTag("shell:lock").assertExists() }.isSuccess }
        onNodeWithTag("cr-restore:hidden", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("cr-restore:text", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun withNoScreenLockLeavingClosesItAndWipesTheText() = runAndroidComposeUiTest<MainActivity>(testTimeout = SLOW) {
        shell("locksettings clear --old $PIN")
        openByCode()
        decryptTheFixture()
        screenOffAndBack(null)
        // Closed, and the shell is back Home: nothing of cr-restore on screen, the calculator's tile is.
        waitUntil(timeoutMillis = 15_000) { runCatching { onNodeWithTag("home:tile-calculator").assertExists() }.isSuccess }
        onNodeWithTag("cr-restore:start-over").assertDoesNotExist()
        onNodeWithTag("cr-restore:hidden", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag("shell:lock").assertDoesNotExist()
    }

    private fun blankCapture(): Boolean {
        val png = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p")).use { it.readBytes() }
        val shot = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return true
        val first = shot.getPixel(shot.width / 2, shot.height / 2)
        for (y in shot.height / 4 until shot.height * 3 / 4 step 8) {
            for (x in shot.width / 8 until shot.width * 7 / 8 step 8) {
                if (shot.getPixel(x, y) != first) return false
            }
        }
        return true
    }

    private fun settles(check: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(250)
        }
        return true
    }

    private companion object {
        const val PIN = "1357"

        /** A test emulator under nested virtualisation takes well over the default minute. */
        val SLOW = 5.minutes

        /** `printf 'Секрет с телефона ✓\n' | openssl enc -aes-256-cbc -pbkdf2 -pass pass:pw-1 -a`, OpenSSL 3.0.13. */
        const val OPENSSL_FILE = "U2FsdGVkX18vUFpuuBpncE5pzWaQ+nG1axd7/wTotwwo6n6dhmPihPRyhFTUzwCR\nEx8V3I6tHAEtnDbqirkqcQ=="
    }
}
