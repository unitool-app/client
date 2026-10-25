package cx.m42.apps.passwords

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTextReplacement
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
        assertEquals(Generator.LENGTH, generated.length)
        click("pw:save")
        assertShown("pw:list")
        val saved = decode(runtime.secrets.entries.getValue(KEY_ENTRIES)).single()
        assertEquals(listOf("Mail", "me@mail", generated), listOf(saved.title, saved.username, saved.password))
        assertEquals(runtime.clock.now(), saved.changed)
        assertTrue(runtime.storage.entries.values.none { generated in it }, "never in the plain storage")
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
            click("pw:export")
            assertShown("pw:list")
            presence.answer = true
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
