package cx.m42.apps.twofa

import kotlin.io.encoding.Base64

/**
 * Google Authenticator's "Transfer accounts" QR code: `otpauth-migration://offline?data=…`, the data
 * a Base64 protobuf of the accounts picked on the old phone. About ten fit in one code, so a longer
 * list is shown as several codes, numbered, which the import page scans onto one list.
 *
 * Google publishes no .proto; this is the one every reader of these codes agrees on:
 *
 * ```
 * message MigrationPayload {
 *   repeated OtpParameters otp_parameters = 1;
 *   int32 version = 2; int32 batch_size = 3; int32 batch_index = 4; int32 batch_id = 5;
 * }
 * message OtpParameters {
 *   bytes secret = 1; string name = 2; string issuer = 3;
 *   Algorithm algorithm = 4;  // 0 unspecified, 1 SHA1, 2 SHA256, 3 SHA512, 4 MD5
 *   DigitCount digits = 5;    // 0 unspecified, 1 six, 2 eight
 *   OtpType type = 6;         // 0 unspecified, 1 HOTP, 2 TOTP
 *   int64 counter = 7;
 * }
 * ```
 *
 * There is no period: Google's app makes 30-second codes only.
 */
internal object GoogleMigration {

    /** One QR code of a transfer: [index] counts from 0 of [size], and [batch] is the same on every code of one transfer. */
    data class Part(val accounts: List<Entry>, val batch: Int, val index: Int, val size: Int)

    /** One account in it, under the name it had there: a draft, or why it cannot be one. */
    data class Entry(val title: String, val draft: Result<AccountDraft>)

    private const val SCHEME = "otpauth-migration:"

    fun isMigration(link: String): Boolean = link.trim().startsWith(SCHEME, ignoreCase = true)

    /** Null when [link] is not a transfer code, or its data does not read as one. */
    fun parse(link: String): Part? {
        val uri = link.trim()
        if (!isMigration(uri)) return null
        val data = uri.substringAfter('?', "").split('&')
            .firstOrNull { it.substringBefore('=').equals("data", ignoreCase = true) }
            ?.substringAfter('=')
            ?: return null
        return try {
            payload(base64(OtpAuthUri.percentDecode(data, plusIsSpace = false)))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Standard or URL-safe, padded or not. A space is a `+` that something decoded as a web form
     * would, and a line break is where a text field wrapped the link.
     */
    private fun base64(text: String): ByteArray {
        val clean = text.trim()
            .map { char -> when (char) { ' ', '-' -> '+'; '_' -> '/'; else -> char } }
            .filter { it != '=' && !it.isWhitespace() }
            .joinToString("")
        return Base64.Default.withPadding(Base64.PaddingOption.ABSENT).decode(clean)
    }

    private fun payload(bytes: ByteArray): Part? {
        val fields = fields(bytes)
        val accounts = fields.filter { it.number == 1 }.map { entry(fields(requireNotNull(it.bytes))) }
        if (accounts.isEmpty()) return null
        val size = fields.int(3).toInt().coerceAtLeast(1)
        return Part(accounts, batch = fields.int(5).toInt(), index = fields.int(4).toInt().coerceIn(0, size - 1), size = size)
    }

    private fun entry(fields: List<Field>): Entry {
        // The label the site's link had, "Issuer:account" or the account alone.
        val (labelIssuer, name) = OtpAuthUri.splitLabel(fields.text(2))
        val issuer = fields.text(3).trim().ifEmpty { labelIssuer }
        val algorithm = when (fields.int(4)) {
            0L, 1L -> HashAlgorithm.SHA1
            2L -> HashAlgorithm.SHA256
            3L -> HashAlgorithm.SHA512
            else -> null // MD5, which no site uses and nothing here computes
        }
        val digits = when (fields.int(5)) {
            0L, 1L -> 6
            2L -> 8
            else -> null
        }
        val secret = fields.lastOrNull { it.number == 1 }?.bytes ?: ByteArray(0)
        val draft = when {
            fields.int(6) == 1L -> failure(DraftError.Hotp)
            fields.int(6) !in 0L..2L -> failure(DraftError.NotOtpAuth)
            algorithm == null -> failure(DraftError.BadAlgorithm)
            digits == null -> failure(DraftError.BadDigits)
            else -> Result.success(AccountDraft(issuer, name, Base32.encode(secret), algorithm, digits, period = 30))
        }
        return Entry(issuer.ifBlank { name }, draft)
    }

    private fun failure(error: DraftError): Result<AccountDraft> = Result.failure(DraftException(error))

    // ------------------------------------------------------------ protobuf

    /** A field as read: a varint, or the bytes of a length-delimited one. */
    private class Field(val number: Int, val varint: Long, val bytes: ByteArray?)

    private fun List<Field>.int(number: Int): Long = lastOrNull { it.number == number && it.bytes == null }?.varint ?: 0L

    private fun List<Field>.text(number: Int): String = lastOrNull { it.number == number }?.bytes?.decodeToString().orEmpty()

    /** Just enough protobuf for this message: varints and length-delimited fields; fixed-width ones are stepped over. */
    private fun fields(message: ByteArray): List<Field> {
        val out = ArrayList<Field>()
        var at = 0
        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                require(at < message.size && shift < 64)
                val byte = message[at++].toInt()
                value = value or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return value
                shift += 7
            }
        }
        fun skip(count: Int) {
            require(message.size - at >= count)
            at += count
        }
        while (at < message.size) {
            val key = varint()
            val number = (key ushr 3).toInt()
            require(number > 0)
            when ((key and 7).toInt()) {
                0 -> out += Field(number, varint(), null)
                1 -> skip(8)
                2 -> {
                    val length = varint()
                    require(length in 0..(message.size - at).toLong())
                    out += Field(number, 0, message.copyOfRange(at, at + length.toInt()))
                    at += length.toInt()
                }
                5 -> skip(4)
                else -> throw IllegalArgumentException("wire type ${key and 7}")
            }
        }
        return out
    }
}
