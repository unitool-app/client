package cx.m42.apps.passwords

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import cx.m42.services.link.LinkState
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.AppTextField
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.components.RowDivider
import kotlinx.coroutines.delay

@Composable
internal fun browserStrings(): BrowserStrings {
    val runtime = LocalAppRuntime.current
    val langTag by runtime.locale.langTag.collectAsState()
    return remember(langTag) { runtime.locale.pick(BrowserStringTables, BrowserStringsEn) }
}

/** The way into "Computers", under Import and Export — only where the host can listen at all. */
@Composable
internal fun ComputersButton(instance: PasswordsInstance) {
    if (!instance.browser.available) return
    Spacer(Modifier.height(8.dp))
    AppButton(
        text = browserStrings().computers,
        onClick = instance::startComputers,
        variant = ButtonVariant.Ghost,
        modifier = Modifier.fillMaxWidth().testTag("pw:computers"),
    )
}

// -------------------------------------------------------------------- Computers

@Composable
internal fun ComputersPage(instance: PasswordsInstance) {
    val tokens = AppTheme
    val strings = browserStrings()
    val browser = instance.browser
    val computers by browser.computers.collectAsState()
    val pairing by browser.pairing.collectAsState()
    val listening by browser.listening.collectAsState()
    var open by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("pw:computers-page"),
    ) {
        Text(text = strings.computersIntro, style = tokens.body, color = tokens.muted)
        Spacer(Modifier.height(16.dp))

        pairing?.let { PairingPanel(instance, it, strings) }

        if (computers.isEmpty()) {
            Text(text = strings.noComputers, style = tokens.body, color = tokens.foreground, modifier = Modifier.testTag("pw:no-computers"))
        } else {
            Card {
                computers.forEachIndexed { index, computer ->
                    if (index > 0) RowDivider()
                    val last = computer.journal.firstOrNull()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { open = if (open == computer.key) null else computer.key }
                            .testTag("pw:computer-$index")
                            .padding(16.dp),
                    ) {
                        Text(text = computer.name, style = tokens.subtitle, color = tokens.foreground)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = last?.let { strings.lastRequest(Export.isoDate(it.at), it.site) } ?: strings.noRequestsYet,
                            style = tokens.footnote,
                            color = tokens.muted,
                        )
                        if (open == computer.key) {
                            Spacer(Modifier.height(8.dp))
                            Text(text = strings.pairedOn(Export.isoDate(computer.pairedAt)), style = tokens.footnote, color = tokens.muted)
                            if (computer.journal.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(text = strings.journal, style = tokens.label, color = tokens.foreground)
                                computer.journal.forEach { line ->
                                    Text(
                                        text = "${Export.isoDate(line.at)} ${clock(line.at)} · ${line.site} · ${strings.outcome(line.outcome)}",
                                        style = tokens.footnote,
                                        color = tokens.muted,
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            AppButton(
                                text = strings.forget,
                                onClick = { browser.forget(computer) },
                                variant = ButtonVariant.Destructive,
                                size = ButtonSize.Sm,
                                modifier = Modifier.testTag("pw:forget-$index"),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        if (browser.canPair) {
            AppButton(
                text = strings.pairComputer,
                onClick = browser::pair,
                modifier = Modifier.fillMaxWidth().testTag("pw:pair"),
            )
            Hint(strings.pairHint)
        } else {
            Hint(strings.noCamera)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = when (listening) {
                is LinkState.Listening -> strings.listening((listening as LinkState.Listening).port)
                is LinkState.Failed -> strings.listenFailed
                else -> strings.notListening
            },
            style = tokens.footnote,
            color = if (listening is LinkState.Failed) tokens.danger else tokens.muted,
            modifier = Modifier.testTag("pw:listening"),
        )
    }
}

private fun clock(millis: Long): String {
    val minutes = (millis / 60_000) % (24 * 60)
    return "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')} UTC"
}

@Composable
private fun PairingPanel(instance: PasswordsInstance, pairing: BrowserLink.Pairing, strings: BrowserStrings) {
    val tokens = AppTheme
    val browser = instance.browser
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("pw:pairing")) {
            when (pairing) {
                is BrowserLink.Pairing.Confirm -> {
                    Text(text = strings.pairQuestion(pairing.code.name), style = tokens.subtitle, color = tokens.foreground, modifier = Modifier.testTag("pw:pair-question"))
                    Spacer(Modifier.height(8.dp))
                    Text(text = strings.pairFingerprint, style = tokens.footnote, color = tokens.muted)
                    Text(
                        text = pairing.sshKey.fingerprint,
                        style = tokens.body.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        color = tokens.foreground,
                        modifier = Modifier.testTag("pw:pair-fingerprint"),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(text = strings.pairExplain, style = tokens.footnote, color = tokens.muted)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton(text = strings.pairYes, onClick = browser::confirmPairing, modifier = Modifier.testTag("pw:pair-yes"))
                        AppButton(text = strings.cancel, onClick = browser::dismissPairing, variant = ButtonVariant.Ghost, modifier = Modifier.testTag("pw:pair-cancel"))
                    }
                }
                is BrowserLink.Pairing.Waiting -> {
                    Text(text = strings.pairWaiting, style = tokens.subtitle, color = tokens.foreground, modifier = Modifier.testTag("pw:pair-waiting"))
                    Spacer(Modifier.height(4.dp))
                    Text(text = strings.pairWaitingHint, style = tokens.footnote, color = tokens.muted)
                    Spacer(Modifier.height(12.dp))
                    AppButton(text = strings.cancel, onClick = browser::dismissPairing, variant = ButtonVariant.Ghost, modifier = Modifier.testTag("pw:pair-cancel"))
                }
                is BrowserLink.Pairing.Paired -> {
                    Text(text = strings.paired(pairing.name), style = tokens.subtitle, color = tokens.success, modifier = Modifier.testTag("pw:paired"))
                    Spacer(Modifier.height(12.dp))
                    AppButton(text = strings.close, onClick = browser::dismissPairing, variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
                }
                is BrowserLink.Pairing.Failed -> {
                    Text(text = strings.pairProblem(pairing.problem), style = tokens.body, color = tokens.danger, modifier = Modifier.testTag("pw:pair-failed"))
                    Spacer(Modifier.height(12.dp))
                    AppButton(text = strings.close, onClick = browser::dismissPairing, variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}

// -------------------------------------------------------------------- the request sheet

private val Scrim = Color(0x99000000)

/**
 * "laptop · Chrome asks for the password of github.com" (README §5, D543): the site large, in
 * Unicode and in the form the address has; the computer's name and its browser marked as its word;
 * the site's entries, a search over the rest, the red warning when the chosen one is another site's.
 */
@Composable
internal fun RequestSheet(instance: PasswordsInstance) {
    val tokens = AppTheme
    val strings = browserStrings()
    val browser = instance.browser
    val request by browser.request.collectAsState()
    val sheet = request ?: return
    val runtime = LocalAppRuntime.current
    var now by remember(sheet.deadline) { mutableStateOf(runtime.clock.now()) }
    LaunchedEffect(sheet.deadline) {
        while (true) {
            delay(1000)
            now = runtime.clock.now()
        }
    }
    val candidates = browser.candidates(sheet)
    val chosen = candidates.firstOrNull { it.id == sheet.selected } ?: instance.entries.value.firstOrNull { it.id == sheet.selected }
    val warning = chosen?.let { browser.mismatch(sheet, it) }

    // The page under the sheet takes no taps while it is open; `clickable` would merge the sheet's semantics.
    Box(
        modifier = Modifier.fillMaxSize().background(Scrim).pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(tokens.pageBackground)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
                .testTag("pw:request"),
        ) {
            Text(text = strings.requestTitle, style = tokens.label, color = tokens.muted)
            Spacer(Modifier.height(8.dp))
            Text(text = sheet.unicodeHost, style = tokens.header, color = tokens.foreground, modifier = Modifier.testTag("pw:request-site"))
            if (sheet.unicodeHost != sheet.host) {
                Text(
                    text = "${strings.asciiForm} ${sheet.host}",
                    style = tokens.footnote.copy(fontFamily = FontFamily.Monospace),
                    color = tokens.muted,
                    modifier = Modifier.testTag("pw:request-ascii"),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = strings.requestFrom(sheet.computerName, sheet.browser) + " (" + strings.computerSays + ")",
                style = tokens.footnote,
                color = tokens.muted,
                modifier = Modifier.testTag("pw:request-from"),
            )
            Spacer(Modifier.height(16.dp))

            if (sheet.searching) {
                if (sheet.matches.isEmpty()) Text(text = strings.noEntriesForSite, style = tokens.body, color = tokens.foreground)
                Spacer(Modifier.height(8.dp))
                AppTextField(
                    value = sheet.query,
                    onValueChange = browser::search,
                    placeholder = strings.searchAll,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier.testTag("pw:request-search"),
                )
                Spacer(Modifier.height(8.dp))
            } else {
                Text(text = strings.chooseEntry, style = tokens.label, color = tokens.foreground)
                Spacer(Modifier.height(8.dp))
            }
            Card {
                Column(modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    candidates.forEachIndexed { index, entry ->
                        if (index > 0) RowDivider()
                        val selected = entry.id == sheet.selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.RadioButton) { browser.choose(entry.id) }
                                .background(if (selected) tokens.border else Color.Transparent)
                                .testTag("pw:request-entry-$index")
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = entry.shownTitle, style = tokens.body, color = tokens.foreground)
                                if (entry.username.isNotEmpty()) Text(text = entry.username, style = tokens.footnote, color = tokens.muted)
                            }
                            if (selected) Text(text = "✓", style = tokens.subtitle, color = tokens.foreground)
                        }
                    }
                }
            }
            if (!sheet.searching) {
                AppButton(
                    text = strings.showAll,
                    onClick = browser::showAll,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                    modifier = Modifier.testTag("pw:request-show-all"),
                )
            }
            warning?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = strings.mismatch(it, sheet.unicodeHost),
                    style = tokens.body,
                    color = tokens.danger,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(tokens.radius))
                        .background(tokens.background)
                        .padding(12.dp)
                        .testTag("pw:request-mismatch"),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(
                    text = strings.send,
                    onClick = browser::send,
                    enabled = chosen != null,
                    variant = if (warning != null) ButtonVariant.Destructive else ButtonVariant.Default,
                    modifier = Modifier.weight(1f).testTag("pw:request-send"),
                )
                AppButton(
                    text = strings.deny,
                    onClick = browser::deny,
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.weight(1f).testTag("pw:request-deny"),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = strings.secondsLeft(((sheet.deadline - now) / 1000).coerceAtLeast(0)),
                style = tokens.footnote,
                color = tokens.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
