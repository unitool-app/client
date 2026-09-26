package cx.m42.unitool.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import cx.m42.services.ssh.diagnostics.KeyFindings
import cx.m42.superizer.HostScreen
import cx.m42.superizer.Superizer
import cx.m42.superizer.diagnostics.Subject
import cx.m42.superizer.ssh.GeneratedKind
import cx.m42.superizer.ssh.ImportOptions
import cx.m42.superizer.ssh.ImportOutcome
import cx.m42.superizer.ssh.ImportRefusal
import cx.m42.superizer.ssh.Inspection
import cx.m42.superizer.ssh.KeyringDevice
import cx.m42.superizer.ssh.MIN_PASSPHRASE
import cx.m42.superizer.ssh.SshKeyAdmin
import cx.m42.superizer.ssh.UsePolicy
import cx.m42.superizer.theme.AppTheme
import cx.m42.superizer.ui.components.AppTextField
import cx.m42.superizer.ui.components.ButtonVariant
import cx.m42.superizer.ui.components.ChoiceRow
import cx.m42.superizer.ui.components.RowDivider
import cx.m42.superizer.ui.components.Section
import cx.m42.superizer.ui.components.ToggleRow
import cx.m42.superizer.ui.platform.SystemBackHandler
import cx.m42.unitool.i18n.SecurityStrings
import cx.m42.unitool.i18n.securityStrings
import cx.m42.unitool.security.FindingRow
import cx.m42.unitool.security.SecretField
import kotlinx.coroutines.launch

/**
 * "Add a key" (02 §5, 05 §2.4): where the file comes from, what it is and where it would live —
 * before any passphrase — then the passphrase, how often to ask, and "Add". A file with no
 * passphrase or one shorter than twelve goes through "Set a passphrase" first (D167); "Create a
 * key" makes one here instead.
 *
 * Out of screenshots the whole way (T10, I13). The file's bytes stay in the wizard only while it is
 * open, each import is handed a copy, and leaving zeroes them (D155).
 */
internal class ImportKeyScreen(private val keys: KeysUi, private val tools: HostTools) : HostScreen {
    override val id: String = "key-import"

    override val secure: Boolean = true

    override fun title(langTag: String): String = securityStrings(langTag).addKey

    @Composable
    override fun Content(superizer: Superizer, back: () -> Unit, open: (HostScreen) -> Unit) {
        val langTag by superizer.settings.langTag.collectAsState()
        val strings = securityStrings(langTag)
        val wizard = remember { ImportWizard(keys.admin) }
        DisposableEffect(wizard) { onDispose { wizard.wipe() } }
        val scope = rememberCoroutineScope()
        val clipboard = LocalClipboardManager.current

        val done: suspend () -> Unit = {
            if (wizard.fromPaste) clearKeyFrom(clipboard)
            wizard.wipe()
            superizer.storage.inspect()
            back()
        }

        // Back inside the wizard goes a step back before it leaves.
        SystemBackHandler(enabled = wizard.step != Step.Source) { wizard.stepBack() }

        Page("import:${wizard.step.name.lowercase()}") {
            when (wizard.step) {
                Step.Source -> SourceStep(wizard, strings, tools) { source ->
                    scope.launch {
                        when (source) {
                            Source.File -> tools.files?.pick()?.let { wizard.take(it.bytes, pasted = false) }
                            Source.Qr -> tools.qr?.scan()?.let { wizard.take(it.encodeToByteArray(), pasted = false) }
                        }
                    }
                }
                Step.Paste -> PasteStep(wizard, strings)
                Step.Create -> CreateStep(wizard, strings, keys.admin.device) { scope.launch { wizard.create(null, done) } }
                Step.Inspect -> InspectStep(wizard, strings, keys.inBrowser) { chars -> scope.launch { wizard.add(chars, done) } }
                Step.NewPassphrase -> {
                    NewPassphraseForm(
                        strings = strings,
                        // "Too short" only where there was one; a file with none is asked to get one.
                        title = if (wizard.creating || wizard.shortLength == 0) strings.setPassphrase else strings.shortPassphrase,
                        body = if (wizard.creating) strings.setPassphraseHint else strings.shortPassphraseBody(wizard.shortLength),
                        note = if (wizard.creating) null else strings.computerCopyNote,
                        action = if (wizard.creating) strings.create else strings.add,
                        busy = wizard.busy,
                        tag = "import:new",
                    ) { chars ->
                        scope.launch { if (wizard.creating) wizard.create(chars, done) else wizard.submit(chars, done) }
                    }
                    Problem(wizard, strings)
                    PageButton(strings.back, "import:back", variant = ButtonVariant.Outline) { wizard.stepBack() }
                }
            }
        }
    }

    /** A key pasted in stays on the clipboard for anything that reads it; once it is in, take it off (04 step 6). */
    private fun clearKeyFrom(clipboard: ClipboardManager) {
        runCatching {
            if (clipboard.getText()?.text?.contains("PRIVATE KEY") == true) clipboard.setText(AnnotatedString(""))
        }
    }
}

private enum class Step { Source, Paste, Create, Inspect, NewPassphrase }

private enum class Source { File, Qr }

/** What went wrong at the last try, as the wizard shows it. */
private sealed interface Problem {
    data object WrongPassphrase : Problem
    data object AlreadyThere : Problem
    data object NoScreenLock : Problem
    data class Rejected(val refusal: ImportRefusal) : Problem
    data class Failed(val code: String) : Problem
}

/** The wizard's state, and the only holder of the file's bytes while it is open. */
private class ImportWizard(private val admin: SshKeyAdmin) {
    var step by mutableStateOf(Step.Source)
    var inspection by mutableStateOf<Inspection?>(null)
    var problem by mutableStateOf<Problem?>(null)
    var busy by mutableStateOf(false)
    var policy by mutableStateOf<UsePolicy>(UsePolicy.EveryUse)
    var keepPassphrase by mutableStateOf(false)
    var kind by mutableStateOf(GeneratedKind.Ed25519)
    var comment by mutableStateOf("")

    /** The "Set a passphrase" step is for a key being created, not a file being imported. */
    var creating by mutableStateOf(false)

    /** The length of the file's passphrase, 0 for none — what "too short" tells the person. */
    var shortLength by mutableStateOf(0)

    var fromPaste = false
        private set

    private var file: ByteArray? = null
    private var given: CharArray? = null

    fun take(bytes: ByteArray, pasted: Boolean) {
        wipe()
        file = bytes
        fromPaste = pasted
        inspection = admin.inspect(bytes)
        problem = null
        creating = false
        step = Step.Inspect
    }

    /** "Add" on the inspect step: straight in, or first a new passphrase (D167). */
    suspend fun add(passphrase: CharArray?, done: suspend () -> Unit) {
        val found = inspection ?: return
        given?.fill('\u0000')
        given = passphrase
        val length = if (found.encrypted) passphrase?.size ?: 0 else 0
        if (length < MIN_PASSPHRASE) {
            shortLength = length
            problem = null
            step = Step.NewPassphrase
            return
        }
        submit(null, done)
    }

    suspend fun submit(fresh: CharArray?, done: suspend () -> Unit) {
        val bytes = file ?: return
        val found = inspection ?: return
        busy = true
        problem = null
        val options = ImportOptions(policy = policy, keepPassphrase = keepPassphrase && found.wouldBe?.level == 2)
        val outcome = try {
            admin.import(bytes.copyOf(), given?.copyOf(), options, fresh)
        } finally {
            busy = false
        }
        settle(outcome, done)
    }

    suspend fun create(passphrase: CharArray?, done: suspend () -> Unit) {
        busy = true
        problem = null
        val outcome = try {
            admin.generate(kind, ImportOptions(policy = policy, comment = comment.trim().ifEmpty { null }), passphrase)
        } finally {
            busy = false
        }
        if (outcome is ImportOutcome.NeedsNewPassphrase) {
            creating = true
            step = Step.NewPassphrase
            return
        }
        settle(outcome, done)
    }

    private suspend fun settle(outcome: ImportOutcome, done: suspend () -> Unit) {
        when (outcome) {
            is ImportOutcome.Imported -> done()
            is ImportOutcome.AlreadyThere -> fail(Problem.AlreadyThere)
            ImportOutcome.WrongPassphrase -> {
                given?.fill('\u0000')
                given = null
                fail(Problem.WrongPassphrase)
            }
            is ImportOutcome.NeedsNewPassphrase -> {
                shortLength = outcome.length
                step = Step.NewPassphrase
            }
            is ImportOutcome.Rejected -> fail(Problem.Rejected(outcome.refusal))
            ImportOutcome.NoScreenLock -> fail(Problem.NoScreenLock)
            // The person closed the device's prompt: nothing happened, and nothing needs saying.
            ImportOutcome.Cancelled -> Unit
            is ImportOutcome.Failed -> fail(Problem.Failed(outcome.code))
        }
    }

    /** A problem is shown where it can be fixed: the passphrase on the inspect step, the rest where the person is. */
    private fun fail(problem: Problem) {
        this.problem = problem
        if (!creating) step = Step.Inspect
    }

    fun stepBack() {
        problem = null
        step = when (step) {
            Step.NewPassphrase -> if (creating) Step.Create else Step.Inspect
            else -> {
                wipe()
                inspection = null
                creating = false
                Step.Source
            }
        }
    }

    fun wipe() {
        file?.fill(0)
        file = null
        given?.fill('\u0000')
        given = null
    }
}

@Composable
private fun SourceStep(wizard: ImportWizard, strings: SecurityStrings, tools: HostTools, pick: (Source) -> Unit) {
    Card {
        // Each source only where the platform has it (D19): no row that could only ever fail.
        if (tools.files != null) {
            ActionRow(strings.importFile, "import:file", strings.keyFileHint) { pick(Source.File) }
            RowDivider()
        }
        ActionRow(strings.paste, "import:paste", strings.pasteHint) { wizard.step = Step.Paste }
        if (tools.qr != null) {
            RowDivider()
            ActionRow(strings.scanQr, "import:qr", strings.qrHint) { pick(Source.Qr) }
            RowText(QR_COMMAND, mono = true, small = true)
            RowText(strings.qrWarning, color = AppTheme.muted, small = true)
        }
        RowDivider()
        ActionRow(strings.createKey, "import:create", strings.createHint) {
            wizard.creating = true
            wizard.step = Step.Create
        }
    }
}

@Composable
private fun PasteStep(wizard: ImportWizard, strings: SecurityStrings) {
    var text by remember { mutableStateOf("") }
    Note(strings.pasteHint)
    Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
        AppTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----",
            label = strings.keyFile,
            singleLine = false,
            minHeight = 160.dp,
            textStyle = AppTheme.mono,
            keyboardOptions = PastedKeyKeyboard,
            modifier = Modifier.testTag("import:paste-field"),
        )
    }
    PageButton(strings.continueLabel, "import:paste-next", enabled = text.isNotBlank()) {
        val bytes = text.trim().encodeToByteArray()
        text = ""
        wizard.take(bytes, pasted = true)
    }
    PageButton(strings.back, "import:back", variant = ButtonVariant.Outline) { wizard.stepBack() }
}

@Composable
private fun CreateStep(wizard: ImportWizard, strings: SecurityStrings, device: KeyringDevice, create: () -> Unit) {
    Section(title = strings.createKey, hint = strings.createHint) {
        ChoiceRow(strings.keyKind(GeneratedKind.Ed25519), wizard.kind == GeneratedKind.Ed25519) { wizard.kind = GeneratedKind.Ed25519 }
        // Only a chip makes a P-256 key: it is the one kind born inside it on every platform (01 §4.4).
        if (1 in device.levels) {
            RowDivider()
            ChoiceRow(strings.keyKind(GeneratedKind.EcdsaP256), wizard.kind == GeneratedKind.EcdsaP256) { wizard.kind = GeneratedKind.EcdsaP256 }
        }
    }
    FieldLabel(strings.keyName)
    Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
        AppTextField(
            value = wizard.comment,
            onValueChange = { wizard.comment = it },
            placeholder = "unitool",
            label = strings.keyName,
            modifier = Modifier.testTag("import:name"),
        )
    }
    PolicyChoice(wizard, strings)
    Problem(wizard, strings)
    PageButton(if (wizard.busy) strings.working else strings.create, "import:create-key", enabled = !wizard.busy) { create() }
    PageButton(strings.back, "import:back", variant = ButtonVariant.Outline) { wizard.stepBack() }
}

@Composable
private fun InspectStep(wizard: ImportWizard, strings: SecurityStrings, inBrowser: Boolean, add: (CharArray?) -> Unit) {
    val found = wizard.inspection ?: return
    val tokens = AppTheme
    val refusal = found.refusal
    if (refusal != null) {
        Card {
            RowText(strings.refusal(refusal), Modifier.testTag("import:refusal"), color = tokens.danger)
            strings.refusalCommand(refusal)?.let { RowText(it, mono = true, small = true) }
        }
        PageButton(strings.back, "import:back", variant = ButtonVariant.Outline) { wizard.stepBack() }
        return
    }

    Card {
        Column(modifier = Modifier.padding(16.dp).semantics(mergeDescendants = true) {}.testTag("import:key")) {
            Text(found.comment.orEmpty().ifEmpty { found.label.orEmpty() }, style = tokens.body, color = tokens.foreground)
            Text("${found.label.orEmpty()} · ${found.id?.fingerprint.orEmpty()}", style = tokens.footnote, color = tokens.muted)
        }
        if (found.alreadyThere) {
            RowDivider()
            RowText(strings.alreadyThere, Modifier.testTag("import:already"), color = tokens.danger)
        }
    }
    if (found.alreadyThere) {
        PageButton(strings.back, "import:back", variant = ButtonVariant.Outline) { wizard.stepBack() }
        return
    }

    found.wouldBe?.let { storage ->
        Section(title = strings.wouldLive) {
            FindingRow(KeyFindings.placement(storage, Subject.Key(found.id?.fingerprint.orEmpty()), inBrowser))
        }
    }

    var passphrase by remember { mutableStateOf("") }
    if (found.encrypted) {
        FieldLabel(strings.passphraseOfFile)
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp)) {
            SecretField(passphrase, { passphrase = it }, strings.passphrase, Modifier.testTag("import:passphrase"))
        }
        Note(strings.characters(passphrase.length), tag = "import:count")
    } else {
        // Said before "Add", not after: the next step will ask for one (D167).
        Note(strings.shortPassphraseBody(0), tag = "import:unencrypted")
    }

    PolicyChoice(wizard, strings)
    if (found.encrypted && found.wouldBe?.level == 2) {
        Card {
            ToggleRow(
                label = strings.keepPassphrase,
                checked = wizard.keepPassphrase,
                onCheckedChange = { wizard.keepPassphrase = it },
                modifier = Modifier.testTag("import:keep-passphrase"),
            )
        }
    }
    Problem(wizard, strings)
    PageButton(
        text = if (wizard.busy) strings.importing else strings.add,
        tag = "import:add",
        enabled = !wizard.busy && (!found.encrypted || passphrase.isNotEmpty()),
    ) {
        val chars = if (found.encrypted) passphrase.toCharArray() else null
        passphrase = ""
        add(chars)
    }
    PageButton(strings.back, "import:back", variant = ButtonVariant.Outline) { wizard.stepBack() }
}

/** Every use (the default) or a session (01 §5.3), with what a session costs said next to it. */
@Composable
private fun PolicyChoice(wizard: ImportWizard, strings: SecurityStrings) {
    Section(title = strings.policy, hint = strings.sessionHint) {
        ChoiceRow(strings.everyUse, wizard.policy == UsePolicy.EveryUse) { wizard.policy = UsePolicy.EveryUse }
        RowDivider()
        ChoiceRow(strings.session(SESSION_SECONDS / 60), wizard.policy is UsePolicy.Session) { wizard.policy = UsePolicy.Session(SESSION_SECONDS) }
    }
}

@Composable
private fun Problem(wizard: ImportWizard, strings: SecurityStrings) {
    val text = when (val p = wizard.problem) {
        null -> return
        Problem.WrongPassphrase -> strings.wrongPassphrase
        Problem.AlreadyThere -> strings.alreadyThere
        Problem.NoScreenLock -> strings.noScreenLock
        is Problem.Rejected -> strings.refusal(p.refusal)
        is Problem.Failed -> "${strings.importFailed} (${p.code})"
    }
    Spacer(Modifier.height(4.dp))
    Note(text, color = AppTheme.danger, tag = "import:problem")
}

/** Five minutes: iOS allows no more for levels 1–2 (01 §5.3), and the same everywhere is simpler to explain. */
private const val SESSION_SECONDS = 300

/** What the computer runs to show a key as a QR code (02 §5) — the same tool the 2FA transfer uses. */
private const val QR_COMMAND = "qrencode -t ansiutf8 < ~/.ssh/id_ed25519; read _; clear"

/** A key is many lines of base64: no suggestions, nothing learnt, no capitals. */
private val PastedKeyKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Uri,
)
