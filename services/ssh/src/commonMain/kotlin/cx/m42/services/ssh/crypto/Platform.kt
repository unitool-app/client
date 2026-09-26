package cx.m42.services.ssh.crypto

/**
 * Bytes from the platform's CSPRNG: `SecureRandom` on the JVM and Android, `SecRandomCopyBytes` on
 * iOS, `crypto.getRandomValues` in the browser. Moved from the 2FA app with the age code.
 *
 * Not `kotlin.random.Random`, which is fine for an id and wrong for a key.
 */
internal expect fun secureRandomBytes(size: Int): ByteArray

/**
 * ECDSA over the NIST curves, which `commonMain` does not do by hand (02 §4): the JCA on Android and
 * desktop, Security.framework on iOS, nothing in the browser. Signatures are (r, s) as big-endian
 * magnitudes, the SSH form; the platform's DER is unwrapped by the actual.
 */
internal expect object PlatformEcdsa {
    /** Whether this platform can do [curveBits] at all. */
    fun supports(curveBits: Int): Boolean

    /** SHA-256/384/512 over [message] as the curve prescribes, signed by [d]; null when unsupported. */
    fun sign(curveBits: Int, d: ByteArray, point: ByteArray, message: ByteArray): Pair<ByteArray, ByteArray>?

    /** Null when unsupported; otherwise whether (r, s) is a signature of [message] by [point]. */
    fun verify(curveBits: Int, point: ByteArray, message: ByteArray, r: ByteArray, s: ByteArray): Boolean?
}
