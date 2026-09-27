#!/usr/bin/env python3
"""
Waits for App Store Connect to process an upload, then gives the build to a TestFlight group.

    python3 scripts/testflight-distribute.py <build number> <group name>

The testflight lane of ios.yml uploads, and an upload alone reaches nobody: Apple processes it for
minutes first, and only a processed build can be put in a group. This waits — on a Linux runner,
where waiting costs a tenth of what it costs on the Mac — adds the build to the group, and says
what still stands between it and the testers. An unanswered export-compliance question is the
usual thing.

The App Store Connect key comes from ASC_KEY_ID, ASC_ISSUER_ID and ASC_KEY_P8_BASE64, the secrets
the upload uses; the bundle id from Config.xcconfig. Needs nothing beyond Python and openssl.
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

API = "https://api.appstoreconnect.apple.com/v1"
WAIT_SECONDS = 45 * 60
POLL_SECONDS = 30


def fail(message):
    print(f"::error::{message}", flush=True)
    sys.exit(1)


def xcconfig(name):
    with open("iosApp/Configuration/Config.xcconfig") as f:
        for line in f:
            key, _, value = line.partition("=")
            if key.strip() == name:
                return value.strip()
    fail(f"{name} is not in iosApp/Configuration/Config.xcconfig")


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def raw_signature(der, size=32):
    """openssl gives ECDSA-Sig-Value (DER: SEQUENCE of r, s); a JWT wants r ‖ s, each 32 bytes."""
    i = 2 if der[1] < 0x80 else 2 + (der[1] & 0x7F)
    out = b""
    for _ in range(2):
        length = der[i + 1]
        out += der[i + 2:i + 2 + length].lstrip(b"\0").rjust(size, b"\0")
        i += 2 + length
    return out


class AscError(Exception):
    def __init__(self, status, detail):
        super().__init__(f"HTTP {status}: {detail}")
        self.status = status


class Asc:
    def __init__(self, key_id, issuer_id, key_path):
        self.key_id, self.issuer_id, self.key_path = key_id, issuer_id, key_path

    def token(self):
        """A fresh one per request: they live twenty minutes at most, and the wait is longer."""
        now = int(time.time())
        header = {"alg": "ES256", "kid": self.key_id, "typ": "JWT"}
        claims = {"iss": self.issuer_id, "iat": now, "exp": now + 15 * 60, "aud": "appstoreconnect-v1"}
        signing_input = b64url(json.dumps(header).encode()) + "." + b64url(json.dumps(claims).encode())
        der = subprocess.run(["openssl", "dgst", "-sha256", "-sign", self.key_path],
                             input=signing_input.encode(), capture_output=True, check=True).stdout
        return signing_input + "." + b64url(raw_signature(der))

    def call(self, method, path, params=None, body=None):
        url = API + path + ("?" + urllib.parse.urlencode(params) if params else "")
        request = urllib.request.Request(
            url, method=method, data=json.dumps(body).encode() if body is not None else None,
            headers={"Authorization": "Bearer " + self.token(), "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            raise AscError(e.code, e.read().decode(errors="replace")) from None


def wait_for_processing(asc, app_id, build_number):
    deadline = time.time() + WAIT_SECONDS
    last = None
    while True:
        builds = asc.call("GET", "/builds", {"filter[app]": app_id, "filter[version]": build_number,
                                             "sort": "-uploadedDate", "limit": "1"})["data"]
        state = builds[0]["attributes"]["processingState"] if builds else "not visible yet"
        if state != last:
            print(f"{time.strftime('%H:%M:%S')} build {build_number}: {state}", flush=True)
            last = state
        if state == "VALID":
            return builds[0]
        if state in ("FAILED", "INVALID"):
            fail(f"Apple did not accept build {build_number} ({state}); the reason goes to the "
                 "developer account's email")
        if time.time() > deadline:
            fail(f"build {build_number} is still «{state}» after {WAIT_SECONDS // 60} minutes; "
                 "add it to the group by hand once Apple has processed it")
        time.sleep(POLL_SECONDS)


def main(build_number, group_name):
    bundle = xcconfig("BUNDLE_ID")
    with tempfile.NamedTemporaryFile("wb", suffix=".p8") as key:
        key.write(base64.b64decode(os.environ["ASC_KEY_P8_BASE64"]))
        key.flush()
        asc = Asc(os.environ["ASC_KEY_ID"].strip(), os.environ["ASC_ISSUER_ID"].strip(), key.name)

        apps = asc.call("GET", "/apps", {"filter[bundleId]": bundle})["data"]
        app = next((a for a in apps if a["attributes"]["bundleId"] == bundle), None)
        if app is None:
            fail(f"App Store Connect has no app with the bundle id {bundle}")

        groups = asc.call("GET", "/betaGroups", {"filter[app]": app["id"], "limit": "200"})["data"]
        group = next((g for g in groups if g["attributes"]["name"] == group_name), None)
        if group is None:
            names = ", ".join(f"«{g['attributes']['name']}»" for g in groups) or "none"
            fail(f"«{app['attributes']['name']}» has no TestFlight group «{group_name}» (it has: {names})")

        build = wait_for_processing(asc, app["id"], build_number)

        if group["attributes"].get("hasAccessToAllBuilds"):
            print(f"«{group_name}» takes every build by itself (automatic distribution); nothing to add")
        else:
            try:
                asc.call("POST", f"/betaGroups/{group['id']}/relationships/builds",
                         body={"data": [{"type": "builds", "id": build["id"]}]})
            except AscError as e:
                fail(f"could not add build {build_number} to «{group_name}»: {e}")
            print(f"build {build_number} is in «{group_name}»")

        if build["attributes"].get("usesNonExemptEncryption") is None:
            print("::warning::Export compliance is unanswered for this build: the testers get it once "
                  "it is answered in App Store Connect (TestFlight → the build).", flush=True)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(*sys.argv[1:])
