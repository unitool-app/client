package cx.m42.apps.converter

import cx.m42.superizer.runtime.Clock
import cx.m42.superizer.runtime.Logger
import cx.m42.superizer.runtime.NetworkService
import cx.m42.superizer.runtime.StorageService
import cx.m42.superizer.runtime.getJson
import cx.m42.superizer.runtime.setJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A table of rates against one base currency, as of one day.
 *
 * Plain data with a pure [convert]: the arithmetic is testable without a network, a clock or a
 * screen, which is the same split the calculator uses and the reason both apps have a logic test
 * that runs in milliseconds.
 */
@Serializable
public data class Rates(
    val base: String,
    val date: String,
    val rates: Map<String, Double>,
    /** When this table was fetched, so "is it stale" is a subtraction rather than a guess (D30). */
    val fetchedAt: Long = 0,
    /**
     * Who has to be named under the answer, when the publisher asks to be. Kept with the table
     * rather than worked out from the config's URL, because a cached table is shown long after the
     * request that brought it, and possibly under a different config.
     */
    val credit: String? = null,
) {
    /**
     * Converts through the base, because that is the only currency every rate is quoted against.
     * Null when either side is unknown — an unknown currency is a question with no answer, not zero.
     */
    public fun convert(amount: Double, from: String, to: String): Double? {
        if (from == to) return amount
        val fromRate = rateOf(from) ?: return null
        val toRate = rateOf(to) ?: return null
        return amount / fromRate * toRate
    }

    /** The base is 1 by definition and is usually absent from the payload. */
    public fun rateOf(code: String): Double? = if (code == base) 1.0 else rates[code]

    public val currencies: List<String> get() = (listOf(base) + rates.keys).distinct()
}

/**
 * Where rates come from, and what happens when they cannot.
 *
 * The order is the whole design: offline means *read the cache without asking* (D32), because the
 * app already knows the answer and a fifteen-second timeout is a worse way to find out. A failed
 * request falls back to the same cache, and only an empty cache is an error the user has to see.
 */
public class RatesRepository(
    private val network: NetworkService,
    private val storage: StorageService,
    private val logger: Logger,
    private val clock: Clock,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** What was loaded, and whether the network had anything to do with it. */
    public data class Loaded(val rates: Rates, val fromCache: Boolean)

    public suspend fun load(url: String, base: String, force: Boolean = false): Result<Loaded> {
        val cached = cached(base)

        if (!network.online.value && !force) {
            logger.info("offline; using the cache without asking")
            return cached?.let { Result.success(Loaded(it, fromCache = true)) }
                ?: Result.failure(IllegalStateException("offline and nothing cached"))
        }

        val fetched = runCatching { fetch(url, base) }
        fetched.onSuccess { rates ->
            storage.setJson(cacheKey(base), rates, json)
            return Result.success(Loaded(rates, fromCache = false))
        }
        logger.warn("rates request failed", fetched.exceptionOrNull())

        return cached?.let { Result.success(Loaded(it, fromCache = true)) }
            ?: Result.failure(fetched.exceptionOrNull() ?: IllegalStateException("no rates"))
    }

    public suspend fun cached(base: String): Rates? = storage.getJson<Rates>(cacheKey(base), json)

    private suspend fun fetch(url: String, base: String): Rates {
        val response = network.get(url)
        if (!response.isSuccess) {
            throw IllegalStateException("rates endpoint answered ${response.status}")
        }
        return parse(response.body, base, clock.now(), json)
    }

    private fun cacheKey(base: String) = "rates.$base"

    public companion object {
        /**
         * Two envelopes, one function:
         *
         *  - ExchangeRate-API: `{"result":"success","provider":"https://www.exchangerate-api.com",
         *    "time_last_update_unix":1789776151,"base_code":"USD","rates":{…}}`;
         *  - Frankfurter: `{"amount":1,"base":"USD","date":"2026-09-19","rates":{…}}`.
         *
         * Parsed by hand out of a `JsonObject` rather than into a mirror data class, because the
         * app should survive an endpoint growing a field, and because the difference between the
         * two is three field names — one function, adjusted, beats two response types. An error
         * from either has no `rates`, and fails here rather than as a table with nothing in it.
         */
        public fun parse(body: String, fallbackBase: String, fetchedAt: Long, json: Json = Json): Rates {
            val root = json.parseToJsonElement(body).jsonObject
            val rates = (root["rates"] as? JsonObject)?.mapValues { (_, value) ->
                value.jsonPrimitive.doubleOrNull ?: 0.0
            }.orEmpty()
            require(rates.isNotEmpty()) { "no rates in the response" }
            return Rates(
                base = (root["base"] ?: root["base_code"])?.jsonPrimitive?.content ?: fallbackBase,
                date = root["date"]?.jsonPrimitive?.content
                    ?: root["time_last_update_unix"]?.jsonPrimitive?.longOrNull?.let(::isoDate)
                    ?: "",
                rates = rates,
                fetchedAt = fetchedAt,
                // Their terms ask for this name wherever the rates are shown.
                credit = "Exchange Rate API".takeIf {
                    root["provider"]?.jsonPrimitive?.content.orEmpty().contains("exchangerate-api.com")
                },
            )
        }
    }
}

/**
 * `YYYY-MM-DD` of a Unix time, in UTC — the day ExchangeRate-API's table is for, written the way
 * Frankfurter writes it, so the line under the answer reads the same whichever one answered.
 *
 * `commonMain` has no calendar, and one conversion is not worth a dependency: this is Howard
 * Hinnant's `civil_from_days`, which is exact for every day either side of 1970.
 */
internal fun isoDate(epochSeconds: Long): String {
    val days = epochSeconds.floorDiv(86_400L) + 719_468
    val era = days.floorDiv(146_097L)
    val dayOfEra = days - era * 146_097
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val shiftedMonth = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * shiftedMonth + 2) / 5 + 1
    val month = if (shiftedMonth < 10) shiftedMonth + 3 else shiftedMonth - 9
    val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
    return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}
