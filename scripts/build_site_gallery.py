#!/usr/bin/env python3
"""Shrink the branded iPhone store slides down for the website gallery.

The store set is 1320x2868 png, ~14MB for the ten of them, which is not
something you put on a landing page. The rail renders each card about 264css
wide so 660 is still 2.5x on a retina panel.

Slides keep their marketing banner, that's the point of using them here.

    python3 scripts/build_site_gallery.py
"""

import os
import glob
from PIL import Image

SRC_DIR = "docs/store/ios/iphone-6.9"
OUT_DIR = "site/public/assets/store"
MAX_W = 660


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    srcs = sorted(glob.glob(os.path.join(SRC_DIR, "*.png")))
    if not srcs:
        raise SystemExit(f"no slides in {SRC_DIR}")

    total = 0
    for src in srcs:
        stem = os.path.splitext(os.path.basename(src))[0]
        im = Image.open(src).convert("RGB")
        if im.size[0] > MAX_W:
            im = im.resize((MAX_W, round(im.size[1] * MAX_W / im.size[0])), Image.LANCZOS)

        jpg = os.path.join(OUT_DIR, f"{stem}.jpg")
        webp = os.path.join(OUT_DIR, f"{stem}.webp")
        im.save(jpg, "JPEG", quality=84, optimize=True, progressive=True)
        im.save(webp, "WEBP", quality=80, method=6)

        kb = (os.path.getsize(jpg) + os.path.getsize(webp)) / 1024
        total += os.path.getsize(webp)
        print(f"  {stem:<20} {im.size[0]}x{im.size[1]}  jpg+webp {kb:.0f} KB")

    print(f"\nwebp payload if every card loads: {total / 1024:.0f} KB")


if __name__ == "__main__":
    main()
