package cx.m42.services.ssh.agent

import cx.m42.services.ssh.SecretBytes
import cx.m42.services.ssh.SshPublicKey
import cx.m42.services.ssh.crypto.Hash
import cx.m42.services.ssh.crypto.hmac
import cx.m42.services.ssh.crypto.secureRandomBytes
import cx.m42.services.ssh.key.PrivateKey
import cx.m42.services.ssh.key.SoftwareOps
import cx.m42.services.ssh.keyring.Keyring
import cx.m42.services.ssh.keyring.UseReason
import cx.m42.superizer.runtime.Clock
import cx.m42.superizer.ssh.AgentConstraint
import cx.m42.superizer.ssh.AgentIdentity
import cx.m42.superizer.ssh.ImportOutcome
import cx.m42.superizer.ssh.ImportRefusal
import cx.m42.superizer.ssh.KeyCapability
import cx.m42.superizer.ssh.KeyId
import cx.m42.superizer.ssh.KeyOperation
import cx.m42.superizer.ssh.KeyOrigin
import cx.m42.superizer.ssh.KeyStorage
import cx.m42.superizer.ssh.SshAgent
import cx.m42.superizer.ssh.SshKeyInfo
import cx.m42.superizer.ssh.SshOutcome
import cx.m42.superizer.ssh.UsePolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ssh-agent over the keyring (02 §2, D151): what `ssh-add -l` lists, what `ssh` asks to sign, what
 * `ssh-add` adds — one method per message, every signature confirmed by the person.
 *
 * **(impl)** `ssh-add key` delivers the key already decrypted; the protocol has no passphrase. A
 * key stored without one is what D167 forbids, so a key added through the agent lives in this
 * object's memory only — exactly as it would in OpenSSH's `ssh-agent` — until removed, locked away
 * or the process ends. Persistent keys are imported in Settings, under a passphrase.
 */
internal class HostAgent(
    private val keyring: Keyring,
    private val clock: Clock,
) : SshAgent {

    private class Added(val key: PrivateKey, val comment: String, val expiresAt: Long?)

    private val added = mutableListOf<Added>()
    private val lock = Mutex()
    private var lockHash: ByteArray? = null
    private val lockSalt = secureRandomBytes(16)

    private fun expire() {
        val now = clock.now()
        added.filter { it.expiresAt != null && now >= it.expiresAt }.forEach { it.key.close(); added.remove(it) }
    }

    override suspend fun identities(): List<AgentIdentity> = lock.withLock {
        if (lockHash != null) return@withLock emptyList()
        expire()
        keyring.keys.value.filter { it.usable }.mapNotNull { info ->
            SshPublicKey.parse(info.publicKey)?.let { AgentIdentity(it.blob, info.comment) }
        } + added.map { AgentIdentity(it.key.publicKey.blob, it.comment) }
    }

    override suspend fun sign(publicBlob: ByteArray, data: ByteArray, flags: Int): SshOutcome<ByteArray> {
        lock.withLock {
            if (lockHash != null) return SshOutcome.Locked
            expire()
        }
        val key = SshPublicKey.fromBlob(publicBlob) ?: return SshOutcome.NoKey
        val rsaHash = when {
            flags and SSH_AGENT_RSA_SHA2_512 != 0 -> Hash.SHA512
            flags and SSH_AGENT_RSA_SHA2_256 != 0 -> Hash.SHA256
            key.type == SshPublicKey.RSA -> return SshOutcome.NotAllowed("ssh-rsa-sha1") // D144
            else -> Hash.SHA512
        }
        val memory = added.firstOrNull { it.key.publicKey.blob.contentEquals(publicBlob) }
        if (memory != null) {
            val info = memoryInfo(memory)
            if (!keyring.confirm(UseReason(null, info, KeyOperation.AgentSign))) return SshOutcome.Cancelled
            return SoftwareOps.signatureBlob(memory.key, data, rsaHash)?.let { SshOutcome.Ok(it) } ?: SshOutcome.Failed("sign")
        }
        val record = keyring.recordByBlob(publicBlob) ?: return SshOutcome.NoKey
        return keyring.agentSign(record, data, rsaHash)
    }

    override suspend fun add(file: ByteArray, passphrase: CharArray?, constraints: Set<AgentConstraint>): ImportOutcome = try {
        lock.withLock {
            if (lockHash != null) return@withLock ImportOutcome.Failed("locked")
            val key = AgentCodec.readKey(file) ?: return@withLock ImportOutcome.Rejected(ImportRefusal.NotAKey)
            if (keyring.recordByBlob(key.publicKey.blob) != null || added.any { it.key.publicKey == key.publicKey }) {
                key.close()
                return@withLock ImportOutcome.Failed("already-there")
            }
            val lifetime = constraints.filterIsInstance<AgentConstraint.Lifetime>().firstOrNull()?.seconds
            added += Added(key, key.comment, lifetime?.let { clock.now() + it * 1000 })
            ImportOutcome.Imported(memoryInfo(added.last()))
        }
    } finally {
        file.fill(0)
        passphrase?.fill('\u0000')
    }

    override suspend fun remove(publicBlob: ByteArray): SshOutcome<Unit> {
        lock.withLock {
            if (lockHash != null) return SshOutcome.Locked
            val memory = added.firstOrNull { it.key.publicKey.blob.contentEquals(publicBlob) }
            if (memory != null) {
                memory.key.close()
                added.remove(memory)
                return SshOutcome.Ok(Unit)
            }
        }
        val record = keyring.recordByBlob(publicBlob) ?: return SshOutcome.NoKey
        return keyring.admin.delete(KeyId(record.id))
    }

    /**
     * `ssh-add -D`: the agent's own keys, gone. The keyring's persistent keys are not the agent's to
     * delete in one go — each one asks (D148) through [remove] — so this leaves them.
     */
    override suspend fun removeAll(): SshOutcome<Unit> = lock.withLock {
        if (lockHash != null) return@withLock SshOutcome.Locked
        added.forEach { it.key.close() }
        added.clear()
        SshOutcome.Ok(Unit)
    }

    override suspend fun lock(passphrase: CharArray): Boolean = lock.withLock {
        try {
            if (lockHash != null) return@withLock false
            lockHash = SecretBytes.utf8(passphrase).use { hmac(Hash.SHA256, lockSalt, it.bytes) }
            keyring.admin.lockAll()
            true
        } finally {
            passphrase.fill('\u0000')
        }
    }

    override suspend fun unlock(passphrase: CharArray): Boolean = lock.withLock {
        try {
            val expected = lockHash ?: return@withLock false
            val given = SecretBytes.utf8(passphrase).use { hmac(Hash.SHA256, lockSalt, it.bytes) }
            var diff = 0
            for (i in expected.indices) diff = diff or (expected[i].toInt() xor given[i].toInt())
            if (diff != 0) return@withLock false
            lockHash = null
            true
        } finally {
            passphrase.fill('\u0000')
        }
    }

    override suspend fun extension(name: String, body: ByteArray): SshOutcome<ByteArray> = when (name) {
        "query" -> SshOutcome.Ok(AgentCodec.nameList(listOf("query", SESSION_BIND)))
        // Accepted and remembered by nobody yet: a client of this agent is not in v1 (02 §6.6).
        SESSION_BIND -> SshOutcome.Ok(ByteArray(0))
        else -> SshOutcome.Failed("extension")
    }

    private fun memoryInfo(added: Added): SshKeyInfo = SshKeyInfo(
        id = added.key.publicKey.id,
        type = added.key.publicKey.type,
        publicKey = added.key.publicKey.authorizedKeysLine(),
        label = added.key.publicKey.label,
        comment = added.comment,
        storage = KeyStorage.SealedByPassphrase,
        policy = UsePolicy.EveryUse,
        origin = KeyOrigin.Imported,
        can = setOf(KeyCapability.Sign),
        createdAt = clock.now(),
    )

    companion object {
        const val SSH_AGENT_RSA_SHA2_256 = 2
        const val SSH_AGENT_RSA_SHA2_512 = 4
        const val SESSION_BIND = "session-bind@openssh.com"
    }
}
