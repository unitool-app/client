#!/usr/bin/env python3
"""
Asks Google Play where a build actually landed, and fails if it is not where it was sent.

    python3 scripts/play-track-check.py <packageName> <versionCode> <track>

Exists because a green publish step does not mean the right thing happened. This repository has
already published a release to `internal` — a track with no testers on it — while every step of
the run reported success, and nobody would have noticed from the run. The upload action reports
what it *sent*; this reports what Play *has*.

Credentials come from PLAY_SERVICE_ACCOUNT_JSON (the whole JSON, which is how CI carries it) or
from .secrets/play-service-account.json when that variable is unset, which is how a laptop has it.

Writes a per-track summary either way, so a failure names the track the build did reach rather
than only the one it missed.
"""
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

TOKEN_URL = "https://oauth2.googleapis.com/token"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"


def credentials():
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON")
    if raw:
        return json.loads(raw)
    path = os.environ.get("PLAY_SERVICE_ACCOUNT_FILE", ".secrets/play-service-account.json")
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def access_token(cred):
    """A signed JWT exchanged for a token.

    Written out rather than pulled in as google-auth: this runs on a runner that installs nothing
    else, and one signature is not worth a dependency tree. `openssl` does the RS256 because the
    standard library has no asymmetric signing.
    """
    def segment(obj):
        return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=")

    now = int(time.time())
    signing_input = segment({"alg": "RS256", "typ": "JWT"}) + b"." + segment({
        "iss": cred["client_email"],
        "scope": "https://www.googleapis.com/auth/androidpublisher",
        "aud": TOKEN_URL,
        "exp": now + 3600,
        "iat": now,
    })
    with tempfile.NamedTemporaryFile("w", suffix=".pem", delete=False) as f:
        f.write(cred["private_key"])
        key = f.name
    try:
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", key],
            input=signing_input, capture_output=True, check=True,
        ).stdout
    finally:
        os.unlink(key)
    jwt = signing_input + b"." + base64.urlsafe_b64encode(signature).rstrip(b"=")
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": jwt.decode(),
    }).encode()
    return json.load(urllib.request.urlopen(TOKEN_URL, body, timeout=30))["access_token"]


def main():
    if len(sys.argv) != 4:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    package, version_code, expected_track = sys.argv[1], sys.argv[2], sys.argv[3]

    headers = {"Authorization": "Bearer " + access_token(credentials()),
               "Content-Type": "application/json"}
    base = f"{API}/{package}"

    def call(method, url, data=None):
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            body = urllib.request.urlopen(request, timeout=30).read()
            return json.loads(body) if body else {}
        except urllib.error.HTTPError as error:
            return {"__error__": error.code, "msg": error.read().decode()[:400]}

    # A throwaway edit, deleted in `finally`. Reading tracks needs one, and an edit that is never
    # committed changes nothing — but an edit that is never *deleted* lingers for days and the next
    # one has to wait behind it.
    edit = call("POST", base + "/edits", b"{}")
    if "__error__" in edit:
        print(f"FAIL: cannot open an edit: {edit}", file=sys.stderr)
        return 1
    try:
        tracks = call("GET", f"{base}/edits/{edit['id']}/tracks")
    finally:
        call("DELETE", f"{base}/edits/{edit['id']}")

    if "__error__" in tracks:
        print(f"FAIL: cannot list tracks: {tracks}", file=sys.stderr)
        return 1

    # The key is "tracks". Reading "track" here returns an empty list for every app that has ever
    # existed, silently, which is exactly what one earlier version of this check did — and it
    # reported "no releases anywhere" for an app with a live release.
    found_on = []
    print(f"{'track':<12} {'release':<10} versionCodes")
    for track in tracks.get("tracks", []):
        releases = track.get("releases", [])
        if not releases:
            print(f"{track['track']:<12} {'—':<10} —")
        for release in releases:
            codes = release.get("versionCodes", [])
            print(f"{track['track']:<12} {str(release.get('name')):<10} {','.join(codes)}"
                  f"   status={release.get('status')}")
            if version_code in codes:
                found_on.append(track["track"])

    print()
    if expected_track in found_on:
        extra = [t for t in found_on if t != expected_track]
        print(f"OK: versionCode {version_code} is on '{expected_track}'"
              + (f" (and also on {', '.join(extra)})" if extra else ""))
        return 0

    if found_on:
        print(f"FAIL: versionCode {version_code} is on {', '.join(found_on)}, "
              f"not on '{expected_track}'", file=sys.stderr)
    else:
        print(f"FAIL: versionCode {version_code} is on no track at all. The upload reported "
              f"success, so Play took the bundle and put it nowhere — check the track id.",
              file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
