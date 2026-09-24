package cx.m42.apps.twofa

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTextReplacement
import cx.m42.services.qr.QrScanner
import cx.m42.services.share.FileSharer
import cx.m42.services.vault.SecretVault
import cx.m42.superizer.app.AppConfig
import cx.m42.superizer.testing.runAppTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Many accounts at once: the export opened on a computer and shown as one QR code — or pasted —
 * read into a list the person looks over, then added with one tap.
 */
class TwoFaImportTest {

    private val github = TotpAccount("", "GitHub", "octocat", "JBSWY3DPEHPK3PXP")
    private val gosuslugi = TotpAccount("", "Госуслуги", "ivan", "GEZDGNBVGY3TQOJQ", HashAlgorithm.SHA256, digits = 8)
    private val bank = TotpAccount("", "", "bank 42", "MFRGGZDFMZTWQ2LK", period = 60)
    private val three = listOf(github, gosuslugi, bank)

    /** What `age -d` prints for an export of [accounts]: one link per line. */
    private fun printed(accounts: List<TotpAccount>): String = Export.plaintext(accounts)

    private fun TwoFaInstance.withoutIds(): List<TotpAccount> = accounts.value.map { it.copy(id = "") }

    // ---------------------------------------------------------------- reading the links

    @Test
    fun everyLinkIsFoundHoweverTheLinesWereJoined() {
        val a = OtpAuthUri.format(github)
        val b = OtpAuthUri.format(bank)
        for (text in listOf("$a\n$b\n", "$a\r\n$b\r\n", "$a $b", "$a$b", "Unitool 2FA\n\n$a\n\n$b\n")) {
            assertEquals(listOf(a, b), Import.links(text), "in ${text.replace("\n", "\\n")}")
        }
        assertEquals(emptyList(), Import.links("no links here"))
        assertEquals(listOf("otpauth-migration://offline?data=x"), Import.links("otpauth-migration://offline?data=x"))
    }

    @Test
    fun theExportReadsBackAsTheSameAccounts() {
        val batch = Import.read(printed(three), have = emptyList())
        assertEquals(three, batch.found.map { it.account })
        assertEquals(emptyList(), batch.failed)
        assertEquals(listOf(0, 1, 2), batch.addable)
    }

    @Test
    fun anAccountWithTheSameCodesIsKnownWhateverItIsCalled() {
        val renamed = github.copy(id = "a", issuer = "Мой GitHub", name = "work")
        val otherPeriod = bank.copy(id = "b", period = 30)
        val batch = Import.read(printed(three), have = listOf(renamed, otherPeriod))
        assertEquals(listOf(true, false, false), batch.found.map { it.known })
        assertEquals(listOf(1, 2), batch.addable)
    }

    @Test
    fun aSecondCodeJoinsTheListAndTheSameCodeTwiceAddsNothing() {
        val first = Import.read(printed(listOf(github, gosuslugi)), have = emptyList())
        val second = Import.read(printed(listOf(bank)) + "otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP\n", emptyList(), first)
        assertEquals(three, second.found.map { it.account })
        assertEquals(listOf(ImportBatch.Failed(position = 4, error = DraftError.Hotp)), second.failed)

        val again = Import.read(printed(listOf(github, gosuslugi)), emptyList(), second)
        assertEquals(three, again.found.map { it.account })
    }

    @Test
    fun linksThatAreNotAccountsAreCountedWithTheReason() {
        val text = OtpAuthUri.format(github) + "\n" +
            "otpauth://totp/Broken?secret=not-base32!\n" +
            "otpauth-migration://offline?data=CjEKCkhlbGxvId6tvu8SGA\n" +
            "otpauth://totp/?secret=JBSWY3DPEHPK3PXP\n"
        val batch = Import.read(text, emptyList())
        assertEquals(listOf(github), batch.found.map { it.account })
        assertEquals(
            listOf(
                ImportBatch.Failed(2, DraftError.BadSecret),
                ImportBatch.Failed(3, DraftError.Migration),
                ImportBatch.Failed(4, DraftError.EmptyName),
            ),
            batch.failed,
        )
    }

    // ---------------------------------------------------------------- the command on the computer

    @Test
    fun theTransferCommandPipesTheDecryptedLinksIntoOneQrCode() {
        val ed = SshKey.parse(JcaAge.ed25519().publicLine)!!
        assertEquals(
            listOf("age -d -i ~/.ssh/id_ed25519 unitool-2fa-2023-11-14.age | qrencode -t ansiutf8"),
            Export.transferCommands(listOf(ed), "unitool-2fa-2023-11-14.age", printed(three)),
        )
    }

    @Test
    fun anExportTooBigForOneCodeIsSplitIntoCodesThatEachFit() {
        val many = (1..60).map { TotpAccount("", "Google", "ivan.petrov$it@gmail.com", "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567") }
        val text = printed(many)
        assertTrue(text.length > Export.QR_BYTES)
        val ed = SshKey.parse(JcaAge.ed25519().publicLine)!!
        val command = Export.transferCommands(listOf(ed), "f.age", text).single()
        val lines = Regex("""split -l (\d+) """).find(command)!!.groupValues[1].toInt()
        assertTrue(command.startsWith("age -d -i ~/.ssh/id_ed25519 f.age | split -l $lines --filter='clear; qrencode -t ansiutf8; read _ </dev/tty'"))
        val parts = text.lines().filter { it.isNotEmpty() }.chunked(lines).map { part -> part.joinToString("") { "$it\n" } }
        assertTrue(parts.size in 2..4, "${parts.size} codes for 60 accounts")
        parts.forEach { assertTrue(it.encodeToByteArray().size <= Export.QR_BYTES, "a part of ${it.length} bytes") }
    }

    // ---------------------------------------------------------------- in the app

    @Test
    fun oneScanOfTheExportAddsEveryAccountWithOneTap() {
        val camera = QueuedCamera(printed(three))
        runAppTest(TwoFaApp(), prepare = { it.provide(QrScanner.Key, camera) }) {
            val twofa = instance as TwoFaInstance
            click("2fa:add")
            click("2fa:scan")
            assertShown("2fa:import-page")
            node("2fa:import-intro").assertTextEquals("Найдено: 3 аккаунта. Отмеченные будут добавлены.")
            assertShown("2fa:import-row-2")
            assertEquals(setOf(0, 1, 2), twofa.importSelected.value)
            assertEquals("Импорт аккаунтов", twofa.chrome.value.title)
            assertEquals(null, runtime.storage.entries[KEY_ACCOUNTS], "a scan is not a save")
            assertEquals(1, runtime.haptics.ticks)
            assertEquals("", twofa.link.value, "the links are in the batch, not left behind in the field")

            node("2fa:import-add").assertTextEquals("Добавить 3 аккаунта")
            click("2fa:import-add")
            assertShown("2fa:list")
            assertEquals(three, twofa.withoutIds())
            assertEquals(3, twofa.accounts.value.map { it.id }.toSet().size, "every account has an id of its own")
            assertEquals(three, Json.decodeFromString<List<TotpAccount>>(runtime.storage.entries[KEY_ACCOUNTS]!!).map { it.copy(id = "") })
            assertEquals("2fa_import" to mapOf("accounts" to "3"), runtime.analytics.events.last())
        }
    }

    @Test
    fun theWholeFilePastedIsTheSameAsScanned() = runAppTest(TwoFaApp()) {
        val twofa = instance as TwoFaInstance
        click("2fa:add")
        type("2fa:link", "Расшифровано:\n" + printed(three))
        click("2fa:read-link")
        assertShown("2fa:import-page")
        click("2fa:import-add")
        assertEquals(three, twofa.withoutIds())
    }

    @Test
    fun onlyTheTickedAccountsAreAddedAndOnesAlreadyHereAreNotOffered() = runAppTest(
        TwoFaApp(),
        prepare = { it.storage.entries[KEY_ACCOUNTS] = Json.encodeToString(listOf(github.copy(id = "a"))) },
    ) {
        val twofa = instance as TwoFaInstance
        click("2fa:add")
        type("2fa:link", printed(three))
        click("2fa:read-link")
        node("2fa:import-intro").assertTextEquals("Найдено: 3 аккаунта, из них новых — 2. Отмеченные будут добавлены.")
        assertEquals(setOf(1, 2), twofa.importSelected.value)

        click("2fa:import-row-0") // already here: not a switch
        assertEquals(setOf(1, 2), twofa.importSelected.value)
        click("2fa:import-row-1")
        click("2fa:import-row-2")
        node("2fa:import-add").assertTextEquals("Добавить 0 аккаунтов")
        click("2fa:import-add")
        assertShown("2fa:import-page")
        click("2fa:import-row-1")
        node("2fa:import-add").assertTextEquals("Добавить 1 аккаунт")
        click("2fa:import-add")
        assertEquals(listOf(github, gosuslugi), twofa.withoutIds())
    }

    @Test
    fun whenEveryAccountIsAlreadyHereThereIsNothingToAdd() = runAppTest(
        TwoFaApp(),
        prepare = { it.storage.entries[KEY_ACCOUNTS] = Json.encodeToString(three.mapIndexed { i, a -> a.copy(id = "$i") }) },
    ) {
        val twofa = instance as TwoFaInstance
        click("2fa:add")
        type("2fa:link", printed(three))
        click("2fa:read-link")
        node("2fa:import-intro").assertTextEquals("Найдено: 3 аккаунта — все уже есть на этом устройстве.")
        assertAbsent("2fa:import-add")
        assertFalse(twofa.importAccounts())
        assertEquals(3, twofa.accounts.value.size)
    }

    @Test
    fun anExportShownAsSeveralCodesIsScannedOntoOneList() {
        val camera = QueuedCamera(
            printed(listOf(github, gosuslugi)),
            "https://example.com/not-a-link",
            printed(listOf(bank)) + "otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP\n",
        )
        runAppTest(TwoFaApp(), prepare = { it.provide(QrScanner.Key, camera) }) {
            val twofa = instance as TwoFaInstance
            click("2fa:add")
            click("2fa:scan")
            assertShown("2fa:import-scan")

            // A code that holds no link says so, and the list stays as it was.
            click("2fa:import-scan")
            assertShown("2fa:error")
            assertEquals(2, twofa.importBatch.value!!.found.size)

            click("2fa:import-scan")
            assertAbsent("2fa:error")
            assertEquals(three, twofa.importBatch.value!!.found.map { it.account })
            node("2fa:import-failed-0").assertTextEquals("Ссылка 4: Коды по счётчику (HOTP) пока не поддерживаются")
            assertEquals(setOf(0, 1, 2), twofa.importSelected.value)
            assertEquals(2, runtime.haptics.ticks, "one for each code that added something")

            click("2fa:import-add")
            assertEquals(three, twofa.withoutIds())
        }
    }

    @Test
    fun cancellingForgetsTheLinksAndTheSnapshotNeverHadThem() {
        val camera = QueuedCamera(printed(three))
        runAppTest(TwoFaApp(), prepare = { it.provide(QrScanner.Key, camera) }) {
            val twofa = instance as TwoFaInstance
            click("2fa:add")
            click("2fa:scan")
            val snapshot = twofa.saveState()
            for (account in three) assertFalse(account.secret in snapshot.toString())
            assertEquals("add", (snapshot["page"] as JsonPrimitive).content, "restored to the form, to scan again")

            click("2fa:import-cancel")
            assertShown("2fa:list")
            assertNull(twofa.importBatch.value)
            assertEquals(emptyList(), twofa.accounts.value)
            assertEquals(null, runtime.storage.entries[KEY_ACCOUNTS])
        }
    }

    @Test
    fun aLinkWithSeveralAccountsInItsConfigStillWaitsForTheTap() = runAppTest(
        TwoFaApp(),
        config = AppConfig(buildJsonObject { put("otpauth", JsonPrimitive(printed(three))) }),
    ) {
        val twofa = instance as TwoFaInstance
        assertShown("2fa:import-page")
        assertEquals(emptyList(), twofa.accounts.value)
        click("2fa:import-add")
        assertEquals(three, twofa.withoutIds())
    }

    @Test
    fun importedAccountsAreSealedLikeAnyOther() {
        val vault = SoftwareVault()
        runAppTest(
            TwoFaApp(),
            prepare = { it.provide(SecretVault.Key, vault) },
        ) {
            click("2fa:add")
            type("2fa:link", printed(three))
            click("2fa:read-link")
            click("2fa:import-add")
            val all = runtime.storage.entries.entries.joinToString("\n") { "${it.key}=${it.value}" }
            for (account in three) assertFalse(account.secret in all, "${account.secret} stored readable:\n$all")
            assertNull(runtime.storage.entries[KEY_ACCOUNTS])
            val opened = Json.decodeFromString<List<TotpAccount>>(vault.openBlocking(runtime.storage.entries[KEY_SEALED_ACCOUNTS]!!)!!)
            assertEquals(three, opened.map { it.copy(id = "") })
        }
    }

    @Test
    fun nothingIsImportedOverAListThatCannotBeRead() {
        val sealedElsewhere = SoftwareVault().sealBlocking("[]")
        runAppTest(
            TwoFaApp(),
            prepare = {
                it.storage.entries[KEY_SEALED_ACCOUNTS] = sealedElsewhere
                it.provide(SecretVault.Key, SoftwareVault())
            },
        ) {
            val twofa = instance as TwoFaInstance
            assertAbsent("2fa:add")
            twofa.startAdd()
            twofa.link.value = printed(three)
            twofa.readLink()
            assertFalse(twofa.importAccounts())
            assertEquals(sealedElsewhere, runtime.storage.entries[KEY_SEALED_ACCOUNTS])
        }
    }

    /**
     * The whole trip: phone A exports to a GitHub key; the computer opens the file with the private
     * key, as `age -d` would; phone B scans what it printed. B ends up with what A had.
     */
    @Test
    fun anExportFromOnePhoneBecomesTheOtherPhonesListInOneScan() {
        val identity = JcaAge.ed25519()
        val sharer = RecordingSharer()
        val keys = """[{"id":1,"key":"${identity.publicLine}"}]"""
        runAppTest(
            TwoFaApp(),
            prepare = {
                it.storage.entries[KEY_ACCOUNTS] = Json.encodeToString(three.mapIndexed { i, a -> a.copy(id = "a$i") })
                it.network.respond(GitHubKeys.url("octocat"), keys)
                it.provide(FileSharer.Key, sharer)
            },
        ) {
            click("2fa:export")
            node("2fa:github").performTextReplacement("octocat")
            idle()
            click("2fa:find-keys")
            val transfer = "age -d -i ~/.ssh/id_ed25519 unitool-2fa-2023-11-14.age | qrencode -t ansiutf8"
            node("2fa:transfer-0").assertTextEquals(transfer)
            click("2fa:share")
            assertTrue(transfer in sharer.messages.single(), "the message beside the file says how")
        }

        val printedOnTheComputer = JcaAge.decrypt(sharer.texts.single(), identity).decodeToString()
        runAppTest(TwoFaApp(), prepare = { it.provide(QrScanner.Key, QueuedCamera(printedOnTheComputer)) }) {
            click("2fa:add")
            click("2fa:scan")
            click("2fa:import-add")
            assertEquals(three, (instance as TwoFaInstance).withoutIds())
        }
    }

    @Test
    fun theImportPageSpeaksEnglishToo() {
        assertEquals("Found 3 accounts. The ticked ones will be added.", TwoFaStringsEn.importIntro(3, 3))
        assertEquals("Add 1 account", TwoFaStringsEn.importAdd(1))
        assertEquals(
            listOf("1 аккаунт", "2 аккаунта", "5 аккаунтов", "11 аккаунтов", "21 аккаунт", "24 аккаунта"),
            listOf(1, 2, 5, 11, 21, 24).map { TwoFaStringsRu.importAdd(it).removePrefix("Добавить ") },
        )
    }
}

/** The camera, answering each of [texts] in turn — one QR code after another. */
private class QueuedCamera(vararg texts: String?) : QrScanner {
    private val queue = ArrayDeque(texts.toList())

    override suspend fun scan(): String? = queue.removeFirst()
}

/** The share sheet, keeping the texts and messages it was handed. */
private class RecordingSharer : FileSharer {
    val texts = mutableListOf<String>()
    val messages = mutableListOf<String>()

    override suspend fun share(fileName: String, text: String, mimeType: String, message: String?): Boolean {
        texts += text
        messages += message.orEmpty()
        return true
    }
}
