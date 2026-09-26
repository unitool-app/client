package cx.m42.apps.twofa

import cx.m42.superizer.runtime.Logger
import cx.m42.superizer.runtime.StorageService
import cx.m42.superizer.secrets.SecretVault
import cx.m42.superizer.secrets.SecretsUnavailableException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Where the accounts live now: the host seals them (contract 3, ssh-new 05 §3.2). */
internal const val KEY_SECRET_ACCOUNTS = "accounts"

/** The accounts as plain JSON, from an install before there was a vault. Read once and moved. */
internal const val KEY_ACCOUNTS = "accounts"

/** The accounts as this app sealed them itself with the host's vault, before contract 3. Read once and moved. */
internal const val KEY_SEALED_ACCOUNTS = "accounts.sealed"

/**
 * The accounts, in `runtime.secrets` — sealed by the host, by whatever the platform has: the chip on
 * a phone, the person's SSH key where there is none (05 §3.2, D171). This app knows neither.
 *
 * What an older version left in the app's own storage is moved in on the first read (04 §9, Б4):
 * `accounts.sealed` opened with [legacyVault] — the same Keystore key the host's vault uses, so a
 * tester's phone opens what it sealed last week — and `accounts` as it was. The old copy is removed
 * only after the new one is written.
 */
internal class AccountStore(
    private val storage: StorageService,
    private val secrets: StorageService,
    private val legacyVault: SecretVault?,
    private val logger: Logger? = null,
) {

    /** One write at a time, in the order they were asked for: sealing suspends, and two saves must not cross. */
    private val writing = Mutex()

    sealed interface Loaded {
        class Ok(val accounts: List<TotpAccount>) : Loaded
        /** There is a list and it cannot be opened now — declined, or sealed under a key that is gone. Leave it alone. */
        data object Locked : Loaded
        /** There is a list and it is not one: somebody's data, unreadable. Leave it alone. */
        data object Unreadable : Loaded
    }

    suspend fun load(): Loaded {
        val current = try {
            secrets.get(KEY_SECRET_ACCOUNTS)
        } catch (_: SecretsUnavailableException) {
            return Loaded.Locked
        }
        if (current != null) {
            val accounts = decode(current) ?: return Loaded.Unreadable
            // A move cut short leaves the old copies behind; the sealed list is the newer.
            dropLegacy()
            return Loaded.Ok(accounts)
        }
        val sealed = storage.get(KEY_SEALED_ACCOUNTS)
        if (sealed != null) {
            val opened = legacyVault?.open(sealed) ?: return Loaded.Unreadable
            val accounts = decode(opened) ?: return Loaded.Unreadable
            move(accounts)
            return Loaded.Ok(accounts)
        }
        val plain = storage.get(KEY_ACCOUNTS) ?: return Loaded.Ok(emptyList())
        val accounts = decode(plain) ?: return Loaded.Unreadable
        move(accounts)
        return Loaded.Ok(accounts)
    }

    /** Written through the host, or a throw with nothing written — never the list in the clear by this app's hand. */
    suspend fun save(accounts: List<TotpAccount>) = writing.withLock {
        secrets.set(KEY_SECRET_ACCOUNTS, Json.encodeToString(accounts))
        dropLegacy()
    }

    private suspend fun move(accounts: List<TotpAccount>) {
        // If the host cannot seal right now, the old copy stays and the next save moves it.
        runCatching { save(accounts) }.onFailure { logger?.warn("2FA accounts not moved into the host's secrets yet", it) }
    }

    private suspend fun dropLegacy() {
        if (storage.get(KEY_SEALED_ACCOUNTS) != null) storage.remove(KEY_SEALED_ACCOUNTS)
        if (storage.get(KEY_ACCOUNTS) != null) storage.remove(KEY_ACCOUNTS)
    }

    private fun decode(json: String): List<TotpAccount>? =
        runCatching { Json.decodeFromString<List<TotpAccount>>(json) }.getOrNull()
}
