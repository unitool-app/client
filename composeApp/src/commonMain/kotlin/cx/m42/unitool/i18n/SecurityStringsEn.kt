package cx.m42.unitool.i18n

import cx.m42.services.backup.GitHubKeys
import cx.m42.services.ssh.keyring.UseReason
import cx.m42.superizer.ssh.GeneratedKind
import cx.m42.superizer.ssh.ImportRefusal
import cx.m42.superizer.ssh.KeyOperation
import cx.m42.superizer.ssh.KeyPurpose

internal object SecurityStringsEn : SecurityStrings {
    override val cancel = "Cancel"
    override val allow = "Allow"
    override val deny = "Don't allow"
    override val unlock = "Unlock"
    override val confirm = "Confirm"
    override val passphrase = "Passphrase"
    override val wrongPassphrase = "Wrong passphrase. Try again."

    override fun grantTitle(app: String, purpose: KeyPurpose) = when (purpose) {
        is KeyPurpose.Sign -> "Let “$app” sign with your SSH key?"
        KeyPurpose.Decrypt -> "Let “$app” decrypt files with your SSH key?"
    }

    override fun grantBody(key: String, purpose: KeyPurpose) = when (purpose) {
        is KeyPurpose.Sign -> when (purpose.namespace) {
            "git" -> "$key\nSignatures of git commits in your name. You will still confirm every signature."
            "file" -> "$key\nSignatures of files in your name. You will still confirm every signature."
            else -> "$key\nSignatures for “${purpose.namespace}”. You will still confirm every signature."
        }
        KeyPurpose.Decrypt -> "$key\nFiles encrypted to this key. You will still confirm every decryption."
    }

    override fun chooseTitle(app: String, purpose: KeyPurpose) = when (purpose) {
        is KeyPurpose.Sign -> "Which key may “$app” sign with?"
        KeyPurpose.Decrypt -> "Which key may “$app” decrypt with?"
    }

    override fun operation(reason: UseReason, app: String?): String {
        val who = app?.let { " · app “$it”" } ?: ""
        val what = when (reason.operation) {
            KeyOperation.Sign -> "Sign" + (reason.namespace?.let { " for “$it”" } ?: "")
            KeyOperation.Decrypt -> "Decrypt"
            KeyOperation.AgentSign -> "Sign for an SSH login"
            KeyOperation.Import -> "Add the key"
            KeyOperation.Generate -> "Create the key"
            KeyOperation.Delete -> "Delete the key"
            KeyOperation.Rekey -> "Change the passphrase"
            KeyOperation.Grant -> "Allow"
            KeyOperation.Revoke -> "Revoke"
        }
        return "$what with “${reason.key.comment}”$who" + (reason.digest?.let { " · $it" } ?: "")
    }

    override fun keyLine(label: String, comment: String, fingerprint: String) = "$comment · $label $fingerprint"

    override val sshKeys = "SSH keys"
    override val sshKeysHint = "Keys for signing and decrypting, kept in the chip where there is one and under their passphrase where there is not."
    override val noKeys = "No keys yet."
    override val manageKeys = "Manage keys"
    override val addKey = "Add a key"
    override val importFile = "Import from a file"
    override val paste = "Paste"
    override val pasteHint = "Pasted text passes through the clipboard and the keyboard, which may keep a copy. A file or a QR code of a key with a passphrase is better."
    override val scanQr = "Scan a QR code"
    override val createKey = "Create a key"
    override val createHint = "Made on this device and never anywhere else."
    override val keyFile = "Private key"
    override val keyFileHint = "The file without .pub, e.g. ~/.ssh/id_ed25519"
    override val wouldLive = "Where it will live"
    override val passphraseOfFile = "The key's passphrase"
    override fun characters(n: Int) = if (n == 1) "1 character" else "$n characters"
    override val shortPassphrase = "The passphrase is too short"
    override fun shortPassphraseBody(n: Int) =
        if (n == 0) "This key has no passphrase. A key is never kept without one: set a new passphrase of at least 12 characters."
        else "A passphrase of $n characters can be guessed from a copy of the file within hours. Set a new one of at least 12 characters."
    override val setPassphrase = "Set a passphrase"
    override val setPassphraseHint = "The key will be kept under this passphrase."
    override val newPassphrase = "New passphrase"
    override val repeatPassphrase = "The same again"
    override val passphrasesDiffer = "The two are not the same."
    override val onlyLengthCounted = "Only the length is counted: nobody here can honestly tell a strong passphrase from a guessable one."
    override val computerCopyNote = "The copy on your computer keeps its old passphrase; only this one changes."
    override val policy = "Ask to confirm"
    override val everyUse = "Every time the key is used"
    override fun session(minutes: Int) = "Once, then for $minutes minutes"
    override val keepPassphrase = "Also ask for the key's own passphrase every time"
    override val add = "Add"
    override val importing = "Adding…"

    override fun refusal(refusal: ImportRefusal) = when (refusal) {
        ImportRefusal.NotAKey -> "This is not an SSH private key."
        ImportRefusal.PublicKeyOnly -> "This is a public key (.pub). The file without .pub is needed."
        ImportRefusal.OldFormat -> "An old key format. Rewrite it in the new one on your computer — the passphrase can stay the same:"
        ImportRefusal.PuttyFormat -> "A PuTTY key. Convert it on your computer:"
        ImportRefusal.UnsupportedCipher -> "Encrypted with a cipher this app does not read. Re-encrypt it on your computer:"
        ImportRefusal.UnsupportedKdf -> "Encrypted in a way this app does not read."
        ImportRefusal.HardwareToken -> "This key lives on a hardware token; the file only points to it."
        ImportRefusal.Dsa -> "DSA keys were switched off in OpenSSH 7.0."
        ImportRefusal.RsaTooShort -> "This RSA key is too short. Make a new one:"
        ImportRefusal.UnsupportedType -> "This kind of key is not supported."
        ImportRefusal.Corrupted -> "The file is damaged or was edited by hand."
    }

    override fun refusalCommand(refusal: ImportRefusal) = when (refusal) {
        ImportRefusal.OldFormat -> "ssh-keygen -p -f ~/.ssh/id_rsa"
        ImportRefusal.PuttyFormat -> "puttygen key.ppk -O private-openssh-new -o id_key"
        ImportRefusal.UnsupportedCipher -> "ssh-keygen -p -Z aes256-ctr -f ~/.ssh/id_ed25519"
        ImportRefusal.RsaTooShort -> "ssh-keygen -t ed25519"
        else -> null
    }

    override val alreadyThere = "This key is already here."
    override val noScreenLock = "Set a screen lock first: keys here are bound to it."
    override val importFailed = "The key could not be added."
    override val publicKey = "Public key"
    override val copy = "Copy"
    override val copied = "Copied"
    override val share = "Share"
    override val grants = "Apps that may use it"
    override val noGrants = "No app may use it yet."
    override val revoke = "Revoke"
    override val journal = "Recent use"
    override val noJournal = "Not used yet."
    override val changePassphrase = "Change passphrase"
    override val useForSecrets = "Seal secrets with this key"
    override val usedForSecrets = "Secrets are sealed with this key."
    override val delete = "Delete"
    override val deleteAnchor = "Secrets are sealed with this key; seal them with another before deleting it."
    override val unusable = "This key can no longer be used here."
    override val lockKeys = "Lock keys now"
    override val storedAt = "Where it lives"
    override val rekeyFailed = "The passphrase was not changed."
    override fun atLeast(n: Int) = "at least $n"
    override val working = "Working…"
    override val create = "Create"
    override val back = "Back"
    override val continueLabel = "Continue"
    override fun keyKind(kind: GeneratedKind) = when (kind) {
        GeneratedKind.Ed25519 -> "Ed25519 — signs and decrypts"
        GeneratedKind.EcdsaP256 -> "ECDSA P-256 — signs only, made inside the chip"
    }
    override val keyName = "Name"
    override val qrHint = "On your computer, show the key file as a QR code:"
    override val qrWarning = "A QR code of a key without a passphrase is the key itself, on a screen."
    override val sessionHint = "In a session, anyone holding the unlocked device can use the key without being asked."
    override fun purposeName(purpose: KeyPurpose) = when (purpose) {
        is KeyPurpose.Sign -> "Sign for “${purpose.namespace}”"
        KeyPurpose.Decrypt -> "Decrypt files"
    }
    override fun operationName(operation: KeyOperation) = when (operation) {
        KeyOperation.Sign -> "sign"
        KeyOperation.Decrypt -> "decrypt"
        KeyOperation.AgentSign -> "SSH login"
        KeyOperation.Import -> "added"
        KeyOperation.Generate -> "created"
        KeyOperation.Delete -> "delete"
        KeyOperation.Rekey -> "passphrase changed"
        KeyOperation.Grant -> "allowed"
        KeyOperation.Revoke -> "revoked"
    }
    override fun outcomeName(outcome: String) = when {
        outcome == "ok" -> "done"
        outcome == "cancelled" -> "cancelled"
        outcome == "refused" -> "refused"
        outcome.startsWith("failed:") -> "failed (${outcome.removePrefix("failed:")})"
        else -> outcome
    }

    override val storageSecurity = "Storage & security"
    override val storageSecurityHint = "How secrets and keys are kept on this device, and a self-test that checks it."
    override val open = "Open"
    override val howItWorks = "How storage works here"
    override val runSelfTest = "Run self-test"
    override val runningSelfTest = "Running…"
    override val shareReport = "Share report"
    override val selfTest = "Self-test"
    override val selfTestNever = "Not run yet."
    override val facts = "Facts"
    override val findings = "Findings"
    override val notRunHere = "not on this platform"
    override val notRunNeedsConfirmation = "only from the Run self-test button: it asks you to confirm"
    override val notRunNoScreenLock = "this device has no screen lock"
    override val notRunAfterFailure = "skipped: the step above failed"
    override val subjectSecrets = "Secrets"
    override val subjectDevice = "This device"
    override val subjectApps = "Apps"
    override fun lastRun(at: String) = "Last run: $at"
    override val copyReport = "Copy report"

    override val backup = "Backup"
    override val backupHint = "Every app's data in one file, encrypted to your GitHub account's SSH keys. Keys are never in it."
    override val githubLogin = "GitHub login"
    override val findKeys = "Find keys"
    override val recipients = "Encrypted to"
    override val makeBackup = "Make a backup"
    override val backupReason = "Make a backup of all app data"
    override val restore = "Restore from a backup"
    override val restoreFile = "Choose the backup file"
    override val restoreReason = "Replace app data with the backup"
    override val restoreSettings = "Also the host's settings"
    override fun restoreDone(apps: Int) = if (apps == 1) "Restored 1 app." else "Restored $apps apps."
    override val keysToImport = "The old device had these keys. Import them again from your computer:"
    override val backupIncludes = "In the backup"
    override val backupExcludes = "Left out: SSH keys, and apps that asked to be."
    override val backupMade = "Backup made."
    override fun backupFailed(code: String) = "The backup could not be made ($code)."
    override fun githubProblem(problem: GitHubKeys.Problem) = when (problem) {
        GitHubKeys.Problem.NoSuchUser -> "There is no such GitHub account."
        GitHubKeys.Problem.NoKeys -> "This account has no public SSH keys."
        GitHubKeys.Problem.NoUsableKeys -> "None of this account's keys can be encrypted to: age takes Ed25519 and RSA keys of 2048 bits or more."
        GitHubKeys.Problem.RateLimited -> "GitHub is limiting requests from this address. Try again in an hour."
        GitHubKeys.Problem.Offline -> "No connection."
        GitHubKeys.Problem.Server -> "GitHub did not answer as expected. Try again later."
    }
    override val cannotEncryptTo = "age cannot encrypt to this kind of key"
    override val restoreHint = "A backup made on another device. The SSH key it was encrypted to has to be here first, under SSH keys; a backup opened on a computer (age -d … > backup.json) is read as it is."
    override val restoreButton = "Restore"
    override val cancelledText = "Nothing was done."
    override val secretsLocked = "A secret could not be opened, so no backup was made."
    override val backupUnreadable = "This is not a backup the keys here can open."
    override fun entries(n: Int) = if (n == 1) "1 entry" else "$n entries"
}
