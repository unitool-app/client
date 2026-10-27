package cx.m42.apps.passwords

import androidx.compose.runtime.Composable
import cx.m42.services.link.LocalLink
import cx.m42.services.qr.QrScanner
import cx.m42.services.share.FileSharer
import cx.m42.services.ssh.Age
import cx.m42.services.ssh.KnownKey
import cx.m42.services.ssh.PublicKeyBook
import cx.m42.services.ssh.SshPublicKey
import cx.m42.superizer.app.AppChrome
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.lock.UserPresence
import cx.m42.superizer.runtime.FilePicker
import cx.m42.superizer.runtime.InstanceRuntime
import cx.m42.superizer.ssh.SshKeyring
import cx.m42.superizer.ssh.SshOutcome
import cx.m42.unitool.kit.crypto.secureRandomBytes
import cx.m42.unitool.kit.password.EntropyPool
import cx.m42.unitool.kit.password.PasswordGenerator
import cx.m42.unitool.kit.password.PasswordMode
import cx.m42.unitool.kit.password.PasswordRules
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Where the app is inside itself. The host's frame only ever sees the title this implies. */
internal sealed interface Page {
    data object List : Page
    data object Add : Page
    data class Details(val id: String) : Page
    data class Edit(val id: String) : Page
    data object Export : Page

    /** Where to import from: the camera, a file, pasted text. */
    data object ImportFrom : Page

    /** What was read, before it is added. */
    data object Import : Page

    /** The paired computers (passwords-browser README §5). */
    data object Computers : Page
}

/** Why the list on screen may not be what is stored — 2FA's three, word for word. */
internal enum class StorageProblem {
    Unreadable,
    Locked,
    NotSaved,
    ;

    /** A stored list is there and not on screen: writing now would replace it. */
    val blocksWriting: Boolean get() = this == Unreadable || this == Locked
}

/** Which field of an entry was copied, for the "Copied" under it. */
internal enum class Field { Username, Password }

internal data class Copied(val id: String, val field: Field)

/** The drawing so far: points taken, the bits they are guessed to hold, and how long it went on. */
internal data class Drawn(val samples: Int = 0, val bits: Int = 0, val startedAt: Long? = null, val lastAt: Long? = null) {
    val seconds: Long get() = if (startedAt == null || lastAt == null) 0 else (lastAt - startedAt) / 1000
}

/** The GitHub login the last export went to — a public name, remembered so a second export is one tap. */
internal const val KEY_EXPORT_LOGIN = "export.github"

/** The generator's settings ([GeneratorSettings]): what the last person chose is what Generate makes. */
internal const val KEY_GENERATOR = "generator"

/** What the export is shared as: armored age is text, but no app should try to open it as text. */
internal const val EXPORT_MIME = "application/octet-stream"

/** How long a copied password stays on the clipboard: long enough to switch apps and paste. */
internal const val CLIPBOARD_SECONDS = 60

/**
 * How long a shown password stays on the screen. After that it is dots again, and showing it asks
 * the device again — an unlocked phone left open on this page does not keep the password in view.
 */
internal const val SECRET_SECONDS = 60

private const val CLIPBOARD_REFOCUS_MILLIS = 1_000L

/**
 * One open Passwords app.
 *
 * Shaped like 2FA's instance on purpose (.ai-notes/passwords): state in flows so a test can drive
 * it without composing, the same storage problems, the same confirmations, the same export.
 */
internal class PasswordsInstance(val runtime: InstanceRuntime) : AppInstance() {

    private val strings: PasswordsStrings get() = runtime.locale.pick(PasswordsStringTables, PasswordsStringsEn)

    private val _entries = MutableStateFlow<List<PasswordEntry>>(emptyList())
    val entries: StateFlow<List<PasswordEntry>> get() = _entries.asStateFlow()

    private val store = EntryStore(runtime.secrets)

    /** False until the stored list has been read; the screen draws nothing rather than "no passwords yet". */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> get() = _loaded.asStateFlow()

    val storageProblem = MutableStateFlow<StorageProblem?>(null)

    private val _page = MutableStateFlow<Page>(Page.List)
    val page: StateFlow<Page> get() = _page.asStateFlow()

    val query = MutableStateFlow("")

    val draft = MutableStateFlow(EntryDraft())
    val draftError = MutableStateFlow<DraftError?>(null)

    /** How Generate makes a password: characters, words or both, and of what. */
    val generator = MutableStateFlow(PasswordRules())

    /** Whether the generator's settings are open under the button. */
    val generatorOpen = MutableStateFlow(false)

    /** The password Generate put into the form last; a change of settings makes it again while it is still there. */
    private var generated: String? = null

    /**
     * What a finger or the mouse drew on the generator's field, mixed into every password while it
     * holds anything. In memory only, gone with the app.
     */
    private val pool = EntropyPool()
    private val poolClock = TimeSource.Monotonic.markNow()

    /** Whether the drawing field is open. */
    val drawing = MutableStateFlow(false)

    /** How much has been drawn, for the field's progress and the hint. */
    val drawn = MutableStateFlow(Drawn())

    /**
     * The host's "is this still the owner?" (06 §5.5), before everything that shows a stored
     * password or gives the list away: Show, Edit (the form has the password in it), Export and
     * Delete. None on desktop and the web; the actions then just work.
     */
    private val presence: UserPresence? = runtime.service(UserPresence.Key)

    /** The entry whose password is on screen, if any. Never in a snapshot. */
    val shownPassword = MutableStateFlow<String?>(null)
    private var hideTimer: Job? = null

    /** What was copied last, for the word under it, until the clipboard is cleared. */
    val copied = MutableStateFlow<Copied?>(null)
    private var onClipboard: Pair<String, SecretClipboard>? = null
    private var clipboardTimer: Job? = null

    // ---- import

    private val scanner: QrScanner? = runtime.service(QrScanner.Key)
    val canScan: Boolean get() = scanner != null
    val scanning = MutableStateFlow(false)

    /** A file picker opens a browser's CSV; the keyring, where there is one, opens this app's export too. */
    private val files: FilePicker? = runtime.service(FilePicker.Key)
    private val keyring: SshKeyring? = runtime.service(SshKeyring.Key)
    val canImportFile: Boolean get() = files != null
    val opening = MutableStateFlow(false)
    val fileProblem = MutableStateFlow<FileProblem?>(null)

    /** Pasted CSV. Like the batch, never in a snapshot. */
    val importText = MutableStateFlow("")
    val importBatch = MutableStateFlow<ImportBatch?>(null)
    val importSelected = MutableStateFlow<Set<Int>>(emptySet())

    val importFilter = MutableStateFlow(ImportFilter.All)

    /** An entry whose password differs from a stored one goes in beside it rather than over it. */
    val importKeepBoth = MutableStateFlow(false)

    /** The list's "⋯": import, export, computers — kept off the bottom of a long list. */
    val menuOpen = MutableStateFlow(false)

    // ---- export, as in 2FA

    val exportLogin = MutableStateFlow("")
    val exportKeys = MutableStateFlow<KeysState>(KeysState.Idle)
    val exportSelected = MutableStateFlow<Set<String>>(emptySet())
    private val book: PublicKeyBook? = runtime.service(PublicKeyBook.Key)
    val savedKeys: StateFlow<List<KnownKey>> = book?.keys ?: MutableStateFlow(emptyList())
    val exportDone = MutableStateFlow<ExportDone?>(null)
    private val sharer: FileSharer? = runtime.service(FileSharer.Key)
    val canShare: Boolean get() = sharer != null

    // ---- a computer's browser asking (.ai-notes/passwords-browser)

    private val browserStrings: BrowserStrings get() = runtime.locale.pick(BrowserStringTables, BrowserStringsEn)

    /**
     * The paired computers and the request sheet. It sees the entries only while the list is open:
     * a locked or unreadable list answers a computer "locked", never an empty choice.
     */
    val browser = BrowserLink(
        runtime = runtime,
        link = runtime.service(LocalLink.Key),
        scanner = scanner,
        entries = { _entries.value.takeIf { _loaded.value && storageProblem.value?.blocksWriting != true } },
        confirm = ::confirmed,
        strings = { browserStrings },
    )

    private val _chrome = MutableStateFlow(AppChrome())
    override val chrome: StateFlow<AppChrome> get() = _chrome.asStateFlow()

    override suspend fun onLaunch() {
        runtime.analytics.screen("passwords")
        if (exportLogin.value.isEmpty()) exportLogin.value = runtime.storage.get(KEY_EXPORT_LOGIN).orEmpty()
        generator.value = GeneratorSettings.decode(runtime.storage.get(KEY_GENERATOR))
        // In the instance's scope, as in 2FA: opening the list is a trip to the Keystore, and a
        // launch that suspends can be cancelled under it by the host's link route.
        runtime.scope.launch { load() }
        runtime.scope.launch { browser.load() }
    }

    private suspend fun load() {
        val loaded = store.load()
        _entries.value = (loaded as? EntryStore.Loaded.Ok)?.entries.orEmpty()
        storageProblem.value = when (loaded) {
            is EntryStore.Loaded.Ok -> null
            EntryStore.Loaded.Locked -> StorageProblem.Locked
            EntryStore.Loaded.Unreadable -> StorageProblem.Unreadable
        }
        _loaded.value = true
        settlePage()
    }

    /** A page restored before the list was read, checked against it once it has been. */
    private fun settlePage() {
        if (!_loaded.value) return
        val current = _page.value
        val blocked = storageProblem.value?.blocksWriting == true
        val gone = (current as? Page.Details)?.id?.let { id -> _entries.value.none { it.id == id } } ?: false
        if (blocked && current != Page.List || gone) _page.value = Page.List
        updateChrome()
    }

    /** "Unlock": read the list again — the host asks for the passphrase this time round. */
    fun unlock() {
        runtime.scope.launch { load() }
    }

    /** Alphabetical by what the list shows, filtered by the search. */
    fun visibleEntries(): List<PasswordEntry> {
        val q = query.value.trim()
        return _entries.value
            .filter { q.isEmpty() || it.matches(q) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.shownTitle })
    }

    fun entry(id: String): PasswordEntry? = _entries.value.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- navigation

    fun startAdd() {
        draft.value = EntryDraft()
        draftError.value = null
        go(Page.Add)
    }

    fun openDetails(entry: PasswordEntry) = go(Page.Details(entry.id))

    /** The form shows the password as typed, so getting into it with a stored one asks first. */
    fun startEdit(entry: PasswordEntry) {
        runtime.scope.launch {
            if (!confirmed(strings.confirmEdit)) return@launch
            draft.value = EntryDraft.of(entry)
            draftError.value = null
            go(Page.Edit(entry.id))
        }
    }

    fun startExport() {
        runtime.scope.launch {
            if (!confirmed(strings.confirmExport)) return@launch
            exportKeys.value = KeysState.Idle
            exportDone.value = null
            go(Page.Export)
        }
    }

    fun startComputers() = go(Page.Computers)

    fun startImport() {
        importText.value = ""
        fileProblem.value = null
        draftError.value = null
        go(Page.ImportFrom)
    }

    /** Back inside the app. False at the root, which is where the host takes over and closes it. */
    fun back(): Boolean {
        if (menuOpen.value) {
            menuOpen.value = false
            return true
        }
        when (val current = _page.value) {
            Page.List -> return false
            is Page.Edit -> go(Page.Details(current.id))
            Page.Import -> go(Page.ImportFrom)
            else -> go(Page.List)
        }
        return true
    }

    private fun go(page: Page) {
        _page.value = page
        menuOpen.value = false
        hidePassword()
        if (page != Page.Import) {
            importBatch.value = null
            importSelected.value = emptySet()
            importKeepBoth.value = false
            importFilter.value = ImportFilter.All
        }
        // The draft holds a stored password while editing; it goes when the form does.
        if (page != Page.Add && page !is Page.Edit) draft.value = EntryDraft()
        updateChrome()
    }

    private fun updateChrome() {
        _chrome.value = when (val current = _page.value) {
            Page.List -> AppChrome()
            Page.Add -> AppChrome(title = strings.newEntry, canGoBack = true)
            is Page.Edit -> AppChrome(title = strings.editEntry, canGoBack = true)
            Page.Export -> AppChrome(title = strings.exportTitle, canGoBack = true)
            Page.ImportFrom, Page.Import -> AppChrome(title = strings.importTitle, canGoBack = true)
            Page.Computers -> AppChrome(title = browserStrings.computersTitle, canGoBack = true)
            is Page.Details -> AppChrome(title = entry(current.id)?.shownTitle, canGoBack = true)
        }
    }

    // ---------------------------------------------------------------- showing and copying

    /** Shows [entry]'s password once the person has confirmed; hiding it asks nothing. */
    fun togglePassword(entry: PasswordEntry) {
        if (shownPassword.value == entry.id) {
            hidePassword()
            return
        }
        runtime.scope.launch {
            if (!confirmed(strings.confirmShow)) return@launch
            shownPassword.value = entry.id
            hideTimer?.cancel()
            hideTimer = launch {
                delay(SECRET_SECONDS * 1000L)
                shownPassword.value = null
            }
        }
    }

    private fun hidePassword() {
        hideTimer?.cancel()
        shownPassword.value = null
    }

    /**
     * [field] of [entry] onto [clipboard], marked sensitive, and the password off it again a minute
     * later if it is still there (06 §7). No confirmation: copying is the everyday thing, the
     * device's lock was asked on the way in, and nothing appears on the screen.
     */
    fun copyTo(clipboard: SecretClipboard, entry: PasswordEntry, field: Field) {
        val text = when (field) {
            Field.Username -> entry.username
            Field.Password -> entry.password
        }
        if (text.isEmpty()) return
        if (runCatching { clipboard.put(text) }.isFailure) return
        copied.value = Copied(entry.id, field)
        runtime.haptics.tick()
        runtime.analytics.event("passwords_copy", mapOf("field" to field.name.lowercase()))
        if (field == Field.Username) return
        onClipboard = text to clipboard
        clipboardTimer?.cancel()
        clipboardTimer = runtime.scope.launch {
            delay(CLIPBOARD_SECONDS * 1000L)
            clearClipboard()
        }
    }

    /** As in 2FA: Android lets only the app in front read the clipboard, so a late clear happens on return. */
    override fun onForeground() {
        if (onClipboard == null || clipboardTimer?.isActive == true) return
        runtime.scope.launch {
            delay(CLIPBOARD_REFOCUS_MILLIS)
            clearClipboard()
        }
    }

    private fun clearClipboard() {
        val (text, clipboard) = onClipboard ?: return
        val settled = runCatching { clipboard.clearIfStill(text) }.getOrDefault(true)
        if (settled != null) {
            onClipboard = null
            copied.value = null
        }
    }

    private suspend fun confirmed(reason: String): Boolean = presence?.confirm(reason) ?: true

    // ---------------------------------------------------------------- adding and editing

    fun editDraft(change: EntryDraft.() -> EntryDraft) {
        draft.value = draft.value.change()
        draftError.value = null
    }

    fun generate() {
        fill()
        runtime.analytics.event("passwords_generate")
    }

    /**
     * A change of the generator's settings, kept for next time. One that leaves no character set,
     * or words and characters with nothing to put between the words, is refused: a switch of mode
     * must always find something to draw. Switching to words and characters turns digits on when
     * neither digits nor symbols are.
     */
    fun changeGenerator(change: PasswordRules.() -> PasswordRules) {
        val current = generator.value
        var next = current.change().let {
            it.copy(
                length = it.length.coerceIn(PasswordGenerator.LENGTHS),
                words = it.words.coerceIn(PasswordGenerator.WORD_COUNTS),
            )
        }
        if (next.mode == PasswordMode.Mixed && next.joints.isEmpty() && current.mode != PasswordMode.Mixed) next = next.copy(digits = true)
        if (next == current || next.kinds.isEmpty() || next.problem != null) return
        generator.value = next
        runtime.scope.launch { runtime.storage.set(KEY_GENERATOR, GeneratorSettings.encode(next)) }
        if (generated != null && draft.value.password == generated) fill()
    }

    /** One point under the finger or the mouse, in the field's own pixels. */
    fun addPoint(x: Int, y: Int) {
        pool.add(x, y, poolClock.elapsedNow().inWholeNanoseconds)
        val now = runtime.clock.now()
        drawn.value = Drawn(pool.samples, pool.bits.toInt(), drawn.value.startedAt ?: now, now)
    }

    /** Closes the field; a generated password still in the form is made again, now with the drawing in it. */
    fun finishDrawing() {
        drawing.value = false
        if (generated != null && draft.value.password == generated) fill()
        if (pool.samples > 0) runtime.analytics.event("passwords_generate_drawn")
    }

    fun clearDrawing() {
        pool.clear()
        drawn.value = Drawn()
    }

    private fun fill() {
        val random = if (pool.samples > 0) pool.source() else ::secureRandomBytes
        val password = PasswordGenerator.generate(generator.value, random)
        generated = password
        editDraft { copy(password = password) }
    }

    /** Saves the form: a new entry on the add page, the same one changed on the edit page. */
    fun save(): Boolean {
        val editing = (_page.value as? Page.Edit)?.id
        val entry = draft.value.validate(editing ?: newId(), runtime.clock.now()).getOrElse {
            draftError.value = (it as? DraftException)?.error
            return false
        }
        val next = if (editing == null) _entries.value + entry else _entries.value.map { if (it.id == editing) entry else it }
        if (!persist(next)) return false
        runtime.analytics.event(if (editing == null) "passwords_add" else "passwords_edit")
        go(if (editing == null) Page.List else Page.Details(editing))
        return true
    }

    /** Deleting is for good, so it asks the device as well as the button. */
    fun delete(id: String) {
        runtime.scope.launch {
            if (!confirmed(strings.confirmDelete)) return@launch
            if (!persist(_entries.value.filterNot { it.id == id })) return@launch
            runtime.analytics.event("passwords_delete")
            go(Page.List)
        }
    }

    // ---------------------------------------------------------------- import

    /** The pasted text, read into the import page. */
    fun readText(): Boolean = read(importText.value)

    /** Opens the camera; one QR code of the export, or the first of several. */
    fun scan() {
        val camera = scanner ?: return
        if (scanning.value) return
        scanning.value = true
        runtime.scope.launch {
            val text = try {
                camera.scan()
            } finally {
                scanning.value = false
            }
            if (text == null) return@launch
            runtime.analytics.event("passwords_scan")
            if (read(text)) runtime.haptics.tick()
        }
    }

    /**
     * Picks a file: a CSV as it is, or this app's export opened with the person's SSH key — the
     * keyring asks first. The picked bytes are wiped as soon as they are read.
     */
    fun importFile() {
        val picker = files ?: return
        if (opening.value) return
        opening.value = true
        fileProblem.value = null
        runtime.scope.launch {
            try {
                val picked = picker.pick() ?: return@launch
                val text = try {
                    open(picked.bytes)
                } finally {
                    picked.bytes.fill(0)
                } ?: return@launch
                if (!read(text)) {
                    draftError.value = null
                    fileProblem.value = FileProblem.NoEntries
                    return@launch
                }
                runtime.analytics.event("passwords_import_file")
            } finally {
                opening.value = false
            }
        }
    }

    private suspend fun open(bytes: ByteArray): String? {
        // Not an age file (it has no header to read): a CSV, read as it is.
        if (Age.stanzas(bytes) == null) return bytes.decodeToString()
        val keys = keyring ?: return null.also { fileProblem.value = FileProblem.NoKey }
        return when (val opened = keys.decrypt(bytes)) {
            is SshOutcome.Ok -> opened.value.decodeToString().also { opened.value.fill(0) }
            SshOutcome.Cancelled, SshOutcome.Refused -> null
            SshOutcome.NoKey -> null.also { fileProblem.value = FileProblem.NoKey }
            else -> null.also { fileProblem.value = FileProblem.NotOpened }
        }
    }

    /**
     * Reads [text] into the batch — added to it on the import page, a new one anywhere else — and
     * ticks the new entries. False, with the reason in [draftError], when it held none.
     */
    private fun read(text: String): Boolean {
        val before = importBatch.value.takeIf { _page.value == Page.Import } ?: ImportBatch()
        val after = Import.read(text, _entries.value, before)
        if (after == null || after.found.isEmpty() && after.failed.isEmpty()) {
            draftError.value = DraftError.NothingRead
            return false
        }
        importBatch.value = after
        importSelected.value = importSelected.value + after.addable.filter { it >= before.found.size }
        draftError.value = null
        if (_page.value != Page.Import) {
            importText.value = ""
            _page.value = Page.Import
            updateChrome()
        }
        return true
    }

    fun toggleImport(index: Int) {
        val batch = importBatch.value ?: return
        if (index !in batch.addable) return
        importSelected.value = importSelected.value.let { if (index in it) it - index else it + index }
    }

    /** Ticks every row the filter shows that can be added, or unticks them; the hidden ones stay as they are. */
    fun selectShown(on: Boolean) {
        val batch = importBatch.value ?: return
        val shown = batch.shown(importFilter.value).filter { it in batch.addable }
        importSelected.value = if (on) importSelected.value + shown else importSelected.value - shown.toSet()
    }

    /**
     * The ticked entries, in one write. One whose password differs takes the stored entry's place —
     * the same id, the new password — unless [importKeepBoth] is on; a second one for the same
     * stored entry is added beside it, so nothing read is dropped.
     */
    fun importEntries(): Boolean {
        val batch = importBatch.value ?: return false
        val selected = importSelected.value
        val now = runtime.clock.now()
        val chosen = batch.addable.filter { it in selected }.map { batch.found[it] }
        if (chosen.isEmpty()) return false
        val replaced = mutableMapOf<String, PasswordEntry>()
        val added = mutableListOf<PasswordEntry>()
        for (found in chosen) {
            val target = found.replaces?.takeIf { !importKeepBoth.value && it !in replaced && entry(it) != null }
            if (target != null) replaced[target] = found.entry.copy(id = target, changed = now)
            else added += found.entry.copy(id = newId(), changed = now)
        }
        if (!persist(_entries.value.map { replaced[it.id] ?: it } + added)) return false
        runtime.analytics.event(
            "passwords_import",
            mapOf("entries" to added.size.toString(), "replaced" to replaced.size.toString()),
        )
        go(Page.List)
        return true
    }

    // ---------------------------------------------------------------- export

    fun editLogin(text: String) {
        exportLogin.value = text
        if ((exportKeys.value as? KeysState.Loaded)?.login != text.trim()) exportKeys.value = KeysState.Idle
        exportDone.value = null
    }

    fun findKeys() {
        if (exportKeys.value == KeysState.Loading) return
        val login = GitHubKeys.login(exportLogin.value) ?: run {
            exportKeys.value = KeysState.Failed(KeysError.BadLogin)
            return
        }
        exportLogin.value = login
        exportKeys.value = KeysState.Loading
        exportDone.value = null
        runtime.scope.launch {
            val state = GitHubKeys.fetch(runtime.network, login)
            exportKeys.value = state
            if (state is KeysState.Loaded) {
                val saved = savedKeys.value.map { it.key.fingerprint }.toSet()
                exportSelected.value = exportSelected.value.filter { it in saved }.toSet() + state.keys.filter { it.usable }.map { it.key.fingerprint }
                runtime.storage.set(KEY_EXPORT_LOGIN, login)
            }
        }
    }

    fun toggleKey(key: GitHubKey) {
        if (!key.usable) return
        toggleFingerprint(key.key.fingerprint)
    }

    fun toggleSaved(key: KnownKey) = toggleFingerprint(key.key.fingerprint)

    private fun toggleFingerprint(fingerprint: String) {
        exportSelected.value = exportSelected.value.let { if (fingerprint in it) it - fingerprint else it + fingerprint }
        exportDone.value = null
    }

    /** The ticked keys: the saved ones first, then the GitHub account's in the order it listed them. */
    fun exportRecipients(): List<SshPublicKey> {
        val selected = exportSelected.value
        val saved = savedKeys.value.filter { Age.supports(it.key) && it.key.fingerprint in selected }.map { it.key }
        val github = (exportKeys.value as? KeysState.Loaded)?.keys.orEmpty().filter { it.usable && it.key.fingerprint in selected }.map { it.key }
        return (saved + github).distinct()
    }

    private fun exportAudience(recipients: List<SshPublicKey>): String {
        val loaded = exportKeys.value as? KeysState.Loaded
        val github = loaded?.takeIf { l -> l.keys.any { it.key in recipients } }?.let { "github.com/${it.login}" }
        val names = savedKeys.value.filter { it.key in recipients }.map { it.name }
        return (listOfNotNull(github) + names).joinToString(", ")
    }

    fun exportFileName(): String = Export.fileName(runtime.clock.now())

    /** Every entry, encrypted on the device to the ticked keys. The CSV exists only inside this call. */
    fun buildExport(): ExportFile? {
        val recipients = exportRecipients()
        if (recipients.isEmpty() || _entries.value.isEmpty()) return null
        val file = Age.encrypt(Export.plaintext(_entries.value).encodeToByteArray(), recipients)
        return ExportFile(exportFileName(), Age.armor(file), recipients, exportAudience(recipients))
    }

    fun share() {
        val sharer = sharer ?: return
        val file = buildExport() ?: return
        runtime.scope.launch {
            val message = strings.shareMessage(
                file.audience,
                Export.commands(file.recipients, file.name).first(),
                Export.transferCommands(file.recipients, file.name, Export.plaintext(_entries.value)).first(),
            )
            if (sharer.share(file.name, file.text, EXPORT_MIME, message)) {
                exportDone.value = ExportDone(file.name, ExportDone.Via.Shared)
                runtime.analytics.event("passwords_export", mapOf("via" to "share", "keys" to file.recipients.size.toString()))
            }
        }
    }

    /** The encrypted text for the clipboard, for hosts with no share sheet; the screen owns the clipboard. */
    fun copyExport(): String? {
        val file = buildExport() ?: return null
        exportDone.value = ExportDone(file.name, ExportDone.Via.Copied)
        runtime.analytics.event("passwords_export", mapOf("via" to "copy", "keys" to file.recipients.size.toString()))
        return file.text
    }

    // ---------------------------------------------------------------- storage

    /**
     * Shows [entries] and writes them. False, with nothing changed, before the list has been read
     * or over one that could not be: writing would replace what may be somebody's only copy.
     */
    private fun persist(entries: List<PasswordEntry>): Boolean {
        if (!_loaded.value || storageProblem.value?.blocksWriting == true) return false
        _entries.value = entries
        runtime.scope.launch {
            // A write, once started, finishes: closing the app a moment after Save cancels this scope.
            val saved = withContext(NonCancellable) { runCatching { store.save(entries) } }
            storageProblem.value = if (saved.isSuccess) null else StorageProblem.NotSaved
            saved.exceptionOrNull()?.let { runtime.logger.warn("Passwords not saved: the vault refused", it) }
        }
        return true
    }

    private fun newId(): String = runtime.clock.now().toString(36) + "-" + Random.nextInt(0, Int.MAX_VALUE).toString(36)

    // ---------------------------------------------------------------- lifecycle

    /**
     * The page, the search and the names typed into a new entry. Never a password or a note (which
     * is where recovery codes end up): a snapshot is session state, not the vault. An edit comes
     * back as the entry's page — its form would need the stored password, and that asks first.
     */
    override fun saveState(): JsonObject = buildJsonObject {
        when (val current = _page.value) {
            Page.List -> put("page", JsonPrimitive("list"))
            Page.Add -> put("page", JsonPrimitive("add"))
            Page.Export -> put("page", JsonPrimitive("export"))
            Page.ImportFrom, Page.Import -> put("page", JsonPrimitive("import"))
            Page.Computers -> put("page", JsonPrimitive("computers"))
            is Page.Details, is Page.Edit -> {
                put("page", JsonPrimitive("details"))
                put("id", JsonPrimitive((current as? Page.Details)?.id ?: (current as Page.Edit).id))
            }
        }
        put("query", JsonPrimitive(query.value))
        put("github", JsonPrimitive(exportLogin.value))
        if (_page.value == Page.Add) {
            put("title", JsonPrimitive(draft.value.title))
            put("username", JsonPrimitive(draft.value.username))
            put("url", JsonPrimitive(draft.value.url))
        }
    }

    override fun restore(state: JsonObject) {
        fun text(key: String) = state[key]?.jsonPrimitive?.content
        text("query")?.let { query.value = it }
        text("github")?.let { exportLogin.value = it }
        draft.value = EntryDraft(title = text("title").orEmpty(), username = text("username").orEmpty(), url = text("url").orEmpty())
        _page.value = when (text("page")) {
            "add" -> Page.Add
            "export" -> Page.Export
            "import" -> Page.ImportFrom
            "computers" -> Page.Computers
            "details" -> text("id")?.let { Page.Details(it) } ?: Page.List
            else -> Page.List
        }
        settlePage()
    }

    /** The host closed the app (or the idle exit did): the phone stops listening at once (D538). */
    override fun dispose() {
        browser.close()
    }

    @Composable
    override fun Content() {
        PasswordsScreen(this)
    }
}
