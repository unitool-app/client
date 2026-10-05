package cx.m42.unitool

import cx.m42.services.ssh.SshKeyringHost
import cx.m42.services.ssh.keyring.InMemoryRecordStore
import cx.m42.services.vault.HardwareVault
import cx.m42.services.vault.VaultDiagnostics
import cx.m42.superizer.diagnostics.DiagnosticsCode
import cx.m42.superizer.diagnostics.DiagnosticsSource
import cx.m42.superizer.diagnostics.Inspected
import cx.m42.superizer.diagnostics.SelfTestCode
import cx.m42.superizer.diagnostics.SelfTestResult
import cx.m42.superizer.diagnostics.SelfTestStep
import cx.m42.superizer.diagnostics.Subject
import cx.m42.superizer.diagnostics.finding
import cx.m42.superizer.host.SuperizerBuilder
import cx.m42.superizer.runtime.Platform
import cx.m42.superizer.ssh.GeneratedKind
import cx.m42.superizer.ssh.ImportOptions
import cx.m42.superizer.ssh.SshKeyInfo
import cx.m42.superizer.testing.FakeClock
import cx.m42.superizer.testing.FakeSecretVault
import cx.m42.unitool.security.KeyPrompts
import cx.m42.unitool.security.UnitoolSecurity
import cx.m42.unitool.settings.BackupSection
import cx.m42.unitool.settings.DiagnosticsBanner
import cx.m42.unitool.settings.HostTools
import cx.m42.unitool.settings.SshKeysScreen
import cx.m42.unitool.settings.StorageSecuritySection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/**
 * What a screenshot is taken on (06 §9): a phone, whose secrets a chip seals and whose keys are
 * [FakeKeys] because a desktop has no chip to put one in; or a desktop, with the real keyring at
 * level 3 — the platform whose "not protected" the frames have to show honestly.
 */
internal sealed interface HostKind {
    fun security(scope: CoroutineScope): UnitoolSecurity?

    fun install(builder: SuperizerBuilder, tools: HostTools)

    fun prepare(security: UnitoolSecurity?)

    /** The vault in [hardware] ("software" is the Unsafe frame), and [lost] adds the Broken one. */
    data class Phone(
        val keys: List<SshKeyInfo> = emptyList(),
        val hardware: String = "strongbox",
        val lost: Boolean = false,
    ) : HostKind {
        override fun security(scope: CoroutineScope): UnitoolSecurity? = null

        override fun install(builder: SuperizerBuilder, tools: HostTools) {
            val fake = FakeKeys(keys)
            builder.secretVault(FakeSecretVault())
            builder.diagnosticsSource(VaultDiagnostics(object : HardwareVault { override fun hardware(): String = this@Phone.hardware }))
            if (lost) builder.diagnosticsSource(DiagnosticsSource { Inspected(listOf(DiagnosticsCode.VaultKeyLost.finding(Subject.HostSecrets))) })
            builder.hostScreen(SshKeysScreen(fake, tools))
            builder.hostSection(StorageSecuritySection(fake, tools))
            builder.hostSection(BackupSection(tools))
            builder.homeBanner(DiagnosticsBanner(fake, tools))
        }

        override fun prepare(security: UnitoolSecurity?) = Unit
    }

    /** No chip; [withKey] makes one first, which then seals every secret (I16). */
    data class Desktop(val withKey: Boolean = false) : HostKind {
        override fun security(scope: CoroutineScope): UnitoolSecurity {
            val prompts = KeyPrompts()
            return UnitoolSecurity(SshKeyringHost.passphraseOnly(InMemoryRecordStore(), prompts, FakeClock(), Platform.Desktop, scope), prompts)
        }

        override fun install(builder: SuperizerBuilder, tools: HostTools) = Unit

        override fun prepare(security: UnitoolSecurity?) {
            if (!withKey || security == null) return
            runBlocking {
                security.keyring.admin.generate(GeneratedKind.Ed25519, ImportOptions(comment = "laptop"), "correct horse battery staple".toCharArray())
            }
        }
    }
}

/** Every step the desktop runs, passed — the self-test as the storage frames show it. 2023-11-14. */
internal val PassedSelfTest: SelfTestResult = SelfTestResult(
    steps = SelfTestCode.entries.map { code ->
        val ran = code in setOf(SelfTestCode.PassphraseRoundtrip, SelfTestCode.AgeRoundtrip, SelfTestCode.MemoryZeroed, SelfTestCode.ReportClean)
        SelfTestStep(code.id, passed = true, millis = if (ran) 40 else 0, ran = ran)
    },
    at = 1_700_000_022_000,
    hostVersion = BuildInfo.VERSION,
)
