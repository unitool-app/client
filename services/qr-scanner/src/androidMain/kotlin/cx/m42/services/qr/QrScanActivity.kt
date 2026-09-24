package cx.m42.services.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Size as AndroidSize
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The scanner's screen: the camera full-screen, a window to aim with, and the first QR code read
 * ends it.
 *
 * An activity rather than a composable inside the app, for the reason [QrScanner] gives — and
 * because only an activity is told the answer to a permission prompt.
 *
 * Every way out answers [AndroidQrScanner]: a code, or null for Close, Back, a refused camera, or
 * the system finishing it. A rotation is none of those; the manifest keeps it from recreating the
 * activity, so the camera does not blink.
 */
public class QrScanActivity : ComponentActivity() {

    private lateinit var strings: ScanStrings

    /** Re-read on every resume: the person may be coming back from the system's app settings. */
    private val granted = mutableStateOf(false)
    private val failed = mutableStateOf(false)
    private val torchOn = mutableStateOf(false)
    private val hasTorch = mutableStateOf(false)

    private val analysis: ExecutorService = Executors.newSingleThreadExecutor()
    private val found = AtomicBoolean(false)
    private var answered = false
    private var started = false

    private val controller: LifecycleCameraController by lazy {
        LifecycleCameraController(this).apply {
            // Preview is always on in a controller; analysis is the one use case added to it.
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            // 720p: enough pixels per module for a setup code across the room on a monitor,
            // few enough that a frame decodes well inside the next one's arrival.
            imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        AndroidSize(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                    ),
                )
                .build()
            setImageAnalysisAnalyzer(analysis, QrAnalyzer { text ->
                if (found.compareAndSet(false, true)) runOnUiThread { close(text) }
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light glyphs over the camera, whatever the device theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        strings = ScanStrings.of(intent.getStringExtra(EXTRA_LANGUAGE))
        granted.value = cameraAllowed()
        setContent { ScanScreen() }
    }

    override fun onResume() {
        super.onResume()
        granted.value = cameraAllowed()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysis.shutdown()
        if (isFinishing) answer(null)
    }

    private fun cameraAllowed(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun answer(text: String?) {
        if (answered) return
        answered = true
        AndroidQrScanner.deliver(text)
    }

    private fun close(text: String?) {
        answer(text)
        finish()
    }

    /** Called once the permission is there; binding before it would only fail inside CameraX. */
    private fun startCamera() {
        if (started) return
        started = true
        controller.bindToLifecycle(this)
        controller.torchState.observe(this) { state ->
            torchOn.value = state == TorchState.ON
            hasTorch.value = controller.cameraInfo?.hasFlashUnit() == true
        }
        controller.initializationFuture.addListener({
            // A back camera where there is one; a Chromebook or a tablet on a stand may only have
            // the front, and a code held up to it reads just as well.
            val selector = when {
                controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                controller.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                else -> null
            }
            if (selector == null) failed.value = true else controller.cameraSelector = selector
        }, ContextCompat.getMainExecutor(this))
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }

    @Composable
    private fun ScanScreen() {
        val allowed by granted
        // Whether the prompt has been answered in this activity. Until it has, a refusal screen
        // would flash up under the system dialog.
        var asked by rememberSaveable { mutableStateOf(false) }
        val prompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            granted.value = ok
            asked = true
        }
        LaunchedEffect(Unit) {
            if (!granted.value) prompt.launch(Manifest.permission.CAMERA)
        }
        LaunchedEffect(allowed) {
            if (allowed) startCamera()
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                allowed && failed.value -> Message(strings.failed, strings.failedText, action = null)
                allowed -> Viewfinder()
                asked -> Message(
                    title = strings.deniedTitle,
                    text = strings.deniedText,
                    // After one refusal Android still shows the dialog again; after "don't ask
                    // again" it silently answers no, and only the settings page can change that.
                    action = if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                        strings.allow to { prompt.launch(Manifest.permission.CAMERA) }
                    } else {
                        strings.openSettings to ::openAppSettings
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Pill(strings.close) { close(null) }
                if (allowed && hasTorch.value) {
                    Pill(strings.torch, filled = torchOn.value) { controller.enableTorch(!torchOn.value) }
                }
            }
        }
    }

    @Composable
    private fun Viewfinder() {
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    controller = this@QrScanActivity.controller
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // The dimmed surround with a clear square to aim with. Offscreen, so the Clear blend cuts a
        // hole in this layer rather than through to the window.
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            val side = minOf(size.width, size.height) * 0.72f
            val topLeft = Offset((size.width - side) / 2, (size.height - side) / 2)
            val corner = CornerRadius(20.dp.toPx())
            drawRect(Color.Black.copy(alpha = 0.55f))
            drawRoundRect(Color.Transparent, topLeft, Size(side, side), corner, blendMode = BlendMode.Clear)
            drawRoundRect(Color.White, topLeft, Size(side, side), corner, style = Stroke(3.dp.toPx()))
        }
        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                strings.hint,
                style = TextStyle(color = Color.White, fontSize = 17.sp, textAlign = TextAlign.Center),
            )
            Spacer(Modifier.height(48.dp))
        }
    }

    @Composable
    private fun Message(title: String, text: String, action: Pair<String, () -> Unit>?) {
        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                title,
                style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
            )
            Spacer(Modifier.height(12.dp))
            BasicText(
                text,
                style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, textAlign = TextAlign.Center),
            )
            if (action != null) {
                Spacer(Modifier.height(24.dp))
                Pill(action.first, filled = true, onClick = action.second)
            }
        }
    }

    @Composable
    private fun Pill(text: String, filled: Boolean = false, onClick: () -> Unit) {
        BasicText(
            text,
            style = TextStyle(color = if (filled) Color.Black else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (filled) Color.White else Color.White.copy(alpha = 0.2f))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }

    internal companion object {
        const val EXTRA_LANGUAGE: String = "cx.m42.services.qr.LANGUAGE"
    }
}

/** Feeds each frame's Y plane to [QrFrameDecoder]; CameraX calls it on one thread. */
private class QrAnalyzer(private val onFound: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val decoder = QrFrameDecoder()
    private var bytes = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        image.use {
            // YUV_420_888: plane 0 is luminance, one byte a pixel, rows padded out to rowStride.
            // The last row may stop at the picture's edge rather than the stride's, hence the
            // copy into a buffer of the full size instead of reading the plane in place.
            val plane = it.planes[0]
            val size = plane.rowStride * it.height
            if (bytes.size != size) bytes = ByteArray(size)
            val buffer = plane.buffer.apply { rewind() }
            buffer.get(bytes, 0, minOf(buffer.remaining(), size))
            decoder.decode(bytes, plane.rowStride, it.width, it.height)?.let(onFound)
        }
    }
}

/** The scanner's own few words, in the host's language rather than the device's. */
internal class ScanStrings(
    val hint: String,
    val close: String,
    val torch: String,
    val deniedTitle: String,
    val deniedText: String,
    val allow: String,
    val openSettings: String,
    val failed: String,
    val failedText: String,
) {
    companion object {
        fun of(tag: String?): ScanStrings =
            if (tag?.substringBefore('-')?.lowercase() == "ru") Ru else En

        private val Ru = ScanStrings(
            hint = "Наведите камеру на QR-код",
            close = "Закрыть",
            torch = "Фонарик",
            deniedTitle = "Нет доступа к камере",
            deniedText = "Без камеры QR-код не прочитать. Разрешите её — или закройте и вставьте ссылку из QR-кода вручную.",
            allow = "Разрешить",
            openSettings = "Открыть настройки",
            failed = "Камера не запустилась",
            failedText = "Закройте сканер и вставьте ссылку из QR-кода вручную.",
        )

        private val En = ScanStrings(
            hint = "Point the camera at a QR code",
            close = "Close",
            torch = "Torch",
            deniedTitle = "No access to the camera",
            deniedText = "A QR code cannot be read without it. Allow the camera, or close this and paste the link from the QR code instead.",
            allow = "Allow",
            openSettings = "Open settings",
            failed = "The camera did not start",
            failedText = "Close the scanner and paste the link from the QR code instead.",
        )
    }
}
