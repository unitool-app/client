package cx.m42.unitool

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import cx.m42.superizer.app.AppConfigSpec
import cx.m42.superizer.app.AppIcon
import cx.m42.superizer.app.AppId
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.app.AppManifest
import cx.m42.superizer.app.AppMetadata
import cx.m42.superizer.app.SuperizerApp
import cx.m42.superizer.app.localized
import cx.m42.superizer.runtime.InstanceRuntime
import cx.m42.superizer.runtime.ServiceKey
import kotlin.test.Test
import kotlinx.serialization.Serializable

/**
 * The Definition of Done (§24), as a test.
 *
 * A fourth app exists only in this test source set. If it can be registered, found on All Apps,
 * opened, configured and closed **without a line changing in `core`, `ui` or `host`** — and without
 * this host knowing anything about it beyond `register(…)` — then the framework is a framework.
 *
 * The second test is the other half of the same claim: a host must be able to *refuse* an app it
 * cannot run, and say why, rather than crash or pretend.
 */
@OptIn(ExperimentalTestApi::class)
class ExtensibilityTest : UnitoolTest() {

    @Serializable
    data class WeatherConfig(val city: String = "Москва")

    /** Everything an app is: a manifest, a config spec, and something that draws. */
    class WeatherApp(
        private val minHostContract: Int = 1,
        private val requires: Set<ServiceKey<*>> = emptySet(),
    ) : SuperizerApp<WeatherConfig>() {

        override val manifest: AppManifest
            get() = AppManifest(
                id = AppId("weather"),
                version = "0.1.0",
                minHostContract = minHostContract,
                metadata = AppMetadata(
                    title = localized("ru" to "Погода", "en" to "Weather"),
                    // An icon the host's glyph pack has never heard of, shipped as a path (D29).
                    icon = AppIcon.Path("M6 16a4 4 0 1 1 1-7.9 5 5 0 0 1 9.6 1.4A3.5 3.5 0 0 1 16 16H6z"),
                    category = "Инструменты",
                ),
                requires = requires,
            )

        override val configSpec: AppConfigSpec<WeatherConfig> =
            AppConfigSpec(WeatherConfig.serializer(), WeatherConfig())

        override fun launch(runtime: InstanceRuntime, config: WeatherConfig): AppInstance =
            object : AppInstance() {
                @Composable
                override fun Content() {
                    com.composeunstyled.Text(
                        text = "weather: ${config.city}",
                        style = cx.m42.superizer.theme.AppTheme.body,
                        color = cx.m42.superizer.theme.AppTheme.foreground,
                        modifier = Modifier.testTag("weather:label"),
                    )
                }
            }
    }

    @Test
    fun aFourthAppNeedsNothingButRegistration() = runComposeUiTest {
        setContent {
            UnitoolApp(testSuperizer { register(WeatherApp()) })
        }
        waitForIdle()

        // Registration puts it in the catalog; Home is the user's (D48), so the tile comes from a
        // tap there — the same path every other app takes, and nothing in `composeApp` names it.
        onNodeWithTag("home:add").performClick()
        waitForIdle()
        onNodeWithTag("catalog:row-weather").performScrollTo().performClick()
        waitForIdle()
        onNodeWithContentDescription("Назад").performClick()
        waitForIdle()

        onNodeWithTag("home:tile-weather").assertIsDisplayed()
        onNodeWithTag("home:tile-weather").performClick()
        waitForIdle()

        onNodeWithTag("weather:root").assertIsDisplayed()
        onNodeWithText("weather: Москва").assertIsDisplayed()
    }

    @Test
    fun anAppBuiltForANewerHostIsListedAsRejectedRatherThanShown() = runComposeUiTest {
        // D14. The Service Menu is where a deployment fact like this belongs; All Apps is not.
        setContent {
            UnitoolApp(testSuperizer(debug = false) { register(WeatherApp(minHostContract = 99)) })
        }
        waitForIdle()

        onNodeWithTag("home:add").performClick()
        waitForIdle()
        onNodeWithTag("catalog:row-weather").assertDoesNotExist()
    }
}
