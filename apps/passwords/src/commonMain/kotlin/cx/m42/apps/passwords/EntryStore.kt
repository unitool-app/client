package cx.m42.apps.passwords

import cx.m42.superizer.runtime.StorageService
import cx.m42.superizer.secrets.SecretsUnavailableException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Where the entries live: the host seals them (contract 3, D524). */
internal const val KEY_ENTRIES = "entries"

/**
 * The entries, as one JSON list in `runtime.secrets` — sealed by the host with whatever the
 * platform has, the chip on a phone. This app knows neither the key nor where it is.
 */
internal class EntryStore(private val secrets: StorageService) {

    /** One write at a time, in the order they were asked for: sealing suspends, and two saves must not cross. */
    private val writing = Mutex()

    sealed interface Loaded {
        class Ok(val entries: List<PasswordEntry>) : Loaded
        /** There is a list and it cannot be opened now — declined, or no passphrase given. Leave it alone. */
        data object Locked : Loaded
        /** There is a list and it is not one: unreadable. Leave it alone. */
        data object Unreadable : Loaded
    }

    suspend fun load(): Loaded {
        val stored = try {
            secrets.get(KEY_ENTRIES)
        } catch (_: SecretsUnavailableException) {
            return Loaded.Locked
        } ?: return Loaded.Ok(emptyList())
        return runCatching { Json.decodeFromString<List<PasswordEntry>>(stored) }
            .fold({ Loaded.Ok(it) }, { Loaded.Unreadable })
    }

    /** Written through the host, or a throw with nothing written — never the list in the clear by this app's hand. */
    suspend fun save(entries: List<PasswordEntry>) = writing.withLock {
        secrets.set(KEY_ENTRIES, Json.encodeToString(entries))
    }
}
