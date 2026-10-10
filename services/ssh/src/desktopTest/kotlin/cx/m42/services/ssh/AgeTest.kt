package cx.m42.services.ssh

import cx.m42.unitool.kit.crypto.ChaCha20Poly1305
import cx.m42.unitool.kit.crypto.Curve25519
import cx.m42.unitool.kit.crypto.Montgomery
import cx.m42.unitool.kit.crypto.Rsa
import cx.m42.unitool.kit.crypto.hkdfSha256
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The export's cryptography, primitive by primitive and then as a whole file.
 *
 * Each primitive is held to its RFC's published vectors *and* to the JDK's implementation on
 * random input — a vector catches a wrong constant, the random inputs catch the carry that only
 * goes wrong one time in a thousand. The file is then opened by [JcaAge], and by the real `age`
 * when the machine has it.
 */
class AgeTest {

    private val random = SecureRandom()

    private fun bytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    // ------------------------------------------------------------ HKDF

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val okm = hkdfSha256(
            ikm = ByteArray(22) { 0x0b },
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.hex())
    }

    @Test
    fun hkdfWithAnEmptySaltMatchesTheJdk() {
        repeat(20) {
            val ikm = bytes(random.nextInt(40))
            val salt = if (it % 2 == 0) ByteArray(0) else bytes(32)
            assertContentEquals(
                JcaAge.hkdf(ikm, salt, "header", 32),
                hkdfSha256(ikm, salt, "header".toByteArray(), 32),
            )
        }
    }

    // ------------------------------------------------------------ ChaCha20-Poly1305
    // The block function and Poly1305 alone are checked against RFC 8439 in the kit (CryptoVectorsTest).

    @Test
    fun aeadMatchesRfc8439Section282() {
        val plaintext = (
            "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, " +
                "sunscreen would be it."
            ).toByteArray()
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val sealed = ChaCha20Poly1305.seal(key, nonce, plaintext, aad)
        assertEquals("1ae10b594f09e26a7e902ecbd0600691", sealed.copyOfRange(sealed.size - 16, sealed.size).hex())
        assertContentEquals(plaintext, JcaAge.chachaOpen(key, nonce, sealed, aad))
    }

    @Test
    fun aeadOpensInTheJdkAtEveryAwkwardLength() {
        // Around the 16-byte Poly1305 block and the 64-byte ChaCha block, and one full age chunk.
        for (length in listOf(0, 1, 15, 16, 17, 31, 32, 33, 63, 64, 65, 127, 128, 129, 1000, 65536)) {
            val key = bytes(32)
            val nonce = bytes(12)
            val aad = if (length % 2 == 0) ByteArray(0) else bytes(length % 23)
            val plaintext = bytes(length)
            val sealed = ChaCha20Poly1305.seal(key, nonce, plaintext, aad)
            assertEquals(length + 16, sealed.size)
            assertContentEquals(plaintext, JcaAge.chachaOpen(key, nonce, sealed, aad), "length $length")
        }
    }

    // ------------------------------------------------------------ X25519

    @Test
    fun x25519MatchesRfc7748Section61() {
        val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val alicePublic = Curve25519.x25519(alice, Curve25519.BASE)
        val bobPublic = Curve25519.x25519(bob, Curve25519.BASE)
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", alicePublic.hex())
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", bobPublic.hex())
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, Curve25519.x25519(alice, bobPublic).hex())
        assertEquals(shared, Curve25519.x25519(bob, alicePublic).hex())
    }

    @Test
    fun x25519MatchesTheJdkOnRandomInput() {
        repeat(50) {
            val scalar = bytes(32)
            val point = JcaAge.x25519(bytes(32), JcaAge.BASE)
            assertContentEquals(JcaAge.x25519(scalar, point), Curve25519.x25519(scalar, point))
        }
    }

    @Test
    fun anEd25519KeyMapsToTheX25519KeyOfTheSameSecret() {
        // The identity side of the map: age -d turns the private key into SHA-512(seed)[0..32].
        repeat(20) {
            val identity = JcaAge.ed25519()
            val scalar = MessageDigest.getInstance("SHA-512").digest(identity.seed).copyOf(32)
            assertContentEquals(JcaAge.x25519(scalar, JcaAge.BASE), Curve25519.edwardsToMontgomery(identity.point))
        }
    }

    // ------------------------------------------------------------ RSA

    @Test
    fun montgomeryPowerMatchesBigInteger() {
        for (bits in listOf(64, 511, 1024, 2048, 3072, 4096)) {
            repeat(5) {
                val modulus = BigInteger(bits, random).setBit(bits - 1).setBit(0)
                val base = BigInteger(bits - 1, random)
                val exponent = if (it == 0) BigInteger.valueOf(65537) else BigInteger(40, random)
                val size = (bits + 7) / 8
                val expected = base.modPow(exponent, modulus)
                val actual = BigInteger(1, Montgomery(modulus.toByteArray()).modPow(fixed(base, size), exponent.toByteArray()))
                assertEquals(expected, actual, "$bits bits")
            }
        }
    }

    private fun fixed(value: BigInteger, size: Int): ByteArray {
        val raw = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(size - raw.size) + raw
    }

    @Test
    fun oaepOpensInTheJdk() {
        for (bits in listOf(2048, 3072)) {
            val pair = JcaAge.rsa(bits)
            val public = pair.keys.public as RSAPublicKey
            val label = "age-encryption.org/v1/ssh-rsa".toByteArray()
            val message = bytes(16)
            val sealed = Rsa.oaepEncrypt(public.modulus.toByteArray(), public.publicExponent.toByteArray(), message, label, bytes(32))
            assertEquals(bits / 8, sealed.size)
            val opened = Cipher.getInstance("RSA/ECB/OAEPPadding").run {
                init(
                    Cipher.DECRYPT_MODE,
                    pair.keys.private,
                    OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified(label)),
                )
                doFinal(sealed)
            }
            assertContentEquals(message, opened)
        }
    }

    // ------------------------------------------------------------ SSH keys

    @Test
    fun fingerprintsAreTheOnesSshKeygenPrints() {
        // Lines and fingerprints from `ssh-keygen -lf`, so this is the tool's word, not ours.
        val ed = SshPublicKey.parse("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOh1VbDdG6aEqkiUkfr6tLE1wlJZ/jm90yZxcCjDq3xS fixture")!!
        assertIs<SshPublicKey.Kind.Ed25519>(ed.kind)
        assertEquals("SHA256:38iHjJmQ3ZgyVqQbwzzs8/a9uWDpucwF7wkjYr9AnMw", ed.fingerprint)
        assertEquals("ED25519", ed.label)
        assertTrue(Age.supports(ed))

        val rsa = SshPublicKey.parse(Fixtures.RSA_3072)!!
        assertEquals(3072, assertIs<SshPublicKey.Kind.Rsa>(rsa.kind).bits)
        assertEquals("SHA256:aMOCCj4NOZ36Zw/kBy/OKKmCgXajBtSgik6S4BuNh+A", rsa.fingerprint)
        assertTrue(Age.supports(rsa))

        val short = SshPublicKey.parse(Fixtures.RSA_1024)!!
        assertEquals(1024, assertIs<SshPublicKey.Kind.Rsa>(short.kind).bits)
        assertEquals("SHA256:yCAseW4QsGJ9vlG8MT0IB3B6Q3fq1hml2khfTwU/Xqk", short.fingerprint)
        assertTrue(!Age.supports(short), "age refuses RSA under 2048 bits")

        val ecdsa = SshPublicKey.parse(Fixtures.ECDSA)!!
        assertIs<SshPublicKey.Kind.Ecdsa>(ecdsa.kind)
        assertEquals("ECDSA 256", ecdsa.label)
        assertEquals("SHA256:whxBWDesIgwmYs1gc16q/7VE2jQtbXN4eUJ84iLnu5A", ecdsa.fingerprint)
        assertTrue(!Age.supports(ecdsa))
    }

    @Test
    fun aLineThatIsNotAKeyIsNull() {
        assertNull(SshPublicKey.parse(""))
        assertNull(SshPublicKey.parse("ssh-ed25519"))
        assertNull(SshPublicKey.parse("ssh-ed25519 not-base64!"))
        // The type outside says RSA, the blob inside says Ed25519.
        assertNull(SshPublicKey.parse("ssh-rsa AAAAC3NzaC1lZDI1NTE5AAAAIOh1VbDdG6aEqkiUkfr6tLE1wlJZ/jm90yZxcCjDq3xS"))
    }

    // ------------------------------------------------------------ the file

    @Test
    fun aFileForTwoKindsOfKeyOpensWithEither() {
        val ed = JcaAge.ed25519()
        val rsa = JcaAge.rsa(2048)
        val recipients = listOf(ed, rsa).map { SshPublicKey.parse(it.publicLine)!! }
        val plaintext = "otpauth://totp/GitHub:octocat?secret=JBSWY3DPEHPK3PXP&issuer=GitHub\n".toByteArray()
        val armored = Age.armor(Age.encrypt(plaintext, recipients))
        assertContentEquals(plaintext, JcaAge.decrypt(armored, ed))
        assertContentEquals(plaintext, JcaAge.decrypt(armored, rsa))
        assertFailsWith<IllegalStateException> { JcaAge.decrypt(armored, JcaAge.ed25519()) }
    }

    @Test
    fun theStreamChunksAtEveryBoundary() {
        val ed = JcaAge.ed25519()
        val recipient = listOf(SshPublicKey.parse(ed.publicLine)!!)
        for (length in listOf(0, 1, 65535, 65536, 65537, 150_000)) {
            val plaintext = bytes(length)
            assertContentEquals(plaintext, JcaAge.decrypt(Age.encrypt(plaintext, recipient), ed), "length $length")
        }
    }

    @Test
    fun aStanzaBodyThatFillsItsLastLineIsFollowedByAnEmptyOne() {
        // 3072 bits = 384 bytes = 512 Base64 columns: eight full lines, then the empty one the
        // format requires. The reader fails on a missing one, since it cannot tell where the body ends.
        val rsa = JcaAge.Rsa(java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair())
        val file = Age.encrypt("x".toByteArray(), listOf(SshPublicKey.parse(rsa.publicLine)!!))
        assertTrue("\n\n--- " in String(file, Charsets.ISO_8859_1), "empty line after a full body")
        assertContentEquals("x".toByteArray(), JcaAge.decrypt(file, rsa))
    }

    @Test
    fun keysAgeCannotUseAreRefused() {
        assertFailsWith<IllegalArgumentException> { Age.encrypt(ByteArray(1), listOf(SshPublicKey.parse(Fixtures.ECDSA)!!)) }
        assertFailsWith<IllegalArgumentException> { Age.encrypt(ByteArray(1), listOf(SshPublicKey.parse(Fixtures.RSA_1024)!!)) }
        assertFailsWith<IllegalArgumentException> { Age.encrypt(ByteArray(1), emptyList()) }
    }

    /**
     * The tool itself, with keys `ssh-keygen` made — exactly what the export page tells a person to
     * type. Skipped, and says so, on a machine with no `age` (a stock CI runner); where it runs, it
     * is the one test here that shares no assumption with the code under test.
     */
    @Test
    fun theAgeToolOpensTheFileWithAnSshKeygenKey() {
        if (!runs("age", "--version") || !runs("ssh-keygen", "-?")) {
            println("SKIPPED theAgeToolOpensTheFileWithAnSshKeygenKey: no age or ssh-keygen on PATH")
            return
        }
        val dir = Files.createTempDirectory("unitool-age").toFile()
        try {
            val identities = listOf("ed25519" to listOf("-t", "ed25519"), "rsa" to listOf("-t", "rsa", "-b", "3072")).map { (name, args) ->
                val key = File(dir, "id_$name")
                run(listOf("ssh-keygen", "-q", "-N", "", "-C", "", "-f", key.path) + args)
                key
            }
            val recipients = identities.map { SshPublicKey.parse(File(it.path + ".pub").readText())!! }
            val plaintext = "otpauth://totp/%D0%93%D0%BE%D1%81%D1%83%D1%81%D0%BB%D1%83%D0%B3%D0%B8:ivan?secret=JBSWY3DPEHPK3PXP\n" +
                "otpauth://totp/GitHub:octocat?secret=GEZDGNBVGY3TQOJQ&issuer=GitHub&digits=8\n"

            val armored = File(dir, "export.age").apply { writeText(Age.armor(Age.encrypt(plaintext.toByteArray(), recipients))) }
            val binary = File(dir, "export.bin.age").apply { writeBytes(Age.encrypt(plaintext.toByteArray(), recipients)) }
            for (identity in identities) {
                for (file in listOf(armored, binary)) {
                    assertEquals(plaintext, run(listOf("age", "-d", "-i", identity.path, file.path)), "${identity.name} ${file.name}")
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun runs(vararg command: String): Boolean =
        runCatching { ProcessBuilder(*command).redirectErrorStream(true).start().apply { inputStream.readAllBytes(); waitFor(10, TimeUnit.SECONDS) } }
            .isSuccess

    private fun run(command: List<String>): String {
        val process = ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
        val out = process.inputStream.readAllBytes().decodeToString()
        val err = process.errorStream.readAllBytes().decodeToString()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "$command failed: $err" }
        return out
    }
}

/** Public keys made by `ssh-keygen` for these tests; their private halves were thrown away. */
internal object Fixtures {
    const val ED25519 = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOh1VbDdG6aEqkiUkfr6tLE1wlJZ/jm90yZxcCjDq3xS fixture"
    const val ECDSA =
        "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEIsMsXqmDgDTtFzJYG8gCJX0455Aa7IlDHEiYA0PtCHVoTPjGzWFYwQgxsQRhLRYXwgEw9EjUXF79TLbDAzMEo="
    const val RSA_1024 =
        "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAAgQDgKxSUwKWChU2E96mssCKyMEYyS284Oa/y2kjtrftPr4kX7otvUC4hIStxXSZVOu9z9xr79P4aTPPbn6mox6Zcri8c/3p1E2RXFPVncCorbTfW6Fr0b5JpiABHQg/fE000lF0516oycRWw/kKQQXyyoTVI0JMknBkGyN5yC3TrMw=="
    const val RSA_3072 =
        "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQDF9f3LvEHB12a4AXHr0ZCeEBSJaK3MNbkcwnETRnaaOa35+LCIzEEtTxieW2gBAv84/UwqEIt/fuwF53f5xuvmUJsFCZvP4A/dHIOjwa8Fz7jGqaJAed6zD+7zPM/B7AyxsYKVFXQpdxqz8Pr/PoZ6yrC4cKQSqZsHSaisapKo5Z8Kq8Yxqgzli9ebKHMagjl/rhonjul5+pH2kJz1Dwt+JeJJ0C6/6tjz32N0BAwgfis792s+pY0dqBPOv0C2INmQXBtaWdV4OJmeH18LBuXdR4HZ4txjpnb+QrTGRO6xzuYB4Rx/va58dKYlB3+jCb7Ag0fXt/FUSmf3+crxJ0ARRTI7PLvCEYFsxRvsFFuBvGqx8R2i+CT6fOktLJEg1uoX0UGalIemP+8hx2TcRgnBfW9Hi7jAqEuO3OO48dtRMqQ2UD38751+AKqEYM5pvBK90nR7b9+ctv/UdB2ZfuNraZNcnFd9D835Y3O9dRZHzS2zgwFy3yrgxKsosWX8OOE="
}
