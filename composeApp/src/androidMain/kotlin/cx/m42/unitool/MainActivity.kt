package cx.m42.unitool

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import cx.m42.services.link.AndroidLocalLink
import cx.m42.services.qr.AndroidQrScanner
import cx.m42.services.share.AndroidFileSharer
import cx.m42.services.files.AndroidFilePicker
import cx.m42.services.files.AndroidFileSaver
import cx.m42.unitool.settings.HostTools
import cx.m42.services.vault.AndroidKeystoreVault
import cx.m42.services.ssh.SshKeyringHost
import cx.m42.services.ssh.android
import cx.m42.services.ssh.keyring.PrefsRecordStore
import cx.m42.services.ssh.keyring.PromptText
import cx.m42.services.ssh.keyring.UseReason
import cx.m42.services.vault.VaultDiagnostics
import cx.m42.superizer.host.SystemClock
import cx.m42.unitool.i18n.securityStrings
import cx.m42.unitool.security.AndroidSelfTest
import cx.m42.unitool.security.KeyPrompts
import cx.m42.unitool.security.UnitoolSecurity
import cx.m42.superizer.Superizer
import cx.m42.superizer.host.platform.AndroidHost
import cx.m42.superizer.host.push.PushTransport
import cx.m42.superizer.host.storage.initPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A `FragmentActivity` rather than the plain `ComponentActivity` Compose needs: the lock over the
 * 2FA app is the system's `BiometricPrompt`, which can only be shown from one (Superizer 0.3.0,
 * note 06). It is still a ComponentActivity underneath, and no AppCompat theme is involved. A debug
 * build that loses this crashes on the first frame, on purpose.
 */
class MainActivity : FragmentActivity() {

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
        // The system's key prompt is drawn over whichever activity is in front (01 §5.1).
        Holder.activity = this

        setContent {
            val superizer = remember { host() }
            remember { deliver(intent) }
            UnitoolApp(superizer, Holder.security)
        }
    }

    /**
     * A link that arrived while the process was already alive — a second notification tap, or a
     * `VIEW` intent for `unitool://…` from anywhere on the device.
     *
     * It goes into the same consume-once route the cold start reads (13 §5), so there is one place
     * that decides what a link means and it does not care which of the two paths delivered it.
     */
    override fun onDestroy() {
        if (Holder.activity === this) Holder.activity = null
        super.onDestroy()
    }

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
        val security = androidSecurity(scope).also { Holder.security = it }
        // The camera for QR codes (D19), only on a device that has one: the 2FA app draws its scan
        // button from whether it is there. The scanner's words follow the host's language setting,
        // read at each scan because it can change while the process lives. The share sheet takes the
        // 2FA export; the system's picker opens a key file or a backup.
        val tools = HostTools(
            files = AndroidFilePicker(applicationContext),
            saver = AndroidFileSaver(applicationContext),
            qr = if (AndroidQrScanner.available(applicationContext)) {
                AndroidQrScanner(applicationContext) { Holder.superizer?.settings?.langTag?.value ?: "en" }
            } else {
                null
            },
            sharer = AndroidFileSharer(applicationContext),
            link = AndroidLocalLink(applicationContext),
        )
        return buildUnitool(scope, debug = BuildConfig.DEBUG, security = security, tools = tools) {
            notifier(notifier)
        }.also { Holder.superizer = it }
    }

    /**
     * The host's vault is the Keystore key the 2FA app sealed with before contract 3 — same alias,
     * same format — so a tester's accounts open after the update (04 §9, Б4). SSH keys go into the
     * Keystore where it is hardware (API 30+), under their passphrase where it is not; the records
     * have a prefs file of their own, in no backup (I10). The system prompt says what the sheet
     * would, in the host's language at the moment it is shown.
     */
    private fun androidSecurity(scope: CoroutineScope): UnitoolSecurity {
        val prompts = KeyPrompts()
        val vault = AndroidKeystoreVault()
        val keyring = SshKeyringHost.android(
            context = applicationContext,
            activity = { Holder.activity },
            texts = ::promptText,
            records = PrefsRecordStore(applicationContext),
            prompter = prompts,
            clock = SystemClock,
            scope = scope,
        )
        val selfTest = AndroidSelfTest(applicationContext, { Holder.activity }) { securityStrings(language()).selfTest }
        return UnitoolSecurity(keyring, prompts, chipVault = vault, chipVaultDiagnostics = VaultDiagnostics(vault), platformSelfTest = selfTest)
    }

    private fun promptText(reason: UseReason): PromptText {
        val strings = securityStrings(language())
        val key = reason.key
        return PromptText(
            title = strings.operation(reason, reason.app?.value),
            subtitle = strings.keyLine(key.label, key.comment, key.id.fingerprint),
            description = null,
            cancel = strings.cancel,
        )
    }

    private fun language(): String = Holder.superizer?.settings?.langTag?.value ?: "en"

    /**
     * One host per process, not per activity.
     *
     * A rotation destroys the activity and a new one composes immediately; rebuilding the host
     * there would re-register every app, lose the open session and start a second set of
     * collectors. The handler's snapshot covers a dead *process*, which is a different event.
     */
    private object Holder {
        var superizer: Superizer? = null
        var security: UnitoolSecurity? = null
        var activity: MainActivity? = null
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
