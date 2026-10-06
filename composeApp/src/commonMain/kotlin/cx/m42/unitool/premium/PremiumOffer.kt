package cx.m42.unitool.premium

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.intl.Locale
import com.composeunstyled.Text
import cx.m42.superizer.ActivateBanner
import cx.m42.superizer.ActivateEntry
import cx.m42.superizer.HostScreen
import cx.m42.superizer.Superizer
import cx.m42.superizer.app.AppIcon
import cx.m42.superizer.runtime.Platform
import cx.m42.superizer.theme.AppTheme
import cx.m42.unitool.i18n.PremiumStrings
import cx.m42.unitool.i18n.premiumStrings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Premium offer above the promo code on Activate. It is a mock-up and sells nothing
 * (2026-10-05).
 *
 * It is laid out the way each store's own subscription offer is: [AppStoreOffer] on iOS,
 * [GooglePlayOffer] on Android. Both have two periods, a price in the region's currency, and the
 * store's small print. There is no StoreKit and no Play Billing behind them, and the store listings
 * still say "no subscription". So every purchase button opens the store's kind of alert,
 * [PurchaseFailedAlert], saying that something went wrong and to try again later. The prices are
 * [PremiumPrices]' placeholders. A real offer reads them from the store (`Product.displayPrice`,
 * `ProductDetails.formattedPrice`).
 */
internal object PremiumOffer : ActivateBanner, ActivateEntry {
    /** In the drawer, Activate is a star and "Premium" rather than a QR code: buying comes first. */
    override val icon: AppIcon = AppIcon.Named("star")

    override fun title(langTag: String): String = premiumStrings(langTag).entry

    @Composable
    override fun Content(superizer: Superizer, open: (HostScreen) -> Unit): Boolean {
        val langTag by superizer.settings.langTag.collectAsState()
        PremiumCard(
            strings = premiumStrings(langTag),
            prices = PremiumPrices.forRegion(Locale.current.region),
            store = LocalPremiumStore.current
                ?: if (superizer.hostInfo.platform == Platform.Android) Store.GooglePlay else Store.AppStore,
        )
        return true
    }
}

/** Whose offer the card is drawn as. Android buys through Google Play; every other target is drawn as iOS. */
internal enum class Store { AppStore, GooglePlay }

/**
 * The store to draw, when it is not the platform's own. The screenshots set it: they are all
 * rendered on desktop, and the Play listing has to show the Android offer.
 */
internal val LocalPremiumStore = staticCompositionLocalOf<Store?> { null }

internal enum class Plan { Month, Year }

/** Not public yet: `play/PRIVACY.md` lives in a private repo (RELEASING.md). Until it is, the link opens nothing. */
internal val PRIVACY_URL: String? = null

@Composable
internal fun PremiumCard(strings: PremiumStrings, prices: PremiumPrices, store: Store) {
    // A year first, as store offers do: it is the better price, and the badge says by how much.
    var plan by remember { mutableStateOf(Plan.Year) }
    var failed by remember { mutableStateOf(false) }
    when (store) {
        Store.AppStore -> AppStoreOffer(strings, prices, plan, onPlan = { plan = it }, onBuy = { failed = true })
        Store.GooglePlay -> GooglePlayOffer(strings, prices, plan, onPlan = { plan = it }, onBuy = { failed = true })
    }
    if (failed) {
        PurchaseFailedAlert(strings, store, onDismiss = { failed = false })
    }
}

@Composable
internal fun Link(text: String, tag: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = AppTheme.footnote,
        color = AppTheme.accent,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick).testTag(tag),
    )
}

/**
 * A five-pointed star filling a square of [side], drawn rather than typed so every platform shows
 * the same glyph. Its bottom points sit higher than its top one, so its middle is lowered a little
 * to look centred.
 */
internal fun starPath(side: Float): Path {
    val outer = side / 2
    val inner = outer * 0.42f
    val centre = Offset(side / 2, side / 2 + outer * 0.1f)
    return Path().apply {
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outer else inner
            val angle = -PI / 2 + i * PI / 5
            val x = centre.x + r * cos(angle).toFloat()
            val y = centre.y + r * sin(angle).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}
