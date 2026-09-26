package cx.m42.services.share

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
// Declared in a category on UIViewController, which Kotlin/Native exposes as an importable extension.
import platform.UIKit.popoverPresentationController

/**
 * The iOS share sheet (`UIActivityViewController`), handed a file in the app's own temporary
 * directory — the equivalent of Android's FileProvider: the sheet reads it from there, and iOS
 * clears the directory on its own schedule.
 *
 * One file at a time: the directory is emptied before each share, so an export does not outlive the
 * next one.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public class IosFileSharer : FileSharer {

    override suspend fun share(fileName: String, text: String, mimeType: String, message: String?): Boolean =
        withContext(Dispatchers.Main) {
            val presenter = topViewController() ?: return@withContext false
            val file = write(fileName, text) ?: return@withContext false
            val items = listOfNotNull<Any>(file, message)
            val sheet = UIActivityViewController(activityItems = items, applicationActivities = null)
            // An iPad shows the sheet as a popover, and a popover with no anchor is an exception.
            sheet.popoverPresentationController?.let { popover ->
                val view = presenter.view
                popover.sourceView = view
                val (width, height) = view.bounds.useContents { size.width to size.height }
                popover.sourceRect = CGRectMake(width / 2, height / 2, 1.0, 1.0)
            }
            presenter.presentViewController(sheet, animated = true, completion = null)
            true
        }

    private fun write(fileName: String, text: String): NSURL? {
        val manager = NSFileManager.defaultManager
        val directory = NSURL.fileURLWithPath(NSTemporaryDirectory()).URLByAppendingPathComponent("share", isDirectory = true)
            ?: return null
        manager.removeItemAtURL(directory, error = null)
        if (!manager.createDirectoryAtURL(directory, withIntermediateDirectories = true, attributes = null, error = null)) return null
        // A name is the app's own text; a path separator in it would be a path.
        val file = directory.URLByAppendingPathComponent(fileName.replace('/', '_')) ?: return null
        val written = NSString.create(string = text).writeToURL(file, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        return if (written) file else null
    }
}

/** Whatever is on top: presenting from anything lower makes UIKit refuse with a warning. */
private fun topViewController(): UIViewController? {
    @Suppress("DEPRECATION")
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}
