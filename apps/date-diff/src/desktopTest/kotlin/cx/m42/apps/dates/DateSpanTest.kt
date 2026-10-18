package cx.m42.apps.dates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DateSpanTest {

    private fun d(text: String) = CalendarDate.parse(text)!!

    @Test
    fun theLastDayCountsOnlyWhenAsked() {
        assertEquals(1, DateSpan.between(d("01.10.2026"), d("02.10.2026"), inclusive = false).days)
        assertEquals(2, DateSpan.between(d("01.10.2026"), d("02.10.2026"), inclusive = true).days)
        assertEquals(0, DateSpan.between(d("01.10.2026"), d("01.10.2026"), inclusive = false).days)
        assertEquals(1, DateSpan.between(d("01.10.2026"), d("01.10.2026"), inclusive = true).days)
    }

    @Test
    fun yearsMonthsAndDaysAgreeWithJavaTime() {
        val start = java.time.LocalDate.of(2023, 1, 1)
        val ends = (0L..900L step 7).map { start.plusDays(it) }
        listOf(start, java.time.LocalDate.of(2024, 1, 31), java.time.LocalDate.of(2023, 8, 31)).forEach { from ->
            ends.filter { it >= from }.forEach { to ->
                val period = java.time.Period.between(from, to)
                val span = DateSpan.between(
                    CalendarDate(from.year, from.monthValue, from.dayOfMonth),
                    CalendarDate(to.year, to.monthValue, to.dayOfMonth),
                    inclusive = false,
                )
                assertEquals(Triple(period.years, period.months, period.days), Triple(span.years, span.months, span.restDays), "$from → $to")
            }
        }
    }

    @Test
    fun aWholeMonthInclusiveIsOneMonth() {
        val span = DateSpan.between(d("01.01.2026"), d("31.01.2026"), inclusive = true)
        assertEquals(31, span.days)
        assertEquals(Triple(0, 1, 0), Triple(span.years, span.months, span.restDays))
    }

    @Test
    fun weekdaysAndWeekends() {
        // Monday 12 to Monday 19 October 2026: one whole week.
        val week = DateSpan.between(d("12.10.2026"), d("19.10.2026"), inclusive = false)
        assertEquals(5, week.weekdays)
        assertEquals(2, week.weekendDays)
        // Friday to Monday, both counted: Fri, Sat, Sun, Mon.
        val weekend = DateSpan.between(d("16.10.2026"), d("19.10.2026"), inclusive = true)
        assertEquals(2, weekend.weekdays)
        assertEquals(2, weekend.weekendDays)
        // All of 2026: 261 weekdays, 104 weekend days.
        val year = DateSpan.between(d("01.01.2026"), d("31.12.2026"), inclusive = true)
        assertEquals(365, year.days)
        assertEquals(261, year.weekdays)
        assertEquals(104, year.weekendDays)
    }

    @Test
    fun datesInTheWrongOrderAreSwappedAndSaySo() {
        val forward = DateSpan.between(d("01.03.2026"), d("18.10.2026"), inclusive = false)
        val backward = DateSpan.between(d("18.10.2026"), d("01.03.2026"), inclusive = false)
        assertFalse(forward.reversed)
        assertTrue(backward.reversed)
        assertEquals(forward.copy(reversed = true), backward)
    }

    @Test
    fun russianPluralsFollowTheLastTwoDigits() {
        val ru = DatesStringsRu
        assertEquals("1 день", ru.days(1))
        assertEquals("3 дня", ru.days(3))
        assertEquals("5 дней", ru.days(5))
        assertEquals("11 дней", ru.days(11))
        assertEquals("14 дней", ru.days(14))
        assertEquals("21 день", ru.days(21))
        assertEquals("112 дней", ru.days(112))
        assertEquals("122 дня", ru.days(122))
        assertEquals("1 год 2 месяца 3 дня", monthsText(DateSpan.between(d("15.05.2024"), d("18.07.2025"), false), ru))
        assertEquals("2 недели 3 дня", weeksText(DateSpan.between(d("01.10.2026"), d("18.10.2026"), false), ru))
        assertEquals("0 дней", monthsText(DateSpan.between(d("01.10.2026"), d("01.10.2026"), false), ru))
    }
}
