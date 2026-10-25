package cx.m42.unitool.kit.json

/**
 * The little JSON the link and the browser host speak (passwords-browser 01 §4–§5), in bytes, so a
 * password can pass through without ever becoming a `String` nobody can wipe (D535).
 *
 * [parse] reads one flat object whose values are strings, numbers, booleans or null — each value as
 * its UTF-8 bytes (a string unescaped, anything else as written). Nesting, duplicate keys and
 * trailing bytes answer null. [JsonWriter] writes any shape, strings either from text or from bytes.
 */
public object FlatJson {

    public fun parse(bytes: ByteArray): Map<String, ByteArray>? = runCatching { Parser(bytes).obj() }.getOrNull()

    /** [parse] with every value as text — for messages with nothing secret in them. */
    public fun parseText(bytes: ByteArray): Map<String, String>? =
        parse(bytes)?.mapValues { (_, v) -> v.decodeToString().also { v.fill(0) } }

    private class Parser(private val s: ByteArray) {
        private var i = 0

        fun obj(): Map<String, ByteArray>? {
            space()
            if (take() != '{'.code) return null
            val out = LinkedHashMap<String, ByteArray>()
            space()
            if (peek() == '}'.code) {
                i++
            } else {
                while (true) {
                    space()
                    val key = string()?.decodeToString() ?: return null
                    space()
                    if (take() != ':'.code) return null
                    space()
                    val value = value() ?: return null
                    if (out.put(key, value) != null) return null
                    space()
                    when (take()) {
                        ','.code -> continue
                        '}'.code -> break
                        else -> return null
                    }
                }
            }
            space()
            return out.takeIf { i == s.size }
        }

        private fun value(): ByteArray? = when (peek()) {
            '"'.code -> string()
            '{'.code, '['.code -> null
            else -> {
                val start = i
                while (i < s.size && s[i].toInt().toChar().let { it.isLetterOrDigit() || it == '-' || it == '+' || it == '.' }) i++
                val word = s.copyOfRange(start, i)
                val text = word.decodeToString()
                val ok = text == "true" || text == "false" || text == "null" || text.toDoubleOrNull() != null
                if (ok) word else null
            }
        }

        private fun string(): ByteArray? {
            if (take() != '"'.code) return null
            val out = ByteBuffer()
            while (true) {
                if (i >= s.size) return null
                val c = s[i++].toInt() and 0xff
                when {
                    c == '"'.code -> return out.toByteArray().also { out.wipe() }
                    c == '\\'.code -> {
                        when (take()) {
                            '"'.code -> out.add('"'.code)
                            '\\'.code -> out.add('\\'.code)
                            '/'.code -> out.add('/'.code)
                            'b'.code -> out.add(8)
                            'f'.code -> out.add(12)
                            'n'.code -> out.add(10)
                            'r'.code -> out.add(13)
                            't'.code -> out.add(9)
                            'u'.code -> {
                                var cp = hex4() ?: return null
                                if (cp in 0xD800..0xDBFF) {
                                    if (take() != '\\'.code || take() != 'u'.code) return null
                                    val low = hex4() ?: return null
                                    if (low !in 0xDC00..0xDFFF) return null
                                    cp = 0x10000 + ((cp - 0xD800) shl 10) + (low - 0xDC00)
                                } else if (cp in 0xDC00..0xDFFF) {
                                    return null
                                }
                                out.utf8(cp)
                            }
                            else -> return null
                        }
                    }
                    c < 0x20 -> return null
                    else -> out.add(c)
                }
            }
        }

        private fun hex4(): Int? {
            if (i + 4 > s.size) return null
            var v = 0
            repeat(4) { v = v * 16 + (s[i++].toInt().toChar().digitToIntOrNull(16) ?: return null) }
            return v
        }

        private fun peek(): Int = if (i < s.size) s[i].toInt() and 0xff else -1
        private fun take(): Int = if (i < s.size) s[i++].toInt() and 0xff else -1

        private fun space() {
            while (i < s.size && (s[i] == ' '.code.toByte() || s[i] == '\n'.code.toByte() || s[i] == '\r'.code.toByte() || s[i] == '\t'.code.toByte())) i++
        }
    }
}

/**
 * JSON into bytes. [string] of a `ByteArray` copies the bytes in escaped and never makes a `String`
 * of them; [toByteArray] then [wipe] is how a message with a password in it is sent and forgotten.
 */
public class JsonWriter {
    private val out = ByteBuffer()

    /** Values written so far in each open object or array, innermost last. */
    private val counts = ArrayList<Int>()
    private var afterName = false

    public fun beginObject(): JsonWriter = apply { value(); out.add('{'.code); counts += 0 }
    public fun endObject(): JsonWriter = apply { counts.removeAt(counts.lastIndex); out.add('}'.code) }
    public fun beginArray(): JsonWriter = apply { value(); out.add('['.code); counts += 0 }
    public fun endArray(): JsonWriter = apply { counts.removeAt(counts.lastIndex); out.add(']'.code) }

    public fun name(name: String): JsonWriter = apply {
        value()
        quoted(name.encodeToByteArray())
        out.add(':'.code)
        afterName = true
    }

    public fun string(value: String): JsonWriter = string(value.encodeToByteArray())

    public fun string(value: ByteArray): JsonWriter = apply { value(); quoted(value) }

    public fun number(value: Long): JsonWriter = apply { value(); ascii(value.toString()) }

    public fun bool(value: Boolean): JsonWriter = apply { value(); ascii(if (value) "true" else "false") }

    public fun nul(): JsonWriter = apply { value(); ascii("null") }

    public fun toByteArray(): ByteArray = out.toByteArray()

    public fun wipe(): Unit = out.wipe()

    /** Before anything that takes a place in the open container: the comma, unless it follows its name. */
    private fun value() {
        if (afterName) {
            afterName = false
            return
        }
        if (counts.isEmpty()) return
        if (counts.last() > 0) out.add(','.code)
        counts[counts.lastIndex] = counts.last() + 1
    }

    private fun ascii(text: String) = text.forEach { out.add(it.code) }

    private fun quoted(bytes: ByteArray) {
        out.add('"'.code)
        for (b in bytes) {
            val c = b.toInt() and 0xff
            when {
                c == '"'.code -> { out.add('\\'.code); out.add('"'.code) }
                c == '\\'.code -> { out.add('\\'.code); out.add('\\'.code) }
                c == 10 -> { out.add('\\'.code); out.add('n'.code) }
                c == 13 -> { out.add('\\'.code); out.add('r'.code) }
                c == 9 -> { out.add('\\'.code); out.add('t'.code) }
                c < 0x20 || c == 0x7f -> {
                    out.add('\\'.code); out.add('u'.code); out.add('0'.code); out.add('0'.code)
                    out.add(HEX[c ushr 4].code); out.add(HEX[c and 15].code)
                }
                else -> out.add(c)
            }
        }
        out.add('"'.code)
    }

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}

/** A growing byte array that zeroes what it leaves behind. */
internal class ByteBuffer {
    private var bytes = ByteArray(64)
    private var size = 0

    fun add(b: Int) {
        if (size == bytes.size) {
            val next = bytes.copyOf(bytes.size * 2)
            bytes.fill(0)
            bytes = next
        }
        bytes[size++] = b.toByte()
    }

    fun utf8(cp: Int) {
        when {
            cp < 0x80 -> add(cp)
            cp < 0x800 -> { add(0xC0 or (cp shr 6)); add(0x80 or (cp and 0x3F)) }
            cp < 0x10000 -> { add(0xE0 or (cp shr 12)); add(0x80 or ((cp shr 6) and 0x3F)); add(0x80 or (cp and 0x3F)) }
            else -> {
                add(0xF0 or (cp shr 18)); add(0x80 or ((cp shr 12) and 0x3F))
                add(0x80 or ((cp shr 6) and 0x3F)); add(0x80 or (cp and 0x3F))
            }
        }
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    fun wipe() {
        bytes.fill(0)
        size = 0
    }
}
