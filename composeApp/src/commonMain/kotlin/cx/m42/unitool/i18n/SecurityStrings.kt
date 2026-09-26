package cx.m42.unitool.i18n

import cx.m42.services.ssh.keyring.UseReason
import cx.m42.superizer.ssh.ImportRefusal
import cx.m42.superizer.ssh.KeyOperation
import cx.m42.superizer.ssh.KeyPurpose

/**
 * The words of Unitool's own security screens and the keyring's sheet (07 §2.2): English first,
 * Russian as its translation, the same interface so neither can miss a line. How keys and secrets
 * are *protected* is not said here — that is [DiagnosticsWords], by catalogue code (D179).
 */
internal interface SecurityStrings {
    // The sheet
    val cancel: String
    val allow: String
    val deny: String
    val unlock: String
    val confirm: String
    val passphrase: String
    val wrongPassphrase: String
    fun grantTitle(app: String, purpose: KeyPurpose): String
    fun grantBody(key: String, purpose: KeyPurpose): String
    fun chooseTitle(app: String, purpose: KeyPurpose): String
    fun operation(reason: UseReason, app: String?): String
    fun keyLine(label: String, comment: String, fingerprint: String): String

    // Keys
    val sshKeys: String
    val sshKeysHint: String
    val noKeys: String
    val manageKeys: String
    val addKey: String
    val importFile: String
    val paste: String
    val pasteHint: String
    val scanQr: String
    val createKey: String
    val createHint: String
    val keyFile: String
    val keyFileHint: String
    val wouldLive: String
    val passphraseOfFile: String
    fun characters(n: Int): String
    val shortPassphrase: String
    fun shortPassphraseBody(n: Int): String
    val setPassphrase: String
    val setPassphraseHint: String
    val newPassphrase: String
    val repeatPassphrase: String
    val passphrasesDiffer: String
    val onlyLengthCounted: String
    val computerCopyNote: String
    val policy: String
    val everyUse: String
    fun session(minutes: Int): String
    val keepPassphrase: String
    val add: String
    val importing: String
    fun refusal(refusal: ImportRefusal): String
    fun refusalCommand(refusal: ImportRefusal): String?
    val alreadyThere: String
    val noScreenLock: String
    val importFailed: String
    val publicKey: String
    val copy: String
    val copied: String
    val share: String
    val grants: String
    val noGrants: String
    val revoke: String
    val journal: String
    val noJournal: String
    val changePassphrase: String
    val useForSecrets: String
    val usedForSecrets: String
    val delete: String
    val deleteAnchor: String
    val unusable: String

    // Storage & security
    val storageSecurity: String
    val storageSecurityHint: String
    val open: String
    val howItWorks: String
    val runSelfTest: String
    val runningSelfTest: String
    val shareReport: String
    val selfTest: String
    val selfTestNever: String
    val facts: String
    val findings: String
    val notRunHere: String

    // Backup
    val backup: String
    val backupHint: String
    val backupScreen: String
    val githubLogin: String
    val findKeys: String
    val recipients: String
    val makeBackup: String
    val backupReason: String
    val restore: String
    val restoreFile: String
    val restoreReason: String
    val restoreSettings: String
    fun restoreDone(apps: Int): String
    val keysToImport: String
    val backupIncludes: String
    val backupExcludes: String
    val backupMade: String
    fun backupFailed(code: String): String
}
