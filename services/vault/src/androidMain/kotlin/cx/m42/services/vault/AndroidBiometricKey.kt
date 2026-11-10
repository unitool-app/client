package cx.m42.services.vault

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * [BiometricKey] in Android's Keystore (.ai-notes/cr-note D654): an RSA-2048 pair made inside it,
 * whose private half decrypts only inside a `BiometricPrompt` with a `CryptoObject` for exactly that
 * one `Cipher` — a strong biometric, timeout zero, no screen-lock credential — and is invalidated by
 * the Keystore itself when a fingerprint or a face is added or removed (D656).
 *
 * RSA-OAEP rather than an AES key, so that sealing asks nothing: the public half encrypts in this
 * process, and that is what lets `178500` make a new key and seal a new root to it without a second
 * sheet (D655). OAEP with SHA-256 and MGF1 with SHA-1, the one OAEP every Keystore decrypts.
 *
 * In the TEE, not StrongBox: making an RSA key in StrongBox takes seconds, and a pause after the
 * code that wipes everything would say what it did. API 30+ only, as the keyring's chip keys
 * (`setUserAuthenticationParameters`); an older phone has no [available] key.
 */
public class AndroidBiometricKey(
    context: Context,
    private val activity: () -> Activity?,
    private val alias: String,
) : BiometricKey {

    private val app = context.applicationContext
    private val keystore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    override fun available(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val lock = app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        val strong = app.getSystemService(BiometricManager::class.java)
            ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        return lock && strong
    }

    override fun exists(): Boolean = runCatching { keystore.containsAlias(alias) }.getOrDefault(false)

    override suspend fun create(secret: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= 30) { "API 30+" }
        destroy()
        try {
            val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, PROVIDER).apply {
                initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                        .setUserAuthenticationRequired(true)
                        .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                        .setInvalidatedByBiometricEnrollment(true)
                        .build(),
                )
            }.generateKeyPair()
            // The public half copied out of the Keystore, so the encryption runs in this process and
            // nothing about it waits for a person.
            val public = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(pair.public.encoded))
            Cipher.getInstance(OAEP).apply { init(Cipher.ENCRYPT_MODE, public, OAEP_SPEC) }.doFinal(secret)
        } catch (e: Exception) {
            destroy()
            throw e
        }
    }

    override suspend fun open(sealed: ByteArray, reason: String, cancel: String): BiometricOpened = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 30) return@withContext BiometricOpened.Failed("api")
        val cipher = try {
            val private = keystore.getKey(alias, null) as? PrivateKey ?: return@withContext BiometricOpened.Invalidated
            Cipher.getInstance(OAEP).apply { init(Cipher.DECRYPT_MODE, private, OAEP_SPEC) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            return@withContext BiometricOpened.Invalidated
        } catch (e: Exception) {
            return@withContext BiometricOpened.Failed(e::class.simpleName ?: "keystore")
        }
        when (val shown = prompt(reason, cancel, BiometricPrompt.CryptoObject(cipher))) {
            null -> try {
                BiometricOpened.Opened(cipher.doFinal(sealed))
            } catch (e: KeyPermanentlyInvalidatedException) {
                BiometricOpened.Invalidated
            } catch (e: Exception) {
                BiometricOpened.Failed(e::class.simpleName ?: "decrypt")
            }
            else -> shown
        }
    }

    override fun destroy() {
        runCatching { keystore.deleteEntry(alias) }
    }

    /** The platform's sheet, strong biometrics only, with [crypto] as the one operation it allows. Null when it said yes. */
    private suspend fun prompt(reason: String, cancel: String, crypto: BiometricPrompt.CryptoObject): BiometricOpened? {
        val context: Context = activity() ?: return BiometricOpened.Failed("no-activity")
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                val prompt = BiometricPrompt.Builder(context)
                    .setTitle(reason)
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    // With a biometric alone Android wants a button to say no with, told to its own listener.
                    .setNegativeButton(cancel, context.mainExecutor) { _, _ ->
                        if (continuation.isActive) continuation.resume(BiometricOpened.Cancelled)
                    }
                    .setConfirmationRequired(false)
                    .build()
                val callback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!continuation.isActive) return
                        continuation.resume(
                            when (errorCode) {
                                BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
                                BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT -> BiometricOpened.Cancelled
                                else -> BiometricOpened.Failed("prompt-$errorCode")
                            },
                        )
                    }
                    // onAuthenticationFailed is one finger or one look that did not match: the sheet stays up.
                }
                runCatching { prompt.authenticate(crypto, signal, context.mainExecutor, callback) }
                    .onFailure { if (continuation.isActive) continuation.resume(BiometricOpened.Failed("prompt")) }
            }
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val OAEP = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        val OAEP_SPEC = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    }
}
