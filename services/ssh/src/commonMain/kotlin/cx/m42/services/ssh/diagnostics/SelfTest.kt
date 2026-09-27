package cx.m42.services.ssh.diagnostics

import cx.m42.services.ssh.Age
import cx.m42.services.ssh.SecretBytes
import cx.m42.services.ssh.SshSig
import cx.m42.services.ssh.crypto.secureRandomBytes
import cx.m42.services.ssh.key.OpenSshKeyFile
import cx.m42.services.ssh.key.PrivateKey
import cx.m42.services.ssh.key.SoftwareOps
import cx.m42.superizer.diagnostics.DeviceFacts
import cx.m42.superizer.diagnostics.SelfTestCode
import cx.m42.superizer.diagnostics.SelfTestResult
import cx.m42.superizer.diagnostics.SelfTestRunner
import cx.m42.superizer.diagnostics.SelfTestStep
import cx.m42.superizer.runtime.Clock
import kotlin.io.encoding.Base64

/**
 * What only a platform can check about itself (06 §3): its vault, its chip, where its files are.
 * Android and iOS implement it; desktop and the web have none of these, and their steps say so
 * (`ran = false`) rather than pass.
 */
public interface PlatformSelfTest {
    /** `vault.roundtrip`, `vault.hardware`, `vault.tamper`, `vault.nonce` — with a throwaway key of the platform's vault. */
    public suspend fun vaultSteps(): List<SelfTestStep>

    /** `wrap.auth-required`, `wrap.confirmed` (only [withConfirmation]), `wrap.passcode-class`, `enclave.sign`. */
    public suspend fun chipSteps(withConfirmation: Boolean): List<SelfTestStep>

    /** `storage.location`: where the records are and whether a backup would carry them. */
    public suspend fun storageStep(): SelfTestStep

    public val device: DeviceFacts
}

/**
 * Why a step did not run, as that step's evidence. The report carries these as they are; the host's
 * screen gives the ones a person can do something about words of its own, and reads anything else
 * as "not on this platform".
 */
public object SkipReason {
    /** The run at start asks nobody (06 §2); the button in Settings does. */
    public const val NEEDS_CONFIRMATION: String = "run from Settings to confirm"

    /** A key bound to the person cannot exist here until the device has a screen lock. */
    public const val NO_SCREEN_LOCK: String = "no screen lock"

    /** An earlier step of the same check failed, and this one had nothing to work on. */
    public const val AFTER_FAILURE: String = "the step before it failed"
}

/**
 * The self-test (06 §3, D178): the real cryptography, on this device, with throwaway keys only —
 * one implementation for Settings → Run self-test, for step 0's instrumented run, and for the iPhone
 * report. Every step records how long it took and, when it fails, the platform's own words.
 *
 * [report] is the host's diagnostics text; `report.clean` scans it for this run's own secrets,
 * which proves the report does not carry what the self-test just held (I20).
 */
public class SelfTest(
    private val clock: Clock,
    private val hostVersion: String,
    private val platform: PlatformSelfTest?,
    private val report: () -> String,
    /** Seconds of bcrypt at which `passphrase.slow` is raised (06 §3). */
    private val slowMillis: Long = 3_000,
) : SelfTestRunner {

    /** How long the last `passphrase.roundtrip` took — the keyring's diagnostics read it for `passphrase.slow`. */
    public var lastPassphraseMillis: Long? = null
        private set

    override suspend fun run(withConfirmation: Boolean): SelfTestResult {
        val steps = mutableListOf<SelfTestStep>()
        val seed = secureRandomBytes(32)
        val passphrase = secureRandomBytes(12).joinToString("") { (it.toInt() and 0x3f).let { v -> ALPHABET[v] }.toString() }.toCharArray()
        try {
            steps += platform?.vaultSteps() ?: notRun(SelfTestCode.VaultRoundtrip, SelfTestCode.VaultHardware, SelfTestCode.VaultTamper, SelfTestCode.VaultNonce)
            steps += platform?.chipSteps(withConfirmation) ?: notRun(SelfTestCode.WrapAuthRequired, SelfTestCode.WrapConfirmed, SelfTestCode.WrapPasscodeClass, SelfTestCode.EnclaveSign)
            steps += step(SelfTestCode.PassphraseRoundtrip) { passphraseRoundtrip(seed, passphrase) }
            steps += step(SelfTestCode.AgeRoundtrip) { ageRoundtrip(seed) }
            steps += platform?.storageStep() ?: SelfTestStep(SelfTestCode.StorageLocation.id, passed = true, millis = 0, evidence = "no platform files", ran = false)
            steps += step(SelfTestCode.MemoryZeroed) { memoryZeroed() }
            steps += step(SelfTestCode.ReportClean) { reportClean(seed, passphrase) }
        } finally {
            seed.fill(0)
            passphrase.fill('\u0000')
        }
        return SelfTestResult(steps, clock.now(), hostVersion, platform?.device ?: DeviceFacts())
    }

    private fun notRun(vararg codes: SelfTestCode): List<SelfTestStep> =
        codes.map { SelfTestStep(it.id, passed = true, millis = 0, evidence = "nothing to test here", ran = false) }

    private suspend fun step(code: SelfTestCode, block: suspend () -> String?): SelfTestStep {
        val started = clock.now()
        return try {
            val failure = block()
            SelfTestStep(code.id, passed = failure == null, millis = clock.now() - started, evidence = failure)
        } catch (e: Throwable) {
            SelfTestStep(code.id, passed = false, millis = clock.now() - started, evidence = "${e::class.simpleName}: ${e.message}".take(300))
        }
    }

    /** A throwaway Ed25519 key through `openssh-key-v1` with 24 bcrypt rounds and back; its time is P9/P14. */
    private fun passphraseRoundtrip(seed: ByteArray, passphrase: CharArray): String? {
        val key = PrivateKey.ed25519(seed, "self-test")
        val file = key.use { SecretBytes.utf8(passphrase).use { secret -> OpenSshKeyFile.write(it, secret) } }
        val started = clock.now()
        val parsed = OpenSshKeyFile.parse(file) as? OpenSshKeyFile.Parsed.Ok ?: return "the file did not parse"
        val opened = SecretBytes.utf8(passphrase).use { parsed.file.decrypt(it) }
        lastPassphraseMillis = clock.now() - started
        file.fill(0)
        val back = (opened as? OpenSshKeyFile.Decrypted.Ok)?.key ?: return "did not open: $opened"
        back.use { k -> k.ed25519Seed().use { if (!it.bytes.contentEquals(seed)) return "opened to another key" } }
        if (lastPassphraseMillis!! > slowMillis) return null // slow is a finding (passphrase.slow), not a failure
        return null
    }

    /** age to the throwaway key and back, in commonMain: the host's vault without a chip works (06 §3). */
    private suspend fun ageRoundtrip(seed: ByteArray): String? {
        val key = PrivateKey.ed25519(seed, "self-test")
        return key.use {
            val text = "self-test " + clock.now()
            val sealed = Age.encrypt(text.encodeToByteArray(), listOf(it.publicKey))
            val identity = SoftwareOps.ageIdentity(it) ?: return "no identity"
            val opened = Age.decrypt(sealed, listOf(identity))
            if ((opened as? Age.Opened.Ok)?.plaintext?.decodeToString() != text) return "did not open: $opened"
            val signature = SoftwareOps.sshsig(it, "self-test@superizer", text.encodeToByteArray()) ?: return "did not sign"
            if (SshSig.verify(signature, text.encodeToByteArray(), "self-test@superizer", it.publicKey) != SshSig.SigCheck.Valid) return "signature did not verify"
            null
        }
    }

    /** `SecretBytes` is zeros once closed (I6); on wasm this is what can be checked, not the JS heap (02 §9). */
    private fun memoryZeroed(): String? {
        val raw = secureRandomBytes(32)
        val held = SecretBytes(raw)
        held.close()
        return if (raw.all { it == 0.toByte() }) null else "SecretBytes kept its bytes after close"
    }

    /** The report must not carry this run's seed or passphrase, in any of the forms a leak takes. */
    private fun reportClean(seed: ByteArray, passphrase: CharArray): String? {
        val text = report()
        val secretText = SecretBytes.utf8(passphrase)
        return secretText.use { pass ->
            val needles = listOf(seed, pass.bytes).flatMap { secret ->
                listOf(
                    secret,
                    Base64.encodeToByteArray(secret),
                    secret.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }.encodeToByteArray(),
                )
            }
            val haystack = text.encodeToByteArray()
            if (needles.any { contains(haystack, it) }) "the report carries a secret of this run" else null
        }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (k in needle.indices) if (haystack[i + k] != needle[k]) continue@outer
            return true
        }
        return false
    }

    private companion object {
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
    }
}
