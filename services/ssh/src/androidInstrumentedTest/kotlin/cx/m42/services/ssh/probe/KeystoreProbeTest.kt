package cx.m42.services.ssh.probe

import android.app.KeyguardManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step 0 (ssh-new/04 §2, D160): the device answers P1–P8, and the answers go to logcat under
 * `KeystoreProbe`, one line each, for the table in 04 §2.
 *
 * `adb logcat -d -s KeystoreProbe` after `connectedDebugAndroidTest` reads them.
 *
 * A device with no screen lock gets PIN 1111 for the length of the run and loses it again at the
 * end — which is also the experiment of 01 §5.7, so the keys made under it are tried once more
 * afterwards. A device that already has a lock (a person's phone) keeps it untouched, and that one
 * experiment is skipped rather than done with somebody's own PIN.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreProbeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().decodeToString() }

    @Test
    fun probe() {
        shell("svc power stayon true")
        shell("input keyevent KEYCODE_WAKEUP")
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val hadLock = keyguard.isDeviceSecure
        if (!hadLock) log("setup", "set-pin: " + shell("locksettings set-pin 1111").trim())

        val probe = KeystoreProbe(context)
        val answers = try {
            probe.run(screenLockSet = keyguard.isDeviceSecure)
        } finally {
            if (!hadLock) log("setup", "clear: " + shell("locksettings clear --old 1111").trim())
        }
        val after = if (!hadLock) probe.afterLockRemoved(probe.lockedAliases) else emptyList<KeystoreProbe.Answer>().also { probe.deleteLocked() }

        (answers + after).forEach { log(it.code, "${it.verdict} — ${it.detail}") }
        val asked = answers.map { it.code }.toSet()
        assertTrue(listOf("P1", "P2", "P3", "P4", "P5", "P6", "P7").all { it in asked }, "every question answered: $asked")
    }

    /** One logical line, cut into pieces logcat will not truncate. */
    private fun log(code: String, text: String) {
        text.chunked(3000).forEachIndexed { i, part -> Log.i(TAG, "$code${if (i > 0) " (cont.)" else ""}: $part") }
    }

    private companion object {
        const val TAG = "KeystoreProbe"
    }
}
