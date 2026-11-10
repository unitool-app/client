package cx.m42.services.vault

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDataWritingFileProtectionComplete
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.posix.memcpy

/**
 * [PrivateFiles] in `Application Support/<dir>`, the directory marked excluded from iCloud and
 * computer backups (D658), every file written atomically with `FileProtectionComplete`: unreadable
 * while the phone is locked.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public class IosPrivateFiles(private val dir: String) : PrivateFiles {

    private val manager get() = NSFileManager.defaultManager

    private fun root(): NSURL {
        val support = manager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL
        val url = support.URLByAppendingPathComponent(dir, isDirectory = true)!!
        if (!manager.fileExistsAtPath(url.path!!)) {
            manager.createDirectoryAtURL(url, withIntermediateDirectories = true, attributes = null, error = null)
        }
        url.setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
        return url
    }

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        val names = manager.contentsOfDirectoryAtPath(root().path!!, error = null) ?: return@withContext emptyList()
        names.mapNotNull { it as? String }.filter { !it.startsWith(".") }.sorted()
    }

    override suspend fun read(name: String): ByteArray? = withContext(Dispatchers.IO) {
        val url = root().URLByAppendingPathComponent(PrivateFiles.checkName(name))!!
        val data = NSData.dataWithContentsOfURL(url) ?: return@withContext null
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        bytes
    }

    override suspend fun write(name: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val url = root().URLByAppendingPathComponent(PrivateFiles.checkName(name))!!
        val data = if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        check(data.writeToURL(url, options = NSDataWritingAtomic or NSDataWritingFileProtectionComplete, error = null)) { "the file was not written" }
    }

    override suspend fun delete(name: String) {
        withContext(Dispatchers.IO) { removeFile(name) }
    }

    private fun removeFile(name: String) {
        val url = root().URLByAppendingPathComponent(PrivateFiles.checkName(name))!!
        manager.removeItemAtURL(url, error = null)
    }
}
