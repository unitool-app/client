package cx.m42.unitool.i18n

internal object StringsEn : Strings {
    override val lang = Lang.En

    override val menu = "Menu"
    override val openMenu = "Open menu"
    override val backAction = "Back"
    override fun version(value: String) = "Version $value"

    override val calculator = "Calculator"
    override val settings = "Settings"

    override val calculatorError = "Error"
    override val calculatorBackspace = "Delete character"
    override val decimalSeparator = "."

    override val settingsLanguage = "Language"
    override val settingsLanguageHint = "Interface language on this device"
    override val settingsHaptics = "Haptics"
    override val settingsHapticsHint = "A short tap response on every key"
    override val settingsAbout = "About"
    override fun settingsBuild(value: String) = "Build $value"
}
