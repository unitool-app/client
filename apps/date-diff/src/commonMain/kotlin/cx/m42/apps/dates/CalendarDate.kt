package cx.m42.apps.dates

/**
 * A day on the proleptic Gregorian calendar, with no time and no zone.
 *
 * Written here rather than taken from kotlinx-datetime: the app needs a date, a day count and a
 * weekday, and that is forty lines of well-known arithmetic (H. Hinnant's `days_from_civil`) against
 * a dependency on every target. The one thing the library would have given for free — what "today"
 * is in this zone — is [localUtcOffsetMillis], one line per platform.
 */
internal data class CalendarDate(val year: Int, val month: Int, val day: Int) : Comparable<CalendarDate> {

    init {
        require(month in 1..12 && day in 1..lengthOfMonth(year, month)) { "no such date: $year-$month-$day" }
    }

    /** Days since 1970-01-01, negative before it. */
    val epochDay: Long
        get() {
            val y = (if (month <= 2) year - 1 else year).toLong()
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (month + if (month > 2) -3 else 9) + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097 + doe - 719_468
        }

    /** ISO weekday: 1 is Monday, 7 is Sunday. 1970-01-01 was a Thursday. */
    val dayOfWeek: Int get() = (epochDay + 3).mod(7L).toInt() + 1

    fun plusDays(days: Long): CalendarDate = ofEpochDay(epochDay + days)

    /** [months] later, with the day clamped to the month's length: 31 January plus one is 28 or 29 February. */
    fun plusMonths(months: Int): CalendarDate {
        val total = year * 12 + (month - 1) + months
        val y = total.floorDiv(12)
        val m = total.mod(12) + 1
        return CalendarDate(y, m, minOf(day, lengthOfMonth(y, m)))
    }

    override fun compareTo(other: CalendarDate): Int = epochDay.compareTo(other.epochDay)

    /** ISO 8601, which is also what the session snapshot keeps. */
    override fun toString(): String =
        "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"

    companion object {
        fun isLeap(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

        fun lengthOfMonth(year: Int, month: Int): Int = when (month) {
            2 -> if (isLeap(year)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

        fun ofEpochDay(epochDay: Long): CalendarDate {
            val z = epochDay + 719_468
            val era = (if (z >= 0) z else z - 146_096) / 146_097
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val y = (yoe + era * 400).toInt() + if (m <= 2) 1 else 0
            return CalendarDate(y, m, d)
        }

        /** The date it is now on this device's wall clock — the zone matters near midnight. */
        fun today(epochMillis: Long, offsetMillis: Long): CalendarDate =
            ofEpochDay((epochMillis + offsetMillis).floorDiv(86_400_000L))

        fun of(year: Int, month: Int, day: Int): CalendarDate? =
            if (month in 1..12 && day in 1..lengthOfMonth(year, month)) CalendarDate(year, month, day) else null

        /**
         * What a person types for a date, read leniently.
         *
         * Day first, the way Russia and most of the world write it — `18.10.2026`, `18/10/26`,
         * `18-10-2026`, `18 10 2026`, or eight bare digits `18102026`. A first group of four digits
         * is ISO instead: `2026-10-18`. A two-digit year is this century. Nothing is guessed beyond
         * that, and the screen prints the date it read under the field, so a US reader who typed
         * month first sees "10 June" rather than a silent wrong answer.
         */
        fun parse(text: String): CalendarDate? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val parts = if (trimmed.all { it.isDigit() } && trimmed.length == 8) {
                listOf(trimmed.substring(0, 2), trimmed.substring(2, 4), trimmed.substring(4))
            } else {
                trimmed.split('.', '/', '-', ' ', ',').filter { it.isNotEmpty() }
            }
            if (parts.size != 3 || parts.any { part -> part.length > 4 || !part.all { it.isDigit() } }) return null
            val numbers = parts.map { it.toInt() }
            return if (parts[0].length == 4) {
                of(numbers[0], numbers[1], numbers[2])
            } else {
                val year = when (parts[2].length) {
                    2 -> 2000 + numbers[2]
                    4 -> numbers[2]
                    else -> return null
                }
                of(year, numbers[1], numbers[0])
            }
        }
    }
}

/**
 * Milliseconds to add to UTC to get this device's wall clock at [epochMillis] — summer time
 * included, which is why it takes the instant rather than being a constant.
 */
internal expect fun localUtcOffsetMillis(epochMillis: Long): Long
