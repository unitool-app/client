package cx.m42.unitool.i18n

/**
 * The languages the app is written in.
 *
 * A closed enum rather than an open locale tag: every string the app owns exists in exactly these,
 * guaranteed by [Strings] being an interface — a language added here does not compile until its
 * table is complete.
 *
 * [tag] is the BCP-47 primary subtag. [nativeName] is how the language names itself: a language
 * picker written in the language the reader is trying to leave is useless.
 */
enum class Lang(val tag: String, val nativeName: String) {
    Ru("ru", "Русский"),
    En("en", "English"),
    ;

    /** The table this language is written in. */
    internal fun table(): Strings = when (this) {
        Ru -> StringsRu
        En -> StringsEn
    }

    companion object {
        /**
         * The language a tag asks for, or null when nothing here matches.
         *
         * Matching is on the primary subtag only and case-insensitively, because the tag can arrive
         * with several spellings of the same wish: `ru`, `ru-RU`, `ru_RU` (Android's
         * `Locale.toString()`). Null is deliberate and is not the same as [En]: "this device speaks
         * a language we do not have" is what makes the caller fall through to the next source of an
         * answer instead of stopping at a guess.
         */
        fun fromTag(tag: String?): Lang? {
            val primary = tag?.trim()?.takeIf { it.isNotEmpty() }
                ?.substringBefore('-')?.substringBefore('_')
                ?.lowercase()
                ?: return null
            return entries.firstOrNull { it.tag == primary }
        }
    }
}
