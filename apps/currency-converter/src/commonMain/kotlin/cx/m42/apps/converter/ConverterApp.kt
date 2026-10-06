package cx.m42.apps.converter

import cx.m42.superizer.app.AppConfigSpec
import cx.m42.superizer.app.AppIcon
import cx.m42.superizer.app.AppId
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.app.AppManifest
import cx.m42.superizer.app.AppMetadata
import cx.m42.superizer.app.AppSetupContext
import cx.m42.superizer.app.SuperizerApp
import cx.m42.superizer.app.localized
import cx.m42.superizer.runtime.InstanceRuntime
import kotlinx.serialization.Serializable

/**
 * What the converter can be told.
 *
 * `ratesUrl` is in the config rather than in the code on purpose (07): swapping the source — to
 * the fallback endpoint, or to a fake in a test — is then a payload rather than a release, and the
 * app never learns whether the payload came from a QR code, a promo code or the Service Menu.
 */
@Serializable
public data class ConverterConfig(
    val base: String = "USD",
    val favorites: List<String> = listOf("EUR", "RUB", "GBP", "VND"),
    val ratesUrl: String = DEFAULT_RATES_URL,
    /** Which currency a deep link asked to start on, if any. */
    val from: String? = null,
    val to: String? = null,
) {
    public companion object {
        /**
         * ExchangeRate-API's open endpoint: no key, CORS open — which the wasm build needs (07) —
         * and over 160 currencies, RUB and VND among them. The ECB, behind `api.frankfurter.app`,
         * was the first choice and publishes neither, so a ruble or a dong had no answer at all;
         * it still parses, and stays one config payload away. The price is a credit under the
         * answer (their terms), and a table that changes once a day.
         */
        public const val DEFAULT_RATES_URL: String = "https://open.er-api.com/v6/latest/USD"
    }
}

/**
 * The app that exists to show what a runtime is for.
 *
 * The calculator proves an app can be moved into the framework; this proves an app can *live* in
 * it — it needs the network, a cache, a clock and a connectivity signal, and it gets all four
 * through `runtime` without importing an HTTP client or knowing which platform it is on.
 */
public class ConverterApp : SuperizerApp<ConverterConfig>() {

    override val manifest: AppManifest = AppManifest(
        id = AppId("currency-converter"),
        version = "1.0.0",
        metadata = AppMetadata(
            title = localized("ru" to "Конвертер валют", "en" to "Currency converter"),
            description = localized(
                "ru" to "Курсы более 160 валют, работает офлайн по кэшу",
                "en" to "Rates for 160+ currencies, works offline from cache",
            ),
            // Its own glyph, as an SVG path the host has never seen (D29). The calculator uses a
            // name from the host's pack; between them the two halves of "an app can look like
            // itself without editing the host" are both exercised.
            icon = AppIcon.Path(
                "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 2.2a7.8 7.8 0 1 1 0 15.6 7.8 7.8 0 0 1 " +
                    "0-15.6zM11 6h2v2.2h1.8v2H9.2c-.3 0-.5.2-.5.5s.2.5.5.5h2.4a2.5 2.5 0 0 1 0 5H13V18h-2v-1.8H9.2v-2h5.6" +
                    "c.3 0 .5-.2.5-.5s-.2-.5-.5-.5h-2.4a2.5 2.5 0 0 1 0-5H11V6z",
            ),
            category = "Finance",
        ),
        deepLinks = setOf("rate"),
        pushTopics = setOf("rates"),
        networkHosts = setOf("open.er-api.com", "api.frankfurter.app"),
    )

    override val configSpec: AppConfigSpec<ConverterConfig> =
        AppConfigSpec(ConverterConfig.serializer(), ConverterConfig())

    override fun setup(ctx: AppSetupContext) {
        // The app's own block on the host's Settings page (03). The host draws the frame and the
        // title; what is inside is the app's, and the host never learns what.
        ctx.settingsSection { runtime ->
            ConverterSettingsSection(runtime, configSpec.default.favorites)
        }

        // `unitool://app/currency-converter/rate?pair=USD-RUB` — the path a push carries in its
        // `link` (13 §2), and the same one a QR code or a pasted URL would. The app turns the
        // query into a config and never learns which of those it was.
        ctx.deepLink("rate") { params ->
            val pair = params["pair"].orEmpty().split('-')
            configSpec.encode(
                ConverterConfig(
                    from = pair.getOrNull(0)?.takeIf { it.length == 3 },
                    to = pair.getOrNull(1)?.takeIf { it.length == 3 },
                ),
            )
        }
    }

    override fun launch(runtime: InstanceRuntime, config: ConverterConfig): AppInstance =
        ConverterInstance(runtime, config)
}
