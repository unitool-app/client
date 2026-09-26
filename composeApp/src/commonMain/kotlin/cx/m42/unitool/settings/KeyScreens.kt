package cx.m42.unitool.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.services.ssh.diagnostics.KeyFindings
import cx.m42.superizer.HostScreen
import cx.m42.superizer.HostSection
import cx.m42.superizer.Superizer
import cx.m42.superizer.diagnostics.Finding
import cx.m42.superizer.ssh.KeyCapability
import cx.m42.superizer.ssh.KeyId
import cx.m42.superizer.ssh.MIN_PASSPHRASE
import cx.m42.superizer.ssh.SshKeyInfo
import cx.m42.superizer.ssh.SshOutcome
import cx.m42.superizer.ssh.UsePolicy
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
import cx.m42.unitool.security.SecretField
import cx.m42.unitool.security.fill
import cx.m42.unitool.security.statusColor
import kotlinx.coroutines.launch

/**
 * "SSH keys" in Settings (02 §5): every key with where it lives, a card per key, and the way to add
 * one. The words for *where* are the catalogue's (D179); everything else is [SecurityStrings].
 */
internal class SshKeysSection(private val keys: KeysUi, private val tools: HostTools) : HostSection {
    override val id: String = "ssh-keys"

    override fun title(langTag: String): String = securityStrings(langTag).sshKeys

    @Composable
    override fun Content(superizer: Superizer, open: (HostScreen) -> Unit) {
        val langTag by superizer.settings.langTag.collectAsState()
        val strings = securityStrings(langTag)
        val list by keys.admin.keys.collectAsState()
        val anchor by keys.secretsKey.collectAsState()

        RowText(strings.sshKeysHint, color = AppTheme.muted, small = true)
        if (list.isEmpty()) {
            RowDivider()
            RowText(strings.noKeys, Modifier.testTag("keys:empty"), color = AppTheme.muted)
        }
        list.forEachIndexed { i, key ->
            RowDivider()
            KeyRow(key, KeyFindings.of(key, anchor, keys.inBrowser).first(), "keys:row-$i") {
                open(KeyCardScreen(keys, tools, key.id))
            }
        }
        RowDivider()
        ActionRow(strings.addKey, "keys:add") { open(ImportKeyScreen(keys, tools)) }
        if (list.isNotEmpty()) {
            RowDivider()
            // `ssh-add -x` without a passphrase: every session and every opened secret, forgotten now.
            ActionRow(strings.lockKeys, "keys:lock-all") { keys.admin.lockAll() }
        }
    }
}

/** A key in the list: its name, its kind and fingerprint, and where it lives in the catalogue's words. */
@Composable
private fun KeyRow(key: SshKeyInfo, level: Finding, tag: String, onClick: () -> Unit) {
    val tokens = AppTheme
    val words = LocalDiagnosticsStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = key.comment.ifEmpty { key.label }, style = tokens.body, color = tokens.foreground)
        Text(text = "${key.label} · ${shortFingerprint(key.id)}", style = tokens.footnote, color = tokens.muted)
        Spacer(Modifier.height(2.dp))
        Text(text = words.title(level.code).fill(level.facts), style = tokens.footnote, color = statusColor(level.status))
    }
}

/**
 * One key (02 §5, 06 §7): where it lives and what that means, the public half to copy or send, which
 * apps may use it and what they did, and the actions — change the passphrase, seal the host's
 * secrets with it, delete it.
 */
internal class KeyCardScreen(private val keys: KeysUi, private val tools: HostTools, private val keyId: KeyId) : HostScreen {
    override val id: String = "key"

    override fun title(langTag: String): String =
        keys.admin.keys.value.firstOrNull { it.id == keyId }?.comment?.ifEmpty { null } ?: securityStrings(langTag).sshKeys

    @Composable
    override fun Content(superizer: Superizer, back: () -> Unit, open: (HostScreen) -> Unit) {
        val langTag by superizer.settings.langTag.collectAsState()
        val strings = securityStrings(langTag)
        val list by keys.admin.keys.collectAsState()
        val key = list.firstOrNull { it.id == keyId }
        if (key == null) {
            // Deleted from here or elsewhere: nothing left to show.
            LaunchedEffect(Unit) { back() }
            return
        }
        val anchor by keys.secretsKey.collectAsState()
        val grants by remember(keyId) { keys.admin.grants(keyId) }.collectAsState()
        val journal by remember(keyId) { keys.admin.journal(keyId) }.collectAsState()
        val scope = rememberCoroutineScope()
        val clipboard = LocalClipboardManager.current
        var copied by remember { mutableStateOf(false) }
        val line = "${key.publicKey} ${key.comment}".trim()

        Page("key:card") {
            Section(title = strings.storedAt) {
                KeyFindings.of(key, anchor, keys.inBrowser).forEachIndexed { i, finding ->
                    if (i > 0) RowDivider()
                    FindingRow(finding)
                }
                RowDivider()
                RowText("${strings.policy}: ${strings.policyWords(key.policy).replaceFirstChar { it.lowercase() }}", small = true, color = AppTheme.muted)
            }

            Section(title = strings.publicKey) {
                RowText(line, Modifier.testTag("key:public"), mono = true)
                val buttons = buildList<Triple<String, String, () -> Unit>> {
                    add(Triple(if (copied) strings.copied else strings.copy, "key:copy") { clipboard.setText(AnnotatedString(line)); copied = true })
                    tools.sharer?.let { sharer ->
                        add(Triple(strings.share, "key:share") { scope.launch { sharer.share(pubFileName(key), line + "\n", "text/plain", null) } })
                    }
                }
                SmallButtons(*buttons.toTypedArray())
            }

            Section(title = strings.grants) {
                if (grants.isEmpty()) RowText(strings.noGrants, color = AppTheme.muted)
                grants.forEachIndexed { i, grant ->
                    if (i > 0) RowDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(appTitle(superizer, grant.app, langTag), style = AppTheme.body, color = AppTheme.foreground)
                            Text(strings.purposeName(grant.purpose), style = AppTheme.footnote, color = AppTheme.muted)
                        }
                        Spacer(Modifier.width(8.dp))
                        AppButton(
                            strings.revoke,
                            { scope.launch { keys.admin.revoke(grant) } },
                            variant = ButtonVariant.Outline,
                            size = ButtonSize.Sm,
                            modifier = Modifier.testTag("key:revoke-$i"),
                        )
                    }
                }
            }

            Section(title = strings.journal) {
                if (journal.isEmpty()) RowText(strings.noJournal, color = AppTheme.muted)
                journal.sortedByDescending { it.at }.take(JOURNAL_LINES).forEachIndexed { i, record ->
                    if (i > 0) RowDivider()
                    val who = record.app?.let { appTitle(superizer, it, langTag) } ?: superizer.hostInfo.name
                    RowText(strings.journalLine(isoMinute(record.at), who, record), small = true)
                }
            }

            if (key.storage.level == 3 && key.usable) {
                PageButton(strings.changePassphrase, "key:rekey", variant = ButtonVariant.Secondary) {
                    open(PassphraseScreen(keys, keyId))
                }
            }
            if (keys.sealsToKey && KeyCapability.DecryptAge in key.can && key.usable) {
                if (anchor == keyId) {
                    Note(strings.usedForSecrets, tag = "key:anchor")
                } else {
                    PageButton(strings.useForSecrets, "key:seal-secrets", variant = ButtonVariant.Secondary) {
                        scope.launch {
                            keys.sealSecretsWith(keyId)
                            superizer.storage.inspect()
                        }
                    }
                }
            }
            if (anchor == keyId) Note(strings.deleteAnchor)
            PageButton(strings.delete, "key:delete", enabled = anchor != keyId, variant = ButtonVariant.Destructive) {
                scope.launch {
                    // The keyring asks the person first (D148); a no leaves everything as it was.
                    if (keys.admin.delete(keyId) is SshOutcome.Ok) superizer.storage.inspect()
                }
            }
        }
    }

    private fun pubFileName(key: SshKeyInfo): String {
        val base = key.comment.filter { it.isLetterOrDigit() || it in "-_." }.ifEmpty { "key" }
        return "$base.pub"
    }

    private companion object {
        const val JOURNAL_LINES = 20
    }
}

/** "Change passphrase" on a level-3 key (05 §2.4): `ssh-keygen -p`, the old one asked by the keyring's sheet. */
internal class PassphraseScreen(private val keys: KeysUi, private val keyId: KeyId) : HostScreen {
    override val id: String = "key-passphrase"

    override val secure: Boolean = true

    override fun title(langTag: String): String = securityStrings(langTag).changePassphrase

    @Composable
    override fun Content(superizer: Superizer, back: () -> Unit, open: (HostScreen) -> Unit) {
        val langTag by superizer.settings.langTag.collectAsState()
        val strings = securityStrings(langTag)
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        Page("key:passphrase") {
            NewPassphraseForm(
                strings = strings,
                title = null,
                body = strings.setPassphraseHint,
                note = strings.computerCopyNote,
                action = strings.changePassphrase,
                busy = busy,
                tag = "key:new",
            ) { chars ->
                scope.launch {
                    busy = true
                    failed = false
                    val outcome = try {
                        keys.admin.rekey(keyId, chars)
                    } finally {
                        busy = false
                    }
                    when (outcome) {
                        is SshOutcome.Ok -> back()
                        SshOutcome.Cancelled -> Unit
                        else -> failed = true
                    }
                }
            }
            if (failed) Note(strings.rekeyFailed, color = AppTheme.danger, tag = "key:rekey-failed")
        }
    }
}

/**
 * A new passphrase, twice (05 §2.4): a count of characters and nothing else — no meter pretending to
 * know a strong one — and the button only from [MIN_PASSPHRASE]. The text becomes a `CharArray` and
 * both fields are emptied the moment it is submitted.
 */
@Composable
internal fun NewPassphraseForm(
    strings: SecurityStrings,
    title: String?,
    body: String?,
    note: String?,
    action: String,
    busy: Boolean,
    tag: String,
    onSubmit: (CharArray) -> Unit,
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val tokens = AppTheme
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = tokens.header, color = tokens.foreground, modifier = Modifier.testTag("$tag-title"))
                Spacer(Modifier.height(6.dp))
            }
            if (body != null) {
                Text(body, style = tokens.body, color = tokens.muted)
                Spacer(Modifier.height(12.dp))
            }
            SecretField(first, { first = it }, strings.newPassphrase, Modifier.testTag("$tag-first"))
            Spacer(Modifier.height(4.dp))
            Text(
                text = strings.characters(first.length) + if (first.length < MIN_PASSPHRASE) " · " + strings.atLeast(MIN_PASSPHRASE) else "",
                style = tokens.footnote,
                color = if (first.isEmpty() || first.length >= MIN_PASSPHRASE) tokens.muted else tokens.danger,
                modifier = Modifier.testTag("$tag-count"),
            )
            Spacer(Modifier.height(8.dp))
            SecretField(second, { second = it }, strings.repeatPassphrase, Modifier.testTag("$tag-second"))
            if (second.isNotEmpty() && second != first) {
                Spacer(Modifier.height(4.dp))
                Text(strings.passphrasesDiffer, style = tokens.footnote, color = tokens.danger, modifier = Modifier.testTag("$tag-differ"))
            }
            Spacer(Modifier.height(12.dp))
            Text(strings.onlyLengthCounted, style = tokens.footnote, color = tokens.muted)
            if (note != null) {
                Spacer(Modifier.height(4.dp))
                Text(note, style = tokens.footnote, color = tokens.muted)
            }
        }
    }
    PageButton(
        text = if (busy) strings.working else action,
        tag = "$tag-submit",
        enabled = !busy && first.length >= MIN_PASSPHRASE && first == second,
    ) {
        val chars = first.toCharArray()
        first = ""
        second = ""
        onSubmit(chars)
    }
}

/** How often the person is asked, in words (01 §5.3). */
internal fun SecurityStrings.policyWords(policy: UsePolicy): String = when (policy) {
    UsePolicy.EveryUse -> everyUse
    is UsePolicy.Session -> session((policy.seconds + 59) / 60)
}
