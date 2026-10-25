package cx.m42.apps.passwords

import cx.m42.unitool.kit.crypto.secureRandomBytes
import kotlinx.serialization.Serializable

/**
 * One login (D523). [title] may be empty when the entry came from a file with only an address —
 * [shownTitle] then names it by the site.
 */
@Serializable
internal data class PasswordEntry(
    val id: String,
    val title: String = "",
    val username: String = "",
    val password: String,
    val url: String = "",
    val note: String = "",
    /** Epoch milliseconds of the last save, 0 for an entry imported with no date. */
    val changed: Long = 0,
) {
    /** What the list shows in bold: the title, else the site's host, else the login. */
    val shownTitle: String get() = title.ifBlank { Site.host(url) }.ifBlank { username }

    /** The grey line under it, and nothing when it would repeat the title. */
    val subtitle: String get() = username.takeIf { it != shownTitle }.orEmpty()

    /** The same login twice: same password and login, and the same site under whichever name it has. */
    fun sameAs(other: PasswordEntry): Boolean =
        password == other.password && username == other.username &&
            (Site.host(url) == Site.host(other.url) || shownTitle.equals(other.shownTitle, ignoreCase = true))

    /** Where [query] can be found: the names a person searches by, never the password or the note. */
    fun matches(query: String): Boolean =
        listOf(title, username, url).any { it.contains(query, ignoreCase = true) }
}

/** The add and edit form, as typed. */
internal data class EntryDraft(
    val title: String = "",
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val note: String = "",
) {
    /** The entry this draft would save as, or why it cannot. */
    fun validate(id: String, now: Long): Result<PasswordEntry> {
        if (password.isEmpty()) return Result.failure(DraftException(DraftError.NoPassword))
        if (title.isBlank() && url.isBlank() && username.isBlank()) return Result.failure(DraftException(DraftError.NoName))
        return Result.success(
            PasswordEntry(id, title.trim(), username.trim(), password, url.trim(), note.trimEnd(), now),
        )
    }

    companion object {
        fun of(entry: PasswordEntry) = EntryDraft(entry.title, entry.username, entry.password, entry.url, entry.note)
    }
}

internal enum class DraftError {
    /** A login with no password is not what this app keeps. */
    NoPassword,

    /** Neither a title, a site nor a login: nothing to find it by in the list. */
    NoName,

    /** Text read for import held no entries at all. */
    NothingRead,
}

internal class DraftException(val error: DraftError) : Exception(error.name)

internal object Site {
    /** `https://www.github.com/login` → `github.com`; anything that is not an address comes back trimmed. */
    fun host(url: String): String {
        val rest = url.trim().substringAfter("://")
        return rest.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':')
            .removePrefix("www.").lowercase()
    }
}

/**
 * The form's "Generate" (D529): [LENGTH] characters from the platform's secure random, with at
 * least one of each kind — a site that wants "a digit and a symbol" takes it as it is.
 *
 * The symbols leave out quotes, the comma and the backslash, so a password can be pasted into a
 * CSV file or a shell without anyone thinking about escaping. Letters and digits that look alike
 * (l, I, O, 0, 1) are left out too: a password is sometimes typed by hand off this screen.
 */
internal object Generator {
    const val LENGTH = 20

    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!#$%&*+-=?@^_"
    private val KINDS = listOf(LOWER, UPPER, DIGITS, SYMBOLS)
    private val ALL = KINDS.joinToString("")

    fun password(random: (Int) -> ByteArray = ::secureRandomBytes): String {
        while (true) {
            val chars = CharArray(LENGTH) { ALL[uniform(ALL.length, random)] }
            if (KINDS.all { kind -> chars.any { it in kind } }) return chars.concatToString()
        }
    }

    /** 0 until [bound], with no bias: bytes past the last whole multiple of [bound] are drawn again. */
    private fun uniform(bound: Int, random: (Int) -> ByteArray): Int {
        val limit = 256 - 256 % bound
        while (true) {
            val b = random(1)[0].toInt() and 0xFF
            if (b < limit) return b % bound
        }
    }
}
