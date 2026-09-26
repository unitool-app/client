package cx.m42.unitool

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import cx.m42.apps.twofa.TwoFaApp
import cx.m42.services.backup.GitHubKeys
import cx.m42.services.share.FileSharer
import cx.m42.services.ssh.SshKeyringHost
import cx.m42.services.ssh.keyring.InMemoryRecordStore
import cx.m42.superizer.Superizer
import cx.m42.superizer.host.storage.SafePrefs
import cx.m42.superizer.runtime.PickedFile
import cx.m42.superizer.runtime.Platform
import cx.m42.superizer.ssh.GeneratedKind
import cx.m42.superizer.ssh.ImportOptions
import cx.m42.superizer.testing.FakeClock
import cx.m42.superizer.testing.FakeFilePicker
import cx.m42.unitool.security.KeyPrompts
import cx.m42.unitool.security.UnitoolSecurity
import cx.m42.unitool.settings.HostTools
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * Settings → Backup as a person uses it (05 §3.1): a GitHub login, its keys, "Make a backup", the
 * key's passphrase once for the secrets, and the file handed to the share sheet — then the same file
 * picked and restored. The cipher is the real one; the GitHub answer is [ratesNetwork]'s.
 */
@OptIn(ExperimentalTestApi::class)
class BackupScreenTest : UnitoolTest() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private class SharedFiles : FileSharer {
        val files = mutableListOf<Pair<String, String>>()
        override suspend fun share(fileName: String, text: String, mimeType: String, message: String?): Boolean {
            files += fileName to text
            return true
        }
    }

    private fun ComposeUiTest.tap(tag: String) {
        runCatching { onNodeWithTag(tag).performScrollTo() }
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.type(tag: String, text: String) {
        runCatching { onNodeWithTag(tag).performScrollTo() }
        onNodeWithTag(tag).performTextInput(text)
        waitForIdle()
    }

    private fun ComposeUiTest.openBackup() {
        onNodeWithContentDescription("Open menu").performClick()
        waitForIdle()
        onNodeWithContentDescription("Settings").performClick()
        waitForIdle()
        tap("backup:open")
    }

    @Test
    fun aBackupIsMadeToTheGithubKeysAndTheLastDateIsKept() = runComposeUiTest {
        val prompts = KeyPrompts()
        val security = UnitoolSecurity(SshKeyringHost.passphraseOnly(InMemoryRecordStore(), prompts, FakeClock(), Platform.Desktop, scope), prompts)
        val shared = SharedFiles()
        val superizer: Superizer = buildUnitool(scope, debug = true, security = security, tools = HostTools(sharer = shared), network = ratesNetwork())
        superizer.settings.chooseLanguage("en")
        runBlocking {
            security.keyring.admin.generate(GeneratedKind.Ed25519, ImportOptions(comment = "desk"), PASSPHRASE.toCharArray())
            superizer.handler.appRuntime(TwoFaApp.ID)!!.secrets.set("accounts", "[]")
            // A new session: the secrets are sealed and the passphrase has to be given again.
            security.keyring.admin.lockAll()
        }
        setContent { UnitoolApp(superizer, security) }
        waitForIdle()

        openBackup()
        onNodeWithTag("backup:includes").assertTextContains("2FA", substring = true)
        type("backup:github", "octocat")
        tap("backup:find-keys")
        // ECDSA is listed and said to be unusable; the two others are encrypted to.
        onNodeWithTag("backup:key-2").assertTextContains("cannot", substring = true)
        tap("backup:make")
        // The secrets are opened by the host's vault — the key's passphrase, once.
        answerPassphrase()

        onNodeWithTag("backup:said").assertTextContains("Backup made", substring = true)
        val (name, text) = shared.files.single()
        assertTrue(name.startsWith("unitool-backup-") && name.endsWith(".age"), name)
        assertTrue(text.startsWith("-----BEGIN AGE ENCRYPTED FILE-----"))
        assertNotNull(superizer.backup.lastBackupAt.value)
    }

    @Test
    fun theSameFilePickedIsListedAndRestored() = runComposeUiTest {
        val prompts = KeyPrompts()
        val security = UnitoolSecurity(SshKeyringHost.passphraseOnly(InMemoryRecordStore(), prompts, FakeClock(), Platform.Desktop, scope), prompts)
        val shared = SharedFiles()
        val picker = FakeFilePicker()
        val network = ratesNetwork()
        val superizer: Superizer = buildUnitool(scope, debug = true, security = security, tools = HostTools(files = picker, sharer = shared), network = network)
        superizer.settings.chooseLanguage("en")
        val secrets = superizer.handler.appRuntime(TwoFaApp.ID)!!.secrets
        runBlocking {
            security.keyring.admin.generate(GeneratedKind.Ed25519, ImportOptions(comment = "desk"), PASSPHRASE.toCharArray())
            secrets.set("accounts", "[before]")
        }
        // The account's published key is this device's own, so this device can open the backup.
        val mine = security.keyring.admin.keys.value.single().publicKey
        network.respond(GitHubKeys.url("me"), """[{"id":1,"key":"$mine"}]""")
        setContent { UnitoolApp(superizer, security) }
        waitForIdle()

        openBackup()
        type("backup:github", "me")
        tap("backup:find-keys")
        tap("backup:make")
        // The key was made in this session, so the vault still holds the secrets open (D176) and
        // the backup asks nothing more.
        onNodeWithTag("keys:passphrase-field").assertDoesNotExist()
        onNodeWithTag("backup:said").assertTextContains("Backup made", substring = true)
        val (name, text) = shared.files.single()
        runBlocking { secrets.set("accounts", "[after]") } // sealing asks nothing (I16)
        val after = SafePrefs.get("secret.2fa.accounts")

        picker.next = PickedFile(name, text.encodeToByteArray())
        tap("backup:pick")
        // Opening the file is a decryption with the key, and every decryption is asked for.
        answerPassphrase()
        onNodeWithTag("backup:app-2fa").assertIsDisplayed()
        tap("backup:restore")
        onNodeWithTag("backup:restored").assertTextContains("Restored 1 app", substring = true)
        val restored = SafePrefs.get("secret.2fa.accounts")!!
        assertTrue(restored.startsWith("age1:") && restored != after, "the backup's value, sealed again here")
        assertTrue(picker.next!!.bytes.all { it == 0.toByte() }, "the picked file is wiped once read")
    }

    private fun ComposeUiTest.answerPassphrase() {
        type("keys:passphrase-field", PASSPHRASE)
        tap("keys:passphrase-yes")
    }

    private companion object {
        const val PASSPHRASE = "correct horse battery staple"
    }
}
