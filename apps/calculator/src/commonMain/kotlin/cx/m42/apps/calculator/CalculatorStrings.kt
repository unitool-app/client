package cx.m42.apps.calculator

/**
 * The calculator's own words (D11).
 *
 * These used to be four entries in Unitool's single `Strings` table. They moved here with the app,
 * because an app that needs a line added to the host's table to name its own error message is an
 * app that cannot be installed anywhere else.
 */
internal interface CalculatorStrings {
    /** What the display reads after an impossible sum. */
    val error: String

    /** Spoken label of the ⌫ key, which has a glyph rather than a word. */
    val backspace: String

    /**
     * The mark between a whole number and its fraction — a full stop in English, a comma in
     * Russian. The one piece of this app that is language and not arithmetic: the state and
     * [format] work in ASCII throughout, and the display and the key swap this in at the last
     * moment, so nothing downstream has to parse a locale.
     */
    val decimalSeparator: String

    /** The subtitle the host's frame shows when the config asked for the scientific row. */
    val scientific: String
}

internal object CalculatorStringsEn : CalculatorStrings {
    override val error = "Error"
    override val backspace = "Delete character"
    override val decimalSeparator = "."
    override val scientific = "Scientific"
}

internal object CalculatorStringsRu : CalculatorStrings {
    override val error = "Ошибка"
    override val backspace = "Удалить символ"
    override val decimalSeparator = ","
    override val scientific = "Инженерный"
}

/**
 * Both tables by primary subtag, for `runtime.locale.pick`.
 *
 * The interface above is what makes "every language has every key" a compile error rather than a
 * test — which is why the only i18n test worth writing is that an unknown tag falls back instead
 * of throwing.
 */
internal val CalculatorStringTables: Map<String, CalculatorStrings> = mapOf(
    "ru" to CalculatorStringsRu,
    "en" to CalculatorStringsEn,
)
