package cx.m42.unitool.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.unitool.BuildInfo
import cx.m42.unitool.components.AppScaffold
import cx.m42.unitool.components.CheckIcon
import cx.m42.unitool.components.MenuEntry
import cx.m42.unitool.data.AppSettings
import cx.m42.unitool.i18n.AppLocale
import cx.m42.unitool.i18n.Lang
import cx.m42.unitool.i18n.strings
import cx.m42.unitool.platform.hapticActionComplete
import cx.m42.unitool.theme.AppTheme

/**
 * Everything the app lets a person decide, which is deliberately little: the language it speaks,
 * whether the keys answer with a vibration, and which build this is.
 *
 * Laid out as card blocks on a grey page — the pattern the rest of the app uses for anything that
 * is a list of unlike things, so a section added later needs no new layout vocabulary.
 */
@Composable
fun SettingsScreen(
    menu: List<MenuEntry>,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    AppScaffold(
        title = strings.settings,
        menu = menu,
        onOpenSettings = onOpenSettings,
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppTheme.PageBackground)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
        ) {
            Section(title = strings.settingsLanguage, hint = strings.settingsLanguageHint) {
                Lang.entries.forEachIndexed { index, lang ->
                    if (index > 0) RowDivider()
                    ChoiceRow(
                        label = lang.nativeName,
                        selected = AppLocale.lang == lang,
                        onClick = {
                            // Written through AppLocale, so the choice is remembered on this device
                            // and the whole tree repaints in the new language on the next frame.
                            AppLocale.choose(lang)
                        },
                    )
                }
            }

            Section(title = strings.settingsHaptics, hint = strings.settingsHapticsHint) {
                // Read once into state: the preference is a plain stored string, not something a
                // composition can observe, so the screen holds the copy it renders.
                var enabled by remember { mutableStateOf(AppSettings.haptics) }
                ToggleRow(
                    label = strings.settingsHaptics,
                    checked = enabled,
                    onCheckedChange = { value ->
                        AppSettings.haptics = value
                        enabled = value
                        // Fired after the write, so turning it *on* is confirmed by the very thing
                        // it turns on.
                        hapticActionComplete()
                    },
                )
            }

            Section(title = strings.settingsAbout) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = strings.settingsBuild(BuildInfo.label),
                        style = AppTheme.Body,
                        color = AppTheme.Foreground,
                    )
                }
            }
        }
    }
}

/** A titled block of rows: the page's one structural unit. */
@Composable
private fun Section(
    title: String,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Text(
            text = title,
            style = AppTheme.Header,
            color = AppTheme.Foreground,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (hint != null) {
            Text(
                text = hint,
                style = AppTheme.Footnote,
                color = AppTheme.Muted,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(AppTheme.Radius))
                .background(AppTheme.Background),
        ) {
            content()
        }
    }
}

/** One of a set of mutually exclusive choices; the chosen one carries the tick. */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = AppTheme.Body,
            color = AppTheme.Foreground,
            modifier = Modifier.weight(1f),
        )
        if (selected) CheckIcon(AppTheme.Foreground)
    }
}

/** A row carrying a switch, with the whole row as its tap target. */
@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = AppTheme.Body,
            color = AppTheme.Foreground,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, label = label)
    }
}

/**
 * The switch itself: a track and a thumb, drawn rather than imported, for the same reason the icon
 * pack is hand-drawn — the app takes no Material dependency, and this is the only control of its
 * kind it needs.
 */
@Composable
private fun Switch(checked: Boolean, label: String) {
    val thumbOffset by animateDpAsState(if (checked) 20.dp else 2.dp)
    Box(
        modifier = Modifier
            .width(44.dp)
            .height(26.dp)
            .clip(CircleShape)
            .background(if (checked) AppTheme.Primary else AppTheme.Border)
            // The row above owns the click; this only has to say what it is showing.
            .semantics { contentDescription = label },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbOffset)
                .size(22.dp)
                .clip(CircleShape)
                .background(AppTheme.Background),
        )
    }
}

@Composable
private fun RowDivider() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(AppTheme.Border),
    )
}
