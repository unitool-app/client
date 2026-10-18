package cx.m42.apps.dates

/**
 * The app's own words (D11).
 *
 * Counts are functions rather than strings: Russian has three plural forms ("1 день", "2 дня",
 * "5 дней") and a table of fixed words would print "21 дней".
 */
internal interface DatesStrings {
    val start: String
    val end: String
    val placeholder: String
    val today: String
    val notADate: String
    val inclusive: String
    val enterBoth: String
    val reversed: String
    val result: String
    val inWeeks: String
    val inMonths: String
    val weekdays: String
    val weekends: String
    val holidaysHint: String

    fun days(n: Long): String
    fun weeks(n: Long): String
    fun months(n: Int): String
    fun years(n: Int): String

    /** "вс, 18 октября 2026" — how the date read from a field is echoed under it. */
    fun longDate(date: CalendarDate): String
}

internal object DatesStringsEn : DatesStrings {
    override val start = "From"
    override val end = "To"
    override val placeholder = "dd.mm.yyyy"
    override val today = "Today"
    override val notADate = "Not a date — day, month, year: 18.10.2026"
    override val inclusive = "Count the last day too"
    override val enterBoth = "Enter both dates"
    override val reversed = "The end is before the start — counted backwards"
    override val result = "Difference"
    override val inWeeks = "In weeks"
    override val inMonths = "Years, months, days"
    override val weekdays = "Weekdays (Mon–Fri)"
    override val weekends = "Weekend days (Sat–Sun)"
    override val holidaysHint = "Public holidays are not taken into account."

    override fun days(n: Long) = "$n " + if (n == 1L) "day" else "days"
    override fun weeks(n: Long) = "$n " + if (n == 1L) "week" else "weeks"
    override fun months(n: Int) = "$n " + if (n == 1) "month" else "months"
    override fun years(n: Int) = "$n " + if (n == 1) "year" else "years"

    private val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    override fun longDate(date: CalendarDate) =
        "${dayNames[date.dayOfWeek - 1]}, ${date.day} ${monthNames[date.month - 1]} ${date.year}"
}

internal object DatesStringsRu : DatesStrings {
    override val start = "С"
    override val end = "По"
    override val placeholder = "дд.мм.гггг"
    override val today = "Сегодня"
    override val notADate = "Не похоже на дату — день, месяц, год: 18.10.2026"
    override val inclusive = "Включая последний день"
    override val enterBoth = "Введите обе даты"
    override val reversed = "Конец раньше начала — посчитано в обратную сторону"
    override val result = "Разница"
    override val inWeeks = "В неделях"
    override val inMonths = "Лет, месяцев, дней"
    override val weekdays = "Будних дней (пн–пт)"
    override val weekends = "Выходных (сб–вс)"
    override val holidaysHint = "Государственные праздники не учитываются."

    override fun days(n: Long) = "$n " + plural(n, "день", "дня", "дней")
    override fun weeks(n: Long) = "$n " + plural(n, "неделя", "недели", "недель")
    override fun months(n: Int) = "$n " + plural(n.toLong(), "месяц", "месяца", "месяцев")
    override fun years(n: Int) = "$n " + plural(n.toLong(), "год", "года", "лет")

    private val monthNames = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )
    private val dayNames = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

    override fun longDate(date: CalendarDate) =
        "${dayNames[date.dayOfWeek - 1]}, ${date.day} ${monthNames[date.month - 1]} ${date.year}"

    /** One for 1, 21, 101; few for 2–4, 22–24; many for the rest, the teens included. */
    private fun plural(n: Long, one: String, few: String, many: String): String {
        val tens = n % 100
        val units = n % 10
        return when {
            tens in 11..14 -> many
            units == 1L -> one
            units in 2..4 -> few
            else -> many
        }
    }
}

internal val DatesStringTables: Map<String, DatesStrings> = mapOf(
    "ru" to DatesStringsRu,
    "en" to DatesStringsEn,
)
