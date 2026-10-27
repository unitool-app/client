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
    val includeStart: String
    val includeEnd: String
    val countedDays: String
    val noDayCounted: String
    val tabDifference: String
    val tabShift: String
    val base: String
    val add: String
    val subtract: String
    val amount: String
    val countStart: String
    val enterAmount: String
    val notAnAmount: String
    val continueFrom: String
    val weekdaysHint: String

    fun unit(unit: ShiftUnit): String

    /** "через 92 дня" / "92 дня назад" — how far the result is, whatever unit was asked for. */
    fun distance(days: Long): String
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
    override val includeStart = "Count the first day"
    override val includeEnd = "Count the last day"
    override val countedDays = "Days counted"
    override val noDayCounted = "none"
    override val tabDifference = "Difference"
    override val tabShift = "Plus / minus"
    override val base = "Date"
    override val add = "+ Add"
    override val subtract = "− Subtract"
    override val amount = "How many"
    override val countStart = "The date itself is day one"
    override val enterAmount = "Enter the date and how many"
    override val notAnAmount = "A whole number, up to five digits"
    override val continueFrom = "Continue from this date"
    override val weekdaysHint = "Weekdays skip Saturdays and Sundays; public holidays are not known."

    override fun unit(unit: ShiftUnit) = when (unit) {
        ShiftUnit.Days -> "Days"
        ShiftUnit.Weeks -> "Weeks"
        ShiftUnit.Months -> "Months"
        ShiftUnit.Years -> "Years"
        ShiftUnit.Weekdays -> "Weekdays"
    }

    override fun distance(days: Long) = when {
        days > 0 -> "in ${days(days)}"
        days < 0 -> "${days(-days)} earlier"
        else -> "the same day"
    }
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
    override val includeStart = "Включая первый день"
    override val includeEnd = "Включая последний день"
    override val countedDays = "Считаются дни"
    override val noDayCounted = "ни одного"
    override val tabDifference = "Разница"
    override val tabShift = "Плюс / минус"
    override val base = "Дата"
    override val add = "+ Прибавить"
    override val subtract = "− Вычесть"
    override val amount = "Сколько"
    override val countStart = "Сама дата — первый день"
    override val enterAmount = "Введите дату и сколько прибавить"
    override val notAnAmount = "Нужно целое число, до пяти цифр"
    override val continueFrom = "Продолжить от этой даты"
    override val weekdaysHint = "Рабочие дни — без суббот и воскресений; государственные праздники не учитываются."

    override fun unit(unit: ShiftUnit) = when (unit) {
        ShiftUnit.Days -> "Дни"
        ShiftUnit.Weeks -> "Недели"
        ShiftUnit.Months -> "Месяцы"
        ShiftUnit.Years -> "Годы"
        ShiftUnit.Weekdays -> "Будни"
    }

    override fun distance(days: Long) = when {
        days > 0 -> "через ${days(days)}"
        days < 0 -> "${days(-days)} назад"
        else -> "тот же день"
    }
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
