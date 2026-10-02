#!/usr/bin/env python3
"""Shrink the store slides down for the website gallery.

The store sets are 1320x2868 (iPhone) and 1080x1920 (Android phone) png,
way too heavy for a landing page. The rail renders each card about 264css
wide so 660 is still 2.5x on a retina panel.

The rail shows each of the 8 slides once, alternating platforms so both apps
are in there without doubling the rail: odd slides from the iPhone set, even
ones from the Android phone set. Slides keep their marketing banner, that's
the point of using them here.

    python3 scripts/build_site_gallery.py
"""

import os
import glob
from PIL import Image

IOS_DIR = "docs/store/ios/iphone-6.9"
ANDROID_DIR = "docs/store/android/phone"
OUT_DIR = "site/public/assets/store"
MAX_W = 660

SLIDES = [
    ("01-hero", "ios"),
    ("02-line-of-sight", "android"),
    ("03-night-mode", "ios"),
    ("04-unit-sync", "android"),
    ("05-sun-moon", "ios"),
    ("06-pdfmap", "android"),
    ("07-symbol-builder", "ios"),
    ("08-export", "android"),
]


def write_variants(src, stem):
    im = Image.open(src).convert("RGB")
    if im.size[0] > MAX_W:
        im = im.resize((MAX_W, round(im.size[1] * MAX_W / im.size[0])), Image.LANCZOS)

    jpg = os.path.join(OUT_DIR, f"{stem}.jpg")
    webp = os.path.join(OUT_DIR, f"{stem}.webp")
    im.save(jpg, "JPEG", quality=84, optimize=True, progressive=True)
    im.save(webp, "WEBP", quality=80, method=6)
    return im.size, os.path.getsize(jpg), os.path.getsize(webp)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    # clear out the old set so stale artwork can't hang around and get linked
    for old in glob.glob(os.path.join(OUT_DIR, "*.jpg")) + glob.glob(os.path.join(OUT_DIR, "*.webp")):
        os.remove(old)

    total = 0
    for name, platform in SLIDES:
        src = os.path.join(IOS_DIR if platform == "ios" else ANDROID_DIR, name + ".png")
        stem = name if platform == "ios" else f"android-{name}"
        size, jpg_bytes, webp_bytes = write_variants(src, stem)
        total += webp_bytes
        print(f"  {stem:<28} {size[0]}x{size[1]}  jpg+webp {(jpg_bytes + webp_bytes) / 1024:.0f} KB")

    print(f"\nwebp payload if every card loads: {total / 1024:.0f} KB")


if __name__ == "__main__":
    main()
