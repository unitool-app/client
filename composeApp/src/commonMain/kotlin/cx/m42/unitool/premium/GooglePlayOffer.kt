package cx.m42.unitool.premium

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.superizer.theme.AppTheme
import cx.m42.unitool.i18n.PremiumStrings

/**
 * The offer as an Android app's subscription screen shows it, in Material's terms:
 * - a round icon and a regular-weight title, left-aligned;
 * - the periods as a list of rows with a radio button each, the better one first;
 * - a pill-shaped button that only says "Subscribe", because Play's own sheet names the price
 *   again;
 * - Google Play's small print and the privacy policy.
 *
 * There is no "Restore Purchases": a Play subscription comes back by itself with the Google account
 * (`queryPurchasesAsync`). There is no licence link either: Play asks for none of its own.
 */
@Composable
internal fun GooglePlayOffer(
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
            .padding(20.dp)
            .testTag("premium:card"),
    ) {
        StarCircle()
        Spacer(Modifier.height(16.dp))
        Text(
            text = strings.title,
            style = tokens.header.copy(fontWeight = FontWeight.Normal),
            color = tokens.foreground,
        )
        Spacer(Modifier.height(4.dp))
        Text(text = strings.lead, style = tokens.body, color = tokens.muted)

        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.selectableGroup()) {
            PlanRow(
                label = strings.yearly,
                price = prices.yearly,
                note = strings.perMonth(prices.yearlyPerMonth),
                badge = "−${prices.yearlySaving}%",
                selected = plan == Plan.Year,
                tag = "premium:plan-year",
                onClick = { onPlan(Plan.Year) },
            )
            PlanRow(
                label = strings.monthly,
                price = prices.monthly,
                note = strings.billedMonthly,
                badge = null,
                selected = plan == Plan.Month,
                tag = "premium:plan-month",
                onClick = { onPlan(Plan.Month) },
            )
        }

        Spacer(Modifier.height(20.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(CircleShape)
                .background(tokens.accent)
                .clickable(role = Role.Button, onClick = onBuy)
                .testTag("premium:subscribe"),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = strings.subscribe, style = tokens.buttonLabel, color = tokens.primaryForeground)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = strings.googlePlayPrint,
            style = tokens.footnote,
            color = tokens.muted,
            modifier = Modifier.testTag("premium:small-print"),
        )
        Spacer(Modifier.height(8.dp))
        Link(strings.privacy, "premium:privacy") { PRIVACY_URL?.let(uri::openUri) }
    }
}

/** One period as a list row: a radio button, its name over a line, and its price at the end. */
@Composable
private fun PlanRow(
    label: String,
    price: String,
    note: String,
    badge: String?,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val tokens = AppTheme
    val shape = RoundedCornerShape(tokens.radius)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) tokens.accentSubtle else tokens.background)
            .border(if (selected) 2.dp else 1.dp, if (selected) tokens.accent else tokens.border, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(tag),
    ) {
        RadioMark(selected)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = label, style = tokens.buttonLabel, color = tokens.foreground)
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = badge,
                        style = tokens.footnote,
                        color = tokens.primaryForeground,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(tokens.accent)
                            .padding(horizontal = 8.dp, vertical = 1.dp),
                    )
                }
            }
            Text(text = note, style = tokens.footnote, color = tokens.muted)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = price,
            style = tokens.body.copy(fontWeight = FontWeight.SemiBold),
            color = tokens.foreground,
        )
    }
}

/** Material's radio button: a ring, and a dot inside it when chosen. */
@Composable
private fun RadioMark(selected: Boolean) {
    val color = if (selected) AppTheme.accent else AppTheme.muted
    Box(
        modifier = Modifier.size(20.dp).border(2.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    }
}

/** The star on a tonal circle, where iOS has an app icon's rounded square. */
@Composable
private fun StarCircle() {
    val tokens = AppTheme
    Box(
        modifier = Modifier.size(48.dp).clip(CircleShape).background(tokens.accentSubtle),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            drawPath(starPath(size.minDimension), tokens.accent)
        }
    }
}
