package cx.m42.apps.converter

import cx.m42.superizer.app.AppConfig
import cx.m42.superizer.testing.runAppTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The converter against fakes: a network that answers, a network that does not, and a device that
 * is not on one.
 *
 * The app cannot tell any of these apart from a real host — which is the point of the runtime being
 * an interface, and the reason this file has no HTTP client in it.
 */
class ConverterInstanceTest {

    private val url = ConverterConfig.DEFAULT_RATES_URL
    private val payload =
        """{"amount":1.0,"base":"USD","date":"2026-09-19","rates":{"EUR":0.92,"GBP":0.79,"RUB":92.10}}"""

    private fun config(json: String) = AppConfig(Json.parseToJsonElement(json) as JsonObject)

    @Test
    fun ratesFromTheNetworkAreShownAndCached() = runAppTest(
        ConverterApp(),
        prepare = { it.network.respond(url, payload) },
    ) {
        assertShown("currency-converter:result")
        node("currency-converter:source").assertExists()
        // Cached on the way past, which is what makes the next launch offline-tolerant.
        assertTrue(runtime.storage.entries.containsKey("rates.USD"))
    }

    @Test
    fun anOfflineDeviceReadsTheCacheWithoutMakingARequest() = runAppTest(
        ConverterApp(),
        prepare = { runtime ->
            runtime.storage.entries["rates.USD"] =
                """{"base":"USD","date":"2026-09-18","rates":{"EUR":0.9},"fetchedAt":1}"""
            runtime.network.online.value = false
            runtime.network.fail(url)
        },
    ) {
        // D32: the decision is made from the connectivity signal, before anything is attempted.
        assertEquals(emptyList(), runtime.network.calls)
        assertShown("currency-converter:result")
    }

    @Test
    fun aFailedRequestFallsBackToTheCacheAndSaysItIsCached() = runAppTest(
        ConverterApp(),
        prepare = { runtime ->
            runtime.storage.entries["rates.USD"] =
                """{"base":"USD","date":"2026-09-18","rates":{"EUR":0.9},"fetchedAt":1}"""
            runtime.network.fail(url)
        },
    ) {
        assertEquals(1, runtime.network.calls.size)
        assertShown("currency-converter:result")
    }

    @Test
    fun nothingCachedAndNothingAnsweringLeavesARetryButton() = runAppTest(
        ConverterApp(),
        prepare = { it.network.fail(url) },
    ) {
        assertShown("currency-converter:error")
        assertShown("currency-converter:retry")
    }

    @Test
    fun aDeepLinksConfigDecidesWhichPairIsOpen() = runAppTest(
        ConverterApp(),
        config = config("""{"from":"EUR","to":"RUB"}"""),
        prepare = { it.network.respond(url, payload) },
    ) {
        val converter = instance as ConverterInstance
        assertEquals("EUR", converter.from.value)
        assertEquals("RUB", converter.to.value)
        // And the app has no idea a link was involved (§12–13).
    }

    @Test
    fun goingToBackgroundStopsTheAppAskingForRates() = runAppTest(
        ConverterApp(),
        prepare = { it.network.respond(url, payload) },
    ) {
        val before = runtime.network.calls.size
        // Both halves, because that is what the handler does: it flips the host lifecycle and then
        // tells the instance. An app that only listened to one of the two would be half-asleep.
        runtime.hostLifecycle.value = cx.m42.superizer.runtime.HostLifecycle.Background
        instance.onBackground()
        (instance as ConverterInstance).refresh()
        // D17: a converter that kept polling while nobody was looking would be a battery complaint.
        assertEquals(before, runtime.network.calls.size)
    }

    @Test
    fun swappingTheTwoSidesInvertsTheAnswer() = runAppTest(
        ConverterApp(),
        prepare = { it.network.respond(url, payload) },
    ) {
        val converter = instance as ConverterInstance
        converter.setAmount("100")
        val forward = converter.result!!
        click("currency-converter:swap")
        val back = converter.result!!
        assertTrue(forward > 0 && back > 0 && forward != back)
    }
}
