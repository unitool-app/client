package cx.m42.apps.crrestore

import androidx.compose.runtime.Composable
import cx.m42.services.files.FileSaver
import cx.m42.services.qr.QrScanner
import cx.m42.superizer.app.AppChrome
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.lock.SecretHold
import cx.m42.superizer.runtime.FilePicker
import cx.m42.superizer.runtime.InstanceRuntime
import cx.m42.superizer.ssh.SshKeyring
import cx.m42.unitool.kit.crypt.Ucrypt
import cx.m42.unitool.kit.openssl.OpensslEnc
import cx.m42.unitool.kit.split.PartReading
import cx.m42.unitool.kit.split.SplitLink
import cx.m42.unitool.kit.split.SplitPart
import cx.m42.unitool.kit.ssh.AgeKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Everything on the screen, and only in memory (D597): the instance is the one place it lives, and
 * [wipe] is the one place it goes. Not in `remember` either — under the curtain `Content()` is not
 * composed at all, and what was typed has to be there when the lock opens again (D598).
 *
 * Nothing is written to `runtime.storage`, no state is saved, and every setting starts from its
 * default each visit (D597).
 */
internal class CrRestoreInstance(val runtime: InstanceRuntime) : AppInstance() {

    private val hold: SecretHold? = runtime.service(SecretHold.Key)
    private val scanner: QrScanner? = runtime.service(QrScanner.Key)
    private val picker: FilePicker? = runtime.service(FilePicker.Key)
    private val saver: FileSaver? = runtime.service(FileSaver.Key)
    private val keyring: SshKeyring? = runtime.service(SshKeyring.Key)

    val canScan: Boolean get() = scanner != null
    val canPick: Boolean get() = picker != null
    val canSave: Boolean get() = saver != null
    val hasPhoneKeys: Boolean get() = keyring != null

    val method = MutableStateFlow<Method?>(null)

    /** age and openssl: what was scanned, pasted or opened. */
    val input = MutableStateFlow<Input?>(null)
    val pasteBox = MutableStateFlow("")

    /** split-qr: the container and the parts; a container still arriving QR by QR is [containerDraft]. */
    val split = MutableStateFlow(SplitGathering(null, null, emptyList()))
    private var containerDraft: Input? = null

    val looksLike = MutableStateFlow<Method?>(null)
    val notice = MutableStateFlow<String?>(null)
    val problem = MutableStateFlow<Problem?>(null)
    val working = MutableStateFlow(false)

    val result = MutableStateFlow<Output?>(null)
    val warning = MutableStateFlow<Warning?>(null)
    val revealed = MutableStateFlow(false)
    val saved = MutableStateFlow<Boolean?>(null)
    val copied = MutableStateFlow(false)

    // age
    val ageKeyText = MutableStateFlow("")
    private var ageKeyBytes: ByteArray? = null
    val ageKeyLoaded = MutableStateFlow<String?>(null)
    val ageKeyPassphrase = MutableStateFlow("")
    val agePassphrase = MutableStateFlow("")

    // openssl
    val password = MutableStateFlow("")
    val showPassword = MutableStateFlow(false)
    val useRawKey = MutableStateFlow(false)
    val keyHex = MutableStateFlow("")
    val ivHex = MutableStateFlow("")
    val iterations = MutableStateFlow(OpensslEnc.DEFAULT_ITERATIONS.toString())
    val params = MutableStateFlow(OpensslEnc.Params())
    val showMore = MutableStateFlow(false)

    private var job: Job? = null
    private var revealTimer: Job? = null
    private var onClipboard: Pair<String, SecretClipboard>? = null
    private var clipboardTimer: Job? = null

    private val _chrome = MutableStateFlow(AppChrome())
    override val chrome: StateFlow<AppChrome> get() = _chrome.asStateFlow()

    // ------------------------------------------------------------ choosing

    fun choose(chosen: Method) {
        clearInput()
        method.value = chosen
    }

    /** Back inside the app: from a method to the choice, from a result to a fresh start. */
    fun back(): Boolean {
        if (method.value == null) return false
        startOver()
        return true
    }

    // ------------------------------------------------------------ input

    fun scan() {
        val camera = scanner ?: return
        runtime.scope.launch {
            val text = outside { camera.scan() } ?: return@launch
            take(text.encodeToByteArray(), name = null, scanned = true)
        }
    }

    fun openFile() {
        val files = picker ?: return
        runtime.scope.launch {
            val file = outside { files.pick() } ?: return@launch
            take(file.bytes, name = file.name, scanned = false)
        }
    }

    fun paste() {
        val text = pasteBox.value.trim()
        if (text.isEmpty()) return
        pasteBox.value = ""
        take(text.encodeToByteArray(), name = null, scanned = false)
    }

    /** Whatever came in, put where the open method wants it (D600, D611). */
    internal fun take(bytes: ByteArray, name: String?, scanned: Boolean) {
        problem.value = null
        notice.value = null
        when (method.value) {
            null -> return
            Method.Split -> takeForSplit(bytes, name, scanned)
            else -> {
                val before = input.value
                val next = if (scanned) Input.appendCode(before, bytes.decodeToString()) else Input(name, bytes, 0).also { before?.wipe() }
                input.value = next
                looksLike.value = Detect.method(next.bytes)?.takeIf { it != method.value }
            }
        }
    }

    private fun takeForSplit(bytes: ByteArray, name: String?, scanned: Boolean) {
        val text = runCatching { bytes.decodeToString().trim() }.getOrDefault("")
        when (val reading = SplitLink.read(text)) {
            is PartReading.Ok -> return addPart(reading.part)
            PartReading.Damaged -> {
                notice.value = strings.splitProblem(cx.m42.unitool.kit.split.SplitProblem.Inconsistent(split.value.parts.size))
                return
            }
            else -> Unit
        }
        // A container: binary, its armor whole, or its armor arriving one QR at a time.
        val draft = containerDraft
        val pieces = when {
            draft != null && scanned -> Input.appendCode(draft, text)
            scanned && text.startsWith("-----BEGIN UCRYPT FILE-----") -> Input.appendCode(null, text)
            else -> null
        }
        if (pieces != null) {
            if (!Detect.armorComplete(pieces.bytes)) {
                containerDraft = pieces
                notice.value = strings.codesRead(pieces.codes, pieces.bytes.size)
                return
            }
            containerDraft = null
            return setContainer(pieces.bytes, null)
        }
        if (Ucrypt.isUcrypt(bytes) || Ucrypt.isArmored(bytes)) return setContainer(bytes, name)
        looksLike.value = Detect.method(bytes)?.takeIf { it != Method.Split }
        notice.value = if (looksLike.value == null) strings.notRecognised else null
    }

    private fun setContainer(bytes: ByteArray, name: String?) {
        // Kept binary: the header is read from it for k, n and the set id at every step.
        val file = if (Ucrypt.isArmored(bytes)) Ucrypt.dearmor(bytes) else bytes
        if (file == null) {
            problem.value = Problem.Damaged
            return
        }
        if (file !== bytes) bytes.fill(0)
        split.value.container?.fill(0)
        split.value = SplitGathering(file, name, split.value.parts)
        tryOpenSplit()
    }

    private fun addPart(part: SplitPart) {
        val gathering = split.value
        if (gathering.parts.any { it.bytes().contentEquals(part.bytes()) }) {
            notice.value = strings.partAgain
            return
        }
        val setId = gathering.header()?.split?.setIdHex ?: gathering.parts.firstOrNull()?.setIdHex
        if (setId != null && setId != part.setIdHex) {
            notice.value = strings.partOtherSet
            return
        }
        split.value = SplitGathering(gathering.container, gathering.containerName, gathering.parts + part)
        tryOpenSplit()
    }

    /** As soon as the container is in and there are enough parts, without a button (D611). */
    private fun tryOpenSplit() {
        val gathering = split.value
        val need = gathering.need ?: return
        if (gathering.parts.size >= need) decrypt()
    }

    // ------------------------------------------------------------ age key

    fun scanAgeKey() {
        val camera = scanner ?: return
        runtime.scope.launch {
            val text = outside { camera.scan() } ?: return@launch
            setAgeKey(text.encodeToByteArray(), strings.ageKey)
        }
    }

    fun openAgeKeyFile() {
        val files = picker ?: return
        runtime.scope.launch {
            val file = outside { files.pick() } ?: return@launch
            setAgeKey(file.bytes, file.name)
        }
    }

    private fun setAgeKey(bytes: ByteArray, label: String) {
        ageKeyBytes?.fill(0)
        ageKeyBytes = bytes
        ageKeyText.value = ""
        ageKeyLoaded.value = label
        problem.value = null
    }

    fun ageRecipients(): List<AgeKeys.Recipient>? = input.value?.let { AgeKeys.recipients(it.bytes) }

    // ------------------------------------------------------------ decrypting

    fun decryptWithPhoneKey() {
        val file = input.value?.bytes ?: return
        val keys = keyring ?: return
        attempt { Methods.ageWithPhoneKey(file, keys) }
    }

    fun decrypt() {
        when (method.value) {
            Method.Age -> {
                val file = input.value?.bytes ?: return
                val keyBytes = ageKeyBytes ?: ageKeyText.value.trim().takeIf { it.isNotEmpty() }?.encodeToByteArray()
                val keyPassphrase = ageKeyPassphrase.value.takeIf { it.isNotEmpty() }?.encodeToByteArray()
                val passphrase = agePassphrase.value.takeIf { it.isNotEmpty() }?.encodeToByteArray()
                attempt {
                    val (keys, keyProblem) = if (keyBytes == null) Keys.None to null else Keys.read(keyBytes, keyPassphrase, MAX_LOG_N)
                    keyPassphrase?.fill(0)
                    if (keyProblem != null || keys == null) {
                        passphrase?.fill(0)
                        Outcome.Failed(keyProblem ?: Problem.BadKey)
                    } else {
                        try {
                            Methods.age(file, keys, passphrase, MAX_LOG_N)
                        } finally {
                            keys.wipe()
                            passphrase?.fill(0)
                        }
                    }
                }
            }
            Method.Openssl -> {
                val file = input.value?.bytes ?: return
                val iter = iterations.value.trim().toIntOrNull()
                if (iter == null || iter < 1) {
                    problem.value = Problem.Unsupported(strings.iterations + " " + iterations.value)
                    return
                }
                val key = if (useRawKey.value) {
                    val k = hex(keyHex.value) ?: return run { problem.value = Problem.Unsupported(strings.keyHex) }
                    val iv = hex(ivHex.value) ?: return run { problem.value = Problem.Unsupported(strings.ivHex) }
                    OpensslEnc.Key.Raw(k, iv)
                } else {
                    OpensslEnc.Key.Password(password.value.encodeToByteArray())
                }
                val chosen = params.value.copy(iterations = iter)
                attempt {
                    try {
                        Methods.openssl(file, key, chosen)
                    } finally {
                        when (key) {
                            is OpensslEnc.Key.Password -> key.bytes.fill(0)
                            is OpensslEnc.Key.Raw -> { key.key.fill(0); key.iv.fill(0) }
                        }
                    }
                }
            }
            Method.Split -> attempt { Methods.split(split.value) }
            null -> Unit
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        working.value = false
    }

    /** One attempt at a time, off the main thread: scrypt and a million PBKDF2 rounds take seconds (D616). */
    private fun attempt(work: suspend () -> Outcome) {
        if (job?.isActive == true) return
        problem.value = null
        working.value = true
        job = runtime.scope.launch {
            try {
                val outcome = withContext(Dispatchers.Default) { work() }
                when (outcome) {
                    is Outcome.Done -> show(outcome)
                    is Outcome.Failed -> problem.value = outcome.problem
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // An out-of-memory scrypt on a small phone among them: say so rather than crash.
                problem.value = Problem.Damaged
            } finally {
                working.value = false
            }
        }
    }

    private fun show(done: Outcome.Done) {
        val name = Output.name(done.insideName, input.value?.name ?: split.value.containerName, today())
        result.value?.bytes?.fill(0)
        result.value = Output.of(done.bytes, name)
        warning.value = done.warning
        revealed.value = false
        saved.value = null
        copied.value = false
        // From here on the screen holds a secret: idle and leaving bring the lock (D598).
        hold?.holding(true)
    }

    // ------------------------------------------------------------ the result

    fun toggleReveal() {
        revealed.value = !revealed.value
        revealTimer?.cancel()
        if (revealed.value) {
            revealTimer = runtime.scope.launch {
                delay(REVEAL_SECONDS * 1000L)
                revealed.value = false
            }
        }
    }

    fun copyTo(clipboard: SecretClipboard) {
        val text = (result.value as? Output.Text)?.text ?: return
        if (runCatching { clipboard.put(text) }.isFailure) return
        copied.value = true
        onClipboard = text to clipboard
        clipboardTimer?.cancel()
        clipboardTimer = runtime.scope.launch {
            delay(CLIPBOARD_SECONDS * 1000L)
            clearClipboard()
        }
    }

    private fun clearClipboard() {
        val (text, clipboard) = onClipboard ?: return
        if (runCatching { clipboard.clearIfStill(text) }.getOrNull() != null) onClipboard = null
    }

    fun save() {
        val output = result.value ?: return
        val files = saver ?: return
        runtime.scope.launch {
            val mime = if (output is Output.Text) "text/plain" else "application/octet-stream"
            saved.value = outside { files.save(output.name, output.bytes, mime) }
        }
    }

    fun startOver() {
        cancel()
        clearInput()
        method.value = null
    }

    // ------------------------------------------------------------ lifecycle

    override fun onBackground() {
        // Whatever was shown is hidden again by the time anyone is back (D619).
        revealed.value = false
    }

    override fun onForeground() {
        if (onClipboard == null || clipboardTimer?.isActive == true) return
        runtime.scope.launch {
            delay(CLIPBOARD_REFOCUS_MILLIS)
            clearClipboard()
        }
    }

    override fun saveState(): JsonObject? = null

    override fun onClose() = wipe()

    override fun dispose() = wipe()

    /** Everything, gone: the result, the input, every field (D597). */
    private fun wipe() {
        cancel()
        clearInput()
        clearClipboard()
    }

    private fun clearInput() {
        input.value?.wipe()
        input.value = null
        containerDraft?.wipe()
        containerDraft = null
        split.value.container?.fill(0)
        split.value = SplitGathering(null, null, emptyList())
        result.value?.bytes?.fill(0)
        result.value = null
        warning.value = null
        revealed.value = false
        revealTimer?.cancel()
        saved.value = null
        copied.value = false
        looksLike.value = null
        notice.value = null
        problem.value = null
        pasteBox.value = ""
        ageKeyBytes?.fill(0)
        ageKeyBytes = null
        ageKeyLoaded.value = null
        ageKeyText.value = ""
        ageKeyPassphrase.value = ""
        agePassphrase.value = ""
        password.value = ""
        showPassword.value = false
        useRawKey.value = false
        keyHex.value = ""
        ivHex.value = ""
        iterations.value = OpensslEnc.DEFAULT_ITERATIONS.toString()
        params.value = OpensslEnc.Params()
        showMore.value = false
        hold?.holding(false)
    }

    private suspend fun <T> outside(block: suspend () -> T): T = hold?.outside(block) ?: block()

    val strings: CrRestoreStrings get() = runtime.locale.pick(CrRestoreStringTables, CrRestoreStringsEn)

    private fun today(): String = isoDate(runtime.clock.now())

    @Composable
    override fun Content() = CrRestoreScreen(this)

    companion object {
        /** scrypt's work factor this app spends at most: 2²⁰ is 1 GiB, more than that a phone cannot give (D615). */
        const val MAX_LOG_N: Int = 20
        const val REVEAL_SECONDS: Int = 60
        const val CLIPBOARD_REFOCUS_MILLIS: Long = 300

        fun hex(text: String): ByteArray? {
            val clean = text.filterNot { it.isWhitespace() }.lowercase()
            if (clean.length % 2 != 0 || clean.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
            return ByteArray(clean.length / 2) { clean.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        }

        /** yyyy-mm-dd of [epochMillis], UTC, without a date library (Howard Hinnant's civil_from_days). */
        fun isoDate(epochMillis: Long): String {
            val days = epochMillis.floorDiv(86_400_000L)
            val z = days + 719_468
            val era = z.floorDiv(146_097L)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
            return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
        }
    }
}
