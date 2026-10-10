package cx.m42.unitool.kit.crypt

import cx.m42.unitool.kit.crypto.ChaCha20Poly1305
import cx.m42.unitool.kit.crypto.Hash
import cx.m42.unitool.kit.crypto.SecretBytes
import cx.m42.unitool.kit.crypto.hkdfExpand
import cx.m42.unitool.kit.crypto.hkdfExtract
import cx.m42.unitool.kit.crypto.hmac
import cx.m42.unitool.kit.crypto.secureRandomBytes
import cx.m42.unitool.kit.split.Split
import cx.m42.unitool.kit.split.SplitPart
import kotlin.io.encoding.Base64

/**
 * `ucrypt`, Unitool's one encrypted-file format (.ai-notes/crypt 01, split D372): a binary header
 * whose sizes are numbers in known places, records for the recipients and the metadata, a MAC over
 * the header, and the content in ChaCha20-Poly1305 chunks that cannot be reordered, cut or added to.
 *
 * ```
 * preamble 84 · record … record · HMAC-SHA-256 32 │ chunk 0 · chunk 1 · … (each + 16-byte tag)
 * ```
 *
 * This is the core plus the first recipient, a key split into parts ([encryptSplit],
 * [Header.unlock]). The SSH recipients `0x0001`/`0x0002` come next with the same numbers
 * (crypt/03); a file for them is read here and said to be for keys this code cannot use yet.
 *
 * Pure functions on bytes: no files, no network. Whoever calls reads and writes.
 */
public object Ucrypt {
    public const val MAJOR: Int = 1
    public const val MINOR: Int = 0
    public const val EXTENSION: String = ".ucrypt"

    /** Bytes of plaintext in every chunk but the last. */
    public const val CHUNK_SIZE: Int = 65_536

    internal val MAGIC = byteArrayOf(0x89.toByte(), 0x55, 0x43, 0x52, 0x0D, 0x0A, 0x1A, 0x0A)
    internal const val PREAMBLE = 84
    internal const val MAC_SIZE = 32
    internal const val TAG = ChaCha20Poly1305.TAG_SIZE
    internal const val SALT_SIZE = 32
    internal const val KEY_SIZE = 32
    internal const val KDF_HKDF_SHA256 = 0x0001
    internal const val AEAD_CHACHA20_POLY1305 = 0x0003
    internal const val KEY_BITS = 256

    internal const val MIN_HEADER = PREAMBLE + MAC_SIZE
    internal const val MAX_HEADER = 1 shl 20
    internal const val MAX_RECORDS = 1024
    internal const val MAX_RECORD = 1 shl 16
    internal const val MIN_CHUNK = 1 shl 12
    internal const val MAX_CHUNK = 1 shl 24
    internal const val MAX_PLAINTEXT = 1L shl 62

    private const val ARMOR_BEGIN = "-----BEGIN UCRYPT FILE-----"
    private const val ARMOR_END = "-----END UCRYPT FILE-----"

    /** Whether [bytes] start like a ucrypt file of any version (01 §5.1). */
    public fun isUcrypt(bytes: ByteArray): Boolean = bytes.size >= MAGIC.size && bytes.copyOf(MAGIC.size).contentEquals(MAGIC)

    /**
     * Encrypts [plaintext] under a fresh 256-bit key and splits that key into [n] parts, any [k] of
     * which open the file (.ai-notes/done/split D360). The key itself is never written anywhere.
     *
     * Before returning, the file is opened again with the key still in memory, MAC and every tag
     * (crypt/01 §7.6), so a fault shows now rather than on the day the file is needed.
     */
    public fun encryptSplit(plaintext: ByteArray, k: Int, n: Int, meta: Meta = Meta()): SplitSealed =
        encryptSplit(plaintext, k, n, meta, CHUNK_SIZE)

    internal fun encryptSplit(plaintext: ByteArray, k: Int, n: Int, meta: Meta, chunkSize: Int): SplitSealed {
        SecretBytes(secureRandomBytes(KEY_SIZE)).use { fileKey ->
            val parts = Split.make(fileKey, k, n)
            val record = SplitRecord(parts.first().setId, k, n)
            val file = write(plaintext, fileKey, listOf(Record(RecordType.SPLIT, record.body())), meta, chunkSize)
            val again = (inspect(file) as? Inspected.Ok)?.header ?: error("ucrypt wrote a header it cannot read back")
            val opened = again.open(fileKey) as? Unlocked.Ok ?: error("ucrypt wrote a file its own key does not open")
            opened.decryptor.use { decryptor ->
                val back = decryptor.decrypt(file) as? Decrypted.Ok ?: error("ucrypt wrote chunks it cannot read back")
                check(back.plaintext.contentEquals(plaintext)) { "ucrypt read back something else than it wrote" }
                back.plaintext.fill(0)
            }
            return SplitSealed(file, parts)
        }
    }

    /**
     * Reads the header at the start of [file] without any key: the whole file, or at least its
     * header. The structure is checked strictly (01 §6, step 2); the sizes and the metadata are not
     * verified until [Header.unlock] has checked the MAC.
     */
    public fun inspect(file: ByteArray): Inspected = HeaderReader.read(file)

    /** The file as text for a messenger or a mail (01 §4): standard Base64, 64 columns, PEM lines. */
    public fun armor(file: ByteArray): String = buildString {
        append(ARMOR_BEGIN).append('\n')
        Base64.Default.encode(file).chunked(64).forEach { append(it).append('\n') }
        append(ARMOR_END).append('\n')
    }

    /** Whether [bytes] are the text form: the first non-blank characters are the BEGIN line. */
    public fun isArmored(bytes: ByteArray): Boolean {
        val start = bytes.indexOfFirst { it.toInt().toChar() !in " \t\r\n" }
        if (start < 0) return false
        val prefix = ARMOR_BEGIN.encodeToByteArray()
        return bytes.size - start >= prefix.size && bytes.copyOfRange(start, start + prefix.size).contentEquals(prefix)
    }

    /** The binary file inside the text form, or null when [bytes] are not a whole, valid armor. */
    public fun dearmor(bytes: ByteArray): ByteArray? {
        val lines = bytes.decodeToString().trim().lines().map { it.trimEnd('\r') }
        if (lines.size < 2 || lines.first() != ARMOR_BEGIN || lines.last() != ARMOR_END) return null
        val body = lines.subList(1, lines.size - 1)
        if (body.isEmpty() || body.dropLast(1).any { it.length != 64 } || body.last().length !in 1..64) return null
        val file = try {
            Base64.Default.decode(body.joinToString(""))
        } catch (_: IllegalArgumentException) {
            return null
        }
        // Canonical only: the same file has exactly one armor.
        return file.takeIf { armor(it).trimEnd() == lines.joinToString("\n") }
    }

    private fun write(plaintext: ByteArray, fileKey: SecretBytes, recipients: List<Record>, meta: Meta, chunkSize: Int): ByteArray {
        require(chunkSize in MIN_CHUNK..MAX_CHUNK && chunkSize and (chunkSize - 1) == 0) { "a chunk is a power of two from 4 KiB to 16 MiB" }
        require(recipients.isNotEmpty()) { "a file for nobody" }
        val salt = secureRandomBytes(SALT_SIZE)
        Keys.derive(fileKey, salt).use { keys ->
            val records = recipients + listOfNotNull(meta.takeUnless { it.isEmpty }?.let { Record(RecordType.META, it.seal(keys.meta)) })
            val headerSize = PREAMBLE + records.sumOf { 6 + it.body.size } + MAC_SIZE
            check(headerSize <= MAX_HEADER) { "a header of $headerSize bytes" }
            val chunks = chunkCount(plaintext.size.toLong(), chunkSize)
            val payloadSize = plaintext.size.toLong() + TAG * chunks
            val out = ByteArray((headerSize + payloadSize).toInt())
            MAGIC.copyInto(out, 0)
            out[8] = MAJOR.toByte()
            out[9] = MINOR.toByte()
            out.putShort(0x0A, 0)
            out.putInt(0x0C, headerSize)
            out.putLong(0x10, headerSize.toLong())
            out.putLong(0x18, payloadSize)
            out.putLong(0x20, plaintext.size.toLong())
            out.putInt(0x28, chunkSize)
            out.putShort(0x2C, records.size)
            out.putShort(0x2E, KDF_HKDF_SHA256)
            out.putShort(0x30, AEAD_CHACHA20_POLY1305)
            out.putShort(0x32, KEY_BITS)
            salt.copyInto(out, 0x34)
            var at = PREAMBLE
            for (record in records) {
                out.putShort(at, record.type)
                out.putInt(at + 2, record.body.size)
                record.body.copyInto(out, at + 6)
                at += 6 + record.body.size
            }
            hmac(Hash.SHA256, keys.mac.bytes, out.copyOf(at)).copyInto(out, at)
            at += MAC_SIZE
            for (i in 0 until chunks) {
                val from = (i * chunkSize.toLong()).toInt()
                val to = minOf(plaintext.size, from + chunkSize)
                ChaCha20Poly1305.seal(keys.payload.bytes, nonce(i, last = i == chunks - 1), plaintext.copyOfRange(from, to)).copyInto(out, at)
                at += to - from + TAG
            }
            check(at == out.size)
            return out
        }
    }

    /** max(1, ⌈size / chunk⌉): an empty file is one chunk of one tag (01 §3). */
    internal fun chunkCount(plaintextSize: Long, chunkSize: Int): Long =
        if (plaintextSize == 0L) 1 else (plaintextSize + chunkSize - 1) / chunkSize

    /** STREAM: the chunk number as 11 bytes big-endian, then 1 for the last chunk and 0 for the rest. */
    internal fun nonce(index: Long, last: Boolean): ByteArray {
        val nonce = ByteArray(12)
        for (i in 0 until 8) nonce[10 - i] = (index ushr (8 * i)).toByte()
        nonce[11] = if (last) 1 else 0
        return nonce
    }
}

/** A file and the parts of its key; the key itself is gone. */
public class SplitSealed(public val file: ByteArray, public val parts: List<SplitPart>)

/** The split recipient's record, `0x0008` (.ai-notes/done/split 02 §4): which set of parts opens the file. */
public class SplitRecord internal constructor(setId: ByteArray, public val k: Int, public val n: Int) {
    private val id = setId.copyOf()

    public val setId: ByteArray get() = id.copyOf()
    public val setIdHex: String get() = id.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    internal fun body(): ByteArray = id + byteArrayOf(k.toByte(), n.toByte())

    internal companion object {
        const val SIZE = SplitPart.SET_ID_SIZE + 2

        fun parse(body: ByteArray): SplitRecord? {
            if (body.size != SIZE) return null
            val k = body[8].toInt() and 0xff
            val n = body[9].toInt() and 0xff
            if (k < 2 || k > n) return null
            return SplitRecord(body.copyOf(8), k, n)
        }
    }
}

internal class Record(val type: Int, val body: ByteArray)

/** The record types of 1.x (crypt/01 §2.1, §2.3). Bit 15 is "critical". */
internal object RecordType {
    const val ED25519 = 0x0001
    const val RSA = 0x0002
    const val META = 0x0003
    const val SPLIT = 0x0008
    const val CRITICAL = 0x8000

    /** Known to 1.0 and saying who can open the file. */
    val RECIPIENTS = setOf(ED25519, RSA, SPLIT)
    val KNOWN = RECIPIENTS + META
}

/** The keys a file key gives, one per job (crypt/02 §1.3). */
internal class Keys private constructor(val payload: SecretBytes, val mac: SecretBytes, val meta: SecretBytes) : AutoCloseable {
    override fun close() {
        payload.close()
        mac.close()
        meta.close()
    }

    companion object {
        fun derive(fileKey: SecretBytes, salt: ByteArray): Keys {
            val prk = hkdfExtract(salt, fileKey.bytes)
            try {
                return Keys(
                    payload = SecretBytes(hkdfExpand(prk, "ucrypt/1 payload".encodeToByteArray(), 32)),
                    mac = SecretBytes(hkdfExpand(prk, "ucrypt/1 header".encodeToByteArray(), 32)),
                    meta = SecretBytes(hkdfExpand(prk, "ucrypt/1 meta".encodeToByteArray(), 32)),
                )
            } finally {
                prk.fill(0)
            }
        }
    }
}

internal fun ByteArray.putShort(at: Int, value: Int) {
    this[at] = (value ushr 8).toByte()
    this[at + 1] = value.toByte()
}

internal fun ByteArray.putInt(at: Int, value: Int) {
    for (i in 0 until 4) this[at + i] = (value ushr (24 - 8 * i)).toByte()
}

internal fun ByteArray.putLong(at: Int, value: Long) {
    for (i in 0 until 8) this[at + i] = (value ushr (56 - 8 * i)).toByte()
}

internal fun ByteArray.short(at: Int): Int = ((this[at].toInt() and 0xff) shl 8) or (this[at + 1].toInt() and 0xff)

internal fun ByteArray.int(at: Int): Long {
    var value = 0L
    for (i in 0 until 4) value = (value shl 8) or (this[at + i].toLong() and 0xff)
    return value
}

/** As unsigned, so a value with the top bit set shows up as negative and fails every limit. */
internal fun ByteArray.long(at: Int): Long {
    var value = 0L
    for (i in 0 until 8) value = (value shl 8) or (this[at + i].toLong() and 0xff)
    return value
}
