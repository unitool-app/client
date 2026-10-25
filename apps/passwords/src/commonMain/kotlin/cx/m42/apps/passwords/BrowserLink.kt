package cx.m42.apps.passwords

import cx.m42.services.link.LinkChannel
import cx.m42.services.link.LinkListener
import cx.m42.services.link.LinkPipe
import cx.m42.services.link.LinkResponder
import cx.m42.services.link.LinkState
import cx.m42.services.link.LocalLink
import cx.m42.services.qr.QrScanner
import cx.m42.services.ssh.Age
import cx.m42.services.ssh.SshPublicKey
import cx.m42.superizer.runtime.HostLifecycle
import cx.m42.superizer.runtime.InstanceRuntime
import cx.m42.superizer.runtime.Platform
import cx.m42.unitool.kit.crypto.Hash
import cx.m42.unitool.kit.crypto.secureRandomBytes
import cx.m42.unitool.kit.link.LinkMessage
import cx.m42.unitool.kit.link.NoiseKeyPair
import cx.m42.unitool.kit.link.PairingCode
import cx.m42.unitool.kit.link.PasswordBox
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's side of "a password from the phone into the computer's browser"
 * (.ai-notes/passwords-browser): the paired computers, pairing one by its QR code, and the sheet a
 * request opens.
 *
 * The phone listens only while this app is on screen, in front, unlocked and with a computer paired
 * or being paired (D538); [screenShown] and the host's lifecycle say when. Every password is the
 * person's choice on the sheet and the device's confirmation (D544), sealed in age to the
 * computer's SSH key (D542). Nothing a computer sends is shown as fact without saying it is the
 * computer's word.
 */
internal class BrowserLink(
    private val runtime: InstanceRuntime,
    private val link: LocalLink?,
    private val scanner: QrScanner?,
    /** The entries as loaded, or null when the list is not open (locked, unreadable, not read yet). */
    private val entries: () -> List<PasswordEntry>?,
    /** The device's "is this still the owner?" — null where there is none. */
    private val confirm: suspend (String) -> Boolean,
    private val strings: () -> BrowserStrings,
) {
    val available: Boolean get() = link != null
    val canPair: Boolean get() = link != null && scanner != null

    private val store = ComputerStore(runtime.secrets)
    private val changing = Mutex()

    private val _computers = MutableStateFlow<List<Computer>>(emptyList())
    val computers: StateFlow<List<Computer>> get() = _computers.asStateFlow()

    /** False until read, and while the stored list cannot be opened — then it is not written over. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> get() = _ready.asStateFlow()

    private val _pairing = MutableStateFlow<Pairing?>(null)
    val pairing: StateFlow<Pairing?> get() = _pairing.asStateFlow()

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> get() = _request.asStateFlow()

    private val _listening = MutableStateFlow<LinkState?>(null)
    val listening: StateFlow<LinkState?> get() = _listening.asStateFlow()

    private var shown = false
    private var listener: LinkListener? = null
    private var listenerState: Job? = null
    private var phone: NoiseKeyPair? = null
    private var pairingTimer: Job? = null
    private val seen = ArrayDeque<String>()

    sealed interface Pairing {
        /** Scanned: "Pair laptop? SHA256:…" (D537). */
        class Confirm(val code: PairingCode, val sshKey: SshPublicKey) : Pairing
        /** Listening under the code's name for the computer to call (01 §2). */
        class Waiting(val code: PairingCode, val sshKey: SshPublicKey) : Pairing
        data class Paired(val name: String) : Pairing
        data class Failed(val problem: PairProblem) : Pairing
    }

    enum class PairProblem { NotACode, KeyNotSupported, TimedOut, NoNetwork, NotSaved, Failed }

    /** One open sheet (README §5). */
    class Request internal constructor(
        val computerKey: String,
        val computerName: String,
        val browser: String,
        val origin: String,
        /** ASCII, as the origin has it. */
        val host: String,
        /** The same host with Unicode labels; equal to [host] when there were none. */
        val unicodeHost: String,
        /** The entries of the requested site (eTLD+1), by id. */
        val matches: List<String>,
        val selected: String?,
        val query: String,
        val searching: Boolean,
        val deadline: Long,
        internal val decision: CompletableDeferred<Decision>,
    ) {
        internal fun copy(selected: String? = this.selected, query: String = this.query, searching: Boolean = this.searching) =
            Request(computerKey, computerName, browser, origin, host, unicodeHost, matches, selected, query, searching, deadline, decision)
    }

    internal sealed interface Decision {
        class Fill(val entry: PasswordEntry) : Decision
        data object Deny : Decision
        data object Timeout : Decision
        data object Gone : Decision
    }

    // ---------------------------------------------------------------- lifecycle

    suspend fun load() {
        if (link == null) return
        val loaded = store.load()
        _computers.value = loaded.orEmpty()
        _ready.value = loaded != null
        phone = if (loaded.isNullOrEmpty()) null else store.linkKey(create = false)
        runtime.scope.launch { runtime.lifecycle.collect { update() } }
        update()
    }

    /** The screen is composed (true) or gone (false). */
    fun screenShown(value: Boolean) {
        shown = value
        update()
    }

    /** Opens or closes the listener so it runs exactly when D538 says, under the names that are current. */
    private fun update() {
        val waiting = _pairing.value as? Pairing.Waiting
        val names = _computers.value.map { it.linkId } + listOfNotNull(waiting?.code?.pairingInstance)
        val want = link != null && shown && _ready.value && phone != null && names.isNotEmpty() &&
            runtime.lifecycle.value == HostLifecycle.Foreground
        val current = listener
        when {
            want && current == null -> {
                val opened = link!!.open(runtime.scope, names) { pipe -> serve(pipe) }
                listener = opened
                listenerState = runtime.scope.launch {
                    opened.state.collect { state ->
                        _listening.value = state
                        if (state is LinkState.Failed && _pairing.value is Pairing.Waiting) _pairing.value = Pairing.Failed(PairProblem.NoNetwork)
                    }
                }
            }
            want && current != null -> current.rename(names)
            !want && current != null -> {
                current.close()
                listener = null
                listenerState?.cancel()
                _listening.value = null
                // The socket is gone; an open sheet has nobody to answer.
                _request.value?.decision?.complete(Decision.Gone)
            }
        }
    }

    fun close() {
        shown = false
        _pairing.value = null
        update()
    }

    // ---------------------------------------------------------------- pairing (D537, 01 §2)

    fun pair() {
        val camera = scanner ?: return
        runtime.scope.launch {
            val text = camera.scan() ?: return@launch
            _pairing.value = scanned(text)
        }
    }

    /** What a scanned text becomes: the question, or why there is none. */
    internal fun scanned(text: String): Pairing {
        val code = PairingCode.parse(text) ?: return Pairing.Failed(PairProblem.NotACode)
        val key = SshPublicKey.fromBlob(code.sshKey)
        if (key == null || !Age.supports(key)) return Pairing.Failed(PairProblem.KeyNotSupported)
        return Pairing.Confirm(code, key)
    }

    fun confirmPairing() {
        val asked = _pairing.value as? Pairing.Confirm ?: return
        runtime.scope.launch {
            if (!_ready.value) {
                _pairing.value = Pairing.Failed(PairProblem.NotSaved)
                return@launch
            }
            phone = phone ?: runCatching { store.linkKey(create = true) }.getOrNull()
            if (phone == null) {
                _pairing.value = Pairing.Failed(PairProblem.NotSaved)
                return@launch
            }
            _pairing.value = Pairing.Waiting(asked.code, asked.sshKey)
            update()
            pairingTimer?.cancel()
            pairingTimer = runtime.scope.launch {
                delay(PAIRING_MILLIS)
                if (_pairing.value is Pairing.Waiting) {
                    _pairing.value = Pairing.Failed(PairProblem.TimedOut)
                    update()
                }
            }
        }
    }

    fun dismissPairing() {
        pairingTimer?.cancel()
        _pairing.value = null
        update()
    }

    private suspend fun finishPairing(channel: LinkChannel, waiting: Pairing.Waiting) {
        val linkId = secureRandomBytes(8)
        channel.send(LinkMessage.Welcome(phoneName(), linkId))
        if (channel.receive(MESSAGE_MILLIS)?.message !is LinkMessage.Paired) return
        val code = waiting.code
        val computer = Computer(
            key = Base64.encode(code.computerKey),
            name = code.name,
            ssh = Base64.encode(code.sshKey),
            linkId = PairingCode.linkInstance(linkId),
            pairedAt = runtime.clock.now(),
        )
        val saved = change { list -> list.filterNot { it.key == computer.key } + computer }
        pairingTimer?.cancel()
        _pairing.value = if (saved) Pairing.Paired(code.name) else Pairing.Failed(PairProblem.NotSaved)
        runtime.analytics.event("passwords_computer_paired")
        runtime.scope.launch { update() }
    }

    /** What the computer is told this phone is called (D562): Superizer knows no device model. */
    private fun phoneName(): String = when (runtime.host.platform) {
        Platform.Android -> "Unitool · Android"
        Platform.Ios -> "Unitool · iPhone"
        Platform.Desktop -> "Unitool · Desktop"
        Platform.Web -> "Unitool"
    }

    fun forget(computer: Computer) {
        runtime.scope.launch {
            if (!confirm(strings().confirmForget(computer.name))) return@launch
            change { list -> list.filterNot { it.key == computer.key } }
            runtime.analytics.event("passwords_computer_forgotten")
            update()
        }
    }

    /** One change to the list, written through the host; false (and nothing changed) when it is not open. */
    private suspend fun change(edit: (List<Computer>) -> List<Computer>): Boolean = changing.withLock {
        if (!_ready.value) return@withLock false
        val next = edit(_computers.value)
        val ok = withContext(NonCancellable) { runCatching { store.save(next) }.isSuccess }
        if (ok) _computers.value = next
        ok
    }

    // ---------------------------------------------------------------- a connection

    private suspend fun serve(pipe: LinkPipe) {
        val key = phone ?: return
        val waiting = _pairing.value as? Pairing.Waiting
        val expected = LinkResponder.Expected(
            phone = key,
            computers = _computers.value.map { it.keyBytes },
            pairingPsk = waiting?.code?.psk,
            pairingComputer = waiting?.code?.computerKey,
        )
        when (val accepted = LinkResponder(pipe).accept(expected)) {
            is LinkResponder.Accepted.Pairing -> accepted.channel.use { finishPairing(it, waiting!!) }
            is LinkResponder.Accepted.Session -> accepted.channel.use { serveRequest(it, accepted.computerKey) }
            null -> Unit
        }
    }

    /** PW_REQ → the sheet → PW_RES (README §4, 01 §3–§4). */
    private suspend fun serveRequest(channel: LinkChannel, computerKey: ByteArray) {
        val keyText = Base64.encode(computerKey)
        val computer = _computers.value.firstOrNull { it.key == keyText } ?: return
        val received = channel.receive(MESSAGE_MILLIS) ?: return
        val req = received.message as? LinkMessage.PasswordRequest ?: return
        val now = runtime.clock.now()
        val refusal = when {
            req.version != 1 -> LinkMessage.VERSION_CODE
            abs(now - req.issuedAt) > CLOCK_SKEW_MILLIS -> LinkMessage.CLOCK
            !remember(req.id) -> LinkMessage.REPLAY
            Sites.originHost(req.origin) == null -> LinkMessage.ORIGIN
            entries() == null -> LinkMessage.LOCKED
            _request.value.let { it != null && it.computerKey != keyText } -> LinkMessage.BUSY
            else -> null
        }
        val host = Sites.originHost(req.origin)
        if (refusal != null) {
            channel.send(LinkMessage.Error(refusal))
            journal(computer, host ?: req.origin.take(64), Outcome.Refused)
            return
        }
        host!!
        // A new request from the same computer replaces the one on screen (README §4).
        _request.value?.decision?.complete(Decision.Timeout)
        val list = entries().orEmpty()
        val matches = list.filter { Sites.sameSite(it, host) }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.shownTitle }).map { it.id }
        val decision = CompletableDeferred<Decision>()
        val sheet = Request(
            computerKey = keyText,
            computerName = computer.name,
            browser = req.browser.take(80),
            origin = req.origin,
            host = host,
            unicodeHost = Sites.unicode(host),
            matches = matches,
            selected = matches.singleOrNull(),
            query = "",
            searching = matches.isEmpty(),
            deadline = now + SHEET_MILLIS,
            decision = decision,
        )
        _request.value = sheet
        channel.send(LinkMessage.PasswordShown(req.id))
        // The computer may give up (the popup closed): the sheet goes with it.
        val watcher = runtime.scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            channel.receive(SHEET_MILLIS + MESSAGE_MILLIS)
            decision.complete(Decision.Gone)
        }
        val outcome = withTimeoutOrNull(SHEET_MILLIS) { decision.await() } ?: Decision.Timeout
        watcher.cancel()
        if (_request.value === sheet || _request.value?.decision === decision) _request.value = null
        when (outcome) {
            is Decision.Fill -> {
                val ssh = SshPublicKey.fromBlob(computer.sshBlob) ?: return
                val password = outcome.entry.password.encodeToByteArray()
                val box = PasswordBox.plaintext(received.bytes, req.origin, outcome.entry.username, password)
                val sealed = try {
                    Age.encrypt(box, listOf(ssh))
                } finally {
                    box.fill(0)
                    password.fill(0)
                }
                channel.send(LinkMessage.PasswordResponse(req.id, LinkMessage.FILL, sealed))
                journal(computer, host, Outcome.Filled)
                runtime.analytics.event("passwords_browser_fill")
            }
            Decision.Deny -> {
                channel.send(LinkMessage.PasswordResponse(req.id, LinkMessage.DENY, ByteArray(0)))
                journal(computer, host, Outcome.Denied)
            }
            Decision.Timeout -> {
                runCatching { channel.send(LinkMessage.PasswordResponse(req.id, LinkMessage.TIMEOUT, ByteArray(0))) }
                journal(computer, host, Outcome.TimedOut)
            }
            Decision.Gone -> journal(computer, host, Outcome.TimedOut)
        }
    }

    /** False when [id] was seen already (README §4): a request is answered once. */
    private fun remember(id: ByteArray): Boolean {
        val text = Base64.encode(Hash.SHA256.digest(id))
        if (text in seen) return false
        seen.addFirst(text)
        while (seen.size > SEEN_IDS) seen.removeLast()
        return true
    }

    private suspend fun journal(computer: Computer, site: String, outcome: Outcome) {
        val line = JournalLine(runtime.clock.now(), site, outcome)
        change { list -> list.map { if (it.key == computer.key) it.copy(journal = (listOf(line) + it.journal).take(JOURNAL_LINES)) else it } }
    }

    // ---------------------------------------------------------------- the sheet

    fun choose(id: String) {
        _request.value = _request.value?.copy(selected = id)
    }

    fun search(query: String) {
        _request.value = _request.value?.copy(query = query, searching = true)
    }

    fun showAll() {
        _request.value = _request.value?.copy(searching = true)
    }

    /** What the sheet lists: the site's entries, or, while searching, every entry the query finds. */
    fun candidates(request: Request): List<PasswordEntry> {
        val all = entries().orEmpty()
        if (!request.searching) return request.matches.mapNotNull { id -> all.firstOrNull { it.id == id } }
        val q = request.query.trim()
        return all.filter { q.isEmpty() || it.matches(q) }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.shownTitle })
    }

    /** The entry's site when it is not the requested one — the red warning (D543) — else null. */
    fun mismatch(request: Request, entry: PasswordEntry): String? =
        if (Sites.sameSite(entry, request.host)) null else Site.host(entry.url).ifEmpty { entry.shownTitle }

    /** "Send": the device's confirmation every time (D544), then the answer. */
    fun send() {
        val sheet = _request.value ?: return
        val entry = entries()?.firstOrNull { it.id == sheet.selected } ?: return
        runtime.scope.launch {
            if (!confirm(strings().confirmSend(sheet.unicodeHost, sheet.computerName))) return@launch
            sheet.decision.complete(Decision.Fill(entry))
        }
    }

    fun deny() {
        _request.value?.decision?.complete(Decision.Deny)
    }

    companion object {
        /** The sheet's life, and the computer's wait for it (README §4). */
        const val SHEET_MILLIS: Long = 60_000
        const val MESSAGE_MILLIS: Long = 10_000
        const val CLOCK_SKEW_MILLIS: Long = 5 * 60_000
        /** The psk's five minutes (D537). */
        const val PAIRING_MILLIS: Long = 5 * 60_000
        const val SEEN_IDS: Int = 256
    }
}
