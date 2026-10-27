package cx.m42.apps.qrscanner

/**
 * What a code holds, read from its text (D626). Pure, so the whole table is tested without a screen.
 * Only what the screen shows is parsed out; anything that does not parse is plain [Text].
 */
internal sealed interface Content {
    /** A `http(s)://` link. */
    data class Link(val url: String) : Content

    /** `WIFI:T:WPA;S:name;P:pass;H:true;;`. */
    data class Wifi(val ssid: String, val security: String?, val password: String?, val hidden: Boolean) : Content

    /** `mailto:` or `MATMSG:`; [uri] is what "Open" hands to the system. */
    data class Email(val to: String, val subject: String?, val body: String?, val uri: String) : Content

    data class Phone(val number: String, val uri: String) : Content

    data class Sms(val number: String, val body: String?, val uri: String) : Content

    data class Geo(val latitude: String, val longitude: String, val uri: String) : Content

    /** `BEGIN:VCARD`: the fields worth a line each; saved whole as `.vcf`. */
    data class Contact(val name: String?, val phones: List<String>, val emails: List<String>, val organization: String?) : Content

    /** `BEGIN:VEVENT` (or a VCALENDAR around one); saved whole as `.ics`. */
    data class Event(val summary: String?, val start: String?, val end: String?, val location: String?) : Content

    /** `otpauth://` or `otpauth-migration://`: who and for whom, never the secret. */
    data class Otp(val issuer: String?, val account: String?, val migration: Boolean) : Content

    data object Text : Content
}

internal object Contents {

    fun of(text: String): Content {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") ->
                if (trimmed.none { it.isWhitespace() }) Content.Link(trimmed) else Content.Text
            trimmed.startsWith("WIFI:", ignoreCase = true) -> wifi(trimmed)
            lower.startsWith("mailto:") -> mailto(trimmed)
            trimmed.startsWith("MATMSG:", ignoreCase = true) -> matmsg(trimmed)
            lower.startsWith("tel:") -> trimmed.substring(4).takeIf { it.isNotBlank() }?.let { Content.Phone(it, trimmed) }
            lower.startsWith("smsto:") || lower.startsWith("sms:") -> sms(trimmed)
            lower.startsWith("geo:") -> geo(trimmed)
            trimmed.startsWith("BEGIN:VCARD", ignoreCase = true) -> contact(trimmed)
            trimmed.startsWith("BEGIN:VEVENT", ignoreCase = true) ||
                trimmed.startsWith("BEGIN:VCALENDAR", ignoreCase = true) && trimmed.contains("BEGIN:VEVENT", ignoreCase = true) -> event(trimmed)
            lower.startsWith("otpauth-migration://") -> Content.Otp(null, null, migration = true)
            lower.startsWith("otpauth://") -> otp(trimmed)
            else -> null
        } ?: Content.Text
    }

    /** The file extension the content is saved under. */
    fun extension(content: Content): String = when (content) {
        is Content.Contact -> "vcf"
        is Content.Event -> "ics"
        else -> "txt"
    }

    private fun wifi(text: String): Content? {
        val fields = meCard(text.substring(5))
        val ssid = fields["S"]?.takeIf { it.isNotEmpty() } ?: return null
        return Content.Wifi(
            ssid = ssid,
            security = fields["T"]?.takeIf { it.isNotEmpty() && !it.equals("nopass", ignoreCase = true) },
            password = fields["P"]?.takeIf { it.isNotEmpty() },
            hidden = fields["H"].equals("true", ignoreCase = true),
        )
    }

    private fun matmsg(text: String): Content? {
        val fields = meCard(text.substring(7))
        val to = fields["TO"]?.takeIf { it.isNotEmpty() } ?: return null
        val query = listOfNotNull(fields["SUB"]?.let { "subject=" + encode(it) }, fields["BODY"]?.let { "body=" + encode(it) })
        return Content.Email(to, fields["SUB"], fields["BODY"], "mailto:$to" + if (query.isEmpty()) "" else "?" + query.joinToString("&"))
    }

    private fun mailto(text: String): Content? {
        val rest = text.substring(7)
        val to = decode(rest.substringBefore('?'))
        val query = query(rest.substringAfter('?', ""))
        if (to.isEmpty() && query["to"] == null) return null
        return Content.Email(to.ifEmpty { query["to"]!! }, query["subject"], query["body"], text)
    }

    private fun sms(text: String): Content? {
        val lower = text.lowercase()
        return if (lower.startsWith("smsto:")) {
            // SMSTO:number:body
            val rest = text.substring(6)
            val number = rest.substringBefore(':').takeIf { it.isNotBlank() } ?: return null
            val body = rest.substringAfter(':', "").ifEmpty { null }
            Content.Sms(number, body, "sms:$number" + (body?.let { "?body=" + encode(it) } ?: ""))
        } else {
            val rest = text.substring(4)
            val number = decode(rest.substringBefore('?')).takeIf { it.isNotBlank() } ?: return null
            Content.Sms(number, query(rest.substringAfter('?', ""))["body"], text)
        }
    }

    private fun geo(text: String): Content? {
        val parts = text.substring(4).substringBefore('?').substringBefore(';').split(',')
        if (parts.size < 2) return null
        val latitude = parts[0].trim()
        val longitude = parts[1].trim()
        if (latitude.toDoubleOrNull() == null || longitude.toDoubleOrNull() == null) return null
        return Content.Geo(latitude, longitude, text)
    }

    private fun contact(text: String): Content {
        val lines = unfold(text)
        fun values(key: String) = lines.filter { it.first.equals(key, ignoreCase = true) }.map { it.second }.filter { it.isNotBlank() }
        val name = values("FN").firstOrNull()
            ?: values("N").firstOrNull()?.split(';')?.let { listOf(it.getOrNull(1), it.getOrNull(0)) }
                ?.filterNot { it.isNullOrBlank() }?.joinToString(" ")?.ifBlank { null }
        return Content.Contact(name, values("TEL"), values("EMAIL"), values("ORG").firstOrNull()?.replace(';', ' ')?.trim())
    }

    private fun event(text: String): Content {
        val lines = unfold(text)
        fun value(key: String) = lines.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second?.takeIf { it.isNotBlank() }
        return Content.Event(value("SUMMARY"), value("DTSTART")?.let(::icsTime), value("DTEND")?.let(::icsTime), value("LOCATION"))
    }

    private fun otp(text: String): Content? {
        // otpauth://totp/Issuer:account?secret=…&issuer=Issuer
        val afterType = text.substringAfter("://").substringAfter('/', "")
        val label = decode(afterType.substringBefore('?'))
        val query = query(afterType.substringAfter('?', ""))
        val issuer = query["issuer"] ?: label.substringBefore(':', "").ifEmpty { null }
        val account = label.substringAfter(':').ifEmpty { null }
        return Content.Otp(issuer, account, migration = false)
    }

    /** `KEY:value;KEY:value;;` with `\;`, `\,`, `\:` and `\\` escaped (MECARD, the Wi-Fi code). */
    private fun meCard(text: String): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        val current = StringBuilder()
        var escaped = false
        fun flush() {
            val field = current.toString()
            current.clear()
            val colon = field.indexOf(':')
            if (colon > 0) fields.getOrPut(field.substring(0, colon).uppercase()) { field.substring(colon + 1) }
        }
        for (char in text) {
            when {
                escaped -> {
                    current.append('\u0000').append(char)
                    escaped = false
                }
                char == '\\' -> escaped = true
                char == ';' -> flush()
                else -> current.append(char)
            }
        }
        flush()
        // The NUL marks an escaped character, so an escaped ':' in a key's value does not split it.
        return fields.mapValues { (_, value) -> value.replace("\u0000", "") }
    }

    /** vCard / iCalendar lines with their continuations joined, as (name without parameters, value). */
    private fun unfold(text: String): List<Pair<String, String>> {
        val joined = text.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "")
        return joined.lines().mapNotNull { line ->
            val colon = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            line.substring(0, colon).substringBefore(';') to
                line.substring(colon + 1).replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").trim()
        }
    }

    /** `20261027T190000Z` → `2026-10-27 19:00 UTC`, `20261027` → `2026-10-27`; anything else as is. */
    private fun icsTime(value: String): String {
        val match = Regex("""(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})\d{2}(Z?))?""").matchEntire(value) ?: return value
        val (y, m, d, hh, mm, z) = match.destructured
        return "$y-$m-$d" + if (hh.isEmpty()) "" else " $hh:$mm" + if (z == "Z") " UTC" else ""
    }

    private fun query(text: String): Map<String, String> = text.split('&').mapNotNull { pair ->
        val key = pair.substringBefore('=').takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        key.lowercase() to decode(pair.substringAfter('=', ""))
    }.toMap()

    /** Percent-decoding as UTF-8; `+` stays a plus, as in a URI (not a form). */
    internal fun decode(text: String): String {
        if ('%' !in text) return text
        val out = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val char = text[i]
            val hex = if (char == '%' && i + 2 < text.length) text.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (hex != null) {
                out += hex.toByte()
                i += 3
            } else {
                char.toString().encodeToByteArray().forEach { out += it }
                i++
            }
        }
        return out.toByteArray().decodeToString()
    }

    private fun encode(text: String): String = buildString {
        for (byte in text.encodeToByteArray()) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (c.isLetterOrDigit() && b < 128 || c in "-._~") append(c) else append('%').append(HEX[b shr 4]).append(HEX[b and 15])
        }
    }

    private const val HEX = "0123456789ABCDEF"
}

/** Text or bytes (D627), and the hex dump of bytes. */
internal object Bytes {
    /** More than this is not drawn as hex on the screen; the file is saved instead (D627). */
    const val MAX_SHOWN: Int = 64 * 1024

    /** UTF-8 with no control characters but line breaks and tabs; else null — as cr-restore's `Output.text`. */
    fun text(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return ""
        val decoded = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return null
        if (decoded.any { it.code < 32 && it != '\n' && it != '\r' && it != '\t' || it.code == 127 }) return null
        return decoded
    }

    /** `00000000  48 65 6c 6c 6f 0a                                 |Hello.|`, as `hexdump -C` prints it. */
    fun dump(bytes: ByteArray, limit: Int = MAX_SHOWN): String = buildString {
        val shown = minOf(bytes.size, limit)
        var offset = 0
        while (offset < shown) {
            val end = minOf(offset + 16, shown)
            append(offset.toString(16).padStart(8, '0')).append("  ")
            for (i in offset until offset + 16) {
                if (i < end) append(HEX[bytes[i].toInt() shr 4 and 15]).append(HEX[bytes[i].toInt() and 15]).append(' ') else append("   ")
                if (i == offset + 7) append(' ')
            }
            append(" |")
            for (i in offset until end) {
                val b = bytes[i].toInt() and 0xFF
                append(if (b in 32..126) b.toChar() else '.')
            }
            append("|\n")
            offset = end
        }
    }

    /** The bytes as one line of hex, what "Copy" copies for binary data. */
    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) append(HEX[byte.toInt() shr 4 and 15]).append(HEX[byte.toInt() and 15])
    }

    private const val HEX = "0123456789abcdef"
}
