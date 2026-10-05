package cx.m42.apps.twofa

import androidx.compose.ui.test.assertTextEquals
import cx.m42.services.qr.QrScanner
import cx.m42.superizer.testing.runAppTest
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Google Authenticator's "Transfer accounts": its QR codes read straight into the import page, the
 * way the export opened on a computer is.
 */
class TwoFaGoogleMigrationTest {

    private val github = TotpAccount("", "GitHub", "octocat", "JBSWY3DPEHPK3PXP")
    private val gosuslugi = TotpAccount("", "Госуслуги", "ivan", "GEZDGNBVGY3TQOJQ", HashAlgorithm.SHA256, digits = 8)
    private val bank = TotpAccount("", "", "bank 42", "MFRGGZDFMZTWQ2LK", HashAlgorithm.SHA512)

    private fun TwoFaInstance.withoutIds(): List<TotpAccount> = accounts.value.map { it.copy(id = "") }

    // ---------------------------------------------------------------- reading a code

    /** The sample every reader of these codes is checked against, written by Google's app itself. */
    @Test
    fun googlesOwnSampleReadsAsTheAccountItHolds() {
        val part = assertNotNull(
            GoogleMigration.parse(
                "otpauth-migration://offline?data=CjEKCkhlbGxvId6tvu8SGEV4YW1wbGU6YWxpY2VAZ29vZ2xlLmNvbRoHRXhhbXBsZSABKAEwAhABGAEgACjr4JP8Bg%3D%3D",
            ),
        )
        assertEquals(1, part.size)
        assertEquals(0, part.index)
        val entry = part.accounts.single()
        assertEquals("Example", entry.title)
        assertEquals(
            TotpAccount("", "Example", "alice@google.com", "JBSWY3DPEHPK3PXP"),
            entry.draft.getOrThrow().validate("").getOrThrow(),
        )
    }

    @Test
    fun everyAccountComesOutWithItsAlgorithmAndDigits() {
        val part = assertNotNull(GoogleMigration.parse(GoogleExport.link(listOf(github, gosuslugi, bank).map(GoogleExport::otp))))
        assertEquals(listOf(github, gosuslugi, bank), part.accounts.map { it.draft.getOrThrow().validate("").getOrThrow() })
        assertEquals(listOf("GitHub", "Госуслуги", "bank 42"), part.accounts.map { it.title })
    }

    @Test
    fun theLabelPrefixIsTheIssuerOnlyWhenTheIssuerFieldIsEmpty() {
        val otps = listOf(
            GoogleExport.Otp(secret("JBSWY3DPEHPK3PXP"), name = "GitHub:octocat", issuer = "GitHub"),
            GoogleExport.Otp(secret("GEZDGNBVGY3TQOJQ"), name = "Госуслуги:ivan"),
            GoogleExport.Otp(secret("MFRGGZDFMZTWQ2LK"), name = "work:me", issuer = "Google"),
        )
        val drafts = GoogleMigration.parse(GoogleExport.link(otps))!!.accounts.map { it.draft.getOrThrow() }
        assertEquals(listOf("GitHub" to "octocat", "Госуслуги" to "ivan", "Google" to "me"), drafts.map { it.issuer to it.name })
    }

    @Test
    fun theDataReadsHoweverItsBase64WasMangled() {
        val link = GoogleExport.link(listOf(github, gosuslugi).map(GoogleExport::otp))
        val data = URLDecoder.decode(link.substringAfter("data="), Charsets.UTF_8)
        val expected = GoogleMigration.parse(link)!!.accounts.map { it.draft.getOrThrow() }
        for (variant in listOf(
            data, // + and / and = left as they are
            data.trimEnd('='),
            data.replace('+', '-').replace('/', '_'),
            data.replace('+', ' '), // a form decoder's doing
            data.chunked(20).joinToString("\n"),
        )) {
            val read = GoogleMigration.parse("otpauth-migration://offline?data=$variant")
            assertEquals(expected, read?.accounts?.map { it.draft.getOrThrow() }, variant)
        }
    }

    @Test
    fun whatIsNotATransferCodeIsNull() {
        for (link in listOf(
            "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP",
            "otpauth-migration://offline",
            "otpauth-migration://offline?data=",
            "otpauth-migration://offline?data=!!!",
            "otpauth-migration://offline?data=CjEKCkhlbGxvId6tvu8SGA", // cut short
            "otpauth-migration://offline?data=" + Base64.getEncoder().encodeToString(byteArrayOf(0x10, 0x01)), // no accounts
        )) {
            assertNull(GoogleMigration.parse(link), link)
        }
    }

    @Test
    fun anAccountTheseCodesCannotServeFailsUnderItsOwnName() {
        val otps = listOf(
            GoogleExport.otp(github),
            GoogleExport.Otp(secret("GEZDGNBVGY3TQOJQ"), name = "counter", issuer = "Old VPN", type = 1),
            GoogleExport.Otp(secret("MFRGGZDFMZTWQ2LK"), name = "md5", issuer = "Legacy", algorithm = 4),
            GoogleExport.Otp(ByteArray(0), name = "empty", issuer = "Broken"),
        )
        val batch = Import.read(GoogleExport.link(otps), have = emptyList())
        assertEquals(listOf(github), batch.found.map { it.account })
        assertEquals(
            listOf(
                ImportBatch.Failed(1, DraftError.Hotp, "Old VPN"),
                ImportBatch.Failed(1, DraftError.BadAlgorithm, "Legacy"),
                ImportBatch.Failed(1, DraftError.EmptySecret, "Broken"),
            ),
            batch.failed,
        )
        assertEquals("Old VPN: Коды по счётчику (HOTP) пока не поддерживаются", TwoFaStringsRu.importFailed(batch.failed[0]))
        assertEquals("Link 3: A code has 6 to 8 digits", TwoFaStringsEn.importFailed(ImportBatch.Failed(3, DraftError.BadDigits)))
    }

    // ---------------------------------------------------------------- several codes

    @Test
    fun aTransferShownAsSeveralCodesIsCountedAndTheSameCodeTwiceAddsNothing() {
        val first = GoogleExport.link(
            listOf(GoogleExport.otp(github), GoogleExport.Otp(secret("GEZDGNBVGY3TQOJQ"), "counter", type = 1)),
            batch = -12345, index = 0, size = 2,
        )
        val second = GoogleExport.link(listOf(gosuslugi, bank).map(GoogleExport::otp), batch = -12345, index = 1, size = 2)

        val one = Import.read(first, emptyList())
        assertEquals(mapOf(-12345 to ImportBatch.Transfer(2, setOf(0))), one.transfers)
        assertEquals("Google Authenticator: отсканировано 1 из 2 QR-кодов. Отсканируйте остальные — их аккаунты добавятся к этому списку.", TwoFaStringsRu.transferParts(one.transfers.values.single()))

        val again = Import.read(first, emptyList(), one)
        assertEquals(one.found, again.found)
        assertEquals(one.failed, again.failed, "no second failure for the same code")

        val both = Import.read(second, emptyList(), again)
        assertEquals(listOf(github, gosuslugi, bank), both.found.map { it.account })
        assertEquals(ImportBatch.Transfer(2, setOf(0, 1)), both.transfers.getValue(-12345))
        assertEquals("Google Authenticator: all 2 transfer QR codes scanned.", TwoFaStringsEn.transferParts(both.transfers.getValue(-12345)))
        assertTrue("1 из 21 QR-кода." in TwoFaStringsRu.transferParts(ImportBatch.Transfer(21, setOf(0))))
    }

    // ---------------------------------------------------------------- in the app

    @Test
    fun scanningGooglesCodesOneAfterAnotherAddsEveryAccount() {
        val camera = TransferCamera(
            GoogleExport.link(listOf(github, gosuslugi).map(GoogleExport::otp), batch = 9, index = 0, size = 2),
            GoogleExport.link(listOf(GoogleExport.otp(bank)), batch = 9, index = 1, size = 2),
        )
        runAppTest(TwoFaApp(), prepare = { it.provide(QrScanner.Key, camera) }) {
            val twofa = instance as TwoFaInstance
            click("2fa:add")
            click("2fa:scan")
            assertShown("2fa:import-page")
            node("2fa:import-intro").assertTextEquals("Найдено: 2 аккаунта. Отмеченные будут добавлены.")
            node("2fa:import-transfer-0").assertTextEquals(
                "Google Authenticator: отсканировано 1 из 2 QR-кодов. Отсканируйте остальные — их аккаунты добавятся к этому списку.",
            )
            assertEquals(null, runtime.storage.entries[KEY_ACCOUNTS], "a scan is not a save")

            click("2fa:import-scan")
            node("2fa:import-transfer-0").assertTextEquals("Google Authenticator: отсканированы все QR-коды переноса (2).")
            assertEquals(setOf(0, 1, 2), twofa.importSelected.value)
            assertEquals(2, runtime.haptics.ticks)

            click("2fa:import-add")
            assertShown("2fa:list")
            assertEquals(listOf(github, gosuslugi, bank), twofa.withoutIds())
            val sealed = Json.decodeFromString<List<TotpAccount>>(runtime.secrets.entries.getValue(KEY_SECRET_ACCOUNTS))
            assertEquals(listOf(github, gosuslugi, bank), sealed.map { it.copy(id = "") })
        }
    }

    @Test
    fun oneTransferCodePastedGoesToTheImportPageEvenWithOneAccountInIt() = runAppTest(TwoFaApp()) {
        val twofa = instance as TwoFaInstance
        click("2fa:add")
        type("2fa:link", GoogleExport.link(listOf(GoogleExport.otp(github))))
        click("2fa:read-link")
        assertShown("2fa:import-page")
        assertAbsent("2fa:import-transfer-0") // one code of one: nothing to count
        click("2fa:import-add")
        assertEquals(listOf(github), twofa.withoutIds())
    }

    private fun secret(base32: String): ByteArray = Base32.decode(base32)!!
}

/**
 * Google Authenticator's side of it: a transfer code as its export screen shows one, for the tests
 * to scan. Written from the same .proto [GoogleMigration] reads, and checked against Google's own
 * sample there.
 */
private object GoogleExport {

    class Otp(
        val secret: ByteArray,
        val name: String,
        val issuer: String = "",
        val algorithm: Int = 1,
        val digits: Int = 1,
        val type: Int = 2,
    )

    fun otp(account: TotpAccount): Otp = Otp(
        secret = Base32.decode(account.secret)!!,
        name = if (account.issuer.isEmpty()) account.name else "${account.issuer}:${account.name}",
        issuer = account.issuer,
        algorithm = account.algorithm.ordinal + 1,
        digits = if (account.digits == 8) 2 else 1,
    )

    fun link(accounts: List<Otp>, batch: Int = 7, index: Int = 0, size: Int = 1): String {
        val payload = message {
            for (otp in accounts) {
                bytes(1, message {
                    bytes(1, otp.secret)
                    bytes(2, otp.name.encodeToByteArray())
                    if (otp.issuer.isNotEmpty()) bytes(3, otp.issuer.encodeToByteArray())
                    varint(4, otp.algorithm.toLong())
                    varint(5, otp.digits.toLong())
                    varint(6, otp.type.toLong())
                })
            }
            varint(2, 1)
            varint(3, size.toLong())
            varint(4, index.toLong())
            varint(5, batch.toLong())
        }
        return "otpauth-migration://offline?data=" + URLEncoder.encode(Base64.getEncoder().encodeToString(payload), Charsets.UTF_8)
    }

    private class Writer {
        val out = ByteArrayOutputStream()

        fun raw(value: Long) {
            var v = value
            while (v and 0x7fL.inv() != 0L) {
                out.write(((v and 0x7f) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }

        fun varint(field: Int, value: Long) {
            raw((field shl 3).toLong())
            raw(value)
        }

        fun bytes(field: Int, value: ByteArray) {
            raw(((field shl 3) or 2).toLong())
            raw(value.size.toLong())
            out.write(value)
        }
    }

    private fun message(build: Writer.() -> Unit): ByteArray = Writer().apply(build).out.toByteArray()
}

/** The camera, answering each of [texts] in turn. */
private class TransferCamera(vararg texts: String?) : QrScanner {
    private val queue = ArrayDeque(texts.toList())

    override suspend fun scan(): String? = queue.removeFirst()
}
