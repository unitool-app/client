package cx.m42.unitool.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.services.ssh.diagnostics.SkipReason
import cx.m42.superizer.HomeBanner
import cx.m42.superizer.HostScreen
import cx.m42.superizer.HostSection
import cx.m42.superizer.Superizer
import cx.m42.superizer.app.AppId
import cx.m42.superizer.diagnostics.DiagnosticsAction
import cx.m42.superizer.diagnostics.Finding
import cx.m42.superizer.diagnostics.Status
import cx.m42.superizer.diagnostics.Subject
import cx.m42.superizer.ssh.KeyId
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppButton
import cx.m42.superizer.ui.components.ButtonSize
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.components.RowDivider
import cx.m42.superizer.ui.components.Section
import cx.m42.superizer.ui.i18n.LocalDiagnosticsStrings
import cx.m42.unitool.i18n.SecurityStrings
import cx.m42.unitool.i18n.securityStrings
import cx.m42.unitool.security.FindingRow
import cx.m42.unitool.security.fill
import cx.m42.unitool.security.statusColor
import kotlinx.coroutines.launch

/*
 * Storage & security (06 §7): how this device keeps secrets and keys, in the catalogue's words and
 * nothing else (D179) — a line in Settings, a screen behind it, and a line on Home while the answer
 * is worse than the listing promises.
 */

/** The line in Settings: the worst finding, and the way in. */
internal class StorageSecuritySection(private val keys: KeysUi?, private val tools: HostTools) : HostSection {
    override val id: String = "storage"

    override fun title(langTag: String): String = securityStrings(langTag).storageSecurity

    @Composable
    override fun Content(superizer: Superizer, open: (HostScreen) -> Unit) {
        val langTag by superizer.settings.langTag.collectAsState()
        val strings = securityStrings(langTag)
        val report by superizer.storage.report.collectAsState()
        RowText(strings.storageSecurityHint, color = AppTheme.muted, small = true)
        report.findings.firstOrNull()?.let { worst ->
            RowDivider()
            FindingRow(worst)
        }
        RowDivider()
        ActionRow(strings.open, "storage:open") { open(StorageSecurityScreen(keys, tools)) }
    }
}

/** One line above the tiles while the worst finding is Unsafe or worse; it goes when that is fixed (06 §7). */
internal class DiagnosticsBanner(private val keys: KeysUi?, private val tools: HostTools) : HomeBanner {
    @Composable
    override fun Content(superizer: Superizer, open: (HostScreen) -> Unit): Boolean {
        val report by superizer.storage.report.collectAsState()
        val worst = report.findings.firstOrNull()?.takeIf { it.status >= Status.Unsafe } ?: return false
        val words = LocalDiagnosticsStrings.current
        val tokens = AppTheme
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(tokens.radius))
                .background(tokens.dangerSubtle)
                .clickable(role = Role.Button) { open(StorageSecurityScreen(keys, tools)) }
                .testTag("home:storage-banner")
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(words.status(worst.status), style = tokens.label, color = tokens.danger)
            Spacer(Modifier.height(2.dp))
            Text(words.title(worst.code).fill(worst.facts), style = tokens.body, color = tokens.foreground)
        }
        return true
    }
}

/**
 * Everything (06 §7): the findings by what they are about, each with what can be done about it; the
 * facts behind them; the last self-test step by step; and the buttons to run it and send the report.
 */
internal class StorageSecurityScreen(private val keys: KeysUi?, private val tools: HostTools) : HostScreen {
    override val id: String = "storage"

    override fun title(langTag: String): String = securityStrings(langTag).storageSecurity

    @Composable
    override fun Content(superizer: Superizer, back: () -> Unit, open: (HostScreen) -> Unit) {
        val langTag by superizer.settings.langTag.collectAsState()
        val strings = securityStrings(langTag)
        val words = LocalDiagnosticsStrings.current
        val report by superizer.storage.report.collectAsState()
        val scope = rememberCoroutineScope()
        val clipboard = LocalClipboardManager.current
        var running by remember { mutableStateOf(false) }
        var copied by remember { mutableStateOf(false) }
        val keyNames = keys?.admin?.keys?.collectAsState()?.value.orEmpty().associate { it.id.fingerprint to it.comment }

        // What is on screen is what is true now, not what was true at start.
        LaunchedEffect(Unit) { superizer.storage.inspect() }

        val runSelfTest: () -> Unit = {
            scope.launch {
                running = true
                try {
                    superizer.storage.selfTest(withConfirmation = true)
                } finally {
                    running = false
                }
            }
        }
        val shareReport: () -> Unit = {
            val text = superizer.storage.exportText()
            val sharer = tools.sharer
            if (sharer != null) {
                scope.launch { sharer.share(REPORT_FILE, text, "text/plain", null) }
            } else {
                clipboard.setText(AnnotatedString(text))
                copied = true
            }
        }
        val act: (Finding, DiagnosticsAction) -> Unit = { finding, action ->
            when (action) {
                DiagnosticsAction.AddSshKey, DiagnosticsAction.ImportKeyAgain -> keys?.let { open(ImportKeyScreen(it, tools)) }
                DiagnosticsAction.SetPassphrase, DiagnosticsAction.Rekey -> {
                    val key = (finding.subject as? Subject.Key)?.id
                    if (keys != null && key != null) open(KeyCardScreen(keys, tools, KeyId(key)))
                }
                DiagnosticsAction.EnableScreenLock -> superizer.lock.openDeviceSettings()
                DiagnosticsAction.RunSelfTest -> runSelfTest()
                DiagnosticsAction.ShareReport -> shareReport()
                DiagnosticsAction.MakeBackup -> open(BackupScreen(tools))
            }
        }

        Page("storage:screen") {
            val worst = report.findings.firstOrNull()
            Card {
                Column(modifier = Modifier.padding(16.dp).semantics(mergeDescendants = true) {}.testTag("storage:summary")) {
                    val status = worst?.status ?: Status.Ok
                    Text(words.status(status), style = AppTheme.header, color = statusColor(status))
                    if (worst != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(words.title(worst.code).fill(worst.facts), style = AppTheme.body, color = AppTheme.foreground)
                    }
                }
            }

            groups(report.findings).forEach { (group, findings) ->
                Section(title = group.title(strings)) {
                    findings.forEachIndexed { i, finding ->
                        if (i > 0) RowDivider()
                        // A key's or an app's name once over its findings, not over each of them.
                        val named = i == 0 || findings[i - 1].subject != finding.subject
                        about(finding.subject, superizer, langTag, keyNames)?.takeIf { named }?.let {
                            Text(
                                it,
                                style = AppTheme.label,
                                color = AppTheme.foreground,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                            )
                        }
                        FindingRow(finding)
                        val actions = finding.actions.filter { it.available(keys) }
                        if (actions.isNotEmpty()) {
                            Row(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                                actions.forEachIndexed { j, action ->
                                    if (j > 0) Spacer(Modifier.width(8.dp))
                                    AppButton(
                                        words.action(action),
                                        { act(finding, action) },
                                        variant = ButtonVariant.Outline,
                                        size = ButtonSize.Sm,
                                        modifier = Modifier.testTag("storage:action-${finding.code}-${action.name}"),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // The self-test's finding heads its own block, over the steps it is about.
            Section(title = strings.selfTest) {
                report.findings.filter { it.subject == Subject.SelfTest }.forEach { FindingRow(it) }
                val result = report.selfTest
                if (result == null) {
                    RowText(strings.selfTestNever, color = AppTheme.muted)
                } else {
                    RowDivider()
                    RowText(strings.lastRun(isoMinute(result.at)), color = AppTheme.muted, small = true)
                    result.steps.forEach { step ->
                        RowDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("storage:step-${step.code}"),
                            verticalAlignment = Alignment.Top,
                        ) {
                            val (mark, color) = when {
                                !step.ran -> "–" to AppTheme.muted
                                step.passed -> "✓" to AppTheme.foreground
                                else -> "✗" to AppTheme.danger
                            }
                            Text(mark, style = AppTheme.body, color = color, modifier = Modifier.width(24.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(words.title(step.code), style = AppTheme.body, color = if (step.ran) AppTheme.foreground else AppTheme.muted)
                                val under = when {
                                    // Why it did not run, where a person can do something about it (SkipReason).
                                    !step.ran -> when (step.evidence) {
                                        SkipReason.NEEDS_CONFIRMATION -> strings.notRunNeedsConfirmation
                                        SkipReason.NO_SCREEN_LOCK -> strings.notRunNoScreenLock
                                        SkipReason.AFTER_FAILURE -> strings.notRunAfterFailure
                                        else -> strings.notRunHere
                                    }
                                    !step.passed -> step.evidence
                                    else -> null
                                }
                                under?.let { Text(it, style = AppTheme.footnote, color = AppTheme.muted) }
                            }
                        }
                    }
                }
            }

            PageButton(if (running) strings.runningSelfTest else strings.runSelfTest, "storage:self-test", enabled = !running) { runSelfTest() }
            PageButton(
                text = if (tools.sharer != null) strings.shareReport else if (copied) strings.copied else strings.copyReport,
                tag = "storage:share-report",
                variant = ButtonVariant.Secondary,
            ) { shareReport() }

            val facts = report.device.facts
            if (facts.isNotEmpty()) {
                Section(title = strings.howItWorks) {
                    facts.entries.forEachIndexed { i, (name, value) ->
                        if (i > 0) RowDivider()
                        RowText("$name: $value", mono = true, small = true)
                    }
                }
            }
        }
    }

    private companion object {
        const val REPORT_FILE = "unitool-storage-report.txt"
    }
}

/** The screen's blocks, in the order a person asks: my secrets, this device, my keys, each app, the backup. The self-test has its own. */
private enum class Group {
    Secrets, Device, Keys, Apps, Backup;

    fun title(strings: SecurityStrings): String = when (this) {
        Secrets -> strings.subjectSecrets
        Device -> strings.subjectDevice
        Keys -> strings.sshKeys
        Apps -> strings.subjectApps
        Backup -> strings.backup
    }
}

private fun groups(findings: List<Finding>): List<Pair<Group, List<Finding>>> =
    findings
        .mapNotNull { finding ->
            val group = when (finding.subject) {
                Subject.HostSecrets -> Group.Secrets
                Subject.Device -> Group.Device
                is Subject.Key -> Group.Keys
                is Subject.AppSecrets -> Group.Apps
                Subject.Backup -> Group.Backup
                // The self-test's block draws its own finding, over its steps.
                Subject.SelfTest -> null
            }
            group?.let { it to finding }
        }
        .groupBy({ it.first }, { it.second })
        // Within a block, one key's (or app's) findings together, the worst subject first.
        .mapValues { (_, list) -> list.groupBy { it.subject }.values.flatten() }
        .toList()
        .sortedBy { it.first.ordinal }

/** Which key or which app a finding is about; null where the block's title says it already. */
private fun about(subject: Subject, superizer: Superizer, langTag: String, keyNames: Map<String, String>): String? = when (subject) {
    is Subject.Key -> keyNames[subject.id]?.ifEmpty { null } ?: subject.id
    is Subject.AppSecrets -> appTitle(superizer, AppId(subject.appId), langTag)
    else -> null
}

/** An action has a button only where this host can do it. */
private fun DiagnosticsAction.available(keys: KeysUi?): Boolean = when (this) {
    DiagnosticsAction.AddSshKey, DiagnosticsAction.ImportKeyAgain, DiagnosticsAction.SetPassphrase, DiagnosticsAction.Rekey -> keys != null
    else -> true
}
