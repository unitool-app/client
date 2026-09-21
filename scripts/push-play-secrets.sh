#!/usr/bin/env bash
#
# Turns the local signing material into the repository secrets android-play.yml expects.
#
#   ./scripts/push-play-secrets.sh <owner/repo> [path/to/play-service-account.json]
#
# Everything it reads is in .secrets/, which is gitignored as a directory precisely so that a
# credential added later is ignored before anybody has to remember to add a pattern for it.
#
# Why a script rather than five lines in a README: every one of these values fails *late* and
# unhelpfully when it is set wrong. A base64 blob with a trailing newline decodes to a keystore
# Java will not open, and the error arrives from inside a Gradle signing task. An alias that does
# not match the one in the keystore fails the same way. Both are checked here, where the keystore
# is still on the machine that made it.
set -euo pipefail

cd "$(dirname "$0")/.."

REPO="${1:-}"
PLAY_JSON="${2:-.secrets/play-service-account.json}"
PROPS=.secrets/keystore.properties
FIREBASE_JSON=.secrets/google-services.json

if [ -z "$REPO" ]; then
  echo "usage: $0 <owner/repo> [path/to/play-service-account.json]" >&2
  exit 2
fi

command -v gh >/dev/null || { echo "FAIL: the GitHub CLI (gh) is not installed"; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "FAIL: gh is not logged in — run 'gh auth login'"; exit 1; }
[ -f "$PROPS" ] || { echo "FAIL: $PROPS missing — see RELEASING.md, 'The upload key'"; exit 1; }

# `sed` rather than `source`: the file is a Java properties file, and a password containing a $ or
# a backtick would be evaluated by the shell.
prop() { sed -n "s/^$1=//p" "$PROPS" | head -1; }

STORE_FILE=$(prop storeFile)
STORE_PASSWORD=$(prop storePassword)
KEY_ALIAS=$(prop keyAlias)
KEY_PASSWORD=$(prop keyPassword)

for name in STORE_FILE STORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
  [ -n "${!name}" ] || { echo "FAIL: $PROPS has no $name"; exit 1; }
done
[ -f "$STORE_FILE" ] || { echo "FAIL: keystore $STORE_FILE does not exist"; exit 1; }

# The same check the workflow runs before it builds, run here instead, where the answer is useful:
# a wrong password and a missing alias are one error message in Gradle and two different fixes.
keytool -list -keystore "$STORE_FILE" -storepass "$STORE_PASSWORD" -alias "$KEY_ALIAS" >/dev/null \
  || { echo "FAIL: $STORE_FILE has no alias '$KEY_ALIAS', or the store password is wrong"; exit 1; }

# Play requires an upload certificate valid past 22 October 2033, and it says so only at upload.
UNTIL=$(keytool -list -v -keystore "$STORE_FILE" -storepass "$STORE_PASSWORD" -alias "$KEY_ALIAS" \
        | sed -n 's/.*until: //p' | head -1)
echo "Upload key '$KEY_ALIAS' in $STORE_FILE, valid until $UNTIL"

set_secret() {
  # printf, never echo: a trailing newline is what breaks a base64 blob once it is in the runner,
  # and by then the only symptom is a keystore that will not open.
  printf '%s' "$2" | gh secret set "$1" --repo "$REPO" >/dev/null
  echo "  set $1"
}

# The signing secrets go up first and on their own.
#
# They are complete without the Play credential and they are the half that is hard to reproduce —
# the keystore exists on exactly one machine. The service account is a form somebody fills in on
# a website, and waiting for it before storing the key would mean the key stays in one place for
# as long as that takes. So this script does what it can and says what it could not, rather than
# refusing the whole job over the missing half.
echo "Setting signing secrets on $REPO:"
set_secret ANDROID_KEYSTORE_BASE64   "$(base64 -w0 "$STORE_FILE")"
set_secret ANDROID_KEYSTORE_PASSWORD "$STORE_PASSWORD"
set_secret ANDROID_KEY_ALIAS         "$KEY_ALIAS"
set_secret ANDROID_KEY_PASSWORD      "$KEY_PASSWORD"

# The Firebase config, which is what makes push work at all. Checked for the right package here
# rather than only in CI: the console hands you one file per project, and a config for the wrong
# project builds green and receives nothing.
if [ -f "$FIREBASE_JSON" ]; then
  python3 -c '
import json, sys
d = json.load(open(sys.argv[1]))
pkgs = [c["client_info"]["android_client_info"]["package_name"] for c in d["client"]]
print("  Firebase project " + d["project_info"]["project_id"] + ", clients: " + " ".join(pkgs))
sys.exit(0 if "cx.m42.unitool" in pkgs else 1)
' "$FIREBASE_JSON" || { echo "FAIL: $FIREBASE_JSON has no client for cx.m42.unitool"; exit 1; }
  set_secret GOOGLE_SERVICES_JSON_BASE64 "$(base64 -w0 "$FIREBASE_JSON")"
else
  echo "  WARNING: $FIREBASE_JSON missing — GOOGLE_SERVICES_JSON_BASE64 not set, so the build"
  echo "           would produce an app that cannot receive a notification."
fi

if [ ! -f "$PLAY_JSON" ]; then
  cat >&2 <<MSG

The four signing secrets are set. PLAY_SERVICE_ACCOUNT_JSON is not, because $PLAY_JSON
does not exist.

That is the service-account key with Play Developer API access, and it is created once, by hand:
  Play Console -> Setup -> API access -> link (or create) a Google Cloud project
  in that project, create a service account, then Keys -> Add key -> JSON
  Play Console -> Users and permissions -> invite the service account's e-mail and grant it,
    for this app, "Release to testing tracks"
Save the JSON as $PLAY_JSON and run this again; it will set the four above a second
time, which costs nothing.

Until then the workflow can still build: run it from Actions with "upload" turned off. That is
also the bundle the first, manual Play upload needs, since the API cannot create an app's first
release.
MSG
  exit 1
fi

python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if d.get("type")=="service_account" else 1)' \
  "$PLAY_JSON" || { echo "FAIL: $PLAY_JSON is not a service-account key"; exit 1; }
set_secret PLAY_SERVICE_ACCOUNT_JSON "$(cat "$PLAY_JSON")"

echo
echo "Done. Actions -> Build and Deploy Android to Google Play -> Run workflow."
echo "Leave 'upload' off for a first run that only builds and keeps the artifact."
