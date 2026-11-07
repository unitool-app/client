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
import cx.m42.services.backup.AgeBackupCipher
import cx.m42.services.ssh.Age
import cx.m42.services.ssh.KnownKey
import cx.m42.services.ssh.diagnostics.KeyFindings
import cx.m42.superizer.HostScreen
import cx.m42.superizer.Superizer
import cx.m42.superizer.diagnostics.Finding
import cx.m42.superizer.runtime.Platform
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * SSH keys (02 §5): every key with where it lives, a card per key, and the way to add one. The words
 * for *where* are the catalogue's (D179); everything else is [SecurityStrings].
 *
 * Not in Settings: only the promo code `{ssh-keys}` opens it (done/idea/09, the owner, 2026-10-05). Most
 * people never need a key of their own, and whoever does is given the code. What Storage & security
 * offers as the fix for a finding — "add an SSH key" where nothing seals the secrets — still leads
 * to the wizard: that is a problem on screen, not a way into this list.
 */
internal class SshKeysScreen(private val keys: KeysUi, private val tools: HostTools) : HostScreen {
    override val id: String = ID

    override fun title(langTag: String): String = securityStrings(langTag).sshKeys

    @Composable
    override fun Content(superizer: Superizer, back: () -> Unit, open: (HostScreen) -> Unit) {
        val langTag by superizer.settings.langTag.collectAsState()
        val strings = securityStrings(langTag)
        val list by keys.admin.keys.collectAsState()
        val anchor by keys.secretsKey.collectAsState()
        // Opened by its code: the website's SSH keys docs are this install's to read from now on.
        LaunchedEffect(Unit) { tools.opened?.add(ID) }

        Page("keys:screen") {
            Card {
                RowText(strings.sshKeysHint, color = AppTheme.muted, small = true)
                if (list.isEmpty()) {
                    RowDivider()
                    RowText(strings.noKeys, Modifier.testTag("keys:empty"), color = AppTheme.muted)
                }
                list.forEachIndexed { i, key ->
                    RowDivider()
                    KeyRow(key, KeyFindings.of(key, anchor, keys.inBrowser, keys.chipHere).first(), opensWith(key, keys, strings), "keys:row-$i") {
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
            keys.publicKeys?.let { KnownKeysCard(it, tools, strings, open) }
        }
    }

    companion object {
        /** What the promo table's `OpenHostScreen` names, and the test tag `host:ssh-keys`. */
        const val ID: String = "ssh-keys"
    }
}

/** A key in the list: its name, its kind and fingerprint, and where it lives in the catalogue's words. */
@Composable
private fun KeyRow(key: SshKeyInfo, level: Finding, opens: Pair<String, Boolean>?, tag: String, onClick: () -> Unit) {
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
        opens?.let { (text, weak) ->
            Text(text = text, style = tokens.footnote, color = if (weak) tokens.danger else tokens.muted, modifier = Modifier.testTag("$tag-opens"))
        }
    }
}

/**
 * Who opens a key in the chip, and whether that is weak — the screen lock alone. Null for a key
 * under its passphrase only, which the level already says.
 */
private fun opensWith(key: SshKeyInfo, keys: KeysUi, strings: SecurityStrings): Pair<String, Boolean>? {
    if (key.storage.level == 3) return null
    val device = keys.admin.device
    val weak = !key.biometricOnly && !key.passphraseKept
    return strings.opensWith(device.platform == Platform.Ios, key.biometricOnly, key.passphraseKept, device.strongBiometric) to weak
}

/**
 * One key (02 §5, 06 §7): where it lives and what that means, the public half to copy or send, which
 * apps may use it and what they did, and the actions — check that its lock opens it, change the
 * passphrase, send it to another device, seal the host's secrets with it, delete it.
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
        var checking by remember { mutableStateOf(false) }
        var checked by remember { mutableStateOf<SshOutcome<Boolean>?>(null) }
        val line = "${key.publicKey} ${key.comment}".trim()

        Page("key:card") {
            Section(title = strings.storedAt) {
                KeyFindings.of(key, anchor, keys.inBrowser, keys.chipHere).forEachIndexed { i, finding ->
                    if (i > 0) RowDivider()
                    FindingRow(finding)
                }
                opensWith(key, keys, strings)?.let { (text, weak) ->
                    RowDivider()
                    RowText(text, Modifier.testTag("key:opens"), small = true, color = if (weak) AppTheme.danger else AppTheme.muted)
                }
                RowDivider()
                RowText("${strings.policy}: ${strings.policyWords(key.policy).replaceFirstChar { it.lowercase() }}", small = true, color = AppTheme.muted)
            }

            Section(title = strings.publicKey) {
                RowText(line, Modifier.testTag("key:public"), mono = true)
                val buttons = buildList<Triple<String, String, () -> Unit>> {
                    add(Triple(if (copied) strings.copied else strings.copy, "key:copy") { clipboard.setText(AnnotatedString(line)); copied = true })
                    // For another phone to read and keep, to encrypt to this key (.ai-notes/public-keys).
                    add(Triple(strings.showQr, "key:qr") { open(PublicKeyQrScreen(key.comment, line, key.id.fingerprint)) })
                    tools.sharer?.let { sharer ->
                        add(Triple(strings.share, "key:share") { scope.launch { sharer.share(fileBase(key) + ".pub", line + "\n", "text/plain", null) } })
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

            if (key.usable) {
                // The key's own lock, asked even inside a session; nothing kept (.ai-notes/key-share).
                PageButton(if (checking) strings.working else strings.verifyKey, "key:verify", enabled = !checking, variant = ButtonVariant.Secondary) {
                    scope.launch {
                        checking = true
                        checked = null
                        checked = try {
                            keys.verify(keyId)
                        } finally {
                            checking = false
                        }
                    }
                }
                checked?.let { outcome ->
                    val (text, color) = when (outcome) {
                        SshOutcome.Ok(true) -> strings.verifyOk to AppTheme.foreground
                        SshOutcome.Ok(false) -> strings.verifyMismatch to AppTheme.danger
                        SshOutcome.Cancelled -> strings.cancelledText to AppTheme.muted
                        SshOutcome.Unusable -> strings.unusable to AppTheme.danger
                        else -> strings.verifyFailed to AppTheme.danger
                    }
                    Note(text, color = color, tag = "key:verify-said")
                }
            }
            if (key.storage.level == 3 && key.usable) {
                PageButton(strings.changePassphrase, "key:rekey", variant = ButtonVariant.Secondary) {
                    open(PassphraseScreen(keys, keyId))
                }
            }
            // A copy for another device of the person's (.ai-notes/key-share); level 1 never leaves the chip.
            if (key.storage.level >= 2 && key.usable && (tools.sharer != null || tools.saver != null)) {
                PageButton(strings.sendKey, "key:send", variant = ButtonVariant.Secondary) {
                    open(SendKeyScreen(keys, tools, keyId))
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

    private companion object {
        const val JOURNAL_LINES = 20
    }
}

/** A file name from the key's comment: `ivan@laptop` is `ivanlaptop`. */
private fun fileBase(key: SshKeyInfo): String = key.comment.filter { it.isLetterOrDigit() || it in "-_." }.ifEmpty { "key" }

/**
 * "Send to another device" (.ai-notes/key-share): a level-2 or level-3 key sealed with age to one of
 * the other devices' public keys, out through the share sheet, armored as the backup is. The keyring
 * asks first — level 2's device prompt, level 3's confirmation — and level 3's file goes as stored,
 * under its passphrase. Only the saved keys are offered: a line typed in here could be anybody's.
 */
internal class SendKeyScreen(private val keys: KeysUi, private val tools: HostTools, private val keyId: KeyId) : HostScreen {
    override val id: String = "key-send"

    override fun title(langTag: String): String = securityStrings(langTag).sendKey

    @Composable
    override fun Content(superizer: Superizer, back: () -> Unit, open: (HostScreen) -> Unit) {
        val langTag by superizer.settings.langTag.collectAsState()
        val strings = securityStrings(langTag)
        val list by keys.admin.keys.collectAsState()
        val key = list.firstOrNull { it.id == keyId }
        if (key == null) {
            LaunchedEffect(Unit) { back() }
            return
        }
        val book = keys.publicKeys
        val known by remember(book) { book?.keys ?: MutableStateFlow<List<KnownKey>>(emptyList()) }.collectAsState()
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        // What happened, and whether it is a problem.
        var said by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

        fun send(to: KnownKey) {
            if (busy) return
            scope.launch {
                busy = true
                said = null
                try {
                    said = when (val sealed = keys.export(keyId, to)) {
                        is SshOutcome.Ok -> {
                            val text = Age.armor(sealed.value)
                            sealed.value.fill(0)
                            val name = fileBase(key) + ".age"
                            val delivered = tools.sharer?.share(name, text, AgeBackupCipher.MIME_TYPE, null)
                                ?: tools.saver?.save(name, text.encodeToByteArray(), AgeBackupCipher.MIME_TYPE)
                                ?: false
                            if (delivered) strings.sent(to.name) to false else strings.sendFailed to true
                        }
                        SshOutcome.Cancelled -> strings.cancelledText to false
                        SshOutcome.Unusable -> strings.unusable to true
                        else -> strings.sendFailed to true
                    }
                } finally {
                    busy = false
                }
            }
        }

        Page("key:send") {
            Note(strings.sendKeyHint)
            Note(strings.sendKeyWarning, color = AppTheme.danger, tag = "key-send:warning")
            if (key.storage.level == 2 && !key.passphraseKept) {
                Note(strings.sendKeyNoPassphrase, color = AppTheme.danger, tag = "key-send:no-passphrase")
            }
            Section(title = strings.sendTo) {
                if (known.isEmpty()) RowText(strings.noRecipients, Modifier.testTag("key-send:empty"), color = AppTheme.muted)
                known.forEachIndexed { i, to ->
                    if (i > 0) RowDivider()
                    ActionRow(to.name, "key-send:to-$i", "${to.key.label} · ${shortFingerprint(to.id)}") { send(to) }
                }
                if (book != null) {
                    RowDivider()
                    ActionRow(strings.addPublicKey, "key-send:add") { open(AddPublicKeyScreen(book, tools)) }
                }
            }
            said?.let { (text, problem) -> Note(text, color = if (problem) AppTheme.danger else AppTheme.foreground, tag = "key-send:said") }
        }
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
