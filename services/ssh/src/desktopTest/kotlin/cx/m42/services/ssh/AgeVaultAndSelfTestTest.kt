package cx.m42.services.ssh

import cx.m42.services.ssh.keyring.InMemoryRecordStore
import cx.m42.superizer.diagnostics.DiagnosticsCode
import cx.m42.superizer.diagnostics.SelfTestCode
import cx.m42.superizer.runtime.Platform
import cx.m42.superizer.ssh.ImportOptions
import cx.m42.superizer.ssh.ImportOutcome
import cx.m42.superizer.ssh.SshOutcome
import cx.m42.superizer.testing.Canary
import cx.m42.superizer.testing.FakeClock
import cx.m42.superizer.testing.LeakScanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The host's vault on an SSH key (05 §3, D176, I16) and the self-test on the JVM (06 §9).
 */
class AgeVaultAndSelfTestTest {

    private fun host(scope: kotlinx.coroutines.CoroutineScope, prompter: ScriptedPrompter, platform: Platform = Platform.Desktop) =
        SshKeyringHost.passphraseOnly(InMemoryRecordStore(), prompter, FakeClock(), platform, scope)

    @Test
    fun sealingNeverAsksAndOpeningAsksOnceForEverySecretThenTheKeyIsGoneAgain() = runTest {
        val prompter = ScriptedPrompter()
        val host = host(backgroundScope, prompter)
        val key = assertIs<ImportOutcome.Imported>(host.admin.import(encryptedFile(GOOD), GOOD.toCharArray(), ImportOptions())).key
        host.useForSecrets(key.id)
        val stored = mutableListOf<String>()
        val vault = host.ageVault(key.id) { stored.toList() }

        val secret = Canary.PLAINTEXT.reveal().decodeToString()
        repeat(5) { stored += vault.seal("$secret #$it") }
        assertEquals(0, prompter.asked.size, "sealing is a public-key operation (I16)")
        assertTrue(stored.all { it.startsWith(cx.m42.services.ssh.vault.AgeVault.PREFIX) })
        assertEquals(emptyList(), LeakScanner.scan(stored.joinToString().encodeToByteArray(), listOf(Canary.PLAINTEXT)))

        // A fresh vault — a new session — opens all five with one passphrase.
        val next = host.ageVault(key.id) { stored.toList() }
        assertEquals(false, next.opensSilently)
        prompter.passphrases.add(GOOD)
        stored.forEachIndexed { i, s -> assertEquals("$secret #$i", next.open(s)) }
        assertEquals(1, prompter.count("passphrase"), "one prompt for the whole session")
        assertEquals(true, next.opensSilently)

        // D176: the vault kept the secrets, not the key — the keyring still asks for its own use.
        prompter.passphrases.add(GOOD)
        assertIs<SshOutcome.Ok<*>>(host.forApp(cx.m42.superizer.app.AppId("git")).sign(key.id, "git", "x".toByteArray()))
        assertEquals(2, prompter.count("passphrase"))

        // lockAll ends the session: the next open asks again; a declined prompt opens nothing.
        host.admin.lockAll()
        assertEquals(false, next.opensSilently)
        prompter.passphrases.add(null)
        assertNull(next.open(stored.first()))
    }

    @Test
    fun theKeyringSaysWhereItsKeysLiveAndTheBrowserSaysSo() = runTest {
        val prompter = ScriptedPrompter()
        for ((platform, code) in listOf(Platform.Desktop to DiagnosticsCode.KeyLevel3, Platform.Web to DiagnosticsCode.KeyLevel3Browser)) {
            val host = host(backgroundScope, prompter, platform)
            val key = assertIs<ImportOutcome.Imported>(host.admin.import(encryptedFile(GOOD), GOOD.toCharArray(), ImportOptions())).key
            host.useForSecrets(key.id)
            host.ageVault(key.id) { emptyList() }
            val findings = host.diagnostics.inspect().findings
            assertTrue(findings.any { it.code == code.id }, "$platform: $findings")
            assertTrue(findings.any { it.code == DiagnosticsCode.KeySecretsAnchor.id })
            val vaultCode = if (platform == Platform.Web) DiagnosticsCode.VaultPassphraseBrowser else DiagnosticsCode.VaultPassphraseOk
            assertTrue(findings.any { it.code == vaultCode.id }, "$platform vault: $findings")
        }
    }

    @Test
    fun theSelfTestPassesOnTheJvmAndSaysWhatItCouldNotTest() = runTest {
        val host = host(backgroundScope, ScriptedPrompter())
        val selfTest = host.selfTest("1.0.0", platformSteps = null) { "Storage & security report\n(nothing)" }
        val result = selfTest.run(withConfirmation = false)
        assertTrue(result.passed, result.steps.joinToString("\n"))
        val ran = result.steps.filter { it.ran }.map { it.code }.toSet()
        assertEquals(
            setOf(SelfTestCode.PassphraseRoundtrip.id, SelfTestCode.AgeRoundtrip.id, SelfTestCode.MemoryZeroed.id, SelfTestCode.ReportClean.id),
            ran,
        )
        assertTrue(result.steps.single { it.code == SelfTestCode.VaultRoundtrip.id }.let { !it.ran })
    }
}
