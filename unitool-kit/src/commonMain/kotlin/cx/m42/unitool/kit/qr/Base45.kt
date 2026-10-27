package cx.m42.unitool.kit.qr

/**
 * Base45 (RFC 9285): bytes as the 45 characters of the QR alphanumeric mode. Two bytes become three
 * characters, and the alphanumeric mode spends 5.5 bits on each, so a byte costs 8.25 bits — about
 * as dense as byte mode, yet plain capitals and digits every scanner hands back unchanged.
 */
public object Base45 {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

    public fun encode(bytes: ByteArray): String = buildString((bytes.size + 1) / 2 * 3) {
        var i = 0
        while (i + 1 < bytes.size) {
            var n = (bytes[i].toInt() and 0xFF) * 256 + (bytes[i + 1].toInt() and 0xFF)
            repeat(3) {
                append(ALPHABET[n % 45])
                n /= 45
            }
            i += 2
        }
        if (i < bytes.size) {
            val n = bytes[i].toInt() and 0xFF
            append(ALPHABET[n % 45])
            append(ALPHABET[n / 45])
        }
    }

    /** The bytes, or null for a character outside the alphabet, a dangling one, or a group over 0xFFFF. */
    public fun decode(text: String): ByteArray? {
        if (text.length % 3 == 1) return null
        val out = ByteArray(text.length / 3 * 2 + if (text.length % 3 == 2) 1 else 0)
        var o = 0
        var i = 0
        while (i < text.length) {
            val group = minOf(3, text.length - i)
            var n = 0
            var factor = 1
            for (j in 0 until group) {
                val value = ALPHABET.indexOf(text[i + j])
                if (value < 0) return null
                n += value * factor
                factor *= 45
            }
            if (group == 3) {
                if (n > 0xFFFF) return null
                out[o++] = (n shr 8).toByte()
                out[o++] = n.toByte()
            } else {
                if (n > 0xFF) return null
                out[o++] = n.toByte()
            }
            i += group
        }
        return out
    }
}
