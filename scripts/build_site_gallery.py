#!/usr/bin/env python3
"""Shrink the store slides down for the website gallery.

The store sets are 1320x2868 (iPhone) and 1080x1920 (Android phone) png,
way too heavy for a landing page. The rail renders each card about 264css
wide so 660 is still 2.5x on a retina panel.

The rail shows each of the 8 slides once, alternating platforms so both apps
are in there without doubling the rail: odd slides from the iPhone set, even
ones from the Android phone set. Slides keep their marketing banner, that's
the point of using them here.

The 2.2 slides got dropped from the tree in #55 so they're read out of git
history (SOURCE_REV). Once the site moves to a newer set, point SLIDES at it
and pass --rev '' to read the working tree instead.

    python3 scripts/build_site_gallery.py
"""

import argparse
import glob
import io
import os
import subprocess
import sys
from PIL import Image

IOS_DIR = "docs/store/ios/iphone-6.9"
ANDROID_DIR = "docs/store/android/phone"
OUT_DIR = "site/public/assets/store"
MAX_W = 660
# parent of fc4ed0e (#55), the last commit that still had the 01-08 slides
SOURCE_REV = "0d88f3092a9a944a38e2df3d4b02215bb0c8119d"

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


def read_source(path, rev):
    """png bytes from the working tree, or from git at rev. None if it isn't there."""
    if not rev:
        try:
            with open(path, "rb") as f:
                return f.read()
        except FileNotFoundError:
            return None
    got = subprocess.run(["git", "show", f"{rev}:{path}"], capture_output=True)
    return got.stdout if got.returncode == 0 else None


def write_variants(png, stem, out_dir):
    im = Image.open(io.BytesIO(png)).convert("RGB")
    if im.size[0] > MAX_W:
        im = im.resize((MAX_W, round(im.size[1] * MAX_W / im.size[0])), Image.LANCZOS)

    jpg = os.path.join(out_dir, f"{stem}.jpg")
    webp = os.path.join(out_dir, f"{stem}.webp")
    im.save(jpg, "JPEG", quality=84, optimize=True, progressive=True)
    im.save(webp, "WEBP", quality=80, method=6)
    return im.size, os.path.getsize(jpg), os.path.getsize(webp)


def main(argv=None, out_dir=OUT_DIR):
    parser = argparse.ArgumentParser(description="Shrink the store slides for the website gallery.")
    parser.add_argument("--rev", default=SOURCE_REV, help="git rev to read slides from, '' for the working tree")
    rev = parser.parse_args(argv).rev

    # load every source before touching the output dir. this used to wipe the
    # gallery first and then die on a missing png, leaving the site with no images
    sources, missing = [], []
    for name, platform in SLIDES:
        src = os.path.join(IOS_DIR if platform == "ios" else ANDROID_DIR, name + ".png")
        png = read_source(src, rev)
        if png is None:
            missing.append(src)
        else:
            sources.append((name if platform == "ios" else f"android-{name}", png))
    if missing:
        where = f"git rev {rev}" if rev else "the working tree"
        print(f"missing from {where}, gallery left alone:", file=sys.stderr)
        for src in missing:
            print(f"  {src}", file=sys.stderr)
        return 1

    os.makedirs(out_dir, exist_ok=True)
    total = 0
    written = set()
    for stem, png in sources:
        size, jpg_bytes, webp_bytes = write_variants(png, stem, out_dir)
        written.update({f"{stem}.jpg", f"{stem}.webp"})
        total += webp_bytes
        print(f"  {stem:<28} {size[0]}x{size[1]}  jpg+webp {(jpg_bytes + webp_bytes) / 1024:.0f} KB")

    # only now clear out leftovers from an older set so stale artwork can't get linked
    for old in glob.glob(os.path.join(out_dir, "*.jpg")) + glob.glob(os.path.join(out_dir, "*.webp")):
        if os.path.basename(old) not in written:
            os.remove(old)

    print(f"\nwebp payload if every card loads: {total / 1024:.0f} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
