package cx.m42.services.ssh.keyring

import cx.m42.services.ssh.Age
import cx.m42.services.ssh.AgeIdentity
import cx.m42.services.ssh.SecretBytes
import cx.m42.services.ssh.SshPublicKey
import cx.m42.services.ssh.SshSig
import cx.m42.services.ssh.StanzaUnwrapper
import cx.m42.services.ssh.crypto.Hash
import cx.m42.services.ssh.crypto.secureRandomBytes
import cx.m42.services.ssh.key.OpenSshKeyFile
import cx.m42.services.ssh.key.PrivateKey
import cx.m42.services.ssh.key.SoftwareOps
import cx.m42.superizer.app.AppId
import cx.m42.superizer.runtime.Clock
import cx.m42.superizer.runtime.Logger
import cx.m42.superizer.runtime.Platform
import cx.m42.superizer.ssh.GeneratedKind
import cx.m42.superizer.ssh.Grant
import cx.m42.superizer.ssh.ImportOptions
import cx.m42.superizer.ssh.ImportOutcome
import cx.m42.superizer.ssh.ImportRefusal
import cx.m42.superizer.ssh.Inspection
import cx.m42.superizer.ssh.KeyCapability
import cx.m42.superizer.ssh.KeyId
import cx.m42.superizer.ssh.KeyOperation
import cx.m42.superizer.ssh.KeyOrigin
import cx.m42.superizer.ssh.KeyPurpose
import cx.m42.superizer.ssh.KeyStorage
import cx.m42.superizer.ssh.KeyringDevice
import cx.m42.superizer.ssh.MIN_PASSPHRASE
import cx.m42.superizer.ssh.SshKeyAdmin
import cx.m42.superizer.ssh.SshKeyInfo
import cx.m42.superizer.ssh.SshKeyring
import cx.m42.superizer.ssh.SshOutcome
import cx.m42.superizer.ssh.SshSignature
import cx.m42.superizer.ssh.UsePolicy
import cx.m42.superizer.ssh.UseRecord
import kotlin.io.encoding.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The keyring (02, 05): one implementation, three doors — [admin] for the host's screens, [forApp]
 * for an app, [agent] for the ssh-agent protocol — and every private operation, whichever door it
 * came through, through one place: [use].
 *
 * Levels: with a [chip], keys live in it (1) or under a wrapping key from it (2); without one, or
 * for a key the chip cannot hold, under the key's own passphrase (3). A level-3 key is the file the
 * person gave, byte for byte, or the one "Set passphrase" wrote (05 §2.2, D167).
 *
 * What is in memory, and for how long: a decrypted key for one operation, or — under
 * [UsePolicy.Session] — until the window closes, [SshKeyAdmin.lockAll], or the process ends (I15).
 * Never in a record, a log, an event or an exception (I1, I5).
 */
internal class Keyring(
    private val store: RecordStore,
    private val prompter: KeyPrompter,
    private val chip: ChipBackend?,
    private val clock: Clock,
    private val platform: Platform,
    private val scope: CoroutineScope,
    private val logger: Logger? = null,
    private val random: (Int) -> ByteArray = ::secureRandomBytes,
) {

    private var file: RecordsFile = load()
    private val _keys = MutableStateFlow(infos())
    val keys: StateFlow<List<SshKeyInfo>> get() = _keys.asStateFlow()

    private val _grants = MutableStateFlow(file.grants)
    private val _journal = MutableStateFlow(file.journal)
    private val _secretsKey = MutableStateFlow(file.secretsKey?.let(::KeyId))

    /** The key the host's secrets are sealed to where there is no chip (05 §3, D173). */
    val secretsKey: StateFlow<KeyId?> get() = _secretsKey.asStateFlow()

    /** Keys opened under a session policy, until they close. Nothing else of a private key outlives an operation. */
    private val sessions = mutableMapOf<String, OpenKey>()

    private class OpenKey(val key: PrivateKey, val until: Long, val timer: Job?)

    /** One sheet at a time (04 step 5): a second request while one is up is [SshOutcome.Busy]. */
    private val sheet = Mutex()

    /** Every write to the records, in order. */
    private val writing = Mutex()

    /** Called by [lockAll], after the sessions are gone: the host's secret cache listens (D176). */
    var onLockAll: () -> Unit = {}

    val admin: SshKeyAdmin = Admin()

    fun forApp(caller: AppId, brick: String? = null): SshKeyring = AppKeyring(caller, brick)

    val device: KeyringDevice
        get() = chip?.device ?: KeyringDevice(platform = platform, levels = setOf(3))

    // ------------------------------------------------------------ records

    private fun load(): RecordsFile =
        store.load()?.let { runCatching { RecordsJson.decodeFromString(RecordsFile.serializer(), it) }.getOrNull() } ?: RecordsFile()

    private suspend fun update(change: (RecordsFile) -> RecordsFile) = writing.withLock {
        file = change(file)
        if (file.keys.isEmpty() && file.grants.isEmpty()) {
            // The last key is gone: the file goes with it, not an empty shell of it (03 §2).
            store.clear()
            file = RecordsFile()
        } else {
            store.save(RecordsJson.encodeToString(RecordsFile.serializer(), file))
        }
        _keys.value = infos()
        _grants.value = file.grants
        _journal.value = file.journal
        _secretsKey.value = file.secretsKey?.let(::KeyId)
    }

    private fun infos(): List<SshKeyInfo> = file.keys.mapNotNull { info(it) }

    private fun info(record: KeyRecord): SshKeyInfo? {
        val key = SshPublicKey.parse(record.publicKey) ?: return null
        val usable = record.usable && (record.alias == null || chip?.exists(record.alias) != false)
        return SshKeyInfo(
            id = KeyId(record.id),
            type = key.type,
            publicKey = record.publicKey,
            label = key.label,
            comment = record.comment,
            storage = record.storage,
            policy = record.policy,
            origin = record.origin,
            can = capabilities(key),
            createdAt = record.createdAt,
            usable = usable,
        )
    }

    private fun capabilities(key: SshPublicKey): Set<KeyCapability> = buildSet {
        add(KeyCapability.Sign)
        if (Age.supports(key)) add(KeyCapability.DecryptAge)
    }

    private fun record(id: KeyId): KeyRecord? = file.keys.firstOrNull { it.id == id.fingerprint }

    fun publicKey(id: KeyId): SshPublicKey? = record(id)?.let { SshPublicKey.parse(it.publicKey) }

    private suspend fun journal(entry: UseRecord) = update { f ->
        f.copy(journal = (f.journal + entry).takeLast(JOURNAL))
    }

    private fun outcomeText(outcome: SshOutcome<*>): String = when (outcome) {
        is SshOutcome.Ok -> "ok"
        SshOutcome.Cancelled -> "cancelled"
        SshOutcome.Refused -> "refused"
        is SshOutcome.NotAllowed -> "not-allowed:${outcome.reason}"
        SshOutcome.NoKey -> "no-key"
        SshOutcome.Unusable -> "unusable"
        SshOutcome.Busy -> "busy"
        SshOutcome.Locked -> "locked"
        is SshOutcome.Failed -> "failed:${outcome.code}"
    }

    // ------------------------------------------------------------ the one door every private operation goes through

    /**
     * Opens [record]'s key for one operation and runs it: from a session if one is open; else by the
     * chip's prompt (level 1 runs [chipOp] in the chip; level 2 unseals and runs [softwareOp]); else by
     * the passphrase (level 3). The opened key is closed before this returns, unless a session keeps it.
     */
    private suspend fun <T> use(
        record: KeyRecord,
        reason: UseReason,
        softwareOp: suspend (PrivateKey) -> T?,
        chipOp: (suspend (String) -> ChipResult<T>)?,
    ): SshOutcome<T> {
        val info = info(record) ?: return SshOutcome.Failed("record")
        if (!info.usable) {
            markUnusable(record)
            return SshOutcome.Unusable
        }
        sessionKey(record)?.let { open ->
            return softwareOp(open)?.let { SshOutcome.Ok(it) } ?: SshOutcome.Failed("operation")
        }
        return when (record.storage.level) {
            1 -> {
                val op = chipOp ?: return SshOutcome.Failed("not-in-chip")
                val alias = record.alias ?: return SshOutcome.Failed("record")
                chipOutcome(record, op(alias))
            }
            2 -> {
                val backend = chip ?: return SshOutcome.Failed("no-chip")
                val unsealed = when (val r = backend.unseal(record.alias ?: return SshOutcome.Failed("record"), record.sealed ?: return SshOutcome.Failed("record"), reason)) {
                    is ChipResult.Ok -> r.value
                    else -> return chipOutcome(record, r).cast()
                }
                try {
                    val parsed = OpenSshKeyFile.parse(unsealed) as? OpenSshKeyFile.Parsed.Ok ?: return SshOutcome.Failed("sealed-file")
                    val key = if (record.keepPassphrase) {
                        when (val opened = openWithPassphrase(parsed.file, reason)) {
                            is SshOutcome.Ok -> opened.value
                            else -> return opened.cast()
                        }
                    } else {
                        (parsed.file.decrypt(null) as? OpenSshKeyFile.Decrypted.Ok)?.key ?: return SshOutcome.Failed("sealed-file")
                    }
                    parsed.file.wipe()
                    runAndMaybeKeep(record, key, softwareOp)
                } finally {
                    unsealed.fill(0)
                }
            }
            else -> {
                val bytes = record.file?.let { Base64.decode(it) } ?: return SshOutcome.Failed("record")
                val parsed = OpenSshKeyFile.parse(bytes) as? OpenSshKeyFile.Parsed.Ok ?: return SshOutcome.Failed("record")
                when (val opened = openWithPassphrase(parsed.file, reason)) {
                    is SshOutcome.Ok -> runAndMaybeKeep(record, opened.value, softwareOp)
                    else -> opened.cast()
                }
            }
        }
    }

    /** Asks for the passphrase until it opens [keyFile], the person cancels, or [MAX_TRIES] wrong ones. */
    private suspend fun openWithPassphrase(keyFile: OpenSshKeyFile, reason: UseReason): SshOutcome<PrivateKey> {
        var wrong = 0
        while (wrong < MAX_TRIES) {
            val chars = prompter.passphrase(reason, wrong) ?: return SshOutcome.Cancelled
            val secret = SecretBytes.utf8(chars)
            chars.fill('\u0000')
            val result = secret.use { keyFile.decrypt(it) }
            when (result) {
                is OpenSshKeyFile.Decrypted.Ok -> return SshOutcome.Ok(result.key)
                OpenSshKeyFile.Decrypted.WrongPassphrase -> wrong++
                OpenSshKeyFile.Decrypted.Corrupted -> return SshOutcome.Failed("corrupted")
            }
        }
        return SshOutcome.Failed("wrong-passphrase")
    }

    private suspend fun <T> runAndMaybeKeep(record: KeyRecord, key: PrivateKey, op: suspend (PrivateKey) -> T?): SshOutcome<T> {
        val result = try {
            op(key)
        } catch (e: Exception) {
            key.close()
            return SshOutcome.Failed("operation", e::class.simpleName)
        }
        val policy = record.policy
        if (policy is UsePolicy.Session && policy.seconds > 0) {
            keep(record.id, key, policy.seconds)
        } else {
            key.close()
        }
        return result?.let { SshOutcome.Ok(it) } ?: SshOutcome.Failed("operation")
    }

    private fun keep(id: String, key: PrivateKey, seconds: Int) {
        sessions.remove(id)?.let { it.key.close(); it.timer?.cancel() }
        val timer = scope.launch {
            delay(seconds * 1000L)
            sessions.remove(id)?.key?.close()
        }
        sessions[id] = OpenKey(key, clock.now() + seconds * 1000L, timer)
    }

    private fun sessionKey(record: KeyRecord): PrivateKey? {
        val open = sessions[record.id] ?: return null
        if (clock.now() >= open.until || open.key.fields.closed) {
            sessions.remove(record.id)
            open.key.close()
            open.timer?.cancel()
            return null
        }
        return open.key
    }

    private suspend fun <T> chipOutcome(record: KeyRecord, result: ChipResult<T>): SshOutcome<T> = when (result) {
        is ChipResult.Ok -> SshOutcome.Ok(result.value)
        ChipResult.Cancelled -> SshOutcome.Cancelled
        ChipResult.Busy -> SshOutcome.Busy
        ChipResult.Unusable -> {
            markUnusable(record)
            SshOutcome.Unusable
        }
        ChipResult.NoScreenLock -> SshOutcome.Failed("no-screen-lock")
        is ChipResult.Failed -> SshOutcome.Failed(result.code, result.evidence)
    }

    private suspend fun markUnusable(record: KeyRecord) {
        if (!record.usable) return
        update { f -> f.copy(keys = f.keys.map { if (it.id == record.id) it.copy(usable = false) else it }) }
    }

    private fun digest(data: ByteArray): String = Hash.SHA256.digest(data).copyOf(4).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    // ------------------------------------------------------------ operations, shared by the doors

    /** SSHSIG with [record]'s key. */
    private suspend fun sshsig(record: KeyRecord, namespace: String, message: ByteArray, reason: UseReason): SshOutcome<SshSignature> {
        val key = SshPublicKey.parse(record.publicKey) ?: return SshOutcome.Failed("record")
        val data = SshSig.signedData(namespace, Hash.SHA512, message)
        return use(
            record,
            reason,
            softwareOp = { private -> SoftwareOps.sshsig(private, namespace, message)?.let(::SshSignature) },
            chipOp = { alias ->
                when (val blob = chip!!.sign(alias, key, data, Hash.SHA512, reason)) {
                    is ChipResult.Ok -> ChipResult.Ok(SshSignature(SshSig.armor(key, namespace, Hash.SHA512, blob.value)))
                    is ChipResult.Cancelled -> ChipResult.Cancelled
                    is ChipResult.Busy -> ChipResult.Busy
                    is ChipResult.Unusable -> ChipResult.Unusable
                    is ChipResult.NoScreenLock -> ChipResult.NoScreenLock
                    is ChipResult.Failed -> blob
                }
            },
        )
    }

    /** age -d with [record]'s key: an identity whose private step is the chip's or the opened key's. */
    private suspend fun ageDecrypt(record: KeyRecord, fileBytes: ByteArray, reason: UseReason): SshOutcome<ByteArray> {
        val key = SshPublicKey.parse(record.publicKey) ?: return SshOutcome.Failed("record")
        suspend fun open(identity: AgeIdentity): ByteArray? =
            (Age.decrypt(fileBytes, listOf(identity)) as? Age.Opened.Ok)?.plaintext
        return use(
            record,
            reason,
            softwareOp = { private -> SoftwareOps.ageIdentity(private)?.let { open(it) } },
            chipOp = { alias ->
                var failure: ChipResult<Nothing>? = null
                val identity = StanzaUnwrapper(
                    key,
                    agree = { peer -> chip!!.agree(alias, peer, reason).valueOr { failure = it; null } },
                    rsaPrivate = { c -> chip!!.rsaDecrypt(alias, c, reason).valueOr { failure = it; null } },
                )
                val plain = open(identity)
                when {
                    plain != null -> ChipResult.Ok(plain)
                    failure != null -> failure!!
                    else -> ChipResult.Failed("age")
                }
            },
        )
    }

    private inline fun <T> ChipResult<T>.valueOr(onFailure: (ChipResult<Nothing>) -> T?): T? = when (this) {
        is ChipResult.Ok -> value
        is ChipResult.Cancelled -> onFailure(this)
        is ChipResult.Busy -> onFailure(this)
        is ChipResult.Unusable -> onFailure(this)
        is ChipResult.NoScreenLock -> onFailure(this)
        is ChipResult.Failed -> onFailure(this)
    }

    /** The records whose keys one of [fileBytes]' stanzas is for, usable ones first. */
    private fun recipientsOf(fileBytes: ByteArray): List<KeyRecord>? {
        val stanzas = Age.stanzas(fileBytes) ?: return null
        return file.keys.filter { record ->
            val key = SshPublicKey.parse(record.publicKey) ?: return@filter false
            val probe = object : AgeIdentity {
                override val publicKey = key
                override suspend fun unwrap(stanza: Age.Stanza): ByteArray? = null
            }
            stanzas.any { probe.matches(it) }
        }.sortedByDescending { record -> info(record)?.usable == true }
    }

    // ------------------------------------------------------------ for the host itself (AgeVault, backup)

    /**
     * Opens every one of [files] with the host's own authority — no app, no grant — asking once for
     * whichever key they are for. The key is closed before this returns, whatever its policy: the host
     * keeps the *secrets* it opened, never the key (D176). Null when the person declined.
     */
    suspend fun decryptForHost(files: List<ByteArray>, operation: KeyOperation = KeyOperation.Decrypt): List<ByteArray?>? {
        if (files.isEmpty()) return emptyList()
        val record = files.firstNotNullOfOrNull { recipientsOf(it)?.firstOrNull() } ?: return files.map { null }
        val info = info(record) ?: return null
        val reason = UseReason(app = null, key = info, operation = operation, digest = digest(files.first()))
        val key = SshPublicKey.parse(record.publicKey) ?: return null
        val result: SshOutcome<List<ByteArray?>> = use(
            record.copy(policy = UsePolicy.EveryUse),
            reason,
            softwareOp = { private ->
                val identity = SoftwareOps.ageIdentity(private) ?: return@use null
                files.map { (Age.decrypt(it, listOf(identity)) as? Age.Opened.Ok)?.plaintext }
            },
            chipOp = { alias ->
                val identity = StanzaUnwrapper(
                    key,
                    agree = { peer -> (chip!!.agree(alias, peer, reason) as? ChipResult.Ok)?.value },
                    rsaPrivate = { c -> (chip!!.rsaDecrypt(alias, c, reason) as? ChipResult.Ok)?.value },
                )
                ChipResult.Ok(files.map { (Age.decrypt(it, listOf(identity)) as? Age.Opened.Ok)?.plaintext })
            },
        )
        journal(UseRecord(clock.now(), null, KeyId(record.id), operation, null, reason.digest, outcomeText(result)))
        return (result as? SshOutcome.Ok)?.value
    }

    suspend fun setSecretsKey(id: KeyId?) = update { it.copy(secretsKey = id?.fingerprint) }

    // ------------------------------------------------------------ SshKeyAdmin

    private inner class Admin : SshKeyAdmin {
        override val keys: StateFlow<List<SshKeyInfo>> get() = this@Keyring.keys
        override val device: KeyringDevice get() = this@Keyring.device

        override fun inspect(file: ByteArray): Inspection = when (val parsed = OpenSshKeyFile.parse(file)) {
            is OpenSshKeyFile.Parsed.Refused -> Inspection(refusal = parsed.refusal)
            is OpenSshKeyFile.Parsed.Ok -> {
                val f = parsed.file
                val key = f.publicKey
                Inspection(
                    type = key.type,
                    id = key.id,
                    label = key.label,
                    comment = f.commentWithoutPassphrase(),
                    encrypted = f.isEncrypted,
                    rounds = f.rounds.takeIf { f.isEncrypted },
                    wouldBe = placement(key, generated = false),
                    alreadyThere = record(key.id) != null,
                ).also { f.wipe() }
            }
        }

        override suspend fun import(file: ByteArray, passphrase: CharArray?, options: ImportOptions, newPassphrase: CharArray?): ImportOutcome = try {
            importFile(file, passphrase, options, newPassphrase, KeyOrigin.Imported)
        } finally {
            file.fill(0)
            passphrase?.fill('\u0000')
            newPassphrase?.fill('\u0000')
        }

        override suspend fun generate(kind: GeneratedKind, options: ImportOptions, passphrase: CharArray?): ImportOutcome = try {
            generateKey(kind, options, passphrase)
        } finally {
            passphrase?.fill('\u0000')
        }

        override suspend fun rekey(id: KeyId, newPassphrase: CharArray): SshOutcome<Unit> = try {
            rekeyFile(id, newPassphrase)
        } finally {
            newPassphrase.fill('\u0000')
        }

        override suspend fun rename(id: KeyId, comment: String) {
            update { f -> f.copy(keys = f.keys.map { if (it.id == id.fingerprint) it.copy(comment = comment.trim()) else it }) }
        }

        override suspend fun delete(id: KeyId): SshOutcome<Unit> {
            val record = record(id) ?: return SshOutcome.NoKey
            if (file.secretsKey == id.fingerprint) return SshOutcome.NotAllowed("secrets-anchor")
            val info = info(record) ?: return SshOutcome.Failed("record")
            if (!prompter.confirm(UseReason(null, info, KeyOperation.Delete))) return SshOutcome.Cancelled
            sessions.remove(record.id)?.let { it.key.close(); it.timer?.cancel() }
            record.alias?.let { alias -> chip?.let { c -> runCatching { c.delete(alias) } } }
            update { f ->
                f.copy(
                    keys = f.keys.filterNot { it.id == record.id },
                    grants = f.grants.filterNot { it.key == id },
                    journal = f.journal.filterNot { it.key == id },
                )
            }
            return SshOutcome.Ok(Unit)
        }

        override fun grants(id: KeyId): StateFlow<List<Grant>> =
            _grants.map { all -> all.filter { it.key == id } }.stateIn(scope, SharingStarted.Eagerly, file.grants.filter { it.key == id })

        override suspend fun revoke(grant: Grant) {
            update { f -> f.copy(grants = f.grants.filterNot { it == grant }) }
        }

        override fun journal(id: KeyId): StateFlow<List<UseRecord>> =
            _journal.map { all -> all.filter { it.key == id } }.stateIn(scope, SharingStarted.Eagerly, file.journal.filter { it.key == id })

        override fun lockAll() {
            sessions.values.forEach { it.key.close(); it.timer?.cancel() }
            sessions.clear()
            onLockAll()
        }
    }

    private fun placement(key: SshPublicKey, generated: Boolean): KeyStorage =
        chip?.placement(key, generated) ?: KeyStorage.SealedByPassphrase

    private suspend fun importFile(
        bytes: ByteArray,
        passphrase: CharArray?,
        options: ImportOptions,
        newPassphrase: CharArray?,
        origin: KeyOrigin,
    ): ImportOutcome {
        val parsed = when (val p = OpenSshKeyFile.parse(bytes)) {
            is OpenSshKeyFile.Parsed.Refused -> return ImportOutcome.Rejected(p.refusal)
            is OpenSshKeyFile.Parsed.Ok -> p.file
        }
        try {
            record(parsed.publicKey.id)?.let { existing -> return ImportOutcome.AlreadyThere(info(existing)!!) }
            val given = if (parsed.isEncrypted) passphrase?.size ?: 0 else 0
            if (parsed.isEncrypted && (passphrase == null || passphrase.isEmpty())) return ImportOutcome.WrongPassphrase
            val key = when (val d = SecretBytes.utf8(passphrase ?: CharArray(0)).use { parsed.decrypt(if (parsed.isEncrypted) it else null) }) {
                is OpenSshKeyFile.Decrypted.Ok -> d.key
                OpenSshKeyFile.Decrypted.WrongPassphrase -> return ImportOutcome.WrongPassphrase
                OpenSshKeyFile.Decrypted.Corrupted -> return ImportOutcome.Rejected(ImportRefusal.Corrupted)
            }
            key.use {
                // D167: no key without a passphrase, and none under one shorter than twelve.
                val stored: ByteArray = if (given < MIN_PASSPHRASE) {
                    val fresh = newPassphrase ?: return ImportOutcome.NeedsNewPassphrase(given)
                    if (fresh.size < MIN_PASSPHRASE) return ImportOutcome.NeedsNewPassphrase(given)
                    SecretBytes.utf8(fresh).use { OpenSshKeyFile.write(key, it, random = random) }
                } else {
                    bytes.copyOf()
                }
                return try {
                    storeKey(key, stored, options, origin)
                } finally {
                    stored.fill(0)
                }
            }
        } finally {
            parsed.wipe()
        }
    }

    /** Puts [key] where [placement] says, with [encryptedFile] as its level-3 file or the file level 2 seals. */
    private suspend fun storeKey(key: PrivateKey, encryptedFile: ByteArray, options: ImportOptions, origin: KeyOrigin): ImportOutcome {
        val public = key.publicKey
        OpenSshKeyFile.refusalFor(public)?.let { return ImportOutcome.Rejected(it) }
        val storage = placement(public, generated = origin is KeyOrigin.Generated)
        val id = public.fingerprint
        val info = SshKeyInfo(
            id = KeyId(id), type = public.type, publicKey = public.authorizedKeysLine(), label = public.label,
            comment = options.comment ?: key.comment, storage = storage, policy = options.policy, origin = origin,
            can = capabilities(public), createdAt = clock.now(),
        )
        var alias: String? = null
        var sealed: String? = null
        if (storage.level < 3) {
            val backend = chip ?: return ImportOutcome.Failed("no-chip")
            if (!backend.device.screenLockSet) return ImportOutcome.NoScreenLock
            alias = ALIAS_PREFIX + id.removePrefix("SHA256:").filter { it.isLetterOrDigit() }.take(16)
            // Level 2 seals the file: the passphrase-encrypted one as a second lock, else one in the clear.
            val toSeal = if (options.keepPassphrase) encryptedFile.copyOf() else OpenSshKeyFile.write(key, null)
            val result = try {
                backend.store(alias, storage, key, toSeal, options.policy, UseReason(null, info, KeyOperation.Import))
            } finally {
                toSeal.fill(0)
            }
            when (result) {
                is ChipResult.Ok -> sealed = result.value
                ChipResult.Cancelled -> return ImportOutcome.Cancelled
                ChipResult.NoScreenLock -> return ImportOutcome.NoScreenLock
                ChipResult.Busy -> return ImportOutcome.Failed("busy")
                ChipResult.Unusable -> return ImportOutcome.Failed("chip")
                is ChipResult.Failed -> return ImportOutcome.Failed(result.code, result.evidence)
            }
        }
        val record = KeyRecord(
            id = id,
            publicKey = public.authorizedKeysLine(),
            comment = info.comment,
            storage = storage,
            policy = options.policy,
            origin = origin,
            createdAt = info.createdAt,
            alias = alias,
            sealed = sealed,
            file = if (storage.level == 3) Base64.encode(encryptedFile) else null,
            keepPassphrase = options.keepPassphrase && storage.level == 2,
        )
        update { f -> f.copy(keys = f.keys + record) }
        journal(UseRecord(clock.now(), null, KeyId(id), if (origin is KeyOrigin.Generated) KeyOperation.Generate else KeyOperation.Import, outcome = "ok"))
        return ImportOutcome.Imported(info(record)!!)
    }

    private suspend fun generateKey(kind: GeneratedKind, options: ImportOptions, passphrase: CharArray?): ImportOutcome {
        when (kind) {
            GeneratedKind.Ed25519 -> {
                val seed = random(32)
                val key = PrivateKey.ed25519(seed, options.comment ?: "unitool")
                seed.fill(0)
                return key.use {
                    val level = placement(it.publicKey, generated = true).level
                    val file = if (level == 3) {
                        val chars = passphrase ?: return ImportOutcome.NeedsNewPassphrase(0)
                        if (chars.size < MIN_PASSPHRASE) return ImportOutcome.NeedsNewPassphrase(chars.size)
                        SecretBytes.utf8(chars).use { secret -> OpenSshKeyFile.write(it, secret, random = random) }
                    } else {
                        OpenSshKeyFile.write(it, null)
                    }
                    try {
                        storeKey(it, file, options.copy(keepPassphrase = false), KeyOrigin.Generated(attested = false))
                    } finally {
                        file.fill(0)
                    }
                }
            }
            GeneratedKind.EcdsaP256 -> {
                val backend = chip ?: return ImportOutcome.Failed("no-chip")
                if (!backend.device.screenLockSet) return ImportOutcome.NoScreenLock
                val alias = ALIAS_PREFIX + "gen" + random(6).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
                val placeholder = SshKeyInfo(
                    KeyId("SHA256:new"), "ecdsa-sha2-nistp256", "", "ECDSA 256", options.comment ?: "unitool",
                    KeyStorage.StrongBox, options.policy, KeyOrigin.Generated(true), setOf(KeyCapability.Sign), clock.now(),
                )
                return when (val made = backend.generate(alias, kind, options.policy, UseReason(null, placeholder, KeyOperation.Generate))) {
                    is ChipResult.Ok -> {
                        val (public, storage) = made.value
                        val record = KeyRecord(
                            id = public.fingerprint, publicKey = public.authorizedKeysLine(), comment = placeholder.comment,
                            storage = storage, policy = options.policy, origin = KeyOrigin.Generated(attested = storage != KeyStorage.SecureEnclave),
                            createdAt = clock.now(), alias = alias,
                        )
                        update { f -> f.copy(keys = f.keys + record) }
                        journal(UseRecord(clock.now(), null, KeyId(record.id), KeyOperation.Generate, outcome = "ok"))
                        ImportOutcome.Imported(info(record)!!)
                    }
                    ChipResult.Cancelled -> ImportOutcome.Cancelled
                    ChipResult.NoScreenLock -> ImportOutcome.NoScreenLock
                    is ChipResult.Failed -> ImportOutcome.Failed(made.code, made.evidence)
                    else -> ImportOutcome.Failed("chip")
                }
            }
        }
    }

    /** `ssh-keygen -p` (05 §2.4): level 3 only; the old passphrase is asked for, the new one written. */
    private suspend fun rekeyFile(id: KeyId, newPassphrase: CharArray): SshOutcome<Unit> {
        val record = record(id) ?: return SshOutcome.NoKey
        if (record.storage.level != 3) return SshOutcome.NotAllowed("not-level-3")
        if (newPassphrase.size < MIN_PASSPHRASE) return SshOutcome.NotAllowed("short-passphrase")
        val info = info(record) ?: return SshOutcome.Failed("record")
        val bytes = Base64.decode(record.file ?: return SshOutcome.Failed("record"))
        val parsed = OpenSshKeyFile.parse(bytes) as? OpenSshKeyFile.Parsed.Ok ?: return SshOutcome.Failed("record")
        val key = when (val opened = openWithPassphrase(parsed.file, UseReason(null, info, KeyOperation.Rekey))) {
            is SshOutcome.Ok -> opened.value
            else -> return opened.cast()
        }
        val written = key.use { SecretBytes.utf8(newPassphrase).use { secret -> OpenSshKeyFile.write(key, secret, random = random) } }
        update { f -> f.copy(keys = f.keys.map { if (it.id == record.id) it.copy(file = Base64.encode(written)) else it }) }
        written.fill(0)
        journal(UseRecord(clock.now(), null, id, KeyOperation.Rekey, outcome = "ok"))
        return SshOutcome.Ok(Unit)
    }

    // ------------------------------------------------------------ SshKeyring, one per app

    private inner class AppKeyring(private val app: AppId, private val brick: String?) : SshKeyring {

        override val keys: StateFlow<List<SshKeyInfo>> = combineKeys()

        private fun combineKeys(): StateFlow<List<SshKeyInfo>> {
            fun granted(): List<SshKeyInfo> {
                val ids = file.grants.filter { it.app == app && it.brick == brick }.map { it.key }.toSet()
                return infos().filter { it.id in ids }
            }
            return _grants.map { granted() }.stateIn(scope, SharingStarted.Eagerly, granted())
        }

        private fun hasGrant(key: KeyId, purpose: KeyPurpose) =
            file.grants.any { it.app == app && it.key == key && it.purpose == purpose && it.brick == brick }

        private suspend fun addGrant(key: KeyId, purpose: KeyPurpose) {
            if (hasGrant(key, purpose)) return
            update { f -> f.copy(grants = f.grants + Grant(app, key, purpose, brick, clock.now())) }
            journal(UseRecord(clock.now(), app, key, KeyOperation.Grant, (purpose as? KeyPurpose.Sign)?.namespace, outcome = "ok"))
        }

        private fun allowed(purpose: KeyPurpose): SshOutcome<Unit>? {
            if (purpose is KeyPurpose.Sign) {
                val ns = purpose.namespace
                if (!SshSig.NAMESPACE.matches(ns)) return SshOutcome.NotAllowed("namespace")
                if (ns.endsWith("@m42.cx")) return SshOutcome.NotAllowed("reserved-namespace")
            }
            return null
        }

        private fun fits(info: SshKeyInfo, purpose: KeyPurpose) = info.usable && when (purpose) {
            is KeyPurpose.Sign -> KeyCapability.Sign in info.can
            KeyPurpose.Decrypt -> KeyCapability.DecryptAge in info.can
        }

        override suspend fun choose(purpose: KeyPurpose): SshOutcome<SshKeyInfo> {
            allowed(purpose)?.let { return it.cast() }
            if (!sheet.tryLock()) return SshOutcome.Busy
            try {
                val candidates = infos().filter { fits(it, purpose) }
                if (candidates.isEmpty()) return SshOutcome.NoKey
                val picked = prompter.choose(app, purpose, candidates) ?: return SshOutcome.Refused
                val info = candidates.firstOrNull { it.id == picked } ?: return SshOutcome.Refused
                addGrant(info.id, purpose)
                return SshOutcome.Ok(info)
            } finally {
                sheet.unlock()
            }
        }

        override suspend fun sign(key: KeyId, namespace: String, message: ByteArray): SshOutcome<SshSignature> {
            val purpose = KeyPurpose.Sign(namespace)
            allowed(purpose)?.let { return it.cast() }
            val record = record(key) ?: return SshOutcome.NoKey
            val info = info(record) ?: return SshOutcome.NoKey
            if (!sheet.tryLock()) return SshOutcome.Busy
            try {
                if (!hasGrant(key, purpose)) {
                    if (!prompter.grant(app, info, purpose)) {
                        journal(UseRecord(clock.now(), app, key, KeyOperation.Sign, namespace, digest(message), "refused"))
                        return SshOutcome.Refused
                    }
                    addGrant(key, purpose)
                }
                val reason = UseReason(app, info, KeyOperation.Sign, namespace, digest(message))
                val result = sshsig(record, namespace, message, reason)
                journal(UseRecord(clock.now(), app, key, KeyOperation.Sign, namespace, reason.digest, outcomeText(result)))
                return result
            } finally {
                sheet.unlock()
            }
        }

        override suspend fun decrypt(file: ByteArray): SshOutcome<ByteArray> {
            val candidates = recipientsOf(file) ?: return SshOutcome.Failed("not-age")
            val usable = candidates.filter { info(it)?.usable == true }
            if (usable.isEmpty()) return if (candidates.isEmpty()) SshOutcome.NoKey else SshOutcome.Unusable
            if (!sheet.tryLock()) return SshOutcome.Busy
            try {
                val record = usable.firstOrNull { hasGrant(KeyId(it.id), KeyPurpose.Decrypt) } ?: usable.first()
                val id = KeyId(record.id)
                val info = info(record)!!
                if (!hasGrant(id, KeyPurpose.Decrypt)) {
                    if (!prompter.grant(app, info, KeyPurpose.Decrypt)) {
                        journal(UseRecord(clock.now(), app, id, KeyOperation.Decrypt, null, digest(file), "refused"))
                        return SshOutcome.Refused
                    }
                    addGrant(id, KeyPurpose.Decrypt)
                }
                val reason = UseReason(app, info, KeyOperation.Decrypt, null, digest(file))
                val result = ageDecrypt(record, file, reason)
                journal(UseRecord(clock.now(), app, id, KeyOperation.Decrypt, null, reason.digest, outcomeText(result)))
                return result
            } finally {
                sheet.unlock()
            }
        }
    }

    // ------------------------------------------------------------ for the agent

    internal fun recordByBlob(blob: ByteArray): KeyRecord? =
        file.keys.firstOrNull { SshPublicKey.parse(it.publicKey)?.blob?.contentEquals(blob) == true }

    internal fun infoOf(record: KeyRecord): SshKeyInfo? = info(record)

    /** A raw signature for the agent (02 §2): the host asks, the person confirms. */
    internal suspend fun agentSign(record: KeyRecord, data: ByteArray, rsaHash: Hash): SshOutcome<ByteArray> {
        val key = SshPublicKey.parse(record.publicKey) ?: return SshOutcome.Failed("record")
        val info = info(record) ?: return SshOutcome.Failed("record")
        val reason = UseReason(null, info, KeyOperation.AgentSign, null, digest(data))
        val result = use(
            record,
            reason,
            softwareOp = { private -> SoftwareOps.signatureBlob(private, data, rsaHash) },
            chipOp = { alias -> chip!!.sign(alias, key, data, rsaHash, reason) },
        )
        journal(UseRecord(clock.now(), null, KeyId(record.id), KeyOperation.AgentSign, null, reason.digest, outcomeText(result)))
        return result
    }

    internal suspend fun confirm(reason: UseReason): Boolean = prompter.confirm(reason)

    /** The keys sessions hold open, for I15's test: closed means wiped. */
    internal fun openSessionsForTest(): List<SecretBytes> = sessions.values.map { it.key.fields }

    companion object {
        const val ALIAS_PREFIX = "cx.m42.services.ssh."
        const val JOURNAL = 200
        const val MAX_TRIES = 3
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <T> SshOutcome<*>.cast(): SshOutcome<T> = this as SshOutcome<T>
