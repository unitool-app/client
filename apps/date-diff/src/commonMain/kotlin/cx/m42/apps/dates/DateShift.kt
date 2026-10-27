package cx.m42.apps.dates

/** What an amount is counted in. [Weekdays] steps over Saturdays and Sundays, not over holidays. */
internal enum class ShiftUnit { Days, Weeks, Months, Years, Weekdays }

/**
 * A date [amount] units later — or earlier, when [amount] is negative.
 *
 * [countStart] makes the date itself day one: 60 days from 1 October, the 1st included, ends on
 * 29 November rather than the 30th, and a year from 1 January 2026 ends on 31 December 2026. It is
 * what a deadline "within 10 days" or a contract "for one year" means.
 *
 * Months and years clamp the day, as [CalendarDate.plusMonths] does: 31 January plus a month is
 * the last day of February.
 */
internal fun CalendarDate.shift(amount: Int, unit: ShiftUnit, countStart: Boolean): CalendarDate {
    if (amount == 0) return this
    val step = if (amount > 0) 1L else -1L
    val moved = when (unit) {
        ShiftUnit.Days -> plusDays(amount.toLong())
        ShiftUnit.Weeks -> plusDays(amount * 7L)
        ShiftUnit.Months -> plusMonths(amount)
        ShiftUnit.Years -> plusMonths(amount * 12)
        ShiftUnit.Weekdays -> return shiftWeekdays(amount, countStart)
    }
    return if (countStart) moved.plusDays(-step) else moved
}

/** One day at a time: an amount is typed by hand, so it is a few hundred steps at the most that matter. */
private fun CalendarDate.shiftWeekdays(amount: Int, countStart: Boolean): CalendarDate {
    val step = if (amount > 0) 1L else -1L
    var left = if (amount > 0) amount else -amount
    if (countStart && dayOfWeek <= 5) left--
    var date = this
    while (left > 0) {
        date = date.plusDays(step)
        if (date.dayOfWeek <= 5) left--
    }
    return date
}
