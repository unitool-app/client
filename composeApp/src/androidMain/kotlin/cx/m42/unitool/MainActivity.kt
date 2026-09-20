package cx.m42.unitool

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cx.m42.unitool.data.initPrefs
import cx.m42.unitool.i18n.AppLocale
import cx.m42.unitool.platform.initHaptics

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate, which is where the window is first laid out: asking for
        // edge-to-edge afterwards leaves the first frame inset and then jumps it. targetSdk 35+
        // draws behind the bars whether or not the app opts in, so the only real choice here is
        // what colour the system's own glyphs come out — and over a white app they have to be
        // dark. AppScaffold already pads for WindowInsets.safeDrawing, so nothing lands under
        // the clock or the home indicator.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, DarkScrim),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, DarkScrim),
        )
        super.onCreate(savedInstanceState)

        // Both before the first composition: App() reads the stored language as it starts up, and
        // the drawer can vibrate on the very first gesture.
        initPrefs(this)
        initHaptics(this)

        // After initPrefs, because it is the stored choice that decides whether the device has a
        // say at all. Runs on every create, not just the first: changing the app's language in
        // Android 13's own settings restarts the activity inside a process that is already warm,
        // and AppLocale.start() would see its once-per-process latch and keep the old language.
        AppLocale.refreshFromDevice()

        setContent {
            App()
        }
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
