package cx.m42.unitool.security

import android.annotation.TargetApi
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cx.m42.services.ssh.diagnostics.PlatformSelfTest
import cx.m42.services.ssh.diagnostics.SkipReason
import cx.m42.services.ssh.probe.KeystoreProbe
import cx.m42.services.vault.AndroidKeystoreVault
import cx.m42.superizer.diagnostics.DeviceFacts
import cx.m42.superizer.diagnostics.SelfTestCode
import cx.m42.superizer.diagnostics.SelfTestStep
import cx.m42.unitool.R
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/**
 * The self-test's Android half (06 §3): the vault with a throwaway key, a wrapping key that must
 * refuse without the person and work with them, where the keyring's records are, and step 0's
 * probe (P1–P8) as device facts — so a tester's report answers the questions the table in 04 §2
 * still has open for real phones.
 */
internal class AndroidSelfTest(
    private val context: Context,
    private val activity: () -> Activity?,
    private val promptTitle: () -> String,
) : PlatformSelfTest {

    private val keystore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
    private val lock: Boolean get() = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    override val device: DeviceFacts
        get() = DeviceFacts(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            os = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            facts = probeFacts,
        )

    private var probeFacts: Map<String, String> = emptyMap()

    override suspend fun vaultSteps(): List<SelfTestStep> = withContext(Dispatchers.IO) {
        val alias = "cx.m42.services.vault.selftest." + System.nanoTime()
        val vault = AndroidKeystoreVault(alias)
        try {
            listOf(
                timed(SelfTestCode.VaultRoundtrip) {
                    val text = "self-test " + System.nanoTime()
                    if (vault.open(vault.seal(text)) == text) null else "did not open to the same text"
                },
                timed(SelfTestCode.VaultHardware) {
                    // Where the key lives is a *finding* (vault.chip.ok or vault.software-key); the step
                    // only checks that the Keystore says.
                    if (vault.hardware() != null) null else "the Keystore did not say where the key is"
                }.let { it.copy(evidence = it.evidence ?: vault.hardware()) },
                timed(SelfTestCode.VaultTamper) {
                    val sealed = vault.seal("tamper")
                    if (vault.open(flipOneByte(sealed)) == null) null else "a changed secret opened"
                },
                timed(SelfTestCode.VaultNonce) {
                    if (vault.seal("same") != vault.seal("same")) null else "the same text sealed the same way twice"
                },
            )
        } finally {
            vault.deleteKey()
        }
    }

    override suspend fun chipSteps(withConfirmation: Boolean): List<SelfTestStep> = withContext(Dispatchers.IO) {
        val steps = mutableListOf<SelfTestStep>()
        val alias = "cx.m42.services.ssh.selftest." + System.nanoTime()
        try {
            if (Build.VERSION.SDK_INT < 30) {
                listOf(SelfTestCode.WrapAuthRequired, SelfTestCode.WrapConfirmed, SelfTestCode.WrapPasscodeClass).forEach {
                    steps += SelfTestStep(it.id, passed = true, millis = 0, evidence = "API ${Build.VERSION.SDK_INT}: keys are level 3 here", ran = false)
                }
            } else if (!lock) {
                steps += SelfTestStep(SelfTestCode.WrapAuthRequired.id, passed = true, millis = 0, evidence = SkipReason.NO_SCREEN_LOCK, ran = false)
                steps += SelfTestStep(SelfTestCode.WrapConfirmed.id, passed = true, millis = 0, evidence = SkipReason.NO_SCREEN_LOCK, ran = false)
                steps += timed(SelfTestCode.WrapPasscodeClass) {
                    // Without a lock the Keystore must refuse a key that needs one (P7, 01 §5.6).
                    val made = runCatching { wrapKey(alias) }
                    if (made.isFailure) null else "a key bound to the person was made with no screen lock"
                }.let { it.copy(evidence = "no screen lock: refused, as it should be") }
            } else {
                steps += timed(SelfTestCode.WrapPasscodeClass) { wrapKey(alias); null }
                var refusal: String? = null
                steps += timed(SelfTestCode.WrapAuthRequired) {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystore.getKey(alias, null) as javax.crypto.SecretKey) }
                    val result = runCatching { cipher.doFinal(byteArrayOf(1, 2, 3)) }
                    refusal = result.exceptionOrNull()?.let { e -> generateSequence(e) { it.cause }.map { "${it::class.simpleName}: ${it.message}" }.joinToString(" ← ").take(300) }
                    if (result.isFailure) null else "the chip encrypted without the person (I7)"
                }.let { it.copy(evidence = refusal) }
                steps += if (withConfirmation) {
                    timed(SelfTestCode.WrapConfirmed) {
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystore.getKey(alias, null) as javax.crypto.SecretKey) }
                        if (!confirm(cipher)) return@timed "not confirmed"
                        cipher.doFinal(byteArrayOf(1, 2, 3))
                        null
                    }
                } else {
                    SelfTestStep(SelfTestCode.WrapConfirmed.id, passed = true, millis = 0, evidence = SkipReason.NEEDS_CONFIRMATION, ran = false)
                }
            }
            steps += SelfTestStep(SelfTestCode.EnclaveSign.id, passed = true, millis = 0, evidence = "iOS only", ran = false)
            probeFacts = runCatching {
                KeystoreProbe(context).run(screenLockSet = lock).filter { it.code != "device" }
                    .groupBy { it.code }.mapValues { (_, answers) -> answers.joinToString("; ") { a -> a.verdict.name + (if (a.code == "P8") " " + a.detail.take(80) else "") } }
                    .mapKeys { "probe.${it.key}" }
            }.getOrElse { mapOf("probe" to "failed: ${it::class.simpleName}") }
        } finally {
            runCatching { keystore.deleteEntry(alias) }
        }
        steps
    }

    override suspend fun storageStep(): SelfTestStep = timed(SelfTestCode.StorageLocation) {
        // I10, read off this APK: both sets of backup rules take only what they name, and the
        // keyring's prefs file is not named (impl, 03 §2 — release lint forbids an exclude here).
        val problems = listOf(R.xml.backup_rules, R.xml.data_extraction_rules).mapNotNull { id -> includeOnlyProblem(id, "cx.m42.services.ssh.xml") }
        problems.firstOrNull()
    }.let { it.copy(evidence = it.evidence ?: "shared_prefs/cx.m42.services.ssh.xml: in no backup, in no transfer") }

    /** Null when every section of the rules [id] includes something, never a whole domain, and never [path]. */
    private fun includeOnlyProblem(id: Int, path: String): String? {
        val parser = context.resources.getXml(id)
        val included = mutableMapOf<String, MutableList<String>>()
        val sections = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "full-backup-content", "cloud-backup", "device-transfer" -> sections += parser.name
                "include" -> included.getOrPut(sections.lastOrNull() ?: "?") { mutableListOf() } += parser.getAttributeValue(null, "path") ?: "."
            }
        }
        for (section in sections) {
            val paths = included[section].orEmpty()
            if (paths.isEmpty()) return "$section includes everything"
            if (paths.any { it == "." || it.isEmpty() || it == "/" }) return "$section includes a whole domain"
            if (path in paths) return "$section includes the keyring's records"
        }
        return null
    }

    @TargetApi(30)
    private fun wrapKey(alias: String) {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    .setUnlockedDeviceRequired(true)
                    .setInvalidatedByBiometricEnrollment(false)
                    .build(),
            )
        }.generateKey()
    }

    @TargetApi(30)
    private suspend fun confirm(cipher: Cipher): Boolean {
        val host: Context = activity() ?: context
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val cancel = CancellationSignal()
                continuation.invokeOnCancellation { cancel.cancel() }
                BiometricPrompt.Builder(host)
                    .setTitle(promptTitle())
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()
                    .authenticate(BiometricPrompt.CryptoObject(cipher), cancel, host.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    })
            }
        }
    }

    private suspend fun timed(code: SelfTestCode, block: suspend () -> String?): SelfTestStep {
        val started = System.currentTimeMillis()
        return try {
            val failure = block()
            SelfTestStep(code.id, passed = failure == null, millis = System.currentTimeMillis() - started, evidence = failure)
        } catch (e: Exception) {
            SelfTestStep(code.id, passed = false, millis = System.currentTimeMillis() - started, evidence = "${e::class.simpleName}: ${e.message}".take(300))
        }
    }
}
