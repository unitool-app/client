package cx.m42.services.ssh.probe

import java.math.BigInteger
import java.security.Principal
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Date

/**
 * The smallest X.509 certificate the Keystore will take next to an imported private key (01 §4.5):
 * version 3, serial 1, `CN=cx.m42.services.ssh` as both issuer and subject, valid 2000–2099, no
 * extensions. DER by hand, because the platform has no certificate *builder* and the Keystore
 * demands a certificate for every `PrivateKeyEntry` whether or not it ever looks at it (P5).
 */
internal object SelfSignedCertificate {

    /** TBSCertificate for [spki], naming [signatureOid] (AlgorithmIdentifier contents) as its algorithm. */
    fun tbs(spki: ByteArray, signatureOid: ByteArray): ByteArray {
        val name = seq(set(seq(tlv(0x06, byteArrayOf(0x55, 0x04, 0x03)), tlv(0x0c, "cx.m42.services.ssh".encodeToByteArray()))))
        return seq(
            tlv(0xa0, tlv(0x02, byteArrayOf(2))),
            tlv(0x02, byteArrayOf(1)),
            seq(signatureOid),
            name,
            seq(tlv(0x17, "000101000000Z".encodeToByteArray()), tlv(0x17, "991231235959Z".encodeToByteArray())),
            name,
            spki,
        )
    }

    fun wrap(tbs: ByteArray, signatureOid: ByteArray, signature: ByteArray): ByteArray =
        seq(tbs, seq(signatureOid), tlv(0x03, byteArrayOf(0) + signature))

    fun der(spki: ByteArray, signatureOid: ByteArray, signature: ByteArray): ByteArray =
        wrap(tbs(spki, signatureOid), signatureOid, signature)

    private fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, concat(parts))

    private fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, concat(parts))

    private fun concat(parts: Array<out ByteArray>): ByteArray = parts.fold(ByteArray(0)) { a, b -> a + b }

    fun tlv(tag: Int, body: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(body.size) + body

    private fun length(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }
}

/**
 * A certificate object for a key type the platform cannot parse (Ed25519, X25519 before Android
 * 14): the DER is ours, the public key reports whatever algorithm name [KeyStore.PrivateKeyEntry]
 * needs to see matched. Nothing here verifies anything, and nothing has to — the Keystore only
 * stores `encoded` (P5 says whether it checks even that).
 */
internal class HandMadeCertificate(
    spki: ByteArray,
    signatureOid: ByteArray,
    private val algorithm: String,
) : X509Certificate() {

    private val publicKey = RawPublicKey(algorithm, spki)
    private val tbsBytes = SelfSignedCertificate.tbs(spki, signatureOid)
    private val der = SelfSignedCertificate.wrap(tbsBytes, signatureOid, ByteArray(64))
    private val principal = Principal { "CN=cx.m42.services.ssh" }

    override fun getEncoded(): ByteArray = der.copyOf()
    override fun getPublicKey(): PublicKey = publicKey
    override fun verify(key: PublicKey?) = Unit
    override fun verify(key: PublicKey?, sigProvider: String?) = Unit
    override fun toString(): String = "HandMadeCertificate($algorithm)"
    override fun checkValidity() = Unit
    override fun checkValidity(date: Date?) = Unit
    override fun getVersion(): Int = 3
    override fun getSerialNumber(): BigInteger = BigInteger.ONE
    override fun getIssuerDN(): Principal = principal
    override fun getSubjectDN(): Principal = principal
    override fun getNotBefore(): Date = Date(946_684_800_000L)
    override fun getNotAfter(): Date = Date(4_102_444_799_000L)
    override fun getTBSCertificate(): ByteArray = tbsBytes.copyOf()
    override fun getSignature(): ByteArray = ByteArray(64)
    override fun getSigAlgName(): String = algorithm
    override fun getSigAlgOID(): String = "1.3.101.112"
    override fun getSigAlgParams(): ByteArray? = null
    override fun getIssuerUniqueID(): BooleanArray? = null
    override fun getSubjectUniqueID(): BooleanArray? = null
    override fun getKeyUsage(): BooleanArray? = null
    override fun getBasicConstraints(): Int = -1
    override fun hasUnsupportedCriticalExtension(): Boolean = false
    override fun getCriticalExtensionOIDs(): MutableSet<String>? = null
    override fun getNonCriticalExtensionOIDs(): MutableSet<String>? = null
    override fun getExtensionValue(oid: String?): ByteArray? = null
}
