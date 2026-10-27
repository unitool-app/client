package cx.m42.apps.dates

/**
 * Everything the screen says about two dates, worked out once.
 *
 * Each boundary is counted or not on its own. The default — the first day in, the last day out — is
 * what "how many days between" means: 1 to 2 October is one day. Both in is what a holiday, a
 * contract or a sick note means: 1 to 2 October is two days off. Both out is "how many days lie
 * between them": 1 to 3 October has one, the 2nd. Every figure below is taken over the same counted
 * days, so the lines never disagree with each other.
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
    /** The first and the last day that were counted; null when no day was, e.g. both ends left out of 1–2 October. */
    val counted: Pair<CalendarDate, CalendarDate>?,
) {
    val weeks: Long get() = days / 7
    val restOfWeek: Int get() = (days % 7).toInt()

    companion object {
        /** [includeStart] and [includeEnd] are about the earlier and the later date, whichever field holds them. */
        fun between(
            start: CalendarDate,
            end: CalendarDate,
            includeStart: Boolean = true,
            includeEnd: Boolean = false,
        ): DateSpan {
            val reversed = end < start
            // The counted days as a half-open range [from, to); empty when the ends left out meet.
            val from = (if (reversed) end else start).let { if (includeStart) it else it.plusDays(1) }
            val to = (if (reversed) start else end).let { if (includeEnd) it.plusDays(1) else it }.let { maxOf(it, from) }

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
                counted = if (days > 0) from to to.plusDays(-1) else null,
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
