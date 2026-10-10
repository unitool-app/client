package cx.m42.services.ssh

import cx.m42.services.ssh.crypto.EcdsaDer
import cx.m42.services.ssh.crypto.PlatformEcdsa
import cx.m42.unitool.kit.crypto.Aes
import cx.m42.unitool.kit.crypto.AesCtr
import cx.m42.unitool.kit.crypto.AesGcm
import cx.m42.unitool.kit.crypto.BcryptPbkdf
import cx.m42.unitool.kit.crypto.Ed25519
import cx.m42.unitool.kit.crypto.Hash
import cx.m42.unitool.kit.crypto.Rsa
import cx.m42.unitool.kit.crypto.hmac
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.MGF1ParameterSpec
import java.security.spec.NamedParameterSpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun hex(text: String): ByteArray = text.filter { it.isLetterOrDigit() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

internal fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/**
 * Every primitive `services/ssh` does by hand, held to its standard's vectors *and* to the JDK on
 * random input (03 §4): a vector catches a wrong constant, random input catches the carry that goes
 * wrong one time in a thousand. The rule the 2FA app learned — a typo in the X25519 ladder passed
 * round trips and failed only on RFC vectors — is why both.
 */
class PrimitivesTest {

    private val random = SecureRandom()
    private fun bytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    // ------------------------------------------------------------ hashes

    @Test
    fun hashesAndHmacMatchTheJdk() {
        val names = mapOf(Hash.SHA1 to "SHA-1", Hash.SHA256 to "SHA-256", Hash.SHA384 to "SHA-384", Hash.SHA512 to "SHA-512")
        for (length in listOf(0, 1, 55, 56, 63, 64, 111, 112, 127, 128, 129, 1000)) {
            val message = bytes(length)
            for ((hash, name) in names) {
                assertContentEquals(MessageDigest.getInstance(name).digest(message), hash.digest(message), "$name/$length")
                val key = bytes(length % 150 + 1)
                val mac = Mac.getInstance("Hmac" + name.replace("-", "")).apply { init(SecretKeySpec(key, "HMAC")) }.doFinal(message)
                assertContentEquals(mac, hmac(hash, key, message), "HMAC $name/$length")
            }
        }
        assertEquals("cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed8086072ba1e7cc2358baeca134c825a7", Hash.SHA384.digest("abc".toByteArray()).hex())
    }

    // ------------------------------------------------------------ Ed25519

    @Test
    fun ed25519MatchesRfc8032Section71() {
        data class Vector(val seed: String, val public: String, val message: String, val signature: String)
        listOf(
            Vector(
                "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
                "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
                "",
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            ),
            Vector(
                "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
                "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
                "72",
                "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            ),
            Vector(
                "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
                "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
                "af82",
                "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
            ),
        ).forEach { v ->
            assertEquals(v.public, Ed25519.publicKey(hex(v.seed)).hex())
            assertEquals(v.signature, Ed25519.sign(hex(v.seed), hex(v.message)).hex())
            assertTrue(Ed25519.verify(hex(v.public), hex(v.message), hex(v.signature)))
        }
    }

    private fun jdkEd25519(seed: ByteArray): KeyPair = KeyPairGenerator.getInstance("Ed25519").run {
        initialize(NamedParameterSpec.ED25519, object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                seed.copyInto(bytes)
            }
        })
        generateKeyPair()
    }

    @Test
    fun ed25519MatchesTheJdkOnAThousandRandomSeeds() {
        repeat(1000) { i ->
            val seed = bytes(32)
            val message = bytes(i % 300)
            val pair = jdkEd25519(seed)
            val jdkPublic = pair.public.encoded.copyOfRange(12, 44)
            assertContentEquals(jdkPublic, Ed25519.publicKey(seed), "public $i")
            val jdkSignature = Signature.getInstance("Ed25519").run { initSign(pair.private); update(message); sign() }
            val ours = Ed25519.sign(seed, message)
            assertContentEquals(jdkSignature, ours, "signature $i")
            assertTrue(Ed25519.verify(jdkPublic, message, jdkSignature))
            if (i % 50 == 0) {
                val bad = ours.copyOf().also { it[i % 64] = (it[i % 64].toInt() xor 1).toByte() }
                assertFalse(Ed25519.verify(jdkPublic, message, bad), "a flipped bit is refused")
                assertFalse(Ed25519.verify(jdkPublic, message + 1, ours), "another message is refused")
            }
        }
    }

    @Test
    fun ed25519RefusesAnSThatIsNotReduced() {
        val seed = bytes(32)
        val signature = Ed25519.sign(seed, byteArrayOf(1))
        // S + ℓ: a different encoding of the same scalar, which RFC 8032 says to reject.
        val l = BigInteger("7237005577332262213973186563042994240857116359379907606001950938285454250989")
        val s = BigInteger(1, signature.copyOfRange(32, 64).reversedArray()).add(l)
        val sBytes = s.toByteArray().reversedArray().copyOf(32)
        assertFalse(Ed25519.verify(Ed25519.publicKey(seed), byteArrayOf(1), signature.copyOf(32) + sBytes))
    }

    // ------------------------------------------------------------ AES

    @Test
    fun aesMatchesFips197AndSp80038a() {
        // FIPS 197 C.1–C.3.
        val plain = hex("00112233445566778899aabbccddeeff")
        val out = ByteArray(16)
        Aes(hex("000102030405060708090a0b0c0d0e0f")).encryptBlock(plain, 0, out, 0)
        assertEquals("69c4e0d86a7b0430d8cdb78070b4c55a", out.hex())
        Aes(hex("000102030405060708090a0b0c0d0e0f1011121314151617")).encryptBlock(plain, 0, out, 0)
        assertEquals("dda97ca4864cdfe06eaf70a0ec0d7191", out.hex())
        Aes(hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")).encryptBlock(plain, 0, out, 0)
        assertEquals("8ea2b7ca516745bfeafc49904b496089", out.hex())
        // SP 800-38A F.5.1, first block.
        val ctr = AesCtr.apply(hex("2b7e151628aed2a6abf7158809cf4f3c"), hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"), hex("6bc1bee22e409f96e93d7e117393172a"))
        assertEquals("874d6191b620e3261bef6864990db6ce", ctr.hex())
    }

    @Test
    fun ctrAndGcmMatchTheJdk() {
        for (keySize in listOf(16, 24, 32)) {
            for (length in listOf(0, 1, 15, 16, 17, 100, 1000)) {
                val key = bytes(keySize)
                val iv = if (length == 100) ByteArray(16) { -1 } else bytes(16) // the carry through all 128 bits
                val data = bytes(length)
                val jdk = Cipher.getInstance("AES/CTR/NoPadding").run {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                    doFinal(data)
                }
                assertContentEquals(jdk, AesCtr.apply(key, iv, data), "CTR $keySize/$length")

                val nonce = bytes(12)
                val aad = bytes(length % 20)
                val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                    updateAAD(aad)
                    doFinal(data)
                }
                assertContentEquals(sealed, AesGcm.seal(key, nonce, data, aad), "GCM $keySize/$length")
                assertContentEquals(data, AesGcm.open(key, nonce, sealed, aad))
                if (sealed.isNotEmpty()) {
                    val bad = sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
                    assertNull(AesGcm.open(key, nonce, bad, aad))
                }
            }
        }
    }

    // ------------------------------------------------------------ bcrypt_pbkdf

    @Test
    fun bcryptPbkdfIsDeterministicAndSpreadsItsOutput() {
        val a = BcryptPbkdf.derive("password".toByteArray(), "salt".toByteArray(), 4, 48)
        val b = BcryptPbkdf.derive("password".toByteArray(), "salt".toByteArray(), 4, 48)
        assertContentEquals(a, b)
        // A longer key is not the shorter one extended: bytes are interleaved across blocks.
        val short = BcryptPbkdf.derive("password".toByteArray(), "salt".toByteArray(), 4, 32)
        assertFalse(a.copyOf(32).contentEquals(short))
        assertFalse(BcryptPbkdf.derive("passwore".toByteArray(), "salt".toByteArray(), 4, 48).contentEquals(a))
        // The real check is OpenSshKeyFileInteropTest: files ssh-keygen encrypted open here.
    }

    // ------------------------------------------------------------ RSA

    @Test
    fun rsaSignaturesMatchTheJdkByteForByte() {
        for (bits in listOf(2048, 3072)) {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()
            val public = pair.public as RSAPublicKey
            val private = pair.private as RSAPrivateCrtKey
            val message = bytes(100)
            for ((hash, name) in listOf(Hash.SHA256 to "SHA256withRSA", Hash.SHA512 to "SHA512withRSA")) {
                val jdk = Signature.getInstance(name).run { initSign(private); update(message); sign() }
                val ours = Rsa.sign(public.modulus.toByteArray(), private.privateExponent.toByteArray(), hash, message)
                assertContentEquals(jdk, ours, "$bits $name")
                assertTrue(Rsa.verify(public.modulus.toByteArray(), public.publicExponent.toByteArray(), hash, message, ours))
                assertFalse(Rsa.verify(public.modulus.toByteArray(), public.publicExponent.toByteArray(), hash, message + 0, ours))
            }
        }
    }

    @Test
    fun oaepWithAgesLabelOpensWhatTheJdkSealed() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val public = pair.public as RSAPublicKey
        val private = pair.private as RSAPrivateCrtKey
        val label = "age-encryption.org/v1/ssh-rsa".toByteArray()
        repeat(10) {
            val message = bytes(16)
            val sealed = Cipher.getInstance("RSA/ECB/OAEPPadding").run {
                init(Cipher.ENCRYPT_MODE, public, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified(label)))
                doFinal(message)
            }
            val em = Rsa.private(public.modulus.toByteArray(), private.privateExponent.toByteArray(), sealed)
            assertContentEquals(message, Rsa.oaepDecode(em, label))
            assertNull(Rsa.oaepDecode(em, "another label".toByteArray()))
            assertNull(Rsa.oaepDecode(em.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }, label))
        }
    }

    // ------------------------------------------------------------ ECDSA (the JCA, through the actual)

    @Test
    fun ecdsaThroughThePlatformRoundTripsAndTheDerCodecIsExact() {
        for ((bits, curve) in listOf(256 to "secp256r1", 384 to "secp384r1", 521 to "secp521r1")) {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()
            val public = pair.public as ECPublicKey
            val field = (bits + 7) / 8
            fun fixed(v: BigInteger) = v.toByteArray().let { b -> ByteArray(maxOf(0, field - b.size)) + b.copyOfRange(maxOf(0, b.size - field), b.size) }
            val point = byteArrayOf(4) + fixed(public.w.affineX) + fixed(public.w.affineY)
            val d = (pair.private as ECPrivateKey).s.toByteArray()
            val message = bytes(77)
            val (r, s) = PlatformEcdsa.sign(bits, d, point, message)!!
            assertEquals(true, PlatformEcdsa.verify(bits, point, message, r, s))
            assertEquals(false, PlatformEcdsa.verify(bits, point, message + 1, r, s))
            val jdk = Signature.getInstance(if (bits == 256) "SHA256withECDSA" else if (bits == 384) "SHA384withECDSA" else "SHA512withECDSA")
                .run { initSign(pair.private); update(message); sign() }
            val (jr, js) = EcdsaDer.decode(jdk)!!
            assertContentEquals(jdk, EcdsaDer.encode(jr, js))
            assertEquals(true, PlatformEcdsa.verify(bits, point, message, jr, js))
        }
    }
}
