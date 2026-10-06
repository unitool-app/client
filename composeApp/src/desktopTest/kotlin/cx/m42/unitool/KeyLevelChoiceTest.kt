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
import cx.m42.unitool.settings.SshKeysScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * "Where it will live" in the wizard (09, D237–D247), on a phone made of data: what is offered,
 * what is chosen by default, that a key in the chip always keeps a lock of its own — a biometric or
 * its passphrase — and the [ImportOptions] the keyring is handed in the end. That the keyring
 * honours them is KeyringTest's.
 */
@OptIn(ExperimentalTestApi::class)
class KeyLevelChoiceTest : UnitoolTest() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun host(keys: FakeKeys): Superizer {
        val tools = HostTools()
        return buildUnitool(scope, debug = true, tools = tools, network = ratesNetwork()) {
            secretVault(FakeSecretVault())
            hostScreen(SshKeysScreen(keys, tools))
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
        // SSH keys are not in Settings: their promo code opens them (idea/09).
        onNodeWithContentDescription("Premium").performClick()
        waitForIdle()
        tap("activate:have-code")
        onNodeWithTag("activate:promo").performTextInput("{ssh-keys}")
        tap("activate:apply")
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
        onNodeWithTag("import:passphrase-required").assertDoesNotExist()
        add()
        assertEquals(null to ImportOptions(level = null, keepPassphrase = false, biometricOnly = true), keys.admin.asked.single())
    }

    @Test
    fun theFingerprintOffMakesThePassphraseRequiredNotTheScreenLock() = runComposeUiTest {
        // D247: there is no "turn it off anyway" — without the finger, the key's passphrase is its lock.
        val keys = FakeKeys(device = AndroidWithFingerprint)
        paste(keys)
        tap("import:biometric-only")
        shown("import:passphrase-required")
        shown("finding:${DiagnosticsCode.KeyLevel2.id}")
        onNodeWithTag("finding:${DiagnosticsCode.KeyBiometricOnly.id}").assertDoesNotExist()
        tap("import:keep-passphrase")
        add()
        assertEquals(null to ImportOptions(level = 2, keepPassphrase = true, biometricOnly = false), keys.admin.asked.single())
    }

    @Test
    fun theFingerprintAndThePassphraseTogether() = runComposeUiTest {
        val keys = FakeKeys(device = AndroidWithFingerprint)
        paste(keys)
        tap("import:keep-passphrase")
        shown("finding:${DiagnosticsCode.KeyLevel2.id}")
        shown("finding:${DiagnosticsCode.KeyBiometricOnly.id}")
        add()
        assertEquals(null to ImportOptions(level = 2, keepPassphrase = true, biometricOnly = true), keys.admin.asked.single())
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
    fun androidWithoutAFingerprintKeepsTheKeyUnderItsPassphraseAndCannotDropIt() = runComposeUiTest {
        val keys = FakeKeys()
        paste(keys)
        shown("import:where-chip")
        shown("import:where-passphrase")
        // The chip could hold it (level 1); with its passphrase it is sealed by the same chip.
        shown("finding:${DiagnosticsCode.KeyLevel2.id}")
        shown("import:passphrase-required")
        onNodeWithTag("import:biometric-only").assertDoesNotExist()
        tap("import:keep-passphrase")
        add()
        assertEquals(null to ImportOptions(level = 2, keepPassphrase = true), keys.admin.asked.single(), "the switch did not move")
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
    fun anIPhoneWithoutFaceIdKeepsTheKeyUnderItsPassphrase() = runComposeUiTest {
        val keys = FakeKeys(device = IPhone, wouldBe = KeyStorage.SealedByChip(Chip.SecureEnclave))
        paste(keys)
        shown("finding:${DiagnosticsCode.KeyLevel2.id}")
        shown("import:passphrase-required")
        add()
        assertEquals(null to ImportOptions(level = 2, keepPassphrase = true), keys.admin.asked.single())
    }

    @Test
    fun anIPhoneWithFaceIdLetsOnlyItOpenTheKeyByDefault() = runComposeUiTest {
        val keys = FakeKeys(device = IPhoneWithFaceId, wouldBe = KeyStorage.SealedByChip(Chip.SecureEnclave))
        paste(keys)
        shown("finding:${DiagnosticsCode.KeyLevel2.id}")
        shown("finding:${DiagnosticsCode.KeyBiometricOnly.id}")
        onNode(hasText("Only Face ID or Touch ID — not the passcode")).assertExists()
        add()
        assertEquals(null to ImportOptions(level = null, keepPassphrase = false, biometricOnly = true), keys.admin.asked.single())
    }

    @Test
    fun anIPhoneWithFaceIdOffMakesThePassphraseRequiredNotThePasscode() = runComposeUiTest {
        val keys = FakeKeys(device = IPhoneWithFaceId, wouldBe = KeyStorage.SealedByChip(Chip.SecureEnclave))
        paste(keys)
        tap("import:biometric-only")
        shown("import:passphrase-required")
        add()
        assertEquals(null to ImportOptions(level = 2, keepPassphrase = true, biometricOnly = false), keys.admin.asked.single())
    }

    @Test
    fun anIPhoneWithFaceIdMakesAP256OnlyItOpens() = runComposeUiTest {
        val keys = FakeKeys(device = IPhoneWithFaceId, wouldBe = KeyStorage.SealedByChip(Chip.SecureEnclave))
        open(keys)
        tap("import:create")
        tapText("ECDSA P-256 — signs only, made inside the chip")
        shown("finding:${DiagnosticsCode.KeyLevel1.id}")
        shown("finding:${DiagnosticsCode.KeyBiometricOnly.id}")
        tap("import:create-key")
        assertEquals(GeneratedKind.EcdsaP256 to ImportOptions(biometricOnly = true), keys.admin.asked.single())
    }

    @Test
    fun anIPhoneWithoutFaceIdHasNoP256ToMake() = runComposeUiTest {
        // It would have no lock of its own: no file for a passphrase, no biometric (D247).
        val keys = FakeKeys(device = IPhone, wouldBe = KeyStorage.SealedByChip(Chip.SecureEnclave))
        open(keys)
        tap("import:create")
        onNode(hasText("ECDSA P-256 — signs only, made inside the chip")).assertDoesNotExist()
    }

    @Test
    fun aKeyMadeOnAndroidWithoutAFingerprintKeepsItsPassphraseLikeAnImportedOne() = runComposeUiTest {
        val keys = FakeKeys()
        open(keys)
        tap("import:create")
        onNode(hasText("ECDSA P-256 — signs only, made inside the chip")).assertDoesNotExist()
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
