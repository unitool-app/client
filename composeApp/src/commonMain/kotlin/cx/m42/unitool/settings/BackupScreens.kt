package cx.m42.unitool.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cx.m42.services.backup.AgeBackupCipher
import cx.m42.services.backup.GitHubKeys
import cx.m42.superizer.HostScreen
import cx.m42.superizer.HostSection
import cx.m42.superizer.Superizer
import cx.m42.superizer.app.AppId
import cx.m42.superizer.backup.BackupOutcome
import cx.m42.superizer.backup.BackupPreview
import cx.m42.superizer.backup.RestorePlan
import cx.m42.superizer.backup.RestoreReport
import cx.m42.superizer.diagnostics.Subject
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppTextField
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.components.RowDivider
import cx.m42.superizer.ui.components.Section
import cx.m42.superizer.ui.components.ToggleRow
import cx.m42.unitool.i18n.SecurityStrings
import cx.m42.unitool.i18n.isoDate
import cx.m42.unitool.i18n.securityStrings
import cx.m42.unitool.security.FindingRow
import kotlinx.coroutines.launch

/*
 * Backup and restore (05 §3.1, §3.4): every app's data in one file, encrypted to the keys a GitHub
 * account publishes, and back on another device. Keys are never in it (D170) — only their names, so
 * the next device can say which to import again.
 */

/** The line in Settings: when the last backup was, in the catalogue's words, and the way in. */
internal class BackupSection(private val tools: HostTools) : HostSection {
    override val id: String = "backup"

    override fun title(langTag: String): String = securityStrings(langTag).backup

    @Composable
    override fun Content(superizer: Superizer, open: (HostScreen) -> Unit) {
        val langTag by superizer.settings.langTag.collectAsState()
        val strings = securityStrings(langTag)
        val report by superizer.storage.report.collectAsState()
        RowText(strings.backupHint, color = AppTheme.muted, small = true)
        report.findings.firstOrNull { it.subject == Subject.Backup }?.let {
            RowDivider()
            FindingRow(it)
        }
        RowDivider()
        ActionRow(strings.open, "backup:open") { open(BackupScreen(tools)) }
    }
}

internal class BackupScreen(private val tools: HostTools) : HostScreen {
    override val id: String = "backup"

    override fun title(langTag: String): String = securityStrings(langTag).backup

    @Composable
    override fun Content(superizer: Superizer, back: () -> Unit, open: (HostScreen) -> Unit) {
        val langTag by superizer.settings.langTag.collectAsState()
        val strings = securityStrings(langTag)
        val scope = rememberCoroutineScope()
        var preview by remember { mutableStateOf<BackupPreview?>(null) }
        var login by remember { mutableStateOf("") }
        var fetched by remember { mutableStateOf<GitHubKeys.Fetched?>(null) }
        var busy by remember { mutableStateOf(false) }
        var said by remember { mutableStateOf<Said?>(null) }
        var plan by remember { mutableStateOf<RestorePlan?>(null) }
        val chosen = remember { mutableStateMapOf<String, Boolean>() }
        var withSettings by remember { mutableStateOf(true) }
        var restored by remember { mutableStateOf<RestoreReport?>(null) }

        LaunchedEffect(Unit) { preview = superizer.backup.preview() }

        Page("backup:screen") {
            // ------------------------------------------------------------ make
            Section(title = strings.makeBackup, hint = strings.backupHint) {
                preview?.let { p ->
                    val included = p.apps.filter { !it.excluded && (it.storageKeys + it.secretKeys) > 0 }
                    RowText(
                        strings.backupIncludes + ": " + included.joinToString(", ") { appTitle(superizer, AppId(it.id), langTag) }.ifEmpty { "—" },
                        Modifier.testTag("backup:includes"),
                        small = true,
                    )
                    RowDivider()
                    RowText(strings.backupExcludes, color = AppTheme.muted, small = true)
                }
            }
            FieldLabel(strings.githubLogin)
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                AppTextField(
                    value = login,
                    onValueChange = { login = it; fetched = null },
                    placeholder = "octocat",
                    label = strings.githubLogin,
                    modifier = Modifier.testTag("backup:github"),
                )
            }
            PageButton(strings.findKeys, "backup:find-keys", enabled = !busy && GitHubKeys.login(login) != null && tools.network != null) {
                val name = GitHubKeys.login(login) ?: return@PageButton
                val network = tools.network ?: return@PageButton
                scope.launch {
                    busy = true
                    fetched = try {
                        GitHubKeys.fetch(network, name)
                    } finally {
                        busy = false
                    }
                }
            }
            when (val f = fetched) {
                null -> Unit
                is GitHubKeys.Fetched.Failed -> Note(strings.githubProblem(f.problem), color = AppTheme.danger, tag = "backup:github-problem")
                is GitHubKeys.Fetched.Keys -> {
                    Section(title = strings.recipients) {
                        f.keys.forEachIndexed { i, r ->
                            if (i > 0) RowDivider()
                            RowText(
                                "${r.key.label} · ${r.key.fingerprint}" + if (r.usable) "" else " — " + strings.cannotEncryptTo,
                                Modifier.testTag("backup:key-$i"),
                                color = if (r.usable) AppTheme.foreground else AppTheme.muted,
                                small = true,
                            )
                        }
                    }
                    PageButton(if (busy) strings.working else strings.makeBackup, "backup:make", enabled = !busy) {
                        scope.launch {
                            busy = true
                            said = null
                            try {
                                val lines = f.keys.filter { it.usable }.map { it.line }
                                said = when (val made = superizer.backup.export(lines, strings.backupReason)) {
                                    is BackupOutcome.Ok -> {
                                        val name = AgeBackupCipher.fileName(isoDate(superizer.backup.lastBackupAt.value ?: 0))
                                        val sharer = tools.sharer
                                        if (sharer != null) sharer.share(name, made.value.decodeToString(), AgeBackupCipher.MIME_TYPE, null)
                                        made.value.fill(0)
                                        superizer.storage.inspect()
                                        Said.Made
                                    }
                                    else -> made.said()
                                }
                            } finally {
                                busy = false
                            }
                        }
                    }
                }
            }
            said?.let { Note(it.text(strings), color = if (it == Said.Made) AppTheme.foreground else AppTheme.danger, tag = "backup:said") }

            // ------------------------------------------------------------ restore
            Section(title = strings.restore, hint = strings.restoreHint) {
                val p = plan
                if (p == null) {
                    if (tools.files != null) {
                        ActionRow(strings.restoreFile, "backup:pick") {
                            scope.launch {
                                val picked = tools.files.pick() ?: return@launch
                                said = null
                                restored = null
                                when (val read = superizer.backup.read(picked.bytes)) {
                                    is BackupOutcome.Ok -> {
                                        plan = read.value
                                        chosen.clear()
                                        read.value.apps.forEach { chosen[it.id] = true }
                                    }
                                    else -> said = read.said()
                                }
                                picked.bytes.fill(0)
                            }
                        }
                    }
                } else {
                    p.apps.forEach { app ->
                        ToggleRow(
                            label = appTitle(superizer, AppId(app.id), langTag) + " · " + strings.entries(app.storageKeys + app.secretKeys),
                            checked = chosen[app.id] == true,
                            onCheckedChange = { chosen[app.id] = it },
                            modifier = Modifier.testTag("backup:app-${app.id}"),
                        )
                        RowDivider()
                    }
                    ToggleRow(strings.restoreSettings, withSettings, { withSettings = it }, Modifier.testTag("backup:settings"))
                }
            }
            plan?.let { p ->
                PageButton(if (busy) strings.working else strings.restoreButton, "backup:restore", enabled = !busy && chosen.values.any { it }) {
                    scope.launch {
                        busy = true
                        try {
                            when (val done = superizer.backup.restore(p, chosen.filterValues { it }.keys, withSettings, strings.restoreReason)) {
                                is BackupOutcome.Ok -> {
                                    restored = done.value
                                    plan = null
                                    superizer.storage.inspect()
                                }
                                else -> said = done.said()
                            }
                        } finally {
                            busy = false
                        }
                    }
                }
                PageButton(strings.cancel, "backup:restore-cancel", variant = ButtonVariant.Outline) { plan = null }
            }
            restored?.let { report ->
                Note(strings.restoreDone(report.restored.size), tag = "backup:restored")
                if (report.keysToImport.isNotEmpty()) {
                    Section(title = strings.keysToImport) {
                        report.keysToImport.forEachIndexed { i, key ->
                            if (i > 0) RowDivider()
                            RowText("${key.comment} · ${key.fingerprint}", Modifier.testTag("backup:import-again-$i"), small = true)
                        }
                    }
                }
            }
        }
    }
}

/** What the screen says after a try; the library's outcome, in the host's words. */
private enum class Said {
    Made, Cancelled, Unsupported, NoRecipients, SecretsLocked, Unreadable, Failed;

    fun text(strings: SecurityStrings): String = when (this) {
        Made -> strings.backupMade
        Cancelled -> strings.cancelledText
        Unsupported -> strings.backupFailed("unsupported")
        NoRecipients -> strings.githubProblem(GitHubKeys.Problem.NoUsableKeys)
        SecretsLocked -> strings.secretsLocked
        Unreadable -> strings.backupUnreadable
        Failed -> strings.backupFailed("failed")
    }
}

private fun BackupOutcome<*>.said(): Said = when (this) {
    is BackupOutcome.Ok -> Said.Made
    BackupOutcome.Cancelled -> Said.Cancelled
    BackupOutcome.Unsupported -> Said.Unsupported
    BackupOutcome.NoRecipients -> Said.NoRecipients
    BackupOutcome.SecretsLocked -> Said.SecretsLocked
    is BackupOutcome.Unreadable -> Said.Unreadable
    is BackupOutcome.Failed -> Said.Failed
}
