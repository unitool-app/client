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
         * Frankfurter's shape: `{"amount":1,"base":"USD","date":"2026-09-19","rates":{…}}`.
         *
         * Parsed by hand out of a `JsonObject` rather than into a mirror data class, because the
         * app should survive the endpoint growing a field, and because the *fallback* endpoint in
         * the config has a different envelope — one function, adjusted, beats two response types.
         */
        public fun parse(body: String, fallbackBase: String, fetchedAt: Long, json: Json = Json): Rates {
            val root = json.parseToJsonElement(body).jsonObject
            val rates = (root["rates"] as? JsonObject)?.mapValues { (_, value) ->
                value.jsonPrimitive.doubleOrNull ?: 0.0
            }.orEmpty()
            require(rates.isNotEmpty()) { "no rates in the response" }
            return Rates(
                base = root["base"]?.jsonPrimitive?.content ?: fallbackBase,
                date = root["date"]?.jsonPrimitive?.content ?: "",
                rates = rates,
                fetchedAt = fetchedAt,
            )
        }
    }
}
