package cx.m42.services.backup

import cx.m42.services.ssh.Age
import cx.m42.services.ssh.SshPublicKey
import cx.m42.superizer.backup.HostBackupCipher
import cx.m42.superizer.ssh.SshOutcome

/**
 * The backup's cipher (05 §3.1): `age -R` to the keys a GitHub account publishes, armored so the
 * file travels as text through any share sheet, and opened by the person's keyring — which asks,
 * as it does for every decryption (D148).
 *
 * [open] is the keyring's door for the host itself (`SshKeyringHost.openForHost`); a test hands in
 * its own. The bundle's plaintext is never kept here.
 */
public class AgeBackupCipher(private val open: suspend (ByteArray) -> SshOutcome<ByteArray>) : HostBackupCipher {

    /** Throws [IllegalArgumentException] when no line is a key age can encrypt to — the library's word for "no recipients". */
    override suspend fun encrypt(bundle: ByteArray, recipients: List<String>): ByteArray {
        val keys = recipients.mapNotNull { SshPublicKey.parse(it.trim()) }.filter { Age.supports(it) }
        require(keys.isNotEmpty()) { "no key age can encrypt to" }
        return Age.armor(Age.encrypt(bundle, keys)).encodeToByteArray()
    }

    override suspend fun decrypt(file: ByteArray): SshOutcome<ByteArray> = open(file)

    public companion object {
        /** `unitool-backup-2026-09-26.age`: the date the person will look for, the extension `age -d` expects. */
        public fun fileName(isoDate: String): String = "unitool-backup-$isoDate.age"

        /** What the share sheet is told: armored age is text, but nothing should open it as text. */
        public const val MIME_TYPE: String = "application/octet-stream"
    }
}
