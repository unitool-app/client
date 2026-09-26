package cx.m42.apps.twofa

import cx.m42.superizer.app.AppConfig
import cx.m42.superizer.secrets.SecretVault
import cx.m42.superizer.testing.FakeAppRuntime
import cx.m42.superizer.testing.FakeSecrets
import cx.m42.superizer.testing.InMemoryStorage
import cx.m42.superizer.testing.runAppTest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The accounts at rest, contract 3 (ssh-new 05 §3.2): in `runtime.secrets`, which the host seals —
 * the app never writes a list anywhere else — and what an older version left in its own storage,
 * moved in on the first read (04 §9, Б4).
 *
 * [SoftwareVault] stands in for the vault an older version sealed with: real AES-GCM under a key
 * of its own. The host's own sealing is tested in Superizer (`Contract3Test`), the Keystore on a
 * device in `services/vault`.
 */
class TwoFaVaultTest {

    private val secret = "JBSWY3DPEHPK3PXP"
    private val otherSecret = "GEZDGNBVGY3TQOJQ"
    private val stored =
        """[{"id":"a","issuer":"GitHub","name":"octocat","secret":"$secret"},""" +
            """{"id":"b","issuer":"Google","name":"me@gmail.com","secret":"$otherSecret"}]"""

    /** Everything the app wrote to its plain storage — what a copy of its files would hold. */
    private fun FakeAppRuntime.plainStorage(): String =
        storage.entries.entries.joinToString("\n") { "${it.key}=${it.value}" }

    private fun FakeAppRuntime.assertNothingInPlainStorage(vararg words: String) {
        val all = plainStorage()
        for (word in words) assertFalse(word in all, "\"$word\" is in the app's plain storage:\n$all")
    }

    private fun decode(json: String): List<TotpAccount> = Json.decodeFromString(json)

    @Test
    fun aSavedAccountGoesToTheHostsSecretsAndNothingElseIsWritten() = runAppTest(TwoFaApp()) {
        click("2fa:add")
        type("2fa:link", "otpauth://totp/GitHub:octocat?secret=$secret&issuer=GitHub")
        click("2fa:read-link")
        click("2fa:save")

        assertEquals(setOf(KEY_SECRET_ACCOUNTS), runtime.secrets.entries.keys)
        assertEquals(listOf(secret), decode(runtime.secrets.entries.getValue(KEY_SECRET_ACCOUNTS)).map { it.secret })
        runtime.assertNothingInPlainStorage(secret, "octocat", "GitHub")
    }

    @Test
    fun accountsComeBackAfterARestart() {
        val secrets = FakeSecrets()
        runAppTest(TwoFaApp()) {
            click("2fa:add")
            click("2fa:mode-key")
            type("2fa:issuer", "Госуслуги")
            type("2fa:secret", secret)
            click("2fa:save")
            secrets.entries.putAll(runtime.secrets.entries)
        }
        runAppTest(TwoFaApp(), prepare = { it.secrets.entries.putAll(secrets.entries) }) {
            val twofa = instance as TwoFaInstance
            assertEquals(listOf("Госуслуги" to secret), twofa.accounts.value.map { it.issuer to it.secret })
            assertShown("2fa:row-0")
            assertAbsent("2fa:storage-problem")
        }
    }

    // ------------------------------------------------------------ Б4: what older versions left

    @Test
    fun aListAnOlderVersionSealedItselfIsMovedIntoTheHostsSecrets() {
        // A tester's phone: the Play build before contract 3 sealed under the Keystore key the
        // host's vault still uses, and registers for one more release as SecretVault.Key.
        val vault = SoftwareVault()
        runAppTest(TwoFaApp(), prepare = {
            it.storage.entries[KEY_SEALED_ACCOUNTS] = vault.sealBlocking(stored)
            it.provide(SecretVault.Key, vault)
        }) {
            val twofa = instance as TwoFaInstance
            assertEquals(listOf("a", "b"), twofa.accounts.value.map { it.id })
            assertEquals(decode(stored), decode(runtime.secrets.entries.getValue(KEY_SECRET_ACCOUNTS)))
            assertNull(runtime.storage.entries[KEY_SEALED_ACCOUNTS], "the old copy goes once the new one is written")
            assertAbsent("2fa:storage-problem")
        }
    }

    @Test
    fun aPlainListFromBeforeAnyVaultIsMovedToo() = runAppTest(TwoFaApp(), prepare = { it.storage.entries[KEY_ACCOUNTS] = stored }) {
        assertEquals(listOf("a", "b"), (instance as TwoFaInstance).accounts.value.map { it.id })
        assertEquals(decode(stored), decode(runtime.secrets.entries.getValue(KEY_SECRET_ACCOUNTS)))
        runtime.assertNothingInPlainStorage(secret, otherSecret, "octocat", "me@gmail.com")
    }

    @Test
    fun oldCopiesLeftBesideTheHostsListAreDroppedAndTheHostsWins() {
        val vault = SoftwareVault()
        val newer = """[{"id":"n","issuer":"Newer","name":"","secret":"$otherSecret"}]"""
        runAppTest(TwoFaApp(), prepare = {
            // What a move cut short between its two writes leaves behind.
            it.storage.entries[KEY_ACCOUNTS] = stored
            it.storage.entries[KEY_SEALED_ACCOUNTS] = vault.sealBlocking(stored)
            it.secrets.entries[KEY_SECRET_ACCOUNTS] = newer
            it.provide(SecretVault.Key, vault)
        }) {
            assertEquals(listOf("Newer"), (instance as TwoFaInstance).accounts.value.map { it.issuer })
            assertTrue(runtime.storage.entries.keys.none { it.startsWith("accounts") }, runtime.plainStorage())
        }
    }

    @Test
    fun anOldListSealedElsewhereIsReportedAndNeverOverwritten() {
        // Sealed on another phone — or before a reinstall — under a key this one does not have.
        val elsewhere = SoftwareVault().sealBlocking(stored)
        runAppTest(TwoFaApp(), prepare = {
            it.storage.entries[KEY_SEALED_ACCOUNTS] = elsewhere
            it.provide(SecretVault.Key, SoftwareVault())
        }) {
            val twofa = instance as TwoFaInstance
            assertEquals(StorageProblem.Unreadable, twofa.storageProblem.value)
            assertShown("2fa:storage-problem")
            assertAbsent("2fa:empty")
            assertAbsent("2fa:add")
            assertAbsent("2fa:export")

            twofa.draft.value = AccountDraft(issuer = "New", secret = otherSecret)
            assertFalse(twofa.save())
            assertEquals(mapOf(KEY_SEALED_ACCOUNTS to elsewhere), runtime.storage.entries)
            assertTrue(runtime.secrets.entries.isEmpty())
        }
    }

    @Test
    fun anOldSealedListOnAHostWithNoVaultIsUnreadableNotEmpty() = runAppTest(
        TwoFaApp(),
        prepare = { it.storage.entries[KEY_SEALED_ACCOUNTS] = SoftwareVault().sealBlocking(stored) },
    ) {
        assertShown("2fa:storage-problem")
        assertAbsent("2fa:add")
        assertEquals(setOf(KEY_SEALED_ACCOUNTS), runtime.storage.entries.keys)
    }

    @Test
    fun anUnreadableListIsNotOpenedOverByALinkOrASnapshot() {
        val elsewhere = SoftwareVault().sealBlocking(stored)
        runAppTest(
            TwoFaApp(),
            config = AppConfig(buildJsonObject { put("otpauth", JsonPrimitive("otpauth://totp/X:y?secret=$otherSecret")) }),
            restore = buildJsonObject { put("page", JsonPrimitive("add")) },
            prepare = {
                it.storage.entries[KEY_SEALED_ACCOUNTS] = elsewhere
                it.provide(SecretVault.Key, SoftwareVault())
            },
        ) {
            assertEquals(Page.List, (instance as TwoFaInstance).page.value)
            assertShown("2fa:storage-problem")
            assertEquals(mapOf(KEY_SEALED_ACCOUNTS to elsewhere), runtime.storage.entries)
        }
    }

    // ------------------------------------------------------------ the host's side failing

    @Test
    fun aListTheHostCannotOpenNowIsLockedAndUnlockingReadsItAgain() = runAppTest(TwoFaApp(), prepare = {
        it.secrets.entries[KEY_SECRET_ACCOUNTS] = stored
        it.secrets.unavailable = true
    }) {
        val twofa = instance as TwoFaInstance
        assertEquals(StorageProblem.Locked, twofa.storageProblem.value)
        assertShown("2fa:storage-problem")
        assertAbsent("2fa:add")
        assertAbsent("2fa:empty")
        twofa.draft.value = AccountDraft(issuer = "New", secret = otherSecret)
        assertFalse(twofa.save(), "nothing written over a list that is there")
        assertEquals(stored, runtime.secrets.entries[KEY_SECRET_ACCOUNTS])

        runtime.secrets.unavailable = false
        click("2fa:unlock")
        idle()
        assertNull(twofa.storageProblem.value)
        assertEquals(listOf("a", "b"), twofa.accounts.value.map { it.id })
    }

    @Test
    fun whenTheHostCannotSealNothingIsWrittenAnywhere() = runAppTest(TwoFaApp(), prepare = { it.secrets.failsToSeal = true }) {
        val twofa = instance as TwoFaInstance
        click("2fa:add")
        click("2fa:mode-key")
        type("2fa:issuer", "GitHub")
        type("2fa:secret", secret)
        click("2fa:save")

        assertEquals(listOf("GitHub"), twofa.accounts.value.map { it.issuer })
        assertEquals(StorageProblem.NotSaved, twofa.storageProblem.value)
        assertTrue(runtime.secrets.entries.isEmpty())
        runtime.assertNothingInPlainStorage(secret, "GitHub")

        runtime.secrets.failsToSeal = false
        val id = twofa.accounts.value.single().id
        assertTrue(twofa.rename(id, "GitHub", "octocat"))
        idle()
        assertNull(twofa.storageProblem.value)
        assertEquals(listOf(secret), decode(runtime.secrets.entries.getValue(KEY_SECRET_ACCOUNTS)).map { it.secret })
    }

    @Test
    fun anOldPlainListStaysWhereItIsUntilTheHostCanTakeIt() = runAppTest(TwoFaApp(), prepare = {
        it.storage.entries[KEY_ACCOUNTS] = stored
        it.secrets.failsToSeal = true
    }) {
        assertEquals(2, (instance as TwoFaInstance).accounts.value.size)
        assertEquals(mapOf(KEY_ACCOUNTS to stored), runtime.storage.entries, "the only copy was touched")
    }

    @Test
    fun launchingDoesNotWaitForTheOldVaultAndShowsNothingUntilItAnswers() {
        val vault = GatedVault()
        val sealed = vault.inner.sealBlocking(stored)
        runAppTest(TwoFaApp(), prepare = {
            it.storage.entries[KEY_SEALED_ACCOUNTS] = sealed
            it.provide(SecretVault.Key, vault)
        }) {
            val twofa = instance as TwoFaInstance
            assertFalse(twofa.loaded.value)
            assertAbsent("2fa:list")
            assertAbsent("2fa:empty")

            vault.gate.complete(Unit)
            idle()
            assertShown("2fa:row-1")
            assertEquals(listOf("a", "b"), twofa.accounts.value.map { it.id })
        }
    }

    @Test
    fun aPageRestoredBeforeTheListIsReadIsCheckedOnceItIs() {
        for ((id, expected) in listOf("a" to Page.Details("a"), "gone" to Page.List)) {
            val vault = GatedVault()
            runAppTest(
                TwoFaApp(),
                restore = buildJsonObject {
                    put("page", JsonPrimitive("details"))
                    put("id", JsonPrimitive(id))
                },
                prepare = {
                    it.storage.entries[KEY_SEALED_ACCOUNTS] = vault.inner.sealBlocking(stored)
                    it.provide(SecretVault.Key, vault)
                },
            ) {
                val twofa = instance as TwoFaInstance
                vault.gate.complete(Unit)
                idle()
                assertEquals(expected, twofa.page.value, "restoring details of \"$id\"")
            }
        }
    }

    @Test
    fun twoSavesLandInTheOrderTheyWereMade() = runTest {
        // The first write is held until the second save has been asked for: without the lock the
        // second would write first and the older list would land on top of it.
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val secrets = object : cx.m42.superizer.runtime.StorageService by InMemoryStorage() {
            val inner = InMemoryStorage()
            override suspend fun get(key: String) = inner.get(key)
            override suspend fun set(key: String, value: String) {
                if (calls++ == 0) release.await()
                inner.set(key, value)
            }
        }
        val store = AccountStore(InMemoryStorage(), secrets, legacyVault = null)
        val older = decode(stored)
        val newer = older.take(1)
        launch { store.save(older) }
        launch { store.save(newer) }
        testScheduler.runCurrent()
        release.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(newer, decode(secrets.inner.entries.getValue(KEY_SECRET_ACCOUNTS)))
    }
}

/** A [SoftwareVault] that answers only once [gate] is opened, as a slow Keystore would. */
private class GatedVault : SecretVault {
    val inner = SoftwareVault()
    var gate = CompletableDeferred<Unit>()

    override suspend fun seal(plaintext: String): String {
        gate.await()
        return inner.seal(plaintext)
    }

    override suspend fun open(sealed: String): String? {
        gate.await()
        return inner.open(sealed)
    }
}

/** AES-256-GCM under a key of its own, in memory: a phone's Keystore, without the phone. */
internal class SoftwareVault : SecretVault {

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    /** Set to make [seal] throw, as a Keystore that cannot be reached does. */
    var refuse = false

    override suspend fun seal(plaintext: String): String = sealBlocking(plaintext)

    fun sealBlocking(plaintext: String): String {
        check(!refuse) { "keystore unavailable" }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return "v1:" + Base64.encode(nonce + cipher.doFinal(plaintext.encodeToByteArray()))
    }

    override suspend fun open(sealed: String): String? = openBlocking(sealed)

    fun openBlocking(sealed: String): String? = runCatching {
        val body = Base64.decode(sealed.removePrefix("v1:"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, body, 0, 12))
        cipher.doFinal(body, 12, body.size - 12).decodeToString()
    }.getOrNull()
}
