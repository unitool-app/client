package cx.m42.unitool.kit.link

import cx.m42.unitool.kit.crypto.Hash
import kotlin.io.encoding.Base64

/**
 * What the computer's QR says (passwords-browser 01 §2, D557): usb-sign's
 * `unitool-pair:1:<base64url(C ‖ psk)>;name=…`, plus the computer's SSH public key (`ssh=`, the
 * blob) and its addresses (`addr=`, a hint only).
 *
 * [parse] is strict: a field it does not know is ignored, a field it needs that is missing or
 * malformed makes the whole code nothing.
 */
public class PairingCode(
    public val computerKey: ByteArray,
    public val psk: ByteArray,
    public val name: String,
    public val sshKey: ByteArray,
    public val addresses: List<String> = emptyList(),
) {
    init {
        require(computerKey.size == 32 && psk.size == 32)
    }

    public fun format(): String = buildString {
        append(PREFIX)
        append(B64.encode(computerKey + psk))
        append(";name=").append(percentEncode(name.take(MAX_NAME)))
        append(";ssh=").append(B64.encode(sshKey))
        if (addresses.isNotEmpty()) append(";addr=").append(addresses.joinToString(",") { percentEncode(it) })
    }

    /** The mDNS instance name the phone answers to while this code is being paired (01 §2). */
    public val pairingInstance: String get() = pairingInstance(psk)

    override fun toString(): String = "PairingCode($name, redacted)"

    public companion object {
        public const val PREFIX: String = "unitool-pair:1:"
        public const val MAX_NAME: Int = 64

        private val B64: Base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        public fun parse(text: String): PairingCode? {
            val trimmed = text.trim()
            if (!trimmed.startsWith(PREFIX)) return null
            val parts = trimmed.substring(PREFIX.length).split(';')
            val keys = runCatching { B64.decode(parts[0]) }.getOrNull() ?: return null
            if (keys.size != 64) return null
            val fields = parts.drop(1).mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
            }.toMap()
            val name = fields["name"]?.let(::percentDecode)?.takeIf { it.isNotBlank() && it.length <= MAX_NAME } ?: return null
            val ssh = fields["ssh"]?.let { runCatching { B64.decode(it) }.getOrNull() } ?: return null
            if (ssh.isEmpty()) return null
            val addresses = fields["addr"]?.split(',')?.mapNotNull(::percentDecode)?.filter { it.isNotEmpty() }.orEmpty()
            return PairingCode(keys.copyOfRange(0, 32), keys.copyOfRange(32, 64), name, ssh, addresses)
        }

        /** `p` and the first 15 hex digits of SHA-256("unitool-pair" ‖ psk). */
        public fun pairingInstance(psk: ByteArray): String =
            "p" + hexOf(Hash.SHA256.digest("unitool-pair".encodeToByteArray() + psk)).take(15)

        /** The mDNS instance name of a paired computer's phone: its link id in hex (01 §3). */
        public fun linkInstance(linkId: ByteArray): String = hexOf(linkId)

        internal fun hexOf(bytes: ByteArray): String = buildString(bytes.size * 2) {
            bytes.forEach {
                val v = it.toInt() and 0xff
                append(HEX[v ushr 4]).append(HEX[v and 15])
            }
        }

        private const val HEX = "0123456789abcdef"

        /** RFC 3986 unreserved stays, everything else is %XX of its UTF-8 — `;`, `=` and `,` included. */
        internal fun percentEncode(text: String): String = buildString {
            for (b in text.encodeToByteArray()) {
                val c = b.toInt() and 0xff
                val ch = c.toChar()
                if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~' || ch == ':') {
                    append(ch)
                } else {
                    append('%').append(HEX[c ushr 4].uppercaseChar()).append(HEX[c and 15].uppercaseChar())
                }
            }
        }

        internal fun percentDecode(text: String): String? {
            val out = ArrayList<Byte>(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%') {
                    if (i + 2 >= text.length) return null
                    val v = text.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                    out += v.toByte()
                    i += 3
                } else {
                    if (c.code > 0x7e || c.code < 0x21) return null
                    out += c.code.toByte()
                    i++
                }
            }
            return runCatching { out.toByteArray().decodeToString(throwOnInvalidSequence = true) }.getOrNull()
        }
    }
}
