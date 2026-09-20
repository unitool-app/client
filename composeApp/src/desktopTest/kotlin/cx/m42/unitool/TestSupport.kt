package cx.m42.unitool

import cx.m42.superizer.Superizer
import cx.m42.superizer.host.SuperizerBuilder
import cx.m42.superizer.host.storage.PrefsStorage
import cx.m42.superizer.runtime.NetworkService
import cx.m42.superizer.testing.FakeNetwork
import java.io.File
import kotlin.test.BeforeTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A Unitool that never touches the machine it is running on.
 *
 * Two isolations, and both are the difference between a suite that passes anywhere and one that
 * passes on the laptop it was written on:
 *
 *  - **Preferences go to a temp directory.** The host stores the language, the haptics switch, the
 *    unlocked set and the session snapshot; a test must not read — or overwrite — the developer's.
 *  - **The network is a fake.** The converter fetches rates, and a suite whose result depends on
 *    frankfurter.app being up is a suite somebody will eventually disable.
 *
 * The host scope is `Unconfined` on purpose. Everything the handler does is suspending but never
 * *delayed*, so an unconfined dispatcher runs the whole launch sequence synchronously and a test
 * can assert straight after the call instead of guessing at a timeout.
 */
abstract class UnitoolTest {

    @BeforeTest
    fun isolateStorage() {
        PrefsStorage.useDirectory(File(System.getProperty("java.io.tmpdir"), "unitool-test-${counter++}"))
    }

    private companion object {
        var counter = 0
    }
}

internal fun testSuperizer(
    network: NetworkService = ratesNetwork(),
    debug: Boolean = true,
    extra: SuperizerBuilder.() -> Unit = {},
): Superizer = buildUnitool(
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    debug = debug,
) {
    network(network)
    extra()
}.also {
    // Pinned, and it has to be *pinned* rather than defaulted: without this the host resolves the
    // language from the machine's own locale, and the same assertions pass on a developer's laptop
    // and fail on a CI runner whose JVM reports `en_US` — the kind of failure that gets a suite
    // disabled. Russian, because that is the language these strings were written in.
    it.settings.chooseLanguage("ru")
}

/** Fixed rates, so a screenshot of the converter is the same picture every time it is taken. */
internal fun ratesNetwork(): FakeNetwork = FakeNetwork().apply {
    respond(
        "https://api.frankfurter.app/latest?from=USD",
        """{"amount":1.0,"base":"USD","date":"2026-09-19","rates":{"EUR":0.92,"GBP":0.79,"RUB":92.10}}""",
    )
}

/** The same source, unreachable — what the offline path and the retry button are drawn against. */
internal fun offlineNetwork(): FakeNetwork = FakeNetwork().apply {
    online.value = false
    fail("https://api.frankfurter.app/latest?from=USD")
}

internal const val CALCULATOR_TILE = "home:tile-calculator"
internal const val CONVERTER_TILE = "home:tile-currency-converter"
