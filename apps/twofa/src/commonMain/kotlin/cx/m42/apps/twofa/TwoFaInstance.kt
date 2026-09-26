package cx.m42.apps.twofa

import androidx.compose.runtime.Composable
import cx.m42.services.qr.QrScanner
import cx.m42.services.share.FileSharer
import cx.m42.services.ssh.Age
import cx.m42.services.ssh.SshPublicKey
import cx.m42.superizer.secrets.SecretVault
import cx.m42.superizer.app.AppChrome
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.lock.UserPresence
import cx.m42.superizer.runtime.InstanceRuntime
import kotlin.random.Random
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Where the app is inside itself. The host's frame only ever sees the title this implies. */
internal sealed interface Page {
    data object List : Page
    data object Add : Page
    data class Details(val id: String) : Page
    data object Export : Page

    /** Many links at once — an export opened on a computer, shown as a QR code — before they are added. */
    data object Import : Page
}

/** How the add form is being filled: from a pasted link, or field by field. */
internal enum class AddMode { Link, Key }

/** Why the list on screen may not be what is stored. */
internal enum class StorageProblem {
    /** There is a stored list and it cannot be opened here; it is left exactly as it is. */
    Unreadable,

    /**
     * The host holds the list and cannot open it right now — its passphrase was not given (desktop,
     * the web: ssh-new 05 §3.4). Nothing is lost; unlocking reads it again.
     */
    Locked,

    /** The last change could not be sealed, so it was not written; it lives until the app closes. */
    NotSaved,
    ;

    /** A stored list is there and not on screen: writing now would replace it. */
    val blocksWriting: Boolean get() = this == Unreadable || this == Locked
}

/** The GitHub login the last export went to — a public name, remembered so a second export is one tap. */
internal const val KEY_EXPORT_LOGIN = "export.github"

/** What the export is shared as: armored age is text, but no app should try to open it as text. */
internal const val EXPORT_MIME = "application/octet-stream"

/**
 * How long a copied code stays on the clipboard. Long enough to switch apps and paste; a code is
 * dead within a minute or so anyway, so what this removes is mostly a keyboard's copy of it.
 */
internal const val CLIPBOARD_SECONDS = 60

private const val CLIPBOARD_REFOCUS_MILLIS = 1_000L

/**
 * One open 2FA app.
 *
 * State is flows on the instance rather than `remember` in the screen, for the two reasons the
 * converter has: a snapshot can carry a half-filled form across a process death, and a test can
 * drive the logic without composing anything.
 */
internal class TwoFaInstance(
    val runtime: InstanceRuntime,
    val config: TwoFaConfig,
) : AppInstance() {

    private val strings: TwoFaStrings get() = runtime.locale.pick(TwoFaStringTables, TwoFaStringsEn)

    private val _accounts = MutableStateFlow<List<TotpAccount>>(emptyList())
    val accounts: StateFlow<List<TotpAccount>> get() = _accounts.asStateFlow()

    /**
     * In `runtime.secrets`: sealed by the host with whatever the platform has (contract 3). What an
     * older version sealed itself is moved in on the first read, with the vault it used (Б4).
     */
    private val store = AccountStore(runtime.storage, runtime.secrets, runtime.service(SecretVault.Key), runtime.logger)

    /** False until the stored list has been read; the screen draws nothing rather than "no accounts yet". */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> get() = _loaded.asStateFlow()

    val storageProblem = MutableStateFlow<StorageProblem?>(null)

    private val _page = MutableStateFlow<Page>(Page.List)
    val page: StateFlow<Page> get() = _page.asStateFlow()

    /** The clock as of the last tick; the screen advances it once a second. */
    private val _now = MutableStateFlow(runtime.clock.now())
    val now: StateFlow<Long> get() = _now.asStateFlow()

    val query = MutableStateFlow("")

    val addMode = MutableStateFlow(AddMode.Link)
    val link = MutableStateFlow("")
    val draft = MutableStateFlow(AccountDraft())
    val draftError = MutableStateFlow<DraftError?>(null)

    /**
     * The camera, where the host has one (D19): Android registers it, desktop and the web do not,
     * and the scan button is simply not drawn there. The pasted link is the same text either way.
     */
    private val scanner: QrScanner? = runtime.service(QrScanner.Key)
    val canScan: Boolean get() = scanner != null

    /** While the scanner is on screen, so a second tap does not open a second one. */
    val scanning = MutableStateFlow(false)

    /**
     * The links being imported, and which of them are ticked. Only in memory, and only while the
     * import page is open: like a key typed into the form, they are not the person's until added.
     */
    val importBatch = MutableStateFlow<ImportBatch?>(null)
    val importSelected = MutableStateFlow<Set<Int>>(emptySet())

    /** The export page: a GitHub login, the keys it has, which of them to encrypt to. */
    val exportLogin = MutableStateFlow("")
    val exportKeys = MutableStateFlow<KeysState>(KeysState.Idle)
    val exportSelected = MutableStateFlow<Set<String>>(emptySet())

    /** The name of the file last handed over, and how — the page says so under the buttons. */
    val exportDone = MutableStateFlow<ExportDone?>(null)

    /**
     * The share sheet, where the host has one (D19). Without it the export still works: the
     * encrypted text goes to the clipboard instead.
     */
    private val sharer: FileSharer? = runtime.service(FileSharer.Key)
    val canShare: Boolean get() = sharer != null

    /** Which account's code was copied last, so the row can say so. */
    val copiedId = MutableStateFlow<String?>(null)

    /**
     * The host's "is this still the owner?" (06 §5.5), asked before the three actions that give
     * the accounts away or take one from its owner: Export, Show key and Delete. Export is the one
     * that matters most — a minute alone with the unlocked phone is enough to type one's own GitHub
     * login and send oneself every secret at once.
     *
     * None on desktop and the web, which have nothing to ask with; the actions then work as before.
     */
    private val presence: UserPresence? = runtime.service(UserPresence.Key)

    /** The account whose key is on screen, if any. Never in a snapshot: it is the secret itself. */
    val shownSecret = MutableStateFlow<String?>(null)

    /** The last code copied and the clipboard it went to, until it has been cleared from it. */
    private var onClipboard: Pair<String, CodeClipboard>? = null
    private var clipboardTimer: Job? = null

    private val _chrome = MutableStateFlow(AppChrome())
    override val chrome: StateFlow<AppChrome> get() = _chrome.asStateFlow()

    override suspend fun onLaunch() {
        runtime.analytics.screen("2fa")
        // Only into an empty field: a snapshot restored before this ran has the newer value.
        if (exportLogin.value.isEmpty()) exportLogin.value = runtime.storage.get(KEY_EXPORT_LOGIN).orEmpty()
        // In the instance's scope rather than here. Opening the list is a trip to the Keystore, so
        // this would suspend, and a launch that suspends can be cancelled under it: Superizer
        // 0.2.0's link route cancels its own launch when it clears the link it is handling.
        runtime.scope.launch { load() }
    }

    private suspend fun load() {
        val loaded = store.load()
        val stored = (loaded as? AccountStore.Loaded.Ok)?.accounts
        _accounts.value = stored.orEmpty()
        storageProblem.value = when (loaded) {
            is AccountStore.Loaded.Ok -> null
            AccountStore.Loaded.Locked -> StorageProblem.Locked
            AccountStore.Loaded.Unreadable -> StorageProblem.Unreadable
        }
        _loaded.value = true
        settlePage()

        // A link handed in by config opens the form, filled in, and stops there (see TwoFaConfig).
        // Not over a list that cannot be read: there would be nowhere to save it.
        config.otpauth?.takeIf { stored != null }?.let { offered ->
            startAdd()
            addMode.value = AddMode.Link
            link.value = offered
            readLink()
        }
    }

    /**
     * Checks a page restored before the list was read against the list, once it has been: an
     * account deleted since the snapshot, or a list that cannot be read, leaves the list page.
     */
    private fun settlePage() {
        if (!_loaded.value) return
        val current = _page.value
        val unreadable = storageProblem.value?.blocksWriting == true
        if (unreadable && current != Page.List || current is Page.Details && _accounts.value.none { it.id == current.id }) {
            _page.value = Page.List
        }
        updateChrome()
    }

    /** "Unlock": read the list again — the host asks for the passphrase this time round. */
    fun unlock() {
        runtime.scope.launch { load() }
    }

    fun tick() {
        _now.value = runtime.clock.now()
    }

    fun visibleAccounts(): List<TotpAccount> {
        val q = query.value.trim()
        if (q.isEmpty()) return _accounts.value
        return _accounts.value.filter { it.issuer.contains(q, ignoreCase = true) || it.name.contains(q, ignoreCase = true) }
    }

    fun codeOf(account: TotpAccount): String = Totp.code(account, _now.value)

    /** Marks [account] copied and returns the code to put on the clipboard; the screen owns the clipboard. */
    fun copy(account: TotpAccount): String {
        tick()
        copiedId.value = account.id
        runtime.haptics.tick()
        runtime.analytics.event("2fa_copy")
        return codeOf(account)
    }

    // ---------------------------------------------------------------- navigation

    fun startAdd() {
        // The link first: it is what a site's QR code holds, and it fills every field at once.
        addMode.value = AddMode.Link
        link.value = ""
        draft.value = AccountDraft()
        draftError.value = null
        go(Page.Add)
    }

    fun openDetails(account: TotpAccount) = go(Page.Details(account.id))

    fun startExport() {
        runtime.scope.launch {
            if (!confirmed(strings.confirmExport)) return@launch
            exportKeys.value = KeysState.Idle
            exportDone.value = null
            go(Page.Export)
        }
    }

    /** Shows [account]'s key once the person has confirmed; hiding it asks nothing. */
    fun toggleSecret(account: TotpAccount) {
        if (shownSecret.value == account.id) {
            shownSecret.value = null
            return
        }
        runtime.scope.launch {
            if (confirmed(strings.confirmShowKey)) shownSecret.value = account.id
        }
    }

    /**
     * True when the host confirmed the person, or has nothing to confirm with. A refused or
     * cancelled sheet is simply nothing happening: the page stays as it was.
     */
    private suspend fun confirmed(reason: String): Boolean = presence?.confirm(reason) ?: true

    /**
     * [account]'s code onto [clipboard], marked sensitive, and off it again a minute later if it is
     * still there (06 §7). The clipboard is the screen's; this only decides when.
     */
    fun copyTo(clipboard: CodeClipboard, account: TotpAccount) {
        val code = copy(account)
        // Headless desktop and a browser without clipboard permission both throw here; the code is
        // on the screen either way.
        if (runCatching { clipboard.put(code) }.isFailure) return
        onClipboard = code to clipboard
        clipboardTimer?.cancel()
        clipboardTimer = runtime.scope.launch {
            delay(CLIPBOARD_SECONDS * 1000L)
            clearClipboard()
        }
    }

    /**
     * Android lets only the app in front read the clipboard, and a code is copied to be pasted
     * somewhere else — so the minute usually runs out with this app behind another. Then it is
     * settled on the way back instead.
     */
    override fun onForeground() {
        if (onClipboard == null || clipboardTimer?.isActive == true) return
        runtime.scope.launch {
            // Coming to the front and having the window's focus are a moment apart on Android.
            delay(CLIPBOARD_REFOCUS_MILLIS)
            clearClipboard()
        }
    }

    private fun clearClipboard() {
        val (code, clipboard) = onClipboard ?: return
        val settled = runCatching { clipboard.clearIfStill(code) }.getOrDefault(true)
        if (settled != null) onClipboard = null
    }

    /** Back inside the app. False at the root, which is where the host takes over and closes it. */
    fun back(): Boolean {
        if (_page.value == Page.List) return false
        go(Page.List)
        return true
    }

    private fun go(page: Page) {
        _page.value = page
        copiedId.value = null
        shownSecret.value = null
        if (page != Page.Import) {
            importBatch.value = null
            importSelected.value = emptySet()
        }
        updateChrome()
    }

    private fun updateChrome() {
        _chrome.value = when (val current = _page.value) {
            Page.List -> AppChrome()
            Page.Add -> AppChrome(title = strings.newAccount, canGoBack = true)
            Page.Export -> AppChrome(title = strings.exportTitle, canGoBack = true)
            Page.Import -> AppChrome(title = strings.importTitle, canGoBack = true)
            is Page.Details -> AppChrome(
                title = _accounts.value.firstOrNull { it.id == current.id }?.title,
                canGoBack = true,
            )
        }
    }

    // ---------------------------------------------------------------- adding

    fun setMode(mode: AddMode) {
        addMode.value = mode
        draftError.value = null
    }

    fun editDraft(change: AccountDraft.() -> AccountDraft) {
        draft.value = draft.value.change()
        draftError.value = null
    }

    /**
     * Parses the pasted link into the form. On success the form shows what it read, for Save. More
     * than one link — a whole export — goes to the import page instead, all of them at once.
     */
    fun readLink(): Boolean {
        val links = Import.links(link.value)
        if (links.size > 1) {
            startImport(link.value)
            return true
        }
        OtpAuthUri.parse(links.singleOrNull() ?: link.value)
            .onSuccess {
                draft.value = it
                draftError.value = null
                addMode.value = AddMode.Key
                return true
            }
            .onFailure { draftError.value = (it as? DraftException)?.error ?: DraftError.NotOtpAuth }
        return false
    }

    /**
     * Opens the camera; what it reads goes through [readLink] exactly as a pasted link would.
     * Closing the scanner changes nothing. Text that is not a TOTP link stays in the field with the
     * reason under it, so the person can see what the camera actually read.
     */
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
            runtime.analytics.event("2fa_scan")
            // The next part of an export that did not fit in one code: added to what is there.
            if (_page.value == Page.Import) {
                if (addToImport(text)) runtime.haptics.tick()
                return@launch
            }
            addMode.value = AddMode.Link
            link.value = text.trim()
            if (readLink()) runtime.haptics.tick()
        }
    }

    /** The code the draft would show right now, or null while it cannot make one. */
    fun previewCode(now: Long = _now.value): String? =
        draft.value.validate("preview").getOrNull()?.let { Totp.code(it, now) }

    fun save(): Boolean {
        val account = draft.value.validate(newId()).getOrElse {
            draftError.value = (it as? DraftException)?.error
            return false
        }
        if (!persist(_accounts.value + account)) return false
        runtime.analytics.event("2fa_add", mapOf("algorithm" to account.algorithm.name))
        go(Page.List)
        return true
    }

    // ---------------------------------------------------------------- import

    private fun startImport(text: String) {
        importBatch.value = null
        importSelected.value = emptySet()
        draftError.value = null
        addToImport(text)
        // The links live in the batch now, and go with it; the field would outlast it.
        link.value = ""
        go(Page.Import)
    }

    /**
     * Reads [text]'s links into the batch and ticks the new ones this device does not have yet.
     * False, with the reason in [draftError], when there was no link in it at all.
     */
    private fun addToImport(text: String): Boolean {
        if (Import.links(text).isEmpty()) {
            draftError.value = DraftError.NotOtpAuth
            return false
        }
        val before = importBatch.value ?: ImportBatch()
        val after = Import.read(text, _accounts.value, before)
        importBatch.value = after
        importSelected.value = importSelected.value + after.addable.filter { it >= before.found.size }
        draftError.value = null
        return true
    }

    fun toggleImport(index: Int) {
        val batch = importBatch.value ?: return
        if (index !in batch.addable) return
        importSelected.value = importSelected.value.let { if (index in it) it - index else it + index }
    }

    /** The ticked accounts, added in one write, in the order the links came. */
    fun importAccounts(): Boolean {
        val batch = importBatch.value ?: return false
        val selected = importSelected.value
        val chosen = batch.addable.filter { it in selected }.map { batch.found[it].account.copy(id = newId()) }
        if (chosen.isEmpty()) return false
        if (!persist(_accounts.value + chosen)) return false
        runtime.analytics.event("2fa_import", mapOf("accounts" to chosen.size.toString()))
        go(Page.List)
        return true
    }

    // ---------------------------------------------------------------- details

    fun rename(id: String, issuer: String, name: String): Boolean {
        if (issuer.isBlank() && name.isBlank()) return false
        if (!persist(_accounts.value.map { if (it.id == id) it.copy(issuer = issuer.trim(), name = name.trim()) else it })) return false
        updateChrome()
        return true
    }

    /** Deleting someone's account cuts them off from wherever it signs in, so it asks first. */
    fun delete(id: String) {
        runtime.scope.launch {
            if (!confirmed(strings.confirmDelete)) return@launch
            if (!persist(_accounts.value.filterNot { it.id == id })) return@launch
            runtime.analytics.event("2fa_delete")
            go(Page.List)
        }
    }

    // ---------------------------------------------------------------- export

    fun editLogin(text: String) {
        exportLogin.value = text
        // The keys on screen belong to the login that fetched them; a different name hides them
        // rather than leaving someone else's keys ticked under it.
        if ((exportKeys.value as? KeysState.Loaded)?.login != text.trim()) exportKeys.value = KeysState.Idle
        exportDone.value = null
    }

    /** Asks GitHub for the login's keys and ticks every one age can use. */
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
                exportSelected.value = state.keys.filter { it.usable }.map { it.key.fingerprint }.toSet()
                runtime.storage.set(KEY_EXPORT_LOGIN, login)
            }
        }
    }

    fun toggleKey(key: GitHubKey) {
        if (!key.usable) return
        val fingerprint = key.key.fingerprint
        exportSelected.value = exportSelected.value.let { if (fingerprint in it) it - fingerprint else it + fingerprint }
        exportDone.value = null
    }

    /** The ticked keys, in the order GitHub listed them. */
    fun exportRecipients(): List<SshPublicKey> {
        val loaded = exportKeys.value as? KeysState.Loaded ?: return emptyList()
        val selected = exportSelected.value
        return loaded.keys.filter { it.usable && it.key.fingerprint in selected }.map { it.key }
    }

    fun exportFileName(): String = Export.fileName(runtime.clock.now())

    /** What to type on the computer, one line per kind of key ticked. */
    fun decryptCommands(): List<String> = Export.commands(exportRecipients(), exportFileName())

    /**
     * Every account, encrypted on the device to the ticked keys. Null while none is ticked. The
     * plain links exist only inside this call — never in storage, a snapshot or a log.
     */
    fun buildExport(): ExportFile? {
        val loaded = exportKeys.value as? KeysState.Loaded ?: return null
        val recipients = exportRecipients()
        if (recipients.isEmpty() || _accounts.value.isEmpty()) return null
        val file = Age.encrypt(Export.plaintext(_accounts.value).encodeToByteArray(), recipients)
        return ExportFile(exportFileName(), Age.armor(file), recipients, loaded.login)
    }

    /** Encrypts and opens the share sheet, with the command that opens the file as its message. */
    fun share() {
        val sharer = sharer ?: return
        val file = buildExport() ?: return
        runtime.scope.launch {
            val message = strings.shareMessage(
                file.login,
                Export.commands(file.recipients, file.name).first(),
                Export.transferCommands(file.recipients, file.name, Export.plaintext(_accounts.value)).first(),
            )
            if (sharer.share(file.name, file.text, EXPORT_MIME, message)) {
                exportDone.value = ExportDone(file.name, ExportDone.Via.Shared)
                runtime.analytics.event("2fa_export", mapOf("via" to "share", "keys" to file.recipients.size.toString()))
            }
        }
    }

    /** The encrypted text for the clipboard, for hosts with no share sheet; the screen owns the clipboard. */
    fun copyExport(): String? {
        val file = buildExport() ?: return null
        exportDone.value = ExportDone(file.name, ExportDone.Via.Copied)
        runtime.analytics.event("2fa_export", mapOf("via" to "copy", "keys" to file.recipients.size.toString()))
        return file.text
    }

    /**
     * Shows [accounts] and writes them. False, with nothing changed, before the list has been read
     * or over one that could not be: writing would replace what may be somebody's only copy of
     * their keys.
     */
    private fun persist(accounts: List<TotpAccount>): Boolean {
        if (!_loaded.value || storageProblem.value?.blocksWriting == true) return false
        _accounts.value = accounts
        runtime.scope.launch {
            // A write, once started, finishes: closing the app a moment after Save cancels this
            // scope, and a change the screen showed as made must not be lost to that.
            val saved = withContext(NonCancellable) { runCatching { store.save(accounts) } }
            storageProblem.value = if (saved.isSuccess) null else StorageProblem.NotSaved
            saved.exceptionOrNull()?.let { runtime.logger.warn("2FA accounts not saved: the vault refused", it) }
        }
        return true
    }

    private fun newId(): String = runtime.clock.now().toString(36) + "-" + Random.nextInt(0, Int.MAX_VALUE).toString(36)

    // ---------------------------------------------------------------- lifecycle

    /**
     * The page and a half-filled form. Never the secret: a snapshot is written where the host
     * keeps session state, and a key typed but not saved has not been agreed to be kept.
     */
    override fun saveState(): JsonObject = buildJsonObject {
        when (val current = _page.value) {
            Page.List -> put("page", JsonPrimitive("list"))
            Page.Add -> put("page", JsonPrimitive("add"))
            // The links themselves are not kept (see importBatch): back to the form, to scan again.
            Page.Import -> put("page", JsonPrimitive("add"))
            Page.Export -> put("page", JsonPrimitive("export"))
            is Page.Details -> {
                put("page", JsonPrimitive("details"))
                put("id", JsonPrimitive(current.id))
            }
        }
        put("query", JsonPrimitive(query.value))
        // The login, not the keys: they are GitHub's to answer again, and may have changed.
        put("github", JsonPrimitive(exportLogin.value))
        put("mode", JsonPrimitive(addMode.value.name))
        put("issuer", JsonPrimitive(draft.value.issuer))
        put("name", JsonPrimitive(draft.value.name))
        put("digits", JsonPrimitive(draft.value.digits))
        put("period", JsonPrimitive(draft.value.period))
        put("algorithm", JsonPrimitive(draft.value.algorithm.name))
    }

    override fun restore(state: JsonObject) {
        state["query"]?.jsonPrimitive?.content?.let { query.value = it }
        state["github"]?.jsonPrimitive?.content?.let { exportLogin.value = it }
        state["mode"]?.jsonPrimitive?.content?.let { name -> AddMode.entries.firstOrNull { it.name == name } }
            ?.let { addMode.value = it }
        draft.value = AccountDraft(
            issuer = state["issuer"]?.jsonPrimitive?.content.orEmpty(),
            name = state["name"]?.jsonPrimitive?.content.orEmpty(),
            digits = state["digits"]?.jsonPrimitive?.intOrNull ?: 6,
            period = state["period"]?.jsonPrimitive?.intOrNull ?: 30,
            algorithm = state["algorithm"]?.jsonPrimitive?.content?.let { HashAlgorithm.parseOrNull(it) }
                ?: HashAlgorithm.SHA1,
        )
        _page.value = when (state["page"]?.jsonPrimitive?.content) {
            "add" -> Page.Add
            "export" -> Page.Export
            "details" -> state["id"]?.jsonPrimitive?.content?.let { Page.Details(it) } ?: Page.List
            else -> Page.List
        }
        // Checked against the list now if it has been read, and when it has been otherwise.
        settlePage()
    }

    @Composable
    override fun Content() {
        TwoFaScreen(this)
    }
}
