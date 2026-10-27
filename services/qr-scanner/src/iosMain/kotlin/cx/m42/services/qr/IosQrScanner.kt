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
import platform.UIKit.UIFont
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
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

    override suspend fun scan(): String? = present(label = null, onCode = null)

    /**
     * The same screen kept open: every new code goes to [onCode] and the scanner closes only on
     * [SeriesStep.Done] — true — or on Close, a refused camera or no camera — false.
     */
    override suspend fun scanSeries(hint: String?, onCode: (String) -> SeriesStep): Boolean {
        // A throw out of an Objective-C callback would take the app down; it is carried out here
        // instead and rethrown in the caller.
        var thrown: Throwable? = null
        val ended = present(label = hint) { text ->
            try {
                onCode(text)
            } catch (e: Throwable) {
                thrown = e
                SeriesStep.Done
            }
        }
        thrown?.let { throw it }
        return ended != null
    }

    /**
     * Shows the scanner until it closes. With [onCode] null, the first code read is the answer;
     * with it, a series runs and the answer is non-null only when [onCode] said [SeriesStep.Done].
     */
    private suspend fun present(label: String?, onCode: ((String) -> SeriesStep)?): String? = withContext(Dispatchers.Main) {
        val strings = ScanStrings.of(language())
        val presenter = topViewController() ?: return@withContext null
        if (!cameraAllowed()) {
            denied(presenter, strings)
            return@withContext null
        }
        suspendCancellableCoroutine { continuation ->
            val session = ScanSession(strings, label, onCode) { text -> if (continuation.isActive) continuation.resume(text) }
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
 * One scan: the session, the controller that shows it, and the one answer it gives. With
 * [onCode], a series: every new code goes there, [label] shows its answers, and only
 * [SeriesStep.Done] closes it with an answer.
 *
 * An `NSObject` because it is the metadata delegate and the close button's target, and both of
 * those are Objective-C callbacks.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class ScanSession(
    private val strings: ScanStrings,
    private val label: String?,
    private val onCode: ((String) -> SeriesStep)?,
    private val onResult: (String?) -> Unit,
) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {

    private val session = AVCaptureSession()
    val controller = UIViewController(nibName = null, bundle = null)
    private var done = false

    /** The series' line over the camera; absent for one code. */
    private var labelView: UILabel? = null
    /**
     * The last text a series handed over. A code stays in view for many frames; only a change of
     * text is news.
     */
    private var last: String? = null

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

        if (onCode != null) {
            val line = UILabel(frame = CGRectMake(24.0, 128.0, width - 48.0, 56.0))
            line.text = label
            line.textColor = UIColor.whiteColor
            line.textAlignment = NSTextAlignmentCenter
            line.font = UIFont.boldSystemFontOfSize(22.0)
            line.numberOfLines = 2
            view.addSubview(line)
            labelView = line
        }

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
        val series = onCode
        if (series != null) {
            seriesCode(series, text)
            return
        }
        UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
        finish(text)
    }

    private fun seriesCode(series: (String) -> SeriesStep, text: String) {
        if (done || text == last) return
        last = text
        when (val step = series(text)) {
            is SeriesStep.Continue -> {
                UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight).impactOccurred()
                labelView?.text = step.label
            }
            SeriesStep.Done -> {
                UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
                finish(text)
            }
        }
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
