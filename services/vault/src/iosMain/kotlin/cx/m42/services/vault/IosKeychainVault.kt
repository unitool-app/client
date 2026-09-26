package cx.m42.services.vault

import cx.m42.services.ssh.ios.cfDictionary
import cx.m42.services.ssh.ios.toByteArray
import cx.m42.services.ssh.ios.toCFData
import cx.m42.superizer.secrets.SecretVault
import kotlin.io.encoding.Base64
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberSInt32Type
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecKeyCopyAttributes
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateDecryptedData
import platform.Security.SecKeyCreateEncryptedData
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyRef
import platform.Security.errSecSuccess
import platform.Security.kSecAccessControlPrivateKeyUsage
import platform.Security.kSecAttrAccessControl
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

/**
 * The iOS half of [SecretVault]: a P-256 key made inside the Secure Enclave, where the device has
 * one, and used there — the private half cannot be read out by this app, by a backup, or by anyone
 * holding a copy of the app's files. Same promise as `AndroidKeystoreVault`, and for the same reason.
 *
 * Sealing is ECIES (`…CofactorVariableIVX963SHA256AESGCM`): an ephemeral key agreed with the
 * enclave's public key, and AES-GCM under what comes out. Opening needs the private key, which only
 * the enclave can use. The simulator has no enclave, so there the same key lives in the Keychain as
 * a software key — still `ThisDeviceOnly`, still never in a backup.
 *
 * Sealed text is `e1:` and Base64 of what Security returned. A prefix of its own rather than
 * Android's `v1:`: the two are different formats, and a blob that somehow crossed platforms should
 * be refused by the prefix rather than fail inside the cipher.
 *
 * [tag] is the key's name in the Keychain — a parameter so a test can use its own and delete it.
 */
@OptIn(ExperimentalForeignApi::class)
public class IosKeychainVault(private val tag: String = DEFAULT_TAG) : SecretVault, HardwareVault {

    override suspend fun seal(plaintext: String): String = withContext(Dispatchers.IO) {
        val key = key()
        val public = SecKeyCopyPublicKey(key) ?: error("the vault key has no public half")
        try {
            val input = plaintext.encodeToByteArray().toCFData()
            try {
                memScoped {
                    val failure = alloc<CFErrorRefVar>()
                    val sealed = SecKeyCreateEncryptedData(public, ALGORITHM, input, failure.ptr)
                        ?: error("the Keychain refused to seal")
                    try {
                        PREFIX + Base64.encode(sealed.toByteArray())
                    } finally {
                        CFRelease(sealed)
                    }
                }
            } finally {
                CFRelease(input)
            }
        } finally {
            CFRelease(public)
            CFRelease(key)
        }
    }

    override suspend fun open(sealed: String): String? = withContext(Dispatchers.IO) {
        if (!sealed.startsWith(PREFIX)) return@withContext null
        val body = runCatching { Base64.decode(sealed, startIndex = PREFIX.length) }.getOrNull()
            ?: return@withContext null
        val key = existingKey() ?: return@withContext null
        try {
            val input = body.toCFData()
            try {
                memScoped {
                    val failure = alloc<CFErrorRefVar>()
                    val opened = SecKeyCreateDecryptedData(key, ALGORITHM, input, failure.ptr)
                        ?: return@memScoped null
                    try {
                        runCatching { opened.toByteArray().decodeToString(throwOnInvalidSequence = true) }.getOrNull()
                    } finally {
                        CFRelease(opened)
                    }
                }
            } finally {
                CFRelease(input)
            }
        } finally {
            CFRelease(key)
        }
    }

    /**
     * Where the key lives (05 §3.4): `secure-enclave` when the Keychain made it there, `software` for
     * the fallback (the simulator, a device without an enclave). Null before the first seal.
     */
    override fun hardware(): String? {
        val key = existingKey() ?: return null
        return try {
            val attributes = SecKeyCopyAttributes(key) ?: return "unknown"
            try {
                val token = CFDictionaryGetValue(attributes, kSecAttrTokenID)
                // Compared as strings: `kSecAttrTokenIDSecureEnclave` is "com.apple.setoken", and a CFString
                // handed back need not be the same object as the constant.
                val name = token?.let { platform.Foundation.CFBridgingRelease(platform.CoreFoundation.CFRetain(it)) as? String }
                val enclave = platform.Foundation.CFBridgingRelease(platform.CoreFoundation.CFRetain(kSecAttrTokenIDSecureEnclave)) as? String
                if (name != null && name == enclave) "secure-enclave" else "software"
            } finally {
                CFRelease(attributes)
            }
        } finally {
            CFRelease(key)
        }
    }

    /** Removes the key, and with it everything it sealed. For tests, and for nothing else. */
    public fun deleteKey() {
        val query = dictionary(
            kSecClass to kSecClassKey,
            kSecAttrApplicationTag to tagData(),
        )
        SecItemDelete(query)
        CFRelease(query)
    }

    /**
     * The key, made on the first call. Under a lock: two first seals racing would each make one,
     * and whatever the loser sealed would be locked for good — the Android vault's reason, too.
     */
    private suspend fun key(): SecKeyRef = lock.withLock { existingKey() ?: generate() }

    private fun existingKey(): SecKeyRef? = memScoped {
        val query = dictionary(
            kSecClass to kSecClassKey,
            kSecAttrApplicationTag to tagData(),
            kSecReturnRef to kCFBooleanTrue,
        )
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        if (status == errSecSuccess) result.value?.reinterpret() else null
    }

    private fun generate(): SecKeyRef =
        // The enclave first. Where there is none — the simulator, some very old devices — the
        // Keychain holds a software key with the same access class.
        create(secureEnclave = true) ?: create(secureEnclave = false) ?: error("the Keychain could not make a key")

    private fun create(secureEnclave: Boolean): SecKeyRef? = memScoped {
        val flags = if (secureEnclave) kSecAccessControlPrivateKeyUsage else 0uL
        val access = SecAccessControlCreateWithFlags(null, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, flags, null)
            ?: return@memScoped null
        val size = alloc<IntVar>().apply { value = 256 }
        val bits = CFNumberCreate(null, kCFNumberSInt32Type, size.ptr)
        val tagData = tagData()
        val privateAttributes = dictionary(
            kSecAttrIsPermanent to kCFBooleanTrue,
            kSecAttrApplicationTag to tagData,
            kSecAttrAccessControl to access,
        )
        val attributes = dictionary(
            kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits to bits,
            kSecPrivateKeyAttrs to privateAttributes,
        )
        if (secureEnclave) CFDictionaryAddValue(attributes, kSecAttrTokenID, kSecAttrTokenIDSecureEnclave)
        val failure = alloc<CFErrorRefVar>()
        val key = SecKeyCreateRandomKey(attributes, failure.ptr)
        CFRelease(attributes)
        CFRelease(privateAttributes)
        CFRelease(tagData)
        CFRelease(bits)
        CFRelease(access)
        key
    }

    private fun tagData(): CFDataRef = tag.encodeToByteArray().toCFData()

    private fun dictionary(vararg entries: Pair<CFTypeRef?, CFTypeRef?>): CFMutableDictionaryRef = cfDictionary(*entries)

    public companion object {
        public const val DEFAULT_TAG: String = "cx.m42.services.vault"

        private const val PREFIX = "e1:"
        private val ALGORITHM = kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM
        private val lock = Mutex()
    }
}
