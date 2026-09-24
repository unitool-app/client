package cx.m42.apps.twofa

import kotlinx.serialization.Serializable

/**
 * One account a person gets codes for.
 *
 * The secret is kept as normalised Base32 — the form it was handed out in — rather than as bytes,
 * so what is stored is what a person could type back in from the site's "can't scan?" page.
 */
@Serializable
public data class TotpAccount(
    val id: String,
    val issuer: String,
    val name: String,
    val secret: String,
    val algorithm: HashAlgorithm = HashAlgorithm.SHA1,
    val digits: Int = 6,
    val period: Int = 30,
) {
    /** What the list shows in bold: the service, or the account when a link named no service. */
    val title: String get() = issuer.ifBlank { name }

    /** The grey line under it, and nothing when it would repeat the title. */
    val subtitle: String get() = if (issuer.isBlank()) "" else name
}

/**
 * RFC 6238 over RFC 4226: the counter is the number of whole periods since the epoch, and the code
 * is the HMAC of that counter, truncated.
 *
 * Pure functions of `(account, time)`. The screen asks for a code once a second with the runtime's
 * clock, which is what lets a test pin the time and the screenshots show the same digits every run.
 */
public object Totp {

    public fun code(account: TotpAccount, epochMillis: Long): String {
        val key = Base32.decode(account.secret) ?: return "—"
        return code(key, epochMillis / 1000 / account.period, account.digits, account.algorithm)
    }

    public fun code(key: ByteArray, counter: Long, digits: Int, algorithm: HashAlgorithm): String {
        val message = ByteArray(8) { (counter ushr (56 - 8 * it)).toByte() }
        val hash = hmac(algorithm, key, message)
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        var modulus = 1L
        repeat(digits) { modulus *= 10 }
        return (binary.toLong() % modulus).toString().padStart(digits, '0')
    }

    /** Whole seconds until this account's code changes; 1..period. */
    public fun secondsLeft(account: TotpAccount, epochMillis: Long): Int {
        val seconds = epochMillis / 1000
        return (account.period - (seconds % account.period)).toInt()
    }

    /** `123456` → `123 456`, `12345678` → `1234 5678`: the grouping every authenticator uses. */
    public fun grouped(code: String): String = when (code.length) {
        6 -> code.substring(0, 3) + " " + code.substring(3)
        7 -> code.substring(0, 3) + " " + code.substring(3)
        8 -> code.substring(0, 4) + " " + code.substring(4)
        else -> code
    }
}

/** RFC 4648 Base32, forgiving about the ways people copy it: spaces, dashes, lower case, no padding. */
public object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    public fun normalize(text: String): String =
        text.uppercase().filter { it != ' ' && it != '-' && it != '=' && it != '\n' && it != '\t' }

    /** Null on a character outside the alphabet, or on nothing at all. */
    public fun decode(text: String): ByteArray? {
        val clean = normalize(text)
        if (clean.isEmpty()) return null
        val out = ArrayList<Byte>(clean.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (char in clean) {
            val value = ALPHABET.indexOf(char)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add((buffer ushr bits).toByte())
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    public fun encode(bytes: ByteArray): String = buildString {
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                append(ALPHABET[(buffer ushr bits) and 31])
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) append(ALPHABET[(buffer shl (5 - bits)) and 31])
    }
}

/** Why a draft is not an account yet. The screen turns each into a sentence. */
public enum class DraftError { EmptyName, EmptySecret, BadSecret, NotOtpAuth, Hotp, Migration, BadDigits, BadPeriod }

/**
 * What the add form holds, before it is an account.
 *
 * Every field a string, as typed, because a form that parses on each keystroke fights the person
 * typing. [validate] is the one place the text becomes numbers.
 */
public data class AccountDraft(
    val issuer: String = "",
    val name: String = "",
    val secret: String = "",
    val algorithm: HashAlgorithm = HashAlgorithm.SHA1,
    val digits: Int = 6,
    val period: Int = 30,
) {
    public fun validate(id: String): Result<TotpAccount> {
        if (issuer.isBlank() && name.isBlank()) return failure(DraftError.EmptyName)
        if (Base32.normalize(secret).isEmpty()) return failure(DraftError.EmptySecret)
        if (Base32.decode(secret) == null) return failure(DraftError.BadSecret)
        if (digits !in 6..8) return failure(DraftError.BadDigits)
        if (period !in 1..300) return failure(DraftError.BadPeriod)
        return Result.success(
            TotpAccount(
                id = id,
                issuer = issuer.trim(),
                name = name.trim(),
                secret = Base32.normalize(secret),
                algorithm = algorithm,
                digits = digits,
                period = period,
            ),
        )
    }
}

public class DraftException(public val error: DraftError) : IllegalArgumentException(error.name)

private fun failure(error: DraftError): Result<TotpAccount> = Result.failure(DraftException(error))

/**
 * `otpauth://totp/Issuer:account?secret=…&issuer=…&algorithm=SHA1&digits=6&period=30` — the text
 * inside every "scan this with your authenticator" QR code (Google's Key Uri Format).
 */
public object OtpAuthUri {

    public fun parse(text: String): Result<AccountDraft> {
        val uri = text.trim()
        // Google Authenticator's "transfer accounts" code: a protobuf of many accounts, and the QR
        // people most often point a new authenticator at by mistake. Named, so the error can say so.
        if (uri.startsWith("otpauth-migration:", ignoreCase = true)) return draftFailure(DraftError.Migration)
        if (!uri.startsWith("otpauth://", ignoreCase = true)) return draftFailure(DraftError.NotOtpAuth)
        val rest = uri.substring("otpauth://".length)
        val type = rest.substringBefore('/').lowercase()
        if (type == "hotp") return draftFailure(DraftError.Hotp)
        if (type != "totp") return draftFailure(DraftError.NotOtpAuth)

        val label = percentDecode(rest.substringAfter('/', "").substringBefore('?'), plusIsSpace = false)
        val params = rest.substringAfter('?', "").split('&').filter { '=' in it }.associate { pair ->
            pair.substringBefore('=').lowercase() to percentDecode(pair.substringAfter('='), plusIsSpace = true)
        }

        val labelIssuer = label.substringBefore(':', "").trim()
        val name = if (':' in label) label.substringAfter(':').trim() else label.trim()

        val secret = params["secret"].orEmpty()
        val draft = AccountDraft(
            // The parameter wins over the label prefix: it is the newer field, and the one the
            // spec says to prefer when the two disagree.
            issuer = params["issuer"]?.trim()?.ifEmpty { null } ?: labelIssuer,
            name = name,
            secret = Base32.normalize(secret),
            algorithm = params["algorithm"]?.let { HashAlgorithm.parseOrNull(it) } ?: HashAlgorithm.SHA1,
            digits = params["digits"]?.toIntOrNull() ?: 6,
            period = params["period"]?.toIntOrNull() ?: 30,
        )
        // Validated here too, so a link that could never produce a code is refused at the door
        // rather than half-filling the form.
        return draft.validate(id = "").map { draft }
    }

    public fun format(account: TotpAccount): String {
        val label = if (account.issuer.isBlank()) account.name else "${account.issuer}:${account.name}"
        return buildString {
            append("otpauth://totp/").append(percentEncode(label))
            append("?secret=").append(account.secret)
            if (account.issuer.isNotBlank()) append("&issuer=").append(percentEncode(account.issuer))
            if (account.algorithm != HashAlgorithm.SHA1) append("&algorithm=").append(account.algorithm.name)
            if (account.digits != 6) append("&digits=").append(account.digits)
            if (account.period != 30) append("&period=").append(account.period)
        }
    }

    private fun draftFailure(error: DraftError): Result<AccountDraft> = Result.failure(DraftException(error))

    /** UTF-8 aware: an issuer called "Госуслуги" arrives as eighteen escaped bytes. */
    internal fun percentDecode(text: String, plusIsSpace: Boolean): String {
        val bytes = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val char = text[i]
            when {
                char == '%' && i + 2 <= text.lastIndex -> {
                    val value = text.substring(i + 1, i + 3).toIntOrNull(16)
                    if (value != null) {
                        bytes.add(value.toByte()); i += 3; continue
                    }
                    bytes.addAll(char.toString().encodeToByteArray().toList())
                }
                char == '+' && plusIsSpace -> bytes.add(' '.code.toByte())
                else -> bytes.addAll(char.toString().encodeToByteArray().toList())
            }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }

    internal fun percentEncode(text: String): String = buildString {
        for (byte in text.encodeToByteArray()) {
            val c = byte.toInt() and 0xff
            val char = c.toChar()
            if (char.isLetterOrDigit() && c < 0x80 || char in "-._~@") {
                append(char)
            } else {
                append('%').append(c.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}
