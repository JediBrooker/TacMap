#!/usr/bin/env python3
"""Shrink release-approved store slides down for the website gallery.

The store set is 1320x2868 png, ~14MB for the ten of them, which is not
something you put on a landing page. The rail renders each card about 264css
wide so 660 is still 2.5x on a retina panel.

Slides keep their marketing banner, that's the point of using them here. The
allowlist deliberately excludes stale artwork so rerunning this script cannot
silently republish a pre-release UI.

    python3 scripts/build_site_gallery.py
"""

import os
import glob
from PIL import Image

SRC_DIR = "docs/store/ios/iphone-6.9"
OUT_DIR = "site/public/assets/store"
MAX_W = 660

ANDROID_APPROVED = {
    "docs/store/android/phone/01-hero.png": "android-01-field-tools",
    "docs/store/android/phone/02-unit-sync.png": "android-02-unit-sync",
}


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
    ios_srcs = [
        src for src in sorted(glob.glob(os.path.join(SRC_DIR, "*.png")))
        if os.path.basename(src) != "01-hero.png"
    ]
    sources = [(src, os.path.splitext(os.path.basename(src))[0]) for src in ios_srcs]
    sources.extend(ANDROID_APPROVED.items())
    if not sources:
        raise SystemExit(f"no slides in {SRC_DIR}")

    total = 0
    for src, stem in sources:
        size, jpg_bytes, webp_bytes = write_variants(src, stem)
        kb = (jpg_bytes + webp_bytes) / 1024
        total += webp_bytes
        print(f"  {stem:<24} {size[0]}x{size[1]}  jpg+webp {kb:.0f} KB")

    print(f"\nwebp payload if every card loads: {total / 1024:.0f} KB")


if __name__ == "__main__":
    main()
