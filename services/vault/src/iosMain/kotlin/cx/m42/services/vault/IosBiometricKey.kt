package cx.m42.services.vault

import cx.m42.services.ssh.ios.cfDictionary
import cx.m42.services.ssh.ios.toByteArray
import cx.m42.services.ssh.ios.toCFData
import cx.m42.unitool.kit.crypto.Hash
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFErrorGetCode
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberSInt32Type
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSError
import platform.Foundation.NSUserDefaults
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorBiometryNotEnrolled
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateDecryptedData
import platform.Security.SecKeyCreateEncryptedData
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyRef
import platform.Security.errSecSuccess
import platform.Security.kSecAccessControlBiometryCurrentSet
import platform.Security.kSecAccessControlPrivateKeyUsage
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrTokenID
import platform.Security.kSecAttrTokenIDSecureEnclave
import platform.Security.kSecClass
import platform.Security.kSecClassKey
import platform.Security.kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecReturnRef
import platform.Security.kSecUseAuthenticationContext
import platform.posix.memcpy

/**
 * [BiometricKey] in the Secure Enclave (.ai-notes/cr-note D654): a P-256 made inside it under
 * `PrivateKeyUsage | BiometryCurrentSet` — Face ID or Touch ID as enrolled now, no passcode, no
 * button that offers one — and `WhenPasscodeSetThisDeviceOnly`: in no backup, gone with the passcode.
 *
 * Sealing is ECIES to the public half and asks nothing (D655); opening is the enclave's, with the
 * system's sheet in between. Which error the enclave gives for a key whose faces changed is not
 * something to bet the message on, so — as the keyring does (ssh-new D247) — the set it was made
 * under is remembered beside it, hashed, and a different set is [BiometricOpened.Invalidated] before
 * anything is tried (D656).
 *
 * No enclave — the simulator — is no key: [available] is false and cr-note does nothing (D651).
 */
@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
public class IosBiometricKey(private val tag: String) : BiometricKey {

    private val enclave: Boolean by lazy { probeEnclave() }

    override fun available(): Boolean =
        enclave && LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, null)

    override fun exists(): Boolean = !biometryChanged() && findKey(null)?.also { CFRelease(it) } != null

    override suspend fun create(secret: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        destroy()
        val key = makeKey() ?: error("the Secure Enclave made no key")
        try {
            val public = SecKeyCopyPublicKey(key) ?: error("no public half")
            try {
                memScoped {
                    val failure = alloc<CFErrorRefVar>()
                    val input = secret.toCFData()
                    val sealed = SecKeyCreateEncryptedData(public, ECIES, input, failure.ptr)
                    CFRelease(input)
                    if (sealed == null) {
                        failure.value?.let { CFRelease(it) }
                        error("the enclave did not seal")
                    }
                    val bytes = sealed.toByteArray()
                    CFRelease(sealed)
                    rememberBiometry()
                    bytes
                }
            } finally {
                CFRelease(public)
            }
        } catch (e: Throwable) {
            destroy()
            throw e
        } finally {
            CFRelease(key)
        }
    }

    override suspend fun open(sealed: ByteArray, reason: String, cancel: String): BiometricOpened = withContext(Dispatchers.IO) {
        if (biometryChanged()) return@withContext BiometricOpened.Invalidated
        val context = LAContext().apply {
            localizedReason = reason
            // The passcode would not open the key: no button that offers it.
            localizedFallbackTitle = ""
            localizedCancelTitle = cancel
        }
        val key = findKey(context) ?: return@withContext BiometricOpened.Invalidated
        try {
            memScoped {
                val failure = alloc<CFErrorRefVar>()
                val input = sealed.toCFData()
                val opened = SecKeyCreateDecryptedData(key, ECIES, input, failure.ptr)
                CFRelease(input)
                if (opened == null) return@memScoped refusal(failure)
                val bytes = opened.toByteArray()
                CFRelease(opened)
                BiometricOpened.Opened(bytes)
            }
        } finally {
            CFRelease(key)
        }
    }

    override fun destroy() {
        val tagData = tag.encodeToByteArray().toCFData()
        val query = cfDictionary(kSecClass to kSecClassKey, kSecAttrApplicationTag to tagData)
        SecItemDelete(query)
        CFRelease(query)
        CFRelease(tagData)
        NSUserDefaults.standardUserDefaults.removeObjectForKey(BIOMETRY_PREFIX + tag)
    }

    // ------------------------------------------------------------ the set of faces the key was made under

    /** Null when it cannot be read now (locked out, say), which says nothing about the key; "" when none is enrolled. */
    private fun biometrySet(): String? = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val context = LAContext()
        if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error.ptr)) {
            return@memScoped if (error.value?.code == LAErrorBiometryNotEnrolled) "" else null
        }
        val state = context.evaluatedPolicyDomainState ?: return@memScoped null
        val bytes = ByteArray(state.length.toInt())
        if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), state.bytes, state.length) }
        Hash.SHA256.digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun rememberBiometry() {
        val now = biometrySet()?.takeIf { it.isNotEmpty() } ?: return
        NSUserDefaults.standardUserDefaults.setObject(now, forKey = BIOMETRY_PREFIX + tag)
    }

    private fun biometryChanged(): Boolean {
        val then = NSUserDefaults.standardUserDefaults.stringForKey(BIOMETRY_PREFIX + tag) ?: return false
        val now = biometrySet() ?: return false
        return now != then
    }

    // ------------------------------------------------------------ Security.framework

    private fun refusal(failure: CFErrorRefVar): BiometricOpened {
        val code = failure.value?.let { CFErrorGetCode(it) } ?: 0L
        failure.value?.let { CFRelease(it) }
        return when (code) {
            // errSecUserCanceled, LAErrorUserCancel, LAErrorUserFallback, LAErrorSystemCancel, LAErrorAppCancel
            -128L, -2L, -3L, -4L, -9L -> BiometricOpened.Cancelled
            // LAErrorBiometryNotEnrolled: every face or finger removed, and the key with them
            -7L -> BiometricOpened.Invalidated
            else -> BiometricOpened.Failed("enclave-$code")
        }
    }

    private fun makeKey(): SecKeyRef? = memScoped {
        val access = SecAccessControlCreateWithFlags(
            null,
            kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
            kSecAccessControlPrivateKeyUsage or kSecAccessControlBiometryCurrentSet,
            null,
        ) ?: return@memScoped null
        val size = alloc<IntVar>().apply { value = 256 }
        val bits = CFNumberCreate(null, kCFNumberSInt32Type, size.ptr)
        val tagData = tag.encodeToByteArray().toCFData()
        val privateAttributes = cfDictionary(
            kSecAttrIsPermanent to kCFBooleanTrue,
            kSecAttrApplicationTag to tagData,
            kSecAttrAccessControl to access,
        )
        val attributes = cfDictionary(
            kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits to bits,
            kSecAttrTokenID to kSecAttrTokenIDSecureEnclave,
            kSecPrivateKeyAttrs to privateAttributes,
        )
        val failure = alloc<CFErrorRefVar>()
        val key = SecKeyCreateRandomKey(attributes, failure.ptr)
        failure.value?.let { CFRelease(it) }
        CFRelease(attributes)
        CFRelease(privateAttributes)
        CFRelease(tagData)
        CFRelease(bits)
        CFRelease(access)
        key
    }

    private fun findKey(context: LAContext?): SecKeyRef? = memScoped {
        val tagData = tag.encodeToByteArray().toCFData()
        val contextRef = context?.let { CFBridgingRetain(it) }
        val entries = mutableListOf<Pair<CFTypeRef?, CFTypeRef?>>(
            kSecClass to kSecClassKey,
            kSecAttrApplicationTag to tagData,
            kSecReturnRef to kCFBooleanTrue,
        )
        if (contextRef != null) entries += (kSecUseAuthenticationContext to contextRef)
        val query = cfDictionary(*entries.toTypedArray())
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        CFRelease(tagData)
        contextRef?.let { CFRelease(it) }
        if (status == errSecSuccess) result.value?.reinterpret() else null
    }

    /** A throwaway enclave key, kept nowhere: whether there is an enclave here at all. */
    private fun probeEnclave(): Boolean = memScoped {
        val access = SecAccessControlCreateWithFlags(null, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, kSecAccessControlPrivateKeyUsage, null)
            ?: return@memScoped false
        val size = alloc<IntVar>().apply { value = 256 }
        val bits = CFNumberCreate(null, kCFNumberSInt32Type, size.ptr)
        val privateAttributes = cfDictionary(kSecAttrIsPermanent to kCFBooleanFalse, kSecAttrAccessControl to access)
        val attributes = cfDictionary(
            kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits to bits,
            kSecAttrTokenID to kSecAttrTokenIDSecureEnclave,
            kSecPrivateKeyAttrs to privateAttributes,
        )
        val failure = alloc<CFErrorRefVar>()
        val key = SecKeyCreateRandomKey(attributes, failure.ptr)
        failure.value?.let { CFRelease(it) }
        CFRelease(attributes)
        CFRelease(privateAttributes)
        CFRelease(bits)
        CFRelease(access)
        key?.let { CFRelease(it) }
        key != null
    }

    private companion object {
        val ECIES = kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM

        /** Where the hashed set of faces a key was made under is kept, by tag. Not a secret. */
        const val BIOMETRY_PREFIX = "cx.m42.services.vault.biometry."
    }
}
