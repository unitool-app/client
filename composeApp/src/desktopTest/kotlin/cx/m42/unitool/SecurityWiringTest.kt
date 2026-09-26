package cx.m42.unitool

import cx.m42.apps.twofa.TwoFaApp
import cx.m42.services.ssh.SshKeyringHost
import cx.m42.services.ssh.keyring.InMemoryRecordStore
import cx.m42.superizer.Superizer
import cx.m42.superizer.diagnostics.DiagnosticsCode
import cx.m42.superizer.host.storage.PrefsStorage
import cx.m42.superizer.runtime.Platform
import cx.m42.superizer.secrets.SecretVault
import cx.m42.superizer.ssh.GeneratedKind
import cx.m42.superizer.ssh.ImportOptions
import cx.m42.superizer.ssh.ImportOutcome
import cx.m42.superizer.ssh.SshKeyring
import cx.m42.superizer.testing.FakeClock
import cx.m42.superizer.testing.FakeSecretVault
import cx.m42.unitool.security.KeyPrompts
import cx.m42.unitool.security.UnitoolSecurity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * Contract 3 as Unitool wires it (ssh-new 07 §2.2): every app gets its own keyring and no app gets
 * the agent or the admin (I9); a phone's vault seals every app's secrets and still opens what 2FA
 * sealed itself before (Б4); a desktop stores secrets plain until the person's first key, then
 * seals them all to it without asking (I16).
 */
class SecurityWiringTest : UnitoolTest() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun security(chipVault: SecretVault? = null): UnitoolSecurity {
        val prompts = KeyPrompts()
        val keyring = SshKeyringHost.passphraseOnly(InMemoryRecordStore(), prompts, FakeClock(), Platform.Desktop, scope)
        return UnitoolSecurity(keyring, prompts, chipVault = chipVault)
    }

    private fun host(security: UnitoolSecurity): Superizer = buildUnitool(scope, debug = true, security = security, network = ratesNetwork())

    private fun stored(): Map<String, String> = PrefsStorage.keys().associateWith { PrefsStorage.get(it).orEmpty() }

    @Test
    fun appsGetACallerBoundKeyringAndNoAppGetsTheAgentOrTheAdmin() {
        val superizer = host(security())
        val twofa = superizer.handler.appRuntime(TwoFaApp.ID)!!.service(SshKeyring.Key)
        val calculator = superizer.handler.appRuntime(cx.m42.superizer.app.AppId("calculator"))!!.service(SshKeyring.Key)
        assertNotNull(twofa)
        assertNotSame(twofa, calculator, "each app its own, so grants stay per app (D146)")
        val names = superizer.hostInfo.services.map { it.name }.toSet()
        assertTrue("ssh-keyring" in names)
        assertTrue(names.none { it.contains("agent") || it.contains("admin") }, "$names")
    }

    @Test
    fun onAPhoneTheChipSealsEverySecretAndOpensWhat2faSealedItselfBefore() = runBlocking<Unit> {
        val vault = FakeSecretVault()
        // A tester's phone before contract 3: 2FA sealed its list with the same vault, in its own storage.
        val list = """[{"id":"a","issuer":"GitHub","name":"octocat","secret":"JBSWY3DPEHPK3PXP"}]"""
        PrefsStorage.put("app.2fa.accounts.sealed", vault.seal(list))
        val superizer = host(security(chipVault = vault))
        assertTrue(superizer.handler.launch(TwoFaApp.ID).isSuccess)
        val secret = PrefsStorage.get("secret.2fa.accounts")
        assertNotNull(secret, "moved into the host's secrets")
        assertTrue(secret.startsWith("fake:"), "sealed by the chip's vault")
        assertEquals(list, vault.open(secret))
        assertEquals(null, PrefsStorage.get("app.2fa.accounts.sealed"), "the old copy is gone")
        assertTrue(stored().values.none { "JBSWY3DPEHPK3PXP" in it })
    }

    @Test
    fun onADesktopSecretsArePlainUntilTheFirstKeyThenSealedToItWithoutAsking() = runBlocking<Unit> {
        val security = security()
        val superizer = host(security)
        superizer.handler.appRuntime(TwoFaApp.ID)!!.secrets.set("accounts", "[plain until a key]")
        assertEquals("plain:[plain until a key]", PrefsStorage.get("secret.2fa.accounts"))
        val before = superizer.storage.inspect()
        assertTrue(before.findings.any { it.code == DiagnosticsCode.VaultMissing.id })
        assertTrue(before.findings.any { it.code == DiagnosticsCode.AppSecretsPlain.id })

        val made = security.keyring.admin.generate(GeneratedKind.Ed25519, ImportOptions(comment = "desk"), "correct horse battery staple".toCharArray())
        assertIs<ImportOutcome.Imported>(made)

        val sealed = PrefsStorage.get("secret.2fa.accounts")!!
        assertTrue(sealed.startsWith("age1:"), sealed)
        assertEquals(null, security.prompts.current.value, "sealing asked nothing (I16)")
        assertNotNull(security.keyring.secretsKey.value)
        val after = superizer.storage.inspect()
        assertTrue(after.findings.any { it.code == DiagnosticsCode.VaultPassphraseOk.id })
        assertTrue(after.findings.any { it.code == DiagnosticsCode.AppSecretsSealed.id })
        assertTrue(after.findings.none { it.code == DiagnosticsCode.VaultMissing.id })
    }
}
