package cx.m42.services.ssh

import cx.m42.services.ssh.crypto.AesGcm
import cx.m42.services.ssh.crypto.Hash
import cx.m42.services.ssh.crypto.secureRandomBytes
import cx.m42.services.ssh.key.OpenSshKeyFile
import cx.m42.services.ssh.key.PrivateKey
import cx.m42.services.ssh.key.SoftwareOps
import cx.m42.services.ssh.keyring.ChipBackend
import cx.m42.services.ssh.keyring.ChipResult
import cx.m42.services.ssh.keyring.InMemoryRecordStore
import cx.m42.services.ssh.keyring.KeyPrompter
import cx.m42.services.ssh.keyring.Keyring
import cx.m42.services.ssh.keyring.UseReason
import cx.m42.superizer.app.AppId
import cx.m42.superizer.runtime.Platform
import cx.m42.superizer.ssh.Chip
import cx.m42.superizer.ssh.GeneratedKind
import cx.m42.superizer.ssh.KeyId
import cx.m42.superizer.ssh.KeyPurpose
import cx.m42.superizer.ssh.KeyStorage
import cx.m42.superizer.ssh.KeyringDevice
import cx.m42.superizer.ssh.SshKeyInfo
import cx.m42.superizer.ssh.UsePolicy
import cx.m42.superizer.testing.Canary
import cx.m42.superizer.testing.FakeClock
import cx.m42.superizer.testing.RecordingLogger
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CoroutineScope

/**
 * The person, scripted (03 §5): each question answered by what the test queued, and every question
 * recorded — so a test can say "one prompt, not two" and mean it.
 */
class ScriptedPrompter : KeyPrompter {
    val asked = mutableListOf<String>()
    var passphrases = ArrayDeque<String?>()
    var grantAnswer = true
    var confirmAnswer = true
    var chooseAnswer: ((List<SshKeyInfo>) -> KeyId?) = { it.firstOrNull()?.id }

    override suspend fun choose(app: AppId, purpose: KeyPurpose, candidates: List<SshKeyInfo>): KeyId? {
        asked += "choose:${app.value}:$purpose"
        return chooseAnswer(candidates)
    }

    override suspend fun grant(app: AppId, key: SshKeyInfo, purpose: KeyPurpose): Boolean {
        asked += "grant:${app.value}:$purpose"
        return grantAnswer
    }

    override suspend fun passphrase(reason: UseReason, wrong: Int): CharArray? {
        asked += "passphrase:${reason.operation}:$wrong"
        return passphrases.removeFirstOrNull()?.toCharArray()
    }

    override suspend fun confirm(reason: UseReason): Boolean {
        asked += "confirm:${reason.operation}"
        return confirmAnswer
    }

    fun count(prefix: String) = asked.count { it.startsWith(prefix) }
}

/**
 * A chip made of memory, for the level-1 and level-2 logic on the JVM (03 §5, `SoftwareBackend`):
 * level 1 keeps the key "inside" (a map this class never hands out), level 2 seals with an AES key
 * that never leaves it. [answer] is the device's prompt; [asks] counts it.
 */
internal class SoftwareChip(
    var answer: Boolean = true,
    var screenLock: Boolean = true,
    private val level: Int = 2,
) : ChipBackend {
    var asks = 0
    private val resident = mutableMapOf<String, PrivateKey>()
    private val wraps = mutableMapOf<String, ByteArray>()

    override val device: KeyringDevice
        get() = KeyringDevice(Platform.Android, sdk = 35, tee = true, screenLockSet = screenLock, levels = setOf(level))

    override fun placement(key: SshPublicKey, generated: Boolean): KeyStorage? =
        if (level == 1) KeyStorage.Tee else KeyStorage.SealedByChip(Chip.Tee)

    private fun prompt(): Boolean {
        asks++
        return answer
    }

    override suspend fun store(alias: String, storage: KeyStorage, key: PrivateKey, file: ByteArray, policy: UsePolicy, reason: UseReason): ChipResult<String?> {
        if (!screenLock) return ChipResult.NoScreenLock
        if (storage.level == 1) {
            resident[alias] = PrivateKey(key.publicKey, key.comment, key.fields.copy())
            return ChipResult.Ok(null)
        }
        if (!prompt()) return ChipResult.Cancelled
        val wrap = secureRandomBytes(32)
        wraps[alias] = wrap
        val iv = secureRandomBytes(12)
        return ChipResult.Ok("v1:" + Base64.encode(iv + AesGcm.seal(wrap, iv, file)))
    }

    override suspend fun generate(alias: String, kind: GeneratedKind, policy: UsePolicy, reason: UseReason) = ChipResult.Failed("not in this fake")

    override suspend fun unseal(alias: String, sealed: String, reason: UseReason): ChipResult<ByteArray> {
        val wrap = wraps[alias] ?: return ChipResult.Unusable
        if (!prompt()) return ChipResult.Cancelled
        val body = Base64.decode(sealed.removePrefix("v1:"))
        return AesGcm.open(wrap, body.copyOf(12), body.copyOfRange(12, body.size))?.let { ChipResult.Ok(it) } ?: ChipResult.Failed("tag")
    }

    override suspend fun sign(alias: String, key: SshPublicKey, data: ByteArray, rsaHash: Hash, reason: UseReason): ChipResult<ByteArray> {
        val inside = resident[alias] ?: return ChipResult.Unusable
        if (!prompt()) return ChipResult.Cancelled
        return SoftwareOps.signatureBlob(inside, data, rsaHash)?.let { ChipResult.Ok(it) } ?: ChipResult.Failed("sign")
    }

    override suspend fun agree(alias: String, peer: ByteArray, reason: UseReason): ChipResult<ByteArray> {
        val inside = resident[alias] ?: return ChipResult.Unusable
        if (!prompt()) return ChipResult.Cancelled
        val h = cx.m42.services.ssh.crypto.Sha512.digest(inside.ed25519Seed().use { it.bytes.copyOf() })
        return ChipResult.Ok(cx.m42.services.ssh.crypto.Curve25519.x25519(h.copyOf(32), peer))
    }

    override suspend fun rsaDecrypt(alias: String, ciphertext: ByteArray, reason: UseReason): ChipResult<ByteArray> = ChipResult.Failed("not in this fake")

    override fun exists(alias: String): Boolean = alias in resident || alias in wraps

    override fun delete(alias: String) {
        resident.remove(alias)?.close()
        wraps.remove(alias)?.fill(0)
    }

    /** The screen lock went: the chip drops its keys (step 0, P7). */
    fun dropEverything() {
        resident.values.forEach { it.close() }
        resident.clear()
        wraps.clear()
    }
}

internal class KeyringFixture(
    scope: CoroutineScope,
    val chip: SoftwareChip? = null,
    val platform: Platform = Platform.Desktop,
) {
    val clock = FakeClock()
    val records = InMemoryRecordStore()
    val prompter = ScriptedPrompter()
    val logger = RecordingLogger()
    internal val keyring = Keyring(records, prompter, chip, clock, platform, scope, logger)
    val admin get() = keyring.admin
    fun app(id: String) = keyring.forApp(AppId(id))

    /** Everything this fixture wrote or said, as bytes to scan. */
    fun everything(): ByteArray = ((records.text ?: "") + logger.lines.joinToString("\n")).encodeToByteArray()
}

/** The canary Ed25519 key as a file under [passphrase] — by our writer; the interop test proves ssh-keygen's opens the same. */
internal fun canaryFile(passphrase: String, rounds: Int = 4): ByteArray {
    val seed = Canary.ED25519_SEED.reveal()
    return PrivateKey.ed25519(seed, "canary").use { key ->
        seed.fill(0)
        SecretBytes.utf8(passphrase.toCharArray()).use { OpenSshKeyFile.write(key, it, rounds = rounds) }
    }
}

internal fun plainFile(seed: ByteArray = secureRandomBytes(32), comment: String = "plain"): ByteArray =
    PrivateKey.ed25519(seed, comment).use { OpenSshKeyFile.write(it, null) }

internal fun encryptedFile(passphrase: String, seed: ByteArray = secureRandomBytes(32), comment: String = "key"): ByteArray =
    PrivateKey.ed25519(seed, comment).use { key -> SecretBytes.utf8(passphrase.toCharArray()).use { OpenSshKeyFile.write(key, it, rounds = 4) } }

internal const val GOOD = "correct horse battery staple"
