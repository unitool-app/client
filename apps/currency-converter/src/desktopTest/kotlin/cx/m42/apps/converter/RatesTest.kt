package cx.m42.apps.converter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The conversion arithmetic and the parser, with no network in sight.
 *
 * Both are pure functions over values, which is the whole reason they are separate from the
 * repository: rates arriving is a hard thing to test, and dividing by a rate is not.
 */
class RatesTest {

    private val rates = Rates(
        base = "USD",
        date = "2026-09-19",
        rates = mapOf("EUR" to 0.92, "GBP" to 0.79, "RUB" to 92.10, "VND" to 26000.0),
    )

    @Test
    fun convertingFromTheBaseIsAMultiplication() {
        assertEquals(92.0, rates.convert(100.0, "USD", "EUR"))
    }

    @Test
    fun convertingBetweenTwoQuotedCurrenciesGoesThroughTheBase() {
        // The only currency every rate is quoted against, so it is the only route between two that
        // are not it.
        val result = rates.convert(92.0, "EUR", "RUB")!!
        assertTrue(kotlin.math.abs(result - 9210.0) < 0.001, "got $result")
    }

    @Test
    fun dongsAndRublesConvertBothWays() {
        val rubles = rates.convert(1_000_000.0, "VND", "RUB")!!
        assertTrue(kotlin.math.abs(rubles - 3542.3077) < 0.001, "got $rubles")
        val dongs = rates.convert(rubles, "RUB", "VND")!!
        assertTrue(kotlin.math.abs(dongs - 1_000_000.0) < 0.001, "got $dongs")
    }

    @Test
    fun aCurrencyNobodyQuotedHasNoAnswerRatherThanZero() {
        assertNull(rates.convert(10.0, "USD", "XYZ"))
        assertNull(rates.convert(10.0, "XYZ", "USD"))
    }

    @Test
    fun convertingACurrencyToItselfChangesNothing() {
        assertEquals(10.0, rates.convert(10.0, "RUB", "RUB"))
    }

    @Test
    fun theBaseIsOneByDefinitionEvenThoughThePayloadOmitsIt() {
        assertEquals(1.0, rates.rateOf("USD"))
    }

    @Test
    fun theFrankfurterEnvelopeIsParsedIntoATable() {
        val parsed = RatesRepository.parse(
            """{"amount":1.0,"base":"USD","date":"2026-09-19","rates":{"EUR":0.92,"RUB":92.1}}""",
            fallbackBase = "XXX",
            fetchedAt = 7,
        )
        assertEquals("USD", parsed.base)
        assertEquals("2026-09-19", parsed.date)
        assertEquals(0.92, parsed.rates["EUR"])
        assertEquals(7, parsed.fetchedAt)
        // The ECB asks for no credit, and nothing in its envelope says otherwise.
        assertNull(parsed.credit)
    }

    @Test
    fun theExchangeRateApiEnvelopeIsParsedIntoTheSameTable() {
        val parsed = RatesRepository.parse(
            """{"result":"success","provider":"https://www.exchangerate-api.com",""" +
                """"time_last_update_unix":1791158551,"time_last_update_utc":"Mon, 05 Oct 2026 00:02:31 +0000",""" +
                """"base_code":"USD","rates":{"USD":1,"RUB":83.817169,"VND":25949.123093}}""",
            fallbackBase = "XXX",
            fetchedAt = 7,
        )
        assertEquals("USD", parsed.base)
        assertEquals("2026-10-05", parsed.date)
        assertEquals(25949.123093, parsed.rates["VND"])
        assertEquals(1.0, parsed.rates["USD"])
        assertEquals("Exchange Rate API", parsed.credit)
    }

    @Test
    fun anExchangeRateApiErrorIsNotRates() {
        val failed = runCatching {
            RatesRepository.parse("""{"result":"error","error-type":"unsupported-code"}""", "USD", 0)
        }
        assertTrue(failed.isFailure)
    }

    @Test
    fun aUnixTimeIsTheUtcDayItFallsOn() {
        assertEquals("1970-01-01", isoDate(0))
        assertEquals("1969-12-31", isoDate(-3600))
        assertEquals("2000-02-29", isoDate(951_825_600))
        assertEquals("2026-09-19", isoDate(1_789_776_151))
        assertEquals("2026-10-05", isoDate(1_791_158_551))
    }

    @Test
    fun aResponseWithNoRatesIsNotRatesAndSaysSo() {
        // The endpoint answering 200 with something else is the failure mode a status check misses.
        val failed = runCatching { RatesRepository.parse("""{"base":"USD"}""", "USD", 0) }
        assertTrue(failed.isFailure)
    }

    @Test
    fun moneyIsAlwaysTwoDecimals() {
        assertEquals("92.00", formatMoney(92.0))
        assertEquals("9210.00", formatMoney(9209.9999))
        assertEquals("0.05", formatMoney(0.047))
        assertEquals("-3.50", formatMoney(-3.5))
    }
}
