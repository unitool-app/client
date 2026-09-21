# Unitool

A Kotlin Multiplatform / Compose Multiplatform **Super App** — `cx.m42.unitool`. One `commonMain`
codebase renders the same UI on **Android**, **desktop (JVM)** and **web (Wasm)**.

Unitool is a *host*: it ships a home screen of apps, a drawer, settings, activation by promo code or
QR payload, deep links and a service menu — and the apps themselves are independent modules that
know nothing about it. The framework that makes that work is
[Superizer](https://github.com/adminization/superizer), developed alongside this repository and consumed
here as an ordinary package.

Three apps ship today:

| | |
|---|---|
| **Калькулятор** | four functions, percent, and a scientific mode a config turns on |
| **Конвертер валют** | ECB rates over the network, with a cache that works offline |
| **Test App** | hidden; one card per runtime service, for whoever is building on this |

Android builds receive push notifications through Firebase. The routing — which app a message is
for, which screen a tap opens, and the rule that a push never unlocks a hidden app — is Superizer's
and is the same on every target; Unitool supplies the Firebase config and the code that draws the
notification. A build without `composeApp/google-services.json` compiles and runs exactly as
before, and simply never receives one.

Home is what you chose, not everything installed: a fresh install starts with the calculator, the
"+" tile opens All Apps where a tap puts an app on Home, and a long press on any tile takes it off
again. A hidden app lands on Home the moment its promo code or QR unlocks it.

---

## Requirements

| | |
|---|---|
| JDK | 21 (the build targets JVM 11 bytecode, but Gradle itself runs on 21) |
| Gradle | none — use the bundled `./gradlew` |
| Node.js | none for the build; the web smoke test in `scripts/` wants Node 20+ |
| Android | Android SDK, only for the Android target |
| Superizer | optional: a checkout at `../superizer` is used automatically; otherwise the published package is |

---

## Running it

```bash
./gradlew :composeApp:run                             # desktop, a 420×820 window
./gradlew :composeApp:installDebug                    # Android, onto a device or emulator
./gradlew :composeApp:wasmJsBrowserDevelopmentRun     # web, then open http://localhost:8082/
./scripts/verify.sh                                   # everything, one report
```

An APK lands in `composeApp/build/outputs/apk/debug/` (`assembleDebug`) and
`composeApp/build/outputs/apk/release/` (`assembleRelease`).

### Trying the activation paths by hand

```bash
# desktop: the stand-ins for a QR code and a notification tap
./gradlew :composeApp:run --args="--link=unitool://app/currency-converter/rate?pair=EUR-RUB"

# Android: the real thing
adb shell am start -a android.intent.action.VIEW -d "unitool://app/calculator"

# web: the query is the query string
http://localhost:8082/?activate=%7B%22type%22%3A%22app_activation%22%2C%22appId%22%3A%22calculator%22%7D
```

Promo codes this build ships: `SCI` opens the calculator in scientific mode, `TEST-2026` unlocks the
hidden bench app and puts it on Home, `SERVICE` opens the Service Menu. Seven taps on the build stamp
in Settings do the same, but only in a debug build — in a release the promo code is the only door.

## Screenshots

```bash
./gradlew :composeApp:desktopTest --tests '*Screenshots*'
```

Rendered headless: no emulator, no browser, no window server. It is an ordinary UI test
([`Screenshots.kt`](composeApp/src/desktopTest/kotlin/cx/m42/unitool/Screenshots.kt)), so it taps its
way to the state it wants — a promo code applied, the drawer open, settings in either language —
before taking the picture. Because every target composes the same `commonMain` tree, these are the
screens a phone shows.

Two sets come out, because the two audiences want different pictures:

| | | |
|---|---|---|
| `build/screenshots/phone/` | 412×915 dp at 2× | the shape of a phone people hold, 20:9. For this README. |
| `build/screenshots/play/` | 360×640 dp at 3× | 1080×1920, 9:16, no alpha channel. What the Play Console accepts. |

The second is not a crop of the first. Play rejects an image whose long side is more than twice its
short one, which 20:9 is, so the listing gets its own render rather than a letterboxed copy.

### The browser needs WebGL

Compose Multiplatform renders through Skiko, which requires a WebGL context. **A browser without one
shows a blank white page**, not an error. On a headless box, a VM or a container with no GPU, launch
Chromium with software rendering:

```bash
chromium --use-gl=angle --use-angle=swiftshader http://localhost:8082/
```

---

## How it is put together

```
composeApp/src/               THE HOST, and nothing else
  commonMain/UnitoolHost.kt   the whole of it: a name, three register() calls, a promo table
  androidMain/                MainActivity, the deep-link intent filter, the launcher icon
  desktopMain/ wasmJsMain/    the window and the viewport entrypoints, and the wasm test bridge
  desktopTest/                end-to-end tests and the screenshots

apps/calculator/              an app: manifest, config, instance, screen, its own strings
apps/currency-converter/      an app: the same shape, plus a repository over runtime.network
```

Everything the two directories *share* — the drawer, All Apps, Settings, the activation screens, the
design tokens, the icon pack, the storage and network implementations — lives in Superizer, not
here. That split is the point: `composeApp` is a list of what this product contains, and an app is a
module that would run under any other host or on its own.

### The principles carried over

- **No Material theme.** The design system is a set of tokens: a white surface, near-black text, one
  black primary accent, and colour reserved for *state*. It lives in `superizer:ui-theme` now, so a
  third-party component set can match Unitool without depending on Unitool.
- **The drawer lies under the screen.** Opening pushes the screen right and scales it down to uncover
  a menu that was already there. One offset in pixels drives the travel, the scale, the corners and
  the fade, so a drag can interrupt the animation without the two fighting over the position.
- **The menu belongs to the host, not to the screen.** It is built from the registry, with the entry
  for wherever you already are disabled rather than hidden.
- **Navigation is a sealed hierarchy and an `AnimatedContent`**, with no navigation library. An open
  app is one destination among the host's own.
- **Logic is pure and lives apart from the UI.** The calculator is
  `CalculatorState.press(Key): CalculatorState`; the converter is `Rates.convert(amount, from, to)`.
  Both are tested without a composition and without a network.
- **Strings are interfaces, not resource files — and each app owns its own.** A language whose table
  is missing an entry does not compile. An app that had to add a line to the host's table to name its
  own button would be an app that cannot ship anywhere else.
- **The build stamps itself.** `generateBuildInfo` bakes the version and the git commit into
  `BuildInfo`; the drawer's footer shows them, and so does `HostInfo`.
- **Back is a platform verb, so it is an `actual`.** An open drawer claims it before the app does,
  and the app before the host, because that is the order they are composed in.
- **The release build is shrunk and obfuscated.** R8 is on and `proguard-rules.pro` is nearly empty:
  registration is explicit, nothing is reached by name at runtime, and the serialization keep rules
  travel with the library.
- **Secrets are never required to build.** No keystore ⇒ an unsigned release artifact, and the build
  still succeeds.

### Adding an app

1. `include(":apps:weather")` in `settings.gradle.kts`.
2. A `build.gradle.kts` copied from `apps/calculator` — the library by coordinates, never by project
   path, and never `superizer:host` or another app. `./gradlew checkDependencyRules` enforces that.
3. Write `WeatherApp : SuperizerApp<WeatherConfig>()` — a manifest, a config spec, an instance.
4. `implementation(project(":apps:weather"))` in `composeApp/build.gradle.kts`.
5. `register(WeatherApp())` in `UnitoolHost.kt`.

Nothing in `superizer:core`, `superizer:ui` or `superizer:host` changes.
`composeApp/src/desktopTest/.../ExtensibilityTest.kt` is that claim as a test: it registers a fourth
app that exists only in the test source set and drives it from the home screen.

### Working on the library at the same time

`settings.gradle.kts` includes `../superizer` as a composite build when the directory is there, so a
change to the contract is visible here on the next build. Dependencies are always written as Maven
coordinates (`libs.superizer.core`), never as project paths — that is what lets the same text resolve
from a repository when the checkout is absent:

```bash
SUPERIZER_FROM_MAVEN=1 ./scripts/verify.sh   # against the published package instead
```

---

## Releasing

[`RELEASING.md`](RELEASING.md) is the procedure: what to build, what to check on a device before
uploading, and every field the Play Console asks for with this app's answer already written down.
The listing's own assets — icon, feature graphic, both descriptions, the privacy policy — are in
[`play/`](play/), and `python3 play/check-listing.py` holds all of them against the limits the
Console enforces, so a rejection happens here rather than after an upload.

The short version of the signing half: values come from the environment (that is what CI has) or
from a gitignored `composeApp/keystore.properties` (that is what a person building by hand has):

```properties
storeFile=/path/to/upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

`ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
are the environment equivalents. `ANDROID_VERSION_CODE` sets the version code, which Play requires
to grow on every upload; a local build keeps 1.

---

## Adding iOS

The targets are left out so the repository builds on Linux. On a Mac, add `iosArm64()` and
`iosSimulatorArm64()` to `composeApp`, each app module and every Superizer module, an `iosMain`
source set with `MainViewController.kt`, and the platform `actual`s the library declares:
`PrefsStorage` over `NSUserDefaults`, `deviceLanguageTag` over `NSLocale`, `platformHapticTick` over
`UIImpactFeedbackGenerator`, `PlatformLifecycle` over `UIApplication` notifications, and
`SystemBackHandler` as a no-op — iOS has an interactive pop gesture but no system back to intercept.
The source-set naming in the library already leaves room for them.
