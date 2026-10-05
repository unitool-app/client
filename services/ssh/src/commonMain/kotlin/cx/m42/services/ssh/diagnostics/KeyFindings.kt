package cx.m42.services.ssh.diagnostics

import cx.m42.superizer.diagnostics.DiagnosticsCode
import cx.m42.superizer.diagnostics.Finding
import cx.m42.superizer.diagnostics.Subject
import cx.m42.superizer.diagnostics.finding
import cx.m42.superizer.ssh.Chip
import cx.m42.superizer.ssh.KeyId
import cx.m42.superizer.ssh.KeyStorage
import cx.m42.superizer.ssh.SshKeyInfo
import cx.m42.superizer.ssh.UsePolicy

/**
 * Where a key lives, as catalogue findings (06 §6.1, D179): the one translation from [KeyStorage] to
 * codes, used by the keyring's diagnostics and by every screen that shows one key — the list, the
 * card, and the import wizard's "where it will live" before any passphrase.
 */
public object KeyFindings {

    /**
     * Everything about [key]: its level (or that it is unusable), whether it seals the host's secrets,
     * its session. [chipHere]: this device has a chip, so level 3 is a choice, not a lack (09, D242).
     */
    public fun of(key: SshKeyInfo, anchor: KeyId?, inBrowser: Boolean, chipHere: Boolean = false): List<Finding> {
        val subject = Subject.Key(key.id.fingerprint)
        return buildList {
            add(if (key.usable) placement(key.storage, subject, inBrowser, chipHere) else DiagnosticsCode.KeyUnusable.finding(subject))
            if (key.usable && key.biometricOnly) add(DiagnosticsCode.KeyBiometricOnly.finding(subject))
            // From before D247: in the chip, and only the screen lock between it and whoever holds the phone.
            if (key.usable && key.storage.level < 3 && !key.biometricOnly && !key.passphraseKept) {
                add(DiagnosticsCode.KeyNoSecondLock.finding(subject))
            }
            if (key.id == anchor) add(DiagnosticsCode.KeySecretsAnchor.finding(subject))
            (key.policy as? UsePolicy.Session)?.let {
                add(DiagnosticsCode.KeySessionPolicy.finding(subject, mapOf("minutes" to ((it.seconds + 59) / 60).toString())))
            }
        }
    }

    /** The level [storage] is, as a finding about [subject]. */
    public fun placement(storage: KeyStorage, subject: Subject, inBrowser: Boolean, chipHere: Boolean = false): Finding = when (storage.level) {
        1 -> DiagnosticsCode.KeyLevel1.finding(subject, mapOf("hardware" to hardware(storage), "level" to "1"))
        2 -> DiagnosticsCode.KeyLevel2.finding(subject, mapOf("hardware" to hardware(storage), "level" to "2"))
        else -> when {
            inBrowser -> DiagnosticsCode.KeyLevel3Browser.finding(subject, mapOf("level" to "3"))
            chipHere -> DiagnosticsCode.KeyLevel3ChipAvailable.finding(subject, mapOf("level" to "3"))
            else -> DiagnosticsCode.KeyLevel3.finding(subject, mapOf("level" to "3"))
        }
    }

    /** The chip's name as the report and the words say it; "none" for level 3. */
    public fun hardware(storage: KeyStorage): String = when (storage) {
        KeyStorage.StrongBox -> "StrongBox"
        KeyStorage.Tee -> "TEE"
        KeyStorage.SecureEnclave -> "Secure Enclave"
        is KeyStorage.SealedByChip -> when (storage.under) {
            Chip.StrongBox -> "StrongBox"
            Chip.Tee -> "TEE"
            Chip.SecureEnclave -> "Secure Enclave"
        }
        KeyStorage.SealedByPassphrase -> "none"
    }
}
