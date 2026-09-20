package cx.m42.unitool

import androidx.compose.runtime.Composable
import cx.m42.apps.calculator.CalculatorApp
import cx.m42.apps.calculator.CalculatorConfig
import cx.m42.apps.converter.ConverterApp
import cx.m42.apps.testapp.TestApp
import cx.m42.superizer.LanguageOption
import cx.m42.superizer.Superizer
import cx.m42.superizer.SuperizerContract
import cx.m42.superizer.activation.Activation
import cx.m42.superizer.activation.ActivationResult
import cx.m42.superizer.app.AppConfig
import cx.m42.superizer.app.AppId
import cx.m42.superizer.app.SuperizerApp
import cx.m42.superizer.host.SuperizerBuilder
import cx.m42.superizer.host.build
import cx.m42.superizer.host.platform.currentPlatform
import cx.m42.superizer.runtime.HostInfo
import cx.m42.superizer.ui.shell.SuperizerShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Unitool, in one function.
 *
 * This is the whole of what makes Unitool a product rather than a framework: a name, three apps and
 * a table of promo codes. Everything else — the drawer, All Apps, Settings, activation, the app
 * container, the Service Menu — comes from the library and is identical in any other host.
 *
 * Registration is explicit (D1), which is also why this reads as an inventory: what is in the build
 * is what is listed here, with no classpath scanning to go looking for and no `@Keep` for R8.
 *
 * **The Definition of Done lives in this file** (§24). Adding a fourth app means one `register(…)`
 * line, one dependency in `composeApp/build.gradle.kts` and one line in `settings.gradle.kts` —
 * and nothing in `core`, `ui` or `host`. `ExtensibilityTest` is that claim, as a test.
 */
public fun buildUnitool(
    scope: CoroutineScope,
    debug: Boolean,
    extra: SuperizerBuilder.() -> Unit = {},
): Superizer = Superizer.build(scope) {
    host(
        HostInfo(
            name = "Unitool",
            version = BuildInfo.VERSION,
            build = BuildInfo.COMMIT + if (BuildInfo.DIRTY) "*" else "",
            platform = currentPlatform(),
            contractVersion = SuperizerContract.VERSION,
            // D34: this is what decides whether seven taps on the build stamp do anything. In a
            // release they do not, and the Service Menu's only door is the promo code below.
            debug = debug,
        ),
    )

    // The scheme in `unitool://app/<id>/<path>` and `unitool://activate?…`, and what the Android
    // manifest's intent filters have to agree with.
    scheme(SCHEME)

    languages(
        LanguageOption("ru", "Русский"),
        LanguageOption("en", "English"),
        // English rather than Russian for a device that has neither: the app is listed worldwide,
        // and English is the language such a reader is likeliest to have some of.
        fallback = "en",
    )

    register(CalculatorApp())
    register(ConverterApp())
    // Hidden (D8). It is a diagnostic instrument, and it stays invisible until someone types the
    // code below — which is also the only way the Service Menu opens in a release.
    register(TestApp())

    promoCodes(
        mapOf(
            // The visible effect of a config, reachable by typing five characters (07).
            "SCI" to ActivationResult.Success(
                Activation(
                    AppId("calculator"),
                    AppConfig(buildJsonObject { put("mode", JsonPrimitive(CalculatorConfig.SCIENTIFIC)) }),
                ),
            ),
            TestApp.PROMO_CODE to ActivationResult.Success(
                Activation(AppId("test-app"), TestApp.promoConfig()),
            ),
        ),
    )

    // D20: a host command, not a reserved app id. The shell matches an enum, so `service-menu`
    // never becomes a name a real app could collide with.
    serviceCode(SERVICE_CODE)

    extra()
}

/** The root composable every entry point calls. */
@Composable
public fun UnitoolApp(superizer: Superizer) {
    SuperizerShell(superizer)
}

/** Registers an app the host was built without — the seam `ExtensibilityTest` uses (§24). */
public fun SuperizerBuilder.registerExtra(app: SuperizerApp<*>) {
    register(app)
}

public const val SCHEME: String = "unitool"

/** The one door into the Service Menu in a release build (D34). */
public const val SERVICE_CODE: String = "SERVICE"
