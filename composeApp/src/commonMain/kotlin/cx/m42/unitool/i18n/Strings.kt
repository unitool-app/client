package cx.m42.unitool.i18n

/**
 * Every word the app writes for itself, in one table per language.
 *
 * ### Why an interface and not a resource file
 *
 * Compose's own `stringResource` is `@Composable`, and text is assembled outside composition too —
 * by pure functions that screens *and* tests call directly. An interface answers everywhere, needs
 * no code generation, and hands the completeness check to the compiler: a language whose table is
 * missing an entry does not build, which is a stronger guarantee than any test over a `strings.xml`
 * could give.
 *
 * ### Rules for adding to this file
 *
 * - **Whole phrases, never fragments.** A sentence assembled from a noun and a verb at the call site
 *   is a sentence that only works in the language it was written in.
 * - **Name after the meaning, not the words** — a table is re-read by somebody who cannot read every
 *   language in it.
 * - Anything interpolated is a function parameter, so no language is forced into the word order of
 *   the one it was translated from.
 */
interface Strings {

    /** This table's language. Lets a screen name the language it is showing without a lookup. */
    val lang: Lang

    // ---------------------------------------------------------------- chrome

    /** The drawer's own heading — also what a test looks for to know the drawer is open. */
    val menu: String

    /** Accessibility label of the burger. */
    val openMenu: String

    /** Accessibility label of the back chevron. */
    val backAction: String

    /** The build stamp in the drawer's footer. */
    fun version(value: String): String

    // ---------------------------------------------------------------- destinations

    val calculator: String
    val settings: String

    // ---------------------------------------------------------------- calculator

    /** What the display reads after an impossible sum — a division by zero. */
    val calculatorError: String

    /** Accessibility label of the ⌫ key, which has a glyph rather than a word. */
    val calculatorBackspace: String

    /**
     * The mark between a whole number and its fraction — a full stop in English, a comma in
     * Russian. It is the one piece of the calculator that is language and not arithmetic: the
     * state and [cx.m42.unitool.calculator.format] work in ASCII throughout, and the display and
     * the key swap this in at the last moment, so nothing downstream has to parse a locale.
     */
    val decimalSeparator: String

    // ---------------------------------------------------------------- settings

    val settingsLanguage: String
    val settingsLanguageHint: String
    val settingsHaptics: String
    val settingsHapticsHint: String
    val settingsAbout: String

    /** The line under [settingsAbout]: which build this is. */
    fun settingsBuild(value: String): String
}
