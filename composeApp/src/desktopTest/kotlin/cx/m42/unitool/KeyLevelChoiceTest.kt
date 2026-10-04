package cx.m42.unitool

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import cx.m42.superizer.Superizer
import cx.m42.superizer.diagnostics.DiagnosticsCode
import cx.m42.superizer.ssh.Chip
import cx.m42.superizer.ssh.GeneratedKind
import cx.m42.superizer.ssh.ImportOptions
import cx.m42.superizer.ssh.KeyStorage
import cx.m42.superizer.testing.FakeSecretVault
import cx.m42.unitool.settings.HostTools
import cx.m42.unitool.settings.SshKeysSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * "Where it will live" in the wizard (09, D237–D244), on a phone made of data: what is offered,
 * what is chosen by default, the warning before Android's key loses its passphrase, and the
 * [ImportOptions] the keyring is handed in the end. That the keyring honours them is KeyringTest's.
 */
@OptIn(ExperimentalTestApi::class)
class KeyLevelChoiceTest : UnitoolTest() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun host(keys: FakeKeys): Superizer {
        val tools = HostTools()
        return buildUnitool(scope, debug = true, tools = tools, network = ratesNetwork()) {
            secretVault(FakeSecretVault())
            hostSection(SshKeysSection(keys, tools))
        }.also { it.settings.chooseLanguage("en") }
    }

    private fun ComposeUiTest.tap(tag: String) {
        runCatching { onNodeWithTag(tag).performScrollTo() }
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.shown(tag: String) {
        onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }

    private fun ComposeUiTest.open(keys: FakeKeys) {
        setContent { UnitoolApp(host(keys), null) }
        waitForIdle()
        onNodeWithContentDescription("Open menu").performClick()
        waitForIdle()
        onNodeWithContentDescription("Settings").performClick()
        waitForIdle()
        tap("keys:add")
    }

    private fun ComposeUiTest.paste(keys: FakeKeys) {
        open(keys)
        tap("import:paste")
        onNodeWithTag("import:paste-field").performTextInput("encrypted")
        waitForIdle()
        tap("import:paste-next")
    }

    private fun ComposeUiTest.add() {
        onNodeWithTag("import:passphrase").performScrollTo().performTextInput(GOOD)
        waitForIdle()
        tap("import:add")
    }

    @Test
    fun androidWithAFingerprintLetsOnlyItOpenTheKeyByDefault() = runComposeUiTest {
        val keys = FakeKeys(device = AndroidWithFingerprint)
        paste(keys)
        shown("finding:${DiagnosticsCode.KeyLevel1.id}")
        shown("finding:${DiagnosticsCode.KeyBiometricOnly.id}")
        onNodeWithTag("finding:${DiagnosticsCode.KeyNoSecondLock.id}").assertDoesNotExist()
        add()
        assertEquals(null to ImportOptions(level = null, keepPassphrase = false, biometricOnly = true), keys.admin.asked.single())
    }

    @Test
    fun androidWarnsBeforeTheFingerprintStopsBeingTheOnlyWayIn() = runComposeUiTest {
        val keys = FakeKeys(device = AndroidWithFingerprint)
        paste(keys)
        tap("import:biometric-only")
        shown("finding:${DiagnosticsCode.KeyNoSecondLock.id}")
        tap("import:keep-stay")
        onNodeWithTag("finding:${DiagnosticsCode.KeyNoSecondLock.id}").assertDoesNotExist()
        shown("finding:${DiagnosticsCode.KeyBiometricOnly.id}")

        // With the passphrase on first, the screen lock is never all there is: nothing to warn about.
        tap("import:keep-passphrase")
        tap("import:biometric-only")
        onNodeWithTag("import:keep-stay").assertDoesNotExist()
        onNodeWithTag("finding:${DiagnosticsCode.KeyBiometricOnly.id}").assertDoesNotExist()
        shown("finding:${DiagnosticsCode.KeyLevel2.id}")
        add()
        assertEquals(null to ImportOptions(level = 2, keepPassphrase = true, biometricOnly = false), keys.admin.asked.single())
    }

    @Test
    fun androidWithAFingerprintMakesAP256OnlyItOpens() = runComposeUiTest {
        val keys = FakeKeys(device = AndroidWithFingerprint)
        open(keys)
        tap("import:create")
        tapText("ECDSA P-256 — signs only, made inside the chip")
        shown("finding:${DiagnosticsCode.KeyBiometricOnly.id}")
        onNodeWithTag("import:keep-passphrase").assertDoesNotExist()
        tap("import:create-key")
        assertEquals(GeneratedKind.EcdsaP256 to ImportOptions(biometricOnly = true), keys.admin.asked.single())
    }

    @Test
    fun androidWithoutAFingerprintKeepsTheKeyInTheChipUnderItsPassphraseByDefault() = runComposeUiTest {
        val keys = FakeKeys()
        paste(keys)
        shown("import:where-chip")
        shown("import:where-passphrase")
        // The chip could hold it (level 1); with the passphrase kept it is sealed by the same chip.
        shown("finding:${DiagnosticsCode.KeyLevel2.id}")
        onNodeWithTag("finding:${DiagnosticsCode.KeyNoSecondLock.id}").assertDoesNotExist()
        onNodeWithTag("import:biometric-only").assertDoesNotExist()
        add()
        assertEquals(null to ImportOptions(level = 2, keepPassphrase = true), keys.admin.asked.single())
    }

    @Test
    fun androidWarnsBeforeTheKeyLosesItsPassphraseAndChangesNothingUntilTold() = runComposeUiTest {
        val keys = FakeKeys()
        paste(keys)
        tap("import:keep-passphrase")
        shown("finding:${DiagnosticsCode.KeyNoSecondLock.id}")
        tap("import:keep-stay")
        onNodeWithTag("import:keep-stay").assertDoesNotExist()
        onNodeWithTag("finding:${DiagnosticsCode.KeyNoSecondLock.id}").assertDoesNotExist()
        shown("finding:${DiagnosticsCode.KeyLevel2.id}")

        tap("import:keep-passphrase")
        tap("import:keep-drop")
        // Inside the chip now, and the warning stays where the choice is.
        shown("finding:${DiagnosticsCode.KeyLevel1.id}")
        shown("finding:${DiagnosticsCode.KeyNoSecondLock.id}")
        add()
        assertEquals(null to ImportOptions(level = null, keepPassphrase = false), keys.admin.asked.single())
    }

    @Test
    fun underThePassphraseOnlyIsOfferedAndTakenAsLevel3() = runComposeUiTest {
        val keys = FakeKeys()
        paste(keys)
        tap("import:where-passphrase")
        shown("finding:${DiagnosticsCode.KeyLevel3.id}")
        onNodeWithTag("import:keep-passphrase").assertDoesNotExist()
        add()
        assertEquals(null to ImportOptions(level = 3), keys.admin.asked.single())
    }

    @Test
    fun anIPhoneSealsTheKeyBehindFaceIdAndAsksNothingBeforeThePassphraseGoes() = runComposeUiTest {
        val keys = FakeKeys(device = IPhone, wouldBe = KeyStorage.SealedByChip(Chip.SecureEnclave))
        paste(keys)
        shown("finding:${DiagnosticsCode.KeyLevel2.id}")
        tap("import:keep-passphrase")
        onNodeWithTag("import:keep-stay").assertDoesNotExist()
        tap("import:keep-passphrase")
        onNodeWithTag("import:keep-stay").assertDoesNotExist()
        onNodeWithTag("finding:${DiagnosticsCode.KeyNoSecondLock.id}").assertDoesNotExist()
        add()
        // On, then off again: the default (question 118) — the chip, without the passphrase.
        assertEquals(null to ImportOptions(level = null, keepPassphrase = false), keys.admin.asked.single())
    }

    @Test
    fun anIPhoneMakesAP256InsideTheEnclaveWithNothingToChoose() = runComposeUiTest {
        val keys = FakeKeys(device = IPhone, wouldBe = KeyStorage.SealedByChip(Chip.SecureEnclave))
        open(keys)
        tap("import:create")
        onNodeWithTag("import:where-chip").performScrollTo().assertIsDisplayed()
        tapText("ECDSA P-256 — signs only, made inside the chip")
        onNodeWithTag("import:where-chip").assertDoesNotExist()
        shown("finding:${DiagnosticsCode.KeyLevel1.id}")
        tap("import:create-key")
        assertEquals(GeneratedKind.EcdsaP256 to ImportOptions(), keys.admin.asked.single())
    }

    @Test
    fun aKeyMadeOnAndroidKeepsItsPassphraseLikeAnImportedOne() = runComposeUiTest {
        val keys = FakeKeys()
        open(keys)
        tap("import:create")
        tap("import:create-key")
        assertEquals(GeneratedKind.Ed25519 to ImportOptions(level = 2, keepPassphrase = true), keys.admin.asked.single())
    }

    private fun ComposeUiTest.tapText(text: String) {
        val node = onNode(hasText(text))
        runCatching { node.performScrollTo() }
        node.performClick()
        waitForIdle()
    }

    private companion object {
        const val GOOD = "correct horse battery staple"
    }
}
