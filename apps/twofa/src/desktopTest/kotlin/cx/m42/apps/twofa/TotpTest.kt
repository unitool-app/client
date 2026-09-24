package cx.m42.apps.twofa

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The arithmetic, against the two authorities there are: RFC 6238's own table, and the JVM's
 * `MessageDigest`/`Mac`. The hand-written hashes run on wasm too, where neither exists.
 */
class TotpTest {

    private val seed1 = "12345678901234567890".encodeToByteArray()
    private val seed256 = "12345678901234567890123456789012".encodeToByteArray()
    private val seed512 = "1234567890123456789012345678901234567890123456789012345678901234".encodeToByteArray()

    /** RFC 6238 Appendix B, all eighteen rows. */
    @Test
    fun rfc6238Vectors() {
        val rows = listOf(
            Triple(59L, "94287082", "46119246") to "90693936",
            Triple(1111111109L, "07081804", "68084774") to "25091201",
            Triple(1111111111L, "14050471", "67062674") to "99943326",
            Triple(1234567890L, "89005924", "91819424") to "93441116",
            Triple(2000000000L, "69279037", "90698825") to "38618901",
            Triple(20000000000L, "65353130", "77737706") to "47863826",
        )
        for ((row, sha512) in rows) {
            val (time, sha1, sha256) = row
            val counter = time / 30
            assertEquals(sha1, Totp.code(seed1, counter, 8, HashAlgorithm.SHA1), "SHA1 at $time")
            assertEquals(sha256, Totp.code(seed256, counter, 8, HashAlgorithm.SHA256), "SHA256 at $time")
            assertEquals(sha512, Totp.code(seed512, counter, 8, HashAlgorithm.SHA512), "SHA512 at $time")
        }
    }

    @Test
    fun hashesAgreeWithTheJvmAtEveryPaddingBoundary() {
        val random = Random(42)
        // Every length up to three SHA-512 blocks, which crosses each of the padding edges
        // (55/56/64 for the 64-byte block, 111/112/128 for the 128-byte one) more than once.
        for (length in 0..400) {
            val message = random.nextBytes(length)
            for ((ours, jvm) in listOf(
                HashAlgorithm.SHA1 to "SHA-1",
                HashAlgorithm.SHA256 to "SHA-256",
                HashAlgorithm.SHA512 to "SHA-512",
            )) {
                assertContentEquals(
                    MessageDigest.getInstance(jvm).digest(message),
                    ours.digest(message),
                    "$jvm of $length bytes",
                )
            }
        }
    }

    @Test
    fun hmacAgreesWithTheJvmIncludingKeysLongerThanABlock() {
        val random = Random(7)
        for (keyLength in listOf(1, 10, 20, 63, 64, 65, 127, 128, 129, 300)) {
            val key = random.nextBytes(keyLength)
            val message = random.nextBytes(8)
            for ((ours, jvm) in listOf(
                HashAlgorithm.SHA1 to "HmacSHA1",
                HashAlgorithm.SHA256 to "HmacSHA256",
                HashAlgorithm.SHA512 to "HmacSHA512",
            )) {
                val mac = Mac.getInstance(jvm).apply { init(SecretKeySpec(key, jvm)) }
                assertContentEquals(mac.doFinal(message), hmac(ours, key, message), "$jvm, key $keyLength")
            }
        }
    }

    @Test
    fun base32RoundTripsAndForgivesHowPeopleCopyIt() {
        val random = Random(1)
        for (length in 1..40) {
            val bytes = random.nextBytes(length)
            assertContentEquals(bytes, Base32.decode(Base32.encode(bytes)))
        }
        assertContentEquals("Hello!".encodeToByteArray(), Base32.decode("jbsw y3dp-ee======"))
        assertNull(Base32.decode("JBSW1"), "1 is not in the alphabet")
        assertNull(Base32.decode("   "))
    }

    @Test
    fun theCountdownAndTheCodeChangeTogether() {
        val account = TotpAccount("x", "GitHub", "me", Base32.encode(seed1))
        // 59 s: the last second of the second window.
        assertEquals(1, Totp.secondsLeft(account, 59_000))
        assertEquals(30, Totp.secondsLeft(account, 60_000))
        assertTrue(Totp.code(account, 59_999) != Totp.code(account, 60_000))
        assertEquals(Totp.code(account, 30_000), Totp.code(account, 59_999))
        assertEquals("287082", Totp.code(account, 59_000), "the RFC row, cut to six digits")
    }

    @Test
    fun codesAreGroupedTheWayAuthenticatorsShowThem() {
        assertEquals("123 456", Totp.grouped("123456"))
        assertEquals("1234 5678", Totp.grouped("12345678"))
    }
}

class OtpAuthUriTest {

    @Test
    fun aTypicalLinkFillsEveryField() {
        val draft = OtpAuthUri.parse(
            "otpauth://totp/ACME%20Co:john.doe@email.com?secret=HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ" +
                "&issuer=ACME%20Co&algorithm=SHA256&digits=8&period=60",
        ).getOrThrow()
        assertEquals("ACME Co", draft.issuer)
        assertEquals("john.doe@email.com", draft.name)
        assertEquals("HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ", draft.secret)
        assertEquals(HashAlgorithm.SHA256, draft.algorithm)
        assertEquals(8, draft.digits)
        assertEquals(60, draft.period)
    }

    @Test
    fun theDefaultsAreGooglesAndTheLabelPrefixIsTheIssuerWhenNoParameterSaysSo() {
        val draft = OtpAuthUri.parse("otpauth://totp/GitHub:octocat?secret=jbswy3dpehpk3pxp").getOrThrow()
        assertEquals("GitHub", draft.issuer)
        assertEquals("octocat", draft.name)
        assertEquals("JBSWY3DPEHPK3PXP", draft.secret)
        assertEquals(HashAlgorithm.SHA1, draft.algorithm)
        assertEquals(6, draft.digits)
        assertEquals(30, draft.period)
    }

    @Test
    fun cyrillicSurvivesPercentEncoding() {
        val link = "otpauth://totp/%D0%93%D0%BE%D1%81%D1%83%D1%81%D0%BB%D1%83%D0%B3%D0%B8:ivan?secret=JBSWY3DPEHPK3PXP"
        assertEquals("Госуслуги", OtpAuthUri.parse(link).getOrThrow().issuer)
    }

    @Test
    fun whatCannotProduceACodeIsRefusedWithAReason() {
        fun reason(link: String) = (OtpAuthUri.parse(link).exceptionOrNull() as DraftException).error
        assertEquals(DraftError.NotOtpAuth, reason("https://example.com"))
        assertEquals(DraftError.Hotp, reason("otpauth://hotp/x?secret=JBSWY3DP&counter=1"))
        assertEquals(DraftError.Migration, reason("otpauth-migration://offline?data=CjEKCkhlbGxvId6tvu8SGA"))
        assertEquals(DraftError.EmptySecret, reason("otpauth://totp/x"))
        assertEquals(DraftError.BadSecret, reason("otpauth://totp/x?secret=0189"))
        assertEquals(DraftError.BadDigits, reason("otpauth://totp/x?secret=JBSWY3DP&digits=4"))
    }

    @Test
    fun formatThenParseIsTheSameAccount() {
        val account = TotpAccount("id", "Госуслуги & Co", "ivan@example.ru", "JBSWY3DPEHPK3PXP", HashAlgorithm.SHA512, 8, 60)
        val back = OtpAuthUri.parse(OtpAuthUri.format(account)).getOrThrow().validate("id").getOrThrow()
        assertEquals(account, back)
    }
}
