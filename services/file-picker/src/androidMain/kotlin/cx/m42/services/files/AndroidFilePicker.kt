package cx.m42.services.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import cx.m42.superizer.runtime.FilePicker
import cx.m42.superizer.runtime.PickedFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Storage Access Framework's "open a document", from a window of its own — the way the QR
 * scanner opens its camera: the host's activity is not needed, and a picker that outlives a rotation
 * of the activity that asked still answers.
 *
 * Read-only and once: the URI's permission is not persisted, so nothing is left behind that could
 * open the file again.
 */
public class AndroidFilePicker(private val context: Context) : FilePicker {

    override suspend fun pick(mimeTypes: List<String>): PickedFile? = withContext(Dispatchers.Main.immediate) {
        val result = CompletableDeferred<PickedFile?>()
        // One picker at a time; a second request answers the first with "closed".
        pending?.complete(null)
        pending = result
        context.startActivity(
            Intent(context, FilePickActivity::class.java)
                .putExtra(FilePickActivity.EXTRA_TYPES, mimeTypes.ifEmpty { listOf("*/*") }.toTypedArray())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            result.await()
        } finally {
            if (pending === result) pending = null
        }
    }

    internal companion object {
        // Main thread only, as in the scanner.
        private var pending: CompletableDeferred<PickedFile?>? = null

        fun deliver(file: PickedFile?) {
            pending?.complete(file)
            pending = null
        }
    }
}

/** Nothing on screen but the system's picker; finishes the moment it answers. */
internal class FilePickActivity : ComponentActivity() {

    private val open = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val file = uri?.let { runCatching { read(it) }.getOrNull() }
        AndroidFilePicker.deliver(file)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After a recreation the picker is already up and its answer comes to [open] above.
        if (savedInstanceState == null) {
            open.launch(intent.getStringArrayExtra(EXTRA_TYPES) ?: arrayOf("*/*"))
        }
    }

    private fun read(uri: Uri): PickedFile {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(MAX_PICKED_BYTES + 1)
            var total = 0
            while (total < buffer.size) {
                val n = input.read(buffer, total, buffer.size - total)
                if (n < 0) break
                total += n
            }
            // Too large to be what anyone picks here: nothing, rather than the first 4 MB of it.
            val kept = if (total > MAX_PICKED_BYTES) ByteArray(0) else buffer.copyOf(total)
            buffer.fill(0)
            kept
        } ?: ByteArray(0)
        return PickedFile(name, bytes)
    }

    companion object {
        const val EXTRA_TYPES = "cx.m42.services.files.TYPES"
    }
}
