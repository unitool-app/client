package cx.m42.apps.passwords

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** eTLD+1 by the Public Suffix List and IDN both ways (passwords-browser D543, D561). */
class SitesTest {

    private fun entry(url: String) = PasswordEntry("x", "", "u", "p", url)

    @Test
    fun theRegistrableDomainIsWhatTheListSays() {
        assertEquals("github.com", Sites.registrable("github.com"))
        assertEquals("github.com", Sites.registrable("gist.github.com"))
        assertEquals("bbc.co.uk", Sites.registrable("www.bbc.co.uk"))
        // PRIVATE section: every github.io site is its own.
        assertEquals("a.github.io", Sites.registrable("a.github.io"))
        assertEquals("a.github.io", Sites.registrable("x.a.github.io"))
        // Wildcard and exception rules: *.ck, !www.ck.
        assertEquals("a.b.ck", Sites.registrable("a.b.ck"))
        assertEquals("www.ck", Sites.registrable("www.ck"))
        // No suffix to speak of: the host itself.
        assertEquals("localhost", Sites.registrable("localhost"))
        assertEquals("192.168.1.10", Sites.registrable("192.168.1.10"))
        assertEquals("[::1]", Sites.registrable("[::1]"))
        assertEquals("co.uk", Sites.registrable("co.uk"))
        assertTrue(PublicSuffixData.VERSION.isNotBlank())
    }

    @Test
    fun entriesMatchBySiteNotByHost() {
        assertTrue(Sites.sameSite(entry("https://github.com/login"), "github.com"))
        assertTrue(Sites.sameSite(entry("https://www.github.com"), "accounts.github.com"))
        assertTrue(Sites.sameSite(entry("github.com"), "github.com"))
        assertFalse(Sites.sameSite(entry("https://github.com"), "github-login.example"))
        assertFalse(Sites.sameSite(entry("https://a.github.io"), "b.github.io"))
        assertFalse(Sites.sameSite(entry(""), "github.com"))
        assertTrue(Sites.sameSite(entry("http://192.168.1.1/admin"), "192.168.1.1"))
        assertFalse(Sites.sameSite(entry("http://192.168.1.1"), "192.168.1.2"))
    }

    @Test
    fun originsAreReadStrictly() {
        assertEquals("github.com", Sites.originHost("https://github.com"))
        assertEquals("localhost", Sites.originHost("http://localhost:8080"))
        assertEquals("[::1]", Sites.originHost("http://[::1]:3000"))
        assertNull(Sites.originHost("chrome://settings"))
        assertNull(Sites.originHost("https://github.com/path"))
        assertNull(Sites.originHost("https://user@github.com"))
        assertNull(Sites.originHost("file:///etc/passwd"))
    }

    @Test
    fun idnIsShownInBothFormsAndMatchesEitherWay() {
        assertEquals("xn--e1afmkfd.xn--p1ai", Punycode.toAscii("пример.рф"))
        assertEquals("пример.рф", Sites.unicode("xn--e1afmkfd.xn--p1ai"))
        assertEquals("bücher.example", Sites.unicode("xn--bcher-kva.example"))
        assertEquals("xn--bcher-kva", "xn--" + Punycode.encode("bücher"))
        // RFC 3492 §7.1 (B): Chinese, simplified.
        assertEquals("ihqwcrb4cv8a8dqg056pqjye", Punycode.encode("他们为什么不说中文"))
        assertEquals("他们为什么不说中文", Punycode.decode("ihqwcrb4cv8a8dqg056pqjye"))
        // An emoji outside the BMP survives the round trip.
        assertEquals("😀x", Punycode.decode(Punycode.encode("😀x")!!))
        // An entry typed in Unicode matches the origin the browser sends in xn--.
        assertTrue(Sites.sameSite(entry("https://пример.рф/вход"), "xn--e1afmkfd.xn--p1ai"))
        // A look-alike in Cyrillic is another site.
        assertFalse(Sites.sameSite(entry("https://apple.com"), Punycode.toAscii("аpple.com")))
    }
}
