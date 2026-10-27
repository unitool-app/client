package cx.m42.services.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Storage Access Framework's "create a document", from a window of its own like the picker.
 * The bytes never touch the app's own files: they go straight from memory to the URI the person
 * chose, and the URI's permission is not persisted.
 */
public class AndroidFileSaver(private val context: Context) : FileSaver {

    override suspend fun save(suggestedName: String, bytes: ByteArray, mimeType: String): Boolean = withContext(Dispatchers.Main.immediate) {
        val result = CompletableDeferred<Uri?>()
        pending?.complete(null)
        pending = result
        context.startActivity(
            Intent(context, FileSaveActivity::class.java)
                .putExtra(FileSaveActivity.EXTRA_NAME, suggestedName)
                .putExtra(FileSaveActivity.EXTRA_TYPE, mimeType)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val uri = try {
            result.await()
        } finally {
            if (pending === result) pending = null
        } ?: return@withContext false
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("no stream")
            }.isSuccess
        }
    }

    internal companion object {
        private var pending: CompletableDeferred<Uri?>? = null

        fun deliver(uri: Uri?) {
            pending?.complete(uri)
            pending = null
        }
    }
}

/** Nothing on screen but the system's "Save as"; finishes the moment it answers. */
internal class FileSaveActivity : ComponentActivity() {

    private val create by lazy {
        registerForActivityResult(ActivityResultContracts.CreateDocument(intent.getStringExtra(EXTRA_TYPE) ?: "application/octet-stream")) { uri ->
            AndroidFileSaver.deliver(uri)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Registered before STARTED, as the result API requires, whatever happens next.
        create
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) create.launch(intent.getStringExtra(EXTRA_NAME) ?: "file")
    }

    companion object {
        const val EXTRA_NAME = "cx.m42.services.files.NAME"
        const val EXTRA_TYPE = "cx.m42.services.files.TYPE"
    }
}
