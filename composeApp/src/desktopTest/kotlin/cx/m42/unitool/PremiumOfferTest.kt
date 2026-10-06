package cx.m42.unitool

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
 * the region's currency with the store's small print, and its buttons only say that purchases come
 * later.
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

        onNodeWithTag("premium:not-yet").assertDoesNotExist()
        onNodeWithTag("premium:subscribe").performClick()
        waitForIdle()
        onNodeWithTag("premium:not-yet").assertExists()

        onNodeWithTag("activate:have-code").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("activate:have-code").assertDoesNotExist()
        onNodeWithTag("activate:promo").assertExists()
    }

    @Test
    fun androidGetsGooglePlaysSmallPrintAndNoAppleLicence() = runComposeUiTest {
        setContent { PremiumCard(premiumStrings("en"), PremiumPrices.forRegion("US"), Store.GooglePlay) }
        waitForIdle()
        onNodeWithTag("premium:small-print").assert(hasText("Google Play", substring = true))
        onNodeWithTag("premium:terms").assertDoesNotExist()
        onNodeWithTag("premium:privacy").assertExists()
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
