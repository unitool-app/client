package cx.m42.services.ssh.wire

/**
 * The SSH wire format (RFC 4251 §5): `uint32`, `string` (a 32-bit big-endian length and that many
 * bytes), `mpint`. The reader came from the 2FA app's `SshKey`; the writer is new (02 §10).
 *
 * The reader never throws: every read answers null past the end, so a parser of hostile input is a
 * chain of `?: return null` and nothing else.
 */
internal class WireReader(private val bytes: ByteArray, start: Int = 0, private val end: Int = bytes.size) {
    var position: Int = start
        private set

    val done: Boolean get() = position == end
    val remaining: Int get() = end - position

    fun byte(): Int? {
        if (position + 1 > end) return null
        return bytes[position++].toInt() and 0xff
    }

    fun uint32(): Long? {
        if (position + 4 > end) return null
        val v = ((bytes[position].toLong() and 0xff) shl 24) or ((bytes[position + 1].toLong() and 0xff) shl 16) or
            ((bytes[position + 2].toLong() and 0xff) shl 8) or (bytes[position + 3].toLong() and 0xff)
        position += 4
        return v
    }

    fun string(): ByteArray? {
        val length = uint32() ?: return null
        if (length > end - position) return null
        return bytes.copyOfRange(position, position + length.toInt()).also { position += length.toInt() }
    }

    fun text(): String? = string()?.let { runCatching { it.decodeToString(throwOnInvalidSequence = true) }.getOrNull() }

    fun raw(n: Int): ByteArray? {
        if (n < 0 || position + n > end) return null
        return bytes.copyOfRange(position, position + n).also { position += n }
    }

    /** An `mpint` as its bytes, sign byte included — what `string()` returns; positive only is the caller's check. */
    fun mpint(): ByteArray? = string()
}

internal class WireWriter {
    private var buffer = ByteArray(64)
    var size: Int = 0
        private set

    private fun ensure(n: Int) {
        if (size + n <= buffer.size) return
        var next = buffer.size * 2
        while (next < size + n) next *= 2
        val grown = buffer.copyOf(next)
        buffer.fill(0)
        buffer = grown
    }

    fun byte(value: Int): WireWriter = apply {
        ensure(1)
        buffer[size++] = value.toByte()
    }

    fun uint32(value: Long): WireWriter = apply {
        ensure(4)
        buffer[size++] = (value ushr 24).toByte()
        buffer[size++] = (value ushr 16).toByte()
        buffer[size++] = (value ushr 8).toByte()
        buffer[size++] = value.toByte()
    }

    fun raw(bytes: ByteArray): WireWriter = apply {
        ensure(bytes.size)
        bytes.copyInto(buffer, size)
        size += bytes.size
    }

    fun string(bytes: ByteArray): WireWriter = uint32(bytes.size.toLong()).raw(bytes)

    fun string(text: String): WireWriter = string(text.encodeToByteArray())

    /** A non-negative big-endian number as an `mpint`: no leading zeros, one added when the top bit is set. */
    fun mpint(magnitude: ByteArray): WireWriter {
        var start = 0
        while (start < magnitude.size && magnitude[start] == 0.toByte()) start++
        val trimmed = magnitude.copyOfRange(start, magnitude.size)
        return if (trimmed.isNotEmpty() && trimmed[0].toInt() and 0x80 != 0) {
            uint32(trimmed.size + 1L).byte(0).raw(trimmed)
        } else {
            string(trimmed)
        }
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    /** The buffer zeroed — for a writer that held private fields. */
    fun wipe() {
        buffer.fill(0)
        size = 0
    }
}

internal fun wire(block: WireWriter.() -> Unit): ByteArray = WireWriter().apply(block).toByteArray()
