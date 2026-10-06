package cx.m42.unitool.premium

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.unitool.i18n.PremiumStrings

/** Apple's standard licence agreement, which an app without its own EULA points to. */
private const val TERMS_URL = "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/"

/**
 * The offer as an App Store subscription screen shows it: the periods side by side, a button that
 * names the price, "Restore Purchases" (App Review asks for one), and Apple's small print with the
 * licence and the privacy policy under it.
 */
@Composable
internal fun AppStoreOffer(
    strings: PremiumStrings,
    prices: PremiumPrices,
    plan: Plan,
    onPlan: (Plan) -> Unit,
    onBuy: () -> Unit,
) {
    val tokens = AppTheme
    val uri = LocalUriHandler.current

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
                onClick = { onPlan(Plan.Month) },
            )
            PlanTile(
                label = strings.yearly,
                price = prices.yearly,
                note = strings.perMonth(prices.yearlyPerMonth),
                badge = "−${prices.yearlySaving}%",
                selected = plan == Plan.Year,
                tag = "premium:plan-year",
                onClick = { onPlan(Plan.Year) },
            )
        }

        Spacer(Modifier.height(16.dp))
        AppButton(
            text = when (plan) {
                Plan.Month -> strings.subscribeMonthly(prices.monthly)
                Plan.Year -> strings.subscribeYearly(prices.yearly)
            },
            onClick = onBuy,
            size = ButtonSize.Default,
            modifier = Modifier.fillMaxWidth().testTag("premium:subscribe"),
        )
        Spacer(Modifier.height(4.dp))
        AppButton(
            text = strings.restore,
            onClick = onBuy,
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Sm,
            modifier = Modifier.align(Alignment.CenterHorizontally).testTag("premium:restore"),
        )

        Spacer(Modifier.height(12.dp))
        Text(
            text = strings.appStorePrint,
            style = tokens.footnote,
            color = tokens.muted,
            modifier = Modifier.testTag("premium:small-print"),
        )
        Spacer(Modifier.height(8.dp))
        // A link that does not fit moves to the next line whole, rather than breaking in two.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Link(strings.terms, "premium:terms") { uri.openUri(TERMS_URL) }
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

/** A white star on an accent rounded square: an iOS app icon's shape. */
@Composable
private fun StarBadge() {
    val tokens = AppTheme
    Box(
        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(tokens.accent),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            drawPath(starPath(size.minDimension), tokens.primaryForeground)
        }
    }
}
