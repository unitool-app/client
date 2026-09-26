package cx.m42.unitool

import androidx.compose.runtime.Composable
import cx.m42.apps.twofa.TwoFaApp
import cx.m42.services.ssh.SshKeyringHost
import cx.m42.services.ssh.keyring.InMemoryRecordStore
import cx.m42.superizer.Superizer
import cx.m42.superizer.app.AppConfigSpec
import cx.m42.superizer.app.AppIcon
import cx.m42.superizer.app.AppId
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.app.AppManifest
import cx.m42.superizer.app.AppMetadata
import cx.m42.superizer.app.SuperizerApp
import cx.m42.superizer.app.localized
import cx.m42.superizer.backup.BackupOutcome
import cx.m42.superizer.backup.BackupPolicy
import cx.m42.superizer.host.storage.PrefsStorage
import cx.m42.superizer.host.storage.SafePrefs
import cx.m42.superizer.runtime.InstanceRuntime
import cx.m42.superizer.runtime.Platform
import cx.m42.superizer.ssh.ImportOptions
import cx.m42.superizer.ssh.ImportOutcome
import cx.m42.superizer.testing.Canary
import cx.m42.superizer.testing.FakeClock
import cx.m42.superizer.testing.LeakScanner
import cx.m42.unitool.security.KeyPrompts
import cx.m42.unitool.security.UnitoolSecurity
import cx.m42.unitool.settings.HostTools
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import org.junit.Assume

/**
 * The backup end to end, on two desktops that share nothing but a key file (05 §3.4):
 *
 *  - **I17** — what the backup carries, opened with the real `age -d -i` on the computer, holds the
 *    2FA accounts and the key's *name*, and not the key: no seed, no key file, no passphrase.
 *  - **I18** — restored on a second host with the same key imported, every secret is sealed again
 *    by *that* host's vault, and an app that said `BackupPolicy.None` was never in the file.
 *
 * Skipped by name without `ssh-keygen` or `age` (03 D158).
 */
class BackupTest : UnitoolTest() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dir: File = Files.createTempDirectory("backup-test").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A desktop host whose sheet answers like the person: the passphrase when asked, yes to the rest. */
    private fun host(): Pair<Superizer, UnitoolSecurity> {
        val prompts = KeyPrompts()
        val security = UnitoolSecurity(SshKeyringHost.passphraseOnly(InMemoryRecordStore(), prompts, FakeClock(), Platform.Desktop, scope), prompts)
        scope.launch {
            prompts.current.collect { question ->
                when (question) {
                    is KeyPrompts.Question.Passphrase -> prompts.answer(PASSPHRASE.toCharArray())
                    is KeyPrompts.Question.Choose -> prompts.answer(question.candidates.first().id)
                    is KeyPrompts.Question.Grant, is KeyPrompts.Question.Confirm -> prompts.answer(true)
                    null -> Unit
                }
            }
        }
        val superizer = buildUnitool(scope, debug = true, security = security, tools = HostTools(), network = ratesNetwork()) {
            register(CacheApp())
        }
        return superizer to security
    }

    private fun importKey(security: UnitoolSecurity, file: File) = runBlocking {
        // No passphrase on the file: kept only under a new one (D167).
        val made = security.keyring.admin.import(file.readBytes(), null, ImportOptions(), PASSPHRASE.toCharArray())
        assertIs<ImportOutcome.Imported>(made)
    }

    @Test
    fun theBackupCarriesTheDataAndTheKeysNamesButNoKeyAndRestoresSealedByTheNextDevice() = runBlocking<Unit> {
        requireTools("ssh-keygen", "age")
        val key = File(dir, "id_ed25519")
        run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "laptop@test", "-f", key.path)
        val publicLine = File(dir, "id_ed25519.pub").readText().trim()
        val accounts = """[{"id":"a","issuer":"GitHub","name":"octocat","secret":"JBSWY3DPEHPK3PXP"}]"""

        // ------------------------------------------------ the old device
        PrefsStorage.useDirectory(File(dir, "old").apply { mkdirs() })
        val (old, oldSecurity) = host()
        importKey(oldSecurity, key)
        old.handler.appRuntime(TwoFaApp.ID)!!.secrets.set("accounts", accounts)
        old.handler.appRuntime(CacheApp.ID)!!.storage.set("rates", "cached-rates-marker")
        assertTrue(SafePrefs.get("secret.2fa.accounts")!!.startsWith("age1:"), "sealed on the old device")

        val made = old.backup.export(listOf(publicLine), "test")
        val file = assertIs<BackupOutcome.Ok<ByteArray>>(made).value
        val onDisk = File(dir, "backup.age").apply { writeBytes(file) }
        val bundle = run("age", "-d", "-i", key.path, onDisk.path)

        // I17: the data and the key's name — and nothing that is the key.
        assertTrue("JBSWY3DPEHPK3PXP" in bundle, "the accounts are in it, opened")
        assertTrue(oldSecurity.keyring.admin.keys.value.single().id.fingerprint in bundle, "the key's name, to import it again")
        assertTrue("cached-rates-marker" !in bundle, "BackupPolicy.None is not carried")
        val canaries = listOf(
            Canary.of("ed25519-seed", ed25519Seed(key)),
            Canary.of("passphrase", PASSPHRASE.encodeToByteArray()),
            Canary.of("key-file", key.readBytes()),
        )
        assertEquals(emptyList(), LeakScanner.scan(bundle, canaries), "no key in the backup (I17)")
        assertEquals(emptyList(), LeakScanner.scan(file, canaries), "nor in the file itself")
        assertTrue("PRIVATE KEY" !in bundle)

        // ------------------------------------------------ the new device, same key imported
        PrefsStorage.useDirectory(File(dir, "new").apply { mkdirs() })
        val (fresh, freshSecurity) = host()
        importKey(freshSecurity, key)
        val plan = assertIs<BackupOutcome.Ok<cx.m42.superizer.backup.RestorePlan>>(fresh.backup.read(file)).value
        assertEquals(setOf("2fa"), plan.apps.map { it.id }.toSet())
        val report = assertIs<BackupOutcome.Ok<cx.m42.superizer.backup.RestoreReport>>(
            fresh.backup.restore(plan, setOf("2fa"), settings = true, reason = "test"),
        ).value
        assertEquals(listOf("2fa"), report.restored)
        assertEquals(emptyList(), report.keysToImport, "the key is here already")

        // I18: sealed again, by the new device's vault, and readable by the app as before.
        val stored = SafePrefs.get("secret.2fa.accounts")!!
        assertTrue(stored.startsWith("age1:"), stored.take(12))
        assertTrue("JBSWY3DPEHPK3PXP" !in stored)
        assertEquals(accounts, fresh.handler.appRuntime(TwoFaApp.ID)!!.secrets.get("accounts"))
    }

    @Test
    fun aDeviceWithoutTheKeySaysWhichKeysToImport() = runBlocking<Unit> {
        requireTools("ssh-keygen")
        val key = File(dir, "id_ed25519")
        run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "laptop@test", "-f", key.path)
        PrefsStorage.useDirectory(File(dir, "old").apply { mkdirs() })
        val (old, oldSecurity) = host()
        importKey(oldSecurity, key)
        old.handler.appRuntime(TwoFaApp.ID)!!.secrets.set("accounts", "[]")
        val file = assertIs<BackupOutcome.Ok<ByteArray>>(old.backup.export(listOf(File(dir, "id_ed25519.pub").readText()), "test")).value

        // Opened on the computer and moved over as JSON (05 §3.4): read as it is, no key needed.
        requireTools("age")
        val onDisk = File(dir, "backup.age").apply { writeBytes(file) }
        val json = run("age", "-d", "-i", key.path, onDisk.path)
        PrefsStorage.useDirectory(File(dir, "new").apply { mkdirs() })
        val (fresh, _) = host()
        val plan = assertIs<BackupOutcome.Ok<cx.m42.superizer.backup.RestorePlan>>(fresh.backup.read(json.encodeToByteArray())).value
        val report = assertIs<BackupOutcome.Ok<cx.m42.superizer.backup.RestoreReport>>(fresh.backup.restore(plan, setOf("2fa"), false, "test")).value
        assertEquals(listOf("laptop@test"), report.keysToImport.map { it.comment })
    }

    /** An app whose data is a cache: `BackupPolicy.None`, which needs contract 3 (Б10). */
    class CacheApp : SuperizerApp<Unit>() {
        override val manifest: AppManifest = AppManifest(
            id = ID,
            version = "1.0.0",
            minHostContract = 3,
            metadata = AppMetadata(title = localized("en" to "Cache"), icon = AppIcon.Path("M0 0h1v1H0z"), category = "Test"),
            backup = BackupPolicy.None,
        )
        override val configSpec: AppConfigSpec<Unit> = AppConfigSpec(Unit.serializer(), Unit)

        override fun launch(runtime: InstanceRuntime, config: Unit): AppInstance = object : AppInstance() {
            @Composable
            override fun Content() = Unit
        }

        companion object {
            val ID = AppId("cache")
        }
    }

    // ------------------------------------------------------------------ tools

    /** The 32-byte seed of an unencrypted `openssh-key-v1` Ed25519 file — what must never be in a backup. */
    private fun ed25519Seed(file: File): ByteArray {
        val body = file.readLines().filterNot { it.startsWith("-----") }.joinToString("")
        val outer = Reader(Base64.decode(body), "openssh-key-v1\u0000".length)
        repeat(3) { outer.string() } // cipher, kdf, kdf options: "none", "none", empty
        outer.u32() // one key
        outer.string() // its public half
        val inner = Reader(outer.string(), 8) // after the two check words
        inner.string() // "ssh-ed25519"
        inner.string() // the public key again
        return inner.string().copyOf(32) // seed ‖ public
    }

    private class Reader(private val bytes: ByteArray, private var at: Int) {
        fun u32(): Int = (0 until 4).fold(0) { v, i -> (v shl 8) or (bytes[at + i].toInt() and 0xff) }.also { at += 4 }

        fun string(): ByteArray = u32().let { n -> bytes.copyOfRange(at, at + n).also { at += n } }
    }

    private fun requireTools(vararg tools: String) = tools.forEach { tool ->
        val has = runCatching { ProcessBuilder("sh", "-c", "command -v $tool").start().waitFor() == 0 }.getOrDefault(false)
        Assume.assumeTrue("SKIP interop: no $tool", has)
    }

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
        val out = process.inputStream.readAllBytes().decodeToString()
        val err = process.errorStream.readAllBytes().decodeToString()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "${command.toList()}: $err" }
        return out
    }

    private companion object {
        const val PASSPHRASE = "correct horse battery staple"
    }
}
