package cx.m42.apps.crnote

import cx.m42.services.vault.BiometricKey
import cx.m42.services.vault.BiometricOpened
import cx.m42.services.vault.PrivateFiles
import kotlin.random.Random

/** [PrivateFiles] in a map, which a test reads and compares between two looks. */
internal class MemoryFiles : PrivateFiles {
    val map = linkedMapOf<String, ByteArray>()

    override suspend fun list(): List<String> = synchronized(map) { map.keys.sorted() }

    override suspend fun read(name: String): ByteArray? = synchronized(map) { map[PrivateFiles.checkName(name)]?.copyOf() }

    override suspend fun write(name: String, bytes: ByteArray) {
        synchronized(map) { map[PrivateFiles.checkName(name)] = bytes.copyOf() }
    }

    override suspend fun delete(name: String) {
        synchronized(map) { map.remove(name) }
    }

    fun snapshot(): Map<String, ByteArray> = synchronized(map) { map.mapValues { it.value.copyOf() } }
}

/**
 * The chip, played: what was sealed is kept here and handed back by [open] as long as the key
 * exists and [next] says so. The sealed blob is random, so nothing of the secret is in the file.
 */
internal class FakeBiometric(var available: Boolean = true) : BiometricKey {
    private var secret: ByteArray? = null
    private var sealed: ByteArray? = null
    var created = 0
    var destroyed = 0
    var prompts = 0

    /** What the next sheet answers; null is the face that fits. */
    var next: BiometricOpened? = null

    override fun available(): Boolean = available

    override fun exists(): Boolean = secret != null

    override suspend fun create(secret: ByteArray): ByteArray {
        destroy()
        created++
        this.secret = secret.copyOf()
        return Random.nextBytes(256).also { sealed = it.copyOf() }
    }

    override suspend fun open(sealed: ByteArray, reason: String, cancel: String): BiometricOpened {
        prompts++
        next?.let { return it.also { next = null } }
        val kept = secret ?: return BiometricOpened.Invalidated
        if (!sealed.contentEquals(this.sealed)) return BiometricOpened.Failed("other")
        return BiometricOpened.Opened(kept.copyOf())
    }

    override fun destroy() {
        if (secret != null) destroyed++
        secret?.fill(0)
        secret = null
        sealed = null
    }

    /** New faces: the chip drops the key by itself (D656). */
    fun invalidate() {
        secret = null
        sealed = null
    }
}
