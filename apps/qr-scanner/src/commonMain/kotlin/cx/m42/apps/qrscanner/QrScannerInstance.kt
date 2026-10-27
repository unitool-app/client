package cx.m42.apps.qrscanner

import androidx.compose.runtime.Composable
import cx.m42.services.files.FileSaver
import cx.m42.services.qr.QrScanner
import cx.m42.services.qr.SeriesStep
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.runtime.InstanceRuntime
import cx.m42.unitool.kit.qr.QrFile
import cx.m42.unitool.kit.qr.QrFileGathering
import kotlin.io.encoding.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the screen shows under the buttons. */
internal sealed interface Result {
    /**
     * One code, or several joined by "One more code" (D634): [text] when the bytes are text, null
     * when they are not and only hex is shown.
     */
    class Single(val bytes: ByteArray, val text: String?, val content: Content, val codes: Int) : Result

    /** Chunks of a file coming in (D632); [otherFile] when a code of another file turned up. */
    class Gathering(val gathering: QrFileGathering, val otherFile: Boolean = false) : Result

    /** A file put back from its chunks, or bytes decoded from base64. */
    class File(val name: String, val bytes: ByteArray, val text: String?) : Result

    /** Every chunk is in and the SHA-256 does not hold (D632). It does not happen unless a code lies. */
    data object Damaged : Result
}

internal sealed interface Notice {
    data object Copied : Notice
    data object Saved : Notice
    data object NotSaved : Notice
}

/**
 * The one result on the screen, and only in memory (D629): no history, no setting, nothing in the
 * session snapshot — a file of a thousand chunks has no place in `SavedState`.
 */
internal class QrScannerInstance(private val runtime: InstanceRuntime) : AppInstance() {

    private val scanner: QrScanner? = runtime.service(QrScanner.Key)
    private val saver: FileSaver? = runtime.service(FileSaver.Key)

    val canScan: Boolean get() = scanner != null
    val canSave: Boolean get() = saver != null

    private val _result = MutableStateFlow<Result?>(null)
    val result: StateFlow<Result?> get() = _result.asStateFlow()

    /** Text or hex for a result that is text (D627); binary is always hex. */
    private val _hex = MutableStateFlow(false)
    val hex: StateFlow<Boolean> get() = _hex.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> get() = _notice.asStateFlow()

    val strings: QrScannerStrings get() = runtime.locale.pick(QrScannerStringTables, QrScannerStringsEn)

    override suspend fun onLaunch() {
        runtime.analytics.screen(QrScannerApp.ID)
    }

    // ---------------------------------------------------------------- input

    /** The camera, kept open while chunks of a file come in (D633). */
    fun scan() {
        val camera = scanner ?: return
        _notice.value = null
        runtime.scope.launch {
            val gathering = _result.value as? Result.Gathering
            val hint = gathering?.let { label(it.gathering) } ?: strings.aim
            camera.scanSeries(hint) { code -> accept(code) }
        }
    }

    /** One more code after the last, on a new line (D634). */
    fun scanMore() {
        val camera = scanner ?: return
        val before = _result.value as? Result.Single ?: return
        _notice.value = null
        runtime.scope.launch {
            val code = camera.scan() ?: return@launch
            val joined = (before.text ?: return@launch).trimEnd('\n') + "\n" + code
            _result.value = single(joined.encodeToByteArray(), before.codes + 1)
        }
    }

    /** Text from the clipboard, or anything else handed in as if it had been scanned. */
    fun paste(text: String) {
        _notice.value = null
        if (text.isEmpty()) return
        accept(text)
    }

    /**
     * A code from the camera or the clipboard. While a file is being gathered, only its chunks
     * count; anything else leaves it as it is.
     */
    internal fun accept(code: String): SeriesStep {
        val chunk = QrFile.parse(code)
        val current = _result.value
        if (chunk == null) {
            if (current is Result.Gathering) return SeriesStep.Continue(strings.notAChunk + " · " + label(current.gathering))
            _hex.value = false
            _result.value = single(code.encodeToByteArray(), 1)
            return SeriesStep.Done
        }
        if (current !is Result.Gathering) {
            _hex.value = false
            return gathered(QrFileGathering(chunk))
        }
        return when (val added = current.gathering.add(chunk)) {
            is QrFileGathering.Added.New -> gathered(added.gathering)
            QrFileGathering.Added.Repeat -> SeriesStep.Continue(label(current.gathering))
            QrFileGathering.Added.OtherFile -> {
                _result.value = Result.Gathering(current.gathering, otherFile = true)
                SeriesStep.Continue(strings.otherFile + " · " + label(current.gathering))
            }
        }
    }

    private fun gathered(gathering: QrFileGathering): SeriesStep {
        if (!gathering.complete) {
            _result.value = Result.Gathering(gathering)
            return SeriesStep.Continue(label(gathering))
        }
        _result.value = when (val file = gathering.assemble()) {
            is QrFileGathering.Assembled.Ok -> Result.File(file.name.ifEmpty { "qr-${today()}.bin" }, file.bytes, Bytes.text(file.bytes))
            else -> Result.Damaged
        }
        return SeriesStep.Done
    }

    private fun label(gathering: QrFileGathering): String = strings.progress(gathering.have, gathering.count)

    private fun single(bytes: ByteArray, codes: Int): Result.Single {
        val text = Bytes.text(bytes)
        return Result.Single(bytes, text, text?.let(Contents::of) ?: Content.Text, codes)
    }

    // ---------------------------------------------------------------- result

    fun showHex(on: Boolean) {
        _hex.value = on
    }

    /** Base64 or an armored block (age, ucrypt, PEM) as the bytes inside (D634); null when it is neither. */
    fun decodable(): ByteArray? {
        val text = (_result.value as? Result.Single)?.text ?: return null
        return base64(text)
    }

    fun decode() {
        val bytes = decodable() ?: return
        _hex.value = false
        _result.value = Result.File("qr-${today()}.bin", bytes, Bytes.text(bytes))
    }

    /** What "Copy" puts on the clipboard: the text, or the bytes as hex (D628). */
    fun copyText(): String? = when (val current = _result.value) {
        is Result.Single -> current.text ?: Bytes.hex(current.bytes)
        is Result.File -> current.text ?: Bytes.hex(current.bytes)
        else -> null
    }

    fun copied() {
        _notice.value = Notice.Copied
    }

    fun save() {
        val files = saver ?: return
        val (name, bytes, mime) = when (val current = _result.value) {
            is Result.Single -> {
                val ext = if (current.text == null) "bin" else Contents.extension(current.content)
                Triple("qr-${today()}.$ext", current.bytes, if (current.text == null) BINARY else mime(ext))
            }
            is Result.File -> Triple(current.name, current.bytes, BINARY)
            else -> return
        }
        runtime.scope.launch {
            _notice.value = if (files.save(name, bytes, mime)) Notice.Saved else Notice.NotSaved
        }
    }

    /** Back to nothing: the result and every chunk gathered go. */
    fun reset() {
        _result.value = null
        _hex.value = false
        _notice.value = null
    }

    @Composable
    override fun Content() = QrScannerScreen(this)

    internal fun today(): String = isoDate(runtime.clock.now())

    companion object {
        private const val BINARY = "application/octet-stream"

        private fun mime(ext: String) = when (ext) {
            "vcf" -> "text/vcard"
            "ics" -> "text/calendar"
            else -> "text/plain"
        }

        /** Without the `-----BEGIN/END …-----` lines and `Key: value` headers, then strict base64; null otherwise. */
        internal fun base64(text: String): ByteArray? {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val body = lines.filterNot { it.startsWith("-----") || ": " in it }.joinToString("")
            if (body.length < 4 || body.any { !(it.isLetterOrDigit() && it.code < 128) && it !in "+/=-_" }) return null
            val standard = body.replace('-', '+').replace('_', '/')
            val padded = standard + "=".repeat((4 - standard.length % 4) % 4)
            return runCatching { Base64.decode(padded) }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

        /** yyyy-mm-dd of [epochMillis], UTC, without a date library (Howard Hinnant's civil_from_days). */
        internal fun isoDate(epochMillis: Long): String {
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
