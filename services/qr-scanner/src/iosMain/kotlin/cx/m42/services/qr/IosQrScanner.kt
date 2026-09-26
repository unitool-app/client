package cx.m42.services.qr

import kotlin.coroutines.resume
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleCancel
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIApplication
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UILabel
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UIScreen
import platform.UIKit.UIViewController
import platform.UIKit.NSTextAlignmentCenter
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue

/**
 * The iPhone camera, reading QR codes with AVFoundation's own detector — the system's, so the
 * frames never meet a third-party decoder, which is what ZXing was for on Android.
 *
 * Its own full-screen controller on top of the app, like `QrScanActivity`: a preview, one line of
 * help and a close button. The camera question is the system's; a refusal ends in an alert that
 * says the link can be pasted instead, and [scan] answers null.
 *
 * Needs `NSCameraUsageDescription` in the app's Info.plist — iOS kills an app that opens the camera
 * without one.
 */
@OptIn(ExperimentalForeignApi::class)
public class IosQrScanner(private val language: () -> String) : QrScanner {

    override suspend fun scan(): String? = withContext(Dispatchers.Main) {
        val strings = ScanStrings.of(language())
        val presenter = topViewController() ?: return@withContext null
        if (!cameraAllowed()) {
            denied(presenter, strings)
            return@withContext null
        }
        suspendCancellableCoroutine { continuation ->
            val session = ScanSession(strings) { text -> if (continuation.isActive) continuation.resume(text) }
            if (!session.start()) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            presenter.presentViewController(session.controller, animated = true, completion = null)
            continuation.invokeOnCancellation { dispatch_async(dispatch_get_main_queue()) { session.finish(null) } }
        }
    }

    private suspend fun cameraAllowed(): Boolean =
        when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
            AVAuthorizationStatusAuthorized -> true
            AVAuthorizationStatusNotDetermined -> suspendCancellableCoroutine { continuation ->
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                    dispatch_async(dispatch_get_main_queue()) { if (continuation.isActive) continuation.resume(granted) }
                }
            }
            else -> false
        }

    private suspend fun denied(presenter: UIViewController, strings: ScanStrings) {
        suspendCancellableCoroutine { continuation ->
            val alert = UIAlertController.alertControllerWithTitle(strings.deniedTitle, strings.deniedText, UIAlertControllerStyleAlert)
            alert.addAction(
                UIAlertAction.actionWithTitle(strings.close, UIAlertActionStyleCancel) { _ ->
                    if (continuation.isActive) continuation.resume(Unit)
                },
            )
            presenter.presentViewController(alert, animated = true, completion = null)
        }
    }

    public companion object {
        /** Whether this device has a camera at all — the simulator, for one, does not. */
        public fun available(): Boolean = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) != null
    }
}

/**
 * One scan: the session, the controller that shows it, and the one answer it gives.
 *
 * An `NSObject` because it is the metadata delegate and the close button's target, and both of
 * those are Objective-C callbacks.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class ScanSession(
    private val strings: ScanStrings,
    private val onResult: (String?) -> Unit,
) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {

    private val session = AVCaptureSession()
    val controller = UIViewController(nibName = null, bundle = null)
    private var done = false

    fun start(): Boolean {
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) ?: return false
        val input = AVCaptureDeviceInput.deviceInputWithDevice(device, error = null) ?: return false
        if (!session.canAddInput(input)) return false
        session.addInput(input)
        val output = AVCaptureMetadataOutput()
        if (!session.canAddOutput(output)) return false
        session.addOutput(output)
        // After addOutput: before it, the output reports no available types and rejects QR.
        output.setMetadataObjectsDelegate(this, queue = dispatch_get_main_queue())
        output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)

        layout()
        controller.modalPresentationStyle = UIModalPresentationFullScreen
        // startRunning blocks until the camera is up, which is not a thing to do on the main thread.
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) { session.startRunning() }
        return true
    }

    private fun layout() {
        val (width, height) = UIScreen.mainScreen.bounds.useContents { size.width to size.height }
        val view = controller.view
        view.setFrame(CGRectMake(0.0, 0.0, width, height))
        view.backgroundColor = UIColor.blackColor

        val preview = AVCaptureVideoPreviewLayer(session = session)
        preview.videoGravity = AVLayerVideoGravityResizeAspectFill
        preview.setFrame(CGRectMake(0.0, 0.0, width, height))
        view.layer.addSublayer(preview)

        val hint = UILabel(frame = CGRectMake(24.0, 72.0, width - 48.0, 48.0))
        hint.text = strings.hint
        hint.textColor = UIColor.whiteColor
        hint.textAlignment = NSTextAlignmentCenter
        hint.numberOfLines = 2
        view.addSubview(hint)

        val close = UIButton.buttonWithType(UIButtonTypeSystem)
        close.setTitle(strings.close, forState = UIControlStateNormal)
        close.setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
        close.setFrame(CGRectMake((width - 160.0) / 2, height - 120.0, 160.0, 48.0))
        close.addTarget(this, action = NSSelectorFromString("closeTapped"), forControlEvents = UIControlEventTouchUpInside)
        view.addSubview(close)
    }

    @ObjCAction
    fun closeTapped() {
        finish(null)
    }

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        val text = didOutputMetadataObjects
            .firstNotNullOfOrNull { (it as? AVMetadataMachineReadableCodeObject)?.stringValue }
            ?: return
        UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
        finish(text)
    }

    /** Once: a code seen in two frames in a row must not answer twice. */
    fun finish(text: String?) {
        if (done) return
        done = true
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) { session.stopRunning() }
        controller.dismissViewControllerAnimated(true, completion = null)
        onResult(text)
    }
}

/** The scanner's few words, in the host's language rather than the device's. */
private class ScanStrings(
    val hint: String,
    val close: String,
    val deniedTitle: String,
    val deniedText: String,
) {
    companion object {
        fun of(tag: String?): ScanStrings = if (tag?.substringBefore('-')?.lowercase() == "ru") Ru else En

        val Ru = ScanStrings(
            hint = "Наведите камеру на QR-код",
            close = "Закрыть",
            deniedTitle = "Нет доступа к камере",
            deniedText = "Без камеры QR-код не прочитать. Разрешите её в Настройках или вставьте ссылку из QR-кода вручную.",
        )

        val En = ScanStrings(
            hint = "Point the camera at a QR code",
            close = "Close",
            deniedTitle = "No camera access",
            deniedText = "A QR code cannot be read without it. Allow the camera in Settings, or paste the link from the QR code instead.",
        )
    }
}

/** Whatever is on top: presenting from anything lower makes UIKit refuse with a warning. */
internal fun topViewController(): UIViewController? {
    @Suppress("DEPRECATION")
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}
