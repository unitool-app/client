package cx.m42.services.ssh.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec

/**
 * The JCA's ECDSA. The private scalar becomes a `BigInteger` here, which cannot be wiped — the
 * limit 01 §4.5 names for RSA and ECDSA, and the reason the heap test reports those rather than
 * failing on them (I12).
 */
internal actual object PlatformEcdsa {

    actual fun supports(curveBits: Int): Boolean = curveBits == 256 || curveBits == 384 || curveBits == 521

    actual fun sign(curveBits: Int, d: ByteArray, point: ByteArray, message: ByteArray): Pair<ByteArray, ByteArray>? {
        if (!supports(curveBits)) return null
        val key = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, d), spec(curveBits)))
        val der = Signature.getInstance(algorithm(curveBits)).run {
            initSign(key)
            update(message)
            sign()
        }
        return EcdsaDer.decode(der)
    }

    actual fun verify(curveBits: Int, point: ByteArray, message: ByteArray, r: ByteArray, s: ByteArray): Boolean? {
        if (!supports(curveBits)) return null
        val field = (point.size - 1) / 2
        if (point.isEmpty() || point[0] != 4.toByte() || point.size != 1 + 2 * field) return false
        val x = BigInteger(1, point.copyOfRange(1, 1 + field))
        val y = BigInteger(1, point.copyOfRange(1 + field, point.size))
        return runCatching {
            val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), spec(curveBits)))
            Signature.getInstance(algorithm(curveBits)).run {
                initVerify(key)
                update(message)
                verify(EcdsaDer.encode(r, s))
            }
        }.getOrDefault(false)
    }

    private fun spec(bits: Int): ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec(if (bits == 521) "secp521r1" else "secp${bits}r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }

    private fun algorithm(bits: Int): String = when (bits) {
        256 -> "SHA256withECDSA"
        384 -> "SHA384withECDSA"
        else -> "SHA512withECDSA"
    }
}
