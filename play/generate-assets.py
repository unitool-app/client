#!/usr/bin/env python3
"""
Generates every raster the vector adaptive icon cannot cover:
  - legacy mipmaps for API 24-25, which predate adaptive icons,
  - the 512x512 Play listing icon and the 1024x500 feature graphic,
  - the 1024x1024 iOS app icon, from which Xcode derives every other size.
Geometry is taken from ic_launcher_foreground.xml so the drawn and the vector icon are the same
mark: on the 108dp adaptive canvas the "=" is 44 wide and 28 tall over a 72dp visible area, i.e.
61.1% wide, 12.5% per bar, 13.9% of gap. Change the vector and change the constants below
together, or the icon a phone on API 25 shows stops being the icon everyone else sees.

    python3 play/generate-assets.py        # from the repository root

Needs Pillow, and nothing else. The output is committed, so this only has to run when the mark
itself changes — it is here to make that change reproducible rather than remembered.
"""
from PIL import Image, ImageDraw
import os

BG = (0x18, 0x18, 0x1B)
FG = (0xFA, 0xFA, 0xFA)
SS = 8  # supersample factor, downsampled at the end for clean antialiased edges

GLYPH_W = 44 / 72      # bar width as a fraction of the visible icon
BAR_H = 9 / 72         # one bar's height
GAP_H = 10 / 72        # the space between them


def draw_equals(d, cx, cy, box, fill=FG):
    """The "=" centred on (cx, cy), sized against a visible icon of `box` pixels."""
    w = box * GLYPH_W
    bar = box * BAR_H
    gap = box * GAP_H
    r = bar / 2
    for sign in (-1, 1):
        # Measured outward from the gap, not from the glyph's own edge, so the two bars stay
        # symmetric about cy whatever the bar height is.
        top = cy - gap / 2 - bar if sign < 0 else cy + gap / 2
        d.rounded_rectangle([cx - w / 2, top, cx + w / 2, top + bar], radius=r, fill=fill)


def legacy(size, round_icon=False):
    """A pre-API-26 launcher icon: the mark on its own masked shape, since nothing masks it for us."""
    n = size * SS
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if round_icon:
        d.ellipse([0, 0, n - 1, n - 1], fill=BG + (255,))
    else:
        d.rounded_rectangle([0, 0, n - 1, n - 1], radius=n * 0.22, fill=BG + (255,))
    draw_equals(d, n / 2, n / 2, n * (0.80 if round_icon else 0.88))
    return img.resize((size, size), Image.LANCZOS)


def store_icon(size=512):
    """Play's listing icon: full-bleed square, no alpha — Play rounds the corners itself."""
    n = size * SS
    img = Image.new("RGB", (n, n), BG)
    draw_equals(ImageDraw.Draw(img), n / 2, n / 2, n * 0.72)
    return img.resize((size, size), Image.LANCZOS)


def feature_graphic(w=1024, h=500):
    """
    The banner at the top of the listing. No text on it: Play draws the app name and icon over
    this graphic in several placements and crops it in others, so anything written here is
    something that may end up half-covered. The mark is sized to about 30% of the banner height,
    which survives being scaled down to a thumbnail.
    """
    img = Image.new("RGB", (w * 2, h * 2), BG)
    draw_equals(ImageDraw.Draw(img), w, h, h * 2 * 0.75)
    return img.resize((w, h), Image.LANCZOS)


root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
res = os.path.join(root, "composeApp/src/androidMain/res")
for density, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    legacy(px).save(f"{res}/mipmap-{density}/ic_launcher.png")
    legacy(px, round_icon=True).save(f"{res}/mipmap-{density}/ic_launcher_round.png")
    print(f"mipmap-{density}: {px}x{px}")

store = os.path.join(root, "play/listing")
os.makedirs(store, exist_ok=True)
store_icon().save(f"{store}/icon-512.png")
feature_graphic().save(f"{store}/feature-graphic-1024x500.png")
print("play listing: icon-512.png, feature-graphic-1024x500.png")

# iOS wants what Play wants — a full-bleed square with no alpha, the system masks the corners — only
# larger, and one size of it: Xcode 14+ derives the rest from a single universal 1024.
ios = os.path.join(root, "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset")
os.makedirs(ios, exist_ok=True)
store_icon(1024).save(f"{ios}/icon-1024.png")
print("ios: icon-1024.png")
