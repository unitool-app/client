#!/usr/bin/env python3
"""
Waits for App Store Connect to process an upload, then gives the build to a TestFlight group.

    python3 scripts/testflight-distribute.py <build number> <group name>
    python3 scripts/testflight-distribute.py --compliance-code

The testflight lane of ios.yml uploads, and an upload alone reaches nobody: Apple processes it for
minutes first, and only a processed build can be put in a group. This waits — on a Linux runner,
where waiting costs a tenth of what it costs on the Mac — adds the build to the group, and says
what still stands between it and the testers. Export compliance it answers itself, by repeating
the answer the owner gave once in App Store Connect (see answer_export_compliance).

With --compliance-code it prints the key of approved encryption documentation instead, for the
testflight lane to write into Info.plist before the archive (see compliance_code), and prints
nothing when there is none.

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
    # To stderr: with --compliance-code, stdout is the key and nothing else.
    print(f"::error::{message}", file=sys.stderr, flush=True)
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


def owners_answer(declaration):
    """
    The owner's export-compliance answer (2026-10-05): the app uses encryption — standard algorithms
    in addition to iOS's own, the 2FA export's age — nothing proprietary, not on the French store.
    A declaration that says anything else is not theirs to repeat.
    """
    a = declaration["attributes"]
    return (a.get("appEncryptionDeclarationState") == "APPROVED"
            and a.get("usesEncryption") is not False
            and a.get("exempt") is not True
            and a.get("containsProprietaryCryptography") is not True
            and a.get("availableOnFrenchStore") is not True)


def beta_state(asc, build_id):
    """MISSING_EXPORT_COMPLIANCE while Apple waits for the answer; READY_FOR_BETA_TESTING once it has it."""
    detail = asc.call("GET", f"/builds/{build_id}/buildBetaDetail")["data"]
    return detail["attributes"].get("internalBuildState")


def answer_export_compliance(asc, app_id, build, build_number):
    """
    Gives the build the answer App Store Connect keeps from the last time it was given by hand (an
    app encryption declaration), so a new build does not wait for someone to open the console. The
    first answer is a person's: this only repeats it, and says so when there is none to repeat.

    Info.plist answers it (NO, see compliance_code), so a build normally never waits here. This is
    for one that still does, from before the answer was in Info.plist or after Apple asked anyway.
    """
    state = beta_state(asc, build["id"])
    if state != "MISSING_EXPORT_COMPLIANCE":
        print(f"export compliance: nothing to answer for build {build_number} ({state})")
        return
    declarations = [d for d in asc.call("GET", "/appEncryptionDeclarations",
                                        {"filter[app]": app_id, "limit": "200"})["data"] if owners_answer(d)]
    if not declarations:
        print(f"::warning::Export compliance is unanswered for build {build_number}, and App Store Connect "
              "has no answer to repeat yet. Answer it once there (TestFlight → the build): standard "
              "algorithms in addition to iOS → not available in France. Later builds get it from this job.",
              flush=True)
        return
    declaration = max(declarations, key=lambda d: d["attributes"].get("createdDate")
                      or d["attributes"].get("uploadedDate") or "")
    errors = []
    # The build's own relationship is the newer endpoint; the declaration's list of builds the older.
    for method, path, body in (
        ("PATCH", f"/builds/{build['id']}/relationships/appEncryptionDeclaration",
         {"data": {"type": "appEncryptionDeclarations", "id": declaration["id"]}}),
        ("POST", f"/appEncryptionDeclarations/{declaration['id']}/relationships/builds",
         {"data": [{"type": "builds", "id": build["id"]}]}),
    ):
        try:
            asc.call(method, path, body=body)
            break
        except AscError as e:
            errors.append(f"{method} {path}: {e}")
    if build["attributes"].get("usesNonExemptEncryption") is None:
        # A build whose Info.plist did not say.
        try:
            asc.call("PATCH", f"/builds/{build['id']}", body={"data": {
                "type": "builds", "id": build["id"], "attributes": {"usesNonExemptEncryption": True}}})
        except AscError as e:
            errors.append(f"PATCH /builds/{build['id']}: {e}")

    # What Apple holds now, not what was sent: the testers get the build only once this moves on.
    for _ in range(6):
        state = beta_state(asc, build["id"])
        if state != "MISSING_EXPORT_COMPLIANCE":
            break
        time.sleep(10)
    if state == "MISSING_EXPORT_COMPLIANCE":
        print(f"::warning::Export compliance for build {build_number} could not be answered from here; "
              "answer it in App Store Connect (TestFlight → the build). " + " | ".join(errors), flush=True)
    else:
        print(f"export compliance: build {build_number} answered with declaration {declaration['id']} ({state})")
        for error in errors:
            print(f"  (on the way: {error})")


def compliance_code(asc, app_id):
    """
    The key of the app's approved encryption documentation, or None while it has none.

    Apple refuses an upload whose Info.plist says "uses non-exempt encryption" without that key
    (ITMS-90592, 2026-10-06). So Info.plist says NO, which is right while the app is not on the
    French store, and the lane writes YES with the key once there is one. The key is read here on
    every build rather than kept in the repo, so new documentation brings its own. The owner's answer is preferred; any other one Apple has not turned down is used with a
    warning.
    """
    declarations = asc.call("GET", "/appEncryptionDeclarations",
                            {"filter[app]": app_id, "limit": "200"})["data"]
    print(f"App Store Connect holds {len(declarations)} export compliance answer(s)", file=sys.stderr)
    for d in declarations:
        a = d["attributes"]
        print(f"declaration {d['id']}: {a.get('appEncryptionDeclarationState')}, "
              f"key {'present' if a.get('codeValue') else 'absent'}, exempt={a.get('exempt')}, "
              f"proprietary={a.get('containsProprietaryCryptography')}, "
              f"france={a.get('availableOnFrenchStore')}, created {a.get('createdDate')}", file=sys.stderr)
    keyed = [d for d in declarations if d["attributes"].get("codeValue")
             and d["attributes"].get("appEncryptionDeclarationState") not in ("REJECTED", "INVALID", "EXPIRED")]
    if not keyed:
        return None
    theirs = [d for d in keyed if owners_answer(d)]
    if not theirs:
        print("::warning::No approved export compliance answer is the owner's (BUILD-IOS.md); the newest "
              "other one's key is used so that the upload goes through", file=sys.stderr, flush=True)
    newest = max(theirs or keyed, key=lambda d: d["attributes"].get("createdDate") or "")
    return newest["attributes"]["codeValue"]


def connect():
    """The upload's own key, from the secrets; the temporary .p8 is gone when the process is."""
    key = tempfile.NamedTemporaryFile("wb", suffix=".p8")
    key.write(base64.b64decode(os.environ["ASC_KEY_P8_BASE64"]))
    key.flush()
    asc = Asc(os.environ["ASC_KEY_ID"].strip(), os.environ["ASC_ISSUER_ID"].strip(), key.name)
    asc.key_file = key
    bundle = xcconfig("BUNDLE_ID")
    apps = asc.call("GET", "/apps", {"filter[bundleId]": bundle})["data"]
    app = next((a for a in apps if a["attributes"]["bundleId"] == bundle), None)
    if app is None:
        fail(f"App Store Connect has no app with the bundle id {bundle}")
    return asc, app


def main(build_number, group_name):
    asc, app = connect()
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

    try:
        answer_export_compliance(asc, app["id"], build, build_number)
    except AscError as e:
        # The build is in the group either way; only the answer is left to give by hand.
        print(f"::warning::Export compliance for build {build_number} was not answered: {e}", flush=True)


if __name__ == "__main__":
    if sys.argv[1:] == ["--compliance-code"]:
        try:
            asc, app = connect()
            print(compliance_code(asc, app["id"]) or "")
        except AscError as e:
            fail(f"could not read the export compliance key: {e}")
    elif len(sys.argv) == 3:
        main(*sys.argv[1:])
    else:
        sys.exit(__doc__)
