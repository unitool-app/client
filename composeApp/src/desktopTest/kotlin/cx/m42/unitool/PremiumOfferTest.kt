package cx.m42.unitool

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import cx.m42.unitool.i18n.premiumStrings
import cx.m42.unitool.premium.PremiumCard
import cx.m42.unitool.premium.PremiumPrices
import cx.m42.unitool.premium.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Premium offer on Activate, a mock-up: it sits above the promo code, shows two periods priced in
 * the region's currency with the store's small print, and each store's own look. Its buttons open the
 * store's kind of alert: something went wrong, try again later.
 */
@OptIn(ExperimentalTestApi::class)
class PremiumOfferTest : UnitoolTest() {

    @Test
    fun theOfferSitsAboveThePromoCodeAndSellsNothingYet() = runComposeUiTest {
        setContent { UnitoolApp(testSuperizer()) }
        waitForIdle()
        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        onNodeWithContentDescription("Премиум").performClick()
        waitForIdle()
        // The drawer's door was a star called Premium, and the bar says the same.
        onNodeWithContentDescription("Активировать").assertDoesNotExist()
        onNodeWithText("Премиум").assertIsDisplayed()

        // The offer first; the code and the QR field fold behind one link under it.
        val card = onNodeWithTag("premium:card").assertIsDisplayed().getUnclippedBoundsInRoot()
        onNodeWithTag("activate:promo").assertDoesNotExist()
        onNodeWithTag("activate:qr").assertDoesNotExist()
        val link = onNodeWithTag("activate:have-code").assertExists().getUnclippedBoundsInRoot()
        assertTrue(card.bottom <= link.top, "the offer is above the promo code")

        // A year is chosen first, and the button names what it would charge.
        onNodeWithTag("premium:plan-year").assertIsSelected()
        onNodeWithTag("premium:subscribe").assert(hasText("в год", substring = true))
        onNodeWithTag("premium:plan-month").performClick()
        waitForIdle()
        onNodeWithTag("premium:plan-month").assertIsSelected()
        onNodeWithTag("premium:subscribe").assert(hasText("в месяц", substring = true))

        // Desktop is drawn as iOS: Apple's small print, and both links.
        onNodeWithTag("premium:small-print").assert(hasText("Apple ID", substring = true))
        onNodeWithTag("premium:terms").assertExists()
        onNodeWithTag("premium:privacy").assertExists()

        // Nothing is sold yet: a purchase fails, in the current language, and OK closes the alert.
        onNodeWithTag("premium:alert").assertDoesNotExist()
        onNodeWithTag("premium:subscribe").performClick()
        waitForIdle()
        onNodeWithTag("premium:alert").assertIsDisplayed()
        onNodeWithText("Что-то пошло не так").assertIsDisplayed()
        onNodeWithText("Повторите попытку позже.").assertIsDisplayed()
        onNodeWithTag("premium:alert-ok").performClick()
        waitForIdle()
        onNodeWithTag("premium:alert").assertDoesNotExist()
        onNodeWithTag("premium:restore").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("premium:alert").assertIsDisplayed()
        onNodeWithTag("premium:alert-ok").performClick()
        waitForIdle()

        onNodeWithTag("activate:have-code").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("activate:have-code").assertDoesNotExist()
        onNodeWithTag("activate:promo").assertExists()
    }

    @Test
    fun androidGetsAGooglePlayOfferAndAMaterialAlert() = runComposeUiTest {
        // Scrollable, as Activate is: under CI's fonts the button can fall below a test window's fold.
        setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PremiumCard(premiumStrings("en"), PremiumPrices.forRegion("US"), Store.GooglePlay)
            }
        }
        waitForIdle()
        // The periods are a list, the better one on top and chosen; the button leaves the price to it.
        val year = onNodeWithTag("premium:plan-year").assertIsSelected().getUnclippedBoundsInRoot()
        val month = onNodeWithTag("premium:plan-month").getUnclippedBoundsInRoot()
        assertTrue(year.bottom <= month.top, "the year is listed above the month")
        onNodeWithTag("premium:subscribe").assert(hasText("Subscribe"))

        // Google Play's small print; no Apple licence, and no Restore: Play brings a subscription back itself.
        onNodeWithTag("premium:small-print").assert(hasText("Google Play", substring = true))
        onNodeWithTag("premium:terms").assertDoesNotExist()
        onNodeWithTag("premium:restore").assertDoesNotExist()
        onNodeWithTag("premium:privacy").assertExists()

        onNodeWithTag("premium:plan-month").performClick()
        onNodeWithTag("premium:subscribe").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Something went wrong").assertIsDisplayed()
        onNodeWithText("Please try again later.").assertIsDisplayed()
        onNodeWithTag("premium:alert-ok").assert(hasText("OK")).performClick()
        waitForIdle()
        onNodeWithTag("premium:alert").assertDoesNotExist()
        onNodeWithTag("premium:plan-month").assertIsSelected()
    }

    @Test
    fun pricesAreInTheRegionsCurrency() {
        val rub = PremiumPrices.forRegion("RU")
        assertEquals("299 ₽", rub.monthly)
        assertEquals("1 990 ₽", rub.yearly)
        assertEquals("166 ₽", rub.yearlyPerMonth)
        assertEquals(44, rub.yearlySaving)

        assertEquals("2,99 €", PremiumPrices.forRegion("de").monthly)
        assertEquals("£19.99", PremiumPrices.forRegion("GB").yearly)

        // A region with no price of its own, or none at all: dollars.
        val usd = PremiumPrices.forRegion("")
        assertEquals("\$2.99", usd.monthly)
        assertEquals("\$1.67", usd.yearlyPerMonth)
        assertEquals(44, usd.yearlySaving)
    }
}
