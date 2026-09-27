package cx.m42.services.ssh.keyring

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToURL

/**
 * The keyring's records in Application Support (05 §4.4, D159): `NSFileProtectionComplete`, so the
 * file is unreadable while the phone is locked, and excluded from iCloud and iTunes backups, so no
 * copy of the device carries it (I10, T1 on iOS). [protectionProblem] reads both back for the
 * self-test's `storage.location`.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public class FileRecordStore : RecordStore {

    private val url: NSURL? by lazy {
        val base = NSFileManager.defaultManager.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)
        base?.URLByAppendingPathComponent(FILE)
    }

    override fun load(): String? {
        val path = url?.path ?: return null
        val data = NSFileManager.defaultManager.contentsAtPath(path) ?: return null
        return NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    override fun save(text: String) {
        val target = url ?: return
        val data: NSData = (text as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        data.writeToURL(target, atomically = true)
        target.path?.let { path ->
            NSFileManager.defaultManager.setAttributes(mapOf(NSFileProtectionKey to NSFileProtectionComplete), ofItemAtPath = path, error = null)
        }
        target.setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
    }

    override fun clear() {
        url?.let { NSFileManager.defaultManager.removeItemAtURL(it, null) }
    }

    override val location: String get() = url?.path ?: "Application Support/$FILE"

    /** Whether there is a file to protect at all: none until the first key is kept. */
    public fun exists(): Boolean = url?.path?.let { NSFileManager.defaultManager.fileExistsAtPath(it) } == true

    /**
     * Null when the file is what [save] asked for — `NSFileProtectionComplete` and out of backups —
     * as the file system answers now, not as [save] meant it: [save] does not see the errors of
     * either call. No file is no problem; there is nothing to carry.
     */
    public fun protectionProblem(): String? {
        val path = url?.path ?: return "no Application Support directory"
        if (!exists()) return null
        val protection = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)?.get(NSFileProtectionKey)
        if (protection != NSFileProtectionComplete) return "file protection is ${protection ?: "unknown"}, not $NSFileProtectionComplete"
        // A URL of its own: an NSURL caches resource values, and the one save() used would answer from memory.
        val excluded = NSURL.fileURLWithPath(path).resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), error = null)
            ?.get(NSURLIsExcludedFromBackupKey) as? NSNumber
        if (excluded?.boolValue != true) return "not excluded from backup"
        return null
    }

    private companion object {
        const val FILE = "cx.m42.services.ssh.json"
    }
}
