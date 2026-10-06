package cx.m42.unitool.premium

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composeunstyled.Text
import cx.m42.superizer.theme.AppTheme
import cx.m42.unitool.i18n.PremiumStrings

/**
 * What a purchase button says while there is nothing behind it: something went wrong, try again
 * later. It is a platform window rather than a layer of the card, so it covers the whole screen as
 * the store's own error would. On Android it inherits the activity's FLAG_SECURE.
 */
@Composable
internal fun PurchaseFailedAlert(strings: PremiumStrings, store: Store, onDismiss: () -> Unit) {
    when (store) {
        Store.AppStore -> AppleAlert(strings, onDismiss)
        Store.GooglePlay -> MaterialAlert(strings, onDismiss)
    }
}

/**
 * UIAlertController's shape: 270 points wide, the words centred, and the answer a full-width row
 * under a hairline. An iOS alert is answered; a tap beside it does nothing.
 */
@Composable
private fun AppleAlert(strings: PremiumStrings, onDismiss: () -> Unit) {
    val tokens = AppTheme
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(270.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tokens.pageBackground)
                .testTag("premium:alert"),
        ) {
            Text(
                text = strings.failedTitle,
                style = tokens.body.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
                color = tokens.foreground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = strings.failedMessage,
                style = tokens.footnote,
                color = tokens.foreground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.border))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .testTag("premium:alert-ok"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = strings.ok,
                    style = tokens.body.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    color = tokens.accent,
                )
            }
        }
    }
}

/**
 * Material's alert dialog: 28 dp corners, a regular-weight title over the text, both left-aligned,
 * and the answer a text button at the bottom right. Back and a tap beside it close it as well.
 */
@Composable
private fun MaterialAlert(strings: PremiumStrings, onDismiss: () -> Unit) {
    val tokens = AppTheme
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 280.dp, max = 560.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(tokens.background)
                .padding(24.dp)
                .testTag("premium:alert"),
        ) {
            Text(
                text = strings.failedTitle,
                style = tokens.header.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal),
                color = tokens.foreground,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = strings.failedMessage,
                style = tokens.label.copy(fontWeight = FontWeight.Normal),
                color = tokens.muted,
            )
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = 40.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .padding(horizontal = 12.dp)
                    .testTag("premium:alert-ok"),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = strings.ok, style = tokens.label, color = tokens.accent)
            }
        }
    }
}
