package cx.m42.apps.dates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalendarDateTest {

    @Test
    fun epochDaysRoundTripAcrossCenturiesAndLeapDays() {
        assertEquals(0L, CalendarDate(1970, 1, 1).epochDay)
        assertEquals(CalendarDate(2000, 2, 29), CalendarDate.ofEpochDay(CalendarDate(2000, 2, 29).epochDay))
        // Every day from 1899 to 2101 against java.time, which is the reference this replaces.
        var day = java.time.LocalDate.of(1899, 1, 1)
        while (day.year < 2101) {
            val ours = CalendarDate(day.year, day.monthValue, day.dayOfMonth)
            assertEquals(day.toEpochDay(), ours.epochDay, "$day")
            assertEquals(ours, CalendarDate.ofEpochDay(day.toEpochDay()))
            assertEquals(day.dayOfWeek.value, ours.dayOfWeek, "$day")
            day = day.plusDays(1)
        }
    }

    @Test
    fun monthsClampToTheShorterMonth() {
        assertEquals(CalendarDate(2024, 2, 29), CalendarDate(2024, 1, 31).plusMonths(1))
        assertEquals(CalendarDate(2025, 2, 28), CalendarDate(2025, 1, 31).plusMonths(1))
        assertEquals(CalendarDate(2025, 11, 30), CalendarDate(2026, 1, 30).plusMonths(-2))
    }

    @Test
    fun todayIsTheWallClockDateNotTheUtcOne() {
        // 2026-10-17 22:30 UTC is already the 18th in Moscow and still the 17th in New York.
        val instant = java.time.Instant.parse("2026-10-17T22:30:00Z").toEpochMilli()
        assertEquals(CalendarDate(2026, 10, 18), CalendarDate.today(instant, 3 * 3_600_000L))
        assertEquals(CalendarDate(2026, 10, 17), CalendarDate.today(instant, -4 * 3_600_000L))
    }

    @Test
    fun parsingAcceptsWhatPeopleType() {
        val expected = CalendarDate(2026, 10, 18)
        listOf("18.10.2026", " 18.10.2026 ", "18/10/2026", "18-10-2026", "18 10 2026", "18,10,2026",
            "18.10.26", "18102026", "2026-10-18", "18.10.2026.").forEach {
            assertEquals(expected, CalendarDate.parse(it), it)
        }
        assertEquals(CalendarDate(2026, 3, 1), CalendarDate.parse("1.3.2026"))
    }

    @Test
    fun parsingRefusesWhatIsNotADate() {
        listOf("", "18.10", "31.02.2026", "29.02.2025", "18.13.2026", "00.10.2026", "18.10.202", "abc",
            "18.10.2026 12:00", "1810202").forEach {
            assertNull(CalendarDate.parse(it), it)
        }
    }
}
