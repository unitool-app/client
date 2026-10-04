package cx.m42.services.ssh.keyring

import cx.m42.superizer.ssh.Grant
import cx.m42.superizer.ssh.KeyOrigin
import cx.m42.superizer.ssh.KeyStorage
import cx.m42.superizer.ssh.UsePolicy
import cx.m42.superizer.ssh.UseRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The keyring's own file (03 §2, D159) — never the host's store: public halves, the ciphertext of a
 * sealed key, grants and a journal without data. Excluded from every backup by name (I10), erased
 * whole when the last key goes.
 *
 * Android: its own prefs file `cx.m42.services.ssh`. iOS: a file in Application Support with
 * `NSFileProtectionComplete` and `isExcludedFromBackup`. Desktop: a file next to the host's. Web:
 * `localStorage`, and the words say "in the browser".
 */
public interface RecordStore {
    public fun load(): String?

    public fun save(text: String)

    public fun clear()

    /** Where it is, for the diagnostics' `location` fact — a path or a name, never contents. */
    public val location: String
}

/** For tests and for a host that keeps nothing: gone with the process. */
public class InMemoryRecordStore(initial: String? = null) : RecordStore {
    public var text: String? = initial
        private set

    override fun load(): String? = text

    override fun save(text: String) {
        this.text = text
    }

    override fun clear() {
        text = null
    }

    override val location: String get() = "memory"
}

@Serializable
internal data class RecordsFile(
    val v: Int = 1,
    val keys: List<KeyRecord> = emptyList(),
    val grants: List<Grant> = emptyList(),
    val journal: List<UseRecord> = emptyList(),
    /** The key the host's secrets are sealed to, where there is no chip (05 §3, D173). */
    val secretsKey: String? = null,
)

@Serializable
internal data class KeyRecord(
    val id: String,
    /** `type base64`, no comment. */
    val publicKey: String,
    val comment: String,
    val storage: KeyStorage,
    val policy: UsePolicy,
    val origin: KeyOrigin,
    val createdAt: Long,
    /** Levels 1–2: the chip's name for the key or its wrapping key. */
    val alias: String? = null,
    /** Level 2: the key file sealed by the chip, `v1:` or `e1:` and Base64. */
    val sealed: String? = null,
    /** Level 3: the `openssh-key-v1` file under its passphrase, Base64 — ciphertext, as the person gave it or as rekey wrote it. */
    val file: String? = null,
    /** Level 2 with the file's own passphrase kept as a second lock (01 §5.4). */
    val keepPassphrase: Boolean = false,
    /** Levels 1–2: only a strong biometric opens it, not the screen lock (09, D245). */
    val biometricOnly: Boolean = false,
    val usable: Boolean = true,
)

internal val RecordsJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
