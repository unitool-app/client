package cx.m42.apps.converter

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.superizer.runtime.AppRuntime
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.ChoiceRow
import cx.m42.superizer.ui.components.RowDivider
import kotlinx.coroutines.launch

/** Where the converter keeps the currency a person chose to start from. */
internal const val KEY_BASE = "base"

/**
 * The converter's block on the host's Settings screen.
 *
 * It is the app's own composable, drawn inside the host's page under the app's own title — the host
 * never learns what is in it. That is what `ctx.settingsSection` is for, and it is the only way an
 * app can put a preference in front of a user without the host growing a row for it.
 *
 * Note which runtime this gets: the **app-level** one (D15). There is no screen here — Settings can
 * be open while the converter is not — so there is no navigation and no instance scope, only
 * storage, locale and the rest of what survives from enable to disable.
 */
@Composable
internal fun ConverterSettingsSection(runtime: AppRuntime, favorites: List<String>) {
    val tokens = AppTheme
    val langTag by runtime.locale.langTag.collectAsState()
    val strings = remember(langTag) { runtime.locale.pick(ConverterStringTables, ConverterStringsEn) }
    val scope = rememberCoroutineScope()

    var base by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(runtime) { base = runtime.storage.get(KEY_BASE) ?: DEFAULT_BASE }

    Column(modifier = Modifier.fillMaxWidth().testTag("currency-converter:settings")) {
        Text(
            text = strings.settingsHint,
            style = tokens.footnote,
            color = tokens.muted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        (listOf(DEFAULT_BASE) + favorites).distinct().forEachIndexed { index, code ->
            if (index > 0) RowDivider()
            ChoiceRow(
                label = code,
                selected = code == base,
                onClick = {
                    base = code
                    // The app's own storage, not the host's: a preference outlives the session and
                    // survives disable, and it is erased only by an explicit reset (D35).
                    scope.launch { runtime.storage.set(KEY_BASE, code) }
                },
            )
        }
    }
}

private const val DEFAULT_BASE = "USD"
