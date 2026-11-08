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
import platform.CoreImage.CIDetector
import platform.CoreImage.CIDetectorAccuracy
import platform.CoreImage.CIDetectorAccuracyHigh
import platform.CoreImage.CIDetectorTypeQRCode
import platform.CoreImage.CIImage
import platform.CoreImage.CIQRCodeFeature
import platform.Foundation.NSData
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleCancel
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleActionSheet
import platform.UIKit.UIApplication
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIFont
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UILabel
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UIScreen
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.UIKit.NSTextAlignmentCenter
import platform.UniformTypeIdentifiers.UTTypeImage
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
 * help, "Photo or file" and a close button. The camera question is the system's; after a refusal
 * the same screen opens without the camera, so a code can still be read from a picture.
 *
 * "Photo or file" (qr-scanner D637) asks which: Photos through `PHPickerViewController`, which needs
 * no photo-library permission, or Files through the document picker. The picture is read by Core
 * Image's QR detector, every code in it.
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
        val camera = cameraAllowed()
        suspendCancellableCoroutine { continuation ->
            val session = ScanSession(strings, label, onCode) { text -> if (continuation.isActive) continuation.resume(text) }
            session.start(camera)
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
 * An `NSObject` because it is the metadata delegate and the buttons' target, and both of those
 * are Objective-C callbacks.
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
    /** The help line; also says what came of a picture that gave no code. */
    private var hintView: UILabel? = null
    private var pictureButton: UIButton? = null
    /** The picker's delegate: UIKit holds delegates weakly, so this is what keeps it alive. */
    private var pictureDelegate: NSObject? = null
    /**
     * The last text a series handed over. A code stays in view for many frames; only a change of
     * text is news.
     */
    private var last: String? = null

    /**
     * Builds the screen; with [camera] false (refused) or a camera that will not start, the same
     * screen without the preview, saying why, with "Photo or file" still there.
     */
    fun start(camera: Boolean) {
        val running = camera && connectCamera()
        layout(if (running) strings.hint else if (camera) strings.failedText else strings.deniedText)
        controller.modalPresentationStyle = UIModalPresentationFullScreen
        // startRunning blocks until the camera is up, which is not a thing to do on the main thread.
        if (running) dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) { session.startRunning() }
    }

    private fun connectCamera(): Boolean {
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
        return true
    }

    private fun layout(help: String) {
        val (width, height) = UIScreen.mainScreen.bounds.useContents { size.width to size.height }
        val view = controller.view
        view.setFrame(CGRectMake(0.0, 0.0, width, height))
        view.backgroundColor = UIColor.blackColor

        val preview = AVCaptureVideoPreviewLayer(session = session)
        preview.videoGravity = AVLayerVideoGravityResizeAspectFill
        preview.setFrame(CGRectMake(0.0, 0.0, width, height))
        view.layer.addSublayer(preview)

        val hint = UILabel(frame = CGRectMake(24.0, 64.0, width - 48.0, 64.0))
        hint.text = help
        hint.textColor = UIColor.whiteColor
        hint.textAlignment = NSTextAlignmentCenter
        hint.numberOfLines = 3
        view.addSubview(hint)
        hintView = hint

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

        val picture = UIButton.buttonWithType(UIButtonTypeSystem)
        picture.setTitle(strings.fromPicture, forState = UIControlStateNormal)
        picture.setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
        picture.backgroundColor = UIColor.whiteColor.colorWithAlphaComponent(0.2)
        picture.layer.cornerRadius = 24.0
        picture.setFrame(CGRectMake((width - 220.0) / 2, height - 184.0, 220.0, 48.0))
        picture.addTarget(this, action = NSSelectorFromString("pictureTapped"), forControlEvents = UIControlEventTouchUpInside)
        view.addSubview(picture)
        pictureButton = picture
    }

    @ObjCAction
    fun closeTapped() {
        finish(null)
    }

    /** Photos or Files: two pickers on iOS, where Android's one offers both. */
    @ObjCAction
    fun pictureTapped() {
        val sheet = UIAlertController.alertControllerWithTitle(null, message = null, preferredStyle = UIAlertControllerStyleActionSheet)
        sheet.addAction(UIAlertAction.actionWithTitle(strings.photo, UIAlertActionStyleDefault) { _ -> pickPhoto() })
        sheet.addAction(UIAlertAction.actionWithTitle(strings.file, UIAlertActionStyleDefault) { _ -> pickFile() })
        sheet.addAction(UIAlertAction.actionWithTitle(strings.cancel, UIAlertActionStyleCancel, handler = null))
        // An iPad shows an action sheet as a popover, and UIKit throws without an anchor for it.
        pictureButton?.let { button ->
            sheet.popoverPresentationController?.sourceView = button
            sheet.popoverPresentationController?.setSourceRect(button.bounds)
        }
        controller.presentViewController(sheet, animated = true, completion = null)
    }

    private fun pickPhoto() {
        val configuration = PHPickerConfiguration()
        configuration.filter = PHPickerFilter.imagesFilter
        configuration.selectionLimit = 1
        val picker = PHPickerViewController(configuration = configuration)
        val delegate = PhotoPicked(::picture)
        pictureDelegate = delegate
        picker.delegate = delegate
        controller.presentViewController(picker, animated = true, completion = null)
    }

    private fun pickFile() {
        // A copy: the original may sit in iCloud or another app's provider, and a copy needs no
        // security-scoped access to read.
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeImage), asCopy = true)
        val delegate = FilePicked(::picture)
        pictureDelegate = delegate
        picker.delegate = delegate
        controller.presentViewController(picker, animated = true, completion = null)
    }

    /**
     * A picked picture's bytes, or null when they would not load. Read off the main thread; the
     * codes found are then treated as if the camera had seen them.
     */
    private fun picture(data: NSData?) {
        if (done) return
        hintView?.text = strings.reading
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            val codes = data?.let(::qrCodesIn)
            dispatch_async(dispatch_get_main_queue()) { pictureCodes(codes) }
        }
    }

    private fun pictureCodes(codes: List<String>?) {
        if (done) return
        when {
            codes == null -> hintView?.text = strings.pictureUnreadable
            codes.isEmpty() -> hintView?.text = strings.noCodeInPicture
            else -> {
                hintView?.text = strings.hint
                val series = onCode
                if (series == null) {
                    UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
                    finish(codes.first())
                } else {
                    for (code in codes) {
                        if (done) break
                        seriesCode(series, code)
                    }
                }
            }
        }
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

/**
 * Every QR code Core Image finds in [data], each text once; null when it is not a picture. Tried
 * again on the negative when the picture gives none, for codes drawn light on dark.
 */
private fun qrCodesIn(data: NSData): List<String>? {
    val image = CIImage.imageWithData(data) ?: return null
    val detector = CIDetector.detectorOfType(
        CIDetectorTypeQRCode,
        context = null,
        options = mapOf<Any?, Any?>(CIDetectorAccuracy to CIDetectorAccuracyHigh),
    ) ?: return emptyList()
    fun read(picture: CIImage): List<String> =
        detector.featuresInImage(picture).mapNotNull { (it as? CIQRCodeFeature)?.messageString }.distinct()
    return read(image).ifEmpty { read(image.imageByApplyingFilter("CIColorInvert")) }
}

/** The photo picker's answer: the first picture's bytes, on the main thread; nothing on Cancel. */
private class PhotoPicked(private val onData: (NSData?) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult ?: return
        result.itemProvider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
            dispatch_async(dispatch_get_main_queue()) { onData(data) }
        }
    }
}

/** The document picker's answer: the picked file's bytes; nothing on Cancel. */
private class FilePicked(private val onData: (NSData?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        onData(NSData.dataWithContentsOfURL(url))
    }
}

/** The scanner's few words, in the host's language rather than the device's. */
private class ScanStrings(
    val hint: String,
    val close: String,
    val deniedText: String,
    val failedText: String,
    val fromPicture: String,
    val photo: String,
    val file: String,
    val cancel: String,
    val reading: String,
    val noCodeInPicture: String,
    val pictureUnreadable: String,
) {
    companion object {
        fun of(tag: String?): ScanStrings = if (tag?.substringBefore('-')?.lowercase() == "ru") Ru else En

        val Ru = ScanStrings(
            hint = "Наведите камеру на QR-код",
            close = "Закрыть",
            deniedText = "Нет доступа к камере. Разрешите её в Настройках — или выберите фото или файл с кодом.",
            failedText = "Камера не запустилась. Выберите фото или файл с кодом.",
            fromPicture = "Фото или файл",
            photo = "Фото",
            file = "Файл",
            cancel = "Отмена",
            reading = "Ищу QR-код в картинке…",
            noCodeInPicture = "В картинке не нашлось QR-кода",
            pictureUnreadable = "Эту картинку не открыть",
        )

        val En = ScanStrings(
            hint = "Point the camera at a QR code",
            close = "Close",
            deniedText = "No camera access. Allow it in Settings, or pick a photo or a file with the code.",
            failedText = "The camera did not start. Pick a photo or a file with the code.",
            fromPicture = "Photo or file",
            photo = "Photo",
            file = "File",
            cancel = "Cancel",
            reading = "Looking for a QR code in the picture…",
            noCodeInPicture = "No QR code in this picture",
            pictureUnreadable = "This picture does not open",
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
