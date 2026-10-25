package cx.m42.services.ssh.keyring

import cx.m42.unitool.kit.ssh.SshPublicKey
import cx.m42.services.ssh.SshSig
import cx.m42.services.ssh.crypto.EcdsaDer
import cx.m42.services.ssh.ios.cfDictionary
import cx.m42.services.ssh.ios.toByteArray
import cx.m42.services.ssh.ios.toCFData
import cx.m42.unitool.kit.ssh.PrivateKey
import cx.m42.unitool.kit.ssh.wire
import cx.m42.superizer.runtime.Platform
import cx.m42.superizer.ssh.Chip
import cx.m42.superizer.ssh.GeneratedKind
import cx.m42.superizer.ssh.KeyStorage
import cx.m42.superizer.ssh.KeyringDevice
import cx.m42.superizer.ssh.UsePolicy
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
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateDecryptedData
import platform.Security.SecKeyCreateEncryptedData
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyRef
import platform.Security.errSecSuccess
import platform.Security.kSecAccessControlBiometryCurrentSet
import platform.Security.kSecAccessControlPrivateKeyUsage
import platform.Security.kSecAccessControlUserPresence
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
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecReturnRef
import platform.Security.kSecUseAuthenticationContext
import platform.posix.memcpy
import kotlin.io.encoding.Base64

/**
 * The Secure Enclave as the keyring's chip (05 §4, D165). The enclave takes no imported key: an
 * imported key is level 2 — its file sealed by ECIES to a P-256 of the enclave's own, bound to
 * `UserPresence`, so opening it is Face ID, Touch ID or the passcode, asked by the system with our
 * words — and the one level-1 key is a P-256 made inside it, which only signs (05 §4.3).
 *
 * A key only a biometric opens (09, D246) is bound to `BiometryCurrentSet` instead: the passcode does
 * not open it, the prompt offers no passcode, and a face or finger added or removed ends it. Which
 * error the enclave gives for such a key is not something to bet the message on, so the set it was
 * made under is remembered beside it, hashed ([biometrySet]), and a different one makes it unusable
 * here before anything is tried (D247).
 *
 * Every key is `WhenPasscodeSetThisDeviceOnly`: none without a passcode, none in a backup, and gone
 * when the passcode goes (01 §5.7 on iOS).
 *
 * What the enclave opens is copied out of a `CFData` that is released, not wiped — as with
 * `BigInteger` on Android (01 §4.5), the platform gives no way to.
 *
 * iOS is checked by the self-test report from a phone (D180): green on 2026-09-27, before D246, whose
 * `BiometryCurrentSet` keys have not run on a phone yet.
 */
@OptIn(ExperimentalForeignApi::class)
internal class SecureEnclaveBackend(private val texts: (UseReason) -> PromptText) : ChipBackend {

    private val enclave: Boolean by lazy { probeEnclave() }

    override val device: KeyringDevice
        get() {
            val lock = LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, null)
            val biometry = enclave && LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, null)
            return KeyringDevice(
                platform = Platform.Ios,
                secureEnclave = enclave,
                screenLockSet = lock,
                levels = if (enclave) setOf(1, 2) else setOf(3),
                strongBiometric = biometry,
            )
        }

    override fun placement(key: SshPublicKey, generated: Boolean): KeyStorage? {
        if (!enclave) return null
        return if (generated && key.kind is SshPublicKey.Kind.Ecdsa) KeyStorage.SecureEnclave else KeyStorage.SealedByChip(Chip.SecureEnclave)
    }

    override suspend fun store(alias: String, storage: KeyStorage, key: PrivateKey, file: ByteArray, policy: UsePolicy, reason: UseReason): ChipResult<String?> =
        withContext(Dispatchers.IO) {
            if (storage.level != 2) return@withContext ChipResult.Failed("level-1-import")
            if (!device.screenLockSet) return@withContext ChipResult.NoScreenLock
            val wrap = makeKey("$alias.wrap", reason.key.biometricOnly) ?: return@withContext ChipResult.Failed("enclave")
            try {
                val public = SecKeyCopyPublicKey(wrap) ?: return@withContext ChipResult.Failed("public")
                try {
                    memScoped {
                        val failure = alloc<CFErrorRefVar>()
                        val input = file.toCFData()
                        val sealed = SecKeyCreateEncryptedData(public, ECIES, input, failure.ptr)
                        CFRelease(input)
                        sealed ?: return@memScoped refusal(failure)
                        val bytes = sealed.toByteArray()
                        CFRelease(sealed)
                        if (reason.key.biometricOnly) rememberBiometry(alias)
                        ChipResult.Ok<String?>("e1:" + Base64.encode(bytes))
                    }
                } finally {
                    CFRelease(public)
                }
            } finally {
                CFRelease(wrap)
            }
        }

    override suspend fun generate(alias: String, kind: GeneratedKind, policy: UsePolicy, reason: UseReason): ChipResult<Pair<SshPublicKey, KeyStorage>> =
        withContext(Dispatchers.IO) {
            if (kind != GeneratedKind.EcdsaP256) return@withContext ChipResult.Failed("unsupported")
            if (!device.screenLockSet) return@withContext ChipResult.NoScreenLock
            val key = makeKey("$alias.sign", reason.key.biometricOnly) ?: return@withContext ChipResult.Failed("enclave")
            try {
                val public = SecKeyCopyPublicKey(key) ?: return@withContext ChipResult.Failed("public")
                val point = memScoped {
                    val failure = alloc<CFErrorRefVar>()
                    SecKeyCopyExternalRepresentation(public, failure.ptr)?.let { data -> data.toByteArray().also { CFRelease(data) } }
                }
                CFRelease(public)
                point ?: return@withContext ChipResult.Failed("public")
                val ssh = SshPublicKey.fromBlob(wire { string("ecdsa-sha2-nistp256"); string("nistp256"); string(point) }) ?: return@withContext ChipResult.Failed("public")
                if (reason.key.biometricOnly) rememberBiometry(alias)
                ChipResult.Ok(ssh to KeyStorage.SecureEnclave)
            } finally {
                CFRelease(key)
            }
        }

    override suspend fun unseal(alias: String, sealed: String, reason: UseReason): ChipResult<ByteArray> = withContext(Dispatchers.IO) {
        val body = runCatching { Base64.decode(sealed.removePrefix("e1:")) }.getOrNull() ?: return@withContext ChipResult.Failed("sealed")
        if (biometryChanged(alias)) return@withContext ChipResult.Unusable
        val context = context(reason)
        val key = findKey("$alias.wrap", context) ?: return@withContext ChipResult.Unusable
        try {
            memScoped {
                val failure = alloc<CFErrorRefVar>()
                val input = body.toCFData()
                val opened = SecKeyCreateDecryptedData(key, ECIES, input, failure.ptr)
                CFRelease(input)
                if (opened == null) return@memScoped refusal(failure)
                val bytes = opened.toByteArray()
                CFRelease(opened)
                ChipResult.Ok(bytes)
            }
        } finally {
            CFRelease(key)
        }
    }

    override suspend fun sign(alias: String, key: SshPublicKey, data: ByteArray, rsaHash: Hash, reason: UseReason): ChipResult<ByteArray> =
        withContext(Dispatchers.IO) {
            if (biometryChanged(alias)) return@withContext ChipResult.Unusable
            val private = findKey("$alias.sign", context(reason)) ?: return@withContext ChipResult.Unusable
            try {
                memScoped {
                    val failure = alloc<CFErrorRefVar>()
                    val input = data.toCFData()
                    val signature = SecKeyCreateSignature(private, kSecKeyAlgorithmECDSASignatureMessageX962SHA256, input, failure.ptr)
                    CFRelease(input)
                    val der = signature?.let { it.toByteArray().also { _ -> CFRelease(it) } } ?: return@memScoped refusal(failure)
                    val (r, s) = EcdsaDer.decode(der) ?: return@memScoped ChipResult.Failed("der")
                    ChipResult.Ok(SshSig.ecdsaSignatureBlob(key, r, s))
                }
            } finally {
                CFRelease(private)
            }
        }

    // A P-256 of the enclave signs; it neither agrees for age nor holds RSA (05 §4.3).
    override suspend fun agree(alias: String, peer: ByteArray, reason: UseReason): ChipResult<ByteArray> = ChipResult.Failed("unsupported")

    override suspend fun rsaDecrypt(alias: String, ciphertext: ByteArray, reason: UseReason): ChipResult<ByteArray> = ChipResult.Failed("unsupported")

    override fun exists(alias: String): Boolean =
        !biometryChanged(alias) && listOf(".wrap", ".sign").any { suffix -> findKey(alias + suffix, null)?.also { CFRelease(it) } != null }

    override fun delete(alias: String) {
        deleteKeys(listOf("$alias.wrap", "$alias.sign"))
        NSUserDefaults.standardUserDefaults.removeObjectForKey(BIOMETRY_PREFIX + alias)
    }

    // ------------------------------------------------------------ the biometric set a key was made under

    private sealed interface BiometrySet {
        /** Face ID or Touch ID is set up; [hash] fingerprints who it knows. */
        data class Known(val hash: String) : BiometrySet

        /** Every face and finger removed: a key bound to them is gone for good. */
        data object None : BiometrySet

        /** Not readable now — locked out after failed tries, say — which says nothing about the key. */
        data object Unknown : BiometrySet
    }

    private fun biometrySet(): BiometrySet = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val context = LAContext()
        if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error.ptr)) {
            return@memScoped if (error.value?.code == LAErrorBiometryNotEnrolled) BiometrySet.None else BiometrySet.Unknown
        }
        val state = context.evaluatedPolicyDomainState ?: return@memScoped BiometrySet.Unknown
        val bytes = ByteArray(state.length.toInt())
        if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), state.bytes, state.length) }
        BiometrySet.Known(Hash.SHA256.digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
    }

    private fun rememberBiometry(alias: String) {
        val now = biometrySet() as? BiometrySet.Known ?: return
        NSUserDefaults.standardUserDefaults.setObject(now.hash, forKey = BIOMETRY_PREFIX + alias)
    }

    /** The faces or fingers changed since the key was made under them: the enclave will not open it again. */
    private fun biometryChanged(alias: String): Boolean {
        val then = NSUserDefaults.standardUserDefaults.stringForKey(BIOMETRY_PREFIX + alias) ?: return false
        return when (val now = biometrySet()) {
            is BiometrySet.Known -> now.hash != then
            BiometrySet.None -> true
            BiometrySet.Unknown -> false
        }
    }

    // ------------------------------------------------------------ Security.framework

    private fun context(reason: UseReason): LAContext {
        val text = texts(reason)
        return LAContext().apply {
            localizedReason = listOfNotNull(text.title, text.subtitle).joinToString("\n")
            if (reason.key.biometricOnly) {
                // The passcode would not open the key: no button that offers it (D246).
                localizedFallbackTitle = ""
                localizedCancelTitle = text.cancel
            }
        }
    }

    /** The person said no, or the system did: which, from the `CFError` the call left. */
    private fun refusal(failure: CFErrorRefVar): ChipResult<Nothing> {
        val code = failure.value?.let { CFErrorGetCode(it) } ?: 0L
        failure.value?.let { CFRelease(it) }
        return when (code) {
            // errSecUserCanceled, LAErrorUserCancel, LAErrorUserFallback, LAErrorSystemCancel, LAErrorAppCancel
            -128L, -2L, -3L, -4L, -9L -> ChipResult.Cancelled
            // errSecAuthFailed / LAErrorPasscodeNotSet: the passcode went, and the key with it (01 §5.7)
            -5L -> ChipResult.NoScreenLock
            // LAErrorBiometryNotEnrolled: every face or finger removed — a key bound to them is gone (D246)
            -7L -> ChipResult.Unusable
            // LAErrorBiometryLockout: too many tries; the passcode on the lock screen lifts it
            -8L -> ChipResult.Failed("biometry-lockout")
            else -> ChipResult.Failed("enclave", "CFError $code")
        }
    }

    /** Whether the enclave makes a key here at all — no on part of the simulators (P10). */
    private fun probeEnclave(): Boolean = throwawayKey()?.let { CFRelease(it); true } ?: false

    internal companion object {
        val ECIES = kSecKeyAlgorithmECIESEncryptionCofactorVariableIVX963SHA256AESGCM

        /**
         * A P-256 made in the enclave that needs no passcode and is kept nowhere — so a phone without
         * a passcode still says whether it has an enclave, and the self-test can sign with nobody there.
         */
        fun throwawayKey(): SecKeyRef? = memScoped {
            val access = SecAccessControlCreateWithFlags(null, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, kSecAccessControlPrivateKeyUsage, null)
                ?: return@memScoped null
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
            key
        }

        /**
         * `enclave.sign` (06 §3, P13): a throwaway enclave key signs an SSHSIG, its DER turned into
         * SSH's `mpint r, mpint s`, and the result must verify as `ssh-keygen -Y verify` would.
         * Null when it did; otherwise what went wrong.
         */
        fun signCheck(): String? {
            val private = throwawayKey() ?: return "the Secure Enclave made no key here"
            try {
                val public = SecKeyCopyPublicKey(private) ?: return "no public half"
                val point = memScoped {
                    val failure = alloc<CFErrorRefVar>()
                    SecKeyCopyExternalRepresentation(public, failure.ptr)?.let { data -> data.toByteArray().also { CFRelease(data) } }
                }
                CFRelease(public)
                point ?: return "the public half did not export"
                val key = SshPublicKey.fromBlob(wire { string("ecdsa-sha2-nistp256"); string("nistp256"); string(point) }) ?: return "not a P-256 point"
                val message = "enclave.sign".encodeToByteArray()
                val data = SshSig.signedData(NAMESPACE, Hash.SHA512, message)
                val der = memScoped {
                    val failure = alloc<CFErrorRefVar>()
                    val input = data.toCFData()
                    val signature = SecKeyCreateSignature(private, kSecKeyAlgorithmECDSASignatureMessageX962SHA256, input, failure.ptr)
                    CFRelease(input)
                    val code = failure.value?.let { CFErrorGetCode(it) }
                    failure.value?.let { CFRelease(it) }
                    signature?.let { it.toByteArray().also { _ -> CFRelease(it) } } ?: return "the enclave did not sign: CFError $code"
                }
                val (r, s) = EcdsaDer.decode(der) ?: return "not DER: ${der.size} bytes"
                val armored = SshSig.armor(key, NAMESPACE, Hash.SHA512, SshSig.ecdsaSignatureBlob(key, r, s))
                val check = SshSig.verify(armored, message, NAMESPACE, key)
                return if (check == SshSig.SigCheck.Valid) null else "the signature did not verify: $check"
            } finally {
                CFRelease(private)
            }
        }

        /**
         * A P-256 in the enclave under [tag], usable only with the person present, gone with the
         * passcode. [biometricOnly]: present means Face ID or Touch ID as enrolled now, not the passcode (D246).
         */
        fun makeKey(tag: String, biometricOnly: Boolean = false): SecKeyRef? = memScoped {
            val access = SecAccessControlCreateWithFlags(
                null,
                kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
                kSecAccessControlPrivateKeyUsage or (if (biometricOnly) kSecAccessControlBiometryCurrentSet else kSecAccessControlUserPresence),
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

        fun findKey(tag: String, context: LAContext?): SecKeyRef? = memScoped {
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

        /** Removes every key under [tags]; a tag with none is not an error. */
        fun deleteKeys(tags: List<String>) {
            tags.forEach { name ->
                val tag = name.encodeToByteArray().toCFData()
                val query = cfDictionary(kSecClass to kSecClassKey, kSecAttrApplicationTag to tag)
                SecItemDelete(query)
                CFRelease(query)
                CFRelease(tag)
            }
        }

        private const val NAMESPACE = "self-test@superizer"

        /** Where the hashed biometric set of a key only Face ID or Touch ID opens is kept, by alias. Not a secret. */
        private const val BIOMETRY_PREFIX = "cx.m42.services.ssh.biometry."
    }
}
