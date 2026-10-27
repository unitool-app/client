package cx.m42.apps.converter

import cx.m42.superizer.backup.BackupPolicy
import cx.m42.superizer.testing.runAppTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        """{"result":"success","provider":"https://www.exchangerate-api.com","time_last_update_unix":1789776151,""" +
            """"base_code":"USD","rates":{"USD":1,"EUR":0.92,"GBP":0.79,"RUB":92.10,"VND":26000}}"""

    @Test
    fun ratesFromTheNetworkAreShownAndCached() = runAppTest(
        ConverterApp(),
        prepare = { it.network.respond(url, payload) },
    ) {
        assertShown("currency-converter:pair-USD-EUR:result")
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
        assertShown("currency-converter:pair-USD-EUR:result")
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
        assertShown("currency-converter:pair-USD-EUR:result")
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
    fun dongsAreConvertedToRublesThroughTheDollar() = runAppTest(
        ConverterApp(),
        prepare = {
            it.storage.entries[KEY_PAIRS] = """[{"from":"VND","to":"RUB"}]"""
            it.network.respond(url, payload)
        },
    ) {
        // Neither is on the ECB's list, which is why the default source is not the ECB any more.
        val converter = instance as ConverterInstance
        val row = converter.rows.value.single()
        converter.setAmount(row.id, "1000000")
        val rubles = converter.result(converter.rows.value.single())!!
        assertTrue(kotlin.math.abs(rubles - 1_000_000 / 26000.0 * 92.10) < 0.001, "got $rubles")
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
        val forward = converter.result(converter.rows.value.single())!!
        click("currency-converter:pair-USD-EUR:swap")
        val back = converter.result(converter.rows.value.single())!!
        assertTrue(forward > 0 && back > 0 && forward != back, "forward $forward, back $back, ${converter.rows.value}")
    }
}

/** The set of pairs: what it starts as, how it changes, and that only the pairs are written down. */
class ConverterPairsTest {

    private val url = ConverterConfig.DEFAULT_RATES_URL
    private val payload =
        """{"result":"success","base_code":"USD","time_last_update_unix":1789776151,"rates":{"EUR":0.92,"RUB":92.10,"VND":26000}}"""

    private fun ConverterInstance.pairs() = rows.value.map { it.from to it.to }

    private fun stored(runtime: cx.m42.superizer.testing.FakeAppRuntime) =
        Json.decodeFromString<List<CurrencyPair>>(runtime.storage.entries.getValue(KEY_PAIRS)).map { it.from to it.to }

    @Test
    fun aFreshConverterStartsWithOnePairAndWritesItDown() = runAppTest(
        ConverterApp(),
        prepare = { it.network.respond(url, payload) },
    ) {
        assertEquals(listOf("USD" to "EUR"), (instance as ConverterInstance).pairs())
        assertEquals(listOf("USD" to "EUR"), stored(runtime))
    }

    @Test
    fun theOnePairOfTheOldVersionBecomesTheFirstOfTheSet() = runAppTest(
        ConverterApp(),
        prepare = {
            it.storage.entries[KEY_BASE] = "GBP"
            it.storage.entries[KEY_TO] = "VND"
            it.network.respond(url, payload)
        },
    ) {
        assertEquals(listOf("GBP" to "VND"), (instance as ConverterInstance).pairs())
        assertEquals(listOf("GBP" to "VND"), stored(runtime))
        assertFalse(KEY_BASE in runtime.storage.entries || KEY_TO in runtime.storage.entries)
    }

    @Test
    fun theStoredSetIsWhatTheConverterOpensOn() = runAppTest(
        ConverterApp(),
        prepare = {
            it.storage.entries[KEY_PAIRS] =
                """[{"from":"RUB","to":"VND"},{"from":"VND","to":"USD"},{"from":"EUR","to":"USD"}]"""
            it.network.respond(url, payload)
        },
    ) {
        assertEquals(
            listOf("RUB" to "VND", "VND" to "USD", "EUR" to "USD"),
            (instance as ConverterInstance).pairs(),
        )
        assertShown("currency-converter:pair-EUR-USD:result")
    }

    @Test
    fun pairsAreAddedChangedSwappedAndRemoved() = runAppTest(
        ConverterApp(),
        prepare = { it.network.respond(url, payload) },
    ) {
        val converter = instance as ConverterInstance
        converter.add("RUB", "VND")
        converter.add("RUB", "VND")
        assertEquals(listOf("USD" to "EUR", "RUB" to "VND"), converter.pairs())

        val first = converter.rows.value[0].id
        converter.pickTo(first, "RUB")
        converter.swap(converter.rows.value[1].id)
        assertEquals(listOf("USD" to "RUB", "VND" to "RUB"), converter.pairs())

        converter.remove(first)
        assertEquals(listOf("VND" to "RUB"), converter.pairs())
        assertEquals(listOf("VND" to "RUB"), stored(runtime))
    }

    @Test
    fun aPairIsAddedOnTheScreenFromThenTo() = runAppTest(
        ConverterApp(),
        prepare = { it.network.respond(url, payload) },
    ) {
        click("currency-converter:add")
        click("currency-converter:pick-RUB")
        click("currency-converter:pick-VND")
        assertEquals(listOf("USD" to "EUR", "RUB" to "VND"), (instance as ConverterInstance).pairs())
        assertShown("currency-converter:pair-RUB-VND:result")

        click("currency-converter:edit")
        click("currency-converter:pair-USD-EUR:remove")
        assertEquals(listOf("RUB" to "VND"), (instance as ConverterInstance).pairs())
    }

    @Test
    fun eachPairHasItsOwnAmountAndNoneIsWrittenDown() = runAppTest(
        ConverterApp(),
        prepare = {
            it.storage.entries[KEY_PAIRS] = """[{"from":"USD","to":"RUB"},{"from":"EUR","to":"USD"}]"""
            it.network.respond(url, payload)
        },
    ) {
        val converter = instance as ConverterInstance
        val (rubles, dollars) = converter.rows.value
        converter.setAmount(rubles.id, "100")
        assertEquals(listOf("100", DEFAULT_AMOUNT), converter.rows.value.map { it.amount })
        assertTrue(kotlin.math.abs(converter.result(converter.rows.value[0])!! - 9210.0) < 0.001)
        assertTrue(kotlin.math.abs(converter.result(converter.rows.value[1])!! - 1 / 0.92) < 0.001)
        assertFalse(runtime.storage.entries.values.any { "100" in it && "amount" in it })
        assertEquals(dollars.id, converter.rows.value[1].id)
    }

    @Test
    fun anAmountComesBackOnlyOntoTheRowItWasTypedIn() = runAppTest(
        ConverterApp(),
        prepare = {
            it.storage.entries[KEY_PAIRS] = """[{"from":"USD","to":"RUB"},{"from":"EUR","to":"USD"}]"""
            it.network.respond(url, payload)
        },
    ) {
        val converter = instance as ConverterInstance
        converter.restore(
            Json.parseToJsonElement(
                """{"rows":[{"from":"USD","to":"RUB","amount":"250"},{"from":"GBP","to":"USD","amount":"7"}]}""",
            ) as JsonObject,
        )
        assertEquals(listOf("250", DEFAULT_AMOUNT), converter.rows.value.map { it.amount })
    }

    @Test
    fun theSetGoesIntoTheBackup() {
        // The pairs are the person's list, not this device's habit (D591).
        assertEquals(BackupPolicy.All, ConverterApp().manifest.backup)
    }
}
