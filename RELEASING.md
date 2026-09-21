# Releasing to Google Play

Everything below assumes the upload key already exists. It does — see [The upload key](#the-upload-key).

---

## 0. The short version: CI already does all of this

`.github/workflows/android-play.yml` builds, signs, verifies and uploads. Every step below is in
it, which is the point — a release that only one laptop can produce is a release nobody else can
make. Set the secrets once and the rest is a button:

```bash
./scripts/push-play-secrets.sh unitool-app/client
```

Then **Actions → Build and Deploy Android to Google Play → Run workflow**. Turn `upload` off for a
run that only builds; that artifact is what the first, manual upload needs (see §5 — the Play API
cannot create an app's first release).

[`.github/workflows/README-android-play.md`](.github/workflows/README-android-play.md) is the
reference for it: the secrets, the tracks, the version numbering, and why the library is published
to mavenLocal mid-run instead of being built from the checkout next door.

The rest of this file is the same procedure by hand — worth reading once, because it is what the
workflow is doing, and worth doing by hand exactly twice: for the first upload, and on the day CI
disagrees with a laptop about what it built.

---

## 1. Build the artifact

### First: commit

`generateBuildInfo` stamps the commit into `BuildInfo`, and the drawer's footer and Settings →
About both print it — with a `*` when the tree was dirty:

```
Сборка 1.0 (162049b*)
```

That string is the one thing a bug report is supposed to quote, and an asterisk in it means the
build cannot be reproduced from any commit. Commit before the build you actually ship. In CI the
question does not arise: a runner only ever has a clean checkout.

### Then build

Play takes an **Android App Bundle** (`.aab`), not an APK. An APK is still worth building to
install on a real device first, because it is the thing you can `adb install`.

```bash
# The version code Play sees. It must be larger than every code already uploaded, and it is the
# one number a local build cannot invent for you.
export ANDROID_VERSION_CODE=1

./gradlew :composeApp:bundleRelease     # play upload → composeApp/build/outputs/bundle/release/
./gradlew :composeApp:assembleRelease   # device smoke test → composeApp/build/outputs/apk/release/
```

Both come out signed, because `.secrets/keystore.properties` exists and the build reads it. Confirm
it before uploading — an unsigned bundle is rejected at the end of a long upload rather than the
start:

```bash
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs \
  composeApp/build/outputs/apk/release/composeApp-release.apk
```

## 2. Smoke-test the release build on a device

Not the debug build. The release build is the one that has been through R8, and shrinking is the
step that can remove something only the release build misses.

```bash
adb install -r composeApp/build/outputs/apk/release/composeApp-release.apk
```

Walk the four things R8 and edge-to-edge touch:

- The launcher icon is the black "=" tile, not a grey Android silhouette.
- The status bar clock is **dark** and nothing is hidden under it or under the home indicator.
- Back: from Settings returns to the calculator; with the drawer open closes the drawer; on the
  calculator with the drawer closed leaves the app.
- Settings → language switches the whole app, and survives a force-stop and relaunch.

## 3. The mapping file, which travels by itself

R8 renames everything, so a crash in the Play Console is unreadable without the symbol map. AGP
puts it inside the bundle for you — it is already there, as
`BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`, so **uploading the `.aab` is
the whole of the job** and there is nothing to attach by hand.

Verify it, rather than assuming:

```bash
unzip -l composeApp/build/outputs/bundle/release/composeApp-release.aab | grep proguard.map
```

The same map is written to `composeApp/build/outputs/mapping/release/mapping.txt`. Keep that copy
with the release — it is what deobfuscates a stack trace that reaches you by any route other than
the Play Console, and it is gitignored precisely because it must be archived rather than
committed.

If you ever upload a bare APK instead of the bundle, the map does *not* go with it and has to be
attached in the Console by hand. A map from a different build resolves a stack trace into
confident nonsense, so it must be the one from the exact build you shipped.

## 4. What Play asks for, and the answer for this app

| Play Console | Answer |
|---|---|
| App icon | `play/listing/icon-512.png` |
| Feature graphic | `play/listing/feature-graphic-1024x500.png` |
| Phone screenshots | the four named in `play/listing/screenshots.txt` — see below |
| Short & full description | `play/listing/description-en.md`, `description-ru.md` |
| Release notes | `play/whatsnew/`, one file per locale, no extension |
| Privacy policy URL | host `play/PRIVACY.md` somewhere public; the URL is required |
| Data safety | **Device or other IDs — collected, not shared**: the FCM registration token and the Firebase installation ID, for app functionality (notifications). Everything else is still "no". See `play/PRIVACY.md`, which is written to be the long answer to this form |
| Ads | none |
| Content rating | questionnaire only; a calculator rates Everyone / PEGI 3 |
| Target audience | 13+ avoids the Families policy programme, which this app has no reason to enter |
| Government app | no |
| Financial features | no |

Regenerate the screenshots, then check the whole listing against the limits Play enforces:

```bash
./gradlew :composeApp:desktopTest --tests '*Screenshots*'
python3 play/check-listing.py
```

`check-listing.py` exits non-zero on anything the Console would reject — a screenshot of the
wrong shape or with an alpha channel, a short description one character over, a privacy policy
still carrying a TODO. All of those otherwise fail *after* the upload rather than before it.

`android-play.yml` runs it on every release build, and treats it the way Play does: advisory for
internal testing, fatal for production. A listing problem must not stop a bundle from reaching
testers, because the listing is not in the bundle.

Which screenshots reach the listing is named in `play/listing/screenshots.txt`, not taken from
whatever is in the directory. The generator writes twelve frames, Play accepts eight, and two of
the twelve are the hidden Service Menu and bench app — a listing assembled by `ls` would put those
on the store page. The file holds the four §4 records, in the order Play shows them.

`build/screenshots/play/` is the Play-legal shape — 1080×1920, 9:16, no alpha channel — for all
twelve frames; the manifest above picks which of them ship. The set in
`build/screenshots/` is 412×915dp at 2x — the true shape of a modern phone, for the README — and
Play **rejects** it, because at 20:9 its long side is more than twice its short one.

## 5. First upload

1. Play Console → Create app. Package name `cx.m42.unitool` is fixed at this point and can never
   be changed for this listing.
2. Keep **Play App Signing** on (the default). Google then holds the key that signs what users
   install, and the key in `.secrets/` is only an *upload* key — which is what makes it
   replaceable if it leaks.
3. Upload to **Internal testing** first. It reaches testers in minutes; production review takes
   days, and the first upload is where a wrong version code or a missing icon shows up.
4. Promote to Production once the smoke test above passes on a real device.

---

## The upload key

Generated for this project and **not** in the repository:

```
key:         .secrets/upload-keystore.jks
alias:       upload
referenced:  .secrets/keystore.properties  (gitignored; holds the password)
```

The build looks for that properties file in two places — next to `composeApp/build.gradle.kts`,
which is where AGP's documentation puts it, and in `.secrets/`, which is where this project's key
actually is. The second exists so the passwords sit beside the `.jks` they open: a key backed up
without them is a key nobody can use.

**Back it up somewhere other than this machine, today.** With Play App Signing on, losing it is
recoverable — Google can reset the upload key — but it costs a support round-trip and days of not
being able to ship. Losing it *without* Play App Signing means the listing can never be updated
again.

The build takes the same values from the environment when they are set, which is what CI does
rather than carrying the file:

```
ANDROID_KEYSTORE_FILE  ANDROID_KEYSTORE_PASSWORD  ANDROID_KEY_ALIAS  ANDROID_KEY_PASSWORD
```

`scripts/push-play-secrets.sh` is what puts them there: it reads the same `.secrets/` material,
checks the keystore actually opens with that password and holds that alias, and sets the five
repository secrets on `unitool-app/client`.

With neither the file nor the variables, `release` builds unsigned and still succeeds — a clone
must not need a secret to compile.

To generate a replacement:

```bash
keytool -genkeypair -v \
  -keystore .secrets/upload-keystore.jks -storetype PKCS12 \
  -alias upload -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Unitool, OU=Unitool, O=m42.cx, L=Unknown, ST=Unknown, C=US"
```

`-validity 10000` is not arbitrary: Play requires an upload certificate valid past 22 October 2033.

---

## What the Superizer migration changed here

The procedure above is unchanged. Three facts about the build are new and worth knowing before a
release:

- **The library is a dependency now.** `gradle/libs.versions.toml` pins `superizer = "0.1.0"`. A
  release build must not be made against a local checkout by accident — run
  `SUPERIZER_FROM_MAVEN=1 ./scripts/verify.sh` first, which resolves the library from a repository
  and fails if the published artifacts are incomplete. `android-play.yml` enforces this rather than
  asking: it publishes the library to mavenLocal at the pinned version and builds with that
  variable set, and it checks the sources out somewhere `settings.gradle.kts` cannot turn into a
  composite build.
- **`assembleRelease` is part of verification.** R8 with an empty `proguard-rules.pro` only works
  because registration is explicit and the serialization keep rules travel inside
  `superizer:core`'s consumer rules. `scripts/verify.sh` builds a release on every run for that
  reason; it does not need a keystore to do it.
- **The listing screenshots moved.** `build/screenshots/play/` now holds eleven frames rather than
  five. The four that belong in the Play listing are `01-home`, `03-calculator`, `05-converter`
  and `08-settings`; the rest exist for the README and for review.

The hidden bench app is in the release build. That is deliberate — it is how a problem on a real
device gets diagnosed — and it is unreachable without the promo code, which is the same code that
opens the Service Menu. Changing `SERVICE_CODE` in `UnitoolHost.kt` before a public release is the
one thing to remember.
