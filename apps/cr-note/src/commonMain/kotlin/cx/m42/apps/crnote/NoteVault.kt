package cx.m42.apps.crnote

import cx.m42.services.vault.PrivateFiles
import cx.m42.unitool.kit.crypto.AesGcm
import cx.m42.unitool.kit.crypto.hkdfSha256
import cx.m42.unitool.kit.crypto.secureRandomBytes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One note. [id] is random and only this app's: it never leaves in an export. */
@Serializable
internal data class Note(val id: String, val text: String, val created: Long, val modified: Long) {
    /** The first line that has anything on it: what the list shows (D657). */
    val title: String get() = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""

    override fun toString(): String = "Note($id, ${text.length} chars)"
}

/**
 * One PIN's directory as it is stored (D662): its notes, and the names of the decoys that are its
 * own (D665) — the only decoys it ever touches again, because no other file can be told apart from
 * another PIN's directory.
 */
@Serializable
internal class StoredDirectory(val notes: List<Note> = emptyList(), val decoys: List<String> = emptyList())

/**
 * A directory opened with a PIN: its file name, its key, and what it holds. [onDisk] is false for a
 * PIN that has never saved anything — no file is written until the first note is (D653).
 */
internal class OpenDirectory(
    val name: String,
    val slot: ByteArray,
    val key: ByteArray,
    val onDisk: Boolean,
    val decoys: List<String>,
) {
    fun wipe() {
        slot.fill(0)
        key.fill(0)
    }
}

/**
 * What cr-note keeps on disk, and how (D653, D655, D662, D664, D665). Nothing in here is open:
 *
 * - `k` — the root key R, 32 bytes, sealed to the chip key behind the face ([cx.m42.services.vault.BiometricKey]);
 * - `<32 hex>` — a directory: `nonce ‖ AES-256-GCM(K, length ‖ JSON ‖ random)`, the slot as AAD, the
 *   whole file a multiple of 16 KiB; its name is `HKDF(R, "cr-note slot" ‖ PIN)`, its key
 *   `HKDF(R, "cr-note key" ‖ PIN)`;
 * - `<32 hex>` — a decoy: random bytes of the same sizes, with names of the same kind.
 *
 * R is the caller's; this class only borrows it. It never reads a file it cannot open as anything
 * but "not mine", and never writes or deletes one that is not its own — except [wipeAll], which
 * deletes everything.
 */
internal class NoteVault(
    private val files: PrivateFiles,
    private val random: (Int) -> ByteArray = ::secureRandomBytes,
) {

    suspend fun hasRoot(): Boolean = files.read(KEY_FILE) != null

    suspend fun sealedRoot(): ByteArray? = files.read(KEY_FILE)

    /** A new install's files, or the files after a wipe: the sealed root and the first decoys (D664). */
    suspend fun start(sealedRoot: ByteArray) {
        files.write(KEY_FILE, sealedRoot)
        repeat(FIRST_DECOYS_MIN + randomBelow(FIRST_DECOYS_SPREAD)) { files.write(newName(), random(BLOCK)) }
    }

    /** Everything, real and decoy, the sealed root with it (D655, D656). */
    suspend fun wipeAll() {
        files.list().forEach { files.delete(it) }
    }

    /** [pin]'s directory: its notes, or none when it has never saved one, or when the file is not its (D653). */
    suspend fun open(root: ByteArray, pin: String): Pair<OpenDirectory, List<Note>> {
        val empty = fresh(root, pin)
        val bytes = files.read(empty.name)
        val stored = bytes?.let { decrypt(empty.key, empty.slot, it) }
        bytes?.fill(0)
        return OpenDirectory(empty.name, empty.slot, empty.key, onDisk = bytes != null, decoys = stored?.decoys ?: emptyList()) to
            (stored?.notes ?: emptyList())
    }

    /**
     * [notes] written as [directory]'s file. The first time, with one to four decoys of its own made
     * beside it; every time, one or two of them refreshed to the new file's size, so that between two
     * looks at the disk more than one file has changed (D664, D665). Returns the directory as it now is.
     */
    suspend fun save(directory: OpenDirectory, notes: List<Note>): OpenDirectory {
        val decoys = if (directory.onDisk && directory.decoys.isNotEmpty()) {
            directory.decoys
        } else {
            List(1 + randomBelow(OWN_DECOYS_SPREAD)) { newName() }
        }
        val file = encrypt(directory.key, directory.slot, StoredDirectory(notes, decoys))
        files.write(directory.name, file)
        val size = file.size
        file.fill(0)
        val refresh = if (directory.onDisk) decoys.shuffled(kotlin.random.Random(seed())).take(1 + randomBelow(2)) else decoys
        refresh.forEach { files.write(it, random(size)) }
        return OpenDirectory(directory.name, directory.slot, directory.key, onDisk = true, decoys = decoys)
    }

    private fun encrypt(key: ByteArray, slot: ByteArray, directory: StoredDirectory): ByteArray {
        val json = JSON.encodeToString(StoredDirectory.serializer(), directory).encodeToByteArray()
        val needed = LENGTH_SIZE + json.size + NONCE_SIZE + AesGcm.TAG_SIZE
        val blocks = (needed + BLOCK - 1) / BLOCK
        val plain = ByteArray(blocks * BLOCK - NONCE_SIZE - AesGcm.TAG_SIZE)
        plain[0] = (json.size ushr 24).toByte()
        plain[1] = (json.size ushr 16).toByte()
        plain[2] = (json.size ushr 8).toByte()
        plain[3] = json.size.toByte()
        json.copyInto(plain, LENGTH_SIZE)
        val pad = random(plain.size - LENGTH_SIZE - json.size)
        pad.copyInto(plain, LENGTH_SIZE + json.size)
        json.fill(0)
        val nonce = random(NONCE_SIZE)
        val sealed = AesGcm.seal(key, nonce, plain, slot)
        plain.fill(0)
        return nonce + sealed
    }

    private fun decrypt(key: ByteArray, slot: ByteArray, file: ByteArray): StoredDirectory? {
        if (file.size < NONCE_SIZE + AesGcm.TAG_SIZE + LENGTH_SIZE) return null
        val plain = AesGcm.open(key, file.copyOfRange(0, NONCE_SIZE), file.copyOfRange(NONCE_SIZE, file.size), slot) ?: return null
        try {
            val length = ((plain[0].toInt() and 0xFF) shl 24) or ((plain[1].toInt() and 0xFF) shl 16) or
                ((plain[2].toInt() and 0xFF) shl 8) or (plain[3].toInt() and 0xFF)
            if (length < 0 || length > plain.size - LENGTH_SIZE) return null
            val json = plain.copyOfRange(LENGTH_SIZE, LENGTH_SIZE + length)
            return runCatching { JSON.decodeFromString(StoredDirectory.serializer(), json.decodeToString()) }.getOrNull()
                .also { json.fill(0) }
        } finally {
            plain.fill(0)
        }
    }

    private fun newName(): String = hex(random(SLOT_SIZE))

    private fun randomBelow(bound: Int): Int = ((random(1)[0].toInt() and 0xFF) % bound)

    private fun seed(): Long = random(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

    companion object {
        const val KEY_FILE = "k"
        const val ROOT_SIZE = 32
        const val PIN_LENGTH = 6

        /** The PIN that wipes everything (D655). It cannot be the one set at creation (D652). */
        const val WIPE_PIN = "178500"

        /** Every file is a multiple of this, so its length says nothing (D662). */
        const val BLOCK = 16 * 1024

        /** Eight decoys at the start, as the note says, and up to four more so the count is not a constant (D664, D665). */
        const val FIRST_DECOYS_MIN = 8
        const val FIRST_DECOYS_SPREAD = 5

        /** One to four decoys of a directory's own (D665). */
        const val OWN_DECOYS_SPREAD = 4

        private const val SLOT_SIZE = 16
        private const val KEY_SIZE = 32
        private const val NONCE_SIZE = 12
        private const val LENGTH_SIZE = 4
        private const val SLOT_INFO = "cr-note slot"
        private const val KEY_INFO = "cr-note key"

        private val JSON = Json { ignoreUnknownKeys = true }

        /** [pin]'s directory under [root] as it is before anything is read or written: its name and key. */
        fun fresh(root: ByteArray, pin: String): OpenDirectory {
            val slot = derive(root, SLOT_INFO, pin, SLOT_SIZE)
            return OpenDirectory(hex(slot), slot, derive(root, KEY_INFO, pin, KEY_SIZE), onDisk = false, decoys = emptyList())
        }

        fun derive(root: ByteArray, info: String, pin: String, size: Int): ByteArray =
            hkdfSha256(root, ByteArray(0), (info + pin).encodeToByteArray(), size)

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        fun isPin(text: String): Boolean = text.length == PIN_LENGTH && text.all { it in '0'..'9' }
    }
}
