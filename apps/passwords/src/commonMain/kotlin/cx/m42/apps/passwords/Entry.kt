package cx.m42.apps.passwords

import cx.m42.unitool.kit.password.PasswordMode
import cx.m42.unitool.kit.password.PasswordRules
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
    fun sameAs(other: PasswordEntry): Boolean = password == other.password && sameLogin(other)

    /** The same login on the same site, whatever its password — an import that may change it. */
    fun sameLogin(other: PasswordEntry): Boolean =
        username == other.username &&
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
 * The generator's settings as the app stores them (plain storage: they are no secret), one
 * `key=value` per `;`. Anything unreadable falls back to the default, so an older or a damaged
 * value never stops the form from opening.
 */
internal object GeneratorSettings {
    fun encode(rules: PasswordRules): String = listOf(
        "mode" to rules.mode.name,
        "length" to rules.length,
        "words" to rules.words,
        "kinds" to buildString {
            if (rules.lower) append('l')
            if (rules.upper) append('u')
            if (rules.digits) append('d')
            if (rules.symbols) append('s')
        },
        "lookalike" to rules.lookAlike,
    ).joinToString(";") { (k, v) -> "$k=$v" }

    fun decode(text: String?): PasswordRules {
        val fields = text.orEmpty().split(';').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }
            .associate { (k, v) -> k to v }
        val default = PasswordRules()
        val kinds = fields["kinds"]
        val rules = PasswordRules(
            mode = PasswordMode.entries.firstOrNull { it.name == fields["mode"] } ?: default.mode,
            length = fields["length"]?.toIntOrNull() ?: default.length,
            words = fields["words"]?.toIntOrNull() ?: default.words,
            lower = kinds?.let { 'l' in it } ?: default.lower,
            upper = kinds?.let { 'u' in it } ?: default.upper,
            digits = kinds?.let { 'd' in it } ?: default.digits,
            symbols = kinds?.let { 's' in it } ?: default.symbols,
            lookAlike = fields["lookalike"]?.toBooleanStrictOrNull() ?: default.lookAlike,
        )
        return if (rules.problem == null && rules.kinds.isNotEmpty()) rules else default
    }
}
