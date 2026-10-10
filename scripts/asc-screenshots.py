#!/usr/bin/env python3
"""
Puts a folder of screenshots into one display slot of an App Store version's localization.

    python3 scripts/asc-screenshots.py <appStoreVersion id> <locale> <display type> <folder> --like <display type>

The order is the one the slot named by --like already has, matched by file name, so the iPad's
frames come in the iPhone's order. A slot that already holds screenshots is left alone: this adds,
it never replaces. Nothing else on the version is touched.

The key and the app come from scripts/testflight-distribute.py (ASC_KEY_ID, ASC_ISSUER_ID,
ASC_KEY_P8_BASE64, the bundle id from Config.xcconfig).
"""
import argparse
import hashlib
import importlib.util
import os
import sys
import time
import urllib.request

spec = importlib.util.spec_from_file_location(
    "distribute", os.path.join(os.path.dirname(os.path.abspath(__file__)), "testflight-distribute.py"))
distribute = importlib.util.module_from_spec(spec)
spec.loader.exec_module(distribute)
fail = distribute.fail

WAIT_SECONDS = 15 * 60
POLL_SECONDS = 10


def screenshots_of(asc, set_id):
    return asc.call("GET", f"/appScreenshotSets/{set_id}/appScreenshots", {"limit": "50"})["data"]


def upload(asc, set_id, path):
    data = open(path, "rb").read()
    name = os.path.basename(path)
    shot = asc.call("POST", "/appScreenshots", body={"data": {
        "type": "appScreenshots",
        "attributes": {"fileName": name, "fileSize": len(data)},
        "relationships": {"appScreenshotSet": {"data": {"type": "appScreenshotSets", "id": set_id}}},
    }})["data"]
    for op in shot["attributes"]["uploadOperations"]:
        part = data[op["offset"]:op["offset"] + op["length"]]
        request = urllib.request.Request(op["url"], method=op["method"], data=part,
                                         headers={h["name"]: h["value"] for h in op.get("requestHeaders") or []})
        with urllib.request.urlopen(request, timeout=120) as response:
            if response.status >= 300:
                fail(f"{name}: {op['method']} part at {op['offset']} answered {response.status}")
    asc.call("PATCH", f"/appScreenshots/{shot['id']}", body={"data": {
        "type": "appScreenshots", "id": shot["id"],
        "attributes": {"uploaded": True, "sourceFileChecksum": hashlib.md5(data).hexdigest()},
    }})
    print(f"{name}: {len(data)} bytes in {len(shot['attributes']['uploadOperations'])} part(s), id {shot['id']}",
          flush=True)
    return shot["id"]


def wait_complete(asc, ids):
    deadline = time.time() + WAIT_SECONDS
    while True:
        shots = [asc.call("GET", f"/appScreenshots/{i}")["data"] for i in ids]
        states = {s["id"]: (s["attributes"].get("assetDeliveryState") or {}) for s in shots}
        failed = {i: st for i, st in states.items() if st.get("state") == "FAILED"}
        if failed:
            fail(f"Apple refused: {failed}")
        if all(st.get("state") == "COMPLETE" for st in states.values()):
            return shots
        if time.time() > deadline:
            fail(f"not COMPLETE after {WAIT_SECONDS // 60} minutes: {states}")
        time.sleep(POLL_SECONDS)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("version")
    parser.add_argument("locale")
    parser.add_argument("display_type")
    parser.add_argument("folder")
    parser.add_argument("--like", required=True)
    args = parser.parse_args()

    asc, app = distribute.connect()
    version = asc.call("GET", f"/appStoreVersions/{args.version}", {"include": "app"})["data"]
    owner = version["relationships"]["app"]["data"]["id"]
    if owner != app["id"]:
        fail(f"version {args.version} belongs to app {owner}, not {app['id']}")
    a = version["attributes"]
    print(f"app {app['id']} {app['attributes']['name']}, version {a['versionString']} {a['platform']} "
          f"{a.get('appStoreState') or a.get('appVersionState')}")

    localizations = asc.call("GET", f"/appStoreVersions/{args.version}/appStoreVersionLocalizations")["data"]
    localization = next((l for l in localizations if l["attributes"]["locale"] == args.locale), None)
    if localization is None:
        fail(f"no {args.locale} localization: {[l['attributes']['locale'] for l in localizations]}")
    print(f"localization {args.locale}: {localization['id']}")

    sets = asc.call("GET", f"/appStoreVersionLocalizations/{localization['id']}/appScreenshotSets")["data"]
    by_type = {s["attributes"]["screenshotDisplayType"]: s for s in sets}
    print("sets:", ", ".join(f"{t} {s['id']}" for t, s in by_type.items()))

    files = sorted(f for f in os.listdir(args.folder) if f.endswith(".png"))
    like = by_type.get(args.like)
    if like is None:
        fail(f"no {args.like} set to take the order from")
    order = [s["attributes"]["fileName"] for s in screenshots_of(asc, like["id"])]
    print(f"{args.like} order: {order}")
    if sorted(order) != files:
        fail(f"{args.like} holds {order}, the folder {files}")

    target = by_type.get(args.display_type)
    if target is None:
        target = asc.call("POST", "/appScreenshotSets", body={"data": {
            "type": "appScreenshotSets",
            "attributes": {"screenshotDisplayType": args.display_type},
            "relationships": {"appStoreVersionLocalization": {
                "data": {"type": "appStoreVersionLocalizations", "id": localization["id"]}}},
        }})["data"]
        print(f"created {args.display_type} set {target['id']}")
    else:
        held = screenshots_of(asc, target["id"])
        if held:
            fail(f"{args.display_type} set {target['id']} already holds "
                 f"{[s['attributes']['fileName'] for s in held]}; not adding to it")
        print(f"found empty {args.display_type} set {target['id']}")

    ids = [upload(asc, target["id"], os.path.join(args.folder, name)) for name in order]
    wait_complete(asc, ids)

    held = [s["id"] for s in screenshots_of(asc, target["id"])]
    if held != ids:
        asc.call("PATCH", f"/appScreenshotSets/{target['id']}/relationships/appScreenshots",
                 body={"data": [{"type": "appScreenshots", "id": i} for i in ids]})
        print("reordered")
    print(f"\n{args.display_type} set {target['id']}:")
    for s in screenshots_of(asc, target["id"]):
        a = s["attributes"]
        image = a.get("imageAsset") or {}
        print(f"  {a['fileName']}  {a['fileSize']} bytes  {image.get('width')}x{image.get('height')}  "
              f"{(a.get('assetDeliveryState') or {}).get('state')}  id {s['id']}")
    if [s["attributes"]["fileName"] for s in screenshots_of(asc, target["id"])] != order:
        fail("the order differs from " + args.like)


if __name__ == "__main__":
    main()
