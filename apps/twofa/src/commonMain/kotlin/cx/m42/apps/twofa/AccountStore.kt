package cx.m42.apps.twofa

import cx.m42.services.vault.SecretVault
import cx.m42.superizer.runtime.Logger
import cx.m42.superizer.runtime.StorageService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** The accounts as plain JSON: on a host with no vault, and on an install from before there was one. */
internal const val KEY_ACCOUNTS = "accounts"

/** The accounts as the host's vault sealed them. */
internal const val KEY_SEALED_ACCOUNTS = "accounts.sealed"

/**
 * The accounts in the app's storage — sealed by the host's [SecretVault] wherever it has one.
 *
 * With a vault nothing readable is ever written: the whole list, names included, goes in as one
 * sealed string, and a plain list left by an older version is sealed and deleted the first time it
 * is read. A vault that fails to seal means nothing is saved, never that the list is saved in the
 * clear. Without one — desktop and the web, which have no keystore to hold a key — the list is
 * plain JSON, as it always was there.
 */
internal class AccountStore(
    private val storage: StorageService,
    private val vault: SecretVault?,
    private val logger: Logger? = null,
) {

    /** One write at a time, in the order they were asked for: sealing suspends, and two saves must not cross. */
    private val writing = Mutex()

    /**
     * The stored list, or null when there is one and it cannot be read — sealed under a key this
     * device does not have, or not a list at all. Null means leave it alone: whatever is there may
     * be somebody's only copy of their keys.
     */
    suspend fun load(): List<TotpAccount>? {
        val sealed = storage.get(KEY_SEALED_ACCOUNTS)
        if (sealed != null) {
            val accounts = vault?.open(sealed)?.let(::decode) ?: return null
            // A plain copy left by a migration that was cut short; the sealed list is the newer.
            if (storage.get(KEY_ACCOUNTS) != null) storage.remove(KEY_ACCOUNTS)
            return accounts
        }
        val plain = storage.get(KEY_ACCOUNTS) ?: return emptyList()
        val accounts = decode(plain) ?: return null
        // Sealed now rather than at the next change, so the clear copy does not wait for one. If
        // the keystore refuses, the list stays as it was and the next save tries again.
        if (vault != null) {
            runCatching { save(accounts) }.onFailure { logger?.warn("2FA accounts left plain: the vault refused to seal them", it) }
        }
        return accounts
    }

    /** Throws when the vault cannot seal, and has then written nothing. */
    suspend fun save(accounts: List<TotpAccount>) = writing.withLock {
        val json = Json.encodeToString(accounts)
        if (vault == null) {
            storage.set(KEY_ACCOUNTS, json)
        } else {
            storage.set(KEY_SEALED_ACCOUNTS, vault.seal(json))
            storage.remove(KEY_ACCOUNTS)
        }
    }

    private fun decode(json: String): List<TotpAccount>? =
        runCatching { Json.decodeFromString<List<TotpAccount>>(json) }.getOrNull()
}
