package cx.m42.unitool.security

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.components.RowDivider
import cx.m42.superizer.ui.platform.SecureWindow
import cx.m42.unitool.i18n.securityStrings

/**
 * The keyring's sheet over the whole shell (01 §5.2, 02 §7): which app, which key, what for — and
 * the passphrase of a level-3 key. Drawn by Unitool on every platform; on a phone the device's own
 * prompt for levels 1–2 is the system's, bound to the key, and this only asks what the chip cannot.
 *
 * Out of screenshots while a passphrase is asked for (T10).
 */
@Composable
internal fun KeyPromptSheet(prompts: KeyPrompts, langTag: String) {
    val question by prompts.current.collectAsState()
    val strings = securityStrings(langTag)
    val tokens = AppTheme
    when (val q = question) {
        null -> Unit
        is KeyPrompts.Question.Grant -> Sheet("keys:grant") {
            Text(strings.grantTitle(q.app.value, q.purpose), style = tokens.header, color = tokens.foreground)
            Spacer(Modifier.height(8.dp))
            Text(strings.grantBody(strings.keyLine(q.key.label, q.key.comment, q.key.id.fingerprint), q.purpose), style = tokens.body, color = tokens.muted)
            Spacer(Modifier.height(16.dp))
            Buttons(strings.deny, { prompts.answer(false) }, strings.allow, { prompts.answer(true) }, "keys:grant")
        }
        is KeyPrompts.Question.Choose -> Sheet("keys:choose") {
            Text(strings.chooseTitle(q.app.value, q.purpose), style = tokens.header, color = tokens.foreground)
            Spacer(Modifier.height(12.dp))
            q.candidates.forEachIndexed { i, key ->
                if (i > 0) RowDivider()
                Text(
                    text = strings.keyLine(key.label, key.comment, key.id.fingerprint),
                    style = tokens.body,
                    color = tokens.foreground,
                    modifier = Modifier.fillMaxWidth().clickable { prompts.answer(key.id) }.padding(vertical = 12.dp).testTag("keys:choose-$i"),
                )
            }
            Spacer(Modifier.height(12.dp))
            AppButton(strings.cancel, { prompts.answer(null) }, variant = ButtonVariant.Outline, size = ButtonSize.Default, modifier = Modifier.testTag("keys:choose-cancel"))
        }
        is KeyPrompts.Question.Passphrase -> key(q) {
            SecureWindow(active = true)
            var text by remember { mutableStateOf("") }
            Sheet("keys:passphrase") {
                Text(strings.operation(q.reason, q.reason.app?.value), style = tokens.header, color = tokens.foreground)
                Spacer(Modifier.height(4.dp))
                Text(strings.keyLine(q.reason.key.label, q.reason.key.comment, q.reason.key.id.fingerprint), style = tokens.footnote, color = tokens.muted)
                if (q.wrong > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(strings.wrongPassphrase, style = tokens.body, color = tokens.danger, modifier = Modifier.testTag("keys:wrong"))
                }
                Spacer(Modifier.height(12.dp))
                SecretField(text, { text = it }, strings.passphrase, Modifier.testTag("keys:passphrase-field"))
                Spacer(Modifier.height(16.dp))
                Buttons(
                    strings.cancel, { text = ""; prompts.answer(null) },
                    strings.unlock, {
                        val chars = text.toCharArray()
                        text = ""
                        prompts.answer(chars)
                    },
                    "keys:passphrase",
                )
            }
        }
        is KeyPrompts.Question.Confirm -> Sheet("keys:confirm") {
            Text(strings.operation(q.reason, q.reason.app?.value), style = tokens.header, color = tokens.foreground)
            Spacer(Modifier.height(16.dp))
            Buttons(strings.cancel, { prompts.answer(false) }, strings.confirm, { prompts.answer(true) }, "keys:confirm")
        }
    }
}

@Composable
private fun Buttons(no: String, onNo: () -> Unit, yes: String, onYes: () -> Unit, tag: String) {
    Row {
        AppButton(no, onNo, variant = ButtonVariant.Outline, size = ButtonSize.Default, modifier = Modifier.testTag("$tag-no"))
        Spacer(Modifier.width(12.dp))
        AppButton(yes, onYes, size = ButtonSize.Default, modifier = Modifier.testTag("$tag-yes"))
    }
}
