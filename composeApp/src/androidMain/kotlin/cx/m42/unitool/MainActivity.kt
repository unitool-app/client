package cx.m42.unitool

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import cx.m42.services.qr.AndroidQrScanner
import cx.m42.services.qr.QrScanner
import cx.m42.services.share.AndroidFileSharer
import cx.m42.services.share.FileSharer
import cx.m42.services.vault.AndroidKeystoreVault
import cx.m42.services.vault.SecretVault
import cx.m42.superizer.Superizer
import cx.m42.superizer.host.platform.AndroidHost
import cx.m42.superizer.host.push.PushTransport
import cx.m42.superizer.host.storage.initPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate, which is where the window is first laid out: asking for
        // edge-to-edge afterwards leaves the first frame inset and then jumps it. targetSdk 35+
        // draws behind the bars whether or not the app opts in, so the only real choice here is
        // what colour the system's own glyphs come out — and over a white app they have to be
        // dark. The library's AppScaffold pads for WindowInsets.safeDrawing, so nothing lands
        // under the clock or the home indicator.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, DarkScrim),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, DarkScrim),
        )
        super.onCreate(savedInstanceState)

        // Both before the first composition: the host resolves the stored language as it starts
        // up, and the drawer can vibrate on the very first gesture.
        initPrefs(this)
        AndroidHost.init(this)

        setContent {
            val superizer = remember { host() }
            remember { deliver(intent) }
            UnitoolApp(superizer)
        }
    }

    /**
     * A link that arrived while the process was already alive — a second notification tap, or a
     * `VIEW` intent for `unitool://…` from anywhere on the device.
     *
     * It goes into the same consume-once route the cold start reads (13 §5), so there is one place
     * that decides what a link means and it does not care which of the two paths delivered it.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deliver(intent)
    }

    /**
     * The POST_NOTIFICATIONS answer, handed back to the transport that asked for it.
     *
     * Forwarded blind rather than filtered on a request code: `PushTransport` ignores a code that
     * is not its own, so this stays correct if anything else here ever asks for a permission too.
     * It has to be here because only an Activity receives this callback, and the transport is an
     * object reached from a `FirebaseMessagingService` that has no activity at all.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PushTransport.onPermissionResult(requestCode, grantResults)
    }

    private fun deliver(intent: Intent?) {
        intent?.data?.toString()?.let { Holder.superizer?.route?.deliver(it) }
        // A notification tap carries its payload as extras, not as data (13 §1). This is the path
        // FCM uses: the notification Firebase itself draws for a `notification` payload arrives
        // here as extras, and the one AndroidNotifier draws arrives as `data` on the VIEW intent
        // above. Both end at the same route.
        intent?.extras?.let { extras ->
            val data = extras.keySet().mapNotNull { key ->
                extras.getString(key)?.let { key to it }
            }.toMap()
            if (data["schemaVersion"] != null) PushTransport.deliverTap(data)
        }
    }

    private fun host(): Superizer {
        val existing = Holder.superizer
        if (existing != null) return existing
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        // The application context, not this activity: a notification can be drawn long after the
        // activity that happened to build the host is gone.
        val notifier = AndroidNotifier(applicationContext)
        return buildUnitool(scope, debug = BuildConfig.DEBUG) {
            notifier(notifier)
            // The camera for QR codes (D19), only on a device that has one: the 2FA app draws its
            // scan button from whether this is registered. The scanner's words follow the host's
            // language setting, read at each scan because it can change while the process lives.
            if (AndroidQrScanner.available(applicationContext)) {
                service(QrScanner.Key, AndroidQrScanner(applicationContext) {
                    Holder.superizer?.settings?.langTag?.value ?: "en"
                })
            }
            // The share sheet (D19): the 2FA export hands its encrypted file to it.
            service(FileSharer.Key, AndroidFileSharer(applicationContext))
            // Encryption at rest (D19): the 2FA accounts are sealed under a Keystore key before
            // they reach the prefs file, and without one registered they would be stored plain.
            service(SecretVault.Key, AndroidKeystoreVault())
        }.also { Holder.superizer = it }
    }

    /**
     * One host per process, not per activity.
     *
     * A rotation destroys the activity and a new one composes immediately; rebuilding the host
     * there would re-register every app, lose the open session and start a second set of
     * collectors. The handler's snapshot covers a dead *process*, which is a different event.
     */
    private object Holder {
        var superizer: Superizer? = null
    }

    private companion object {
        /**
         * What sits behind the bars on API 26 and below, where a light navigation bar cannot draw
         * its handle dark. A translucent black is the platform's own answer, and the value AndroidX
         * documents for exactly this fallback.
         */
        const val DarkScrim = 0x801B1B1B.toInt()
    }
}
