package cx.m42.apps.passwords

import cx.m42.superizer.runtime.StorageService
import cx.m42.superizer.secrets.SecretsUnavailableException
import cx.m42.unitool.kit.link.NoiseKeyPair
import kotlin.io.encoding.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The paired computers, sealed by the host like the entries (passwords-browser README §3). */
internal const val KEY_COMPUTERS = "computers"

/** This phone's static key for the link (Noise `P`), one for every computer (D563). */
internal const val KEY_LINK_KEY = "link.key"

/** How many lines of a computer's journal are kept (D564). */
internal const val JOURNAL_LINES = 20

/**
 * A computer that may ask for passwords (D537): its link key `C`, the SSH key the answers are sealed
 * to, the name it gave, and the id the phone announces itself under for it (01 §3).
 */
@Serializable
internal data class Computer(
    /** `C` in Base64: also what identifies the computer here. */
    val key: String,
    val name: String,
    /** The SSH public key's blob in Base64 — age seals every answer to it. */
    val ssh: String,
    /** This pairing's mDNS name: 8 random bytes in hex (01 §3). */
    val linkId: String,
    val pairedAt: Long,
    /** Newest first; time, site, outcome — never a login (D544). */
    val journal: List<JournalLine> = emptyList(),
) {
    val keyBytes: ByteArray get() = Base64.decode(key)
    val sshBlob: ByteArray get() = Base64.decode(ssh)
}

@Serializable
internal data class JournalLine(val at: Long, val site: String, val outcome: Outcome)

internal enum class Outcome { Filled, Denied, TimedOut, Refused }

/** The computers and the phone's link key, in `runtime.secrets`. */
internal class ComputerStore(private val secrets: StorageService) {
    private val writing = Mutex()

    /** Null when there is a list and it cannot be opened now: then nothing is written over it. */
    suspend fun load(): List<Computer>? {
        val stored = try {
            secrets.get(KEY_COMPUTERS)
        } catch (_: SecretsUnavailableException) {
            return null
        } ?: return emptyList()
        return runCatching { Json.decodeFromString<List<Computer>>(stored) }.getOrNull()
    }

    suspend fun save(computers: List<Computer>) = writing.withLock {
        secrets.set(KEY_COMPUTERS, Json.encodeToString(computers))
    }

    /** The phone's key, made the first time a computer is paired. */
    suspend fun linkKey(create: Boolean): NoiseKeyPair? = writing.withLock {
        val stored = try {
            secrets.get(KEY_LINK_KEY)
        } catch (_: SecretsUnavailableException) {
            return@withLock null
        }
        stored?.let { text -> runCatching { NoiseKeyPair.fromPrivate(Base64.decode(text)) }.getOrNull() }?.let { return@withLock it }
        if (!create) return@withLock null
        val pair = NoiseKeyPair.generate()
        secrets.set(KEY_LINK_KEY, Base64.encode(pair.private.bytes))
        pair
    }
}
