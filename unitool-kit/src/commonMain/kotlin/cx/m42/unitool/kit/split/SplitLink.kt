package cx.m42.unitool.kit.split

import kotlin.io.encoding.Base64

/**
 * A part as an ordinary-looking link (.ai-notes/done/split 02 §2–§3):
 *
 * ```
 * https://example.com/p#AXq8Zr0m…      ← the 48 bytes as 64 base64url characters, after '#'
 * ```
 *
 * The part sits in the fragment because browsers and HTTP clients never send the fragment to the
 * server (RFC 3986 §3.5): a stranger who scans the code with an ordinary camera shows the server
 * only the path. Firefox Send, Bitwarden Send and MEGA keep their keys in links the same way.
 *
 * The address is whoever generates the parts' choice; there is no default (question 172). Reading
 * ignores it entirely: a part is found by its fragment on any domain, or as the 64 characters alone.
 */
public object SplitLink {

    /** Characters a part takes in a link: 48 bytes of base64url, no padding. */
    public const val PART_LENGTH: Int = 64

    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /** Why [base] cannot carry a part, or null when it can: an absolute http(s) address with no '#'. */
    public fun checkBase(base: String): BaseProblem? {
        val scheme = base.substringBefore("://", "").lowercase()
        return when {
            base.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f } -> BaseProblem.Spaces
            scheme != "http" && scheme != "https" -> BaseProblem.NotHttp
            base.substringAfter("://").takeWhile { it != '/' && it != '?' }.isEmpty() -> BaseProblem.NoHost
            '#' in base -> BaseProblem.Fragment
            else -> null
        }
    }

    /** [part] under [base]. */
    public fun format(part: SplitPart, base: String): String {
        checkBase(base)?.let { throw IllegalArgumentException("'$base' cannot carry a part: $it") }
        return base + "#" + base64.encode(part.bytes())
    }

    /**
     * The part inside [text]: a link on any domain or the 64 characters alone, with spaces and line
     * breaks around it allowed. A '#' is looked for last, so a part never hides inside a path.
     */
    public fun read(text: String): PartReading {
        val trimmed = text.trim()
        val candidate = if ('#' in trimmed) trimmed.substringAfterLast('#') else trimmed
        if (candidate.length != PART_LENGTH || candidate.any { it !in ALPHABET }) return PartReading.NotAPart
        val bytes = try {
            base64.decode(candidate)
        } catch (_: IllegalArgumentException) {
            return PartReading.NotAPart
        }
        return SplitPart.read(bytes)
    }

    private val ALPHABET: Set<Char> = (('A'..'Z') + ('a'..'z') + ('0'..'9') + '-' + '_').toSet()
}

/** Why an address cannot carry a part; each user interface words it its own way. */
public enum class BaseProblem {
    /** Not `http://` or `https://`. */
    NotHttp,

    /** Nothing between `://` and the path. */
    NoHost,

    /** A '#' of its own: the part must be the only fragment. */
    Fragment,

    /** Spaces or control characters: a scanner would cut the link there. */
    Spaces,
}
