# Android → Google Play (GitHub Actions)

`android-play.yml` builds the Android app on an Ubuntu runner, signs it with the upload key and
publishes the bundle to Google Play. It runs on every push to `main`/`master` and can be started
by hand (**Actions → Build and Deploy Android to Google Play → Run workflow**), which offers two
inputs: `upload` — turn it off for a build that only leaves an artifact behind — and `track`.

There is no Fastlane. Unlike iOS, nothing on the Android path needs a keychain, a provisioning
profile or Xcode, so Gradle plus one upload action is the whole conveyor.

The repository is `unitool-app/client`; the Play package is `cx.m42.unitool`. The two are unrelated
strings and neither can be inferred from the other — the package is fixed the moment the Play app
record is created and can never be changed for that listing afterwards.

## What a run does

1. Checks that all six secrets exist, before spending forty minutes finding out one does not, and
   writes `composeApp/google-services.json` out of one of them. That file is gitignored and so
   absent from the checkout; without it the build applies no Firebase plugin and silently produces
   an app that can never receive a push.
2. Writes the upload keystore into `RUNNER_TEMP` — never into the workspace, where the artifact
   upload at the end would sweep it up — and confirms with `keytool` that it holds the alias the
   build is about to ask for.
3. Checks out the library and publishes it to mavenLocal, then builds everything with
   `SUPERIZER_FROM_MAVEN=1`. See [The library](#the-library) below; this is the step that differs
   most from a single-repository app.
4. Runs `./gradlew verify` — the dependency rules of 02, plus the host and app tests.
5. Runs `play/check-listing.py` against the screenshots that test just wrote. Advisory for every
   track but `production`, which is how Play behaves: a listing problem must not stop a bundle
   from reaching testers, because the listing is not in the bundle. Always in the job summary.
6. Builds `:composeApp:bundleRelease` (the `.aab` Play takes) and `:composeApp:assembleRelease`
   (an `.apk`, because a bundle cannot be sideloaded and RELEASING.md §2 wants the release build
   on a real device). Both, plus `mapping.txt`, are kept as run artifacts for 90 days.
7. Verifies the APK is actually signed and that the bundle carries its `proguard.map`.
8. Uploads the bundle with `r0adkll/upload-google-play`.

## The library

Superizer is a dependency, not a subproject, and `settings.gradle.kts` resolves it one of two ways:
a checkout at `../superizer` becomes a composite build, and anything else falls back to Maven
coordinates. RELEASING.md is explicit that a release must not be built the first way — a composite
substitutes whatever is in that directory regardless of what `gradle/libs.versions.toml` pins, so
the shipped binary would contain a version of the library that no number describes.

So the workflow does the second: it checks the library out into `RUNNER_TEMP` (deliberately *not*
`../superizer`), runs `publishToMavenLocal` at exactly the version the catalogue names, and exports
`SUPERIZER_FROM_MAVEN=1` for every step after it. That is mode C of 12 §2, and it is also the only
thing that proves the library publishes at all — real POMs, real Gradle module metadata, and the
consumer ProGuard rules that let `composeApp/proguard-rules.pro` stay almost empty.

Two repository variables steer it, and neither is normally set:

| Variable | Default | When to set it |
| --- | --- | --- |
| `SUPERIZER_REPO` | `adminization/superizer` | A fork, or a rename |
| `SUPERIZER_REF` | the default branch | Pinning a release to a tag or a commit |

A `SUPERIZER_TOKEN` secret would be needed only if that repository were private, because the job's
own token cannot read another repository and the checkout fails with a 404 rather than a 403.
`adminization/superizer` is public, so none is set.

> `verify.yml` used to name the library `m42cx/superizer`, which is not a repository that exists —
> so that job had never got past its second step. Both workflows now read the same variable, so the
> next rename is one setting rather than two files that drift apart again.

### The ordering this creates

The library is published from its **remote**, not from anything on your machine. So a change that
spans both repositories has one legal order, and getting it wrong produces a confusing failure
rather than an obvious one:

1. Commit and push Superizer.
2. Then push Unitool.

Push them the other way round and CI checks out a library without the change, publishes it under
the version `libs.versions.toml` pins, and fails compiling Unitool against it — while the same
build passes on the laptop that has both checkouts. Worse, `publishToMavenLocal` will happily
stamp `0.2.0` onto a tree that is not 0.2.0, because the version is a `-P` flag and not a fact
about the source.

This is what the `SUPERIZER_REF` variable is for once the pace picks up: pin a release build to
the exact library commit it was tested against, instead of to whatever the default branch holds
at the moment the job starts.

## Which track a build lands on

Play names its tracks in the API, and the name is **not** the label the console shows. The four
built-in ids are `internal`, `alpha` (closed testing), `beta` (open testing) and `production`.

The default here is `internal`, because that is what RELEASING.md §5 says the first upload goes to:
it reaches testers in minutes, where a production review takes days and the first upload is exactly
where a wrong version code or a missing icon shows up. Promote from the console once the release
build has been through the device smoke test in RELEASING.md §2.

A closed track created by hand in the console is a different matter: it gets an id of its own that
CI cannot guess, so it goes in the `PLAY_TRACK` repository variable (**Settings → Secrets and
variables → Actions → Variables**) rather than in this file. The manual run's `track` input beats
the variable, which beats the `internal` default. An id no track has fails the upload with `Track
not found` and nothing else — the error does not list the tracks that do exist, so read the id off
the Play Developer API (`edits.tracks.list`) rather than off the console's heading.

Anything past internal testing wants two more things:

* **A tester list** — Play Console → Testing → the track → *Testers*. Without one the release is
  created and visible in the console, and nobody can install it.
* **The content forms filled in** — privacy policy, data safety, content rating, target audience,
  ads. RELEASING.md §4 has Unitool's answer to each. The upload still succeeds without them; the
  release simply stays undistributed, with the reason under the app's *Dashboard*.

## Required repository secrets

| Secret | What it is |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 .secrets/upload-keystore.jks` — the upload key |
| `ANDROID_KEYSTORE_PASSWORD` | Its store password |
| `ANDROID_KEY_ALIAS` | Alias inside the keystore (`upload`) |
| `ANDROID_KEY_PASSWORD` | Password of that key; equal to the store password for a PKCS12 keystore |
| `PLAY_SERVICE_ACCOUNT_JSON` | The whole service-account JSON, pasted as-is, of an account with Play Developer API access |
| `GOOGLE_SERVICES_JSON_BASE64` | `base64 -w0 composeApp/google-services.json` — the Firebase Android config for project `unitool-b1529`. Per-deployment, gitignored, and the difference between a build that receives notifications and one that cannot |

The first four come from `.secrets/`, and one script puts them there:

```bash
./scripts/push-play-secrets.sh unitool-app/client
```

It checks that the keystore opens with that password and holds that alias before it sets anything,
and it uses `printf` rather than `echo` — a trailing newline breaks a base64 blob once it is in the
runner, and the only symptom is a keystore Java will not open.

The last two are not derivable from anything in the repository. `GOOGLE_SERVICES_JSON_BASE64` is
downloaded from the Firebase console for package `cx.m42.unitool`; the workflow refuses a config
whose client list does not contain that package, because the alternative is a green build that
receives nothing.

`PLAY_SERVICE_ACCOUNT_JSON` is the awkward one. It is a file somebody
downloads once from Google Cloud, and the script prints the steps and exits 1 when it is missing —
after setting the other four, because the signing key exists on exactly one machine and waiting for
a web form before copying it off is the wrong order. Save it as `.secrets/play-service-account.json`
and run the script again; setting the first four a second time costs nothing.

> **It is not the Firebase service account.** The key in the reference app's
> `data/Agentiz-push-config/firebase-service-account.json` signs FCM messages and cannot reach the
> Play Developer API at all — asked for `cx.m42.agentiz`, Google answers *"Google Play Android
> Developer API has not been used in project 12377090594 before or it is disabled"*. The two are
> different accounts with different grants, and the reference app's real Play credential exists
> only as a GitHub secret, which GitHub will not read back to anybody.

## Version numbers

* **versionCode** — `1000 + github.run_number`, read by `composeApp/build.gradle.kts` from
  `ANDROID_VERSION_CODE`. Play refuses a code it has already accepted, and a run number only ever
  grows within a repository, so re-running an old commit cannot collide with another CI upload.
  The offset reserves everything below 1000 for bundles built by hand, which is not hypothetical:
  the first release has to be uploaded that way. A local build with the variable unset keeps `1`.
* **versionName** — `appVersionName` in `composeApp/build.gradle.kts` (`1.0`). Play shows it to
  testers and does not care whether it repeats.
* **release name** — the bare `github.run_number`, which is what Play Console lists the release
  under. Only ever read by a person, so it is the one number that opens the run that built it; the
  run knows its own commit, which is why no SHA is carried. It tracks the versionCode by
  construction — release `4` is versionCode `1004`.
* **What's new** — `play/whatsnew/`, edited by a person before a release. See the README there.

## Before the first run

Three things have to exist, and none of them can be created from CI. Two of them already do — the
Play Developer API was asked, rather than the console being read over somebody's shoulder:

1. **The app record in Play Console**, package `cx.m42.unitool`. ✅ It exists. The package was
   fixed when the record was created and can never be changed for that listing.
2. **A service account with Play Developer API access.** ✅
   `fastlane-play-uploader@alpine-scholar-468911-g6.iam.gserviceaccount.com`, which also publishes
   the reference app, and which `edits.tracks.list` confirms can reach `cx.m42.unitool`. Note that
   the account is granted per app in Play Console → Users and permissions: a new app is invisible
   to it until somebody adds it there, and the grant takes a few minutes to propagate. A run
   started straight after it can fail with `The caller does not have permission` and then succeed
   on a re-run with nothing changed.
3. **One release uploaded by hand.** ❌ Not yet — the API reports no release on any track, so Play
   has never accepted a bundle for this app. Google does not let the API create an app's *first*
   release: upload the signed `.aab` through Play Console → Testing → Internal testing → Create
   new release. That upload is also what registers the upload key with Play App Signing, so it
   must be the bundle signed with *this* keystore and not one Android Studio signed with a debug
   key. A manual run of this workflow with `upload` turned off produces exactly that bundle as an
   artifact — download it from the run and upload that file.

Until step 3 is done, a run that reaches the publish step fails there and nowhere else. That is
the expected failure, not a broken pipeline.

## The upload key

`cx.m42.unitool` uses **Play App Signing**: Play holds the key that actually signs what users
install, and the key in these secrets is only the *upload* key — the credential that proves a
bundle comes from us. Losing it is recoverable, at the cost of a support round-trip; losing an app
signing key would not be, which is why Play holds it.

It lives at `.secrets/upload-keystore.jks`, is not in the repository and is not in Play. **Back it
up somewhere other than this machine.** RELEASING.md has the `keytool` line that generated it and
the one that generates a replacement.

## Building a signed binary by hand

The signing values come from the environment, or from `composeApp/keystore.properties` when there
is no environment to speak of. With neither, `release` builds **unsigned** and still succeeds — a
clone must not need a keystore to compile.

```bash
SUPERIZER_FROM_MAVEN=1 ./gradlew :composeApp:bundleRelease
```
