package cx.m42.unitool.settings

import androidx.compose.foundation.layout.Box
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
import cx.m42.services.ssh.keyring.sealedBySameChip
import cx.m42.superizer.HostScreen
import cx.m42.superizer.Superizer
import cx.m42.superizer.diagnostics.DiagnosticsCode
import cx.m42.superizer.diagnostics.Subject
import cx.m42.superizer.diagnostics.finding
import cx.m42.superizer.runtime.Platform
import cx.m42.superizer.ssh.GeneratedKind
import cx.m42.superizer.ssh.ImportOptions
import cx.m42.superizer.ssh.ImportOutcome
import cx.m42.superizer.ssh.ImportRefusal
import cx.m42.superizer.ssh.Inspection
import cx.m42.superizer.ssh.KeyStorage
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
                    ProblemLine(wizard, strings)
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
    var kind by mutableStateOf(GeneratedKind.Ed25519)

    val device: KeyringDevice = admin.device

    /** The device has a chip, so where a key lives is the person's to choose (09, D237). */
    val chipHere: Boolean = device.levels.any { it < 3 }

    /** In the chip — the default (question 118) — or under the key's passphrase only. */
    var inChip by mutableStateOf(true)

    /** "Only my fingerprint" / "Only Face ID or Touch ID" is offered: a strong biometric is enrolled (D245, D246). */
    val offersBiometricOnly: Boolean = device.strongBiometric

    val onIphone: Boolean = device.platform == Platform.Ios

    /** Only a strong biometric opens the key, not the screen lock — the default where it is offered (D245, D246). */
    var biometricOnly by mutableStateOf(offersBiometricOnly)

    /** The passphrase as a second lock beside the biometric — the person's to add. */
    var passphraseChosen by mutableStateOf(false)

    /**
     * D247: a key in the chip has a lock of its own. Without the biometric, that is its passphrase,
     * and then the switch is not the person's to turn off.
     */
    val passphraseRequired: Boolean get() = !(offersBiometricOnly && biometricOnly)

    /** The key's passphrase asked for on every use: level 2 even where the chip could hold the key. */
    val keepPassphrase: Boolean get() = passphraseRequired || passphraseChosen

    /** The chip could take a key that would otherwise live at [best]. */
    fun chipFor(best: KeyStorage?): Boolean = chipHere && best != null && best.level < 3

    /** Where a key whose best is [best] goes with the choices as they stand. */
    fun chosen(best: KeyStorage): KeyStorage = when {
        !chipFor(best) || !inChip -> KeyStorage.SealedByPassphrase
        keepPassphrase -> best.sealedBySameChip() ?: best
        else -> best
    }

    /** The choices as the keyring takes them; [chipCan]: the chip could take this key at all. */
    fun options(chipCan: Boolean, comment: String? = null): ImportOptions {
        val chip = chipHere && chipCan && inChip
        return ImportOptions(
            policy = policy,
            comment = comment,
            level = when {
                !chipHere -> null
                !chip -> 3
                keepPassphrase -> 2
                else -> null
            },
            keepPassphrase = chip && keepPassphrase,
            biometricOnly = chip && offersBiometricOnly && biometricOnly,
        )
    }
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
        val options = options(chipFor(found.wouldBe))
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
            val name = comment.trim().ifEmpty { null }
            // P-256 is born in the chip or not at all: no level to choose, no passphrase to keep.
            // A P-256 born in the chip has no file for a passphrase: the biometric is its own lock (D247).
            val options = if (kind == GeneratedKind.EcdsaP256) {
                ImportOptions(policy = policy, comment = name, biometricOnly = true)
            } else {
                options(chipHere, name)
            }
            admin.generate(kind, options, passphrase)
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
        // With no file to keep a passphrase on, its own lock can only be a biometric (D247).
        if (1 in device.levels && wizard.offersBiometricOnly) {
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
    if (wizard.kind == GeneratedKind.EcdsaP256) {
        Section(title = strings.wouldLive) {
            FindingRow(KeyFindings.placement(bornInChip(device), Subject.Key(""), inBrowser = false))
            RowDivider()
            FindingRow(DiagnosticsCode.KeyBiometricOnly.finding(Subject.Key("")))
            RowDivider()
            RowText(strings.noPassphraseInChip, color = AppTheme.muted, small = true)
        }
    } else if (wizard.chipHere) {
        // Which level the chip gives a key made here is the keyring's to say; the choice is the person's.
        WhereChoice(wizard, strings, if (wizard.inChip) null else KeyStorage.SealedByPassphrase, Subject.Key(""), inBrowser = false)
    }
    PolicyChoice(wizard, strings)
    ProblemLine(wizard, strings)
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

    val subject = Subject.Key(found.id?.fingerprint.orEmpty())
    found.wouldBe?.let { best ->
        if (wizard.chipFor(best)) {
            WhereChoice(wizard, strings, wizard.chosen(best), subject, inBrowser)
        } else {
            Section(title = strings.wouldLive) {
                FindingRow(KeyFindings.placement(best, subject, inBrowser))
            }
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
    ProblemLine(wizard, strings)
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

/**
 * Where the key will live (09, D237): in the chip or under its passphrase only, then — in the chip —
 * the key's own locks ([LockChoices]). [chosen] is shown in the catalogue's words when it is known.
 */
@Composable
private fun WhereChoice(wizard: ImportWizard, strings: SecurityStrings, chosen: KeyStorage?, subject: Subject, inBrowser: Boolean) {
    Section(title = strings.wouldLive) {
        Box(Modifier.testTag("import:where-chip")) {
            ChoiceRow(strings.keepInChip, wizard.inChip) { wizard.inChip = true }
        }
        RowDivider()
        Box(Modifier.testTag("import:where-passphrase")) {
            ChoiceRow(strings.keepUnderPassphrase, !wizard.inChip) { wizard.inChip = false }
        }
        chosen?.let {
            RowDivider()
            FindingRow(KeyFindings.placement(it, subject, inBrowser))
        }
        if (wizard.inChip) BiometricFinding(wizard, subject)
    }
    if (wizard.inChip) LockChoices(wizard, strings)
}

/** "Only my fingerprint", in the catalogue's words, when it is chosen (D245). */
@Composable
private fun BiometricFinding(wizard: ImportWizard, subject: Subject) {
    if (!wizard.offersBiometricOnly || !wizard.biometricOnly) return
    RowDivider()
    FindingRow(DiagnosticsCode.KeyBiometricOnly.finding(subject))
}

/**
 * The key's own locks in the chip (D247): only a strong biometric (on by default where one is
 * enrolled, D245, D246), and its passphrase — which is required, and cannot be switched off, when
 * the biometric is not the lock. The screen lock alone never is.
 */
@Composable
private fun LockChoices(wizard: ImportWizard, strings: SecurityStrings) {
    Card {
        if (wizard.offersBiometricOnly) {
            ToggleRow(
                label = if (wizard.onIphone) strings.biometricOnlyIphone else strings.biometricOnly,
                checked = wizard.biometricOnly,
                onCheckedChange = { wizard.biometricOnly = it },
                modifier = Modifier.testTag("import:biometric-only"),
            )
            RowDivider()
        }
        ToggleRow(
            label = strings.keepPassphrase,
            checked = wizard.keepPassphrase,
            onCheckedChange = { wizard.passphraseChosen = it },
            modifier = Modifier.testTag("import:keep-passphrase"),
            enabled = !wizard.passphraseRequired,
        )
        if (wizard.passphraseRequired) {
            RowText(strings.passphraseRequired, Modifier.testTag("import:passphrase-required"), color = AppTheme.muted, small = true)
        }
    }
}

/** Where a P-256 made here lives: the chip it is born in (05 §4.3). */
private fun bornInChip(device: KeyringDevice): KeyStorage = when {
    device.secureEnclave -> KeyStorage.SecureEnclave
    device.strongBox -> KeyStorage.StrongBox
    else -> KeyStorage.Tee
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
private fun ProblemLine(wizard: ImportWizard, strings: SecurityStrings) {
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
