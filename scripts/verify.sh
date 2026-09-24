#!/usr/bin/env bash
#
# One run, every target, one report. Exit 0 only on PASS.
#
# Same shape as the library's, because a person should not have to learn two of these. A skip is
# always an explicit line — the difference between "Android was not built" and "there is no SDK
# here" is the whole value of the file.
#
# SUPERIZER_FROM_MAVEN=1 forces the library to resolve from mavenLocal instead of the checkout next
# door. That is mode C of 12 §2 and the only thing that proves the library actually publishes.
set -uo pipefail

cd "$(dirname "$0")/.."
out=build/verify
rm -rf "$out"
mkdir -p "$out"
report="$out/report.md"
failed=0

say()  { echo "$1" | tee -a "$report"; }
step() { say ""; say "## $1"; }
ok()   { say "- PASS: $1"; }
skip() { say "- SKIPPED: $1"; }
bad()  { say "- FAIL: $1"; failed=1; }

say "# Unitool — verify"
say ""
say "\`$(date -u '+%Y-%m-%d %H:%M:%SZ')\` · $(git rev-parse --short HEAD 2>/dev/null || echo 'no git')"
if [ "${SUPERIZER_FROM_MAVEN:-0}" = "1" ]; then
  say "· superizer from **mavenLocal** (mode C)"
elif [ -d ../superizer ]; then
  say "· superizer from **../superizer** (composite build, mode B)"
else
  say "· superizer from **a repository** (no local checkout)"
fi

# ---------------------------------------------------------------- 1. static
step "1. Static"
if ./gradlew --quiet checkDependencyRules > "$out/static.log" 2>&1; then
  ok "app modules reach neither the host nor each other (02)"
else
  bad "dependency rules — see $out/static.log"
fi

if grep -rn --include='*.kt' -E '@Ignore|\.skip\(' composeApp/src apps > "$out/ignored.txt" 2>/dev/null; then
  bad "disabled tests found — see $out/ignored.txt"
else
  ok "no @Ignore and no .skip("
fi

# The rule that makes mode C possible at all (02): coordinates, never project paths.
if grep -rn 'project(":superizer' --include='*.kts' . --exclude-dir=build > "$out/paths.txt" 2>/dev/null; then
  bad "a build file names a library project directly — see $out/paths.txt"
else
  ok "the library is referenced by Maven coordinates only"
fi

# ---------------------------------------------------------------- 2. tests
step "2. Unit and UI tests (desktop)"
if ./gradlew --quiet :composeApp:desktopTest :apps:calculator:desktopTest :apps:currency-converter:desktopTest :apps:twofa:desktopTest \
    :services:qr-scanner:testDebugUnitTest --continue > "$out/test.log" 2>&1; then
  ok "host and app tests"
else
  bad "tests — see $out/test.log"
fi
mkdir -p "$out/junit"
find . -path '*/build/test-results/*' -name '*.xml' -not -path "./$out/*" -exec cp {} "$out/junit/" \; 2>/dev/null
say "  ($(ls "$out/junit" 2>/dev/null | wc -l) result files)"

# ---------------------------------------------------------------- 3. screenshots
step "3. Screenshots"
if ./gradlew --quiet :composeApp:desktopTest --tests '*Screenshots*' > "$out/shots.log" 2>&1; then
  cp -r composeApp/build/screenshots "$out/screenshots" 2>/dev/null
  ok "$(find "$out/screenshots" -name '*.png' 2>/dev/null | wc -l) images in $out/screenshots"
  say "  Look at them. A green test says a node exists, not that a person could read the screen."
else
  bad "screenshots — see $out/shots.log"
fi

# ---------------------------------------------------------------- 4. web
step "4. Web (wasm)"
if ./gradlew --quiet :composeApp:wasmJsBrowserDistribution > "$out/web.log" 2>&1; then
  ok "wasm bundle"
  dist=composeApp/build/dist/wasmJs/productionExecutable
  wasm=$(ls -S "$dist"/*.wasm 2>/dev/null | head -1)
  [ -n "$wasm" ] && say "  wasm bundle: $(( $(stat -c%s "$wasm") / 1024 )) KB"
else
  bad "wasm bundle — see $out/web.log"
fi

# ---------------------------------------------------------------- 5. android
step "5. Android"
if [ -n "${ANDROID_HOME:-}" ] || grep -q sdk.dir local.properties 2>/dev/null; then
  if ./gradlew --quiet :composeApp:assembleDebug > "$out/android.log" 2>&1; then
    apk=$(ls -S composeApp/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)
    ok "assembleDebug$( [ -n "$apk" ] && echo " — $(( $(stat -c%s "$apk") / 1024 )) KB" )"
  else
    bad "assembleDebug — see $out/android.log"
  fi

  # The one Android thing this script must catch without a device: R8 on a release build. The
  # serialization keep rules travel with the library, so an empty proguard-rules.pro has to hold.
  if ./gradlew --quiet :composeApp:assembleRelease >> "$out/android.log" 2>&1; then
    ok "assembleRelease (R8, unsigned without a keystore)"
  else
    bad "assembleRelease — see $out/android.log"
  fi

  if adb get-state > /dev/null 2>&1; then
    if ./gradlew --quiet :composeApp:connectedDebugAndroidTest >> "$out/android.log" 2>&1; then
      ok "instrumented smoke test"
    else
      bad "instrumented smoke test — see $out/android.log"
    fi
    # The 2FA keys' encryption at rest against a real AndroidKeyStore, which exists nowhere else.
    if ./gradlew --quiet :services:vault:connectedDebugAndroidTest >> "$out/android.log" 2>&1; then
      ok "Keystore vault on the device"
    else
      bad "Keystore vault — see $out/android.log"
    fi
  else
    skip "instrumented tests (smoke, Keystore vault): no device attached"
  fi
else
  skip "Android: no SDK configured"
fi

# ---------------------------------------------------------------- result
say ""
if [ "$failed" -eq 0 ]; then
  say "RESULT: PASS"
else
  say "RESULT: FAIL"
fi
exit "$failed"
