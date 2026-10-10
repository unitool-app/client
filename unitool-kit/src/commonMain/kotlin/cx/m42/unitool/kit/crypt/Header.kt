package cx.m42.unitool.kit.crypt

import cx.m42.unitool.kit.crypto.ChaCha20Poly1305
import cx.m42.unitool.kit.crypto.Hash
import cx.m42.unitool.kit.crypto.SecretBytes
import cx.m42.unitool.kit.crypto.hmac
import cx.m42.unitool.kit.split.Split
import cx.m42.unitool.kit.split.SplitPart
import cx.m42.unitool.kit.split.SplitPlan
import cx.m42.unitool.kit.split.SplitProblem

/** What [Ucrypt.inspect] found. */
public sealed interface Inspected {
    public class Ok(public val header: Header) : Inspected

    /** No ucrypt magic. */
    public data object NotUcrypt : Inspected

    /** A version or a feature this code does not read; [missing] names the feature when it is one. */
    public class Newer(public val major: Int, public val minor: Int, public val missing: String?) : Inspected

    public class Damaged(public val what: String) : Inspected
}

/**
 * A parsed header: everything crypt/01 §1 lists, plus the records. Until [unlock] has checked the
 * MAC, none of it is verified — `inspect` without a key shows it as such.
 */
public class Header internal constructor(
    internal val bytes: ByteArray,
    public val major: Int,
    public val minor: Int,
    public val headerSize: Int,
    public val payloadOffset: Long,
    public val payloadSize: Long,
    /** Bytes the file decrypts to: the useful data. */
    public val plaintextSize: Long,
    public val chunkSize: Int,
    public val kdf: Int,
    public val aead: Int,
    public val keyBits: Int,
    internal val salt: ByteArray,
    /** The split recipient, when the file is opened by parts. */
    public val split: SplitRecord?,
    /** The recipient record types, in order: `0x0008` for parts, `0x0001`/`0x0002` for SSH keys. */
    public val recipientTypes: List<Int>,
    /** Record types a newer writer added and this reader skipped. */
    public val skippedTypes: List<Int>,
    internal val metaSealed: ByteArray?,
) {
    /** The first 8 bytes of the file's salt as hex: an id for the file that says nothing about it. */
    public val fileId: String get() = salt.copyOf(8).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** How many chunks the sizes say there are (01 §1). */
    public val chunkCount: Long get() = Ucrypt.chunkCount(plaintextSize, chunkSize)

    /** Whether the file carries metadata (a name, a time); what it says is known only after [unlock]. */
    public val hasMeta: Boolean get() = metaSealed != null

    /**
     * Opens the file key from [parts] and checks the header with it (01 §6, steps 3–5).
     *
     * With more parts than it takes, every combination of k is tried, so one damaged or forged part
     * among them is left out and named in [Unlocked.Ok.disagreeing].
     */
    public fun unlock(parts: List<SplitPart>): Unlocked {
        val record = split ?: return Unlocked.NotSplit
        SplitPlan.check(record.setId, record.k, record.n, parts)?.let { return Unlocked.Parts(it) }
        val found = Split.search(record.k, parts) { keyFits(it) } ?: return Unlocked.DoNotMatch
        return found.key.use { key ->
            when (val opened = open(key)) {
                is Unlocked.Ok -> Unlocked.Ok(opened.decryptor, found.disagreeing)
                else -> opened
            }
        }
    }

    /** Steps 4–5 and the metadata, with the file key at hand. [fileKey] stays the caller's. */
    internal fun open(fileKey: SecretBytes): Unlocked {
        val keys = Keys.derive(fileKey, salt)
        try {
            if (!macMatches(keys.mac)) return Unlocked.DoNotMatch
            // Only now are the sizes the writer's: they were under the MAC.
            if (payloadOffset != headerSize.toLong()) return Unlocked.Damaged("the content does not start right after the header")
            if (plaintextSize !in 0..Ucrypt.MAX_PLAINTEXT) return Unlocked.Damaged("a plaintext size of $plaintextSize")
            if (payloadSize != plaintextSize + Ucrypt.TAG * chunkCount) return Unlocked.Damaged("the content size disagrees with the plaintext size")
            val meta = metaSealed?.let { sealed ->
                val inside = ChaCha20Poly1305.open(keys.meta.bytes, ByteArray(12), sealed) ?: return Unlocked.Damaged("the metadata fails its tag")
                Meta.parse(inside) ?: return Unlocked.Damaged("the metadata is malformed or names a path")
            } ?: Meta()
            return Unlocked.Ok(Decryptor(this, keys.payload.copy(), meta), emptyList())
        } finally {
            keys.close()
        }
    }

    /** Whether [fileKey] is this file's: the MAC its header key gives matches. */
    private fun keyFits(fileKey: SecretBytes): Boolean = Keys.derive(fileKey, salt).use { macMatches(it.mac) }

    private fun macMatches(macKey: SecretBytes): Boolean {
        val expected = hmac(Hash.SHA256, macKey.bytes, bytes.copyOf(headerSize - Ucrypt.MAC_SIZE))
        val actual = bytes.copyOfRange(headerSize - Ucrypt.MAC_SIZE, headerSize)
        // In constant time: how many bytes differ, never where the first one is.
        var difference = 0
        for (i in expected.indices) difference = difference or (expected[i].toInt() xor actual[i].toInt())
        return difference == 0
    }
}

/** What [Header.unlock] found. */
public sealed interface Unlocked {
    /** The parts fit. [disagreeing] are the indices of given parts that do not belong to this key. */
    public class Ok(public val decryptor: Decryptor, public val disagreeing: List<Int>) : Unlocked

    /** The file is not opened by parts: it is for SSH keys. */
    public data object NotSplit : Unlocked

    /** The parts cannot be joined yet. */
    public class Parts(public val problem: SplitProblem) : Unlocked

    /** The parts make a key, and it is not this file's: a part is damaged or forged, or the header was changed. */
    public data object DoNotMatch : Unlocked

    /** The header is the writer's, and what it says is impossible. */
    public class Damaged(public val what: String) : Unlocked
}

/** What [Decryptor.decrypt] found. */
public sealed interface Decrypted {
    public class Ok(public val plaintext: ByteArray) : Decrypted

    /** The file is not as long as its header says: cut short, or something added after. */
    public class WrongLength(public val expected: Long, public val actual: Long) : Decrypted

    /** Chunk [index] fails its tag: changed, or out of place. */
    public class BadChunk(public val index: Long) : Decrypted
}

/** The content of an unlocked file, chunk by chunk or whole. [close] zeroes the key. */
public class Decryptor internal constructor(
    public val header: Header,
    private val payloadKey: SecretBytes,
    /** Verified: it was under the MAC and its own tag. */
    public val meta: Meta,
) : AutoCloseable {

    /** Where chunk [index] lies in the file (01 §3), to read only what is needed. */
    public fun location(index: Long): LongRange {
        val count = header.chunkCount
        require(index in 0 until count) { "chunk $index of $count" }
        val start = header.payloadOffset + index * (header.chunkSize + Ucrypt.TAG)
        val size = if (index < count - 1) header.chunkSize.toLong() else header.plaintextSize - (count - 1) * header.chunkSize
        return start until start + size + Ucrypt.TAG
    }

    /** The plaintext of chunk [index], or null when it fails its tag. */
    public fun chunk(index: Long, sealed: ByteArray): ByteArray? =
        ChaCha20Poly1305.open(payloadKey.bytes, Ucrypt.nonce(index, last = index == header.chunkCount - 1), sealed)

    /** The whole file at once: its length first, then every chunk in order. */
    public fun decrypt(file: ByteArray): Decrypted {
        val expected = header.payloadOffset + header.payloadSize
        if (file.size.toLong() != expected) return Decrypted.WrongLength(expected, file.size.toLong())
        val out = ByteArray(header.plaintextSize.toInt())
        var at = 0
        for (index in 0 until header.chunkCount) {
            val range = location(index)
            val plain = chunk(index, file.copyOfRange(range.first.toInt(), range.last.toInt() + 1))
            if (plain == null) {
                out.fill(0)
                return Decrypted.BadChunk(index)
            }
            plain.copyInto(out, at)
            at += plain.size
            plain.fill(0)
        }
        return Decrypted.Ok(out)
    }

    override fun close() {
        payloadKey.close()
    }
}

/**
 * The encrypted metadata (crypt/01 §2.2): what the file was called, when it was changed, its mode.
 * All optional; text from the CLI has none of them.
 */
public class Meta(
    public val name: String? = null,
    /** Seconds since the Unix epoch. */
    public val modified: Long? = null,
    /** Only the bits `0777`. */
    public val mode: Int? = null,
) {
    init {
        name?.let { require(isSafeName(it)) { "a file name, not a path: '$it'" } }
        mode?.let { require(it in 0..0x1ff) { "mode bits beyond 0777: ${it.toString(8)}" } }
    }

    internal val isEmpty: Boolean get() = name == null && modified == null && mode == null

    internal fun seal(key: SecretBytes): ByteArray {
        val tlv = mutableListOf<Byte>()
        fun put(tag: Int, value: ByteArray) {
            tlv += tag.toByte()
            tlv += (value.size ushr 8).toByte()
            tlv += value.size.toByte()
            tlv += value.toList()
        }
        name?.let { put(NAME, it.encodeToByteArray()) }
        modified?.let { put(MODIFIED, ByteArray(8).apply { putLong(0, it) }) }
        mode?.let { put(MODE, ByteArray(2).apply { putShort(0, it) }) }
        // meta_key is the file's own, so the all-zero nonce is used exactly once.
        return ChaCha20Poly1305.seal(key.bytes, ByteArray(12), tlv.toByteArray())
    }

    internal companion object {
        const val NAME = 0x01
        const val MODIFIED = 0x02
        const val MODE = 0x03

        /** The last component of a path only (D302): no '/', no NUL, not `.` or `..`, at most 255 bytes. */
        fun isSafeName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name &&
                name.encodeToByteArray().size <= 255

        /** Unknown tags are skipped; a known one of the wrong size, or a name that is a path, is refused. */
        fun parse(tlv: ByteArray): Meta? {
            var name: String? = null
            var modified: Long? = null
            var mode: Int? = null
            var at = 0
            while (at < tlv.size) {
                if (at + 3 > tlv.size) return null
                val tag = tlv[at].toInt() and 0xff
                val length = tlv.short(at + 1)
                at += 3
                if (at + length > tlv.size) return null
                val value = tlv.copyOfRange(at, at + length)
                at += length
                when (tag) {
                    NAME -> name = value.decodeToString().takeIf { isSafeName(it) && it.encodeToByteArray().contentEquals(value) } ?: return null
                    MODIFIED -> modified = value.takeIf { it.size == 8 }?.long(0) ?: return null
                    MODE -> mode = value.takeIf { it.size == 2 }?.short(0)?.and(0x1ff) ?: return null
                }
            }
            return Meta(name, modified, mode)
        }
    }
}

/** The strict parse of 01 §6, steps 1–2, and the version rules of 01 §5.2. */
internal object HeaderReader {

    fun read(file: ByteArray): Inspected {
        if (!Ucrypt.isUcrypt(file)) return Inspected.NotUcrypt
        if (file.size < 16) return Inspected.Damaged("the file ends inside its preamble")
        val major = file[8].toInt() and 0xff
        val minor = file[9].toInt() and 0xff
        if (major > Ucrypt.MAJOR) return Inspected.Newer(major, minor, null)
        if (major < Ucrypt.MAJOR) return Inspected.Damaged("major version $major")
        val newer = minor > Ucrypt.MINOR
        fun unknown(what: String): Inspected = if (newer) Inspected.Newer(major, minor, what) else Inspected.Damaged("$what in a $major.$minor file")

        val flags = file.short(0x0A)
        val headerSize = file.int(0x0C)
        if (headerSize !in Ucrypt.MIN_HEADER..Ucrypt.MAX_HEADER) return Inspected.Damaged("a header of $headerSize bytes")
        if (file.size < headerSize) return Inspected.Damaged("the file ends inside its header")
        if (flags != 0) return unknown("flags ${flags.toString(16)}")

        val kdf = file.short(0x2E)
        val aead = file.short(0x30)
        val keyBits = file.short(0x32)
        if (kdf != Ucrypt.KDF_HKDF_SHA256) return unknown("KDF $kdf")
        if (aead != Ucrypt.AEAD_CHACHA20_POLY1305) return unknown("AEAD $aead")
        if (keyBits != Ucrypt.KEY_BITS) return unknown("a $keyBits-bit key")
        val chunkSize = file.int(0x28)
        if (chunkSize !in Ucrypt.MIN_CHUNK..Ucrypt.MAX_CHUNK || chunkSize and (chunkSize - 1) != 0L) {
            return Inspected.Damaged("chunks of $chunkSize bytes")
        }
        val recordCount = file.short(0x2C)
        if (recordCount > Ucrypt.MAX_RECORDS) return Inspected.Damaged("$recordCount records")

        var split: SplitRecord? = null
        var metaSealed: ByteArray? = null
        val recipients = mutableListOf<Int>()
        val skipped = mutableListOf<Int>()
        val end = headerSize.toInt() - Ucrypt.MAC_SIZE
        var at = Ucrypt.PREAMBLE
        repeat(recordCount) {
            if (at + 6 > end) return Inspected.Damaged("a record runs past the header")
            val type = file.short(at)
            val size = file.int(at + 2)
            if (size > Ucrypt.MAX_RECORD || at + 6 + size > end) return Inspected.Damaged("a record of $size bytes runs past the header")
            val body = file.copyOfRange(at + 6, at + 6 + size.toInt())
            at += 6 + size.toInt()
            when (type) {
                RecordType.SPLIT -> {
                    if (split != null) return Inspected.Damaged("two split records")
                    split = SplitRecord.parse(body) ?: return Inspected.Damaged("a malformed split record")
                    recipients += type
                }
                RecordType.META -> {
                    if (metaSealed != null) return Inspected.Damaged("two metadata records")
                    if (body.size < Ucrypt.TAG) return Inspected.Damaged("a metadata record shorter than its tag")
                    metaSealed = body
                }
                in RecordType.RECIPIENTS -> recipients += type
                else -> when {
                    type and RecordType.CRITICAL != 0 -> return unknown("record type 0x${type.toString(16).padStart(4, '0')}")
                    !newer -> return Inspected.Damaged("record type 0x${type.toString(16).padStart(4, '0')} in a $major.$minor file")
                    else -> skipped += type
                }
            }
        }
        if (at != end) return Inspected.Damaged("the records end before the MAC")
        if (recipients.isEmpty()) {
            return if (skipped.isNotEmpty()) Inspected.Newer(major, minor, "a recipient of a newer kind") else Inspected.Damaged("a file for nobody")
        }
        return Inspected.Ok(
            Header(
                bytes = file.copyOf(headerSize.toInt()),
                major = major,
                minor = minor,
                headerSize = headerSize.toInt(),
                payloadOffset = file.long(0x10),
                payloadSize = file.long(0x18),
                plaintextSize = file.long(0x20),
                chunkSize = chunkSize.toInt(),
                kdf = kdf,
                aead = aead,
                keyBits = keyBits,
                salt = file.copyOfRange(0x34, 0x54),
                split = split,
                recipientTypes = recipients,
                skippedTypes = skipped,
                metaSealed = metaSealed,
            ),
        )
    }
}
