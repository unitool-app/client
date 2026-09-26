package cx.m42.services.ssh.crypto

import cx.m42.services.ssh.ios.cfDictionary
import cx.m42.services.ssh.ios.toByteArray
import cx.m42.services.ssh.ios.toCFData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFNumberSInt32Type
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyVerifySignature
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA384
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA512
import platform.Security.kSecRandomDefault

/** The system's CSPRNG, as Security framework offers it. A failure is not an answer: no bytes, no key. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    if (size == 0) return bytes
    val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) }
    check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
    return bytes
}

/**
 * Security.framework's ECDSA over a key given as bytes (`SecKeyCreateWithData`, X9.63: `04‖X‖Y‖D`).
 * Written, not yet run: iOS is checked by the self-test report from a phone (D180).
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object PlatformEcdsa {

    actual fun supports(curveBits: Int): Boolean = curveBits == 256 || curveBits == 384 || curveBits == 521

    actual fun sign(curveBits: Int, d: ByteArray, point: ByteArray, message: ByteArray): Pair<ByteArray, ByteArray>? {
        if (!supports(curveBits)) return null
        val field = (point.size - 1) / 2
        val scalar = ByteArray(maxOf(0, field - d.size)) + d
        val external = point + scalar
        try {
            val der = withKey(curveBits, external, private = true) { key ->
                memScoped {
                    val failure = alloc<CFErrorRefVar>()
                    val data = message.toCFData()
                    val signature = SecKeyCreateSignature(key, algorithm(curveBits), data, failure.ptr)
                    CFRelease(data)
                    signature?.let { it.toByteArray().also { _ -> CFRelease(it) } }
                }
            } ?: return null
            return EcdsaDer.decode(der)
        } finally {
            scalar.fill(0)
            external.fill(0)
        }
    }

    actual fun verify(curveBits: Int, point: ByteArray, message: ByteArray, r: ByteArray, s: ByteArray): Boolean? {
        if (!supports(curveBits)) return null
        return withKey(curveBits, point, private = false) { key ->
            memScoped {
                val failure = alloc<CFErrorRefVar>()
                val data = message.toCFData()
                val signature = EcdsaDer.encode(r, s).toCFData()
                val ok = SecKeyVerifySignature(key, algorithm(curveBits), data, signature, failure.ptr)
                CFRelease(data)
                CFRelease(signature)
                ok
            }
        } ?: false
    }

    private fun <T> withKey(bits: Int, external: ByteArray, private: Boolean, block: (platform.Security.SecKeyRef) -> T): T? = memScoped {
        val size = alloc<IntVar>().apply { value = bits }
        val number = CFNumberCreate(null, kCFNumberSInt32Type, size.ptr)
        val attributes = cfDictionary(
            kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeyClass to if (private) kSecAttrKeyClassPrivate else kSecAttrKeyClassPublic,
            kSecAttrKeySizeInBits to number,
        )
        val data = external.toCFData()
        val failure = alloc<CFErrorRefVar>()
        val key = SecKeyCreateWithData(data, attributes, failure.ptr)
        CFRelease(data)
        CFRelease(attributes)
        CFRelease(number)
        if (key == null) return@memScoped null
        try {
            block(key)
        } finally {
            CFRelease(key)
        }
    }

    private fun algorithm(bits: Int) = when (bits) {
        256 -> kSecKeyAlgorithmECDSASignatureMessageX962SHA256
        384 -> kSecKeyAlgorithmECDSASignatureMessageX962SHA384
        else -> kSecKeyAlgorithmECDSASignatureMessageX962SHA512
    }
}
