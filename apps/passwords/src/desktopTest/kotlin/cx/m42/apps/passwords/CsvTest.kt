package cx.m42.apps.passwords

import cx.m42.unitool.kit.password.PasswordMode
import cx.m42.unitool.kit.password.PasswordRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The CSV both ways, and the headers the common exporters write (question 230). */
class CsvTest {

    @Test
    fun quotesCommasNewlinesAndAByteOrderMark() {
        val text = "﻿a,b,c\r\n\"x, y\",\"he said \"\"hi\"\"\",\"two\nlines\"\n\nlast,,"
        assertEquals(
            listOf(listOf("a", "b", "c"), listOf("x, y", "he said \"hi\"", "two\nlines"), listOf("last", "", "")),
            Csv.parse(text),
        )
    }

    @Test
    fun whatIsFormattedParsesBackTheSame() {
        val rows = listOf(listOf("name", "p,w\"d", " padded ", "multi\r\nline", ""))
        assertEquals(rows, Csv.parse(Csv.format(rows)))
    }

    @Test
    fun chromesExportIsReadAndSoIsOurs() {
        val chrome = "name,url,username,password,note\ngithub.com,https://github.com/login,octocat,s3cret,\n"
        val batch = assertNotNull(Import.read(chrome, emptyList()))
        val entry = batch.found.single().entry
        assertEquals(listOf("github.com", "https://github.com/login", "octocat", "s3cret"), listOf(entry.title, entry.url, entry.username, entry.password))

        val ours = listOf(PasswordEntry("1", "Mail", "me", "pw,1", "https://mail.example", "line 1\nline 2"))
        val back = assertNotNull(Import.read(Export.plaintext(ours), emptyList())).found.single().entry
        assertEquals(ours.single().copy(id = "", changed = 0), back)
    }

    @Test
    fun firefoxHasNoTitleSoTheSiteNamesTheEntry() {
        val firefox = "\"url\",\"username\",\"password\",\"httpRealm\",\"formActionOrigin\",\"guid\"\n" +
            "\"https://www.example.org\",\"ann\",\"pw\",,\"https://www.example.org\",\"{1}\"\n"
        val entry = assertNotNull(Import.read(firefox, emptyList())).found.single().entry
        assertEquals("", entry.title)
        assertEquals("example.org", entry.shownTitle)
    }

    @Test
    fun bitwardenRowsThatAreNotLoginsAreLeftOut() {
        val bitwarden = "folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp\n" +
            ",,login,Bank,,,0,https://bank.example,ivan,b4nk,\n" +
            ",,note,Wifi,the password is on the router,,0,,,,\n"
        val batch = assertNotNull(Import.read(bitwarden, emptyList()))
        assertEquals(listOf("Bank"), batch.found.map { it.entry.title })
        assertTrue(batch.failed.isEmpty())
    }

    @Test
    fun keepassxcAndApplePasswordsHeadersAreRead() {
        val keepass = "\"Group\",\"Title\",\"Username\",\"Password\",\"URL\",\"Notes\"\n\"Root\",\"Forum\",\"me\",\"f0rum\",\"https://forum.example\",\"\"\n"
        val apple = "Title,URL,Username,Password,Notes,OTPAuth\nShop,https://shop.example,me@x,sh0p,,\n"
        assertEquals("f0rum", assertNotNull(Import.read(keepass, emptyList())).found.single().entry.password)
        assertEquals("Shop", assertNotNull(Import.read(apple, emptyList())).found.single().entry.title)
    }

    @Test
    fun aSecondQrPartHasNoHeaderAndIsReadInTheFirstPartsColumns() {
        val entries = (1..4).map { PasswordEntry("$it", "Site $it", "user$it", "pw$it") }
        val lines = Export.plaintext(entries).lines()
        val first = lines.take(3).joinToString("\n")
        val second = lines.drop(3).joinToString("\n")
        val one = assertNotNull(Import.read(first, emptyList()))
        val both = assertNotNull(Import.read(second, emptyList(), one))
        assertEquals(entries.map { it.title }, both.found.map { it.entry.title })
        // The same part scanned again adds nothing.
        assertEquals(4, assertNotNull(Import.read(second, emptyList(), both)).found.size)
    }

    @Test
    fun rowsWithoutAPasswordAreReportedAndTheKnownOnesMarked() {
        val have = listOf(PasswordEntry("a", "GitHub", "octocat", "s3cret", "https://github.com"))
        val csv = "name,url,username,password\nGitHub,https://github.com,octocat,s3cret\nEmpty,https://e.example,me,\n"
        val batch = assertNotNull(Import.read(csv, have))
        assertTrue(batch.found.single().known)
        assertEquals(emptyList(), batch.addable)
        assertEquals(emptyList(), batch.differing)
        assertEquals(ImportBatch.Failed(3, DraftError.NoPassword, "Empty"), batch.failed.single())
    }

    @Test
    fun anotherPasswordForAStoredLoginPointsAtIt() {
        val have = listOf(PasswordEntry("a", "GitHub", "octocat", "s3cret", "https://github.com"))
        val csv = "name,url,username,password\nGitHub,https://www.github.com/login,octocat,n3w\nGitHub,https://github.com,other,x\n"
        val batch = assertNotNull(Import.read(csv, have))
        assertEquals(listOf("a", null), batch.found.map { it.replaces })
        assertEquals(listOf(0), batch.differing)
    }

    @Test
    fun textThatIsNoCsvOfLoginsIsNotRead() {
        assertNull(Import.read("hello world", emptyList()))
        assertNull(Import.read("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP", emptyList()))
    }

    @Test
    fun theGeneratorsSettingsReadBackAndFallBackToTheDefault() {
        val rules = PasswordRules(PasswordMode.Mixed, length = 32, words = 8, upper = false, lookAlike = true)
        assertEquals(rules, GeneratorSettings.decode(GeneratorSettings.encode(rules)))
        assertEquals(PasswordRules(), GeneratorSettings.decode(null))
        assertEquals(PasswordRules(), GeneratorSettings.decode("garbage"))
        assertEquals(PasswordRules(), GeneratorSettings.decode("mode=Words;kinds="), "no character set: the default")
        assertEquals(PasswordRules(), GeneratorSettings.decode("length=2"), "out of range: the default")
        assertEquals(PasswordRules(length = 30), GeneratorSettings.decode("length=30;future=1"))
    }

    @Test
    fun theSiteIsTheHostWithoutWww() {
        assertEquals("github.com", Site.host("https://www.GitHub.com/login?x=1"))
        assertEquals("example.org", Site.host("user@example.org:8080/path"))
        assertEquals("", Site.host(""))
    }
}
