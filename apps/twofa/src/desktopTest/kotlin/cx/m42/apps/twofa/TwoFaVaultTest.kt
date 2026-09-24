package cx.m42.apps.twofa

import cx.m42.services.vault.SecretVault
import cx.m42.superizer.app.AppConfig
import cx.m42.superizer.testing.FakeAppRuntime
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The accounts at rest, with a vault.
 *
 * [SoftwareVault] stands in for the Keystore: real AES-GCM under a key of its own, so "sealed" is
 * checked by trying to read what was stored — with the key and without it — rather than by a fake
 * saying so. The Keystore itself is tested on a device, in `services/vault`.
 */
class TwoFaVaultTest {

    private val secret = "JBSWY3DPEHPK3PXP"
    private val otherSecret = "GEZDGNBVGY3TQOJQ"
    private val stored =
        """[{"id":"a","issuer":"GitHub","name":"octocat","secret":"$secret"},""" +
            """{"id":"b","issuer":"Google","name":"me@gmail.com","secret":"$otherSecret"}]"""

    /** Everything the app has written — what a copy of its files would hold. */
    private fun FakeAppRuntime.everythingStored(): String =
        storage.entries.entries.joinToString("\n") { "${it.key}=${it.value}" }

    /** Nothing of [words] anywhere in storage, keys and values alike. */
    private fun FakeAppRuntime.assertNothingReadable(vararg words: String) {
        val all = everythingStored()
        for (word in words) assertFalse(word in all, "\"$word\" is stored readable:\n$all")
    }

    private fun decode(json: String): List<TotpAccount> = Json.decodeFromString(json)

    @Test
    fun aSavedAccountIsStoredSealedAndNothingElseIsWritten() {
        val vault = SoftwareVault()
        runAppTest(TwoFaApp(), prepare = { it.provide(SecretVault.Key, vault) }) {
            click("2fa:add")
            type("2fa:link", "otpauth://totp/GitHub:octocat?secret=$secret&issuer=GitHub")
            click("2fa:read-link")
            click("2fa:save")

            assertEquals(setOf(KEY_SEALED_ACCOUNTS), runtime.storage.entries.keys)
            runtime.assertNothingReadable(secret, "octocat", "GitHub", "secret")
            // And it is the list, sealed — opened here with the key, which is the only way in.
            val sealed = runtime.storage.entries.getValue(KEY_SEALED_ACCOUNTS)
            assertEquals(listOf(secret), decode(assertNotNull(vault.openBlocking(sealed))).map { it.secret })
            assertNull(SoftwareVault().openBlocking(sealed), "another key opened it")
        }
    }

    @Test
    fun sealedAccountsComeBackAfterARestart() {
        val vault = SoftwareVault()
        val disk = InMemoryStorage()
        runAppTest(TwoFaApp(), prepare = { it.provide(SecretVault.Key, vault) }) {
            click("2fa:add")
            click("2fa:mode-key")
            type("2fa:issuer", "Госуслуги")
            type("2fa:secret", secret)
            click("2fa:save")
            disk.entries.putAll(runtime.storage.entries)
        }
        runAppTest(TwoFaApp(), prepare = {
            it.storage.entries.putAll(disk.entries)
            it.provide(SecretVault.Key, vault)
        }) {
            val twofa = instance as TwoFaInstance
            assertEquals(listOf("Госуслуги" to secret), twofa.accounts.value.map { it.issuer to it.secret })
            assertShown("2fa:row-0")
            assertAbsent("2fa:storage-problem")
        }
    }

    @Test
    fun aPlainListFromBeforeIsSealedOnFirstLaunchAndTheClearCopyDeleted() {
        val vault = SoftwareVault()
        runAppTest(TwoFaApp(), prepare = {
            it.storage.entries[KEY_ACCOUNTS] = stored
            it.provide(SecretVault.Key, vault)
        }) {
            val twofa = instance as TwoFaInstance
            assertEquals(listOf("a", "b"), twofa.accounts.value.map { it.id })
            assertNull(runtime.storage.entries[KEY_ACCOUNTS], "the clear copy is still there")
            runtime.assertNothingReadable(secret, otherSecret, "octocat", "me@gmail.com")
            assertEquals(decode(stored), decode(vault.openBlocking(runtime.storage.entries.getValue(KEY_SEALED_ACCOUNTS))!!))
        }
    }

    @Test
    fun aClearCopyLeftBesideASealedListIsDeletedAndTheSealedOneWins() {
        val vault = SoftwareVault()
        val newer = """[{"id":"n","issuer":"Newer","name":"","secret":"$otherSecret"}]"""
        runAppTest(TwoFaApp(), prepare = {
            // What a migration cut short between its two writes leaves behind.
            it.storage.entries[KEY_ACCOUNTS] = stored
            it.storage.entries[KEY_SEALED_ACCOUNTS] = vault.sealBlocking(newer)
            it.provide(SecretVault.Key, vault)
        }) {
            assertEquals(listOf("Newer"), (instance as TwoFaInstance).accounts.value.map { it.issuer })
            assertEquals(setOf(KEY_SEALED_ACCOUNTS), runtime.storage.entries.keys)
        }
    }

    @Test
    fun renamingAndDeletingAreSealedToo() {
        val vault = SoftwareVault()
        runAppTest(TwoFaApp(), prepare = {
            it.storage.entries[KEY_ACCOUNTS] = stored
            it.provide(SecretVault.Key, vault)
        }) {
            val twofa = instance as TwoFaInstance
            assertTrue(twofa.rename("a", "Work GitHub", "octocat"))
            twofa.delete("b")
            runtime.assertNothingReadable(secret, otherSecret, "Work GitHub", "octocat")
            val now = decode(vault.openBlocking(runtime.storage.entries.getValue(KEY_SEALED_ACCOUNTS))!!)
            assertEquals(listOf("Work GitHub" to secret), now.map { it.issuer to it.secret })
        }
    }

    @Test
    fun aListSealedElsewhereIsReportedAndNeverOverwritten() {
        // Sealed on another phone — or before a reinstall — under a key this one does not have.
        val elsewhere = SoftwareVault().sealBlocking(stored)
        runAppTest(TwoFaApp(), prepare = {
            it.storage.entries[KEY_SEALED_ACCOUNTS] = elsewhere
            it.provide(SecretVault.Key, SoftwareVault())
        }) {
            val twofa = instance as TwoFaInstance
            assertEquals(StorageProblem.Unreadable, twofa.storageProblem.value)
            assertShown("2fa:storage-problem")
            // Not "no accounts yet", and no way to add one over the top of it.
            assertAbsent("2fa:empty")
            assertAbsent("2fa:add")
            assertAbsent("2fa:export")

            twofa.draft.value = AccountDraft(issuer = "New", secret = otherSecret)
            assertFalse(twofa.save())
            assertEquals(mapOf(KEY_SEALED_ACCOUNTS to elsewhere), runtime.storage.entries)
        }
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

    @Test
    fun aSealedListOnAHostWithNoVaultIsUnreadableNotEmpty() = runAppTest(
        TwoFaApp(),
        prepare = { it.storage.entries[KEY_SEALED_ACCOUNTS] = SoftwareVault().sealBlocking(stored) },
    ) {
        assertShown("2fa:storage-problem")
        assertAbsent("2fa:add")
        assertEquals(setOf(KEY_SEALED_ACCOUNTS), runtime.storage.entries.keys)
    }

    @Test
    fun whenTheVaultRefusesNothingIsWrittenInTheClear() {
        val vault = SoftwareVault().apply { refuse = true }
        runAppTest(TwoFaApp(), prepare = { it.provide(SecretVault.Key, vault) }) {
            val twofa = instance as TwoFaInstance
            click("2fa:add")
            click("2fa:mode-key")
            type("2fa:issuer", "GitHub")
            type("2fa:secret", secret)
            click("2fa:save")

            // On screen, said to be unsaved, and nowhere on disk — sealed or otherwise.
            assertEquals(listOf("GitHub"), twofa.accounts.value.map { it.issuer })
            assertEquals(StorageProblem.NotSaved, twofa.storageProblem.value)
            assertShown("2fa:storage-problem")
            assertTrue(runtime.storage.entries.isEmpty(), runtime.everythingStored())

            // The next change that does seal takes everything on screen with it, and the note goes.
            vault.refuse = false
            val id = twofa.accounts.value.single().id
            assertTrue(twofa.rename(id, "GitHub", "octocat"))
            idle()
            assertNull(twofa.storageProblem.value)
            assertAbsent("2fa:storage-problem")
            assertEquals(listOf(secret), decode(vault.openBlocking(runtime.storage.entries.getValue(KEY_SEALED_ACCOUNTS))!!).map { it.secret })
        }
    }

    @Test
    fun aPlainListStaysAsItIsUntilItCanBeSealed() {
        val vault = SoftwareVault().apply { refuse = true }
        runAppTest(TwoFaApp(), prepare = {
            it.storage.entries[KEY_ACCOUNTS] = stored
            it.provide(SecretVault.Key, vault)
        }) {
            assertEquals(2, (instance as TwoFaInstance).accounts.value.size)
            assertEquals(mapOf(KEY_ACCOUNTS to stored), runtime.storage.entries, "the only copy was touched")
        }
    }

    @Test
    fun withoutAVaultTheListIsPlainJsonAsOnDesktopAndTheWeb() = runAppTest(TwoFaApp()) {
        click("2fa:add")
        click("2fa:mode-key")
        type("2fa:issuer", "GitHub")
        type("2fa:secret", secret)
        click("2fa:save")
        assertEquals(setOf(KEY_ACCOUNTS), runtime.storage.entries.keys)
        assertEquals(listOf(secret), decode(runtime.storage.entries.getValue(KEY_ACCOUNTS)).map { it.secret })
        assertAbsent("2fa:storage-problem")
    }

    @Test
    fun launchingDoesNotWaitForTheKeystoreAndShowsNothingUntilItAnswers() {
        val vault = GatedVault()
        val sealed = vault.inner.sealBlocking(stored)
        runAppTest(TwoFaApp(), prepare = {
            it.storage.entries[KEY_SEALED_ACCOUNTS] = sealed
            it.provide(SecretVault.Key, vault)
        }) {
            // onLaunch has returned with the vault still holding the list: a host that cancels a
            // launch which suspends has nothing to cancel.
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
    fun aSaveInFlightFinishesWhenTheAppIsClosedUnderIt() {
        val vault = GatedVault()
        vault.gate.complete(Unit)
        runAppTest(TwoFaApp(), prepare = { it.provide(SecretVault.Key, vault) }) {
            vault.gate = CompletableDeferred()
            click("2fa:add")
            click("2fa:mode-key")
            type("2fa:issuer", "GitHub")
            type("2fa:secret", secret)
            click("2fa:save")
            // Closed while the Keystore is still sealing: the handler cancels the instance's scope.
            runtime.scope.cancel()
            vault.gate.complete(Unit)
            idle()
            val sealed = assertNotNull(runtime.storage.entries[KEY_SEALED_ACCOUNTS], "the save was lost")
            assertEquals(listOf(secret), decode(vault.inner.openBlocking(sealed)!!).map { it.secret })
        }
    }

    @Test
    fun twoSavesLandInTheOrderTheyWereMade() = runTest {
        // The first seal is held until the second save has been asked for: without the lock the
        // second would write first and the older list would land on top of it.
        val release = CompletableDeferred<Unit>()
        val inner = SoftwareVault()
        var calls = 0
        val vault = object : SecretVault {
            override suspend fun seal(plaintext: String): String {
                if (calls++ == 0) release.await()
                return inner.seal(plaintext)
            }
            override suspend fun open(sealed: String): String? = inner.open(sealed)
        }
        val storage = InMemoryStorage()
        val store = AccountStore(storage, vault)
        val older = decode(stored)
        val newer = older.take(1)
        launch { store.save(older) }
        launch { store.save(newer) }
        testScheduler.runCurrent()
        release.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(newer, decode(vault.open(storage.entries.getValue(KEY_SEALED_ACCOUNTS))!!))
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
