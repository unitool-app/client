package cx.m42.unitool.security

import android.app.KeyguardManager
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cx.m42.services.ssh.SshKeyringHost
import cx.m42.services.ssh.android
import cx.m42.services.ssh.keyring.InMemoryRecordStore
import cx.m42.services.ssh.keyring.PromptText
import cx.m42.superizer.diagnostics.SelfTestCode
import cx.m42.superizer.host.SystemClock
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The self-test as Settings runs it, minus the step that needs a person (06 §3): every step that
 * ran on this device passed, the vault's four ran, and step 0's probe answered into the device
 * facts. Each step goes to logcat under `SelfTest`, which is how an attached phone's answers reach
 * the table in 04 §2.
 */
@RunWith(AndroidJUnit4::class)
class SelfTestOnDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().decodeToString() }

    @Test
    fun everyStepThatRunsHerePasses() = runBlocking<Unit> {
        val result = run()
        val failed = result.steps.filter { it.ran && !it.passed }
        assertTrue(failed.isEmpty(), failed.joinToString("\n") { "${it.code}: ${it.evidence}" })
        val vault = listOf(SelfTestCode.VaultRoundtrip, SelfTestCode.VaultHardware, SelfTestCode.VaultTamper, SelfTestCode.VaultNonce).map { it.id }
        assertEquals(vault, result.steps.filter { it.code in vault && it.ran }.map { it.code }, "the vault's steps ran")
        assertTrue(result.device.facts.keys.any { it.startsWith("probe.P1") }, "step 0's probe answered: ${result.device.facts}")
    }

    /** With a lock, the chip must refuse a key bound to the person when nobody confirmed (I7). PIN 1111, removed after. */
    @Test
    fun withAScreenLockTheChipRefusesWithoutThePerson() = runBlocking<Unit> {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val setPin = !keyguard.isDeviceSecure
        if (setPin) shell("locksettings set-pin 1111")
        try {
            val result = run()
            val step = result.steps.single { it.code == SelfTestCode.WrapAuthRequired.id }
            assertTrue(step.ran && step.passed, "${step.evidence}")
            assertTrue(result.steps.single { it.code == SelfTestCode.WrapPasscodeClass.id }.let { it.ran && it.passed })
        } finally {
            if (setPin) shell("locksettings clear --old 1111")
        }
    }

    private suspend fun run(): cx.m42.superizer.diagnostics.SelfTestResult {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val keyring = SshKeyringHost.android(
            context = context,
            activity = { null },
            texts = { PromptText("self-test", null, null) },
            records = InMemoryRecordStore(),
            prompter = KeyPrompts(),
            clock = SystemClock,
            scope = scope,
        )
        val platform = AndroidSelfTest(context, { null }) { "self-test" }
        val result = keyring.selfTest("instrumented", platform) { "no report in this test" }.run(withConfirmation = false)

        Log.i("SelfTest", "${result.device.model} · ${result.device.os}")
        result.steps.forEach { Log.i("SelfTest", "${it.code} ran=${it.ran} passed=${it.passed} ${it.millis} ms · ${it.evidence}") }
        result.device.facts.forEach { (k, v) -> Log.i("SelfTest", "$k = $v") }
        return result
    }
}
