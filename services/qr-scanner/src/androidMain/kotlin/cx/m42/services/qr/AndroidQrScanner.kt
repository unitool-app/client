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
        supersede()
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

    /**
     * The same activity in series mode: it stays open, hands every new code to [onCode] through
     * [seriesCode] and shows the label that comes back.
     */
    override suspend fun scanSeries(hint: String?, onCode: (String) -> SeriesStep): Boolean =
        withContext(Dispatchers.Main.immediate) {
            supersede()
            val current = Series(++lastSeriesId, onCode)
            series = current
            context.startActivity(
                Intent(context, QrScanActivity::class.java)
                    .putExtra(QrScanActivity.EXTRA_LANGUAGE, language())
                    .putExtra(QrScanActivity.EXTRA_SERIES, current.id)
                    .putExtra(QrScanActivity.EXTRA_LABEL, hint)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            try {
                current.result.await()
            } finally {
                if (series === current) series = null
            }
        }

    /**
     * One series: what the app does with a code, and how it ended. [id] travels in the intent, so
     * an activity left over from an earlier series cannot feed codes into a later one.
     */
    private class Series(val id: Long, val onCode: (String) -> SeriesStep) {
        val result = CompletableDeferred<Boolean>()
    }

    public companion object {
        // Main thread only, both ways: set in scan() and scanSeries() above and read from the
        // activity's callbacks.
        private var pending: CompletableDeferred<String?>? = null
        private var series: Series? = null
        private var lastSeriesId = 0L

        /** Ends whatever scan is still waiting, one code or a series, as closed. */
        private fun supersede() {
            pending?.complete(null)
            pending = null
            series?.result?.complete(false)
            series = null
        }

        internal fun deliver(text: String?) {
            pending?.complete(text)
            pending = null
        }

        /**
         * A new code for series [id]; what the app answered, or null when that series is no longer
         * the one waiting — or when [Series.onCode] threw, which ends the series with that throw
         * in the caller of scanSeries rather than in the activity's main-thread callback.
         */
        internal fun seriesCode(id: Long, text: String): SeriesStep? {
            val current = series?.takeIf { it.id == id } ?: return null
            val step = try {
                current.onCode(text)
            } catch (e: Throwable) {
                current.result.completeExceptionally(e)
                series = null
                return null
            }
            if (step == SeriesStep.Done) {
                current.result.complete(true)
                series = null
            }
            return step
        }

        /** Series [id] closed without the app ending it: Close, Back, no camera, or the system. */
        internal fun seriesClosed(id: Long) {
            val current = series?.takeIf { it.id == id } ?: return
            current.result.complete(false)
            series = null
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
