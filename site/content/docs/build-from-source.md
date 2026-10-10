---
title: Build from source
description: Running Unitool on the desktop, in the browser and on a phone from the source tree.
---

# Build from source

Unitool is a Kotlin Multiplatform and Compose Multiplatform project. One `commonMain` codebase
renders the same UI on Android, iOS, the desktop (JVM) and the web (WebAssembly).

> The Unitool repository is not public yet, so this page is for people who have access to it.
> [Superizer]({{superizerUrl}}), the framework underneath, is public.

## Requirements

| | |
|---|---|
| JDK | 21 |
| Gradle | none — use the bundled `./gradlew` |
| Android SDK | for the Android target (Gradle configures the whole project, so it is needed for any build) |
| Xcode on a Mac | only to link and run the iOS app; the Kotlin side compiles on Linux too |
| Node.js 20+ | only for the web smoke test |

## Run it

```sh
./gradlew :composeApp:run                             # desktop, a 420×820 window
./gradlew :composeApp:installDebug                    # Android, onto a device or emulator
./gradlew :composeApp:wasmJsBrowserDevelopmentRun     # web, then open http://localhost:8082/
./scripts/verify.sh                                   # every check, one report
```

The APKs land in `composeApp/build/outputs/apk/debug/` and `…/release/`.

**The browser needs WebGL.** Compose renders through Skiko, which needs a WebGL context; a browser
without one shows a blank white page, not an error. On a machine with no GPU, start Chromium with
software rendering:

```sh
chromium --use-gl=angle --use-angle=swiftshader http://localhost:8082/
```

## Without secrets

No keystore, no Firebase config, no store credentials are needed to build. Without a keystore the
release build is simply unsigned; without the Firebase config the app builds and runs and never
receives a notification.

## Opening a tool by link

```sh
# desktop: the stand-in for a QR code or a notification tap
./gradlew :composeApp:run --args="--link=unitool://app/currency-converter/rate?pair=EUR-RUB"

# Android
adb shell am start -a android.intent.action.VIEW -d "unitool://app/calculator"
```

## Screenshots

```sh
./gradlew :composeApp:desktopTest --tests '*Screenshots*'
```

Rendered headless — no emulator, no browser, no window server. It is an ordinary UI test that taps
its way to each state before taking the picture, and because every platform composes the same
tree, these are the screens a phone shows. The pictures on this site come from it.

## Tests

`./scripts/verify.sh` runs everything: unit and UI tests on the JVM, the dependency rules, the
command-line tool natively, the web smoke test, and the Android device tests when a device is
attached.
