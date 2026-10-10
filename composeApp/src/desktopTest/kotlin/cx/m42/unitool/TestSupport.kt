package cx.m42.unitool

import cx.m42.superizer.Superizer
import cx.m42.superizer.host.SuperizerBuilder
import cx.m42.superizer.host.storage.PrefsStorage
import cx.m42.superizer.runtime.NetworkService
import cx.m42.superizer.testing.FakeNetwork
import java.nio.file.Files
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
 *    open.er-api.com being up is a suite somebody will eventually disable.
 *
 * The host scope is `Unconfined` on purpose. Everything the handler does is suspending but never
 * *delayed*, so an unconfined dispatcher runs the whole launch sequence synchronously and a test
 * can assert straight after the call instead of guessing at a timeout.
 */
abstract class UnitoolTest {

    /**
     * A new directory on every test *and* on every run.
     *
     * A numbered name is not enough: these outlive the JVM, so the second run would inherit the
     * first one's unlocked apps, its language and its session snapshot, and a failure would depend
     * on what happened the day before.
     */
    @BeforeTest
    fun isolateStorage() {
        PrefsStorage.useDirectory(Files.createTempDirectory("unitool-test").toFile())
    }
}

internal fun testSuperizer(
    network: NetworkService = ratesNetwork(),
    debug: Boolean = true,
    extra: SuperizerBuilder.() -> Unit = {},
): Superizer = buildUnitool(
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    debug = debug,
    network = network,
) {
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
        "https://open.er-api.com/v6/latest/USD",
        """{"result":"success","provider":"https://www.exchangerate-api.com","time_last_update_unix":1789776151,""" +
            """"base_code":"USD","rates":{"USD":1,"EUR":0.92,"GBP":0.79,"RUB":92.10,"VND":26000}}""",
    )
    respond("https://api.github.com/users/octocat/keys", GITHUB_KEYS)
    // Settings → Website, as far as the screenshots go: a first registration and a browser waiting.
    respond("https://unitool.m42.cx/api/unitool/v1/devices", """{"deviceId":"d_AAAAAAAAAAAA","push":"none","pushEnabled":false}""", status = 201)
    respond(
        "https://unitool.m42.cx/api/unitool/v1/web/logins/inspect",
        """{"loginId":"wl_AAAAAAAAAAAAAAAA","browser":{"userAgent":"Mozilla/5.0 (X11; Linux x86_64; rv:142.0) Gecko/20100101 Firefox/142.0","ip":"203.0.113.7"},"createdAt":1791600000,"expiresAt":1791600300}""",
    )
}

/** A sign-in link of the website, as the login page shows it under its QR. */
internal const val WEBSITE_LINK = "https://unitool.m42.cx/login/app/#wlt_ABCDEFGHJKLMNPQRSTUVWXYZ"

/**
 * What GitHub's API answers for an account with three keys — two age can encrypt to and one it
 * cannot — so the export screen is drawn with every kind of row. Public keys `ssh-keygen` made for
 * the tests; nothing holds their private halves.
 */
private val GITHUB_KEYS = listOf(
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOh1VbDdG6aEqkiUkfr6tLE1wlJZ/jm90yZxcCjDq3xS",
    "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQDF9f3LvEHB12a4AXHr0ZCeEBSJaK3MNbkcwnETRnaaOa35+LCIzEEtTxieW2gBAv84/UwqEIt/fuwF53f5xuvmUJsFCZvP4A/dHIOjwa8Fz7jGqaJAed6zD+7zPM/B7AyxsYKVFXQpdxqz8Pr/PoZ6yrC4cKQSqZsHSaisapKo5Z8Kq8Yxqgzli9ebKHMagjl/rhonjul5+pH2kJz1Dwt+JeJJ0C6/6tjz32N0BAwgfis792s+pY0dqBPOv0C2INmQXBtaWdV4OJmeH18LBuXdR4HZ4txjpnb+QrTGRO6xzuYB4Rx/va58dKYlB3+jCb7Ag0fXt/FUSmf3+crxJ0ARRTI7PLvCEYFsxRvsFFuBvGqx8R2i+CT6fOktLJEg1uoX0UGalIemP+8hx2TcRgnBfW9Hi7jAqEuO3OO48dtRMqQ2UD38751+AKqEYM5pvBK90nR7b9+ctv/UdB2ZfuNraZNcnFd9D835Y3O9dRZHzS2zgwFy3yrgxKsosWX8OOE=",
    "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEIsMsXqmDgDTtFzJYG8gCJX0455Aa7IlDHEiYA0PtCHVoTPjGzWFYwQgxsQRhLRYXwgEw9EjUXF79TLbDAzMEo=",
).mapIndexed { i, key -> """{"id":${i + 1},"key":"$key"}""" }.joinToString(",", "[", "]")

/** The same source, unreachable — what the offline path and the retry button are drawn against. */
internal fun offlineNetwork(): FakeNetwork = FakeNetwork().apply {
    online.value = false
    fail("https://open.er-api.com/v6/latest/USD")
}

/**
 * The Service Menu's code. Plain here, in the tests, and only a hash in the build (done/idea/09, D256);
 * `PromoCodesTest` is what proves the two still agree.
 */
internal const val SERVICE_CODE = "SERVICE"

internal const val CALCULATOR_TILE = "home:tile-calculator"
internal const val CONVERTER_TILE = "home:tile-currency-converter"
internal const val TWOFA_TILE = "home:tile-2fa"
