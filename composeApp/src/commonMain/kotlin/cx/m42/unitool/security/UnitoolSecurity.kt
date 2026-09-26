package cx.m42.unitool.security

import cx.m42.services.ssh.SshKeyringHost
import cx.m42.services.ssh.diagnostics.PlatformSelfTest
import cx.m42.superizer.Superizer
import cx.m42.superizer.diagnostics.DiagnosticsSource
import cx.m42.superizer.host.SuperizerBuilder
import cx.m42.superizer.host.secrets.HostSecrets
import cx.m42.superizer.host.storage.HostKeys
import cx.m42.superizer.host.storage.SafePrefs
import cx.m42.superizer.secrets.SecretVault
import cx.m42.superizer.ssh.KeyCapability
import cx.m42.superizer.ssh.SshKeyring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What a platform brings for secrets and keys, and how Unitool hands it to the host (ssh-new 07
 * §2.2): the keyring for apps (caller-bound, D146), the vault that seals every app's
 * `runtime.secrets` (05 §3.2), and the diagnostics that say how (06).
 *
 * On a phone the vault is the chip's (the Keystore, the Secure Enclave). Where there is none —
 * desktop, the web — it is the person's own SSH key: nothing until they add one, then every secret
 * sealed to it without a word and opened with its passphrase once a session (D164, D176).
 */
public class UnitoolSecurity(
    public val keyring: SshKeyringHost,
    public val prompts: KeyPrompts,
    /** The chip's vault; null where the vault is an SSH key. */
    public val chipVault: SecretVault? = null,
    public val chipVaultDiagnostics: DiagnosticsSource? = null,
    public val platformSelfTest: PlatformSelfTest? = null,
) {
    private var superizer: Superizer? = null

    /** Registers everything with the host being built. [hostVersion] stamps the self-test. */
    public fun install(builder: SuperizerBuilder, hostVersion: String) {
        builder.service(SshKeyring.Key) { caller -> keyring.forApp(caller) }
        builder.secretVault(chipVault ?: keyring.secretsKey.value?.let { keyring.ageVault(it, ::hostSecretValues) })
        // One release more (04 §9, Б4): an app that sealed with the vault itself moves its data into
        // runtime.secrets, and needs the vault it sealed with to open it.
        chipVault?.let { builder.service(SecretVault.Key, it) }
        builder.diagnosticsSource(keyring.diagnostics)
        chipVaultDiagnostics?.let { builder.diagnosticsSource(it) }
        builder.selfTest(keyring.selfTest(hostVersion, platformSelfTest) { superizer?.storage?.exportText().orEmpty() })
        builder.backupKeys { keyring.keyNames() }
    }

    /**
     * After the host is built: where there is no chip, the first key that can hold secrets becomes
     * their key, and whatever was stored plain is sealed to it at once, without asking (I16).
     */
    public fun attach(superizer: Superizer, scope: CoroutineScope) {
        this.superizer = superizer
        if (chipVault != null) return
        scope.launch {
            keyring.admin.keys.first { keys -> keys.any { it.usable && KeyCapability.DecryptAge in it.can } }
            if (keyring.secretsKey.value != null && superizer.secrets.vault.value != null) return@launch
            val key = keyring.admin.keys.value.first { it.usable && KeyCapability.DecryptAge in it.can }
            keyring.useForSecrets(key.id)
            superizer.secrets.reseal(keyring.ageVault(key.id, ::hostSecretValues))
        }
    }
}

/** Every value the host holds sealed — so one passphrase opens them all (D176). */
internal fun hostSecretValues(): Collection<String> =
    SafePrefs.keys().filter { it.startsWith(HostSecrets.SECRET_PREFIX) || it == HostKeys.HEARTBEAT }.mapNotNull { SafePrefs.get(it) }
