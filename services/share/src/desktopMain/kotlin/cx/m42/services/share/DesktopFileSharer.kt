package cx.m42.services.share

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A computer has no share sheet; the nearest thing is "Save as": the system's dialog, the name
 * filled in, the file written where the person says. [message] has nowhere to go and is dropped.
 */
public class DesktopFileSharer : FileSharer {
    override suspend fun share(fileName: String, text: String, mimeType: String, message: String?): Boolean = withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, "", FileDialog.SAVE)
        dialog.file = fileName
        dialog.isVisible = true
        val name = dialog.file ?: return@withContext false
        File(dialog.directory, name).writeText(text)
        true
    }
}
