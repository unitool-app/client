#!/usr/bin/env python3
"""
Checks the Play listing material against the limits the Play Console enforces on upload.

Exists because every one of these fails *late* — the Console rejects an 81-character short
description after you have pasted it, and a screenshot of the wrong shape after the upload
finishes — and every one of them is a number that can be read off the file here instead.

    python3 play/check-listing.py        # from the repository root; exit 1 if anything is wrong

Run `./gradlew :composeApp:desktopTest --tests '*Screenshots*'` first, or the screenshot checks
have nothing to look at and say so.
"""
import os
import re
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

failures = []
notes = []


def check(ok, message):
    print(f"  {'PASS' if ok else 'FAIL'}  {message}")
    if not ok:
        failures.append(message)


def png(path):
    """Width, height and colour type, straight out of the IHDR — no image library needed."""
    with open(path, "rb") as f:
        head = f.read(34)
    width, height = struct.unpack(">II", head[16:24])
    return width, height, head[25]


RGB = 2  # PNG colour type 2 is 24-bit truecolour; 6 is the same with an alpha channel.


def check_image(path, expect, label):
    """`expect` is (width, height) exactly, or None to accept any Play-legal phone screenshot."""
    if not os.path.exists(path):
        notes.append(f"missing: {os.path.relpath(path, ROOT)}")
        print(f"  SKIP  {label} — not generated yet")
        return
    w, h, colour = png(path)
    name = os.path.basename(path)
    if expect:
        check((w, h) == expect, f"{label} {name} is {w}x{h}, wants {expect[0]}x{expect[1]}")
    else:
        # Play: each side 320-3840px, and 9:16 portrait for a phone screenshot.
        check(320 <= min(w, h) and max(w, h) <= 3840, f"{label} {name} sides within 320-3840 ({w}x{h})")
        check(round(w / h, 3) == 0.562, f"{label} {name} is 9:16 ({w}x{h}, ratio {w / h:.3f})")
    # Play composites listings onto its own backgrounds, so an alpha channel is rejected.
    check(colour == RGB, f"{label} {name} is 24-bit RGB with no alpha (colour type {colour})")


print("Listing graphics")
check_image(os.path.join(ROOT, "play/listing/icon-512.png"), (512, 512), "icon")
check_image(os.path.join(ROOT, "play/listing/feature-graphic-1024x500.png"), (1024, 500), "feature graphic")

print("\nPhone screenshots")
shots_dir = os.path.join(ROOT, "composeApp/build/screenshots/play")
shots = sorted(f for f in os.listdir(shots_dir) if f.endswith(".png")) if os.path.isdir(shots_dir) else []
# Two is the bare minimum Play accepts; four at 1080px+ is what makes a listing eligible to be
# promoted on the store, which is free and worth not missing by one.
check(len(shots) >= 4, f"at least 4 screenshots (found {len(shots)})")
check(len(shots) <= 8, f"at most 8 screenshots (found {len(shots)})")
for shot in shots:
    check_image(os.path.join(shots_dir, shot), None, "screenshot")

print("\nStore text")
LIMITS = {
    "App name": 30, "Название": 30,
    "Short description": 80, "Краткое описание": 80,
    "Full description": 4000, "Полное описание": 4000,
}
for listing in ("description-en.md", "description-ru.md"):
    path = os.path.join(ROOT, "play/listing", listing)
    text = open(path, encoding="utf-8").read()
    fields = re.findall(r"^## (.+?)\n+```\n(.*?)\n```", text, re.S | re.M)
    check(len(fields) == 3, f"{listing} has all three fields (found {len(fields)})")
    for heading, body in fields:
        label = heading.split(" (")[0]
        limit = LIMITS.get(label)
        if limit is None:
            check(False, f"{listing}: unrecognised field {label!r}")
            continue
        check(len(body) <= limit, f"{listing}: {label} is {len(body)}/{limit} characters")

print("\nPrivacy policy")
privacy = open(os.path.join(ROOT, "play/PRIVACY.md"), encoding="utf-8").read()
check("TODO" not in privacy, "play/PRIVACY.md has no TODO left in it (Play needs a contact address)")

if notes:
    print("\n" + "\n".join(f"note: {n}" for n in notes))
print()
if failures:
    print(f"{len(failures)} problem(s) — the Play Console would reject this listing.")
    sys.exit(1)
print("Listing material is within every limit the Play Console enforces.")
