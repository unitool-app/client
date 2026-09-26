package cx.m42.services.ssh.keyring

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * The keyring's records as a file of their own next to the host's (D159), readable by its owner
 * only. Public halves and passphrase-encrypted key files: what `~/.ssh` holds, in one place.
 */
public class FileRecordStore(private val file: File) : RecordStore {

    override fun load(): String? = runCatching { if (file.isFile) file.readText() else null }.getOrNull()

    override fun save(text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        runCatching {
            Files.setPosixFilePermissions(temp.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }

    override fun clear() {
        file.delete()
    }

    override val location: String get() = file.absolutePath
}
