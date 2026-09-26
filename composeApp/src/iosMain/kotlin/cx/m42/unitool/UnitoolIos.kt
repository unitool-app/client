package cx.m42.unitool

import cx.m42.services.qr.IosQrScanner
import cx.m42.services.qr.QrScanner
import cx.m42.services.share.FileSharer
import cx.m42.services.share.IosFileSharer
import cx.m42.services.vault.IosKeychainVault
import cx.m42.services.ssh.SshKeyringHost
import cx.m42.services.ssh.keyring.FileRecordStore
import cx.m42.services.vault.VaultDiagnostics
import cx.m42.superizer.host.SystemClock
import cx.m42.unitool.security.KeyPrompts
import cx.m42.unitool.security.UnitoolSecurity
import cx.m42.superizer.Superizer
import cx.m42.superizer.host.push.IosMessaging
import cx.m42.superizer.host.push.Notifier
import cx.m42.superizer.host.push.PushTransport
import kotlin.experimental.ExperimentalNativeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSUUID
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * What Firebase Messaging is to the rest of the app, implemented in Swift (`iosApp/iOSApp.swift`):
 * the SDK is a Swift package in the app target, and Kotlin cannot link it. Declared here, in the
 * framework Swift imports, so the app implements a protocol of Unitool's and not one buried in a
 * library it never sees.
 */
interface FirebaseMessagingBridge {
    fun subscribe(topic: String)
    fun unsubscribe(topic: String)
}

/**
 * Everything iOS hands the app from outside a composition: a link opened, an FCM token, a message,
 * a tapped notification. `UnitoolIos.shared` from Swift.
 *
 * Also where the host lives — one per process, not per controller, for the reason MainActivity's
 * `Holder` gives. Links and taps that arrive before it exists (a cold start *by* a notification
 * hands its payload to `didFinishLaunching`, before any view) are held and passed on once it does.
 */
object UnitoolIos {

    private var superizer: Superizer? = null

    /** The keyring and the vault, for the root composable's sheet. */
    var security: UnitoolSecurity? = null
        private set
    private val heldLinks = mutableListOf<String>()
    private val heldTaps = mutableListOf<Map<String, String>>()

    @OptIn(ExperimentalNativeApi::class)
    fun host(): Superizer {
        superizer?.let { return it }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        // The Secure Enclave seals the host's secrets as it sealed the 2FA accounts before (Б4). The
        // keyring is level 3 here until the enclave backend runs on a phone (step 5b, D180).
        val prompts = KeyPrompts()
        val vault = IosKeychainVault()
        val keyring = SshKeyringHost.passphraseOnly(FileRecordStore(), prompts, SystemClock, cx.m42.superizer.runtime.Platform.Ios, scope)
        val security = UnitoolSecurity(keyring, prompts, chipVault = vault, chipVaultDiagnostics = VaultDiagnostics(vault)).also { this.security = it }
        val built = buildUnitool(scope, debug = Platform.isDebugBinary, security = security) {
            notifier(LocalNotifier)
            // The camera for QR codes (D19), only on a device that has one — the simulator has none,
            // and the 2FA app draws its scan button from whether this is registered.
            if (IosQrScanner.available()) {
                service(QrScanner.Key, IosQrScanner { superizer?.settings?.langTag?.value ?: "en" })
            }
            service(FileSharer.Key, IosFileSharer())
        }
        superizer = built
        heldLinks.forEach { built.route.deliver(it) }
        heldLinks.clear()
        // A tap goes to the push router, whose collector the host has only just launched on the
        // main queue. Queued behind it, so it is listening by the time the tap arrives.
        val taps = heldTaps.toList()
        heldTaps.clear()
        if (taps.isNotEmpty()) scope.launch { taps.forEach { PushTransport.deliverTap(it) } }
        return built
    }

    /** `unitool://…` from anywhere on the device — SwiftUI's `onOpenURL`. */
    fun deliverLink(url: String) {
        val host = superizer
        if (host == null) heldLinks += url else host.route.deliver(url)
    }

    /**
     * A tapped notification's `userInfo`, as strings. A push from our server carries the host's own
     * payload (`schemaVersion`, `appId`, `link`) and goes through the router, like on Android; one
     * drawn by [LocalNotifier] carries only the link.
     */
    fun deliverTap(data: Map<String, String>) {
        when {
            data["schemaVersion"] != null -> if (superizer == null) heldTaps += data else PushTransport.deliverTap(data)
            else -> data["link"]?.let(::deliverLink)
        }
    }

    /** A data message that arrived while the app was running. */
    fun deliverMessage(data: Map<String, String>) {
        PushTransport.deliverMessage(data)
    }

    /** `MessagingDelegate`'s token — the first one, and every rotation after it. */
    fun deliverToken(token: String) {
        PushTransport.deliverToken(token)
    }

    /** Called once Firebase is configured. A build without `GoogleService-Info.plist` never calls it. */
    fun installMessaging(bridge: FirebaseMessagingBridge) {
        PushTransport.messaging = object : IosMessaging {
            override fun subscribe(topic: String) = bridge.subscribe(topic)
            override fun unsubscribe(topic: String) = bridge.unsubscribe(topic)
        }
    }
}

/**
 * Draws the notification a non-silent data message asks for, as a local notification — iOS shows
 * nothing for a data message by itself. The link rides in `userInfo`, and a tap comes back through
 * [UnitoolIos.deliverTap] like any other.
 */
private object LocalNotifier : Notifier {
    override fun show(title: String?, body: String?, link: String) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title ?: "Unitool")
            setBody(body.orEmpty())
            setUserInfo(mapOf<Any?, Any?>("link" to link))
            setSound(UNNotificationSound.defaultSound)
        }
        // The link as the identifier, so a second push about the same screen replaces the first —
        // what the tag does on Android.
        val request = UNNotificationRequest.requestWithIdentifier(link.ifEmpty { NSUUID().UUIDString }, content, trigger = null)
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, withCompletionHandler = null)
    }
}
