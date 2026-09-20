package cx.m42.apps.calculator

import cx.m42.superizer.app.AppConfigSpec
import cx.m42.superizer.app.AppIcon
import cx.m42.superizer.app.AppId
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.app.AppManifest
import cx.m42.superizer.app.AppMetadata
import cx.m42.superizer.app.SuperizerApp
import cx.m42.superizer.app.localized
import cx.m42.superizer.runtime.InstanceRuntime
import kotlinx.serialization.Serializable

/**
 * What a calculator can be told to be.
 *
 * One field, and it changes what is on the screen: `"scientific"` adds a row of keys and a
 * subtitle. A config nobody can see the effect of is a config nobody can tell is working, which is
 * why this is three keys rather than a setting.
 */
@Serializable
public data class CalculatorConfig(val mode: String = STANDARD) {
    internal val isScientific: Boolean get() = mode == SCIENTIFIC

    public companion object {
        public const val STANDARD: String = "standard"
        public const val SCIENTIFIC: String = "scientific"
    }
}

/**
 * The four-function calculator Unitool started life as, now an app like any other.
 *
 * It is the proof that the migration was a migration and not a rewrite: the arithmetic in
 * `Calculator.kt` moved across unchanged, and what it lost was the top bar, the drawer and the
 * knowledge that it was the only screen in the process.
 *
 * Nothing here knows about Unitool. It reaches the platform through `runtime` and would run in any
 * host, or standalone.
 */
public class CalculatorApp : SuperizerApp<CalculatorConfig>() {

    override val manifest: AppManifest = AppManifest(
        id = AppId("calculator"),
        version = "1.1.0",
        metadata = AppMetadata(
            title = localized("ru" to "Калькулятор", "en" to "Calculator"),
            description = localized(
                "ru" to "Четыре действия, проценты, инженерный режим",
                "en" to "Four functions, percent, a scientific mode",
            ),
            // A name from the host's glyph pack. The converter ships its own path instead, so both
            // halves of D29 are exercised by the MVP rather than one of them being theory.
            icon = AppIcon.Named("calculator"),
            category = "Инструменты",
        ),
    )

    override val configSpec: AppConfigSpec<CalculatorConfig> =
        AppConfigSpec(CalculatorConfig.serializer(), CalculatorConfig())

    override fun launch(runtime: InstanceRuntime, config: CalculatorConfig): AppInstance =
        CalculatorInstance(runtime, config)
}
