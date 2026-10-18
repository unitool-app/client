package cx.m42.apps.dates

/**
 * Everything the screen says about two dates, worked out once.
 *
 * The range is half-open — from the start up to, not including, the end — which is what "how many
 * days between" means: 1 to 2 October is one day. [inclusive] counts the last day as well, which is
 * what a holiday, a contract or a sick note means: 1 to 2 October is two days off. Every figure
 * below follows the same switch, so the lines never disagree with each other.
 */
internal data class DateSpan(
    /** Calendar days. Never negative: dates in the wrong order are swapped and [reversed] says so. */
    val days: Long,
    val years: Int,
    val months: Int,
    /** What is left after whole years and months, in the sense of [CalendarDate.plusMonths]. */
    val restDays: Int,
    /** Monday to Friday. Public holidays are not known here — they differ by country and by year. */
    val weekdays: Long,
    val weekendDays: Long,
    val reversed: Boolean,
) {
    val weeks: Long get() = days / 7
    val restOfWeek: Int get() = (days % 7).toInt()

    companion object {
        fun between(start: CalendarDate, end: CalendarDate, inclusive: Boolean): DateSpan {
            val reversed = end < start
            val from = if (reversed) end else start
            val to = (if (reversed) start else end).let { if (inclusive) it.plusDays(1) else it }

            // Whole months first, then the days left over — the way java.time's Period counts, so
            // 31 January to 1 March in a leap year is one month and one day.
            var totalMonths = (to.year - from.year) * 12 + (to.month - from.month)
            if (totalMonths > 0 && to.day < from.day) totalMonths--
            val anchor = from.plusMonths(totalMonths)
            val rest = (to.epochDay - anchor.epochDay).toInt()

            val days = to.epochDay - from.epochDay
            val weekdays = weekdaysFrom(from, days)
            return DateSpan(
                days = days,
                years = totalMonths / 12,
                months = totalMonths % 12,
                restDays = rest,
                weekdays = weekdays,
                weekendDays = days - weekdays,
                reversed = reversed,
            )
        }

        /** Mondays to Fridays among [count] days starting at [from]: five per whole week, then the tail one by one. */
        private fun weekdaysFrom(from: CalendarDate, count: Long): Long {
            var result = count / 7 * 5
            var dow = from.dayOfWeek
            repeat((count % 7).toInt()) {
                if (dow <= 5) result++
                dow = dow % 7 + 1
            }
            return result
        }
    }
}
