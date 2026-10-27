package cx.m42.services.files

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.darwin.NSObject

/**
 * `UIDocumentPickerViewController(forExportingURLs:asCopy:)`: iOS exports only a file, so the bytes
 * are written to a temporary one first, which is removed the moment the picker answers — whatever
 * the answer.
 *
 * Written, not run: there is no Mac here; the iPhone is what will say it works.
 */
@OptIn(ExperimentalForeignApi::class)
public class IosFileSaver : FileSaver {

    private var delegate: Delegate? = null

    override suspend fun save(suggestedName: String, bytes: ByteArray, mimeType: String): Boolean = withContext(Dispatchers.Main) {
        val presenter = topViewController() ?: return@withContext false
        val name = suggestedName.replace('/', '_').ifBlank { "file" }
        val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + name)
        val data = if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        if (!data.writeToURL(url, atomically = true)) return@withContext false
        val result = CompletableDeferred<Boolean>()
        try {
            val picker = UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)
            val answer = Delegate(result).also { delegate = it }
            picker.delegate = answer
            presenter.presentViewController(picker, animated = true, completion = null)
            result.await()
        } finally {
            delegate = null
            NSFileManager.defaultManager.removeItemAtURL(url, error = null)
        }
    }

    private class Delegate(private val result: CompletableDeferred<Boolean>) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            result.complete(didPickDocumentsAtURLs.isNotEmpty())
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            result.complete(false)
        }
    }
}

private fun topViewController(): UIViewController? {
    @Suppress("DEPRECATION")
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}
