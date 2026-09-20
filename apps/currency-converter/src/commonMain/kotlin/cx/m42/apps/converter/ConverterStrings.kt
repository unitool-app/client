package cx.m42.apps.converter

/** The converter's own words (D11). */
internal interface ConverterStrings {
    val amount: String
    val fromLabel: String
    val toLabel: String
    val swap: String
    val loading: String
    val offline: String
    val failed: String
    val retry: String

    /** The line above the converter's own block on the host's Settings page. */
    val settingsHint: String

    /**
     * The grey line under the answer. Whole phrase and not two fragments, because "from cache" is
     * an aside in English and a separate clause in Russian — assembling it at the call site is how
     * a translation ends up in the word order of the language it was translated from.
     */
    fun source(date: String, fromCache: Boolean): String
}

internal object ConverterStringsEn : ConverterStrings {
    override val amount = "Amount"
    override val fromLabel = "From"
    override val toLabel = "To"
    override val swap = "Swap"
    override val loading = "Loading rates…"
    override val offline = "Offline, and nothing cached yet"
    override val failed = "Could not get rates"
    override val retry = "Try again"
    override val settingsHint = "Which currency to start from"

    override fun source(date: String, fromCache: Boolean): String =
        if (fromCache) "ECB rates for $date · cached, offline" else "ECB rates for $date · live"
}

internal object ConverterStringsRu : ConverterStrings {
    override val amount = "Сумма"
    override val fromLabel = "Из"
    override val toLabel = "В"
    override val swap = "Поменять местами"
    override val loading = "Загружаем курсы…"
    override val offline = "Офлайн, и в кэше пока пусто"
    override val failed = "Не удалось получить курсы"
    override val retry = "Повторить"
    override val settingsHint = "С какой валюты начинать"

    override fun source(date: String, fromCache: Boolean): String =
        if (fromCache) "Курс ЕЦБ на $date · кэш, офлайн" else "Курс ЕЦБ на $date · сеть"
}

internal val ConverterStringTables: Map<String, ConverterStrings> = mapOf(
    "ru" to ConverterStringsRu,
    "en" to ConverterStringsEn,
)
