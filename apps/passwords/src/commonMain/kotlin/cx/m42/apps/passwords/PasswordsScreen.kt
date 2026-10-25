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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import cx.m42.services.ssh.KnownKey
import cx.m42.services.ssh.SshPublicKey
import cx.m42.superizer.diagnostics.DiagnosticsCode
import cx.m42.superizer.diagnostics.Finding
import cx.m42.superizer.diagnostics.Status
import cx.m42.superizer.diagnostics.Subject
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.AppTextField
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.components.RowDivider
import cx.m42.superizer.ui.components.Switch
import cx.m42.superizer.ui.i18n.LocalDiagnosticsStrings
import cx.m42.superizer.ui.platform.SystemBackHandler

/** A password field (06 §7): no suggestions over it and nothing learnt from it. */
private val SecretKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Password,
)

/** A login or an address: nothing capitalised, nothing corrected. */
private val PlainKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
)

/** Pasted CSV holds passwords, so it is a field nothing is learnt from too. */
private val CsvKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Uri,
)

private const val DOTS = "••••••••••••"

/** The list, the form, an entry's page, import and export — switched on [PasswordsInstance.page]. */
@Composable
internal fun PasswordsScreen(instance: PasswordsInstance) {
    val runtime = LocalAppRuntime.current
    val langTag by runtime.locale.langTag.collectAsState()
    val strings = remember(langTag) { runtime.locale.pick(PasswordsStringTables, PasswordsStringsEn) }
    val page by instance.page.collectAsState()
    val loaded by instance.loaded.collectAsState()

    SystemBackHandler(enabled = page != Page.List) { instance.back() }
    // The phone listens only while this screen is composed — and in front, which the instance knows (D538).
    DisposableEffect(instance) {
        instance.browser.screenShown(true)
        onDispose { instance.browser.screenShown(false) }
    }

    Box(modifier = Modifier.fillMaxSize().background(AppTheme.pageBackground)) {
        // The moment it takes the Keystore to open the list: blank, not "no passwords yet".
        if (!loaded) return@Box
        when (val current = page) {
            Page.List -> ListPage(instance, strings)
            Page.Add, is Page.Edit -> FormPage(instance, strings)
            is Page.Details -> DetailsPage(instance, strings, current.id)
            Page.Export -> ExportPage(instance, strings)
            Page.ImportFrom -> ImportFromPage(instance, strings)
            Page.Import -> ImportPage(instance, strings)
            Page.Computers -> ComputersPage(instance)
        }
        // Over any page: a computer asks, the sheet answers (README §5).
        RequestSheet(instance)
    }
}

// -------------------------------------------------------------------- list

@Composable
private fun ListPage(instance: PasswordsInstance, strings: PasswordsStrings) {
    val tokens = AppTheme
    val entries by instance.entries.collectAsState()
    val query by instance.query.collectAsState()
    val copied by instance.copied.collectAsState()
    val problem by instance.storageProblem.collectAsState()
    val clipboard = rememberSecretClipboard()
    val findings by LocalAppRuntime.current.diagnostics.findings.collectAsState()

    Column(modifier = Modifier.fillMaxSize().testTag("pw:list")) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Protection(findings)
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
                        .testTag("pw:storage-problem"),
                )
            }
            if (entries.isEmpty()) {
                if (problem?.blocksWriting != true) EmptyState(instance, strings)
            } else {
                AppTextField(
                    value = query,
                    onValueChange = { instance.query.value = it },
                    placeholder = strings.search,
                    keyboardOptions = PlainKeyboard,
                    modifier = Modifier.testTag("pw:search"),
                )
                Spacer(Modifier.height(12.dp))
                val visible = instance.visibleEntries()
                if (visible.isEmpty()) {
                    Text(
                        text = strings.nothingFound,
                        style = tokens.body,
                        color = tokens.muted,
                        modifier = Modifier.padding(8.dp).testTag("pw:nothing-found"),
                    )
                }
                Card {
                    visible.forEachIndexed { index, entry ->
                        if (index > 0) RowDivider()
                        EntryRow(
                            entry = entry,
                            index = index,
                            copied = copied == Copied(entry.id, Field.Password),
                            strings = strings,
                            onOpen = { instance.openDetails(entry) },
                            onCopy = { instance.copyTo(clipboard, entry, Field.Password) },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                // Below the list rather than beside Add: things done once in a while.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (problem?.blocksWriting != true) {
                        AppButton(
                            text = strings.import,
                            onClick = instance::startImport,
                            variant = ButtonVariant.Ghost,
                            modifier = Modifier.weight(1f).testTag("pw:import"),
                        )
                    }
                    AppButton(
                        text = strings.export,
                        onClick = instance::startExport,
                        variant = ButtonVariant.Ghost,
                        modifier = Modifier.weight(1f).testTag("pw:export"),
                    )
                }
                ComputersButton(instance)
            }
        }

        if (problem == StorageProblem.Locked) {
            AppButton(
                text = strings.unlock,
                onClick = instance::unlock,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("pw:unlock"),
            )
        }
        if (problem?.blocksWriting != true) {
            AppButton(
                text = strings.add,
                onClick = instance::startAdd,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("pw:add"),
            )
        }
    }
}

/** How the passwords are kept, in the host's words — 2FA's note, the same finding. */
@Composable
private fun Protection(findings: List<Finding>) {
    val finding = findings.firstOrNull { it.subject is Subject.AppSecrets && it.status >= Status.Missing }
        ?: findings.firstOrNull { it.subject == Subject.HostSecrets && it.code != DiagnosticsCode.VaultSessionOpen.id }
        ?: return
    val words = LocalDiagnosticsStrings.current
    val tokens = AppTheme
    val bad = finding.status >= Status.Unsafe
    val facts = finding.facts
    val title = Regex("\\{([a-z]+)\\}").replace(words.title(finding.code)) { facts[it.groupValues[1]] ?: it.value }
    val detail = if (bad) words.detail(finding.code, facts) else ""
    Text(
        text = listOf(title, detail).filter { it.isNotBlank() }.joinToString(" "),
        style = if (bad) tokens.body else tokens.footnote,
        color = if (bad) tokens.danger else tokens.muted,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("pw:protection"),
    )
}

@Composable
private fun EmptyState(instance: PasswordsInstance, strings: PasswordsStrings) {
    val tokens = AppTheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 8.dp, end = 8.dp).testTag("pw:empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(tokens.background),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "•••", style = tokens.subtitle, color = tokens.foreground)
        }
        Spacer(Modifier.height(20.dp))
        Text(text = strings.emptyTitle, style = tokens.header, color = tokens.foreground)
        Spacer(Modifier.height(8.dp))
        Text(text = strings.emptyHint, style = tokens.body, color = tokens.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        AppButton(
            text = strings.import,
            onClick = instance::startImport,
            variant = ButtonVariant.Secondary,
            modifier = Modifier.testTag("pw:import"),
        )
        ComputersButton(instance)
    }
}

@Composable
private fun EntryRow(
    entry: PasswordEntry,
    index: Int,
    copied: Boolean,
    strings: PasswordsStrings,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
) {
    val tokens = AppTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .testTag("pw:row-$index")
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.shownTitle,
                style = tokens.label,
                color = tokens.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("pw:title-$index"),
            )
            if (entry.subtitle.isNotEmpty()) {
                Text(text = entry.subtitle, style = tokens.footnote, color = tokens.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (copied) Text(text = strings.copied, style = tokens.footnote, color = tokens.success, modifier = Modifier.testTag("pw:copied"))
        }
        Spacer(Modifier.width(8.dp))
        AppButton(
            text = strings.copy,
            onClick = onCopy,
            size = ButtonSize.Sm,
            variant = ButtonVariant.Secondary,
            modifier = Modifier.testTag("pw:copy-$index"),
        )
    }
}

// -------------------------------------------------------------------- form

@Composable
private fun FormPage(instance: PasswordsInstance, strings: PasswordsStrings) {
    val tokens = AppTheme
    val draft by instance.draft.collectAsState()
    val error by instance.draftError.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("pw:form"),
    ) {
        FieldLabel(strings.title)
        AppTextField(
            value = draft.title,
            onValueChange = { v -> instance.editDraft { copy(title = v) } },
            placeholder = strings.titleHint,
            label = strings.title,
            modifier = Modifier.testTag("pw:title"),
        )
        Spacer(Modifier.height(12.dp))
        FieldLabel(strings.username)
        AppTextField(
            value = draft.username,
            onValueChange = { v -> instance.editDraft { copy(username = v) } },
            label = strings.username,
            keyboardOptions = PlainKeyboard,
            modifier = Modifier.testTag("pw:username"),
        )
        Spacer(Modifier.height(12.dp))
        FieldLabel(strings.password)
        // In the clear while typed: the window is kept out of screenshots, and a password nobody
        // can see while typing it is one typed wrong.
        AppTextField(
            value = draft.password,
            onValueChange = { v -> instance.editDraft { copy(password = v) } },
            label = strings.password,
            textStyle = tokens.body.copy(fontFeatureSettings = "tnum", letterSpacing = 1.sp),
            keyboardOptions = SecretKeyboard,
            modifier = Modifier.testTag("pw:password"),
        )
        Spacer(Modifier.height(8.dp))
        AppButton(
            text = strings.generate,
            onClick = instance::generate,
            size = ButtonSize.Sm,
            variant = ButtonVariant.Secondary,
            modifier = Modifier.testTag("pw:generate"),
        )
        Hint(strings.generateHint)
        Spacer(Modifier.height(12.dp))
        FieldLabel(strings.url)
        AppTextField(
            value = draft.url,
            onValueChange = { v -> instance.editDraft { copy(url = v) } },
            placeholder = "https://github.com",
            label = strings.url,
            keyboardOptions = PlainKeyboard.copy(keyboardType = KeyboardType.Uri),
            modifier = Modifier.testTag("pw:url"),
        )
        Spacer(Modifier.height(12.dp))
        FieldLabel(strings.note)
        AppTextField(
            value = draft.note,
            onValueChange = { v -> instance.editDraft { copy(note = v) } },
            label = strings.note,
            singleLine = false,
            minHeight = 80.dp,
            // Recovery codes end up here; nothing learnt from them either.
            keyboardOptions = PlainKeyboard,
            modifier = Modifier.testTag("pw:note"),
        )
        ErrorLine(error, strings)
        Spacer(Modifier.height(16.dp))
        AppButton(
            text = strings.save,
            onClick = { instance.save() },
            modifier = Modifier.fillMaxWidth().testTag("pw:save"),
        )
        Spacer(Modifier.height(8.dp))
        AppButton(
            text = strings.cancel,
            onClick = { instance.back() },
            variant = ButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth().testTag("pw:cancel"),
        )
    }
}

// -------------------------------------------------------------------- details

@Composable
private fun DetailsPage(instance: PasswordsInstance, strings: PasswordsStrings, id: String) {
    val tokens = AppTheme
    val entries by instance.entries.collectAsState()
    val entry = entries.firstOrNull { it.id == id } ?: return
    val clipboard = rememberSecretClipboard()
    val copied by instance.copied.collectAsState()
    val shown by instance.shownPassword.collectAsState()
    val showing = shown == entry.id
    var confirmDelete by remember(id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("pw:details"),
    ) {
        Card {
            if (entry.username.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { instance.copyTo(clipboard, entry, Field.Username) }
                        .testTag("pw:detail-username")
                        .padding(16.dp),
                ) {
                    Text(text = strings.username, style = tokens.footnote, color = tokens.muted)
                    Spacer(Modifier.height(4.dp))
                    Text(text = entry.username, style = tokens.body, color = tokens.foreground)
                    if (copied == Copied(entry.id, Field.Username)) {
                        Text(text = strings.copied, style = tokens.footnote, color = tokens.success)
                    }
                }
                RowDivider()
            }
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(text = strings.password, style = tokens.footnote, color = tokens.muted)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (showing) entry.password else DOTS,
                    style = tokens.body.copy(fontFeatureSettings = "tnum", letterSpacing = 1.sp),
                    color = tokens.foreground,
                    modifier = Modifier.testTag("pw:password-value"),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppButton(
                        text = strings.copy,
                        onClick = { instance.copyTo(clipboard, entry, Field.Password) },
                        size = ButtonSize.Sm,
                        modifier = Modifier.testTag("pw:copy-password"),
                    )
                    AppButton(
                        text = if (showing) strings.hidePassword else strings.showPassword,
                        // Showing asks the device first (06 §5.5); hiding does not.
                        onClick = { instance.togglePassword(entry) },
                        size = ButtonSize.Sm,
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.testTag("pw:toggle-password"),
                    )
                    if (copied == Copied(entry.id, Field.Password)) {
                        Text(text = strings.copied, style = tokens.footnote, color = tokens.success, modifier = Modifier.testTag("pw:copied"))
                    }
                }
            }
            if (entry.url.isNotEmpty()) {
                RowDivider()
                InfoRow(strings.url, entry.url)
            }
            if (entry.note.isNotEmpty()) {
                RowDivider()
                InfoRow(strings.note, entry.note)
            }
        }
        if (entry.changed > 0) {
            Hint(strings.changed(Export.isoDate(entry.changed)))
        }

        Spacer(Modifier.height(16.dp))
        AppButton(
            text = strings.edit,
            onClick = { instance.startEdit(entry) },
            variant = ButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth().testTag("pw:edit"),
        )
        Spacer(Modifier.height(8.dp))
        if (!confirmDelete) {
            AppButton(
                text = strings.delete,
                onClick = { confirmDelete = true },
                variant = ButtonVariant.Destructive,
                modifier = Modifier.fillMaxWidth().testTag("pw:delete"),
            )
        } else {
            Card {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(text = strings.deleteConfirm, style = tokens.body, color = tokens.foreground)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton(
                            text = strings.deleteForever,
                            onClick = { instance.delete(entry.id) },
                            variant = ButtonVariant.Destructive,
                            size = ButtonSize.Default,
                            modifier = Modifier.testTag("pw:delete-confirm"),
                        )
                        AppButton(
                            text = strings.cancel,
                            onClick = { confirmDelete = false },
                            variant = ButtonVariant.Ghost,
                            size = ButtonSize.Default,
                            modifier = Modifier.testTag("pw:delete-cancel"),
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
            modifier = Modifier.fillMaxWidth().testTag("pw:back"),
        )
    }
}

// -------------------------------------------------------------------- import

@Composable
private fun ImportFromPage(instance: PasswordsInstance, strings: PasswordsStrings) {
    val tokens = AppTheme
    val text by instance.importText.collectAsState()
    val error by instance.draftError.collectAsState()
    val scanning by instance.scanning.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("pw:import-from"),
    ) {
        Text(text = strings.importIntroText, style = tokens.body, color = tokens.muted)
        Spacer(Modifier.height(20.dp))
        if (instance.canImportFile) {
            val opening by instance.opening.collectAsState()
            val fileProblem by instance.fileProblem.collectAsState()
            AppButton(
                text = strings.importFile,
                onClick = instance::importFile,
                enabled = !opening,
                modifier = Modifier.fillMaxWidth().testTag("pw:import-file"),
            )
            Hint(strings.importFileHint)
            fileProblem?.let {
                Text(
                    text = strings.fileProblem(it),
                    style = tokens.body,
                    color = tokens.danger,
                    modifier = Modifier.padding(top = 8.dp).testTag("pw:file-problem"),
                )
            }
            Spacer(Modifier.height(20.dp))
        }
        if (instance.canScan) {
            AppButton(
                text = strings.scan,
                onClick = instance::scan,
                enabled = !scanning,
                variant = if (instance.canImportFile) ButtonVariant.Secondary else ButtonVariant.Default,
                modifier = Modifier.fillMaxWidth().testTag("pw:scan"),
            )
            Hint(strings.scanHint)
            Spacer(Modifier.height(20.dp))
        }
        FieldLabel(strings.orPaste)
        AppTextField(
            value = text,
            onValueChange = { instance.importText.value = it; instance.draftError.value = null },
            placeholder = "name,url,username,password,note",
            label = strings.orPaste,
            singleLine = false,
            minHeight = 96.dp,
            keyboardOptions = CsvKeyboard,
            modifier = Modifier.testTag("pw:paste"),
        )
        Hint(strings.pasteHint)
        ErrorLine(error, strings)
        Spacer(Modifier.height(12.dp))
        AppButton(
            text = strings.readText,
            onClick = { instance.readText() },
            enabled = text.isNotBlank(),
            variant = ButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth().testTag("pw:read"),
        )
        Spacer(Modifier.height(8.dp))
        AppButton(
            text = strings.cancel,
            onClick = { instance.back() },
            variant = ButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth().testTag("pw:import-from-cancel"),
        )
    }
}

@Composable
private fun ImportPage(instance: PasswordsInstance, strings: PasswordsStrings) {
    val tokens = AppTheme
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
            .testTag("pw:import-page"),
    ) {
        Text(
            text = strings.importIntro(batch.found.size, batch.addable.size),
            style = tokens.body,
            color = tokens.muted,
            modifier = Modifier.testTag("pw:import-intro"),
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
                modifier = Modifier.padding(top = 10.dp).testTag("pw:import-failed-$index"),
            )
        }
        if (instance.canScan) {
            Spacer(Modifier.height(20.dp))
            AppButton(
                text = strings.scanMore,
                onClick = instance::scan,
                enabled = !scanning,
                variant = ButtonVariant.Secondary,
                modifier = Modifier.fillMaxWidth().testTag("pw:import-scan"),
            )
            Hint(strings.scanMoreHint)
        }
        ErrorLine(error, strings)
        Spacer(Modifier.height(16.dp))
        if (batch.addable.isNotEmpty()) {
            AppButton(
                text = strings.importAdd(chosen),
                onClick = { instance.importEntries() },
                enabled = chosen > 0,
                modifier = Modifier.fillMaxWidth().testTag("pw:import-add"),
            )
            Spacer(Modifier.height(8.dp))
        }
        AppButton(
            text = strings.cancel,
            onClick = { instance.back() },
            variant = ButtonVariant.Ghost,
            modifier = Modifier.fillMaxWidth().testTag("pw:import-cancel"),
        )
    }
}

@Composable
private fun ImportRow(found: ImportBatch.Found, checked: Boolean, index: Int, strings: PasswordsStrings, onToggle: () -> Unit) {
    val tokens = AppTheme
    val entry = found.entry
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !found.known, role = Role.Switch, onClick = onToggle)
            .testTag("pw:import-row-$index")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.shownTitle,
                style = tokens.label,
                color = if (found.known) tokens.muted else tokens.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.subtitle.isNotEmpty()) {
                Text(text = entry.subtitle, style = tokens.footnote, color = tokens.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (found.known) Text(text = strings.alreadyHere, style = tokens.footnote, color = tokens.muted)
        }
        if (!found.known) {
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, label = entry.shownTitle)
        }
    }
}

// -------------------------------------------------------------------- export

@Composable
private fun ExportPage(instance: PasswordsInstance, strings: PasswordsStrings) {
    val tokens = AppTheme
    val entries by instance.entries.collectAsState()
    val login by instance.exportLogin.collectAsState()
    val keys by instance.exportKeys.collectAsState()
    val selected by instance.exportSelected.collectAsState()
    val saved by instance.savedKeys.collectAsState()
    val done by instance.exportDone.collectAsState()
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copiedCommand by remember { mutableStateOf<String?>(null) }
    val loaded = keys as? KeysState.Loaded

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("pw:export-page"),
    ) {
        Text(text = strings.exportIntro(entries.size), style = tokens.body, color = tokens.muted)
        Spacer(Modifier.height(20.dp))
        if (saved.isNotEmpty()) {
            FieldLabel(strings.savedKeys)
            Card {
                saved.forEachIndexed { index, key ->
                    if (index > 0) RowDivider()
                    SavedKeyRow(key, key.key.fingerprint in selected, index) { instance.toggleSaved(key) }
                }
            }
            Hint(strings.savedKeysHint)
            Spacer(Modifier.height(20.dp))
        }
        FieldLabel(strings.githubAccount)
        AppTextField(
            value = login,
            onValueChange = instance::editLogin,
            placeholder = "octocat",
            label = strings.githubAccount,
            keyboardOptions = PlainKeyboard,
            modifier = Modifier.testTag("pw:github"),
        )
        Hint(strings.githubHint)
        Spacer(Modifier.height(12.dp))
        AppButton(
            text = if (keys == KeysState.Loading) strings.findingKeys else strings.findKeys,
            onClick = instance::findKeys,
            enabled = login.isNotBlank() && keys != KeysState.Loading,
            variant = if (loaded != null) ButtonVariant.Secondary else ButtonVariant.Default,
            modifier = Modifier.fillMaxWidth().testTag("pw:find-keys"),
        )
        (keys as? KeysState.Failed)?.let {
            Text(
                text = strings.keysError(it.error),
                style = tokens.label,
                color = tokens.danger,
                modifier = Modifier.padding(top = 10.dp).testTag("pw:keys-error"),
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
        }

        if (loaded != null || saved.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            val recipients = remember(selected, saved, keys) { instance.exportRecipients() }
            val ready = recipients.isNotEmpty() && entries.isNotEmpty()
            if (instance.canShare) {
                AppButton(
                    text = strings.shareExport,
                    onClick = instance::share,
                    enabled = ready,
                    modifier = Modifier.fillMaxWidth().testTag("pw:share"),
                )
                Spacer(Modifier.height(8.dp))
            }
            AppButton(
                text = strings.copyExport,
                onClick = { instance.copyExport()?.let { text -> runCatching { clipboard.setText(AnnotatedString(text)) } } },
                enabled = ready,
                variant = if (instance.canShare) ButtonVariant.Secondary else ButtonVariant.Default,
                modifier = Modifier.fillMaxWidth().testTag("pw:copy-export"),
            )
            done?.let {
                Text(
                    text = strings.exportDone(it),
                    style = tokens.label,
                    color = tokens.success,
                    modifier = Modifier.padding(top = 10.dp).testTag("pw:export-done"),
                )
            }

            val fileName = instance.exportFileName()
            val commands = Export.commands(recipients, fileName)
            if (commands.isNotEmpty()) {
                val copy: (String) -> Unit = { command ->
                    copiedCommand = command
                    runCatching { clipboard.setText(AnnotatedString(command)) }
                }
                Spacer(Modifier.height(24.dp))
                FieldLabel(strings.decryptTitle)
                Commands(commands, "pw:command", copy)
                Hint(if (copiedCommand in commands) strings.commandCopied else strings.tapToCopyCommand)
                Spacer(Modifier.height(4.dp))
                Hint(strings.decryptHint)

                val plaintext = remember(entries) { Export.plaintext(entries) }
                val transfers = Export.transferCommands(recipients, fileName, plaintext)
                Spacer(Modifier.height(24.dp))
                FieldLabel(strings.transferTitle)
                Commands(transfers, "pw:transfer", copy)
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
            modifier = Modifier.fillMaxWidth().testTag("pw:export-back"),
        )
    }
}

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

@Composable
private fun KeyRow(key: GitHubKey, checked: Boolean, index: Int, strings: PasswordsStrings, onToggle: () -> Unit) {
    val tokens = AppTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = key.usable, role = Role.Switch, onClick = onToggle)
            .testTag("pw:key-$index")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = key.key.label, style = tokens.label, color = if (key.usable) tokens.foreground else tokens.muted)
            Text(text = key.key.fingerprint, style = tokens.footnote, color = tokens.muted)
            if (!key.usable) {
                Text(
                    text = if (key.key.type == SshPublicKey.RSA) strings.weakKey else strings.unsupportedKey,
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

@Composable
private fun SavedKeyRow(key: KnownKey, checked: Boolean, index: Int, onToggle: () -> Unit) {
    val tokens = AppTheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch, onClick = onToggle)
            .testTag("pw:saved-key-$index")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = key.name, style = tokens.label, color = tokens.foreground)
            Text(text = "${key.key.label} · ${key.key.fingerprint}", style = tokens.footnote, color = tokens.muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, label = key.name)
    }
}

// -------------------------------------------------------------------- bits

@Composable
internal fun FieldLabel(text: String) {
    Text(text = text, style = AppTheme.label, color = AppTheme.foreground, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
internal fun Hint(text: String) {
    Text(text = text, style = AppTheme.footnote, color = AppTheme.muted, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ErrorLine(error: DraftError?, strings: PasswordsStrings) {
    if (error == null) return
    Text(
        text = strings.error(error),
        style = AppTheme.label,
        color = AppTheme.danger,
        modifier = Modifier.padding(top = 10.dp).testTag("pw:error"),
    )
}

@Composable
internal fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppTheme.radius))
            .background(AppTheme.background),
    ) { content() }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(text = label, style = AppTheme.footnote, color = AppTheme.muted)
        Spacer(Modifier.height(4.dp))
        Text(text = value, style = AppTheme.body, color = AppTheme.foreground)
    }
}
