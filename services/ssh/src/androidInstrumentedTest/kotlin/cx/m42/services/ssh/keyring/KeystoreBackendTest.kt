package cx.m42.services.ssh.keyring

import android.app.KeyguardManager
import android.content.Context
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cx.m42.services.ssh.SecretBytes
import cx.m42.services.ssh.SshPublicKey
import cx.m42.services.ssh.SshKeyringHost
import cx.m42.services.ssh.key.OpenSshKeyFile
import cx.m42.services.ssh.key.PrivateKey
import cx.m42.superizer.runtime.Clock
import cx.m42.superizer.runtime.Platform
import cx.m42.superizer.ssh.ImportOptions
import cx.m42.superizer.ssh.ImportOutcome
import cx.m42.superizer.ssh.KeyCapability
import cx.m42.superizer.ssh.KeyId
import cx.m42.superizer.ssh.KeyOperation
import cx.m42.superizer.ssh.KeyOrigin
import cx.m42.superizer.ssh.KeyStorage
import cx.m42.superizer.ssh.SshKeyInfo
import cx.m42.superizer.ssh.UsePolicy
import cx.m42.superizer.testing.Canary
import cx.m42.superizer.testing.LeakScanner
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Keystore backend against a real Keystore (03 §6.2): I2 a resident key has no private half
 * outside it, I3/I7 nothing happens without the person, I11 delete leaves no alias, 01 §5.7 a key
 * the lock took is unusable and still shown, I1/I12 the canary is on no disk and in no heap.
 *
 * PIN 1111 for the length of each test on a device with no lock, removed after — a device that has
 * one (a person's phone) keeps it, and the one test that needs to remove a lock is skipped there.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreBackendTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private var setPin = false
    private var lockedAt = 0L
    private val alias = "cx.m42.services.ssh.test." + System.nanoTime()
    private val nobody = { _: UseReason -> PromptText("test", null, null) }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().decodeToString() }

    @Before
    fun lock() {
        shell("svc power stayon true")
        shell("input keyevent KEYCODE_WAKEUP")
        if (context.getSystemService(KeyguardManager::class.java).isDeviceSecure) return
        shell("locksettings set-pin 1111")
        lockedAt = System.currentTimeMillis()
        setPin = true
    }

    @After
    fun cleanUp() {
        keystore.aliases().toList().filter { it.startsWith(alias) }.forEach { runCatching { keystore.deleteEntry(it) } }
        if (setPin) shell("locksettings clear --old 1111")
    }

    private fun backend() = KeystoreBackend(context, { null }, nobody)

    private fun reason(key: SshPublicKey, biometricOnly: Boolean = false) = UseReason(
        null,
        SshKeyInfo(key.id, key.type, key.authorizedKeysLine(), key.label, "t", KeyStorage.Tee, UsePolicy.EveryUse, KeyOrigin.Imported, setOf(KeyCapability.Sign), 0, biometricOnly = biometricOnly),
        KeyOperation.Import,
    )

    private fun canary(): PrivateKey {
        val seed = Canary.ED25519_SEED.reveal()
        return PrivateKey.ed25519(seed, "canary").also { seed.fill(0) }
    }

    /** A generated RSA key through our own writer and reader, so no tool is needed on the device. */
    private fun rsa(): PrivateKey {
        val private = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private as java.security.interfaces.RSAPrivateCrtKey
        return PrivateKey.rsa(
            private.modulus.toByteArray(), private.publicExponent.toByteArray(), private.privateExponent.toByteArray(),
            private.crtCoefficient.toByteArray(), private.primeP.toByteArray(), private.primeQ.toByteArray(), "rsa",
        )
    }

    @Test
    fun aResidentKeyHasNoPrivateHalfOutsideTheKeystoreAndRefusesWithoutThePerson() = runBlocking<Unit> {
        val backend = backend()
        if (!backend.curve25519) {
            // Android 13 without KeyMint 2 — the API 33 emulator. The probe said no, and the Keystore
            // agrees: the import is refused, so an Ed25519 key is sealed by a chip key here instead.
            canary().use { key ->
                assertIs<ChipResult.Failed>(backend.store(alias, KeyStorage.Tee, key, ByteArray(0), UsePolicy.EveryUse, reason(key.publicKey)))
                if (1 in backend.device.levels) assertIs<KeyStorage.SealedByChip>(backend.placement(key.publicKey, generated = false))
            }
            assertFalse(backend.exists(alias))
            return@runBlocking
        }
        canary().use { key ->
            val stored = backend.store(alias, KeyStorage.Tee, key, ByteArray(0), UsePolicy.EveryUse, reason(key.publicKey))
            assertEquals(ChipResult.Ok(null), stored)
        }
        for (suffix in listOf(".sign", ".agree")) {
            val private = keystore.getKey(alias + suffix, null) as java.security.PrivateKey
            // I2: a handle, not the key.
            assertEquals(null, private.encoded, suffix)
        }
        val signing = keystore.getKey("$alias.sign", null) as java.security.PrivateKey
        val info = runCatching { KeyFactory.getInstance("EC", "AndroidKeyStore").getKeySpec(signing, KeyInfo::class.java) }.getOrNull()
        info?.let {
            assertTrue(it.isUserAuthenticationRequired, "bound to the person")
            assertFalse(it.isInvalidatedByBiometricEnrollment, "D144")
        }
        // I7: without the prompt the Keystore refuses — at sign(), as step 0 found (P7).
        val signature = Signature.getInstance("Ed25519").apply { initSign(signing) }
        signature.update(byteArrayOf(1, 2, 3))
        assertTrue(runCatching { signature.sign() }.isFailure, "signed without anyone")
        // …and the X25519 twin, whose confirmation is a window rather than a CryptoObject (impl, 01 §5.1).
        val agreeing = keystore.getKey("$alias.agree", null) as java.security.PrivateKey
        val peer = KeyFactory.getInstance("XDH").generatePublic(X509EncodedKeySpec(X25519_SPKI + ByteArray(32).also { it[0] = 9 }))
        // Setting the PIN above counts as a confirmation to the Keystore, as unlocking the phone does:
        // the window has to run out before the refusal can be seen.
        Thread.sleep(maxOf(0L, lockedAt + 7_000 - System.currentTimeMillis()))
        val agreed = runCatching { KeyAgreement.getInstance("XDH", "AndroidKeyStore").apply { init(agreeing); doPhase(peer, true) }.generateSecret() }
        assertTrue(agreed.isFailure, "agreed without anyone")
        assertTrue(backend.exists(alias))
        backend.delete(alias)
        assertFalse(backend.exists(alias), "I11: no alias left")
        assertTrue(keystore.aliases().toList().none { it.startsWith(alias) })
    }

    @Test
    fun rsaAndEcdsaImportUnderTheirOwnAliasesAndTheRawRsaIsForDecryptionOnly() = runBlocking<Unit> {
        val backend = backend()
        val dir = File(context.cacheDir, "keys").apply { mkdirs() }
        rsa().use { assertEquals(ChipResult.Ok(null), backend.store(alias, KeyStorage.Tee, it, ByteArray(0), UsePolicy.EveryUse, reason(it.publicKey))) }
        assertTrue(keystore.containsAlias("$alias.sign"))
        assertTrue(keystore.containsAlias("$alias.decrypt"))
        val decrypt = keystore.getKey("$alias.decrypt", null) as java.security.PrivateKey
        // 01 §5.1 as the Keystore recorded it: STRONG biometrics or the lock, for this one operation.
        for (suffix in listOf(".sign", ".decrypt")) {
            val info = KeyFactory.getInstance("RSA", "AndroidKeyStore").getKeySpec(keystore.getKey(alias + suffix, null), KeyInfo::class.java)
            assertTrue(info.isUserAuthenticationRequired, suffix)
            assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL, info.userAuthenticationType, suffix)
            assertEquals(0, info.userAuthenticationValidityDurationSeconds, suffix)
            assertFalse(info.isInvalidatedByBiometricEnrollment, "$suffix: D144")
        }
        assertTrue(runCatching { Signature.getInstance("SHA256withRSA").apply { initSign(decrypt) } }.isFailure, "the raw key cannot sign (D144)")
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, decrypt) }
        assertTrue(runCatching { cipher.doFinal(ByteArray(256) { 1 }) }.isFailure, "and cannot decrypt without the person")
        dir.deleteRecursively()
    }

    @Test
    fun theKeyringOnThisDeviceKeepsTheCanaryOffDiskAndOutOfTheHeap() = runBlocking<Unit> {
        val records = PrefsRecordStore(context)
        records.clear()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val backend = KeystoreBackend(context, { null }, nobody)
        val host = SshKeyringHost.withChip(records, NoPrompter, backend, Clock { System.currentTimeMillis() }, Platform.Android, scope)
        val pass = "canary passphrase for the heap"
        val file = canary().use { k -> SecretBytes.utf8(pass.toCharArray()).use { OpenSshKeyFile.write(k, it, rounds = 4) } }
        // The keyring's own path, decrypting the file, at the one level that needs nobody here: since
        // D247 a key in the chip needs a finger or its passphrase, and neither answers in a test.
        val outcome = host.admin.import(file, pass.toCharArray(), ImportOptions(level = 3))
        assertTrue(outcome is ImportOutcome.Imported, "$outcome")
        // …and the seed's way into the Keystore itself, below the keyring's rule.
        if (backend.curve25519) {
            canary().use { assertEquals(ChipResult.Ok(null), backend.store(alias, KeyStorage.Tee, it, ByteArray(0), UsePolicy.EveryUse, reason(it.publicKey))) }
        }

        // I1: every file of the app, every encoding of the seed: nothing.
        val found = mutableListOf<String>()
        context.dataDir.walkTopDown().filter { it.isFile && it.length() < 20_000_000 }.forEach { f ->
            LeakScanner.scan(f.readBytes(), listOf(Canary.ED25519_SEED)).forEach { found += "${f.name}: ${it.encoding}" }
        }
        assertEquals(emptyList(), found)

        // I12: after collection, the heap holds no copy of the seed.
        repeat(3) { Runtime.getRuntime().gc(); Thread.sleep(200) }
        val dump = File(context.cacheDir, "heap.hprof")
        Debug.dumpHprofData(dump.path)
        try {
            val leaks = mutableListOf<String>()
            dump.inputStream().buffered().use { input ->
                val window = ByteArray(8 * 1024 * 1024)
                val overlap = 256
                var carried = 0
                var offset = 0L
                while (true) {
                    val n = input.read(window, carried, window.size - carried)
                    if (n <= 0) break
                    val filled = carried + n
                    LeakScanner.scan(window.copyOf(filled), listOf(Canary.ED25519_SEED)).forEach { leaks += "${it.encoding}@${offset + it.offset}" }
                    carried = minOf(overlap, filled)
                    window.copyInto(window, 0, filled - carried, filled)
                    offset += filled - carried
                }
            }
            assertEquals(emptyList(), leaks.distinct())
        } finally {
            dump.delete()
            (outcome as? ImportOutcome.Imported)?.let { host.admin.delete(it.key.id) }
            backend.delete(alias)
            records.clear()
        }
    }

    @Test
    fun aKeyThePersonKeepsUnderItsPassphraseLeavesTheKeystoreAlone() = runBlocking<Unit> {
        // 09, D237: "Under the key's passphrase only" on a phone with a chip is level 3, and only that.
        val records = PrefsRecordStore(context)
        records.clear()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = SshKeyringHost.withChip(records, NoPrompter, KeystoreBackend(context, { null }, nobody), Clock { System.currentTimeMillis() }, Platform.Android, scope)
        val before = keystore.aliases().toList().toSet()
        val pass = "canary passphrase for level three"
        val file = canary().use { k -> SecretBytes.utf8(pass.toCharArray()).use { OpenSshKeyFile.write(k, it, rounds = 4) } }
        val key = assertIs<ImportOutcome.Imported>(host.admin.import(file, pass.toCharArray(), ImportOptions(level = 3))).key
        try {
            assertEquals(KeyStorage.SealedByPassphrase, key.storage)
            assertEquals(before, keystore.aliases().toList().toSet(), "nothing went into the Keystore")
            val found = mutableListOf<String>()
            context.dataDir.walkTopDown().filter { it.isFile && it.length() < 20_000_000 }.forEach { f ->
                LeakScanner.scan(f.readBytes(), listOf(Canary.ED25519_SEED)).forEach { found += "${f.name}: ${it.encoding}" }
            }
            assertEquals(emptyList(), found, "I1: the file is under its passphrase")
        } finally {
            host.admin.delete(key.id)
            records.clear()
        }
    }

    @Test
    fun aKeyOnlyAFingerprintOpensIsBoundToItAloneOrNotMadeAtAll() = runBlocking<Unit> {
        // 09, D245: the screen lock must not open it, and a new fingerprint must end it.
        val backend = backend()
        if (!backend.device.strongBiometric) {
            // No fingerprint here (the emulator, unless one was enrolled): the keyring refuses before the Keystore is touched.
            val records = PrefsRecordStore(context)
            records.clear()
            val host = SshKeyringHost.withChip(records, NoPrompter, backend, Clock { System.currentTimeMillis() }, Platform.Android, CoroutineScope(SupervisorJob() + Dispatchers.Default))
            val before = keystore.aliases().toList().toSet()
            val file = rsa().use { k -> SecretBytes.utf8("rsa passphrase, long enough".toCharArray()).use { OpenSshKeyFile.write(k, it, rounds = 4) } }
            assertEquals(ImportOutcome.Failed("biometric.unavailable"), host.admin.import(file, "rsa passphrase, long enough".toCharArray(), ImportOptions(biometricOnly = true)))
            assertEquals(before, keystore.aliases().toList().toSet())
            records.clear()
            return@runBlocking
        }
        rsa().use { assertEquals(ChipResult.Ok(null), backend.store(alias, KeyStorage.Tee, it, ByteArray(0), UsePolicy.EveryUse, reason(it.publicKey, biometricOnly = true))) }
        for (suffix in listOf(".sign", ".decrypt")) {
            val info = KeyFactory.getInstance("RSA", "AndroidKeyStore").getKeySpec(keystore.getKey(alias + suffix, null), KeyInfo::class.java)
            assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, info.userAuthenticationType, "$suffix: the finger, not the lock")
            assertTrue(info.isInvalidatedByBiometricEnrollment, "$suffix: a new finger ends it")
        }
        backend.delete(alias)
    }

    @Test
    fun removingTheScreenLockTakesTheKeyAndTheRecordStaysUnusable() = runBlocking<Unit> {
        if (!setPin) return@runBlocking // a person's own lock is not ours to remove
        val backend = backend()
        // Any key the chip holds itself; Ed25519 only where the Keystore takes Curve25519.
        (if (backend.curve25519) canary() else rsa()).use { assertEquals(ChipResult.Ok(null), backend.store(alias, KeyStorage.Tee, it, ByteArray(0), UsePolicy.EveryUse, reason(it.publicKey))) }
        assertTrue(backend.exists(alias))
        shell("locksettings clear --old 1111")
        setPin = false
        assertFalse(backend.exists(alias), "the Keystore dropped it with the lock (P7)")
        shell("locksettings set-pin 1111")
        lockedAt = System.currentTimeMillis()
        setPin = true
    }

    @Test
    fun withoutAScreenLockNothingIsTakenIn() = runBlocking<Unit> {
        if (!setPin) return@runBlocking
        shell("locksettings clear --old 1111")
        setPin = false
        val backend = backend()
        canary().use { assertEquals(ChipResult.NoScreenLock, backend.store(alias, KeyStorage.Tee, it, ByteArray(0), UsePolicy.EveryUse, reason(it.publicKey))) }
        assertFalse(backend.exists(alias))
    }

    private companion object {
        val X25519_SPKI = "302a300506032b656e032100".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private object NoPrompter : KeyPrompter {
        override suspend fun choose(app: cx.m42.superizer.app.AppId, purpose: cx.m42.superizer.ssh.KeyPurpose, candidates: List<SshKeyInfo>): KeyId? = null
        override suspend fun grant(app: cx.m42.superizer.app.AppId, key: SshKeyInfo, purpose: cx.m42.superizer.ssh.KeyPurpose) = false
        override suspend fun passphrase(reason: UseReason, wrong: Int): CharArray? = null
        override suspend fun confirm(reason: UseReason) = true
    }
}
