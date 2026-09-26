package cx.m42.services.vault

import cx.m42.superizer.diagnostics.DiagnosticsCode
import cx.m42.superizer.diagnostics.DiagnosticsSource
import cx.m42.superizer.diagnostics.Inspected
import cx.m42.superizer.diagnostics.Subject
import cx.m42.superizer.diagnostics.finding

/**
 * What a chip vault can say about itself (05 §3.4, 06 §6.1): where its key lives — `strongbox`,
 * `tee`, `secure-enclave` — or `software`, which is the finding a person must see, because the
 * listing says "secure hardware" and a software key is not that. Null before the key exists.
 */
public interface HardwareVault {
    public fun hardware(): String?
}

/** The vault's own finding: `vault.chip.ok` with where, or `vault.software-key`. */
public class VaultDiagnostics(private val vault: HardwareVault) : DiagnosticsSource {
    override suspend fun inspect(): Inspected {
        val hardware = vault.hardware() ?: return Inspected(emptyList(), mapOf("vault.key" to "not yet made"))
        val finding = if (hardware == "software") {
            DiagnosticsCode.VaultSoftwareKey.finding(Subject.HostSecrets, mapOf("hardware" to hardware))
        } else {
            DiagnosticsCode.VaultChipOk.finding(Subject.HostSecrets, mapOf("hardware" to label(hardware)))
        }
        return Inspected(listOf(finding), mapOf("vault.hardware" to hardware))
    }

    private fun label(hardware: String): String = when (hardware) {
        "strongbox" -> "StrongBox"
        "tee" -> "TEE"
        "secure-enclave" -> "Secure Enclave"
        else -> hardware
    }
}
