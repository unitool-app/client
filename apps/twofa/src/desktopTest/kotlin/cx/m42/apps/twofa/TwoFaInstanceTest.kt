package cx.m42.apps.twofa

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import cx.m42.services.qr.QrScanner
import cx.m42.services.share.FileSharer
import cx.m42.services.ssh.KnownKey
import cx.m42.services.ssh.PublicKeyBook
import cx.m42.services.ssh.SshPublicKey
import cx.m42.superizer.app.AppConfig
import cx.m42.superizer.testing.AppTestScope
import cx.m42.superizer.testing.AppContractTest
import cx.m42.superizer.testing.runAppTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The guarantees the host assumes, in one line (D21). */
class TwoFaContractTest : AppContractTest(TwoFaApp()) {

    override val configFixtures: List<String> = listOf(
        """{"schemaVersion":1}""",
        """{"otpauth":"otpauth://totp/GitHub:octocat?secret=JBSWY3DPEHPK3PXP"}""",
    )

    override val stateFixtures: List<String> = listOf(
        """{"page":"list","query":"git"}""",
        """{"page":"add","mode":"Key","issuer":"GitHub","name":"me","digits":6,"period":30,"algorithm":"SHA1"}""",
        """{"page":"details","id":"gone"}""",
        """{"page":"export","github":"octocat"}""",
    )
}

/**
 * The app on fakes, driven by tags: the flows a person goes through, not the functions behind them.
 *
 * The fake clock is 1 700 000 000 000 ms — 2023-11-14 22:13:20 UTC — so every code below is fixed.
 */
class TwoFaInstanceTest {

    private val secret = "JBSWY3DPEHPK3PXP"
    private val stored =
        """[{"id":"a","issuer":"GitHub","name":"octocat","secret":"$secret"},""" +
            """{"id":"b","issuer":"Google","name":"me@gmail.com","secret":"GEZDGNBVGY3TQOJQ"}]"""

    /**
     * A node inside a list row. The row is one tap target, so Compose merges its text into a
     * single node for a screen reader — right for a person, and the reason a test has to look
     * at the unmerged tree to find the code on its own.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun AppTestScope<*>.inRow(tag: String): SemanticsNodeInteraction =
        ui.onNodeWithTag(tag, useUnmergedTree = true)

    private fun TwoFaInstance.expected(index: Int): String =
        Totp.grouped(Totp.code(accounts.value[index], runtime.clock.now()))

    @Test
    fun aFreshInstallShowsTheEmptyStateAndTheAddButton() = runAppTest(TwoFaApp()) {
        assertShown("2fa:empty")
        assertShown("2fa:add")
    }

    @Test
    fun storedAccountsAreListedWithTheirCurrentCodes() = runAppTest(
        TwoFaApp(),
        prepare = { it.storage.entries[KEY_ACCOUNTS] = stored },
    ) {
        val twofa = instance as TwoFaInstance
        inRow("2fa:code-0").assertTextEquals(twofa.expected(0))
        inRow("2fa:code-1").assertTextEquals(twofa.expected(1))
    }

    @Test
    fun theCodeTurnsOverWhenThePeriodEnds() = runAppTest(
        TwoFaApp(),
        prepare = { it.storage.entries[KEY_ACCOUNTS] = stored },
    ) {
        val twofa = instance as TwoFaInstance
        val before = twofa.expected(0)
        runtime.clock.advance(seconds = 30)
        twofa.tick()
        idle()
        val after = twofa.expected(0)
        assertTrue(before != after)
        inRow("2fa:code-0").assertTextEquals(after)
    }

    @Test
    fun addingByLinkReadsTheLinkThenSavesOnlyOnSave() = runAppTest(TwoFaApp()) {
        click("2fa:add")
        type("2fa:link", "otpauth://totp/GitHub:octocat?secret=$secret&issuer=GitHub")
        click("2fa:read-link")
        // The form now shows what the link said, and nothing is stored yet.
        assertShown("2fa:preview")
        assertEquals(null, runtime.storage.entries[KEY_ACCOUNTS])

        click("2fa:save")
        assertShown("2fa:list")
        val twofa = instance as TwoFaInstance
        assertEquals(listOf("GitHub"), twofa.accounts.value.map { it.issuer })
        assertTrue(runtime.secrets.entries[KEY_SECRET_ACCOUNTS]!!.contains(secret))
        inRow("2fa:code-0").assertTextEquals(twofa.expected(0))
    }

    @Test
    fun aBadLinkSaysWhyAndStaysOnTheForm() = runAppTest(TwoFaApp()) {
        click("2fa:add")
        type("2fa:link", "otpauth://hotp/x?secret=$secret")
        click("2fa:read-link")
        assertShown("2fa:error")
        assertEquals(DraftError.Hotp, (instance as TwoFaInstance).draftError.value)
    }

    @Test
    fun addingByKeyValidatesThenSaves() = runAppTest(TwoFaApp()) {
        click("2fa:add")
        click("2fa:mode-key")
        type("2fa:issuer", "Yandex")
        type("2fa:secret", "not base32!")
        click("2fa:save")
        assertEquals(DraftError.BadSecret, (instance as TwoFaInstance).draftError.value)

        (instance as TwoFaInstance).editDraft { copy(secret = "") }
        idle()
        type("2fa:secret", "jbsw y3dp ehpk 3pxp")
        click("2fa:digits-8")
        click("2fa:save")
        val account = (instance as TwoFaInstance).accounts.value.single()
        assertEquals("Yandex", account.issuer)
        assertEquals(secret, account.secret)
        assertEquals(8, account.digits)
    }

    @Test
    fun tappingARowCopiesAndSaysSo() = runAppTest(
        TwoFaApp(),
        prepare = { it.storage.entries[KEY_ACCOUNTS] = stored },
    ) {
        click("2fa:row-1")
        inRow("2fa:copied").assertIsDisplayed()
        assertEquals("b", (instance as TwoFaInstance).copiedId.value)
        assertEquals(1, runtime.haptics.ticks)
    }

    @Test
    fun searchNarrowsTheList() = runAppTest(
        TwoFaApp(),
        prepare = { it.storage.entries[KEY_ACCOUNTS] = stored },
    ) {
        type("2fa:search", "goo")
        assertShown("2fa:row-0")
        assertAbsent("2fa:row-1")
        type("2fa:search", "zzz")
        assertShown("2fa:nothing-found")
    }

    @Test
    fun detailsRenamesAndDeletesWithAConfirmation() = runAppTest(
        TwoFaApp(),
        prepare = { it.storage.entries[KEY_ACCOUNTS] = stored },
    ) {
        val twofa = instance as TwoFaInstance
        click("2fa:details-0")
        assertShown("2fa:details-page")
        assertEquals("GitHub", twofa.chrome.value.title)

        node("2fa:rename-issuer").performTextReplacement("GitHub Enterprise")
        idle()
        click("2fa:rename")
        assertEquals("GitHub Enterprise", twofa.accounts.value[0].issuer)
        assertEquals("GitHub Enterprise", twofa.chrome.value.title)

        // Delete sits at the bottom of the page, and where the fold falls depends on the fonts:
        // with DejaVu (the CI runner) the confirm button starts below the window and a bare tap
        // lands on nothing.
        node("2fa:delete").performScrollTo().performClick()
        assertEquals(2, twofa.accounts.value.size, "the first tap only asks")
        node("2fa:delete-confirm").performScrollTo().performClick()
        assertShown("2fa:list")
        assertEquals(listOf("b"), twofa.accounts.value.map { it.id })
        assertTrue(!runtime.secrets.entries[KEY_SECRET_ACCOUNTS]!!.contains("\"a\""))
    }

    @Test
    fun theSecretIsHiddenUntilAsked() = runAppTest(
        TwoFaApp(),
        prepare = { it.storage.entries[KEY_ACCOUNTS] = stored },
    ) {
        click("2fa:details-0")
        node("2fa:secret-value").assertTextEquals("•••• •••• •••• ••••")
        click("2fa:toggle-secret")
        node("2fa:secret-value").assertTextEquals("JBSW Y3DP EHPK 3PXP")
    }

    @Test
    fun backInsideTheAppReturnsToTheListAndAtTheRootBelongsToTheHost() = runAppTest(TwoFaApp()) {
        val twofa = instance as TwoFaInstance
        click("2fa:add")
        assertTrue(twofa.chrome.value.canGoBack)
        assertTrue(twofa.back())
        idle()
        assertShown("2fa:list")
        assertTrue(!twofa.back())
    }

    @Test
    fun aConfigLinkOpensTheFilledFormAndStillWaitsForSave() = runAppTest(
        TwoFaApp(),
        config = AppConfig(
            Json.parseToJsonElement(
                """{"otpauth":"otpauth://totp/GitHub:octocat?secret=$secret"}""",
            ) as JsonObject,
        ),
    ) {
        val twofa = instance as TwoFaInstance
        assertShown("2fa:add-page")
        assertEquals("octocat", twofa.draft.value.name)
        assertEquals(emptyList(), twofa.accounts.value)
        click("2fa:save")
        assertEquals(1, twofa.accounts.value.size)
    }

    @Test
    fun theDeepLinkTurnsItsQueryIntoThatConfig() {
        val app = TwoFaApp()
        val config = app.configSpec.decode(
            AppConfig(buildJsonObject { put("otpauth", JsonPrimitive("otpauth://totp/x?secret=$secret")) }),
        ).getOrThrow()
        assertNotNull(config.otpauth)
    }

    @Test
    fun withoutACameraThereIsNoScanButtonAndThePastedLinkIsTheWayIn() = runAppTest(TwoFaApp()) {
        click("2fa:add")
        assertAbsent("2fa:scan")
        assertShown("2fa:link")
    }

    @Test
    fun aScannedCodeFillsTheFormAndStillWaitsForSave() {
        val camera = FakeCamera("otpauth://totp/GitHub:octocat?secret=$secret&issuer=GitHub")
        runAppTest(TwoFaApp(), prepare = { it.provide(QrScanner.Key, camera) }) {
            val twofa = instance as TwoFaInstance
            click("2fa:add")
            click("2fa:scan")
            assertEquals(1, camera.opened)
            assertShown("2fa:preview")
            assertEquals("octocat", twofa.draft.value.name)
            assertEquals(null, runtime.storage.entries[KEY_ACCOUNTS], "a scan is not a save")
            assertEquals(1, runtime.haptics.ticks)

            click("2fa:save")
            assertEquals(listOf("GitHub"), twofa.accounts.value.map { it.issuer })
        }
    }

    @Test
    fun closingTheScannerLeavesTheFormAsItWas() {
        val camera = FakeCamera(null)
        runAppTest(TwoFaApp(), prepare = { it.provide(QrScanner.Key, camera) }) {
            val twofa = instance as TwoFaInstance
            click("2fa:add")
            click("2fa:scan")
            assertEquals(1, camera.opened)
            assertEquals(AddMode.Link, twofa.addMode.value)
            assertEquals("", twofa.link.value)
            assertEquals(null, twofa.draftError.value)
            assertEquals(false, twofa.scanning.value)
            assertAbsent("2fa:error")
        }
    }

    @Test
    fun aCodeThatIsNotASetupLinkStaysVisibleWithTheReason() {
        // Google Authenticator's transfer code, cut short: it reads as no accounts at all.
        val camera = FakeCamera("otpauth-migration://offline?data=CjEKCkhlbGxvId6tvu8SGA")
        runAppTest(TwoFaApp(), prepare = { it.provide(QrScanner.Key, camera) }) {
            val twofa = instance as TwoFaInstance
            click("2fa:add")
            click("2fa:scan")
            assertShown("2fa:error")
            assertEquals(DraftError.Migration, twofa.draftError.value)
            assertTrue(twofa.link.value.startsWith("otpauth-migration://"), "the field shows what was read")
            assertEquals(0, runtime.haptics.ticks)
        }
    }

    @Test
    fun theSnapshotKeepsTheFormButNeverTheSecret() = runAppTest(TwoFaApp()) {
        val twofa = instance as TwoFaInstance
        click("2fa:add")
        click("2fa:mode-key")
        type("2fa:issuer", "GitHub")
        type("2fa:secret", secret)
        val snapshot = twofa.saveState()
        assertTrue(secret !in snapshot.toString())
        assertEquals("add", (snapshot["page"] as JsonPrimitive).content)
    }
}

/**
 * The export, driven the way a person drives it: a login typed, GitHub asked (a [FakeNetwork]
 * stub), a share tapped. What reaches the share sheet is then *opened* — by [JcaAge], with the
 * private half of the key GitHub was said to have — because "a file was shared" proves nothing.
 */
class TwoFaExportTest {

    private val stored =
        """[{"id":"a","issuer":"GitHub","name":"octocat","secret":"JBSWY3DPEHPK3PXP"},""" +
            """{"id":"b","issuer":"Госуслуги","name":"ivan","secret":"GEZDGNBVGY3TQOJQ","digits":8}]"""

    private val url = GitHubKeys.url("octocat")

    private fun keysJson(vararg lines: String): String =
        lines.mapIndexed { i, line -> """{"id":${i + 1},"key":"$line"}""" }.joinToString(",", "[", "]")

    private fun openExport(scope: AppTestScope<*>) = with(scope) {
        click("2fa:export")
        assertShown("2fa:export-page")
        node("2fa:github").performTextReplacement("octocat")
        idle()
        click("2fa:find-keys")
    }

    @Test
    fun anotherDevicesSavedKeyIsEnoughWithoutGitHub() {
        // The host's saved public keys (.ai-notes/public-keys, D515): the other phone's key, read off its screen.
        val identity = JcaAge.ed25519()
        val sharer = FakeSharer()
        val book = object : PublicKeyBook {
            override val keys = MutableStateFlow(listOf(KnownKey(SshPublicKey.parse(identity.publicLine)!!, "Ivan's phone", 0)))
        }
        runAppTest(
            TwoFaApp(),
            prepare = {
                it.storage.entries[KEY_ACCOUNTS] = stored
                it.provide(FileSharer.Key, sharer)
                it.provide(PublicKeyBook.Key, book)
            },
        ) {
            val twofa = instance as TwoFaInstance
            click("2fa:export")
            assertShown("2fa:saved-key-0")
            // Saved keys start unticked: nothing to share until one is chosen.
            click("2fa:share")
            assertTrue(sharer.shared.isEmpty())

            click("2fa:saved-key-0")
            node("2fa:command-0").assertTextEquals("age -d -i ~/.ssh/id_ed25519 unitool-2fa-2023-11-14.age")
            click("2fa:share")
            val shared = sharer.shared.single()
            assertTrue(shared.message!!.startsWith("Ключи 2FA из Unitool, зашифрованы для: Ivan's phone."), shared.message)
            val opened = JcaAge.decrypt(shared.text, identity).decodeToString().lines().filter { it.isNotEmpty() }
            assertEquals(twofa.accounts.value.map { OtpAuthUri.format(it) }, opened)
            assertTrue(runtime.network.calls.isEmpty(), "GitHub was never asked")
        }
    }

    @Test
    fun thereIsNothingToExportOnAFreshInstall() = runAppTest(TwoFaApp()) {
        assertAbsent("2fa:export")
    }

    @Test
    fun theSharedFileOpensWithTheKeyGitHubListsAndHoldsEveryAccount() {
        val identity = JcaAge.ed25519()
        val sharer = FakeSharer()
        runAppTest(
            TwoFaApp(),
            prepare = {
                it.storage.entries[KEY_ACCOUNTS] = stored
                it.network.respond(url, keysJson(identity.publicLine))
                it.provide(FileSharer.Key, sharer)
            },
        ) {
            val twofa = instance as TwoFaInstance
            openExport(this)
            assertEquals(listOf(url), runtime.network.calls.map { it.url })
            assertShown("2fa:key-0")
            node("2fa:command-0").assertTextEquals("age -d -i ~/.ssh/id_ed25519 unitool-2fa-2023-11-14.age")

            click("2fa:share")
            val shared = sharer.shared.single()
            assertEquals("unitool-2fa-2023-11-14.age", shared.name)
            assertEquals(EXPORT_MIME, shared.mimeType)
            assertTrue(shared.message!!.contains("age -d -i ~/.ssh/id_ed25519 unitool-2fa-2023-11-14.age"))
            assertTrue(shared.text.startsWith("-----BEGIN AGE ENCRYPTED FILE-----"))
            assertTrue("JBSWY3DPEHPK3PXP" !in shared.text)

            val opened = JcaAge.decrypt(shared.text, identity).decodeToString().lines().filter { it.isNotEmpty() }
            assertEquals(twofa.accounts.value.map { OtpAuthUri.format(it) }, opened)
            // Every link reads back as the account it came from — the Cyrillic issuer included.
            assertEquals(
                twofa.accounts.value.map { it.copy(id = "") },
                opened.map { OtpAuthUri.parse(it).getOrThrow().validate("").getOrThrow() },
            )
            assertShown("2fa:export-done")
            assertEquals("octocat", runtime.storage.entries[KEY_EXPORT_LOGIN])
        }
    }

    @Test
    fun keysAgeCannotUseAreListedButNotTicked() {
        val sharer = FakeSharer()
        runAppTest(
            TwoFaApp(),
            prepare = {
                it.storage.entries[KEY_ACCOUNTS] = stored
                it.network.respond(url, keysJson(Fixtures.ECDSA, Fixtures.RSA_1024, Fixtures.ED25519, Fixtures.RSA_3072))
                it.provide(FileSharer.Key, sharer)
            },
        ) {
            val twofa = instance as TwoFaInstance
            openExport(this)
            val loaded = twofa.exportKeys.value as KeysState.Loaded
            // Usable first, and only those ticked.
            assertEquals(listOf("ED25519", "RSA 3072", "ECDSA 256", "RSA 1024"), loaded.keys.map { it.key.label })
            assertEquals(loaded.keys.take(2).map { it.key.fingerprint }.toSet(), twofa.exportSelected.value)
            // One command per kind of key, each with the kind's default file.
            node("2fa:command-0").assertTextEquals("age -d -i ~/.ssh/id_ed25519 unitool-2fa-2023-11-14.age")
            node("2fa:command-1").assertTextEquals("age -d -i ~/.ssh/id_rsa unitool-2fa-2023-11-14.age")

            click("2fa:key-2")
            assertEquals(2, twofa.exportSelected.value.size, "a key age cannot use does not tick")
            click("2fa:key-0")
            click("2fa:key-1")
            assertTrue(twofa.exportSelected.value.isEmpty())
            assertAbsent("2fa:command-0")
            // AppButton draws itself disabled and swallows the tap; it has no semantics to assert on.
            click("2fa:share")
            click("2fa:copy-export")
            assertTrue(sharer.shared.isEmpty())
            assertNull(twofa.exportDone.value)
        }
    }

    @Test
    fun withoutAShareSheetTheEncryptedTextIsForTheClipboard() {
        val identity = JcaAge.rsa(2048)
        runAppTest(
            TwoFaApp(),
            prepare = {
                it.storage.entries[KEY_ACCOUNTS] = stored
                it.network.respond(url, keysJson(identity.publicLine))
            },
        ) {
            val twofa = instance as TwoFaInstance
            openExport(this)
            assertAbsent("2fa:share")
            assertShown("2fa:copy-export")
            val text = twofa.copyExport()!!
            assertEquals(
                twofa.accounts.value.joinToString("") { OtpAuthUri.format(it) + "\n" },
                JcaAge.decrypt(text, identity).decodeToString(),
            )
            assertEquals(ExportDone("unitool-2fa-2023-11-14.age", ExportDone.Via.Copied), twofa.exportDone.value)
        }
    }

    @Test
    fun whatGitHubSaidIsShownInWords() {
        val answers = listOf(
            404 to KeysError.NoSuchUser,
            403 to KeysError.RateLimited,
            500 to KeysError.Server,
        )
        for ((status, error) in answers) {
            runAppTest(
                TwoFaApp(),
                prepare = {
                    it.storage.entries[KEY_ACCOUNTS] = stored
                    it.network.respond(url, """{"message":"x"}""", status)
                },
            ) {
                openExport(this)
                assertEquals(KeysState.Failed(error), (instance as TwoFaInstance).exportKeys.value)
                assertShown("2fa:keys-error")
                assertAbsent("2fa:copy-export")
            }
        }
        runAppTest(
            TwoFaApp(),
            prepare = {
                it.storage.entries[KEY_ACCOUNTS] = stored
                it.network.fail(url)
            },
        ) {
            openExport(this)
            assertEquals(KeysState.Failed(KeysError.Offline), (instance as TwoFaInstance).exportKeys.value)
        }
        for ((body, error) in listOf("[]" to KeysError.NoKeys, keysJson(Fixtures.ECDSA) to KeysError.NoUsableKeys)) {
            runAppTest(
                TwoFaApp(),
                prepare = {
                    it.storage.entries[KEY_ACCOUNTS] = stored
                    it.network.respond(url, body)
                },
            ) {
                openExport(this)
                assertEquals(KeysState.Failed(error), (instance as TwoFaInstance).exportKeys.value)
            }
        }
    }

    @Test
    fun aNameThatCannotBeALoginNeverReachesTheNetwork() = runAppTest(
        TwoFaApp(),
        prepare = { it.storage.entries[KEY_ACCOUNTS] = stored },
    ) {
        click("2fa:export")
        type("2fa:github", "../../orgs/x")
        click("2fa:find-keys")
        assertEquals(KeysState.Failed(KeysError.BadLogin), (instance as TwoFaInstance).exportKeys.value)
        assertTrue(runtime.network.calls.isEmpty())
    }

    @Test
    fun whatPeoplePasteIsReadAsTheLogin() {
        for (text in listOf("octocat", " @octocat ", "github.com/octocat", "https://github.com/octocat.keys", "https://github.com/octocat/")) {
            assertEquals("octocat", GitHubKeys.login(text), text)
        }
        for (text in listOf("", "-octocat", "octo--cat", "octo cat", "a".repeat(40), "octocat?x=1")) {
            assertNull(GitHubKeys.login(text), text)
        }
    }

    @Test
    fun editingTheLoginHidesTheOtherAccountsKeys() {
        val identity = JcaAge.ed25519()
        runAppTest(
            TwoFaApp(),
            prepare = {
                it.storage.entries[KEY_ACCOUNTS] = stored
                it.network.respond(url, keysJson(identity.publicLine))
            },
        ) {
            openExport(this)
            assertShown("2fa:key-0")
            node("2fa:github").performTextReplacement("octocat2")
            idle()
            assertAbsent("2fa:key-0")
            assertEquals(KeysState.Idle, (instance as TwoFaInstance).exportKeys.value)
        }
    }

    @Test
    fun theLastLoginIsOfferedNextTime() = runAppTest(
        TwoFaApp(),
        prepare = {
            it.storage.entries[KEY_ACCOUNTS] = stored
            it.storage.entries[KEY_EXPORT_LOGIN] = "octocat"
        },
    ) {
        click("2fa:export")
        // The field's placeholder is also "octocat", so the value is checked, not the pixels.
        assertEquals("octocat", (instance as TwoFaInstance).exportLogin.value)
    }

    @Test
    fun theSnapshotKeepsTheExportPageAndTheLoginButNoKeys() {
        val identity = JcaAge.ed25519()
        runAppTest(
            TwoFaApp(),
            prepare = {
                it.storage.entries[KEY_ACCOUNTS] = stored
                it.network.respond(url, keysJson(identity.publicLine))
            },
        ) {
            val twofa = instance as TwoFaInstance
            openExport(this)
            val snapshot = twofa.saveState()
            assertEquals("export", (snapshot["page"] as JsonPrimitive).content)
            assertEquals("octocat", (snapshot["github"] as JsonPrimitive).content)
            assertTrue("AAAA" !in snapshot.toString(), "keys are asked for again, not restored")
        }
    }

    @Test
    fun theFileIsNamedForTheUtcDate() {
        assertEquals("1970-01-01", Export.isoDate(0))
        assertEquals("2023-11-14", Export.isoDate(1_700_000_000_000))
        assertEquals("2024-02-29", Export.isoDate(1_709_164_800_000))
        assertEquals("2026-09-24", Export.isoDate(1_790_294_399_999))
        assertEquals("1969-12-31", Export.isoDate(-1))
    }
}

/** The share sheet, keeping what it was handed. */
private class FakeSharer : FileSharer {
    data class Shared(val name: String, val text: String, val mimeType: String, val message: String?)

    val shared = mutableListOf<Shared>()

    override suspend fun share(fileName: String, text: String, mimeType: String, message: String?): Boolean {
        shared += Shared(fileName, text, mimeType, message)
        return true
    }
}

/** The camera, answering [text] every time it is opened. */
private class FakeCamera(private val text: String?) : QrScanner {
    var opened = 0

    override suspend fun scan(): String? {
        opened++
        return text
    }
}
