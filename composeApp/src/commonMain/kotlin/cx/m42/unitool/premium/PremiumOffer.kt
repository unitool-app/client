package cx.m42.unitool.premium

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.superizer.ActivateBanner
import cx.m42.superizer.HostScreen
import cx.m42.superizer.Superizer
import cx.m42.superizer.runtime.Platform
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.unitool.i18n.PremiumStrings
import cx.m42.unitool.i18n.premiumStrings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Premium offer above the promo code on Activate. It is a mock-up and sells nothing
 * (2026-10-05).
 *
 * It is laid out the way a store's own subscription offer is: two periods, a price in the region's
 * currency, one button, "Restore Purchases", and the store's small print with its links. There is
 * no StoreKit and no Play Billing behind it, and the store listings still say "no subscription".
 * So the buttons only say that purchases come later. The prices are [PremiumPrices]' placeholders.
 * A real offer reads them from the store (`Product.displayPrice`, `ProductDetails.formattedPrice`).
 */
internal object PremiumOffer : ActivateBanner {
    @Composable
    override fun Content(superizer: Superizer, open: (HostScreen) -> Unit): Boolean {
        val langTag by superizer.settings.langTag.collectAsState()
        PremiumCard(
            strings = premiumStrings(langTag),
            prices = PremiumPrices.forRegion(Locale.current.region),
            store = if (superizer.hostInfo.platform == Platform.Android) Store.GooglePlay else Store.AppStore,
        )
        return true
    }
}

/** Whose small print the card shows. Android buys through Google Play; every other target is drawn as iOS. */
internal enum class Store { AppStore, GooglePlay }

private enum class Plan { Month, Year }

/** Apple's standard licence agreement, which an app without its own EULA points to. */
private const val TERMS_URL = "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/"

/** Not public yet: `play/PRIVACY.md` lives in a private repo (RELEASING.md). Until it is, the link opens nothing. */
private val PRIVACY_URL: String? = null

@Composable
internal fun PremiumCard(strings: PremiumStrings, prices: PremiumPrices, store: Store) {
    val tokens = AppTheme
    val uri = LocalUriHandler.current
    // A year first, as store offers do: it is the better price, and the badge says by how much.
    var plan by remember { mutableStateOf(Plan.Year) }
    var tried by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radius))
            .background(tokens.background)
            .padding(16.dp)
            .testTag("premium:card"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StarBadge()
            Spacer(Modifier.width(12.dp))
            Text(text = strings.title, style = tokens.subtitle, color = tokens.foreground)
        }
        Spacer(Modifier.height(8.dp))
        Text(text = strings.lead, style = tokens.body, color = tokens.muted)

        Spacer(Modifier.height(16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
        ) {
            PlanTile(
                label = strings.monthly,
                price = prices.monthly,
                note = strings.billedMonthly,
                badge = null,
                selected = plan == Plan.Month,
                tag = "premium:plan-month",
                onClick = { plan = Plan.Month },
            )
            PlanTile(
                label = strings.yearly,
                price = prices.yearly,
                note = strings.perMonth(prices.yearlyPerMonth),
                badge = "−${prices.yearlySaving}%",
                selected = plan == Plan.Year,
                tag = "premium:plan-year",
                onClick = { plan = Plan.Year },
            )
        }

        Spacer(Modifier.height(16.dp))
        AppButton(
            text = when (plan) {
                Plan.Month -> strings.subscribeMonthly(prices.monthly)
                Plan.Year -> strings.subscribeYearly(prices.yearly)
            },
            onClick = { tried = true },
            size = ButtonSize.Default,
            modifier = Modifier.fillMaxWidth().testTag("premium:subscribe"),
        )
        Spacer(Modifier.height(4.dp))
        AppButton(
            text = strings.restore,
            onClick = { tried = true },
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Sm,
            modifier = Modifier.align(Alignment.CenterHorizontally).testTag("premium:restore"),
        )
        if (tried) {
            Text(
                text = strings.notYet,
                style = tokens.footnote,
                color = tokens.warning,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("premium:not-yet"),
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = when (store) {
                Store.AppStore -> strings.appStorePrint
                Store.GooglePlay -> strings.googlePlayPrint
            },
            style = tokens.footnote,
            color = tokens.muted,
            modifier = Modifier.testTag("premium:small-print"),
        )
        Spacer(Modifier.height(8.dp))
        // A link that does not fit moves to the next line whole, rather than breaking in two.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Google Play asks for no licence of its own; Apple's subscription screens link both.
            if (store == Store.AppStore) {
                Link(strings.terms, "premium:terms") { uri.openUri(TERMS_URL) }
            }
            Link(strings.privacy, "premium:privacy") { PRIVACY_URL?.let(uri::openUri) }
        }
    }
}

/** One period: its name, its price, a line under it, and the saving in a corner when there is one. */
@Composable
private fun RowScope.PlanTile(
    label: String,
    price: String,
    note: String,
    badge: String?,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val tokens = AppTheme
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) tokens.accentSubtle else tokens.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) tokens.accent else tokens.border, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(12.dp)
            .testTag(tag),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = tokens.label,
                color = if (selected) tokens.accent else tokens.muted,
                modifier = Modifier.weight(1f),
            )
            if (badge != null) {
                Text(
                    text = badge,
                    style = tokens.footnote,
                    color = tokens.primaryForeground,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(tokens.accent)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(text = price, style = tokens.subtitle, color = tokens.foreground)
        Text(text = note, style = tokens.footnote, color = tokens.muted)
    }
}

@Composable
private fun Link(text: String, tag: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = AppTheme.footnote,
        color = AppTheme.accent,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick).testTag(tag),
    )
}

/** A white star on the accent colour, drawn rather than typed so every platform shows the same glyph. */
@Composable
private fun StarBadge() {
    val tokens = AppTheme
    Box(
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(tokens.accent),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val outer = size.minDimension / 2
            val inner = outer * 0.42f
            // A five-pointed star's bottom points sit higher than its top one, so its middle is
            // lowered a little to look centred.
            val cx = size.width / 2
            val cy = size.height / 2 + outer * 0.1f
            val star = Path().apply {
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) outer else inner
                    val angle = -PI / 2 + i * PI / 5
                    val x = cx + r * cos(angle).toFloat()
                    val y = cy + r * sin(angle).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(star, tokens.primaryForeground)
        }
    }
}
