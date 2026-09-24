package cx.m42.services.qr

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [QrScanner] on Android: starts [QrScanActivity] and waits for what it reads.
 *
 * [language] is asked at every scan rather than once, because the host's language is a setting
 * that can change while the process lives, and the scanner's few words should follow it rather than
 * the device's.
 */
public class AndroidQrScanner(
    private val context: Context,
    private val language: () -> String,
) : QrScanner {

    override suspend fun scan(): String? = withContext(Dispatchers.Main.immediate) {
        val result = CompletableDeferred<String?>()
        // One scanner at a time. A second request answers the first with null rather than leaving
        // it waiting on an activity that is no longer its own.
        pending?.complete(null)
        pending = result
        context.startActivity(
            Intent(context, QrScanActivity::class.java)
                .putExtra(QrScanActivity.EXTRA_LANGUAGE, language())
                // The application context has no task of its own; with this flag the activity
                // joins the one with Unitool's affinity, on top of the screen that asked.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            result.await()
        } finally {
            if (pending === result) pending = null
        }
    }

    public companion object {
        // Main thread only, both ways: set in scan() above and read from the activity's callbacks.
        private var pending: CompletableDeferred<String?>? = null

        internal fun deliver(text: String?) {
            pending?.complete(text)
            pending = null
        }

        /**
         * Whether the device has a camera at all. A host registers the service only when it does,
         * so a phone without one — or a Chromebook with the lid closed — shows no button that
         * could only ever fail.
         */
        public fun available(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
}
