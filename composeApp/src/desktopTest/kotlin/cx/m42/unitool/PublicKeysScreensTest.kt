package cx.m42.unitool

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import cx.m42.apps.twofa.TwoFaApp
import cx.m42.services.qr.QrScanner
import cx.m42.services.share.FileSharer
import cx.m42.services.ssh.SshKeyringHost
import cx.m42.services.ssh.SshPublicKey
import cx.m42.services.ssh.keyring.HostAction
import cx.m42.services.ssh.keyring.InMemoryRecordStore
import cx.m42.services.ssh.keyring.KeyPrompter
import cx.m42.services.ssh.keyring.UseReason
import cx.m42.superizer.Superizer
import cx.m42.superizer.app.AppId
import cx.m42.superizer.runtime.Platform
import cx.m42.superizer.ssh.GeneratedKind
import cx.m42.superizer.ssh.ImportOptions
import cx.m42.superizer.ssh.ImportOutcome
import cx.m42.superizer.ssh.KeyId
import cx.m42.superizer.ssh.KeyPurpose
import cx.m42.superizer.ssh.SshKeyInfo
import cx.m42.superizer.ssh.SshOutcome
import cx.m42.superizer.testing.FakeClock
import cx.m42.unitool.security.KeyPrompts
import cx.m42.unitool.security.UnitoolSecurity
import cx.m42.unitool.settings.HostTools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * Two phones and one key (.ai-notes/public-keys): B shows its key as a QR code, A reads it and keeps
 * only the public half, A's backup is encrypted to it, and B — the real keyring, its own passphrase —
 * opens what A made.
 */
@OptIn(ExperimentalTestApi::class)
class PublicKeysScreensTest : UnitoolTest() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private class SharedFiles : FileSharer {
        val files = mutableListOf<Pair<String, String>>()
        override suspend fun share(fileName: String, text: String, mimeType: String, message: String?): Boolean {
            files += fileName to text
            return true
        }
    }

    /** The camera, pointed at whatever [next] is. */
    private class Camera(var next: String? = null) : QrScanner {
        override suspend fun scan(): String? = next
    }

    /** Phone B's person, outside any screen: they type the passphrase and say yes. */
    private class Owner(private val passphrase: String) : KeyPrompter {
        override suspend fun choose(app: AppId, purpose: KeyPurpose, candidates: List<SshKeyInfo>): KeyId? = candidates.firstOrNull()?.id
        override suspend fun grant(app: AppId, key: SshKeyInfo, purpose: KeyPurpose): Boolean = true
        override suspend fun passphrase(reason: UseReason, wrong: Int): CharArray? = passphrase.toCharArray()
        override suspend fun confirm(reason: UseReason): Boolean = true
    }

    private fun phoneB(): SshKeyringHost =
        SshKeyringHost.passphraseOnly(InMemoryRecordStore(), Owner(PASSPHRASE), FakeClock(), Platform.Desktop, scope).also {
            runBlocking { it.admin.generate(GeneratedKind.Ed25519, ImportOptions(comment = "phone-b"), PASSPHRASE.toCharArray()) }
        }

    private fun security(): UnitoolSecurity {
        val prompts = KeyPrompts()
        return UnitoolSecurity(SshKeyringHost.passphraseOnly(InMemoryRecordStore(), prompts, FakeClock(), Platform.Desktop, scope), prompts)
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

    private fun ComposeUiTest.openKeys() {
        onNodeWithContentDescription("Open menu").performClick()
        waitForIdle()
        onNodeWithContentDescription("Premium").performClick()
        waitForIdle()
        tap("activate:have-code")
        type("activate:promo", "{ssh-keys}")
        tap("activate:apply")
    }

    @Test
    fun aKeysCardShowsItsPublicHalfAsAQrCodeWithTheFingerprint() = runComposeUiTest {
        val security = security()
        val superizer = buildUnitool(scope, debug = true, security = security, tools = HostTools(), network = ratesNetwork())
        superizer.settings.chooseLanguage("en")
        runBlocking { security.keyring.admin.generate(GeneratedKind.Ed25519, ImportOptions(comment = "desk"), PASSPHRASE.toCharArray()) }
        val key = security.keyring.admin.keys.value.single()
        setContent { UnitoolApp(superizer, security) }
        waitForIdle()

        openKeys()
        onNodeWithTag("known:empty").performScrollTo().assertIsDisplayed()
        tap("keys:row-0")
        tap("key:qr")
        onNodeWithTag("key-qr:code").assertIsDisplayed()
        onNodeWithTag("key-qr:fingerprint").assertTextEquals(key.id.fingerprint)
    }

    @Test
    fun aKeyReadOffTheOtherPhoneIsSavedAndTheBackupMadeToItOpensThere() = runComposeUiTest {
        val b = phoneB()
        val bKey = b.admin.keys.value.single()
        // What B's QR code says (D510): the line its card copies.
        val shown = "${bKey.publicKey} ${bKey.comment}"

        val security = security()
        val shared = SharedFiles()
        val camera = Camera(shown)
        val superizer: Superizer = buildUnitool(scope, debug = true, security = security, tools = HostTools(qr = camera, sharer = shared), network = ratesNetwork())
        superizer.settings.chooseLanguage("en")
        runBlocking { superizer.handler.appRuntime(TwoFaApp.ID)!!.secrets.set("accounts", "[from-a]") }
        setContent { UnitoolApp(superizer, security) }
        waitForIdle()

        openKeys()
        tap("known:add")
        tap("known-add:qr")
        onNodeWithTag("known-add:fingerprint").assertTextContains(bKey.id.fingerprint, substring = true)
        onNodeWithTag("known-add:name").performTextReplacement("Ivan's phone")
        waitForIdle()
        tap("known-add:save")
        onNodeWithTag("known:row-0").assertTextContains("Ivan's phone", substring = true)
        val saved = security.keyring.publicKeys.keys.value.single()
        assertEquals(bKey.id, saved.id)

        // The same key again is refused, and says under which name it is.
        tap("known:add")
        tap("known-add:qr")
        onNodeWithTag("known-add:problem").assertTextContains("Already saved as “Ivan's phone”", substring = true)

        // Settings → Backup: the saved key ticked, no GitHub at all.
        onNodeWithContentDescription("Open menu").performClick()
        waitForIdle()
        onNodeWithContentDescription("Settings").performClick()
        waitForIdle()
        tap("backup:open")
        onNodeWithTag("backup:make").assertDoesNotExist()
        tap("backup:known-0")
        tap("backup:make")
        onNodeWithTag("backup:said").assertTextContains("Backup made", substring = true)
        val (_, text) = shared.files.single()

        // A holds no private key at all; B opens the file with its own.
        assertTrue(security.keyring.admin.keys.value.isEmpty())
        val opened = runBlocking { b.openForHost(text.encodeToByteArray()) }
        assertIs<SshOutcome.Ok<ByteArray>>(opened)
    }

    @Test
    fun aKeySentToTheOtherPhoneOpensThereAndIsAddedUnderItsPassphrase() = runComposeUiTest {
        val b = phoneB()
        val bKey = b.admin.keys.value.single()
        val security = security()
        val shared = SharedFiles()
        val superizer = buildUnitool(scope, debug = true, security = security, tools = HostTools(sharer = shared), network = ratesNetwork())
        superizer.settings.chooseLanguage("en")
        runBlocking {
            security.keyring.admin.generate(GeneratedKind.Ed25519, ImportOptions(comment = "desk"), DESK_PASSPHRASE.toCharArray())
            security.keyring.publicKeys.add(SshPublicKey.parse(bKey.publicKey)!!, "Ivan's phone")
        }
        val desk = security.keyring.admin.keys.value.single()
        setContent { UnitoolApp(superizer, security) }
        waitForIdle()

        openKeys()
        tap("keys:row-0")
        tap("key:send")
        onNodeWithTag("key-send:warning").assertIsDisplayed()
        tap("key-send:to-0")
        // Asked like a delete (D148), and the sheet says where it goes; the passphrase is not asked, nothing is opened.
        onNodeWithText("Send the key “desk” to “Ivan's phone”").assertIsDisplayed()
        tap("keys:confirm-yes")
        onNodeWithTag("key-send:said").assertTextContains("encrypted to “Ivan's phone”", substring = true)
        val (name, text) = shared.files.single()
        assertEquals("desk.age", name)
        assertTrue(text.startsWith("-----BEGIN AGE ENCRYPTED FILE-----"))
        assertEquals("ok", security.keyring.admin.journal(desk.id).value.single { it.namespace == HostAction.EXPORT }.outcome)

        // B opens it with its own key and adds the key A had, under the passphrase it had there.
        val file = assertIs<SshOutcome.Ok<ByteArray>>(runBlocking { b.openForHost(text.encodeToByteArray()) }).value
        val added = runBlocking { b.admin.import(file, DESK_PASSPHRASE.toCharArray(), ImportOptions()) }
        assertEquals(desk.id, assertIs<ImportOutcome.Imported>(added).key.id)
    }

    @Test
    fun whatCannotBeEncryptedToAndAnOwnKeyAreRefusedWithWords() = runComposeUiTest {
        val security = security()
        val superizer = buildUnitool(scope, debug = true, security = security, tools = HostTools(), network = ratesNetwork())
        superizer.settings.chooseLanguage("en")
        runBlocking { security.keyring.admin.generate(GeneratedKind.Ed25519, ImportOptions(comment = "desk"), PASSPHRASE.toCharArray()) }
        val own = security.keyring.admin.keys.value.single()
        setContent { UnitoolApp(superizer, security) }
        waitForIdle()

        openKeys()
        tap("known:add")
        tap("known-add:paste")
        type("known-add:paste-field", ECDSA)
        tap("known-add:paste-next")
        onNodeWithTag("known-add:problem").assertTextContains("ECDSA", substring = true)

        onNodeWithTag("known-add:paste-field").performTextReplacement(own.publicKey)
        waitForIdle()
        tap("known-add:paste-next")
        onNodeWithTag("known-add:problem").assertTextContains("your own key “desk”", substring = true)
        assertTrue(security.keyring.publicKeys.keys.value.isEmpty())
    }

    private companion object {
        const val PASSPHRASE = "correct horse battery staple"
        const val DESK_PASSPHRASE = "the desk's own passphrase"
        const val ECDSA =
            "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEIsMsXqmDgDTtFzJYG8gCJX0455Aa7IlDHEiYA0PtCHVoTPjGzWFYwQgxsQRhLRYXwgEw9EjUXF79TLbDAzMEo="
    }
}
