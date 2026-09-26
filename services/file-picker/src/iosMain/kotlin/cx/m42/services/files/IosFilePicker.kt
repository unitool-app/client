package cx.m42.services.files

import cx.m42.superizer.runtime.FilePicker
import cx.m42.superizer.runtime.PickedFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * `UIDocumentPickerViewController`, opening a copy (`asCopy`): the app gets a file in its own inbox
 * and no security-scoped access to the original, which is all a pick needs.
 *
 * Written, not run: there is no Mac here (ssh-new 04 step 5b); the iPhone self-test report is what
 * will say it works.
 */
@OptIn(ExperimentalForeignApi::class)
public class IosFilePicker : FilePicker {

    // Held while the picker is up: UIKit keeps its delegate weakly.
    private var delegate: Delegate? = null

    override suspend fun pick(mimeTypes: List<String>): PickedFile? = withContext(Dispatchers.Main) {
        val presenter = topViewController() ?: return@withContext null
        val result = CompletableDeferred<NSURL?>()
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeData), asCopy = true)
        val answer = Delegate(result).also { delegate = it }
        picker.delegate = answer
        picker.allowsMultipleSelection = false
        presenter.presentViewController(picker, animated = true, completion = null)
        val url = try {
            result.await()
        } finally {
            delegate = null
        }
        url?.let(::read)
    }

    private fun read(url: NSURL): PickedFile {
        val name = url.lastPathComponent.orEmpty()
        val data = NSData.dataWithContentsOfURL(url) ?: return PickedFile(name, ByteArray(0))
        val size = data.length.toInt()
        if (size > MAX_PICKED_BYTES) return PickedFile(name, ByteArray(0))
        val bytes = ByteArray(size)
        if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return PickedFile(name, bytes)
    }

    private class Delegate(private val result: CompletableDeferred<NSURL?>) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            result.complete(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            result.complete(null)
        }
    }
}

/** Whatever is on top: presenting from anything lower makes UIKit refuse with a warning. */
private fun topViewController(): UIViewController? {
    @Suppress("DEPRECATION")
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}
