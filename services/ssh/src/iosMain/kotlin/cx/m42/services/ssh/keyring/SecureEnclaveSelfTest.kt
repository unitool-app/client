package cx.m42.services.ssh.keyring

import cx.m42.services.ssh.crypto.secureRandomBytes
import cx.m42.services.ssh.diagnostics.SkipReason
import cx.m42.services.ssh.ios.toByteArray
import cx.m42.services.ssh.ios.toCFData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFErrorGetCode
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.LocalAuthentication.LAContext
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateDecryptedData
import platform.Security.SecKeyCreateEncryptedData

/**
 * The Secure Enclave's steps of the self-test (06 §3), with throwaway keys only: `enclave.sign`,
 * and the wrapping key's three — made only with a passcode (P12), refused with nobody there (I7,
 * P11: `interactionNotAllowed`), opened once the person confirms. Here rather than in the host,
 * because only this module speaks Security.framework. First run on an iPhone 2026-09-27, green (D180).
 */
@OptIn(ExperimentalForeignApi::class)
public object SecureEnclaveSelfTest {

    public class Check(public val passed: Boolean, public val evidence: String?, public val ran: Boolean = true)

    public fun sign(): Check = SecureEnclaveBackend.signCheck().let { Check(it == null, it) }

    /**
     * `wrap.passcode-class`, `wrap.auth-required`, `wrap.confirmed`, in that order. [confirmReason]
     * is the prompt's text; null skips the step that needs a person.
     */
    public fun wrap(passcodeSet: Boolean, confirmReason: String?): List<Check> {
        val tag = "cx.m42.services.ssh.selftest." + secureRandomBytes(6).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val key = SecureEnclaveBackend.makeKey(tag)
        try {
            if (!passcodeSet) {
                val refused = Check(key == null, if (key == null) "no passcode: refused, as it should be" else "a key bound to the person was made with no passcode")
                return listOf(refused, Check(true, SkipReason.NO_SCREEN_LOCK, ran = false), Check(true, SkipReason.NO_SCREEN_LOCK, ran = false))
            }
            key ?: return listOf(Check(false, "the enclave made no key bound to the person"), Check(true, SkipReason.AFTER_FAILURE, ran = false), Check(true, SkipReason.AFTER_FAILURE, ran = false))
            val plain = "self-test".encodeToByteArray()
            val sealed = seal(key, plain) ?: return listOf(Check(true, null), Check(false, "the public half did not seal"), Check(true, SkipReason.AFTER_FAILURE, ran = false))
            val nobody = open(tag, sealed, LAContext().apply { interactionNotAllowed = true })
            val authRequired = Check(nobody.first == null, nobody.second ?: "opened with nobody there (I7)")
            val confirmed = if (confirmReason == null) {
                Check(true, SkipReason.NEEDS_CONFIRMATION, ran = false)
            } else {
                val opened = open(tag, sealed, LAContext().apply { localizedReason = confirmReason })
                Check(opened.first?.contentEquals(plain) == true, opened.second)
            }
            return listOf(Check(true, null), authRequired, confirmed)
        } finally {
            key?.let { CFRelease(it) }
            SecureEnclaveBackend.deleteKeys(listOf(tag))
        }
    }

    private fun seal(key: platform.Security.SecKeyRef, plain: ByteArray): ByteArray? = memScoped {
        val public = SecKeyCopyPublicKey(key) ?: return@memScoped null
        val failure = alloc<CFErrorRefVar>()
        val input = plain.toCFData()
        val sealed = SecKeyCreateEncryptedData(public, SecureEnclaveBackend.ECIES, input, failure.ptr)
        failure.value?.let { CFRelease(it) }
        CFRelease(input)
        CFRelease(public)
        sealed?.let { it.toByteArray().also { _ -> CFRelease(it) } }
    }

    /** What the enclave opened, or null and the error it gave — the evidence either way. */
    private fun open(tag: String, sealed: ByteArray, context: LAContext): Pair<ByteArray?, String?> = memScoped {
        val key = SecureEnclaveBackend.findKey(tag, context) ?: return@memScoped null to "the key was not found"
        val failure = alloc<CFErrorRefVar>()
        val input = sealed.toCFData()
        val opened = SecKeyCreateDecryptedData(key, SecureEnclaveBackend.ECIES, input, failure.ptr)
        val code = failure.value?.let { CFErrorGetCode(it) }
        failure.value?.let { CFRelease(it) }
        CFRelease(input)
        CFRelease(key)
        val bytes = opened?.let { it.toByteArray().also { _ -> CFRelease(it) } }
        bytes to code?.let { "CFError $it" }
    }
}
