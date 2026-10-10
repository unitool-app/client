import SwiftUI
import UIKit
import UserNotifications
import ComposeApp
import FirebaseCore
import FirebaseMessaging

/**
 * Push on iOS through Firebase Cloud Messaging — the route Android takes, and agentiz's pattern.
 *
 * The app registers an *FCM* token and Google talks to APNs for it, so neither this file nor a
 * server has to know which APNs environment a build is in: Firebase reads it off the registration.
 *
 * Firebase is optional here, as `google-services.json` is on Android: `GoogleService-Info.plist` is
 * a per-deployment secret, copied into the bundle only when it is present (see the "Copy Firebase
 * Config" build phase). Without it `FirebaseApp.configure()` would trap, so it is not called, and
 * the app runs with push inert — `PushTransport.available` is false.
 *
 * The delegate's whole job is to hand things to the shared Kotlin side: the token, a data message,
 * a tapped notification. Everything after that is the same router every other platform runs.
 */
class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        if Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil {
            FirebaseApp.configure()
            Messaging.messaging().delegate = self
            // No APNs registration and no token here (push-opt-in D421): Info.plist turns Firebase's
            // auto-init off, and Superizer's PushTransport.activate() registers with APNs and calls
            // `activate()` below once the person uses an app that takes pushes.
            UnitoolIos.shared.installMessaging(bridge: FirebaseTopics())
        }
        // Launched *by* a notification: the tap has no callback of its own then, only this.
        if let userInfo = launchOptions?[.remoteNotification] as? [AnyHashable: Any] {
            UnitoolIos.shared.deliverTap(data: strings(userInfo))
        }
        return true
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        // To Firebase, not to a server: the registration is the FCM token that follows.
        Messaging.messaging().apnsToken = deviceToken
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        // Almost always a missing aps-environment entitlement or a profile without Push — see
        // BUILD-IOS.md. Everything else keeps working; only push is lost.
        print("[push] APNs registration failed: \(error.localizedDescription)")
    }

    /// A data message (`content-available`) while the app is running or woken for it.
    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        UnitoolIos.shared.deliverMessage(data: strings(userInfo))
        completionHandler(.newData)
    }

    /// In the foreground iOS stays silent unless asked; a notification the host drew should show.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        UnitoolIos.shared.deliverTap(data: strings(response.notification.request.content.userInfo))
        completionHandler()
    }

    /**
     * The payload as the host reads it: FCM data is a flat map of strings, and anything else in
     * `userInfo` — the `aps` dictionary, Google's own keys' non-string values — is not part of it.
     */
    private func strings(_ userInfo: [AnyHashable: Any]) -> [String: String] {
        var out: [String: String] = [:]
        for (key, value) in userInfo {
            if let key = key as? String, let value = value as? String { out[key] = value }
        }
        return out
    }
}

extension AppDelegate: MessagingDelegate {
    /// On first registration and whenever Firebase rotates the token.
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard let fcmToken else { return }
        UnitoolIos.shared.deliverToken(token: fcmToken)
    }
}

/// Topic subscriptions, which only the Firebase SDK can make — handed to Kotlin at launch.
final class FirebaseTopics: NSObject, FirebaseMessagingBridge {
    func subscribe(topic: String) {
        Messaging.messaging().subscribe(toTopic: topic)
    }

    func unsubscribe(topic: String) {
        Messaging.messaging().unsubscribe(fromTopic: topic)
    }

    func activate() {
        Messaging.messaging().isAutoInitEnabled = true
    }
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
