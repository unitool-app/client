package cx.m42.unitool.kit.qr

import cx.m42.unitool.kit.crypto.Sha256
import cx.m42.unitool.kit.crypto.secureRandomBytes

/**
 * A file cut into QR codes and put back (.ai-notes/qr-scanner D630): `unitool qr encode --file`
 * cuts, the phone's QR scanner gathers.
 *
 * Every code is one chunk, as text in the alphanumeric mode:
 * ```
 * UQR1:<set>:<i>:<n>:<data>
 * ```
 * - `set` — four random bytes in upper-case hex (eight characters), so chunks of two files never mix.
 *   Not base45: its alphabet has the `:` that separates the fields;
 * - `i` / `n` — the chunk's number from 1 and how many there are, in decimal;
 * - `data` — the chunk's bytes in [Base45].
 *
 * The bytes cut are a stream: the name's length (one byte), the name in UTF-8, the file's size (four
 * bytes, big-endian), its SHA-256, then the file. The header is in the first chunks rather than in a
 * chunk of its own, and the SHA-256 checks the whole file once every chunk is in; inside a chunk
 * the QR code's own Reed–Solomon catches what a camera misreads. Chunks come in any order.
 */
public object QrFile {
    public const val PREFIX: String = "UQR1:"

    /** More than this nobody scans off a screen, and the gathered chunks are kept in memory (D635). */
    public const val MAX_BYTES: Int = 1 shl 20

    /** About a version 22 code at L: dense, and still read off a screen without trouble (D631). */
    public const val DEFAULT_CHUNK: Int = 1000

    /** The chunk sizes allowed: the largest still fits a version 40 code at L with its prefix. */
    public val CHUNK_SIZES: IntRange = 50..2800

    private const val MAX_NAME_BYTES = 255
    private const val MAX_CHUNKS = 99_999
    private const val HASH = 32

    /**
     * [bytes] named [name] as chunks of [chunk] bytes, each the text of one QR code, in order. Only
     * the last part of [name] after a slash is kept, cut to 255 bytes of UTF-8.
     */
    public fun cut(name: String, bytes: ByteArray, chunk: Int = DEFAULT_CHUNK, set: ByteArray = secureRandomBytes(4)): List<String> {
        require(bytes.size <= MAX_BYTES) { "at most $MAX_BYTES bytes, not ${bytes.size}" }
        require(chunk in CHUNK_SIZES) { "a chunk is $CHUNK_SIZES bytes, not $chunk" }
        require(set.size == 4) { "a set is 4 bytes" }
        val nameBytes = cleanName(name).encodeToByteArray()
        val stream = byteArrayOf(nameBytes.size.toByte()) + nameBytes + int(bytes.size) + Sha256.digest(bytes) + bytes
        val count = (stream.size + chunk - 1) / chunk
        val setText = set.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.uppercase()
        return (0 until count).map { i ->
            val data = stream.copyOfRange(i * chunk, minOf(stream.size, (i + 1) * chunk))
            "$PREFIX$setText:${i + 1}:$count:${Base45.encode(data)}"
        }
    }

    /** How many codes [cut] would make: what the CLI says when a file is over [MAX_BYTES]. */
    public fun count(name: String, size: Long, chunk: Int = DEFAULT_CHUNK): Long =
        (1 + cleanName(name).encodeToByteArray().size + 4 + HASH + size + chunk - 1) / chunk

    /** [text] as a chunk, or null when it is not one. */
    public fun parse(text: String): QrFileChunk? {
        if (!text.startsWith(PREFIX)) return null
        val fields = text.substring(PREFIX.length).split(':', limit = 4)
        if (fields.size != 4 || fields[0].length != 8 || fields[0].any { it !in '0'..'9' && it !in 'A'..'F' }) return null
        val index = fields[1].toIntOrNull() ?: return null
        val count = fields[2].toIntOrNull() ?: return null
        if (count !in 1..MAX_CHUNKS || index !in 1..count) return null
        val data = Base45.decode(fields[3])?.takeIf { it.isNotEmpty() } ?: return null
        return QrFileChunk(fields[0], index, count, data)
    }

    /** Whether [text] looks like a chunk at all, before it is read in full. */
    public fun isChunk(text: String): Boolean = text.startsWith(PREFIX)

    /** The last part after a slash, without control characters, at most 255 bytes of UTF-8. */
    internal fun cleanName(name: String): String {
        var clean = name.substringAfterLast('/').substringAfterLast('\\').filter { it.code >= 32 && it.code != 127 }
        if (clean == "." || clean == "..") clean = ""
        while (clean.encodeToByteArray().size > MAX_NAME_BYTES) clean = clean.dropLast(1)
        // A surrogate pair cut in half would not survive UTF-8.
        if (clean.lastOrNull()?.isHighSurrogate() == true) clean = clean.dropLast(1)
        return clean
    }

    internal fun int(value: Int): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    internal fun headerSize(nameLength: Int): Int = 1 + nameLength + 4 + HASH
}

/** One code's chunk: number [index] of [count] in the file [set]. */
public class QrFileChunk(public val set: String, public val index: Int, public val count: Int, public val data: ByteArray)

/**
 * The chunks of one file gathered so far, in any order. Immutable: [add] answers a new gathering, so
 * a screen can hold it as state.
 */
public class QrFileGathering private constructor(
    public val set: String,
    private val chunks: Array<ByteArray?>,
) {
    public constructor(first: QrFileChunk) : this(first.set, arrayOfNulls<ByteArray>(first.count).also { it[first.index - 1] = first.data })

    public val count: Int get() = chunks.size
    public val have: Int get() = chunks.count { it != null }
    public val complete: Boolean get() = chunks.all { it != null }

    public fun has(index: Int): Boolean = chunks.getOrNull(index - 1) != null

    /** The numbers still missing, from 1. */
    public val missing: List<Int> get() = chunks.indices.filter { chunks[it] == null }.map { it + 1 }

    /** The file's name and size, as soon as the chunks from the first on hold the header. */
    public val name: String? get() = header()?.first
    public val size: Int? get() = header()?.second

    /** [chunk] added; [Added.Repeat] and [Added.OtherFile] leave this gathering as it was. */
    public fun add(chunk: QrFileChunk): Added = when {
        chunk.set != set || chunk.count != count -> Added.OtherFile
        has(chunk.index) -> Added.Repeat
        else -> Added.New(QrFileGathering(set, chunks.copyOf().also { it[chunk.index - 1] = chunk.data }))
    }

    /** The file, once [complete]; [Assembled.Damaged] when the header or the SHA-256 do not hold. */
    public fun assemble(): Assembled {
        if (!complete) return Assembled.Incomplete
        val stream = ByteArray(chunks.sumOf { it!!.size })
        var at = 0
        for (chunk in chunks) {
            chunk!!.copyInto(stream, at)
            at += chunk.size
        }
        val nameLength = stream[0].toInt() and 0xFF
        val start = QrFile.headerSize(nameLength)
        if (stream.size < start) return Assembled.Damaged
        val size = readInt(stream, 1 + nameLength)
        val hash = stream.copyOfRange(start - 32, start)
        val bytes = stream.copyOfRange(start, stream.size)
        if (size != bytes.size || !Sha256.digest(bytes).contentEquals(hash)) return Assembled.Damaged
        return Assembled.Ok(QrFile.cleanName(stream.copyOfRange(1, 1 + nameLength).decodeToString()), bytes)
    }

    private fun header(): Pair<String, Int>? {
        val prefix = chunks.takeWhile { it != null }.fold(ByteArray(0)) { all, chunk ->
            if (all.isNotEmpty() && all.size >= QrFile.headerSize(all[0].toInt() and 0xFF)) all else all + chunk!!
        }
        if (prefix.isEmpty()) return null
        val nameLength = prefix[0].toInt() and 0xFF
        if (prefix.size < QrFile.headerSize(nameLength)) return null
        return QrFile.cleanName(prefix.copyOfRange(1, 1 + nameLength).decodeToString()) to readInt(prefix, 1 + nameLength)
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF shl 24) or (bytes[at + 1].toInt() and 0xFF shl 16) or
            (bytes[at + 2].toInt() and 0xFF shl 8) or (bytes[at + 3].toInt() and 0xFF)

    public sealed interface Added {
        public class New(public val gathering: QrFileGathering) : Added
        public data object Repeat : Added

        /** A chunk of another file: another set, or the same set with another count. */
        public data object OtherFile : Added
    }

    public sealed interface Assembled {
        public class Ok(public val name: String, public val bytes: ByteArray) : Assembled
        public data object Incomplete : Assembled
        public data object Damaged : Assembled
    }
}
