package cx.m42.unitool

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
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

    private fun deliver(intent: Intent?) {
        intent?.data?.toString()?.let { Holder.superizer?.route?.deliver(it) }
        // A notification tap carries its payload as extras, not as data (13 §1). Absent a
        // transport there is nothing to receive one yet, but the path is the same one FCM will use.
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
        return buildUnitool(scope, debug = BuildConfig.DEBUG).also { Holder.superizer = it }
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
