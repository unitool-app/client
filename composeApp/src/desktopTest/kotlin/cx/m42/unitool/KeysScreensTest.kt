package cx.m42.unitool

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import cx.m42.apps.twofa.TwoFaApp
import cx.m42.services.ssh.SshKeyringHost
import cx.m42.services.ssh.diagnostics.SkipReason
import cx.m42.services.ssh.keyring.InMemoryRecordStore
import cx.m42.superizer.Superizer
import cx.m42.superizer.diagnostics.DiagnosticsCode
import cx.m42.superizer.diagnostics.SelfTestCode
import cx.m42.superizer.diagnostics.SelfTestResult
import cx.m42.superizer.diagnostics.SelfTestStep
import cx.m42.superizer.host.storage.HostKeys
import cx.m42.superizer.host.storage.SafePrefs
import cx.m42.superizer.runtime.Platform
import cx.m42.superizer.ssh.GeneratedKind
import cx.m42.superizer.ssh.ImportOptions
import cx.m42.superizer.ssh.ImportOutcome
import cx.m42.superizer.ssh.KeyOrigin
import cx.m42.superizer.ssh.KeyStorage
import cx.m42.superizer.testing.FakeClock
import cx.m42.unitool.security.KeyPrompts
import cx.m42.unitool.security.UnitoolSecurity
import cx.m42.unitool.settings.HostTools
import cx.m42.unitool.settings.ImportKeyScreen
import cx.m42.unitool.settings.PassphraseScreen
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assume

/**
 * The host's key screens against the real keyring on a desktop — level 3, no chip (04 step 6): the
 * wizard from a pasted file to a key in the list, every turn it can take on the way (D167, 02 §8),
 * the card, Storage & security, and the 2FA line that says how its accounts are kept. The files are
 * made by the real `ssh-keygen`; without it those tests are skipped by name (03 D158).
 */
@OptIn(ExperimentalTestApi::class)
class KeysScreensTest : UnitoolTest() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun security(): UnitoolSecurity {
        val prompts = KeyPrompts()
        return UnitoolSecurity(SshKeyringHost.passphraseOnly(InMemoryRecordStore(), prompts, FakeClock(), Platform.Desktop, scope), prompts)
    }

    private fun host(security: UnitoolSecurity): Superizer =
        buildUnitool(scope, debug = true, security = security, tools = HostTools(), network = ratesNetwork())
            .also { it.settings.chooseLanguage("en") }

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

    private fun ComposeUiTest.openSettings() {
        onNodeWithContentDescription("Open menu").performClick()
        waitForIdle()
        onNodeWithContentDescription("Settings").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.pasteKey(file: String) {
        openSettings()
        tap("keys:add")
        tap("import:paste")
        type("import:paste-field", file)
        tap("import:paste-next")
    }

    @Test
    fun aDesktopWithoutKeysSaysSoOnHomeInSettingsAndIn2fa() = runComposeUiTest {
        val security = security()
        val superizer = host(security)
        setContent { UnitoolApp(superizer, security) }
        waitForIdle()

        // Nothing seals secrets here until there is a key (06 §7): a line on Home until that changes.
        onNodeWithTag("home:storage-banner").assertIsDisplayed()
        onNodeWithTag("home:storage-banner").assertTextContains("Not protected", substring = true)

        openSettings()
        onNodeWithTag("settings:host:ssh-keys").performScrollTo()
        onNodeWithTag("keys:empty").assertIsDisplayed()
        onNodeWithTag("settings:host:storage").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aPastedKeyWithAGoodPassphraseLandsAtLevel3AndSealsTheSecrets() = runComposeUiTest {
        requireSshKeygen()
        val file = keygen(passphrase = GOOD)
        val security = security()
        val superizer = host(security)
        setContent { UnitoolApp(superizer, security) }
        waitForIdle()

        pasteKey(file)
        // Before any passphrase: what it is and where it would live (02 §5).
        onNodeWithTag("import:key").assertTextContains("ED25519", substring = true)
        onNodeWithTag("finding:${DiagnosticsCode.KeyLevel3.id}").assertIsDisplayed()
        type("import:passphrase", GOOD)
        onNodeWithTag("import:count").assertTextContains("${GOOD.length} characters")
        tap("import:add")

        val keys = security.keyring.admin.keys.value
        assertEquals(1, keys.size, "added")
        assertEquals(KeyStorage.SealedByPassphrase, keys.single().storage)
        onNodeWithTag("keys:row-0").performScrollTo().assertIsDisplayed()
        // The first key that can hold secrets becomes their key at once (I16), and the banner goes.
        assertEquals(keys.single().id, security.keyring.secretsKey.value)
        assertTrue(superizer.storage.report.value.findings.any { it.code == DiagnosticsCode.VaultPassphraseOk.id })
        assertTrue(superizer.storage.report.value.findings.none { it.code == DiagnosticsCode.VaultMissing.id })
    }

    @Test
    fun aKeyWithoutAPassphraseIsKeptOnlyUnderANewOne() = runComposeUiTest {
        requireSshKeygen()
        val file = keygen(passphrase = "")
        val security = security()
        val superizer = host(security)
        setContent { UnitoolApp(superizer, security) }
        waitForIdle()

        pasteKey(file)
        // Said before "Add" (D167), and then the step that asks for one.
        onNodeWithTag("import:unencrypted").assertIsDisplayed()
        tap("import:add")
        onNodeWithTag("import:new-title").assertIsDisplayed()
        assertTrue(security.keyring.admin.keys.value.isEmpty(), "nothing stored yet")

        type("import:new-first", "too short")
        type("import:new-second", "too short")
        tap("import:new-submit")
        onNodeWithTag("import:new-count").assertTextContains("at least 12", substring = true)
        assertTrue(security.keyring.admin.keys.value.isEmpty(), "nine characters do nothing")

        // Retyped from the start: the fields hold what was typed, and 12 is counted, not judged.
        pasteAgainNewPassphrase()
        assertEquals(1, security.keyring.admin.keys.value.size)
    }

    private fun ComposeUiTest.pasteAgainNewPassphrase() {
        tap("import:back")
        tap("import:add")
        type("import:new-first", GOOD)
        type("import:new-second", GOOD + "x")
        onNodeWithTag("import:new-differ").assertIsDisplayed()
        tap("import:new-submit")
        onNodeWithTag("import:new-title").assertIsDisplayed()
        tap("import:back")
        tap("import:add")
        type("import:new-first", GOOD)
        type("import:new-second", GOOD)
        tap("import:new-submit")
    }

    @Test
    fun aShortPassphraseIsReplacedAndAWrongOneAskedAgainWithoutPickingTheFileAgain() = runComposeUiTest {
        requireSshKeygen()
        val short = "ten chars!"
        val file = keygen(passphrase = short)
        val security = security()
        val superizer = host(security)
        setContent { UnitoolApp(superizer, security) }
        waitForIdle()

        pasteKey(file)
        type("import:passphrase", "not the one")
        tap("import:add")
        // Short, so the wizard asks for a new one first; the old one is only checked on "Add".
        onNodeWithTag("import:new-title").assertIsDisplayed()
        type("import:new-first", GOOD)
        type("import:new-second", GOOD)
        tap("import:new-submit")
        onNodeWithTag("import:problem").assertTextContains("Wrong passphrase", substring = true)
        assertTrue(security.keyring.admin.keys.value.isEmpty())

        // The file is still the wizard's: the right passphrase now, and in it goes.
        type("import:passphrase", short)
        tap("import:add")
        onNodeWithTag("import:new-title").assertTextContains("too short", substring = true)
        type("import:new-first", GOOD)
        type("import:new-second", GOOD)
        tap("import:new-submit")
        assertEquals(1, security.keyring.admin.keys.value.size)
    }

    @Test
    fun whatCannotBeImportedSaysWhyAndTheCommandThatFixesIt() = runComposeUiTest {
        requireSshKeygen()
        val dir = Files.createTempDirectory("keys-ui").toFile()
        val pem = File(dir, "old")
        run("ssh-keygen", "-q", "-t", "rsa", "-b", "2048", "-m", "PEM", "-N", GOOD, "-C", "old@test", "-f", pem.path)
        val security = security()
        val superizer = host(security)
        setContent { UnitoolApp(superizer, security) }
        waitForIdle()

        pasteKey(pem.readText())
        onNodeWithTag("import:refusal").assertTextContains("old key format", substring = true)
        tap("import:back")
        tap("import:paste")
        type("import:paste-field", File(dir, "old.pub").readText())
        tap("import:paste-next")
        onNodeWithTag("import:refusal").assertTextContains(".pub", substring = true)
        dir.deleteRecursively()
    }

    @Test
    fun aKeyMadeHereOnADesktopAsksForItsPassphrase() = runComposeUiTest {
        val security = security()
        val superizer = host(security)
        setContent { UnitoolApp(superizer, security) }
        waitForIdle()

        openSettings()
        tap("keys:add")
        tap("import:create")
        type("import:name", "made-here")
        tap("import:create-key")
        // No chip: the key lands at level 3, so it needs a passphrase (D167).
        onNodeWithTag("import:new-title").assertIsDisplayed()
        type("import:new-first", GOOD)
        type("import:new-second", GOOD)
        tap("import:new-submit")
        val key = security.keyring.admin.keys.value.single()
        assertEquals("made-here", key.comment)
        assertIs<KeyOrigin.Generated>(key.origin)
    }

    @Test
    fun theCardShowsTheKeyAndDeletesOnlyWhatDoesNotSealTheSecrets() = runComposeUiTest {
        val security = security()
        val superizer = host(security)
        runBlocking {
            assertIs<ImportOutcome.Imported>(security.keyring.admin.generate(GeneratedKind.Ed25519, ImportOptions(comment = "anchor"), GOOD.toCharArray()))
            assertIs<ImportOutcome.Imported>(security.keyring.admin.generate(GeneratedKind.Ed25519, ImportOptions(comment = "spare"), GOOD.toCharArray()))
        }
        setContent { UnitoolApp(superizer, security) }
        waitForIdle()

        openSettings()
        tap("keys:row-0")
        onNodeWithTag("key:card").assertIsDisplayed()
        onNodeWithTag("key:public").assertTextContains("ssh-ed25519 ", substring = true)
        onNodeWithTag("finding:${DiagnosticsCode.KeyLevel3.id}").assertIsDisplayed()
        // The first key seals the host's secrets (D173): it says so, and it cannot go.
        onNodeWithTag("key:anchor").performScrollTo().assertIsDisplayed()
        tap("key:delete")
        onNodeWithTag("keys:confirm").assertDoesNotExist()
        assertEquals(2, security.keyring.admin.keys.value.size)
        onNodeWithContentDescription("Back").performClick()
        waitForIdle()

        tap("keys:row-1")
        tap("key:delete")
        // The keyring asks first (D148), in the host's sheet.
        tap("keys:confirm-yes")
        assertEquals(listOf("anchor"), security.keyring.admin.keys.value.map { it.comment })
        onNodeWithTag("keys:row-1").assertDoesNotExist()
    }

    @Test
    fun storageAndSecurityShowsWhatIsWrongAndLeadsToTheFix() = runComposeUiTest {
        val security = security()
        val superizer = host(security)
        setContent { UnitoolApp(superizer, security) }
        waitForIdle()

        onNodeWithTag("home:storage-banner").performClick()
        waitForIdle()
        onNodeWithTag("storage:summary").assertTextContains("Not protected", substring = true)
        onNodeWithTag("finding:${DiagnosticsCode.VaultMissing.id}").assertIsDisplayed()
        // The catalogue's action for "no vault" is to add a key, and the button goes there.
        tap("storage:action-${DiagnosticsCode.VaultMissing.id}-AddSshKey")
        onNodeWithTag("host:key-import").assertIsDisplayed()
    }

    @Test
    fun aSkippedStepSaysWhyWhenAPersonCanDoSomethingAboutIt() = runComposeUiTest {
        // As an iPhone showed it on 2026-09-27: the run at start skips the step that needs Face ID,
        // and the screen called that "not on this platform".
        val skipped = SelfTestResult(
            steps = listOf(
                SelfTestStep(SelfTestCode.WrapConfirmed.id, passed = true, millis = 0, evidence = SkipReason.NEEDS_CONFIRMATION, ran = false),
                SelfTestStep(SelfTestCode.WrapAuthRequired.id, passed = true, millis = 0, evidence = SkipReason.NO_SCREEN_LOCK, ran = false),
                SelfTestStep(SelfTestCode.EnclaveSign.id, passed = true, millis = 0, evidence = "iOS only", ran = false),
            ),
            at = 1_700_000_022_000,
            // This version's, or the host would run its own at start over it.
            hostVersion = BuildInfo.VERSION,
        )
        SafePrefs.put(HostKeys.SELF_TEST, Json.encodeToString(SelfTestResult.serializer(), skipped))
        val security = security()
        val superizer = host(security)
        setContent { UnitoolApp(superizer, security) }
        waitForIdle()
        openSettings()
        tap("storage:open")

        fun under(code: SelfTestCode, text: String) =
            onNode(hasText(text, substring = true) and hasAnyAncestor(hasTestTag("storage:step-${code.id}"))).performScrollTo().assertIsDisplayed()
        under(SelfTestCode.WrapConfirmed, "only from the Run self-test button")
        under(SelfTestCode.WrapAuthRequired, "no screen lock")
        under(SelfTestCode.EnclaveSign, "not on this platform")
    }

    @Test
    fun the2faListSaysHowItsAccountsAreKept() = runComposeUiTest {
        val security = security()
        val superizer = host(security)
        setContent { UnitoolApp(superizer, security) }
        waitForIdle()
        runBlocking { superizer.addToHome(TwoFaApp.ID) }
        waitForIdle()
        tap(TWOFA_TILE)
        onNodeWithTag("2fa:protection").assertTextContains("plain text", substring = true)

        runBlocking {
            security.keyring.admin.generate(GeneratedKind.Ed25519, ImportOptions(comment = "desk"), GOOD.toCharArray())
            superizer.storage.inspect()
        }
        waitForIdle()
        onNodeWithTag("2fa:protection").assertTextContains("passphrase of your SSH key desk", substring = true)
    }

    @Test
    fun everyScreenThatTakesAPassphraseIsKeptOutOfScreenshots() {
        // I13 on the desktop: the flag the shell hands to SecureWindow. That Android honours it is
        // SecureWindowTest's, on a device.
        val keys = FakeKeys()
        assertTrue(ImportKeyScreen(keys, HostTools()).secure)
        assertTrue(PassphraseScreen(keys, keys.admin.keys.value.first().id).secure)
    }

    // ------------------------------------------------------------------ ssh-keygen

    private fun requireSshKeygen() {
        val has = runCatching { ProcessBuilder("sh", "-c", "command -v ssh-keygen").start().waitFor() == 0 }.getOrDefault(false)
        Assume.assumeTrue("SKIP interop: no ssh-keygen", has)
    }

    private fun keygen(passphrase: String): String {
        val dir = Files.createTempDirectory("keys-ui").toFile()
        try {
            val file = File(dir, "id")
            run("ssh-keygen", "-q", "-t", "ed25519", "-N", passphrase, "-C", "ui@test", "-f", file.path)
            return file.readText()
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun run(vararg command: String) {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "${command.toList()}: $out" }
    }

    private companion object {
        /** Twenty-eight characters: long enough for D167 without a second look. */
        const val GOOD = "correct horse battery staple"
    }
}
