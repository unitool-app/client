package cx.m42.services.vault

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [PrivateFiles] over one directory of the file system, rooted by the host in `noBackupFilesDir`
 * (D658) — outside Auto Backup and device-to-device transfer, inside the app's sandbox. The desktop
 * has the same class, for tests: two copies of a page rather than a source set of their own.
 */
public class DirectoryFiles(private val dir: File) : PrivateFiles {

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.map { it.name }?.sorted() ?: emptyList()
    }

    override suspend fun read(name: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(dir, PrivateFiles.checkName(name))
        if (file.isFile) file.readBytes() else null
    }

    override suspend fun write(name: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val file = File(dir, PrivateFiles.checkName(name))
        dir.mkdirs()
        val temporary = File(dir, ".$name.tmp")
        FileOutputStream(temporary).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override suspend fun delete(name: String): Unit = withContext(Dispatchers.IO) {
        File(dir, PrivateFiles.checkName(name)).delete()
        Unit
    }
}
