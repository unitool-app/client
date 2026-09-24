package cx.m42.services.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AES-256-GCM under a key made by, and kept in, the `AndroidKeyStore`.
 *
 * The key is created on first use and never leaves the Keystore: this class holds a handle to it,
 * `encoded` is null, and the encryption itself runs in the keystore's own process — in the TEE or
 * StrongBox where the device has one. No user authentication is asked for, because the codes a
 * 2FA app shows have to be there whenever it opens; what the key protects against is the app's
 * files being read off the device — a backup, a rooted copy, a forensic image — not someone
 * holding the unlocked phone.
 *
 * Sealed text is `v1:` and Base64 of the 12-byte nonce the Keystore chose followed by the
 * ciphertext and its 16-byte tag. The prefix is also the associated data, so it is authenticated
 * along with the rest.
 *
 * [alias] is the key's name in the Keystore — a parameter so a test can use its own and delete it.
 */
public class AndroidKeystoreVault(private val alias: String = DEFAULT_ALIAS) : SecretVault {

    override suspend fun seal(plaintext: String): String = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // No nonce passed in: the Keystore insists on choosing it, which is the safe way round.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        PREFIX + Base64.encode(cipher.iv + cipher.doFinal(plaintext.encodeToByteArray()))
    }

    override suspend fun open(sealed: String): String? = withContext(Dispatchers.IO) {
        if (!sealed.startsWith(PREFIX)) return@withContext null
        runCatching {
            val body = Base64.decode(sealed, startIndex = PREFIX.length)
            if (body.size < NONCE_SIZE + TAG_BYTES) return@runCatching null
            val key = existingKey() ?: return@runCatching null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, body, 0, NONCE_SIZE))
            cipher.updateAAD(AAD)
            cipher.doFinal(body, NONCE_SIZE, body.size - NONCE_SIZE).decodeToString(throwOnInvalidSequence = true)
        }.getOrNull()
    }

    /**
     * The key, made on the first call. Under a lock: two first seals racing on two IO threads
     * would otherwise each generate one, and whatever the loser sealed would be locked for good.
     */
    private fun key(): SecretKey = synchronized(LOCK) { existingKey() ?: generate() }

    private fun existingKey(): SecretKey? =
        KeyStore.getInstance(PROVIDER).apply { load(null) }.getKey(alias, null) as? SecretKey

    private fun generate(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    public companion object {
        public const val DEFAULT_ALIAS: String = "cx.m42.services.vault"

        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFIX = "v1:"
        private const val NONCE_SIZE = 12
        private const val TAG_BYTES = 16
        private val AAD = PREFIX.encodeToByteArray()
        private val LOCK = Any()
    }
}
