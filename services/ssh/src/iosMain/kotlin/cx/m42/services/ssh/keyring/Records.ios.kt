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
 * copy of the device carries it (I10, T1 on iOS). Written, not yet run (D180).
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

    private companion object {
        const val FILE = "cx.m42.services.ssh.json"
    }
}
