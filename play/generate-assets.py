#!/usr/bin/env python3
"""
Generates every icon raster of the app from one picture, play/icon-source.png (the "37" of the
37tool name: black jersey digits with a white outline on a flat green):
  - the adaptive-icon layers for API 26+: foreground (drawable-*dpi) and monochrome (drawable-*dpi),
    the latter also the notification's small icon,
  - legacy mipmaps for API 24-25, which predate adaptive icons,
  - the 512x512 Play listing icon and the 1024x500 feature graphic,
  - the 1024x1024 iOS app icon, from which Xcode derives every other size.
The digits are found in the source by how far each pixel is from the background green, cropped,
and re-centred on a green canvas at a size chosen per target, so the source's own margins do not
matter. The background colour must equal ic_launcher_background in values/ic_launcher_background.xml.

    python3 play/generate-assets.py        # from the repository root

Needs Pillow, and nothing else. The output is committed, so this only has to run when the mark
itself changes — it is here to make that change reproducible rather than remembered.
"""
from PIL import Image, ImageChops, ImageDraw
import os

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src = Image.open(os.path.join(root, "play/icon-source.png")).convert("RGB")
BG = src.getpixel((2, 2))  # the flat green the digits stand on

# How far each pixel is from the green: 0 on the background, high on the white outline and the
# black digits. Scaled so that anything clearly not green is fully opaque.
diff = ImageChops.difference(src, Image.new("RGB", src.size, BG))
r, g, b = diff.split()
dist = ImageChops.lighter(ImageChops.lighter(r, g), b)
alpha = dist.point(lambda v: min(255, v * 255 // 90))

box = alpha.point(lambda v: 255 if v > 40 else 0).getbbox()
glyph = src.crop(box)            # the digits with their outline, still on green at the edges
glyph_alpha = alpha.crop(box)    # the same as a silhouette
GW, GH = glyph.size


def place(canvas, layer, cx, cy, width):
    """Pastes `layer` (glyph or its silhouette) centred on (cx, cy), `width` pixels wide."""
    w = round(width)
    h = round(width * GH / GW)
    scaled = layer.resize((w, h), Image.LANCZOS)
    pos = (round(cx - w / 2), round(cy - h / 2))
    if layer.mode == "L":
        canvas.paste((255, 255, 255, 255), pos, scaled)
    else:
        canvas.paste(scaled, pos)


def foreground(size):
    """Adaptive foreground, 108dp canvas: the digits 50dp wide, half-diagonal inside the 66dp safe
    circle. Opaque green under them, so a launcher's mask never shows an edge between layers."""
    img = Image.new("RGBA", (size, size), BG + (255,))
    place(img, glyph, size / 2, size / 2, size * 50 / 108)
    return img


def monochrome(size):
    """Themed-icon layer (Android 13+) and notification icon: the silhouette only, white on
    transparent, which the system re-tints."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    place(img, glyph_alpha, size / 2, size / 2, size * 50 / 108)
    return img


def legacy(size, round_icon=False):
    """A pre-API-26 launcher icon: the mark on its own masked shape, since nothing masks it for us."""
    n = size * 4
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    mask = Image.new("L", (n, n), 0)
    d = ImageDraw.Draw(mask)
    if round_icon:
        d.ellipse([0, 0, n - 1, n - 1], fill=255)
    else:
        d.rounded_rectangle([0, 0, n - 1, n - 1], radius=n * 0.22, fill=255)
    tile = Image.new("RGBA", (n, n), BG + (255,))
    place(tile, glyph, n / 2, n / 2, n * (0.62 if round_icon else 0.72))
    img.paste(tile, (0, 0), mask)
    return img.resize((size, size), Image.LANCZOS)


def store_icon(size=512):
    """Full-bleed square, no alpha — Play and iOS round the corners themselves."""
    img = Image.new("RGB", (size, size), BG)
    place(img, glyph, size / 2, size / 2, size * 0.72)
    return img


def feature_graphic(w=1024, h=500):
    """The banner at the top of the listing. No text on it: Play draws the name and icon over it in
    places and crops it in others. The mark is about 60% of the banner height."""
    img = Image.new("RGB", (w, h), BG)
    place(img, glyph, w / 2, h / 2, h * 0.60 * GW / GH)
    return img


res = os.path.join(root, "composeApp/src/androidMain/res")
for density, scale in [("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)]:
    layer = round(108 * scale)
    os.makedirs(f"{res}/drawable-{density}", exist_ok=True)
    foreground(layer).save(f"{res}/drawable-{density}/ic_launcher_foreground.png")
    monochrome(layer).save(f"{res}/drawable-{density}/ic_launcher_monochrome.png")
    px = round(48 * scale)
    legacy(px).save(f"{res}/mipmap-{density}/ic_launcher.png")
    legacy(px, round_icon=True).save(f"{res}/mipmap-{density}/ic_launcher_round.png")
    print(f"{density}: layers {layer}px, mipmaps {px}px")

store = os.path.join(root, "play/listing")
os.makedirs(store, exist_ok=True)
store_icon().save(f"{store}/icon-512.png")
feature_graphic().save(f"{store}/feature-graphic-1024x500.png")
print("play listing: icon-512.png, feature-graphic-1024x500.png")

ios = os.path.join(root, "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset")
store_icon(1024).save(f"{ios}/icon-1024.png")
print("ios: icon-1024.png")
print("background:", "#%02X%02X%02X" % BG)
