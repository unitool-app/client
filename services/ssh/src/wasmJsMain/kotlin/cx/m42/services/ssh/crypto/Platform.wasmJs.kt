package cx.m42.services.ssh.crypto

/** None in the browser: WebCrypto's ECDSA is asynchronous and this is not. SSHSIG says Unsupported (02 §4). */
internal actual object PlatformEcdsa {
    actual fun supports(curveBits: Int): Boolean = false

    actual fun sign(curveBits: Int, d: ByteArray, point: ByteArray, message: ByteArray): Pair<ByteArray, ByteArray>? = null

    actual fun verify(curveBits: Int, point: ByteArray, message: ByteArray, r: ByteArray, s: ByteArray): Boolean? = null
}
