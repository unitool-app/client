package cx.m42.apps.twofa

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.AppTextField
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.components.RowDivider
import cx.m42.superizer.ui.components.Switch
import cx.m42.superizer.ui.platform.SystemBackHandler
import kotlinx.coroutines.delay

/** The last seconds of a code, when it is safer to wait for the next one than to type this one. */
private const val EXPIRING_SECONDS = 5

/**
 * The list of codes, the add form, one account's page, the export and the import — five pages of one app,
 * switched on [TwoFaInstance.page] the way the host switches its own destinations.
 */
@Composable
internal fun TwoFaScreen(instance: TwoFaInstance) {
    val runtime = LocalAppRuntime.current
    val langTag by runtime.locale.langTag.collectAsState()
    val strings = remember(langTag) { runtime.locale.pick(TwoFaStringTables, TwoFaStringsEn) }
    val page by instance.page.collectAsState()
    val loaded by instance.loaded.collectAsState()

    // Once a second, on the second: a code that changes half a second late is a code somebody
    // typed after it stopped working.
    LaunchedEffect(instance) {
        while (true) {
            instance.tick()
            delay(1000 - instance.now.value % 1000)
        }
    }

    // Composed inside the app, so it claims back before the host does — and only while there is
    // somewhere inside the app to go back to.
    SystemBackHandler(enabled = page != Page.List) { instance.back() }

    Box(modifier = Modifier.fillMaxSize().background(AppTheme.pageBackground)) {
        // The moment it takes the Keystore to open the list: blank, not "no accounts yet".
        if (!loaded) return@Box
        when (val current = page) {
            Page.List -> ListPage(instance, strings)
            Page.Add -> AddPage(instance, strings)
            is Page.Details -> DetailsPage(instance, strings, current.id)
            Page.Export -> ExportPage(instance, strings)
            Page.Import -> ImportPage(instance, strings)
        }
    }
}

// -------------------------------------------------------------------- list

@Composable
private fun ListPage(instance: TwoFaInstance, strings: TwoFaStrings) {
    val tokens = AppTheme
    val accounts by instance.accounts.collectAsState()
    val query by instance.query.collectAsState()
    // Handed down as a parameter, not read inside the row: with strong skipping a row whose
    // arguments are unchanged is skipped, and a code read from the instance would freeze.
    val now by instance.now.collectAsState()
    val copiedId by instance.copiedId.collectAsState()
    val problem by instance.storageProblem.collectAsState()
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxSize().testTag("2fa:list")) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            problem?.let {
                Text(
                    text = strings.storageProblem(it),
                    style = tokens.body,
                    color = tokens.danger,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .clip(RoundedCornerShape(tokens.radius))
                        .background(tokens.background)
                        .padding(16.dp)
                        .testTag("2fa:storage-problem"),
                )
            }
            if (accounts.isEmpty()) {
                // "No accounts yet" would be untrue over a list that is there but cannot be read.
                if (problem != StorageProblem.Unreadable) EmptyState(strings)
            } else {
                AppTextField(
                    value = query,
                    onValueChange = { instance.query.value = it },
                    placeholder = strings.search,
                    modifier = Modifier.testTag("2fa:search"),
                )
                Spacer(Modifier.height(12.dp))

                val visible = instance.visibleAccounts()
                if (visible.isEmpty()) {
                    Text(
                        text = strings.nothingFound,
                        style = tokens.body,
                        color = tokens.muted,
                        modifier = Modifier.padding(8.dp).testTag("2fa:nothing-found"),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(tokens.radius))
                        .background(tokens.background),
                ) {
                    visible.forEachIndexed { index, account ->
                        if (index > 0) RowDivider()
                        AccountRow(
                            instance = instance,
                            account = account,
                            now = now,
                            index = index,
                            copied = copiedId == account.id,
                            strings = strings,
                            onCopy = {
                                val code = instance.copy(account)
                                // Headless desktop and a browser without clipboard permission both
                                // throw here; the code is on the screen either way.
                                runCatching { clipboard.setText(AnnotatedString(code)) }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = strings.tapToCopy,
                    style = tokens.footnote,
                    color = tokens.muted,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(16.dp))
                // Below the list rather than beside Add: something done once a year, not every day.
                AppButton(
                    text = strings.export,
                    onClick = instance::startExport,
                    variant = ButtonVariant.Ghost,
                    modifier = Modifier.fillMaxWidth().testTag("2fa:export"),
                )
            }
        }

        // Nowhere to save a new account while the stored list cannot be read (TwoFaInstance.persist).
        if (problem != StorageProblem.Unreadable) {
            AppButton(
                text = strings.add,
                onClick = instance::startAdd,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("2fa:add"),
            )
        }
    }
}

@Composable
private fun EmptyState(strings: TwoFaStrings) {
    val tokens = AppTheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 8.dp, end = 8.dp).testTag("2fa:empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(tokens.background),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "2FA", style = tokens.subtitle, color = tokens.foreground)
        }
        Spacer(Modifier.height(20.dp))
        Text(text = strings.emptyTitle, style = tokens.header, color = tokens.foreground)
        Spacer(Modifier.height(8.dp))
        Text(
            text = strings.emptyHint,
            style = tokens.body,
            color = tokens.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun AccountRow(
    instance: TwoFaInstance,
    account: TotpAccount,
    now: Long,
    index: Int,
    copied: Boolean,
    strings: TwoFaStrings,
    onCopy: () -> Unit,
) {
    val tokens = AppTheme
    val left = Totp.secondsLeft(account, now)
    val expiring = left <= EXPIRING_SECONDS
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onCopy)
            .testTag("2fa:row-$index")
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.title,
                style = tokens.label,
                color = tokens.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (account.subtitle.isNotEmpty()) {
                Text(
                    text = account.subtitle,
                    style = tokens.footnote,
                    color = tokens.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = Totp.grouped(Totp.code(account, now)),
                style = codeStyle(32.sp),
                color = if (expiring) tokens.danger else tokens.foreground,
                modifier = Modifier.testTag("2fa:code-$index"),
            )
            if (copied) {
                Text(
                    text = strings.copied,
                    style = tokens.footnote,
                    color = tokens.success,
                    modifier = Modifier.testTag("2fa:copied"),
                )
            }
        }
        Countdown(left, account.period, expiring, strings)
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .clickable(role = Role.Button) { instance.openDetails(account) }
                .semantics { contentDescription = strings.details }
                .testTag("2fa:details-$index"),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "›", style = tokens.header, color = tokens.muted)
        }
    }
}

/** A ring that empties as the code ages, with the seconds in it. */
@Composable
private fun Countdown(left: Int, period: Int, expiring: Boolean, strings: TwoFaStrings) {
    val tokens = AppTheme
    val color = if (expiring) tokens.danger else tokens.foreground
    val track = tokens.border
    Box(
        modifier = Modifier.size(36.dp).semantics { contentDescription = strings.seconds(left) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(36.dp)) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(
                color,
                -90f,
                -360f * left / period,
                false,
                Offset(inset, inset),
                arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text(text = left.toString(), style = tokens.footnote, color = color)
    }
}

@Composable
private fun codeStyle(size: androidx.compose.ui.unit.TextUnit): TextStyle =
    AppTheme.display.copy(fontSize = size, lineHeight = size * 1.2f, letterSpacing = 1.sp, fontFeatureSettings = "tnum")

// -------------------------------------------------------------------- add

@Composable
private fun AddPage(instance: TwoFaInstance, strings: TwoFaStrings) {
    val tokens = AppTheme
    val mode by instance.addMode.collectAsState()
    val link by instance.link.collectAsState()
    val draft by instance.draft.collectAsState()
    val error by instance.draftError.collectAsState()
    val now by instance.now.collectAsState()
    val scanning by instance.scanning.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("2fa:add-page"),
    ) {
        Segmented(
            options = listOf(AddMode.Link to strings.byLink, AddMode.Key to strings.byKey),
            selected = mode,
            onSelect = instance::setMode,
            tag = { "2fa:mode-${it.name.lowercase()}" },
        )
        Spacer(Modifier.height(16.dp))

        when (mode) {
            AddMode.Link -> {
                // The camera first where there is one: the QR code is what the site shows, and
                // pointing at it beats getting its text onto the clipboard.
                if (instance.canScan) {
                    AppButton(
                        text = strings.scan,
                        onClick = instance::scan,
                        enabled = !scanning,
                        modifier = Modifier.fillMaxWidth().testTag("2fa:scan"),
                    )
                    Hint(strings.scanHint)
                    Spacer(Modifier.height(20.dp))
                    FieldLabel(strings.orPaste)
                } else {
                    FieldLabel(strings.link)
                }
                AppTextField(
                    value = link,
                    onValueChange = { instance.link.value = it; instance.draftError.value = null },
                    placeholder = "otpauth://totp/…",
                    label = strings.link,
                    singleLine = false,
                    minHeight = 96.dp,
                    modifier = Modifier.testTag("2fa:link"),
                )
                Hint(strings.linkHint)
                ErrorLine(error, strings)
                Spacer(Modifier.height(16.dp))
                AppButton(
                    text = strings.readLink,
                    onClick = { instance.readLink() },
                    enabled = link.isNotBlank(),
                    // Second to the camera when there is one; the only way in when there is not.
                    variant = if (instance.canScan) ButtonVariant.Secondary else ButtonVariant.Default,
                    modifier = Modifier.fillMaxWidth().testTag("2fa:read-link"),
                )
            }

            AddMode.Key -> {
                FieldLabel(strings.issuer)
                AppTextField(
                    value = draft.issuer,
                    onValueChange = { v -> instance.editDraft { copy(issuer = v) } },
                    placeholder = strings.issuerHint,
                    label = strings.issuer,
                    modifier = Modifier.testTag("2fa:issuer"),
                )
                Spacer(Modifier.height(12.dp))
                FieldLabel(strings.accountName)
                AppTextField(
                    value = draft.name,
                    onValueChange = { v -> instance.editDraft { copy(name = v) } },
                    placeholder = strings.accountNameHint,
                    label = strings.accountName,
                    modifier = Modifier.testTag("2fa:name"),
                )
                Spacer(Modifier.height(12.dp))
                FieldLabel(strings.secret)
                AppTextField(
                    value = draft.secret,
                    onValueChange = { v -> instance.editDraft { copy(secret = v) } },
                    placeholder = "JBSW Y3DP EHPK 3PXP",
                    label = strings.secret,
                    textStyle = tokens.body.copy(fontFeatureSettings = "tnum", letterSpacing = 1.sp),
                    modifier = Modifier.testTag("2fa:secret"),
                )
                Hint(strings.secretHint)

                Spacer(Modifier.height(16.dp))
                Text(text = strings.advanced, style = tokens.label, color = tokens.foreground)
                Spacer(Modifier.height(8.dp))
                OptionLine(strings.algorithm) {
                    Segmented(
                        options = HashAlgorithm.entries.map { it to it.name },
                        selected = draft.algorithm,
                        onSelect = { a -> instance.editDraft { copy(algorithm = a) } },
                        tag = { "2fa:algorithm-${it.name}" },
                    )
                }
                OptionLine(strings.digits) {
                    Segmented(
                        options = listOf(6, 7, 8).map { it to it.toString() },
                        selected = draft.digits,
                        onSelect = { d -> instance.editDraft { copy(digits = d) } },
                        tag = { "2fa:digits-$it" },
                    )
                }
                OptionLine(strings.period) {
                    Segmented(
                        options = listOf(30, 60).map { it to it.toString() },
                        selected = draft.period,
                        onSelect = { p -> instance.editDraft { copy(period = p) } },
                        tag = { "2fa:period-$it" },
                    )
                }

                val preview = instance.previewCode(now)
                if (preview != null) {
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(tokens.radius))
                            .background(tokens.background)
                            .padding(16.dp),
                    ) {
                        Text(text = strings.preview, style = tokens.footnote, color = tokens.muted)
                        Text(
                            text = Totp.grouped(preview),
                            style = codeStyle(28.sp),
                            color = tokens.foreground,
                            modifier = Modifier.testTag("2fa:preview"),
                        )
                    }
                }

                ErrorLine(error, strings)
                Spacer(Modifier.height(16.dp))
                AppButton(
                    text = strings.save,
                    onClick = { instance.save() },
                    modifier = Modifier.fillMaxWidth().testTag("2fa:save"),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        AppButton(
            text = strings.cancel,
            onClick = { instance.back() },
            variant = ButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth().testTag("2fa:cancel"),
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = AppTheme.label,
        color = AppTheme.foreground,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = AppTheme.footnote,
        color = AppTheme.muted,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun ErrorLine(error: DraftError?, strings: TwoFaStrings) {
    if (error == null) return
    Text(
        text = strings.error(error),
        style = AppTheme.label,
        color = AppTheme.danger,
        modifier = Modifier.padding(top = 10.dp).testTag("2fa:error"),
    )
}

@Composable
private fun OptionLine(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = AppTheme.body, color = AppTheme.muted, modifier = Modifier.width(128.dp))
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}

/** A row of mutually exclusive choices, the selected one filled — the add form's radio buttons. */
@Composable
private fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    tag: (T) -> String,
) {
    val tokens = AppTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tokens.border)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) tokens.background else tokens.border)
                    .clickable(role = Role.RadioButton) { onSelect(value) }
                    .testTag(tag(value))
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = label, style = tokens.label, color = if (on) tokens.foreground else tokens.muted)
            }
        }
    }
}

// -------------------------------------------------------------------- export

@Composable
private fun ExportPage(instance: TwoFaInstance, strings: TwoFaStrings) {
    val tokens = AppTheme
    val accounts by instance.accounts.collectAsState()
    val login by instance.exportLogin.collectAsState()
    val keys by instance.exportKeys.collectAsState()
    val selected by instance.exportSelected.collectAsState()
    val done by instance.exportDone.collectAsState()
    // The file name in the commands carries the date, so it follows the clock like the codes do.
    val now by instance.now.collectAsState()
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copiedCommand by remember { mutableStateOf<String?>(null) }
    val loaded = keys as? KeysState.Loaded

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("2fa:export-page"),
    ) {
        Text(text = strings.exportIntro(accounts.size), style = tokens.body, color = tokens.muted)
        Spacer(Modifier.height(20.dp))
        FieldLabel(strings.githubAccount)
        AppTextField(
            value = login,
            onValueChange = instance::editLogin,
            placeholder = "octocat",
            label = strings.githubAccount,
            modifier = Modifier.testTag("2fa:github"),
        )
        Hint(strings.githubHint)
        Spacer(Modifier.height(12.dp))
        AppButton(
            text = if (keys == KeysState.Loading) strings.findingKeys else strings.findKeys,
            onClick = instance::findKeys,
            enabled = login.isNotBlank() && keys != KeysState.Loading,
            variant = if (loaded != null) ButtonVariant.Secondary else ButtonVariant.Default,
            modifier = Modifier.fillMaxWidth().testTag("2fa:find-keys"),
        )
        (keys as? KeysState.Failed)?.let {
            Text(
                text = strings.keysError(it.error),
                style = tokens.label,
                color = tokens.danger,
                modifier = Modifier.padding(top = 10.dp).testTag("2fa:keys-error"),
            )
        }

        if (loaded != null) {
            Spacer(Modifier.height(24.dp))
            FieldLabel(strings.keysOf(loaded.login))
            Card {
                loaded.keys.forEachIndexed { index, key ->
                    if (index > 0) RowDivider()
                    KeyRow(key, key.key.fingerprint in selected, index, strings) { instance.toggleKey(key) }
                }
            }
            Hint(strings.keysHint)

            Spacer(Modifier.height(20.dp))
            val ready = selected.isNotEmpty() && accounts.isNotEmpty()
            if (instance.canShare) {
                AppButton(
                    text = strings.shareExport,
                    onClick = instance::share,
                    enabled = ready,
                    modifier = Modifier.fillMaxWidth().testTag("2fa:share"),
                )
                Spacer(Modifier.height(8.dp))
            }
            AppButton(
                text = strings.copyExport,
                onClick = {
                    // Encrypted, so the clipboard — which keyboards and other apps can read — only
                    // ever sees what the share sheet would have carried anyway.
                    instance.copyExport()?.let { text -> runCatching { clipboard.setText(AnnotatedString(text)) } }
                },
                enabled = ready,
                variant = if (instance.canShare) ButtonVariant.Secondary else ButtonVariant.Default,
                modifier = Modifier.fillMaxWidth().testTag("2fa:copy-export"),
            )
            done?.let {
                Text(
                    text = strings.exportDone(it),
                    style = tokens.label,
                    color = tokens.success,
                    modifier = Modifier.padding(top = 10.dp).testTag("2fa:export-done"),
                )
            }

            val recipients = loaded.keys.filter { it.usable && it.key.fingerprint in selected }.map { it.key }
            val commands = Export.commands(recipients, Export.fileName(now))
            if (commands.isNotEmpty()) {
                val copy: (String) -> Unit = { command ->
                    copiedCommand = command
                    runCatching { clipboard.setText(AnnotatedString(command)) }
                }
                Spacer(Modifier.height(24.dp))
                FieldLabel(strings.decryptTitle)
                Commands(commands, "2fa:command", copy)
                Hint(if (copiedCommand in commands) strings.commandCopied else strings.tapToCopyCommand)
                Spacer(Modifier.height(4.dp))
                Hint(strings.decryptHint)

                val plaintext = remember(accounts) { Export.plaintext(accounts) }
                val transfers = Export.transferCommands(recipients, Export.fileName(now), plaintext)
                Spacer(Modifier.height(24.dp))
                FieldLabel(strings.transferTitle)
                Commands(transfers, "2fa:transfer", copy)
                Hint(if (copiedCommand in transfers) strings.commandCopied else strings.tapToCopyCommand)
                Spacer(Modifier.height(4.dp))
                Hint(strings.transferHint)
            }
        }

        Spacer(Modifier.height(8.dp))
        AppButton(
            text = strings.back,
            onClick = { instance.back() },
            variant = ButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth().testTag("2fa:export-back"),
        )
    }
}

/** Commands to type on a computer, each copied by a tap. */
@Composable
private fun Commands(commands: List<String>, tag: String, onCopy: (String) -> Unit) {
    Card {
        commands.forEachIndexed { index, command ->
            if (index > 0) RowDivider()
            Text(
                text = command,
                style = AppTheme.footnote,
                color = AppTheme.foreground,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onCopy(command) }
                    .testTag("$tag-$index")
                    .padding(16.dp),
            )
        }
    }
}

/** One of the account's keys: what kind, its fingerprint, and a switch — or why there is none. */
@Composable
private fun KeyRow(key: GitHubKey, checked: Boolean, index: Int, strings: TwoFaStrings, onToggle: () -> Unit) {
    val tokens = AppTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = key.usable, role = Role.Switch, onClick = onToggle)
            .testTag("2fa:key-$index")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = key.key.label,
                style = tokens.label,
                color = if (key.usable) tokens.foreground else tokens.muted,
            )
            Text(text = key.key.fingerprint, style = tokens.footnote, color = tokens.muted)
            if (!key.usable) {
                Text(
                    text = if (key.key is SshKey.Rsa) strings.weakKey else strings.unsupportedKey,
                    style = tokens.footnote,
                    color = tokens.muted,
                )
            }
        }
        if (key.usable) {
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, label = key.key.label)
        }
    }
}

// -------------------------------------------------------------------- import

@Composable
private fun ImportPage(instance: TwoFaInstance, strings: TwoFaStrings) {
    val tokens = AppTheme
    // Null only for the moment between leaving the page and it being gone.
    val batch = instance.importBatch.collectAsState().value ?: return
    val selected by instance.importSelected.collectAsState()
    val error by instance.draftError.collectAsState()
    val scanning by instance.scanning.collectAsState()
    val chosen = batch.addable.count { it in selected }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("2fa:import-page"),
    ) {
        Text(
            text = strings.importIntro(batch.found.size, batch.addable.size),
            style = tokens.body,
            color = tokens.muted,
            modifier = Modifier.testTag("2fa:import-intro"),
        )
        if (batch.found.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Card {
                batch.found.forEachIndexed { index, found ->
                    if (index > 0) RowDivider()
                    ImportRow(found, index in selected, index, strings) { instance.toggleImport(index) }
                }
            }
        }
        batch.failed.forEachIndexed { index, failed ->
            Text(
                text = strings.importFailed(failed),
                style = tokens.label,
                color = tokens.danger,
                modifier = Modifier.padding(top = 10.dp).testTag("2fa:import-failed-$index"),
            )
        }

        if (instance.canScan) {
            Spacer(Modifier.height(20.dp))
            AppButton(
                text = strings.scanMore,
                onClick = instance::scan,
                enabled = !scanning,
                variant = ButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth().testTag("2fa:import-scan"),
            )
            Hint(strings.scanMoreHint)
        }
        ErrorLine(error, strings)

        Spacer(Modifier.height(16.dp))
        // No "Add 0 accounts" when the device has every one of them already.
        if (batch.addable.isNotEmpty()) {
            AppButton(
                text = strings.importAdd(chosen),
                onClick = { instance.importAccounts() },
                enabled = chosen > 0,
                modifier = Modifier.fillMaxWidth().testTag("2fa:import-add"),
            )
            Spacer(Modifier.height(8.dp))
        }
        AppButton(
            text = strings.cancel,
            onClick = { instance.back() },
            variant = ButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth().testTag("2fa:import-cancel"),
        )
    }
}

/** One account from the batch: its names and a switch — or, where the device has it, a note instead. */
@Composable
private fun ImportRow(found: ImportBatch.Found, checked: Boolean, index: Int, strings: TwoFaStrings, onToggle: () -> Unit) {
    val tokens = AppTheme
    val account = found.account
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !found.known, role = Role.Switch, onClick = onToggle)
            .testTag("2fa:import-row-$index")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.title,
                style = tokens.label,
                color = if (found.known) tokens.muted else tokens.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (account.subtitle.isNotEmpty()) {
                Text(
                    text = account.subtitle,
                    style = tokens.footnote,
                    color = tokens.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (found.known) Text(text = strings.alreadyHere, style = tokens.footnote, color = tokens.muted)
        }
        if (!found.known) {
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, label = account.title)
        }
    }
}

// -------------------------------------------------------------------- details

@Composable
private fun DetailsPage(instance: TwoFaInstance, strings: TwoFaStrings, id: String) {
    val tokens = AppTheme
    val accounts by instance.accounts.collectAsState()
    val now by instance.now.collectAsState()
    val account = accounts.firstOrNull { it.id == id } ?: return
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val copiedId by instance.copiedId.collectAsState()

    var showSecret by remember(id) { mutableStateOf(false) }
    var confirmDelete by remember(id) { mutableStateOf(false) }
    var issuer by remember(id) { mutableStateOf(account.issuer) }
    var name by remember(id) { mutableStateOf(account.name) }

    val left = Totp.secondsLeft(account, now)
    val expiring = left <= EXPIRING_SECONDS

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("2fa:details-page"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(tokens.radius))
                .background(tokens.background)
                .clickable(role = Role.Button) {
                    val code = instance.copy(account)
                    runCatching { clipboard.setText(AnnotatedString(code)) }
                }
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = account.title, style = tokens.subtitle, color = tokens.foreground)
            if (account.subtitle.isNotEmpty()) {
                Text(text = account.subtitle, style = tokens.body, color = tokens.muted)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = Totp.grouped(Totp.code(account, now)),
                style = codeStyle(48.sp),
                color = if (expiring) tokens.danger else tokens.foreground,
                modifier = Modifier.testTag("2fa:detail-code"),
            )
            Spacer(Modifier.height(8.dp))
            Countdown(left, account.period, expiring, strings)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (copiedId == account.id) strings.copied else strings.tapToCopy,
                style = tokens.footnote,
                color = if (copiedId == account.id) tokens.success else tokens.muted,
            )
        }

        Spacer(Modifier.height(16.dp))
        Card {
            InfoRow(strings.parameters, strings.parametersLine(account))
            RowDivider()
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(text = strings.secret, style = tokens.footnote, color = tokens.muted)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (showSecret) account.secret.chunked(4).joinToString(" ") else "•••• •••• •••• ••••",
                    style = tokens.body.copy(fontFeatureSettings = "tnum", letterSpacing = 1.sp),
                    color = tokens.foreground,
                    modifier = Modifier.testTag("2fa:secret-value"),
                )
                Spacer(Modifier.height(8.dp))
                AppButton(
                    text = if (showSecret) strings.hideSecret else strings.showSecret,
                    onClick = { showSecret = !showSecret },
                    size = ButtonSize.Sm,
                    variant = ButtonVariant.Secondary,
                    modifier = Modifier.testTag("2fa:toggle-secret"),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Card {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                FieldLabel(strings.issuer)
                AppTextField(
                    value = issuer,
                    onValueChange = { issuer = it },
                    label = strings.issuer,
                    modifier = Modifier.testTag("2fa:rename-issuer"),
                )
                Spacer(Modifier.height(12.dp))
                FieldLabel(strings.accountName)
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = strings.accountName,
                    modifier = Modifier.testTag("2fa:rename-name"),
                )
                Spacer(Modifier.height(12.dp))
                AppButton(
                    text = strings.rename,
                    onClick = { instance.rename(account.id, issuer, name) },
                    enabled = (issuer.trim() != account.issuer || name.trim() != account.name) &&
                        (issuer.isNotBlank() || name.isNotBlank()),
                    size = ButtonSize.Default,
                    modifier = Modifier.testTag("2fa:rename"),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        if (!confirmDelete) {
            AppButton(
                text = strings.delete,
                onClick = { confirmDelete = true },
                variant = ButtonVariant.Destructive,
                modifier = Modifier.fillMaxWidth().testTag("2fa:delete"),
            )
        } else {
            Card {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(text = strings.deleteConfirm, style = tokens.body, color = tokens.foreground)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton(
                            text = strings.deleteForever,
                            onClick = { instance.delete(account.id) },
                            variant = ButtonVariant.Destructive,
                            size = ButtonSize.Default,
                            modifier = Modifier.testTag("2fa:delete-confirm"),
                        )
                        AppButton(
                            text = strings.cancel,
                            onClick = { confirmDelete = false },
                            variant = ButtonVariant.Ghost,
                            size = ButtonSize.Default,
                            modifier = Modifier.testTag("2fa:delete-cancel"),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        AppButton(
            text = strings.back,
            onClick = { instance.back() },
            variant = ButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth().testTag("2fa:back"),
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppTheme.radius))
            .background(AppTheme.background),
    ) { content() }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(text = label, style = AppTheme.footnote, color = AppTheme.muted)
        Spacer(Modifier.height(4.dp))
        Text(text = value, style = AppTheme.body, color = AppTheme.foreground)
    }
}
