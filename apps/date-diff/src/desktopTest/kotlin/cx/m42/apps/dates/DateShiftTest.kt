package cx.m42.apps.dates

import kotlin.test.Test
import kotlin.test.assertEquals

class DateShiftTest {

    private fun d(text: String) = CalendarDate.parse(text)!!

    @Test
    fun daysWeeksMonthsAndYears() {
        assertEquals(d("30.11.2026"), d("01.10.2026").shift(60, ShiftUnit.Days, countStart = false))
        assertEquals(d("02.08.2026"), d("01.10.2026").shift(-60, ShiftUnit.Days, countStart = false))
        assertEquals(d("15.10.2026"), d("01.10.2026").shift(2, ShiftUnit.Weeks, countStart = false))
        assertEquals(d("28.02.2026"), d("31.01.2026").shift(1, ShiftUnit.Months, countStart = false))
        assertEquals(d("28.02.2025"), d("29.02.2024").shift(1, ShiftUnit.Years, countStart = false))
        assertEquals(d("01.10.2026"), d("01.10.2026").shift(0, ShiftUnit.Weekdays, countStart = true))
    }

    @Test
    fun theDateItselfAsDayOne() {
        // 60 days starting on the 1st, the 1st included, end on the 29th of November.
        assertEquals(d("29.11.2026"), d("01.10.2026").shift(60, ShiftUnit.Days, countStart = true))
        assertEquals(d("01.10.2026"), d("01.10.2026").shift(1, ShiftUnit.Days, countStart = true))
        // A year from 1 January is through 31 December.
        assertEquals(d("31.12.2026"), d("01.01.2026").shift(1, ShiftUnit.Years, countStart = true))
        // Backwards the same way: the 10 days up to and including the 10th start on the 1st.
        assertEquals(d("01.10.2026"), d("10.10.2026").shift(-10, ShiftUnit.Days, countStart = true))
    }

    @Test
    fun weekdaysStepOverWeekends() {
        // Friday 16 October 2026 plus one weekday is Monday the 19th.
        assertEquals(d("19.10.2026"), d("16.10.2026").shift(1, ShiftUnit.Weekdays, countStart = false))
        assertEquals(d("30.10.2026"), d("16.10.2026").shift(10, ShiftUnit.Weekdays, countStart = false))
        // Counting Friday itself as the first: ten weekdays end on Thursday the 29th.
        assertEquals(d("29.10.2026"), d("16.10.2026").shift(10, ShiftUnit.Weekdays, countStart = true))
        // A Saturday is not a weekday, so it cannot be day one: same as without the switch.
        assertEquals(d("19.10.2026"), d("17.10.2026").shift(1, ShiftUnit.Weekdays, countStart = true))
        // Monday minus one weekday is the Friday before.
        assertEquals(d("16.10.2026"), d("19.10.2026").shift(-1, ShiftUnit.Weekdays, countStart = false))
    }

    @Test
    fun weekdaysAgreeWithTheDifference() {
        val from = d("01.01.2026")
        listOf(1, 5, 7, 23, 261).forEach { n ->
            val to = from.shift(n, ShiftUnit.Weekdays, countStart = true)
            assertEquals(n.toLong(), DateSpan.between(from, to, includeEnd = true).weekdays, "$n")
        }
    }
}
