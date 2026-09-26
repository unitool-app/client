package cx.m42.services.ssh

/**
 * Private material inside this module, and nowhere else (02 §9, D155).
 *
 * Not a data class and not serializable, so no generated `toString`, `copy` or serializer can hand
 * the bytes out; `equals` is identity. [close] zeroes the array, and every holder closes it in a
 * `finally` — or with [use].
 *
 * On wasm the array is zeroed but the JavaScript heap may hold copies nobody can reach to clear,
 * and on the JVM a `BigInteger` made from it cannot be cleared at all (01 §4.5). Both are written
 * down rather than pretended away; the heap test (I12) says which keys it holds to zero.
 */
internal class SecretBytes(bytes: ByteArray) : AutoCloseable {

    private var value: ByteArray? = bytes

    val size: Int get() = value?.size ?: 0

    /** The bytes themselves — never to be stored, only read inside the operation that needs them. */
    val bytes: ByteArray get() = value ?: error("SecretBytes used after close")

    val closed: Boolean get() = value == null

    fun copy(): SecretBytes = SecretBytes(bytes.copyOf())

    fun copyOfRange(from: Int, to: Int): SecretBytes = SecretBytes(bytes.copyOfRange(from, to))

    override fun close() {
        value?.fill(0)
        value = null
    }

    override fun toString(): String = "SecretBytes(redacted)"

    companion object {
        /** A passphrase as UTF-8, zeroing nothing of [chars] — the caller does that. */
        fun utf8(chars: CharArray): SecretBytes {
            var i = 0
            val bytes = ByteArray(chars.size * 3)
            var n = 0
            while (i < chars.size) {
                var c = chars[i].code
                if (c in 0xD800..0xDBFF && i + 1 < chars.size && chars[i + 1].code in 0xDC00..0xDFFF) {
                    c = 0x10000 + ((c - 0xD800) shl 10) + (chars[i + 1].code - 0xDC00)
                    i++
                }
                when {
                    c < 0x80 -> bytes[n++] = c.toByte()
                    c < 0x800 -> {
                        bytes[n++] = (0xC0 or (c shr 6)).toByte()
                        bytes[n++] = (0x80 or (c and 0x3F)).toByte()
                    }
                    c < 0x10000 -> {
                        bytes[n++] = (0xE0 or (c shr 12)).toByte()
                        bytes[n++] = (0x80 or ((c shr 6) and 0x3F)).toByte()
                        bytes[n++] = (0x80 or (c and 0x3F)).toByte()
                    }
                    else -> {
                        bytes[n++] = (0xF0 or (c shr 18)).toByte()
                        bytes[n++] = (0x80 or ((c shr 12) and 0x3F)).toByte()
                        bytes[n++] = (0x80 or ((c shr 6) and 0x3F)).toByte()
                        bytes[n++] = (0x80 or (c and 0x3F)).toByte()
                    }
                }
                i++
            }
            val result = bytes.copyOf(n)
            bytes.fill(0)
            return SecretBytes(result)
        }
    }
}

/** Runs [block] and zeroes [array] after, whatever happens. */
internal inline fun <T> ByteArray.wiped(block: (ByteArray) -> T): T = try {
    block(this)
} finally {
    fill(0)
}

internal inline fun <T> CharArray.wiped(block: (CharArray) -> T): T = try {
    block(this)
} finally {
    fill('\u0000')
}
