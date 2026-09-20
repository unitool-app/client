package cx.m42.unitool.i18n

/** The language this app is written in; the other tables are translated from it. */
internal object StringsRu : Strings {
    override val lang = Lang.Ru

    override val menu = "Меню"
    override val openMenu = "Открыть меню"
    override val backAction = "Назад"
    override fun version(value: String) = "Версия $value"

    override val calculator = "Калькулятор"
    override val settings = "Настройки"

    override val calculatorError = "Ошибка"
    override val calculatorBackspace = "Удалить символ"
    override val decimalSeparator = ","

    override val settingsLanguage = "Язык"
    override val settingsLanguageHint = "Язык интерфейса на этом устройстве"
    override val settingsHaptics = "Вибрация"
    override val settingsHapticsHint = "Короткий отклик при нажатии клавиши"
    override val settingsAbout = "О приложении"
    override fun settingsBuild(value: String) = "Сборка $value"
}
