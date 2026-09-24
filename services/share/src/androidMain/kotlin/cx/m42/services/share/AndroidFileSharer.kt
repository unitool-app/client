package cx.m42.services.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The provider the manifest declares, under a class name no other library will also declare. */
public class ShareFileProvider : FileProvider()

/**
 * [FileSharer] on Android: writes the file into the app's cache, and offers it through the system
 * share sheet with a read grant for whichever app is picked.
 */
public class AndroidFileSharer(private val context: Context) : FileSharer {

    override suspend fun share(fileName: String, text: String, mimeType: String, message: String?): Boolean {
        val file = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
            // Yesterday's files go. Not the ones from a minute ago: a mail app attaches the URI
            // and reads it only when the mail is sent, which can be after the next share.
            val cutoff = System.currentTimeMillis() - KEEP_MS
            directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
            File(directory, safeName(fileName)).apply { writeText(text) }
        }
        return withContext(Dispatchers.Main.immediate) {
            val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
            val send = Intent(Intent.ACTION_SEND)
                .setType(mimeType)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (message != null) send.putExtra(Intent.EXTRA_TEXT, message)
            // ClipData as well as the extra: it is what carries the grant through the chooser, and
            // what lets the sheet show the file's name.
            send.clipData = ClipData.newRawUri(fileName, uri)
            val chooser = Intent.createChooser(send, null)
                // The application context has no task of its own (see AndroidQrScanner).
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(chooser) }.isSuccess
        }
    }

    private companion object {
        const val DIRECTORY = "shared"
        const val AUTHORITY_SUFFIX = ".share"
        const val KEEP_MS = 24 * 60 * 60 * 1000L

        /** A name, not a path: nothing an app passes may climb out of the shared directory. */
        fun safeName(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\').filter { it.isLetterOrDigit() || it in "-_." }
                .trimStart('.').ifEmpty { "shared.txt" }
    }
}
