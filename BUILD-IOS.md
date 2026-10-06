# Building Unitool for iPhone

The Kotlin side compiles anywhere: Kotlin 2.4 cross-compiles Apple targets, and `scripts/verify.sh`
compiles every module for `iosArm64` and `iosSimulatorArm64` on Linux. Linking the `ComposeApp`
framework and building `iosApp/` needs a Mac with Xcode 16 or later; App Store Connect takes only
builds from Xcode 26 (iOS 26 SDK), which is what CI uses.

## What is where

| | |
|---|---|
| `composeApp/src/iosMain` | `MainViewController()`, and `UnitoolIos`, the one object Swift talks to: links, the FCM token, messages, taps, and the Firebase topic bridge |
| `services/*/src/iosMain` | the camera (`IosQrScanner`, AVFoundation), the share sheet (`IosFileSharer`), the vault (`IosKeychainVault`, a Secure Enclave key) |
| `apps/twofa/src/iosMain` | `SecRandomCopyBytes`, and a clipboard that expires copied codes itself |
| `iosApp/` | the Xcode project: SwiftUI wrapper, `AppDelegate` with Firebase, Info.plist, entitlements, privacy manifest |
| Superizer `*/src/iosMain` | the host on iOS: defaults, Darwin networking, lifecycle, Face ID lock, FCM transport, privacy cover |

## Build

On the Mac, with Superizer checked out next to Unitool (`../superizer`, the same composite build
as on Linux), or with `SUPERIZER_FROM_MAVEN=1` and a GitHub Packages token:

```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64     # the framework alone
open iosApp/iosApp.xcodeproj                                    # or build it from the command line:

xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 16' \
  -derivedDataPath build/ios-dd CODE_SIGNING_ALLOWED=NO build
```

The project's first build phase runs `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`
itself, so Xcode alone is enough once the checkout is there. `scripts/verify.sh` does the simulator
build on a Mac and names the skip everywhere else.

agentiz builds its iOS app on a remote CI Mac and signs it locally (`agentiz/mobile-client/BUILD-IOS.md`).
Two traps from there apply here as well:

- `OTHER_LDFLAGS="-Wl,-U,_OBJC_CLASS_\$_UIViewLayoutRegion"` for SDKs older than iOS 26. Compose
  Multiplatform 1.11.1 looks that class up behind an availability check.
- `CADisableMinimumFrameDurationOnPhone` must stay in Info.plist. Compose aborts at startup without
  it.

One trap is Unitool's own. The simulator framework exists for Apple silicon only
(`iosSimulatorArm64`, no `iosX64`). `Config.xcconfig` therefore excludes `x86_64` from simulator
builds, and an Intel Mac, agentiz's CI Mac among them, can build for the simulator but cannot run
the result there. On that Mac, build for the device instead: `-configuration Release -sdk iphoneos
-destination generic/platform=iOS` with signing off, then sign where the certificate is.

## Signing

`iosApp/Configuration/Config.xcconfig` sets `TEAM_ID` (the team agentiz is signed with),
`BUNDLE_ID=cx.m42.unitool` and `APP_NAME=Unitool`. Code signing is Automatic. For push, the App ID
needs the **Push Notifications** capability. Release uses `iosApp.entitlements`
(`aps-environment=production`) and Debug uses `iosAppDebug.entitlements` (`development`). A profile
without the capability fails the build rather than producing an app that never registers.

Two ways to get a build onto a phone, both in `.github/workflows/ios.yml` (ssh-new/06 §4.1). Only
TestFlight is in use. The unsigned device build runs only on a push to the branch `ios-device`,
and a third lane, a Debug build for the simulator, only on a push to `ios-simulator`.

- **Unsigned device build, signed here.** The `device` lane builds Release for `iphoneos` with
  signing off and uploads `Unitool.app`. On a Mac with the development certificate and a
  *development* profile for the explicit App ID `cx.m42.unitool` (Push Notifications on, this
  iPhone's UDID in it):

      IOS_SIGN_IDENTITY=<cert SHA-1> IOS_PROFILE=~/Library/MobileDevice/Provisioning\ Profiles/<uuid>.mobileprovision \
        scripts/ios-sign-and-install.sh Unitool.app

  Nothing from the repository's secrets is involved. This is the loop for the on-device self-test.
- **TestFlight.** The `testflight` lane archives, signs with a distribution certificate and uploads
  to App Store Connect. It runs only when these repository secrets exist, and says so otherwise:

  | Secret | What |
  |---|---|
  | `IOS_DIST_CERT_P12_BASE64` | Apple Distribution certificate with its private key, `.p12`, base64 |
  | `IOS_DIST_CERT_PASSWORD` | the `.p12` password |
  | `IOS_PROFILE_BASE64` | App Store profile for `cx.m42.unitool` made with that certificate, `.mobileprovision`, base64 |
  | `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8_BASE64` | App Store Connect API key (App Manager), its issuer, the `.p8` base64 |

  Signing is manual, as in agentiz: automatic signing on a clean runner would mint a development
  certificate every run, and an App Manager key is not sure to be allowed to. The certificate and
  the key are the team's and agentiz's serve; the profile is per app. It is made at
  developer.apple.com → Profiles → **+** → App Store Connect → App ID `cx.m42.unitool` (with Push
  Notifications on, or `aps-environment` is missing) → the distribution certificate.
  `scripts/ios-ci-signing.py` checks the profile against the certificate and the app before
  anything is built and names the secret that is wrong.

  The App ID, the app record in App Store Connect and a TestFlight internal group are made in the
  console first. `CFBundleVersion` is the workflow run number, so every upload is a new build.
  Started by every push to `main` (as Android ships to Play; a push that touches only
  `unitool-cli/` builds nothing), by hand, or by putting the label `testflight` on a pull request
  into `main` — one upload per label added. The job after
  it, on Linux, waits for Apple to process the build and adds it to the TestFlight group (the
  repository variable `TESTFLIGHT_GROUP`, `dev` when unset; `scripts/testflight-distribute.py`).
  Export compliance answers itself from the second build on: Info.plist says the app encrypts
  (`ITSAppUsesNonExemptEncryption`), and the job hands each new build the declaration given once by
  hand in App Store Connect. It warns when there is none to hand, or Apple refused it.
  `iosApp/Configuration/ExportOptions.plist` is the export recipe; it holds no secret.

## Push (FCM)

Push goes through Firebase Cloud Messaging, as on Android, so the server sends one kind of message
to both. What iOS needs on top:

1. Register the iOS app `cx.m42.unitool` in the same Firebase project as the Android one and
   download `GoogleService-Info.plist` into `iosApp/iosApp/`. It is gitignored. CI would write it
   from `GOOGLE_SERVICE_INFO_PLIST_BASE64`.
2. Upload an APNs key (`.p8`) in the Firebase console → Project settings → Cloud Messaging. The key
   goes to Firebase, not to any server of ours.
3. Xcode resolves the Firebase Swift package (`firebase-ios-sdk`, FirebaseCore and
   FirebaseMessaging) on the first build.

Without the plist the "Copy Firebase Config" phase prints a warning and the app runs with push
inert: `AppDelegate` configures Firebase only when the file is in the bundle.

How a message travels:
- A data message reaches `didReceiveRemoteNotification` → `UnitoolIos.deliverMessage` → the router.
- A non-silent one is drawn as a local notification carrying the link.
- A tap arrives at `didReceive response` → `UnitoolIos.deliverTap`. A tap that cold-starts the app
  is held until the host exists.
- A background data message must be sent with `content-available: 1`.
- Anything that should show while the app is closed needs a `notification` block in the FCM
  message: iOS draws nothing for a bare data message.

## What iOS does differently

- **The 2FA lock** is Face ID or Touch ID with the passcode behind it (`LocalAuthentication`).
  - "The screen turned off" is "the phone locked", which iOS reports a few seconds after the side
    button.
  - Settings → Protection → "Set up" opens Unitool's own page in Settings. iOS has no public way
    into Face ID & Passcode.
- **Screenshots cannot be blocked on iOS.** The app switcher shows a blank screen instead of the
  2FA app, and screen recording, mirroring and AirPlay show it blank too. A screenshot, the system
  only reports after the fact.
- **The clipboard** expires a copied code after a minute by itself (`UIPasteboardOptionExpirationDate`)
  and keeps it off Universal Clipboard (`localOnly`).
- **The vault** sealing is ECIES under a Secure Enclave P-256 key; the simulator has no enclave and
  uses a software key in the Keychain with the same access class (`WhenUnlockedThisDeviceOnly`).
  Neither travels in a backup, so — as on Android — the 2FA export is the way to a new phone.
- **Export compliance** is the owner's answer (2026-10-05): the app encrypts — the 2FA export's own
  age (X25519, ChaCha20-Poly1305, RSA-OAEP), standard algorithms in addition to iOS's — nothing
  proprietary, not on the French store. `ITSAppUsesNonExemptEncryption` is `true` in Info.plist,
  not `false` as in base_layouts (HTTPS only there). Apple asks the rest once: TestFlight → the
  build → standard algorithms in addition to iOS → not France. That answer becomes an app encryption
  declaration, and `scripts/testflight-distribute.py` attaches it to every later build through the
  App Store Connect API, with the key the upload uses. If App Store Connect ever shows a key for the
  declaration, it goes into Info.plist as `ITSEncryptionExportComplianceCode` instead.
