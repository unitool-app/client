package cx.m42.apps.passwords

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.EdECPrivateKey
import java.security.interfaces.EdECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * An age *reader* on the JVM's own crypto: X25519, ChaCha20-Poly1305, RSA-OAEP and HMAC from the
 * JDK, and nothing from `commonMain` but the format's constants. If the hand-written side and this
 * one agree, it is not because they share a mistake.
 *
 * Follows age's `agessh` identities: an Ed25519 identity decrypts with SHA-512(seed)[0..32] as its
 * X25519 scalar, which is what `age -d -i ~/.ssh/id_ed25519` does with the private key file.
 */
internal object JcaAge {

    sealed class Identity {
        abstract val blob: ByteArray
        val publicLine: String get() = type + " " + Base64.getEncoder().encodeToString(blob) + " test@unitool"
        abstract val type: String
    }

    class Ed25519(val seed: ByteArray, val point: ByteArray) : Identity() {
        override val type = "ssh-ed25519"
        override val blob: ByteArray = sshString(type.toByteArray()) + sshString(point)
    }

    class Rsa(val keys: KeyPair) : Identity() {
        override val type = "ssh-rsa"
        private val public = keys.public as RSAPublicKey
        override val blob: ByteArray =
            sshString(type.toByteArray()) + sshString(public.publicExponent.toByteArray()) + sshString(public.modulus.toByteArray())
    }

    fun ed25519(): Ed25519 {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val point = (pair.public as EdECPublicKey).point
        // RFC 8032 encoding: y little-endian, the parity of x in the top bit.
        val y = point.y.toByteArray().reversedArray().copyOf(32)
        if (point.isXOdd) y[31] = (y[31].toInt() or 0x80).toByte()
        val seed = (pair.private as EdECPrivateKey).bytes.get()
        return Ed25519(seed, y)
    }

    fun rsa(bits: Int): Rsa = Rsa(KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair())

    /** The plaintext, or an exception naming what did not check out. */
    fun decrypt(armored: String, identity: Identity): ByteArray = decrypt(dearmor(armored), identity)

    fun decrypt(file: ByteArray, identity: Identity): ByteArray {
        val footer = indexOf(file, "\n--- ".toByteArray())
        check(footer > 0) { "no header footer" }
        val macEnd = file.indexOfFirst(footer + 5) { it == '\n'.code.toByte() }
        val headerForMac = file.copyOfRange(0, footer + 4) // up to and including "---"
        val mac = b64raw(String(file, footer + 5, macEnd - footer - 5))

        val lines = String(headerForMac).split("\n")
        check(lines.first() == "age-encryption.org/v1") { "bad intro ${lines.first()}" }
        val stanzas = mutableListOf<Pair<List<String>, ByteArray>>()
        var i = 1
        while (i < lines.size && lines[i].startsWith("-> ")) {
            val args = lines[i].removePrefix("-> ").split(" ")
            i++
            val body = StringBuilder()
            while (true) {
                val line = lines[i++]
                check(line.length <= 64) { "body line over 64 columns" }
                body.append(line)
                if (line.length < 64) break
            }
            stanzas += args to b64raw(body.toString())
        }
        check(lines[i] == "---") { "junk after stanzas: ${lines[i]}" }

        val fileKey = stanzas.firstNotNullOfOrNull { (args, body) -> unwrap(args, body, identity) }
            ?: error("no stanza for this identity")
        check(fileKey.size == 16)
        check(MessageDigest.isEqual(hmac(hkdf(fileKey, ByteArray(0), "header", 32), headerForMac), mac)) { "header MAC" }

        val nonce = file.copyOfRange(macEnd + 1, macEnd + 17)
        val payloadKey = hkdf(fileKey, nonce, "payload", 32)
        val payload = file.copyOfRange(macEnd + 17, file.size)
        val out = ByteArrayOutputStream()
        var offset = 0
        var counter = 0L
        while (true) {
            val end = minOf(offset + 65536 + 16, payload.size)
            val last = end == payload.size
            val chunkNonce = ByteArray(12)
            for (k in 0 until 8) chunkNonce[10 - k] = (counter ushr (8 * k)).toByte()
            chunkNonce[11] = if (last) 1 else 0
            out.write(chachaOpen(payloadKey, chunkNonce, payload.copyOfRange(offset, end)))
            if (last) break
            offset = end
            counter++
        }
        return out.toByteArray()
    }

    private fun unwrap(args: List<String>, body: ByteArray, identity: Identity): ByteArray? {
        val tag = Base64.getEncoder().withoutPadding().encodeToString(sha256(identity.blob).copyOf(4))
        if (args[0] != identity.type || args[1] != tag) return null
        return when (identity) {
            is Ed25519 -> {
                val label = "age-encryption.org/v1/ssh-ed25519"
                val scalar = MessageDigest.getInstance("SHA-512").digest(identity.seed).copyOf(32)
                val ours = x25519(scalar, BASE)
                val ephemeral = b64raw(args[2])
                val shared = x25519(scalar, ephemeral)
                val tweak = hkdf(ByteArray(0), identity.blob, label, 32)
                val tweaked = x25519(tweak, shared)
                val wrappingKey = hkdf(tweaked, ephemeral + ours, label, 32)
                chachaOpen(wrappingKey, ByteArray(12), body)
            }
            is Rsa -> Cipher.getInstance("RSA/ECB/OAEPPadding").run {
                val label = "age-encryption.org/v1/ssh-rsa".toByteArray()
                init(
                    Cipher.DECRYPT_MODE,
                    identity.keys.private,
                    OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified(label)),
                )
                doFinal(body)
            }
        }
    }

    // ------------------------------------------------------------ JDK primitives, for other tests too

    val BASE: ByteArray = ByteArray(32).also { it[0] = 9 }

    fun x25519(scalar: ByteArray, u: ByteArray): ByteArray {
        val factory = KeyFactory.getInstance("XDH")
        val private = factory.generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, scalar))
        val bigEndian = u.reversedArray().also { it[0] = (it[0].toInt() and 0x7f).toByte() }
        val public = factory.generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger(1, bigEndian)))
        return KeyAgreement.getInstance("XDH").run {
            init(private)
            doPhase(public, true)
            generateSecret()
        }
    }

    fun chachaOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray =
        Cipher.getInstance("ChaCha20-Poly1305").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            if (aad.isNotEmpty()) updateAAD(aad)
            doFinal(sealed)
        }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray {
        // An empty salt is HashLen zeros (RFC 5869 §2.2); the JDK refuses an empty HMAC key.
        val prk = hmac(salt.takeIf { it.isNotEmpty() } ?: ByteArray(32), ikm)
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmac(prk, block + info.toByteArray() + byteArrayOf(counter++.toByte()))
            out.write(block)
        }
        return out.toByteArray().copyOf(length)
    }

    fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(message)
        }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun dearmor(text: String): ByteArray {
        val lines = text.trim().lines()
        check(lines.first() == "-----BEGIN AGE ENCRYPTED FILE-----") { "no armor header" }
        check(lines.last() == "-----END AGE ENCRYPTED FILE-----") { "no armor footer" }
        val body = lines.subList(1, lines.size - 1)
        check(body.dropLast(1).all { it.length == 64 } && body.last().length in 1..64) { "armor columns" }
        return Base64.getDecoder().decode(body.joinToString(""))
    }

    private fun b64raw(text: String): ByteArray = Base64.getDecoder().decode(text)

    private fun sshString(bytes: ByteArray): ByteArray =
        byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()) + bytes

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int =
        (0..haystack.size - needle.size).firstOrNull { start -> needle.indices.all { haystack[start + it] == needle[it] } } ?: -1

    private inline fun ByteArray.indexOfFirst(from: Int, predicate: (Byte) -> Boolean): Int =
        (from until size).first { predicate(this[it]) }
}
