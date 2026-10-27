package cx.m42.apps.passwords

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import cx.m42.services.qr.QrScanner
import cx.m42.services.share.FileSharer
import cx.m42.services.ssh.Age
import cx.m42.services.ssh.SshPublicKey
import cx.m42.superizer.app.LockPolicy
import cx.m42.superizer.lock.UserPresence
import cx.m42.superizer.runtime.FilePicker
import cx.m42.superizer.runtime.PickedFile
import cx.m42.superizer.ssh.SshKeyring
import cx.m42.superizer.ssh.SshOutcome
import cx.m42.superizer.testing.AppContractTest
import cx.m42.superizer.testing.FakeFilePicker
import cx.m42.superizer.testing.FakeSshKeyring
import cx.m42.superizer.testing.FakeUserPresence
import cx.m42.superizer.testing.runAppTest
import cx.m42.unitool.kit.password.PasswordGenerator
import cx.m42.unitool.kit.password.PasswordMode
import cx.m42.unitool.kit.password.PasswordRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json

/** The guarantees the host assumes, in one line (D21). */
class PasswordsContractTest : AppContractTest(PasswordsApp()) {

    override val configFixtures: List<String> = listOf("""{"schemaVersion":1}""")

    override val stateFixtures: List<String> = listOf(
        """{"page":"list","query":"git"}""",
        """{"page":"add","title":"GitHub","username":"me","url":""}""",
        """{"page":"details","id":"gone"}""",
        """{"page":"export","github":"octocat"}""",
        """{"page":"import"}""",
        """{"page":"computers"}""",
    )
}

/** The app on fakes, driven by tags: the flows a person goes through. */
class PasswordsInstanceTest {

    private val stored = Json.encodeToString(
        listOf(
            PasswordEntry("a", "GitHub", "octocat", "gh-pass", "https://github.com", "recovery: 1111"),
            PasswordEntry("b", "", "ann", "bank-pass", "https://www.bank.example"),
        ),
    )

    private fun decode(json: String) = Json.decodeFromString<List<PasswordEntry>>(json)

    @Test
    fun itIsBehindTheDeviceLockAndKeptOutOfScreenshots() {
        val manifest = PasswordsApp().manifest
        assertEquals(LockPolicy.Required, manifest.protection.lock)
        assertTrue(manifest.protection.secureWindow)
        assertEquals(3, manifest.minHostContract)
        assertTrue(manifest.deepLinks.isEmpty(), "no link fills in a password (D531)")
    }

    @Test
    fun aFreshInstallShowsTheEmptyStateAddAndImport() = runAppTest(PasswordsApp()) {
        assertShown("pw:empty")
        assertShown("pw:add")
        assertShown("pw:import")
        assertAbsent("pw:export")
    }

    @Test
    fun addingWithAGeneratedPasswordSealsItInTheHostsSecrets() = runAppTest(PasswordsApp()) {
        val pw = instance as PasswordsInstance
        click("pw:add")
        type("pw:title", "Mail")
        type("pw:username", "me@mail")
        click("pw:generate")
        val generated = pw.draft.value.password
        assertEquals(PasswordGenerator.DEFAULT_LENGTH, generated.length)
        click("pw:save")
        assertShown("pw:list")
        val saved = decode(runtime.secrets.entries.getValue(KEY_ENTRIES)).single()
        assertEquals(listOf("Mail", "me@mail", generated), listOf(saved.title, saved.username, saved.password))
        assertEquals(runtime.clock.now(), saved.changed)
        assertTrue(runtime.storage.entries.values.none { generated in it }, "never in the plain storage")
    }

    @Test
    fun theGeneratorsSettingsRemakeThePasswordAndAreKept() = runAppTest(PasswordsApp()) {
        val pw = instance as PasswordsInstance
        click("pw:add")
        assertAbsent("pw:generator")
        click("pw:generate")
        click("pw:generator-settings")
        assertShown("pw:generator")

        click("pw:gen-mode-words")
        val words = pw.draft.value.password.split(' ')
        assertEquals(PasswordGenerator.DEFAULT_WORDS, words.size)
        assertTrue(words.all { it in PasswordGenerator.wordList })
        assertAbsent("pw:gen-lower")
        node("pw:generate-hint").assertTextEquals("6 слов · 66 бит энтропии устройства")

        click("pw:gen-mode-mixed")
        click("pw:gen-longer")
        click("pw:gen-symbols")
        val mixed = pw.draft.value.password
        assertEquals(7, mixed.split(Regex("[^a-z]")).size, mixed)
        assertTrue(mixed.none { it in "!#$%&*+-=?@^_ " }, mixed)

        click("pw:gen-mode-characters")
        click("pw:gen-shorter")
        assertEquals(PasswordGenerator.DEFAULT_LENGTH - 1, pw.draft.value.password.length)

        // A password typed by hand is never replaced by a change of settings.
        node("pw:password").performTextReplacement("mine")
        click("pw:gen-shorter")
        assertEquals("mine", pw.draft.value.password)

        (runtime.scope as TestScope).testScheduler.runCurrent()
        val kept = GeneratorSettings.decode(runtime.storage.entries[KEY_GENERATOR])
        assertEquals(PasswordRules(PasswordMode.Characters, length = 18, words = 7, symbols = false), kept)
    }

    @Test
    fun drawingIsMixedIntoTheNextPassword() = runAppTest(PasswordsApp()) {
        val pw = instance as PasswordsInstance
        click("pw:add")
        click("pw:generate")
        val before = pw.draft.value.password
        click("pw:generator-settings")
        click("pw:gen-draw")
        node("pw:gen-field").performScrollTo().performTouchInput {
            repeat(20) { i -> swipe(Offset(10f + i * 7, 10f + (i * i) % 50), Offset(200f - i * 5, 120f + (i * 13) % 70), durationMillis = 200) }
        }
        assertTrue(pw.drawn.value.samples > 50, "${pw.drawn.value}")
        assertTrue(pw.drawn.value.bits > 0, "${pw.drawn.value}")
        node("pw:gen-draw-done").performScrollTo().performClick()
        assertAbsent("pw:gen-field")
        val after = pw.draft.value.password
        assertEquals(PasswordGenerator.DEFAULT_LENGTH, after.length)
        assertTrue(after != before, "made again with the drawing")
        node("pw:generate-hint").assertTextContains("+ рисование", substring = true)

        node("pw:gen-draw").performScrollTo().performClick()
        node("pw:gen-draw-reset").performScrollTo().performClick()
        assertEquals(Drawn(), pw.drawn.value)
    }

    @Test
    fun theLastCharacterSetCannotBeTurnedOff() = runAppTest(PasswordsApp()) {
        val pw = instance as PasswordsInstance
        pw.changeGenerator { copy(lower = false, upper = false, digits = false) }
        pw.changeGenerator { copy(symbols = false) }
        assertTrue(pw.generator.value.symbols)
        pw.changeGenerator { copy(length = 1000) }
        assertEquals(PasswordGenerator.LENGTHS.last, pw.generator.value.length)
    }

    @Test
    fun theFormSaysWhatIsMissing() = runAppTest(PasswordsApp()) {
        click("pw:add")
        type("pw:title", "Mail")
        click("pw:save")
        node("pw:error").assertTextEquals("Нет пароля")
        node("pw:title").performTextReplacement("")
        type("pw:password", "x")
        click("pw:save")
        node("pw:error").assertTextEquals("Нужно название, сайт или логин")
        assertTrue(runtime.secrets.entries.isEmpty())
    }

    @Test
    fun theListIsAlphabeticalAndTheSearchLooksAtNamesNotPasswords() = runAppTest(
        PasswordsApp(),
        prepare = { it.secrets.entries[KEY_ENTRIES] = stored },
    ) {
        val pw = instance as PasswordsInstance
        assertEquals(listOf("bank.example", "GitHub"), pw.visibleEntries().map { it.shownTitle })
        type("pw:search", "octo")
        assertEquals(listOf("a"), pw.visibleEntries().map { it.id })
        node("pw:search").performTextReplacement("gh-pass")
        assertShown("pw:nothing-found")
        node("pw:search").performTextReplacement("recovery")
        assertShown("pw:nothing-found")
    }

    @Test
    fun thePasswordShowsOnlyOnceConfirmedAndHidesAfterAMinute() {
        val presence = FakeUserPresence(answer = false)
        runAppTest(PasswordsApp(), prepare = {
            it.secrets.entries[KEY_ENTRIES] = stored
            it.provide(UserPresence.Key, presence)
        }) {
            val pw = instance as PasswordsInstance
            val time = (runtime.scope as TestScope).testScheduler
            click("pw:row-1")
            node("pw:password-value").assertTextEquals("••••••••••••")
            click("pw:toggle-password")
            node("pw:password-value").assertTextEquals("••••••••••••")

            presence.answer = true
            click("pw:toggle-password")
            node("pw:password-value").assertTextEquals("gh-pass")
            assertEquals(listOf("Показать пароль", "Показать пароль"), presence.reasons)

            time.advanceTimeBy(SECRET_SECONDS * 1000L + 1)
            time.runCurrent()
            assertNull(pw.shownPassword.value)
            idle()
            node("pw:password-value").assertTextEquals("••••••••••••")
        }
    }

    @Test
    fun copyingAsksNothingAndTheClipboardIsClearedAfterAMinute() = runAppTest(
        PasswordsApp(),
        prepare = { it.secrets.entries[KEY_ENTRIES] = stored },
    ) {
        val pw = instance as PasswordsInstance
        val time = (runtime.scope as TestScope).testScheduler
        val clipboard = FakeClipboard()
        val github = pw.entry("a")!!
        pw.copyTo(clipboard, github, Field.Password)
        assertEquals("gh-pass", clipboard.text)
        assertEquals(Copied("a", Field.Password), pw.copied.value)
        time.advanceTimeBy(CLIPBOARD_SECONDS * 1000L + 1)
        time.runCurrent()
        assertNull(clipboard.text)
        assertNull(pw.copied.value)

        // The login stays where it was put: it is not the secret.
        pw.copyTo(clipboard, github, Field.Username)
        time.advanceTimeBy(CLIPBOARD_SECONDS * 1000L + 1)
        time.runCurrent()
        assertEquals("octocat", clipboard.text)
    }

    @Test
    fun editingAsksFirstThenChangesTheSameEntry() {
        val presence = FakeUserPresence(answer = false)
        runAppTest(PasswordsApp(), prepare = {
            it.secrets.entries[KEY_ENTRIES] = stored
            it.provide(UserPresence.Key, presence)
        }) {
            click("pw:row-1")
            click("pw:edit")
            assertShown("pw:details")
            assertAbsent("pw:form")

            presence.answer = true
            click("pw:edit")
            assertShown("pw:form")
            node("pw:password").assertTextEquals("gh-pass")
            node("pw:password").performTextReplacement("new-pass")
            click("pw:save")
            assertShown("pw:details")
            val saved = decode(runtime.secrets.entries.getValue(KEY_ENTRIES))
            assertEquals(listOf("a", "b"), saved.map { it.id })
            assertEquals("new-pass", saved.first { it.id == "a" }.password)
            assertEquals("recovery: 1111", saved.first { it.id == "a" }.note)
        }
    }

    @Test
    fun deletingAsksTheButtonAndTheDevice() {
        val presence = FakeUserPresence()
        runAppTest(PasswordsApp(), prepare = {
            it.secrets.entries[KEY_ENTRIES] = stored
            it.provide(UserPresence.Key, presence)
        }) {
            click("pw:row-1")
            click("pw:delete")
            click("pw:delete-confirm")
            assertShown("pw:list")
            assertEquals(listOf("b"), decode(runtime.secrets.entries.getValue(KEY_ENTRIES)).map { it.id })
            assertEquals(listOf("Удалить пароль"), presence.reasons)
        }
    }

    @Test
    fun aPastedBrowserCsvIsPreviewedAndTheTickedEntriesAdded() = runAppTest(
        PasswordsApp(),
        prepare = { it.secrets.entries[KEY_ENTRIES] = stored },
    ) {
        val pw = instance as PasswordsInstance
        click("pw:menu")
        click("pw:import")
        type("pw:paste", "name,url,username,password,note\nGitHub,https://github.com,octocat,gh-pass,\nForum,https://forum.example,me,f0rum,\n")
        click("pw:read")
        assertShown("pw:import-page")
        assertEquals(listOf(1), pw.importBatch.value!!.addable)
        assertEquals(setOf(1), pw.importSelected.value)
        assertFalse("f0rum" in pw.saveState().toString(), "not in a snapshot before it is added")
        click("pw:import-add")
        assertShown("pw:list")
        assertEquals(listOf("a", "b", "f0rum"), decode(runtime.secrets.entries.getValue(KEY_ENTRIES)).map { if (it.password == "f0rum") it.password else it.id })
    }

    @Test
    fun theMenuSitsAtTheTopAndBackClosesIt() = runAppTest(
        PasswordsApp(),
        prepare = { it.secrets.entries[KEY_ENTRIES] = stored },
    ) {
        val pw = instance as PasswordsInstance
        assertAbsent("pw:import")
        assertAbsent("pw:export")
        click("pw:menu")
        assertShown("pw:import")
        assertShown("pw:export")
        assertTrue(pw.back())
        assertAbsent("pw:menu-items")
        assertShown("pw:list")
        assertFalse(pw.back())
    }

    @Test
    fun aDifferentPasswordForAStoredLoginIsShownAndReplacesItUnlessBothAreKept() {
        val csv = "name,url,username,password\nGitHub,https://github.com,octocat,new-pass\nForum,https://forum.example,me,f0rum\n"
        runAppTest(PasswordsApp(), prepare = { it.secrets.entries[KEY_ENTRIES] = stored }) {
            val pw = instance as PasswordsInstance
            click("pw:menu")
            click("pw:import")
            type("pw:paste", csv)
            click("pw:read")
            assertEquals(listOf(0), pw.importBatch.value!!.differing)
            node("pw:import-row-0").assertTextContains("Пароль отличается от сохранённого: заменит сохранённый")
            node("pw:import-add").assertTextEquals("Добавить 1, заменить 1")
            click("pw:import-add")
            val saved = decode(runtime.secrets.entries.getValue(KEY_ENTRIES))
            assertEquals(listOf("a", "b"), saved.take(2).map { it.id })
            assertEquals("new-pass", saved.first { it.id == "a" }.password)
            assertEquals(listOf("f0rum"), saved.drop(2).map { it.password })
        }
        runAppTest(PasswordsApp(), prepare = { it.secrets.entries[KEY_ENTRIES] = stored }) {
            click("pw:menu")
            click("pw:import")
            type("pw:paste", csv)
            click("pw:read")
            click("pw:import-keep-both")
            node("pw:import-add").assertTextEquals("Добавить 2 записи")
            click("pw:import-add")
            val saved = decode(runtime.secrets.entries.getValue(KEY_ENTRIES))
            assertEquals(listOf("gh-pass", "bank-pass", "new-pass", "f0rum"), saved.map { it.password })
        }
    }

    @Test
    fun importFiltersByKindAndTicksOrUnticksWhatIsShown() = runAppTest(
        PasswordsApp(),
        prepare = { it.secrets.entries[KEY_ENTRIES] = stored },
    ) {
        val pw = instance as PasswordsInstance
        click("pw:menu")
        click("pw:import")
        type(
            "pw:paste",
            "name,url,username,password\nGitHub,https://github.com,octocat,gh-pass\nGitHub,https://github.com,octocat,new-pass\n" +
                "Forum,https://forum.example,me,f0rum\nShop,https://shop.example,me,sh0p\n",
        )
        click("pw:read")
        assertEquals(setOf(1, 2, 3), pw.importSelected.value)
        click("pw:import-filter-new")
        assertShown("pw:import-row-2")
        assertAbsent("pw:import-row-0")
        assertAbsent("pw:import-row-1")
        node("pw:import-select-all").assertTextEquals("Снять все")
        click("pw:import-select-all")
        assertEquals(setOf(1), pw.importSelected.value, "the hidden row keeps its tick")
        click("pw:import-select-all")
        assertEquals(setOf(1, 2, 3), pw.importSelected.value)
        click("pw:import-filter-known")
        assertShown("pw:import-row-0")
        assertAbsent("pw:import-select-all")
        click("pw:import-filter-differing")
        node("pw:import-row-1").assertTextContains("Пароль отличается от сохранённого: заменит сохранённый")
    }

    @Test
    fun textWithNoLoginsSaysSo() = runAppTest(PasswordsApp()) {
        click("pw:import")
        type("pw:paste", "just some words")
        click("pw:read")
        assertShown("pw:import-from")
        node("pw:error").assertTextEquals("Здесь не нашлось ни одной записи с паролем")
    }

    @Test
    fun aCsvFileIsReadAsItIsAndWipedAfter() {
        val file = PickedFile("passwords.csv", "name,url,username,password\nShop,https://shop.example,me,sh0p\n".encodeToByteArray())
        runAppTest(PasswordsApp(), prepare = { it.provide(FilePicker.Key, FakeFilePicker(file)) }) {
            val pw = instance as PasswordsInstance
            click("pw:import")
            click("pw:import-file")
            assertShown("pw:import-page")
            assertEquals("sh0p", pw.importBatch.value!!.found.single().entry.password)
            assertTrue(file.bytes.all { it == 0.toByte() })
        }
    }

    @Test
    fun ourExportFileOpensWithTheKeyringAndWithoutOneSaysThereIsNoKey() {
        val key = SshPublicKey.parse(JcaAge.ed25519().publicLine)!!
        val entries = listOf(PasswordEntry("x", "Mail", "me", "m41l"))
        val encrypted = Age.armor(Age.encrypt(Export.plaintext(entries).encodeToByteArray(), listOf(key))).encodeToByteArray()

        runAppTest(PasswordsApp(), prepare = { it.provide(FilePicker.Key, FakeFilePicker(PickedFile("x.age", encrypted.copyOf()))) }) {
            click("pw:import")
            click("pw:import-file")
            assertShown("pw:file-problem")
            assertShown("pw:import-from")
        }

        val keyring = FakeSshKeyring().apply { onDecrypt = { SshOutcome.Ok(Export.plaintext(entries).encodeToByteArray()) } }
        runAppTest(PasswordsApp(), prepare = {
            it.provide(FilePicker.Key, FakeFilePicker(PickedFile("x.age", encrypted.copyOf())))
            it.provide(SshKeyring.Key, keyring)
        }) {
            val pw = instance as PasswordsInstance
            click("pw:import")
            click("pw:import-file")
            assertEquals(listOf("decrypt"), keyring.calls)
            assertEquals("m41l", pw.importBatch.value!!.found.single().entry.password)
        }
    }

    @Test
    fun twoQrPartsOfALongExportJoinOneList() {
        val entries = (1..4).map { PasswordEntry("$it", "Site $it", "u$it", "p$it") }
        val lines = Export.plaintext(entries).lines()
        val camera = FakeCamera(lines.take(3).joinToString("\n"))
        runAppTest(PasswordsApp(), prepare = { it.provide(QrScanner.Key, camera) }) {
            val pw = instance as PasswordsInstance
            click("pw:import")
            click("pw:scan")
            assertShown("pw:import-page")
            camera.text = lines.drop(3).joinToString("\n")
            click("pw:import-scan")
            assertEquals(entries.map { it.title }, pw.importBatch.value!!.found.map { it.entry.title })
            assertEquals(setOf(0, 1, 2, 3), pw.importSelected.value)
        }
    }

    @Test
    fun exportOpensOnlyOnceConfirmedAndTheFileOpensWithTheKeyToEveryEntry() {
        val identity = JcaAge.ed25519()
        val sharer = FakeSharer()
        val presence = FakeUserPresence(answer = false)
        runAppTest(PasswordsApp(), prepare = {
            it.secrets.entries[KEY_ENTRIES] = stored
            it.provide(UserPresence.Key, presence)
            it.provide(FileSharer.Key, sharer)
            it.network.respond("https://api.github.com/users/octocat/keys", """[{"id":1,"key":"${identity.publicLine.substringBeforeLast(' ')}"}]""")
        }) {
            val pw = instance as PasswordsInstance
            click("pw:menu")
            click("pw:export")
            assertShown("pw:list")
            assertAbsent("pw:menu-items")
            presence.answer = true
            click("pw:menu")
            click("pw:export")
            assertShown("pw:export-page")
            assertEquals(listOf("Экспорт всех паролей", "Экспорт всех паролей"), presence.reasons)

            type("pw:github", "octocat")
            click("pw:find-keys")
            node("pw:command-0").assertTextEquals("age -d -i ~/.ssh/id_ed25519 unitool-passwords-2023-11-14.age")
            click("pw:share")
            val shared = sharer.shared.single()
            assertEquals("unitool-passwords-2023-11-14.age", shared.name)
            val opened = JcaAge.decrypt(shared.text, identity).decodeToString()
            assertEquals(Export.plaintext(pw.entries.value), opened)
            assertTrue(opened.startsWith("name,url,username,password,note\n"))
            assertTrue("gh-pass" !in shared.text)
        }
    }

    @Test
    fun aSnapshotNeverHoldsAPasswordOrANote() = runAppTest(
        PasswordsApp(),
        prepare = { it.secrets.entries[KEY_ENTRIES] = stored },
    ) {
        val pw = instance as PasswordsInstance
        click("pw:add")
        type("pw:title", "New")
        type("pw:password", "typed-secret")
        type("pw:note", "codes 1234")
        val state = pw.saveState().toString()
        assertTrue("New" in state)
        assertFalse("typed-secret" in state)
        assertFalse("codes 1234" in state)
        click("pw:cancel")
        click("pw:row-1")
        assertFalse("gh-pass" in pw.saveState().toString())
    }

    @Test
    fun aListTheHostCannotOpenIsLeftAloneAndUnlockingReadsItAgain() = runAppTest(PasswordsApp(), prepare = {
        it.secrets.entries[KEY_ENTRIES] = stored
        it.secrets.unavailable = true
    }) {
        assertShown("pw:storage-problem")
        assertShown("pw:unlock")
        assertAbsent("pw:add")
        runtime.secrets.unavailable = false
        click("pw:unlock")
        assertShown("pw:row-0")
        assertEquals(stored, runtime.secrets.entries[KEY_ENTRIES])
    }

    @Test
    fun anUnreadableListIsNeverOverwritten() = runAppTest(PasswordsApp(), prepare = { it.secrets.entries[KEY_ENTRIES] = "not json" }) {
        assertShown("pw:storage-problem")
        assertAbsent("pw:add")
        assertEquals("not json", runtime.secrets.entries[KEY_ENTRIES])
    }

    @Test
    fun whenTheHostCannotSealNothingIsWritten() = runAppTest(PasswordsApp(), prepare = { it.secrets.failsToSeal = true }) {
        click("pw:add")
        type("pw:title", "Mail")
        type("pw:password", "x")
        click("pw:save")
        assertShown("pw:storage-problem")
        assertTrue(runtime.secrets.entries.isEmpty())
        assertTrue(runtime.storage.entries.values.none { "Mail" in it })
    }
}

private class FakeClipboard : SecretClipboard {
    var text: String? = null

    override fun put(text: String) {
        this.text = text
    }

    override fun clearIfStill(text: String): Boolean {
        if (this.text == text) this.text = null
        return true
    }
}

private class FakeSharer : FileSharer {
    data class Shared(val name: String, val text: String, val mimeType: String, val message: String?)

    val shared = mutableListOf<Shared>()

    override suspend fun share(fileName: String, text: String, mimeType: String, message: String?): Boolean {
        shared += Shared(fileName, text, mimeType, message)
        return true
    }
}

private class FakeCamera(var text: String?) : QrScanner {
    override suspend fun scan(): String? = text
}
