package cx.m42.services.files

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The system's own save dialog (AWT's), starting in the home folder. */
public class DesktopFileSaver(private val startIn: File? = File(System.getProperty("user.home"))) : FileSaver {

    override suspend fun save(suggestedName: String, bytes: ByteArray, mimeType: String): Boolean = withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, "", FileDialog.SAVE)
        startIn?.takeIf { it.isDirectory }?.let { dialog.directory = it.path }
        dialog.file = suggestedName
        dialog.isVisible = true
        val name = dialog.file ?: return@withContext false
        runCatching { File(dialog.directory, name).writeBytes(bytes) }.isSuccess
    }
}
