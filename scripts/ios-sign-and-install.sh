#!/bin/bash
# Signs the unsigned Unitool.app that the `device` lane of ios.yml produced, and installs it on the
# iPhone plugged into this Mac. The agentiz pattern (agentiz/mobile-client/sign-and-install.sh):
# CI has the SDK and no certificate, this Mac has the certificate and no need for the SDK.
#
# Usage:
#   IOS_SIGN_IDENTITY=<SHA-1 of the development certificate> \
#   IOS_PROFILE=~/Library/MobileDevice/Provisioning\ Profiles/<uuid>.mobileprovision \
#   scripts/ios-sign-and-install.sh build/Unitool.app
#
# The profile must be a *development* profile for the explicit App ID cx.m42.unitool with Push
# Notifications, listing this iPhone's UDID (BUILD-IOS.md, Signing). A wildcard profile carries no
# aps-environment and the signed app would never register for push. Entitlements are taken from
# the profile, not from the repository, so what gets signed is exactly what Apple authorised.
#
# Identity by SHA-1, not by name: two certificates can share a common name and codesign refuses an
# ambiguous match. `security find-identity -v -p codesigning` lists them.

set -euo pipefail

APP_PATH="${1:?usage: $0 <path-to-unsigned Unitool.app>}"
IDENTITY="${IOS_SIGN_IDENTITY:?set IOS_SIGN_IDENTITY to the SHA-1 of the certificate (security find-identity -v -p codesigning)}"
PROFILE="${IOS_PROFILE:?set IOS_PROFILE to the development .mobileprovision for cx.m42.unitool}"

[ -d "$APP_PATH" ] || { echo "not an .app bundle: $APP_PATH" >&2; exit 1; }
[ -f "$PROFILE" ] || { echo "profile not found: $PROFILE" >&2; exit 1; }
command -v ideviceinstaller > /dev/null || { echo "ideviceinstaller is missing: brew install libimobiledevice ideviceinstaller" >&2; exit 1; }

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

APP_NAME=$(basename "$APP_PATH")
cp -R "$APP_PATH" "$WORK_DIR/$APP_NAME"
TARGET="$WORK_DIR/$APP_NAME"

# The profile travels inside the bundle as embedded.mobileprovision.
cp "$PROFILE" "$TARGET/embedded.mobileprovision"

# Entitlements come from the profile so they match what Apple authorised. The bundle id is checked
# against it: a profile for another app signs fine and then refuses to install, which is a worse
# place to find out.
security cms -D -i "$PROFILE" > "$WORK_DIR/profile.plist"
/usr/libexec/PlistBuddy -x -c 'Print :Entitlements' "$WORK_DIR/profile.plist" > "$WORK_DIR/entitlements.plist"
PROFILE_APP_ID=$(/usr/libexec/PlistBuddy -c 'Print :Entitlements:application-identifier' "$WORK_DIR/profile.plist")
BUNDLE_ID=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$TARGET/Info.plist")
case "$PROFILE_APP_ID" in
  *".$BUNDLE_ID") ;;
  *) echo "profile is for $PROFILE_APP_ID, app is $BUNDLE_ID" >&2; exit 1 ;;
esac
if ! /usr/libexec/PlistBuddy -c 'Print :Entitlements:aps-environment' "$WORK_DIR/profile.plist" > /dev/null 2>&1; then
  echo "warning: this profile has no aps-environment — push will not register in this build" >&2
fi

# Nested code first, the outer bundle last: the ComposeApp framework and anything else under Frameworks/.
if [ -d "$TARGET/Frameworks" ]; then
  find "$TARGET/Frameworks" -maxdepth 1 \( -name '*.framework' -o -name '*.dylib' \) | while read -r item; do
    codesign --force --sign "$IDENTITY" --timestamp=none "$item"
  done
fi

codesign --force --sign "$IDENTITY" --entitlements "$WORK_DIR/entitlements.plist" --timestamp=none "$TARGET"
codesign --verify --verbose=2 "$TARGET"

ideviceinstaller install "$TARGET"
echo "installed $BUNDLE_ID — open Settings → Storage & security → Run self-test, then Share report"
