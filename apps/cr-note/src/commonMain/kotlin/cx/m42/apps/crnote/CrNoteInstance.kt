package cx.m42.apps.crnote

import androidx.compose.runtime.Composable
import cx.m42.services.files.FileSaver
import cx.m42.services.qr.QrScanner
import cx.m42.services.qr.SeriesStep
import cx.m42.services.share.FileSharer
import cx.m42.services.ssh.PublicKeyBook
import cx.m42.services.vault.BiometricKey
import cx.m42.services.vault.BiometricOpened
import cx.m42.services.vault.PrivateFiles
import cx.m42.superizer.app.AppChrome
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.lock.SecretHold
import cx.m42.superizer.runtime.FilePicker
import cx.m42.superizer.runtime.InstanceRuntime
import cx.m42.superizer.runtime.LenientJson
import cx.m42.superizer.runtime.NetworkException
import cx.m42.superizer.ssh.SshKeyring
import cx.m42.superizer.ssh.SshOutcome
import cx.m42.unitool.kit.crypto.secureRandomBytes
import cx.m42.unitool.kit.openssl.OpensslEnc
import cx.m42.unitool.kit.password.PasswordGenerator
import cx.m42.unitool.kit.qr.QrFile
import cx.m42.unitool.kit.qr.QrFileGathering
import cx.m42.unitool.kit.ssh.Age
import cx.m42.unitool.kit.ssh.AgeKeys
import cx.m42.unitool.kit.ssh.SshPublicKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Where the person is (D651–D663). */
internal sealed interface Page {
    /** Blank, while the face sheet is up or the app is on its way out (D651). */
    data object Starting : Page

    /** The faces or fingers changed and the notes are gone (D656). */
    data object Changed : Page

    /** The first visit: "Choose a PIN", then "Repeat it" (D652). */
    data object NewPin : Page

    /** Every other visit: the PIN that picks the directory (D653). */
    data object Pin : Page

    data object Notes : Page

    data class Editor(val id: String) : Page

    data object Export : Page

    data object Import : Page
}

internal enum class PinProblem { Mismatch, Forbidden }

/** Someone to export to (D660): a key of this phone, a saved one, one pasted, or one of a GitHub account. */
internal class Recipient(val id: String, val label: String, val ssh: SshPublicKey?, val x25519: ByteArray?)

internal sealed interface ExportProblem {
    data object ShortPassword : ExportProblem
    data object PasswordsDiffer : ExportProblem
    data object NoRecipients : ExportProblem
    data object BadRecipient : ExportProblem
    data object Failed : ExportProblem
}

internal enum class GitHubState { Idle, Loading, NoSuchUser, NoKeys, Offline }

/** An export made and not yet handed over: armored text, so `age -d` and `openssl -a` read it as it is. */
internal class ExportReady(val name: String, val text: String, val method: ExportMethod)

internal sealed interface ImportProblem {
    data object Refused : ImportProblem
    data object WrongPassword : ImportProblem
    data object NoKey : ImportProblem
    data object NotText : ImportProblem
    data object NothingNew : ImportProblem
    data object Damaged : ImportProblem
}

/**
 * cr-note (.ai-notes/cr-note): one root key behind the face, a directory per PIN, `178500` to wipe.
 *
 * What is open lives here and only here, and only while the app is: the root R, the open directory's
 * key, the notes. Closing — the background, a minute idle, back from the PIN — zeroes them (D659).
 * The disk is touched through one queue, [disk], which outlives the instance just long enough to
 * finish the last write; every key is zeroed at the end of that queue, not before.
 */
internal class CrNoteInstance(val runtime: InstanceRuntime) : AppInstance() {

    private val biometric: BiometricKey? = runtime.service(BiometricKey.Key)
    private val files: PrivateFiles? = runtime.service(PrivateFiles.Key)
    private val hold: SecretHold? = runtime.service(SecretHold.Key)
    private val scanner: QrScanner? = runtime.service(QrScanner.Key)
    private val picker: FilePicker? = runtime.service(FilePicker.Key)
    private val saver: FileSaver? = runtime.service(FileSaver.Key)
    private val sharer: FileSharer? = runtime.service(FileSharer.Key)
    private val keyring: SshKeyring? = runtime.service(SshKeyring.Key)
    private val book: PublicKeyBook? = runtime.service(PublicKeyBook.Key)

    private val vault: NoteVault? = files?.let { NoteVault(it) }

    val canScan: Boolean get() = scanner != null
    val canPick: Boolean get() = picker != null
    val canSave: Boolean get() = saver != null
    val canShare: Boolean get() = sharer != null
    val hasPhoneKeys: Boolean get() = keyring != null

    private val _page = MutableStateFlow<Page>(Page.Starting)
    val page: StateFlow<Page> get() = _page.asStateFlow()

    // ------------------------------------------------------------ what is open

    private var root: ByteArray? = null
    private var directory: OpenDirectory? = null

    val pin = MutableStateFlow("")
    private var firstPin: String? = null
    val repeating = MutableStateFlow(false)
    val pinProblem = MutableStateFlow<PinProblem?>(null)

    val notes = MutableStateFlow<List<Note>>(emptyList())
    val editorText = MutableStateFlow("")
    val confirmDelete = MutableStateFlow(false)
    val menuOpen = MutableStateFlow(false)

    // ------------------------------------------------------------ export

    val exportMethod = MutableStateFlow(ExportMethod.AgePassword)
    val exportPassword = MutableStateFlow("")
    val exportRepeat = MutableStateFlow("")
    val exportShowPassword = MutableStateFlow(false)
    val recipients = MutableStateFlow<List<Recipient>>(emptyList())
    val chosen = MutableStateFlow<Set<String>>(emptySet())
    val recipientText = MutableStateFlow("")
    val githubLogin = MutableStateFlow("")
    val githubState = MutableStateFlow(GitHubState.Idle)
    val exportProblem = MutableStateFlow<ExportProblem?>(null)
    val exportReady = MutableStateFlow<ExportReady?>(null)
    val exportDone = MutableStateFlow(false)

    // ------------------------------------------------------------ import

    val incoming = MutableStateFlow<Incoming?>(null)
    val importPassword = MutableStateFlow("")
    val importProblem = MutableStateFlow<ImportProblem?>(null)
    val importFound = MutableStateFlow<List<ExportNote>?>(null)
    val importAdded = MutableStateFlow<Int?>(null)
    val scanProgress = MutableStateFlow<String?>(null)

    val working = MutableStateFlow(false)

    private var saveJob: Job? = null
    private var dirty = false
    private var outsideNow = false
    private var closed = false

    /** The disk's one queue, not the instance's scope: the last write has to finish after the screen is gone. */
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val diskLock = Mutex()

    private val _chrome = MutableStateFlow(AppChrome())
    override val chrome: StateFlow<AppChrome> get() = _chrome.asStateFlow()

    val strings: CrNoteStrings get() = runtime.locale.pick(CrNoteStringTables, CrNoteStringsEn)

    // ------------------------------------------------------------ coming in

    override suspend fun onLaunch() {
        val key = biometric
        // No face to ask with — no biometric, a phone too old, the web: the code was taken as any is,
        // and then nothing happens at all (D651, question 284). No key is made, no file written.
        if (key == null || vault == null || !runCatching { key.available() }.getOrDefault(false)) {
            // After the launch, not inside it: the handler holds its gate until onLaunch returns.
            runtime.scope.launch { leave() }
            return
        }
        runtime.scope.launch { comeIn(key, vault) }
    }

    private suspend fun comeIn(key: BiometricKey, vault: NoteVault) {
        val sealed = disk { vault.sealedRoot() }
        when {
            sealed == null -> create(key, vault)
            !key.exists() -> {
                // The chip let the key go — new faces — while its file stayed (D656).
                disk { vault.wipeAll() }
                go(Page.Changed)
            }
            else -> when (val opened = key.open(sealed, strings.faceReason, strings.cancel)) {
                is BiometricOpened.Opened -> {
                    if (opened.secret.size != NoteVault.ROOT_SIZE) {
                        opened.secret.fill(0)
                        return leave()
                    }
                    root = opened.secret
                    go(Page.Pin)
                }
                BiometricOpened.Invalidated -> {
                    key.destroy()
                    disk { vault.wipeAll() }
                    go(Page.Changed)
                }
                // Cancelled, not recognised, too many tries: the app closes (D651).
                else -> leave()
            }
        }
    }

    /** The first visit, or after D656: a new root, sealed to a new chip key, opened once by the face (D652). */
    private suspend fun create(key: BiometricKey, vault: NoteVault) {
        val fresh = secureRandomBytes(NoteVault.ROOT_SIZE)
        val sealed = try {
            key.create(fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            fresh.fill(0)
            return leave()
        }
        when (val opened = key.open(sealed, strings.faceReason, strings.cancel)) {
            is BiometricOpened.Opened -> {
                val same = opened.secret.contentEquals(fresh)
                opened.secret.fill(0)
                if (!same) {
                    fresh.fill(0)
                    key.destroy()
                    return leave()
                }
                disk { vault.start(sealed) }
                root = fresh
                firstPin = null
                repeating.value = false
                go(Page.NewPin)
            }
            else -> {
                fresh.fill(0)
                key.destroy()
                leave()
            }
        }
    }

    /** "Start again" after the faces changed: as a first visit. */
    fun startAgain() {
        val key = biometric ?: return
        val vault = vault ?: return
        runtime.scope.launch { create(key, vault) }
    }

    // ------------------------------------------------------------ the PIN

    fun digit(d: Char) {
        if (d !in '0'..'9' || pin.value.length >= NoteVault.PIN_LENGTH) return
        pinProblem.value = null
        pin.value += d
        if (pin.value.length == NoteVault.PIN_LENGTH) submitPin()
    }

    fun erase() {
        pin.value = pin.value.dropLast(1)
    }

    private fun submitPin() {
        val typed = pin.value
        pin.value = ""
        when (_page.value) {
            Page.NewPin -> {
                val first = firstPin
                when {
                    first == null && typed == NoteVault.WIPE_PIN -> pinProblem.value = PinProblem.Forbidden
                    first == null -> {
                        firstPin = typed
                        repeating.value = true
                    }
                    typed == first -> {
                        firstPin = null
                        repeating.value = false
                        enter(typed)
                    }
                    else -> {
                        firstPin = null
                        repeating.value = false
                        pinProblem.value = PinProblem.Mismatch
                    }
                }
            }
            Page.Pin -> if (typed == NoteVault.WIPE_PIN) wipeEverything() else enter(typed)
            else -> Unit
        }
    }

    /** [typed]'s directory: its notes, or an empty one — there is no wrong PIN (D653, question 278). */
    private fun enter(typed: String) {
        val r = root ?: return
        val vault = vault ?: return
        runtime.scope.launch {
            val (opened, stored) = disk { vault.open(r, typed) }
            directory = opened
            notes.value = sorted(stored)
            go(Page.Notes)
        }
    }

    /**
     * `178500` (D655): the chip key, every file and R gone; a new key, a new R and new decoys; and on
     * screen, at once, the empty directory of `178500` under the new R — as any unknown PIN shows.
     * Nothing asked, nothing said. The slow part, the new chip key, runs behind the screen.
     */
    private fun wipeEverything() {
        val key = biometric ?: return
        val vault = vault ?: return
        val old = root
        val fresh = secureRandomBytes(NoteVault.ROOT_SIZE)
        root = fresh
        directory = NoteVault.fresh(fresh, NoteVault.WIPE_PIN)
        notes.value = emptyList()
        go(Page.Notes)
        val sealing = fresh.copyOf()
        queue {
            old?.fill(0)
            key.destroy()
            vault.wipeAll()
            try {
                vault.start(key.create(sealing))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // No chip key now: the next visit is a first one. Nothing of the old is left either way.
            } finally {
                sealing.fill(0)
            }
        }
    }

    // ------------------------------------------------------------ notes

    fun newNote() {
        val now = runtime.clock.now()
        val id = NoteVault.hex(secureRandomBytes(8))
        editorText.value = ""
        editing = Note(id, "", now, now)
        go(Page.Editor(id))
    }

    /** A note being written that is not in the list yet: it joins it with its first character. */
    private var editing: Note? = null

    fun openNote(id: String) {
        val note = notes.value.firstOrNull { it.id == id } ?: return
        editing = note
        editorText.value = note.text
        go(Page.Editor(id))
    }

    fun edit(text: String) {
        val current = editing ?: return
        editorText.value = text
        val changed = Note(current.id, text, current.created, runtime.clock.now())
        editing = changed
        val list = notes.value
        notes.value = if (list.any { it.id == current.id }) list.map { if (it.id == current.id) changed else it } else list + changed
        changedNotes()
    }

    /**
     * A QR code read by the camera, its text as it is, at the end of the open note (D669): on a new
     * line when the note has text, nothing encrypted or decoded about it. The camera leaves the app
     * for its own screen, so through [outside] — the background would close the app otherwise.
     */
    fun scanIntoNote() {
        val camera = scanner ?: return
        val note = editing ?: return
        runtime.scope.launch {
            val code = outside { camera.scan() } ?: return@launch
            if (closed || editing?.id != note.id || code.isEmpty()) return@launch
            val before = editorText.value
            edit(
                when {
                    before.isEmpty() -> code
                    before.endsWith("\n") -> before + code
                    else -> before + "\n" + code
                },
            )
        }
    }

    fun askDelete() {
        confirmDelete.value = true
    }

    fun cancelDelete() {
        confirmDelete.value = false
    }

    fun delete() {
        val current = editing ?: return
        confirmDelete.value = false
        notes.value = notes.value.filterNot { it.id == current.id }
        editing = null
        editorText.value = ""
        changedNotes()
        go(Page.Notes)
    }

    /** A save half a second after the last keystroke (D657). */
    private fun changedNotes() {
        dirty = true
        saveJob?.cancel()
        saveJob = runtime.scope.launch {
            delay(SAVE_DELAY_MILLIS)
            flush()
        }
    }

    /** What is unsaved, into the queue now. The directory's key is the queue's from here on. */
    private fun flush() {
        saveJob?.cancel()
        saveJob = null
        if (!dirty) return
        dirty = false
        val vault = vault ?: return
        val dir = directory ?: return
        val snapshot = notes.value
        queue {
            val saved = vault.save(dir, snapshot)
            // The same arrays: only whether it is on disk, and its own decoys, changed.
            if (directory === dir) directory = saved
        }
    }

    // ------------------------------------------------------------ export (D660)

    fun startExport() {
        menuOpen.value = false
        exportMethod.value = ExportMethod.AgePassword
        exportPassword.value = ""
        exportRepeat.value = ""
        exportShowPassword.value = false
        recipientText.value = ""
        githubLogin.value = ""
        githubState.value = GitHubState.Idle
        exportProblem.value = null
        exportReady.value = null
        exportDone.value = false
        chosen.value = emptySet()
        recipients.value = ownRecipients()
        go(Page.Export)
    }

    private fun ownRecipients(): List<Recipient> {
        val phone = keyring?.keys?.value.orEmpty().mapNotNull { info ->
            val key = SshPublicKey.parse(info.publicKey)?.takeIf { Age.supports(it) } ?: return@mapNotNull null
            Recipient("phone:" + info.publicKey, strings.phoneKey(info.comment.ifBlank { info.label }), key, null)
        }
        val saved = book?.keys?.value.orEmpty().filter { Age.supports(it.key) }.map {
            Recipient("saved:" + it.line, it.name, it.key, null)
        }
        return phone + saved
    }

    fun generatePassword() {
        val generated = PasswordGenerator.generate()
        exportPassword.value = generated
        exportRepeat.value = generated
        exportShowPassword.value = true
    }

    fun toggle(id: String) {
        chosen.value = if (id in chosen.value) chosen.value - id else chosen.value + id
    }

    /** `age1…` or SSH public key lines, added to the list and ticked. */
    fun addRecipients() {
        val text = recipientText.value.trim()
        if (text.isEmpty()) return
        val added = mutableListOf<Recipient>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val x = AgeKeys.parseRecipient(line)
            val ssh = if (x == null) SshPublicKey.parse(line)?.takeIf { Age.supports(it) } else null
            when {
                x != null -> added += Recipient("age:$line", line.take(16) + "…", null, x)
                ssh != null -> added += Recipient("ssh:" + ssh.authorizedKeysLine(), ssh.authorizedKeysLine().take(24) + "…", ssh, null)
                else -> {
                    exportProblem.value = ExportProblem.BadRecipient
                    return
                }
            }
        }
        recipientText.value = ""
        exportProblem.value = null
        addAndChoose(added)
    }

    private fun addAndChoose(added: List<Recipient>) {
        val known = recipients.value.map { it.id }.toSet()
        recipients.value = recipients.value + added.filter { it.id !in known }
        chosen.value = chosen.value + added.map { it.id }
    }

    /** A GitHub account's SSH keys, as `github.com/<login>.keys` lists them (D660). */
    fun fetchGitHub() {
        val login = githubLogin(githubLogin.value) ?: return run { githubState.value = GitHubState.NoSuchUser }
        githubState.value = GitHubState.Loading
        runtime.scope.launch {
            val response = try {
                runtime.network.get("https://api.github.com/users/$login/keys")
            } catch (_: NetworkException) {
                githubState.value = GitHubState.Offline
                return@launch
            }
            if (response.status == 404) return@launch run { githubState.value = GitHubState.NoSuchUser }
            if (!response.isSuccess) return@launch run { githubState.value = GitHubState.Offline }
            val keys = runCatching { LenientJson.decodeFromString<List<GitHubApiKey>>(response.body) }.getOrNull().orEmpty()
                .mapNotNull { SshPublicKey.parse(it.key) }.filter { Age.supports(it) }
            if (keys.isEmpty()) return@launch run { githubState.value = GitHubState.NoKeys }
            githubState.value = GitHubState.Idle
            githubLogin.value = ""
            addAndChoose(keys.map { Recipient("gh:" + it.authorizedKeysLine(), "github.com/$login · ${it.type}", it, null) })
        }
    }

    fun export() {
        if (working.value) return
        exportProblem.value = null
        val method = exportMethod.value
        val password = exportPassword.value
        if (method != ExportMethod.AgeKeys) {
            if (password.length < Transfer.MIN_PASSWORD) return run { exportProblem.value = ExportProblem.ShortPassword }
            if (password != exportRepeat.value) return run { exportProblem.value = ExportProblem.PasswordsDiffer }
        }
        val picked = recipients.value.filter { it.id in chosen.value }
        if (method == ExportMethod.AgeKeys && picked.isEmpty()) return run { exportProblem.value = ExportProblem.NoRecipients }
        val snapshot = notes.value
        val name = Transfer.fileName(runtime.clock.now(), method)
        working.value = true
        runtime.scope.launch {
            try {
                val text = withContext(Dispatchers.Default) {
                    val plain = Transfer.plaintext(snapshot)
                    try {
                        when (method) {
                            ExportMethod.AgePassword -> Transfer.agePassword(plain, password)
                            ExportMethod.AgeKeys -> Transfer.ageKeys(plain, picked.mapNotNull { it.ssh }, picked.mapNotNull { it.x25519 })
                            ExportMethod.Openssl -> Transfer.openssl(plain, password)
                        }
                    } finally {
                        plain.fill(0)
                    }
                }
                exportReady.value = ExportReady(name, text, method)
                exportPassword.value = ""
                exportRepeat.value = ""
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                exportProblem.value = ExportProblem.Failed
            } finally {
                working.value = false
            }
        }
    }

    fun saveExport() {
        val ready = exportReady.value ?: return
        val files = saver ?: return
        runtime.scope.launch {
            exportDone.value = outside { files.save(ready.name, ready.text.encodeToByteArray(), "application/octet-stream") }
        }
    }

    fun shareExport() {
        val ready = exportReady.value ?: return
        val sheet = sharer ?: return
        runtime.scope.launch {
            exportDone.value = outside { sheet.share(ready.name, ready.text, "application/octet-stream", null) }
        }
    }

    // ------------------------------------------------------------ import (D661)

    fun startImport() {
        menuOpen.value = false
        clearImport()
        go(Page.Import)
    }

    private fun clearImport() {
        incoming.value = null
        importPassword.value = ""
        importProblem.value = null
        importFound.value = null
        importAdded.value = null
        scanProgress.value = null
    }

    fun importFile() {
        val files = picker ?: return
        runtime.scope.launch {
            val file = outside { files.pick() } ?: return@launch
            take(file.bytes)
        }
    }

    /** One code with the whole armored file, or a file cut into many (UQR1, qr-scanner D633). */
    fun importQr() {
        val camera = scanner ?: return
        runtime.scope.launch {
            var gathering: QrFileGathering? = null
            var result: ByteArray? = null
            outside {
                camera.scanSeries(strings.scanHint) { code ->
                    val chunk = QrFile.parse(code)
                    if (chunk == null) {
                        if (gathering != null) return@scanSeries SeriesStep.Continue(strings.scanProgress(gathering!!.have, gathering!!.count))
                        result = code.encodeToByteArray()
                        return@scanSeries SeriesStep.Done
                    }
                    val next = gathering?.let { (it.add(chunk) as? QrFileGathering.Added.New)?.gathering ?: it } ?: QrFileGathering(chunk)
                    gathering = next
                    if (!next.complete) return@scanSeries SeriesStep.Continue(strings.scanProgress(next.have, next.count))
                    result = (next.assemble() as? QrFileGathering.Assembled.Ok)?.bytes ?: ByteArray(0)
                    SeriesStep.Done
                }
            }
            result?.let { take(it) }
        }
    }

    internal fun take(bytes: ByteArray) {
        clearImport()
        when (val detected = Transfer.detect(bytes)) {
            Incoming.Refused -> importProblem.value = ImportProblem.Refused
            else -> incoming.value = detected
        }
    }

    fun openImport() {
        val what = incoming.value ?: return
        if (working.value) return
        importProblem.value = null
        val password = importPassword.value.encodeToByteArray()
        working.value = true
        runtime.scope.launch {
            try {
                val plain: ByteArray? = when (what) {
                    is Incoming.Age -> if (what.password) {
                        withContext(Dispatchers.Default) {
                            (AgeKeys.decrypt(what.file, passphrase = password) as? AgeKeys.Opened.Ok)?.plaintext
                        } ?: return@launch run { importProblem.value = ImportProblem.WrongPassword }
                    } else {
                        val keys = keyring ?: return@launch run { importProblem.value = ImportProblem.NoKey }
                        when (val opened = outside { keys.decrypt(what.file) }) {
                            is SshOutcome.Ok -> opened.value
                            SshOutcome.Cancelled, SshOutcome.Refused -> return@launch
                            else -> return@launch run { importProblem.value = ImportProblem.NoKey }
                        }
                    }
                    is Incoming.Openssl -> withContext(Dispatchers.Default) { openssl(what.file, password) }
                        ?: return@launch run { importProblem.value = ImportProblem.WrongPassword }
                    Incoming.Refused -> null
                }
                if (plain == null) return@launch
                val found = Transfer.notes(plain, runtime.clock.now())
                plain.fill(0)
                if (found == null) return@launch run { importProblem.value = ImportProblem.NotText }
                val fresh = Transfer.fresh(notes.value, found)
                if (fresh.isEmpty()) return@launch run { importProblem.value = ImportProblem.NothingNew }
                importFound.value = fresh
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                importProblem.value = ImportProblem.Damaged
            } finally {
                password.fill(0)
                working.value = false
            }
        }
    }

    /** Ours first, then `openssl enc`'s own default and its old one: a file from elsewhere opens too (question 286). */
    private fun openssl(file: ByteArray, password: ByteArray): ByteArray? {
        val tries = listOf(
            Transfer.OPENSSL,
            OpensslEnc.Params(),
            OpensslEnc.Params(kdf = OpensslEnc.Kdf.Legacy),
        )
        for (params in tries) {
            val opened = OpensslEnc.decrypt(file, OpensslEnc.Key.Password(password.copyOf()), params)
            if (opened is OpensslEnc.Opened.Ok && opened.checked) {
                if (Transfer.notes(opened.plaintext, 0) != null) return opened.plaintext
                opened.plaintext.fill(0)
            }
        }
        return null
    }

    /** "Add N notes?" — yes (D661). */
    fun confirmImport() {
        val found = importFound.value ?: return
        val added = found.map { Note(NoteVault.hex(secureRandomBytes(8)), it.text, it.created, it.modified) }
        notes.value = sorted(notes.value + added)
        importFound.value = null
        incoming.value = null
        importAdded.value = added.size
        changedNotes()
    }

    fun cancelImport() {
        clearImport()
    }

    // ------------------------------------------------------------ moving about

    fun openMenu() {
        menuOpen.value = !menuOpen.value
    }

    /** Back inside the app; false where the host takes over and closes it. */
    fun back(): Boolean {
        if (menuOpen.value) {
            menuOpen.value = false
            return true
        }
        if (confirmDelete.value) {
            confirmDelete.value = false
            return true
        }
        when (_page.value) {
            is Page.Editor -> {
                val current = editing
                if (current != null && current.text.isBlank() && notes.value.any { it.id == current.id }) {
                    notes.value = notes.value.filterNot { it.id == current.id }
                    changedNotes()
                }
                editing = null
                editorText.value = ""
                notes.value = sorted(notes.value)
                go(Page.Notes)
            }
            Page.Export -> {
                exportReady.value = null
                exportPassword.value = ""
                exportRepeat.value = ""
                go(Page.Notes)
            }
            Page.Import -> {
                clearImport()
                go(Page.Notes)
            }
            else -> return false
        }
        return true
    }

    private fun go(page: Page) {
        _page.value = page
        menuOpen.value = false
        confirmDelete.value = false
        _chrome.value = when (page) {
            is Page.Editor -> AppChrome(canGoBack = true)
            Page.Export -> AppChrome(title = strings.exportTitle, canGoBack = true)
            Page.Import -> AppChrome(title = strings.importTitle, canGoBack = true)
            else -> AppChrome()
        }
    }

    private fun sorted(list: List<Note>): List<Note> = list.sortedByDescending { it.modified }

    // ------------------------------------------------------------ going

    /** The host left for the background: closed, not curtained (D659). Unless it was our own picker or sheet. */
    override fun onBackground() {
        if (outsideNow) return
        leaveNow()
    }

    override fun saveState(): JsonObject? = null

    override fun onClose() = wipe()

    override fun dispose() = wipe()

    private fun leaveNow() {
        wipe()
        runtime.scope.launch { leave() }
    }

    /** Closed and gone back where the person came from (D651, D659). */
    private suspend fun leave() {
        go(Page.Starting)
        withContext(NonCancellable) { runtime.navigation.close() }
    }

    /**
     * Everything open, gone (D659): the last save into the queue, then the keys zeroed at its end, the
     * notes off the screen now.
     */
    private fun wipe() {
        if (closed) return
        closed = true
        flush()
        val r = root
        val dir = directory
        root = null
        directory = null
        editing = null
        if (r != null || dir != null) queue {
            r?.fill(0)
            dir?.wipe()
        }
        notes.value = emptyList()
        editorText.value = ""
        pin.value = ""
        firstPin = null
        exportPassword.value = ""
        exportRepeat.value = ""
        exportReady.value = null
        importPassword.value = ""
        importFound.value = null
        incoming.value = null
        _page.value = Page.Starting
    }

    private suspend fun <T> disk(block: suspend () -> T): T = withContext(Dispatchers.Default) { diskLock.withLock { block() } }

    private fun queue(block: suspend () -> Unit) {
        writer.launch { diskLock.withLock { runCatching { block() } } }
    }

    private suspend fun <T> outside(block: suspend () -> T): T {
        outsideNow = true
        try {
            return hold?.outside(block) ?: block()
        } finally {
            outsideNow = false
        }
    }

    /** The pending save now rather than in half a second. For tests, whose clock is virtual. */
    internal fun saveNow() = flush()

    /** Waits for every write queued so far. For tests. */
    internal suspend fun settle() {
        diskLock.withLock { }
        writer.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
    }

    @Composable
    override fun Content() = CrNoteScreen(this)

    @Serializable
    private class GitHubApiKey(val key: String)

    companion object {
        const val SAVE_DELAY_MILLIS: Long = 500

        private val LOGIN = Regex("^[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}$")

        fun githubLogin(text: String): String? = text.trim()
            .removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .removePrefix("github.com/").removePrefix("@").removeSuffix("/").removeSuffix(".keys")
            .takeIf { LOGIN.matches(it) }
    }
}
