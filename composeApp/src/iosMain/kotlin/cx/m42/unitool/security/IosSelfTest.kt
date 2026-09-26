package cx.m42.unitool.security

import cx.m42.services.ssh.diagnostics.PlatformSelfTest
import cx.m42.services.ssh.keyring.SecureEnclaveSelfTest
import cx.m42.services.vault.IosKeychainVault
import cx.m42.superizer.diagnostics.DeviceFacts
import cx.m42.superizer.diagnostics.SelfTestCode
import cx.m42.superizer.diagnostics.SelfTestStep
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.UIKit.UIDevice

/**
 * The self-test's iOS half (06 §3, §4): the vault with a throwaway Secure Enclave key — the answer
 * to "does `IosKeychainVault` run at all" (ios/01 §4) — and the enclave's own steps: a wrapping key
 * that exists only with a passcode, refuses with nobody there and opens with the person, and a P-256
 * whose signature verifies as SSH's. Its report, shared from the phone, is how iOS gets checked
 * without a Mac (D180). Written, not yet run.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosSelfTest(private val confirmReason: () -> String) : PlatformSelfTest {

    override val device: DeviceFacts
        get() = DeviceFacts(
            model = UIDevice.currentDevice.model,
            os = "${UIDevice.currentDevice.systemName} ${UIDevice.currentDevice.systemVersion}",
        )

    override suspend fun vaultSteps(): List<SelfTestStep> {
        val vault = IosKeychainVault("cx.m42.services.vault.selftest." + now())
        try {
            return listOf(
                timed(SelfTestCode.VaultRoundtrip) {
                    val text = "self-test " + now()
                    if (vault.open(vault.seal(text)) == text) null else "did not open to the same text"
                },
                timed(SelfTestCode.VaultHardware) { if (vault.hardware() != null) null else "the Keychain did not say where the key is" }
                    .let { it.copy(evidence = it.evidence ?: vault.hardware()) },
                timed(SelfTestCode.VaultTamper) {
                    val sealed = vault.seal("tamper")
                    if (vault.open(flipOneByte(sealed)) == null) null else "a changed secret opened"
                },
                timed(SelfTestCode.VaultNonce) { if (vault.seal("same") != vault.seal("same")) null else "sealed the same way twice" },
            )
        } finally {
            vault.deleteKey()
        }
    }

    override suspend fun chipSteps(withConfirmation: Boolean): List<SelfTestStep> = withContext(Dispatchers.IO) {
        val passcode = LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, null)
        val codes = listOf(SelfTestCode.WrapPasscodeClass, SelfTestCode.WrapAuthRequired, SelfTestCode.WrapConfirmed)
        val started = now()
        val wrap = SecureEnclaveSelfTest.wrap(passcode, if (withConfirmation) confirmReason() else null)
        val millis = now() - started
        val steps = codes.zip(wrap) { code, check -> SelfTestStep(code.id, check.passed, if (check.ran) millis else 0, check.evidence, check.ran) }
        val signStarted = now()
        val sign = SecureEnclaveSelfTest.sign()
        steps + SelfTestStep(SelfTestCode.EnclaveSign.id, sign.passed, now() - signStarted, sign.evidence)
    }

    override suspend fun storageStep(): SelfTestStep =
        SelfTestStep(SelfTestCode.StorageLocation.id, passed = true, millis = 0, evidence = "Application Support, NSFileProtectionComplete, excluded from backup")

    private suspend fun timed(code: SelfTestCode, block: suspend () -> String?): SelfTestStep {
        val started = now()
        return try {
            val failure = block()
            SelfTestStep(code.id, passed = failure == null, millis = now() - started, evidence = failure)
        } catch (e: Throwable) {
            SelfTestStep(code.id, passed = false, millis = now() - started, evidence = "${e::class.simpleName}: ${e.message}".take(300))
        }
    }

    private fun now(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
}
