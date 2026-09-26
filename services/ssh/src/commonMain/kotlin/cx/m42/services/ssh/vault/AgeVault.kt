package cx.m42.services.ssh.vault

import cx.m42.services.ssh.Age
import cx.m42.services.ssh.keyring.Keyring
import cx.m42.superizer.secrets.SecretVault
import cx.m42.superizer.ssh.KeyId
import cx.m42.superizer.ssh.KeyOperation
import kotlin.io.encoding.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The host's vault where there is no chip — desktop and the web (05 §3, D164): the person's own SSH
 * key, and its passphrase as the root of trust.
 *
 * Sealing is `age -r <the key's public half>`: a pure function of a public key, so it never asks
 * anyone (I16). Opening needs the private half: the first time in a session, the keyring asks for the
 * passphrase once and this opens every value the host holds under this key at once, then the key
 * is closed again. What stays in memory until the host closes or [lock] is the *secrets*, never the
 * key (D176) — so every keyring operation still asks on its own.
 *
 * [sealedValues] is where the host keeps them — its prefs, `secret.*` — so one prompt opens them all.
 */
public class AgeVault internal constructor(
    private val keyring: Keyring,
    /** The key this vault seals to (D173: it cannot be deleted while it does). */
    public val keyId: KeyId,
    private val sealedValues: () -> Collection<String>,
) : SecretVault {

    private val cache = mutableMapOf<String, String>()
    private val opening = Mutex()
    private var open = false

    override val opensSilently: Boolean get() = open

    /** Whether the secrets are unlocked for this session (06: `vault.session-open`). */
    public val sessionOpen: Boolean get() = open

    override suspend fun seal(plaintext: String): String {
        val key = keyring.publicKey(keyId) ?: error("the key for secrets is gone")
        val sealed = PREFIX + Base64.encode(Age.encrypt(plaintext.encodeToByteArray(), listOf(key)))
        // Known now, so no reason to ask for it back later in this session.
        cache[sealed] = plaintext
        return sealed
    }

    override suspend fun open(sealed: String): String? {
        cache[sealed]?.let { return it }
        if (!sealed.startsWith(PREFIX)) return null
        return opening.withLock {
            cache[sealed]?.let { return@withLock it }
            val pending = (sealedValues().filter { it.startsWith(PREFIX) } + sealed).distinct().filter { it !in cache }
            val files = pending.map { runCatching { Base64.decode(it, startIndex = PREFIX.length) }.getOrDefault(ByteArray(0)) }
            val opened = keyring.decryptForHost(files, KeyOperation.Decrypt) ?: return@withLock null
            pending.zip(opened).forEach { (value, plain) ->
                if (plain != null) {
                    cache[value] = plain.decodeToString()
                    plain.fill(0)
                }
            }
            open = true
            cache[sealed]
        }
    }

    /** The session ends: every opened secret forgotten (lockAll, the host closing). */
    public fun lock() {
        cache.clear()
        open = false
    }

    public companion object {
        public const val PREFIX: String = "age1:"
    }
}
