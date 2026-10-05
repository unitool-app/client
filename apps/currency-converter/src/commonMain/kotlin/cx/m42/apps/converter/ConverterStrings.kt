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
     *
     * [credit] is the publisher the table names, when it names one (`Rates.credit`).
     */
    fun source(date: String, fromCache: Boolean, credit: String?): String
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

    override fun source(date: String, fromCache: Boolean, credit: String?): String {
        val rates = if (credit != null) "Rates by $credit for $date" else "Rates for $date"
        return if (fromCache) "$rates · cached, offline" else "$rates · live"
    }
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

    override fun source(date: String, fromCache: Boolean, credit: String?): String {
        val rates = if (credit != null) "Курс от $credit на $date" else "Курс на $date"
        return if (fromCache) "$rates · кэш, офлайн" else "$rates · сеть"
    }
}

internal val ConverterStringTables: Map<String, ConverterStrings> = mapOf(
    "ru" to ConverterStringsRu,
    "en" to ConverterStringsEn,
)
