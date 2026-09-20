# Unitool

A Kotlin Multiplatform / Compose Multiplatform starter — `cx.m42.unitool`. One `commonMain`
codebase renders the same UI on **Android**, **desktop (JVM)** and **web (Wasm)**.

What it does: a four-function calculator, and a settings page behind a slide-out drawer. That is
deliberately all of it — the point of this repository is the *frame* around a feature, not the
feature: navigation, the drawer, the design tokens, the language tables, the build stamp and the
test setup are what a second screen is meant to be dropped into.

---

## Requirements

| | |
|---|---|
| JDK | 21 (the build targets JVM 11 bytecode, but Gradle itself runs on 21) |
| Gradle | none — use the bundled `./gradlew` |
| Node.js | none — Gradle downloads its own Node/Yarn for the Wasm target |
| Android | Android SDK, only for the Android target |

---

## Running it

```bash
./gradlew :composeApp:run                             # desktop, a 420×820 window
./gradlew :composeApp:installDebug                    # Android, onto a device or emulator
./gradlew :composeApp:wasmJsBrowserDevelopmentRun     # web, then open http://localhost:8082/
./gradlew :composeApp:desktopTest                     # the tests
```

An APK lands in `composeApp/build/outputs/apk/debug/` (`assembleDebug`) and
`composeApp/build/outputs/apk/release/` (`assembleRelease`).

## Screenshots

```bash
./gradlew :composeApp:desktopTest --tests '*Screenshots*'
```

Rendered headless: no emulator, no browser, no window server. It is an ordinary UI test
([`Screenshots.kt`](composeApp/src/desktopTest/kotlin/cx/m42/unitool/Screenshots.kt)), so it can tap
its way to the state it wants to capture — a sum typed in, the drawer open, settings in either
language — before taking the picture. Because every target composes the same `commonMain` tree,
these are the screens a phone shows.

Two sets come out, because the two audiences want different pictures:

| | | |
|---|---|---|
| `build/screenshots/` | 412×915 dp at 2× | the shape of a phone people hold, 20:9. For this README. |
| `build/screenshots/play/` | 360×640 dp at 3× | 1080×1920, 9:16, no alpha channel. What the Play Console accepts. |

The second is not a crop of the first. Play rejects an image whose long side is more than twice
its short one, which 20:9 is, so the listing gets its own render rather than a letterboxed copy.

### The browser needs WebGL

Compose Multiplatform renders through Skiko, which requires a WebGL context. **A browser without
one shows a blank white page**, not an error. On a headless box, a VM or a container with no GPU,
launch Chromium with software rendering:

```bash
chromium --use-gl=angle --use-angle=swiftshader http://localhost:8082/
```

---

## How it is put together

```
composeApp/src/
  commonMain/       the entire app
    calculator/     Calculator.kt — the arithmetic, as a pure function over immutable state
    components/     AppScaffold (the drawer), AppButton, the hand-drawn icon pack
    data/           AppSettings + the one expect/actual the preferences need
    i18n/           Strings (one interface, one table per language) and how the language resolves
    platform/       expect/actual: device language, haptics, the system back gesture
    screens/        CalculatorScreen, SettingsScreen
    theme/          AppTheme — every colour and type style the app has
    App.kt          the destinations, the drawer's menu, the screen transition
  androidMain/      MainActivity + the Android actuals
    res/            the launcher icon, the window theme, the backup and locale rules
  desktopMain/      the window entrypoint + the JVM actuals
  wasmJsMain/       the ComposeViewport entrypoint, index.html + the browser actuals
  desktopTest/      UI tests, run on the desktop target
```

Only the entrypoint and the five `actual` implementations are per-platform; every screen is
shared.

### The principles carried over

- **No Material theme.** [`theme/AppTheme.kt`](composeApp/src/commonMain/kotlin/cx/m42/unitool/theme/AppTheme.kt)
  is the whole design system: a white surface, near-black text, one black primary accent, and colour
  reserved for *state*. A value not in that file is a value some screen invented on its own.
- **The drawer lies under the screen.** Opening pushes the screen right and scales it down to
  uncover a menu that was already there, rather than flying a panel in over it. One offset in
  pixels drives the travel, the scale, the corners and the fade, so a drag can interrupt the
  animation without the two fighting over the position. See
  [`components/AppScaffold.kt`](composeApp/src/commonMain/kotlin/cx/m42/unitool/components/AppScaffold.kt).
- **The menu belongs to the app, not to the screen.** It is built once in `App.kt` from the current
  destination, with the entry for wherever you already are disabled rather than hidden.
- **Navigation is a sealed hierarchy and an `AnimatedContent`**, with no navigation library: a
  destination carries what its screen was opened with, and every move states its direction so
  forward slides in from the right and back is the mirror image.
- **Logic is pure and lives apart from the UI.** The calculator is
  `CalculatorState.press(Key): CalculatorState`, tested without a composition; the screen only lays
  out keys.
- **Strings are an interface, not a resource file.** A language whose table is missing an entry does
  not compile, and text can be assembled outside composition.
- **The build stamps itself.** `generateBuildInfo` bakes the version and the git commit into
  `BuildInfo`, and the drawer's footer shows them — the first thing to ask for when a bug report
  comes in.
- **Back is a platform verb, so it is an `actual`.** Android has a back gesture and desktop and
  the browser do not, so `SystemBackHandler` joins haptics and the device language rather than
  being faked in shared code. An open drawer claims it before the navigation does, because it is
  composed deeper.
- **The release build is shrunk and obfuscated.** R8 is on, and
  [`proguard-rules.pro`](composeApp/proguard-rules.pro) is nearly empty on purpose — nothing in
  this app is reached by name at runtime, so nothing has to be kept by hand. A rule added there
  should say which class is reached reflectively and by what.
- **Secrets are never required to build.** No keystore ⇒ an unsigned release artifact, and the
  build still succeeds.

### Adding a screen

1. Add a `data object`/`data class` to `Destination` in `App.kt`.
2. Add its branch to the `when`.
3. Add a `MenuEntry` for it to the `menu` list.
4. Write the screen as `@Composable fun … (menu, onOpenSettings, …)` wrapping its body in
   `AppScaffold`.

Nothing else has to change.

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
`iosSimulatorArm64()` with a `ComposeApp` framework to `composeApp/build.gradle.kts`, an
`iosMain` source set with `MainViewController.kt` and the four `actual`s
(`PrefsStorage` over `NSUserDefaults`, `deviceLanguageTag` over `NSLocale`,
`platformHapticTick` over `UIImpactFeedbackGenerator`, and `SystemBackHandler` as a no-op — iOS
has an interactive pop gesture but no system back to intercept), and an Xcode project that builds
the framework through Gradle.
