package cx.m42.apps.crnote

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import cx.m42.services.files.FileSaver
import cx.m42.services.vault.BiometricKey
import cx.m42.services.vault.BiometricOpened
import cx.m42.services.vault.PrivateFiles
import cx.m42.superizer.lock.SecretHold
import cx.m42.superizer.runtime.FilePicker
import cx.m42.superizer.runtime.PickedFile
import cx.m42.superizer.testing.AppTestScope
import cx.m42.superizer.testing.runAppTest
import cx.m42.unitool.kit.ssh.AgeKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * cr-note on fakes: the face, the PIN and its directories, `178500`, new faces, closing, export and
 * import — and that none of it is open on disk, in storage or in a snapshot.
 */
@OptIn(ExperimentalTestApi::class)
class CrNoteTest {

    private val files = MemoryFiles()
    private val chip = FakeBiometric()

    private class FakeHold : SecretHold {
        var held = false
        override fun holding(held: Boolean) {
            this.held = held
        }

        override suspend fun <T> outside(block: suspend () -> T): T = block()
    }

    private class FakeSaver : FileSaver {
        var bytes: ByteArray? = null
        var name: String? = null
        override suspend fun save(suggestedName: String, bytes: ByteArray, mimeType: String): Boolean {
            name = suggestedName
            this.bytes = bytes.copyOf()
            return true
        }
    }

    /** A camera that reads [codes] one per scan. */
    private class Camera(vararg codes: String) : cx.m42.services.qr.QrScanner {
        private val queue = ArrayDeque(codes.toList())
        override suspend fun scan(): String? = queue.removeFirstOrNull()
    }

    private class OnePicker(private val file: PickedFile) : FilePicker {
        override suspend fun pick(mimeTypes: List<String>): PickedFile = file
    }

    private val hold = FakeHold()
    private val saver = FakeSaver()

    private fun run(
        picker: FilePicker? = null,
        biometric: BiometricKey? = chip,
        camera: cx.m42.services.qr.QrScanner? = null,
        block: AppTestScope<Unit>.() -> Unit,
    ) =
        runAppTest(CrNoteApp(), prepare = {
            biometric?.let { b -> it.provide(BiometricKey.Key, b) }
            it.provide(PrivateFiles.Key, files)
            it.provide(SecretHold.Key, hold)
            it.provide(FileSaver.Key, saver)
            picker?.let { p -> it.provide(FilePicker.Key, p) }
            camera?.let { c -> it.provide(cx.m42.services.qr.QrScanner.Key, c) }
        }, block = block)

    private fun AppTestScope<*>.app() = instance as CrNoteInstance

    private fun AppTestScope<*>.waitFor(condition: () -> Boolean) {
        ui.waitUntil(timeoutMillis = 30_000) { condition() }
        idle()
    }

    private fun AppTestScope<*>.pin(digits: String) {
        digits.forEach { click("cr-note:digit-$it") }
    }

    private fun AppTestScope<*>.settle() {
        runBlocking { app().settle() }
        idle()
    }

    /** First visit through to an open directory, with [code] as the first PIN. */
    private fun AppTestScope<*>.create(code: String) {
        waitFor { app().page.value == Page.NewPin }
        pin(code)
        pin(code)
        waitFor { app().page.value == Page.Notes }
    }

    private fun AppTestScope<*>.enter(code: String) {
        waitFor { app().page.value == Page.Pin }
        pin(code)
        waitFor { app().page.value == Page.Notes }
    }

    private fun AppTestScope<*>.write(text: String) {
        click("cr-note:new")
        type("cr-note:text", text)
        app().saveNow()
        settle()
        click("cr-note:back")
    }

    private fun AppTestScope<*>.assertNothingKept() {
        assertTrue(runtime.storage.entries.isEmpty(), "nothing in storage: ${runtime.storage.entries.keys}")
        assertTrue(runtime.secrets.entries.isEmpty(), "nothing in secrets")
        assertNull(instance.saveState())
    }

    // ------------------------------------------------------------ coming in

    @Test
    fun withoutABiometricTheCodeOpensNothingAndWritesNothing() {
        chip.available = false
        run {
            waitFor { runtime.navigation.closed == 1 }
            assertEquals(0, chip.prompts, "no sheet (D651)")
            assertEquals(0, chip.created)
            assertTrue(files.snapshot().isEmpty())
        }
        run(biometric = null) {
            waitFor { runtime.navigation.closed == 1 }
            assertTrue(files.snapshot().isEmpty())
        }
    }

    @Test
    fun theFirstVisitIsAFaceThenThePinTwiceAnd178500CannotBeIt() = run {
        waitFor { app().page.value == Page.NewPin }
        assertEquals(1, chip.created)
        assertEquals(1, chip.prompts, "the face first (D651)")
        node("cr-note:pin-title").assertTextEquals(app().strings.choosePin)

        pin("178500")
        node("cr-note:pin-problem").assertTextEquals(app().strings.pinForbidden)
        pin("123456")
        node("cr-note:pin-title").assertTextEquals(app().strings.repeatPin)
        pin("654321")
        node("cr-note:pin-problem").assertTextEquals(app().strings.pinMismatch)
        node("cr-note:pin-title").assertTextEquals(app().strings.choosePin)
        pin("123456")
        pin("123456")
        waitFor { app().page.value == Page.Notes }
        assertShown("cr-note:empty")
        settle()
        assertTrue(NoteVault.KEY_FILE in files.map)
        assertTrue(files.map.size - 1 in 8..12, "decoys from the start (D664)")
        assertNothingKept()
    }

    @Test
    fun eachPinIsItsOwnDirectoryAndNoneSaysWrong() {
        run {
            create("123456")
            write("Пароль от сейфа: 4711")
            assertEquals(1, ui.onAllNodesWithTag("cr-note:note").fetchSemanticsNodes().size)
        }
        run {
            enter("123456")
            assertEquals("Пароль от сейфа: 4711", app().notes.value.single().text)
        }
        val before = files.snapshot().keys
        run {
            enter("999999")
            assertShown("cr-note:empty")
            settle()
        }
        assertEquals(before, files.snapshot().keys, "an unknown PIN leaves nothing behind (D653)")
        for ((name, bytes) in files.snapshot()) {
            assertFalse("4711" in String(bytes, Charsets.ISO_8859_1), name)
            assertFalse("сейф" in bytes.decodeToString(), name)
        }
    }

    @Test
    fun theWipePinLeavesAnEmptyDirectoryAndNothingOfBefore() {
        run {
            create("123456")
            write("до стирания")
        }
        val old = files.snapshot().keys
        run {
            enter("178500")
            assertShown("cr-note:empty")
            settle()
            assertEquals(2, chip.created, "a new chip key (D655)")
            assertTrue(chip.destroyed >= 1)
            assertEquals(1, chip.prompts.minus(1), "no second sheet")
            assertTrue(files.snapshot().keys.intersect(old - NoteVault.KEY_FILE).isEmpty(), "every old file gone")
            write("после")
        }
        run {
            enter("123456")
            assertTrue(app().notes.value.isEmpty(), "the old directory is gone")
        }
        run {
            enter("178500")
            assertTrue(app().notes.value.isEmpty(), "178500 again wipes its own notes too")
        }
    }

    @Test
    fun cancellingTheFaceClosesTheApp() {
        run { create("123456") }
        chip.next = BiometricOpened.Cancelled
        run {
            waitFor { runtime.navigation.closed == 1 }
            assertEquals(Page.Starting, app().page.value)
        }
    }

    @Test
    fun newFacesMeanTheNotesAreGoneAndItStartsAgain() {
        run {
            create("123456")
            write("старое")
        }
        chip.invalidate()
        run {
            waitFor { app().page.value == Page.Changed }
            assertShown("cr-note:changed")
            settle()
            assertTrue(files.snapshot().isEmpty(), "nothing left to open (D656)")
            click("cr-note:start-again")
            create("222222")
            assertTrue(app().notes.value.isEmpty())
        }
    }

    @Test
    fun theBackgroundClosesAndForgets() {
        run {
            create("123456")
            click("cr-note:new")
            type("cr-note:text", "не успело")
            app().onBackground()
            waitFor { runtime.navigation.closed == 1 }
            assertTrue(app().notes.value.isEmpty())
            assertEquals("", app().editorText.value)
            assertEquals(Page.Starting, app().page.value)
            settle()
        }
        // What was typed went to disk on the way out, not lost.
        run {
            enter("123456")
            assertEquals("не успело", app().notes.value.single().text)
        }
    }

    // ------------------------------------------------------------ out and in

    @Test
    fun exportIsEncryptedAndImportAddsWhatIsNew() {
        val password = "длинный пароль 12"
        run {
            create("123456")
            write("первая")
            write("вторая")
            click("cr-note:menu")
            click("cr-note:export")
            type("cr-note:password", "short")
            type("cr-note:password-repeat", "short")
            click("cr-note:encrypt")
            assertShown("cr-note:export-problem")
            app().exportPassword.value = password
            app().exportRepeat.value = password
            idle()
            click("cr-note:encrypt")
            waitFor { app().exportReady.value != null }
            click("cr-note:save")
            waitFor { saver.bytes != null }
        }
        val file = saver.bytes!!
        assertTrue(file.decodeToString().startsWith("-----BEGIN AGE ENCRYPTED FILE-----"))
        assertFalse("первая" in file.decodeToString())
        val opened = assertIs<AgeKeys.Opened.Ok>(AgeKeys.decrypt(cx.m42.unitool.kit.ssh.Age.dearmor(file)!!, passphrase = password.encodeToByteArray()))
        val inside = opened.plaintext.decodeToString()
        assertTrue("\"format\": \"cr-note/1\"" in inside, inside)
        assertTrue("первая" in inside && "вторая" in inside)

        run(picker = OnePicker(PickedFile(saver.name!!, file))) {
            enter("777777")
            click("cr-note:menu")
            click("cr-note:import")
            click("cr-note:import-file")
            assertShown("cr-note:import-incoming")
            app().importPassword.value = password
            idle()
            click("cr-note:import-open")
            waitFor { app().importFound.value != null }
            node("cr-note:import-question").assertExists()
            click("cr-note:import-yes")
            assertEquals(2, app().notes.value.size)
            app().saveNow()
            settle()
        }
        run(picker = OnePicker(PickedFile(saver.name!!, file))) {
            enter("777777")
            assertEquals(setOf("первая", "вторая"), app().notes.value.map { it.text }.toSet())
            click("cr-note:menu")
            click("cr-note:import")
            click("cr-note:import-file")
            app().importPassword.value = password
            idle()
            click("cr-note:import-open")
            waitFor { app().importProblem.value != null }
            assertEquals(ImportProblem.NothingNew, app().importProblem.value, "the same notes are not added twice (D661)")
        }
    }

    @Test
    fun plainTextIsNotImported() = run(picker = OnePicker(PickedFile("notes.txt", "просто текст".encodeToByteArray()))) {
        create("123456")
        click("cr-note:menu")
        click("cr-note:import")
        click("cr-note:import-file")
        assertShown("cr-note:import-problem")
        assertEquals(ImportProblem.Refused, app().importProblem.value)
    }

    @Test
    fun aForeignOpensslFileWithTextIsOneNote() {
        val sealed = cx.m42.unitool.kit.openssl.OpensslEnc.encrypt(
            "чужой текст".encodeToByteArray(),
            cx.m42.unitool.kit.openssl.OpensslEnc.Key.Password("pw".encodeToByteArray()),
        )
        run(picker = OnePicker(PickedFile("x.enc", sealed))) {
            create("123456")
            app().startImport()
            idle()
            click("cr-note:import-file")
            app().importPassword.value = "pw"
            idle()
            click("cr-note:import-open")
            waitFor { app().importFound.value != null }
            click("cr-note:import-yes")
            assertEquals("чужой текст", app().notes.value.single().text)
        }
    }

    @Test
    fun aScannedQrCodeGoesToTheEndOfTheNoteAsItIs() {
        run(camera = Camera("https://example.org/?a=1", "второй код", "")) {
            create("123456")
            click("cr-note:new")
            click("cr-note:scan-qr")
            assertEquals("https://example.org/?a=1", app().editorText.value, "an empty note is the code itself")
            type("cr-note:text", " — ссылка")
            click("cr-note:scan-qr")
            assertEquals("https://example.org/?a=1 — ссылка\nвторой код", app().editorText.value, "on a new line after the text")
            click("cr-note:scan-qr")
            assertEquals("https://example.org/?a=1 — ссылка\nвторой код", app().editorText.value, "nothing read, nothing added")
            app().saveNow()
            settle()
        }
        run {
            enter("123456")
            assertEquals("https://example.org/?a=1 — ссылка\nвторой код", app().notes.value.single().text, "saved like typed text")
            app().openNote(app().notes.value.single().id)
            idle()
            assertAbsent("cr-note:scan-qr")
        }
    }
}
