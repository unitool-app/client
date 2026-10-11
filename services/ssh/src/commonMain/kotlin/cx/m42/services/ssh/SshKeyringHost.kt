package cx.m42.services.ssh

import cx.m42.services.ssh.agent.AgentServer
import cx.m42.services.ssh.agent.HostAgent
import cx.m42.services.ssh.diagnostics.KeyFindings
import cx.m42.services.ssh.diagnostics.SelfTest
import cx.m42.services.ssh.keyring.ChipBackend
import cx.m42.services.ssh.keyring.KeyPrompter
import cx.m42.services.ssh.keyring.Keyring
import cx.m42.services.ssh.keyring.RecordStore
import cx.m42.services.ssh.vault.AgeVault
import cx.m42.superizer.app.AppId
import cx.m42.superizer.backup.BackupKeyName
import cx.m42.superizer.diagnostics.DiagnosticsCode
import cx.m42.superizer.diagnostics.DiagnosticsSource
import cx.m42.superizer.diagnostics.Finding
import cx.m42.superizer.diagnostics.Inspected
import cx.m42.superizer.diagnostics.Subject
import cx.m42.superizer.diagnostics.finding
import cx.m42.superizer.runtime.Clock
import cx.m42.superizer.runtime.Logger
import cx.m42.superizer.runtime.Platform
import cx.m42.superizer.ssh.KeyId
import cx.m42.superizer.ssh.KeyOperation
import cx.m42.superizer.ssh.SshAgent
import cx.m42.superizer.ssh.SshKeyAdmin
import cx.m42.superizer.ssh.SshKeyring
import cx.m42.superizer.ssh.SshOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * The keyring as Unitool wires it (07 §2.2): one object per host, handing out the three doors of
 * the contract — [admin] to the host's screens, [forApp] to the service registry (D146), [agent] to
 * nobody in v1 — plus what only the host itself uses: the vault on an SSH key ([ageVault]), the
 * backup's decryption ([decryptForHost]) and the diagnostics.
 */
public class SshKeyringHost internal constructor(
    internal val keyring: Keyring,
    private val clock: Clock,
    private val platform: Platform,
    /** The web says "in the browser" (05 §2.3). */
    private val inBrowser: Boolean,
    private val records: RecordStore,
) {

    public val admin: SshKeyAdmin get() = keyring.admin

    public fun forApp(caller: AppId): SshKeyring = keyring.forApp(caller)

    /** Other devices' public keys (.ai-notes/public-keys): this to the host's screens, [PublicKeyBookAdmin.readOnly] to the registry (D512). */
    public val publicKeys: PublicKeyBookAdmin get() = keyring.publicKeys

    public val agent: SshAgent by lazy { HostAgent(keyring, clock) }

    /** The agent protocol's bytes over [agent], for a transport the caller brings (a test's socket). */
    public suspend fun agentMessage(message: ByteArray): ByteArray = AgentServer(agent).handle(message)

    /** The key the host's secrets are sealed to where there is no chip (D173); null before there is one. */
    public val secretsKey: StateFlow<KeyId?> get() = keyring.secretsKey

    public suspend fun useForSecrets(id: KeyId?) {
        keyring.setSecretsKey(id)
    }

    private val vaults = mutableListOf<AgeVault>()

    /** A vault sealing to [id] (05 §3). [sealedValues]: every value the host holds, so one prompt opens them all. */
    public fun ageVault(id: KeyId, sealedValues: () -> Collection<String>): AgeVault =
        AgeVault(keyring, id, sealedValues).also { vaults += it }

    init {
        // lockAll forgets the sessions and, with them, every secret a vault opened (D176).
        keyring.onLockAll = { vaults.forEach { it.lock() } }
    }

    /** Opens a file encrypted to one of the person's keys, for the host itself: the backup (05 §3.4). Null: declined or no key. */
    public suspend fun decryptForHost(file: ByteArray): ByteArray? =
        keyring.decryptForHost(listOf(file), KeyOperation.Decrypt)?.firstOrNull()

    /**
     * [decryptForHost] with the reason kept (05 §3.4): the backup needs "declined" and "no key of
     * yours opens this" told apart, because only the second has something to do about it.
     */
    public suspend fun openForHost(file: ByteArray): SshOutcome<ByteArray> {
        val opened = keyring.decryptForHost(listOf(file), KeyOperation.Decrypt) ?: return SshOutcome.Cancelled
        return opened.firstOrNull()?.let { SshOutcome.Ok(it) } ?: SshOutcome.NoKey
    }

    /** The names of the keys, for the backup's hint (D170). */
    public fun keyNames(): List<BackupKeyName> =
        keyring.keys.value.map { BackupKeyName(it.id.fingerprint, it.comment, it.storage.level) }

    /** The self-test, for `SuperizerBuilder.selfTest` (06 §3). */
    public fun selfTest(hostVersion: String, platformSteps: cx.m42.services.ssh.diagnostics.PlatformSelfTest?, report: () -> String): SelfTest =
        SelfTest(clock, hostVersion, platformSteps, report).also { selfTest = it }

    private var selfTest: SelfTest? = null

    /** Findings about the keys, the device as the keyring sees it, and the vault on an SSH key (06 §6.1). */
    public val diagnostics: DiagnosticsSource = DiagnosticsSource { inspect() }

    private fun inspect(): Inspected {
        val findings = mutableListOf<Finding>()
        val device = keyring.device
        val anchor = keyring.secretsKey.value
        keyring.keys.value.forEach { key -> findings += KeyFindings.of(key, anchor, inBrowser, chipHere = device.levels.any { it < 3 }) }
        if (device.levels.any { it < 3 } && !device.screenLockSet) {
            findings += DiagnosticsCode.DeviceNoScreenLock.finding(Subject.Device)
        }
        val vault = vaults.lastOrNull { it.keyId == anchor }
        if (vault != null && anchor != null) {
            val comment = keyring.keys.value.firstOrNull { it.id == anchor }?.comment.orEmpty()
            findings += if (inBrowser) {
                DiagnosticsCode.VaultPassphraseBrowser.finding(Subject.HostSecrets, mapOf("comment" to comment))
            } else {
                DiagnosticsCode.VaultPassphraseOk.finding(Subject.HostSecrets, mapOf("comment" to comment))
            }
            if (vault.sessionOpen) findings += DiagnosticsCode.VaultSessionOpen.finding(Subject.HostSecrets)
        }
        selfTest?.lastPassphraseMillis?.let { millis ->
            if (millis > SLOW_MILLIS) {
                findings += DiagnosticsCode.PassphraseSlow.finding(Subject.Device, mapOf("seconds" to ((millis + 500) / 1000).toString()))
            }
        }
        val facts = linkedMapOf(
            "keyring.platform" to platform.name,
            "keyring.levels" to device.levels.sorted().joinToString(","),
            "keyring.records" to records.location,
            "keyring.keys" to keyring.keys.value.size.toString(),
            "keyring.known" to keyring.publicKeys.keys.value.size.toString(),
        )
        device.sdk?.let { facts["android.sdk"] = it.toString() }
        if (device.levels.any { it < 3 }) {
            facts["chip.strongbox"] = device.strongBox.toString()
            facts["chip.tee"] = device.tee.toString()
            facts["chip.secure-enclave"] = device.secureEnclave.toString()
            facts["chip.curve25519"] = device.curve25519InKeystore.toString()
            facts["screen-lock"] = device.screenLockSet.toString()
        }
        return Inspected(findings, facts)
    }

    public companion object {
        private const val SLOW_MILLIS = 3_000L

        /**
         * A keyring with no chip: every key at level 3, under its own passphrase — desktop, the web,
         * and a phone whose Keystore is software (05 §2.1).
         */
        public fun passphraseOnly(
            records: RecordStore,
            prompter: KeyPrompter,
            clock: Clock,
            platform: Platform,
            scope: CoroutineScope,
            logger: Logger? = null,
        ): SshKeyringHost = SshKeyringHost(
            Keyring(records, prompter, chip = null, clock = clock, platform = platform, scope = scope, logger = logger),
            clock, platform, inBrowser = platform == Platform.Web, records = records,
        )

        internal fun withChip(
            records: RecordStore,
            prompter: KeyPrompter,
            chip: ChipBackend?,
            clock: Clock,
            platform: Platform,
            scope: CoroutineScope,
            logger: Logger? = null,
        ): SshKeyringHost = SshKeyringHost(
            Keyring(records, prompter, chip, clock, platform, scope, logger),
            clock, platform, inBrowser = false, records = records,
        )
    }
}
