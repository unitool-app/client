package cx.m42.services.files

import cx.m42.superizer.runtime.FilePicker
import cx.m42.superizer.runtime.PickedFile
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The system's own file dialog (AWT's, which is the native one on macOS and Windows). Starts in
 * `~/.ssh` when there is one, because that is where the file people pick here usually is.
 *
 * [mimeTypes] are not applied: AWT filters by name, and a key file has no extension to filter by.
 */
public class DesktopFilePicker(private val startIn: File? = File(System.getProperty("user.home"), ".ssh")) : FilePicker {

    override suspend fun pick(mimeTypes: List<String>): PickedFile? = withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, "", FileDialog.LOAD)
        startIn?.takeIf { it.isDirectory }?.let { dialog.directory = it.path }
        // Modal: returns when the person chose or closed it.
        dialog.isVisible = true
        val name = dialog.file ?: return@withContext null
        val file = File(dialog.directory, name)
        val bytes = if (file.length() in 0..MAX_PICKED_BYTES.toLong()) file.readBytes() else ByteArray(0)
        PickedFile(name, bytes)
    }
}
