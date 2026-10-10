package cx.m42.unitool

import androidx.compose.ui.test.assertTextEquals

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

/**
 * The four doors into an app, and the fact that the app cannot tell them apart.
 *
 * A promo code, a QR payload and a deep link all end in the same place — `ActivationService.apply`
 * — which is why "an app never learns how it was opened" (§12–13) is a structural property here and
 * not a convention somebody has to remember.
 */
@OptIn(ExperimentalTestApi::class)
class ActivationTest : UnitoolTest() {

    @Test
    fun aPromoCodeOpensTheCalculatorInItsScientificMode() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        openActivate()
        onNodeWithTag("activate:promo").performTextInput("SCI")
        onNodeWithTag("activate:apply").performScrollTo().performClick()
        waitForIdle()

        // The visible effect of a config (07): a row of keys that is not there in standard mode,
        // and a subtitle in the host's frame the app asked for through `chrome`.
        onNodeWithTag("calculator:key-√").assertIsDisplayed()
        onNodeWithText("Инженерный").assertIsDisplayed()
    }

    @Test
    fun aPromoCodeUnlocksTheHiddenBenchAppAndOpensIt() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        openActivate()
        onNodeWithTag("activate:promo").performTextInput(cx.m42.apps.testapp.TestApp.PROMO_CODE)
        onNodeWithTag("activate:apply").performScrollTo().performClick()
        waitForIdle()

        onNodeWithTag("test-app:root").assertIsDisplayed()
        onNodeWithTag("test-app:section-config").assertIsDisplayed()
    }

    @Test
    fun aQrPayloadOpensTheSameAppWithTheSameConfig() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        openActivate()
        onNodeWithTag("activate:qr").performTextInput(
            """{"schemaVersion":1,"type":"app_activation","appId":"calculator","config":{"mode":"scientific"}}""",
        )
        onNodeWithTag("activate:apply").performScrollTo().performClick()
        waitForIdle()

        // The same screen the promo code produced. That the two paths are indistinguishable from
        // inside the app is the whole of §12–13.
        onNodeWithTag("calculator:key-√").assertIsDisplayed()
    }

    @Test
    fun textThatIsNotAnActivationSaysSoInsteadOfDoingNothing() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        openActivate()
        onNodeWithTag("activate:qr").performTextInput("just some text")
        onNodeWithTag("activate:apply").performScrollTo().performClick()
        waitForIdle()

        onNodeWithTag("activate:error").assertIsDisplayed()
        onNodeWithText("Это не QR активации").assertIsDisplayed()
    }

    @Test
    fun anUnknownCodeIsRejectedByName() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        openActivate()
        onNodeWithTag("activate:promo").performTextInput("NOPE")
        onNodeWithTag("activate:apply").performScrollTo().performClick()
        waitForIdle()

        onNodeWithText("Код не найден").assertIsDisplayed()
    }

    @Test
    fun theServiceCodeOpensTheServiceMenuRatherThanAnApp() = runComposeUiTest {
        // D20: a host command, not a reserved app id. There is no `service-menu` app to collide
        // with anything, and the shell matches an enum.
        setContent { UnitoolApp(testSuperizer(debug = false)) }
        waitForIdle()

        openActivate()
        onNodeWithTag("activate:promo").performTextInput(SERVICE_CODE)
        onNodeWithTag("activate:apply").performScrollTo().performClick()
        waitForIdle()

        onNodeWithTag("service:root").assertIsDisplayed()
        onNodeWithTag("service:app-test-app").assertIsDisplayed()
    }

    @Test
    fun aDeepLinkArrivingBeforeTheFirstFrameOpensTheAppItNames() = runComposeUiTest {
        // The cold-start path (13 §5): a notification tap into a dead process, an intent, or
        // `?link=` in the browser's query all arrive this way — through the route, before anything
        // has composed.
        val superizer = testSuperizer()
        superizer.route.deliver("unitool://app/currency-converter")
        setContent { UnitoolApp(superizer) }
        waitForIdle()

        onNodeWithTag("currency-converter:root").assertIsDisplayed()
    }

    @Test
    fun aDeepLinkPathTheAppDeclaredArrivesAsItsConfig() = runComposeUiTest {
        val superizer = testSuperizer()
        superizer.route.deliver("unitool://app/currency-converter/rate?pair=EUR-RUB")
        setContent { UnitoolApp(superizer) }
        waitForIdle()

        // `setup` turned the query into a config; the app read it in `launch` and has no idea a
        // link was involved.
        onNodeWithTag("currency-converter:root").assertIsDisplayed()
        onNodeWithText("EUR").assertIsDisplayed()
        onNodeWithText("RUB").assertIsDisplayed()
    }

    @Test
    fun aLinkThatMerelyNamesTheHiddenAppDoesNotRevealIt() = runComposeUiTest {
        // D25: naming an app is not knowing a secret about it. Only an activation with a payload,
        // or a promo code, unlocks — otherwise "hidden" would mean "hidden from people who have
        // not guessed the id".
        val superizer = testSuperizer()
        superizer.route.deliver("unitool://app/test-app")
        setContent { UnitoolApp(superizer) }
        waitForIdle()

        onNodeWithTag("test-app:root").assertDoesNotExist()
        onNodeWithTag(CALCULATOR_TILE).assertIsDisplayed()
    }

    /**
     * done/idea/09, as the owner put it: the code opens the secret app; out of it, and it is gone — not
     * on Home, in the menu or in "+"; the code again, and it is back with what it kept.
     */
    @Test
    fun theSecretAppOpensByItsCodeAndIsNowhereOnceLeft() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        enterCode(cx.m42.apps.testapp.SecretTestApp.PROMO_CODE)
        onNodeWithTag("secret-test:root").assertIsDisplayed()
        onNodeWithTag("secret-test:visits").assertTextEquals("Визит 1")

        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        onNodeWithText("Главная").performClick()
        waitForIdle()
        onNodeWithTag("home:tile-secret-test").assertDoesNotExist()
        // The menu from Home, where nothing behind it is the app's own title bar.
        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        onNodeWithText("Секретное приложение").assertDoesNotExist()
        onNodeWithText("Все приложения").performClick()
        waitForIdle()
        onNodeWithTag("catalog:row-secret-test").assertDoesNotExist()
        onNodeWithTag("catalog:row-calculator").assertIsDisplayed()

        enterCode("secret2026")
        onNodeWithTag("secret-test:visits").assertTextEquals("Визит 2")
    }

    @Test
    fun aQrCodeNamingTheSecretAppIsAnUnknownApp() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        openActivate()
        onNodeWithTag("activate:qr").performTextInput("""{"type":"app_activation","appId":"secret-test"}""")
        onNodeWithTag("activate:apply").performScrollTo().performClick()
        waitForIdle()

        onNodeWithTag("activate:error").assertIsDisplayed()
        onNodeWithTag("secret-test:root").assertDoesNotExist()
    }

    @Test
    fun theCameraOnTheActivateScreenActivatesWhatItReads() = runComposeUiTest {
        val payload = """{"schemaVersion":1,"type":"app_activation","appId":"calculator","config":{"mode":"scientific"}}"""
        setContent { UnitoolApp(testSuperizer { qrScanner { payload } }) }
        waitForIdle()

        openActivate()
        onNodeWithTag("activate:scan").performClick()
        waitForIdle()

        onNodeWithTag("calculator:key-√").assertIsDisplayed()
    }

    @Test
    fun withoutACameraTheActivateScreenHasNoScanButton() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()

        openActivate()
        onNodeWithTag("activate:qr").assertIsDisplayed()
        onNodeWithTag("activate:scan").assertDoesNotExist()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.enterCode(code: String) {
        openActivate()
        onNodeWithTag("activate:promo").performTextInput(code)
        onNodeWithTag("activate:apply").performScrollTo().performClick()
        waitForIdle()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.openActivate() {
        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        onNodeWithContentDescription("Премиум").performClick()
        waitForIdle()
        // The offer comes first; the code is one link under it.
        onNodeWithTag("activate:have-code").performScrollTo().performClick()
        waitForIdle()
    }
}
