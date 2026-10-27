package cx.m42.apps.qrscanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The table of what a code holds (D626) and the text-or-bytes rule with its hex dump (D627). */
class ContentsTest {

    @Test
    fun eachKindIsReadOut() {
        assertEquals(Content.Link("https://m42.cx/a?b=c"), Contents.of("https://m42.cx/a?b=c"))
        assertEquals(Content.Text, Contents.of("https://m42.cx and more words"), "a sentence that starts with a link is text")
        assertEquals(Content.Wifi("Дом;5G", "WPA", "p:a;ss", hidden = true), Contents.of("WIFI:T:WPA;S:Дом\\;5G;P:p\\:a\\;ss;H:true;;"))
        assertEquals(Content.Wifi("open", null, null, hidden = false), Contents.of("WIFI:S:open;T:nopass;;"))
        assertEquals(Content.Email("a@b.c", "Привет", null, "mailto:a@b.c?subject=%D0%9F%D1%80%D0%B8%D0%B2%D0%B5%D1%82"),
            Contents.of("mailto:a@b.c?subject=%D0%9F%D1%80%D0%B8%D0%B2%D0%B5%D1%82"))
        assertEquals(Content.Email("a@b.c", "Hi", "Text", "mailto:a@b.c?subject=Hi&body=Text"), Contents.of("MATMSG:TO:a@b.c;SUB:Hi;BODY:Text;;"))
        assertEquals(Content.Phone("+79001234567", "tel:+79001234567"), Contents.of("tel:+79001234567"))
        assertEquals(Content.Sms("+7900", "hi there", "sms:+7900?body=hi%20there"), Contents.of("SMSTO:+7900:hi there"))
        assertEquals(Content.Geo("55.75", "37.61", "geo:55.75,37.61?z=12"), Contents.of("geo:55.75,37.61?z=12"))
        assertEquals(Content.Otp("Банк", "me@x", migration = false), Contents.of("otpauth://totp/%D0%91%D0%B0%D0%BD%D0%BA:me@x?secret=JBSWY3DPEHPK3PXP"))
        assertEquals(Content.Otp("Corp", "me", migration = false), Contents.of("otpauth://totp/me?secret=X&issuer=Corp"))
        assertEquals(Content.Otp(null, null, migration = true), Contents.of("otpauth-migration://offline?data=abc"))
        assertEquals(Content.Text, Contents.of("просто текст"))
        assertEquals(Content.Text, Contents.of("WIFI:;;"), "no network name: not Wi-Fi")
        assertEquals(Content.Text, Contents.of("geo:north,south"))
    }

    @Test
    fun aContactAndAnEventKeepTheirFields() {
        val card = "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Иванов;Иван;;;\r\nTEL;TYPE=CELL:+7 900\r\nTEL:+7 901\r\nEMAIL:i@x.ru\r\nORG:ООО;Отдел\r\nEND:VCARD"
        assertEquals(Content.Contact("Иван Иванов", listOf("+7 900", "+7 901"), listOf("i@x.ru"), "ООО Отдел"), Contents.of(card))
        val event = "BEGIN:VEVENT\nSUMMARY:Встреча\nDTSTART:20261027T190000Z\nDTEND:20261028\nLOCATION:Офис\\, 3 этаж\nEND:VEVENT"
        assertEquals(Content.Event("Встреча", "2026-10-27 19:00 UTC", "2026-10-28", "Офис, 3 этаж"), Contents.of(event))
        assertEquals("vcf", Contents.extension(Contents.of(card)))
        assertEquals("ics", Contents.extension(Contents.of(event)))
    }

    @Test
    fun textIsUtf8WithoutControlCharacters() {
        assertEquals("строка\nи\tтаб", Bytes.text("строка\nи\tтаб".encodeToByteArray()))
        assertNull(Bytes.text(byteArrayOf(0x48, 0x00, 0x49)))
        assertNull(Bytes.text(byteArrayOf(0xC3.toByte())), "broken UTF-8")
        assertNull(Bytes.text(byteArrayOf(0x1B, 0x5B)), "an escape is not text")
    }

    @Test
    fun theDumpIsHexdumpC() {
        val dump = Bytes.dump("Hello, world!\n\u0000ÿ".encodeToByteArray())
        assertEquals(
            "00000000  48 65 6c 6c 6f 2c 20 77  6f 72 6c 64 21 0a 00 c3  |Hello, world!...|\n" +
                "00000010  bf                                                |.|\n",
            dump,
        )
        assertEquals("00ff10", Bytes.hex(byteArrayOf(0, -1, 16)))
        assertEquals(4, Bytes.dump(ByteArray(100_000), limit = 64).lines().count { it.isNotEmpty() })
    }

    @Test
    fun missingNumbersAreRuns() {
        assertEquals("1, 3–5, 9", ranges(listOf(1, 3, 4, 5, 9)))
        assertTrue(ranges((1..100 step 2).toList()).endsWith("…"))
    }

    @Test
    fun base64AndArmorDecode() {
        assertEquals("hello", QrScannerInstance.base64("aGVsbG8=")!!.decodeToString())
        assertEquals("hello", QrScannerInstance.base64("-----BEGIN X-----\naGVs\nbG8\n-----END X-----")!!.decodeToString())
        assertNull(QrScannerInstance.base64("просто текст"))
        assertNull(QrScannerInstance.base64("hello world"))
    }
}
