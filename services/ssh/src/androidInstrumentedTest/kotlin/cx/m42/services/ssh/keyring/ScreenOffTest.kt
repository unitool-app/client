package cx.m42.services.ssh.keyring

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cx.m42.services.ssh.SecretBytes
import cx.m42.services.ssh.SshKeyringHost
import cx.m42.services.ssh.android
import cx.m42.services.ssh.key.OpenSshKeyFile
import cx.m42.services.ssh.key.PrivateKey
import cx.m42.superizer.app.AppId
import cx.m42.superizer.runtime.Clock
import cx.m42.superizer.ssh.ImportOptions
import cx.m42.superizer.ssh.ImportOutcome
import cx.m42.superizer.ssh.KeyId
import cx.m42.superizer.ssh.KeyPurpose
import cx.m42.superizer.ssh.SshKeyInfo
import cx.m42.superizer.ssh.SshOutcome
import cx.m42.superizer.ssh.UsePolicy
import cx.m42.superizer.testing.Canary
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * I15 on Android (04 step 5): a session ends when the screen goes off, as `ssh-agent -t` would
 * want and a phone left on a table needs — the next use asks for the passphrase again.
 */
@RunWith(AndroidJUnit4::class)
class ScreenOffTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().decodeToString() }

    /** `Awake`, `Asleep` or `Dozing`, as the power manager has it. */
    private fun wakefulness(): String = shell("dumpsys power").lineSequence().firstOrNull { "mWakefulness=" in it }?.substringAfter("=")?.trim().orEmpty()

    private fun waitFor(millis: Long, done: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (!done() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        return done()
    }

    private class Asking(private val pass: String) : KeyPrompter {
        var passphrases = 0
        override suspend fun choose(app: AppId, purpose: KeyPurpose, candidates: List<SshKeyInfo>): KeyId? = candidates.firstOrNull()?.id
        override suspend fun grant(app: AppId, key: SshKeyInfo, purpose: KeyPurpose) = true
        override suspend fun passphrase(reason: UseReason, wrong: Int): CharArray? = pass.toCharArray().also { passphrases++ }
        override suspend fun confirm(reason: UseReason) = true
    }

    @Test
    fun theScreenGoingOffEndsEverySession() = runBlocking<Unit> {
        val pass = "a passphrase long enough"
        val prompter = Asking(pass)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = SshKeyringHost.android(context, { null }, { PromptText("t", null, null) }, InMemoryRecordStore(), prompter, Clock { System.currentTimeMillis() }, scope)
        try {
            // Going off is only an event for a screen that is on. An emulator nobody has touched for
            // a few minutes is asleep already, KEYCODE_SLEEP does nothing, and no broadcast comes.
            shell("svc power stayon true")
            shell("input keyevent KEYCODE_WAKEUP")
            assertTrue(waitFor(10_000) { wakefulness() == "Awake" }, "the screen did not come on: ${wakefulness()}")
            val seed = Canary.ED25519_SEED.reveal()
            val file = PrivateKey.ed25519(seed, "screen").use { k -> SecretBytes.utf8(pass.toCharArray()).use { OpenSshKeyFile.write(k, it, rounds = 4) } }
            seed.fill(0)
            val key = assertIs<ImportOutcome.Imported>(host.admin.import(file, pass.toCharArray(), ImportOptions(policy = UsePolicy.Session(600)))).key
            val app = host.forApp(AppId("screen-off"))
            assertIs<SshOutcome.Ok<*>>(app.sign(key.id, "git", "1".toByteArray()))
            assertIs<SshOutcome.Ok<*>>(app.sign(key.id, "git", "2".toByteArray()))
            assertEquals(1, prompter.passphrases, "inside the session, asked once")

            shell("input keyevent KEYCODE_SLEEP")
            // The broadcast follows the display's own power-down, which on a CI emulator takes seconds.
            val closed = waitFor(20_000) { host.keyring.openSessionsForTest().all { it.closed } }
            val state = wakefulness()
            shell("input keyevent KEYCODE_WAKEUP")
            assertTrue(closed, "the screen went off ($state) and the session stayed")

            assertIs<SshOutcome.Ok<*>>(app.sign(key.id, "git", "3".toByteArray()))
            assertEquals(2, prompter.passphrases, "after the screen went off, asked again")
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            scope.cancel()
        }
    }
}
