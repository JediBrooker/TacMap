#!/usr/bin/env python3
"""Derive the website's brand mark from the iOS app icon.

Single source of truth is the appiconset, so if the icon ever gets redrawn the
site picks it up by rerunning this rather than someone hand-copying a png into
assets/brand and forgetting.

The source is a plain opaque square (ios masks the corners itself at render
time), so we leave it square here and let css round it. That keeps the favicon
looking right too, since browsers don't want a pre-rounded icon.

    python3 scripts/build_site_brand.py
"""

import os
from PIL import Image

SRC = "ios/TacticalMaps/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"
OUT = "site/public/assets/brand/app-icon-512.png"
SIZE = 512


def main():
    im = Image.open(SRC)
    if im.size != (1024, 1024):
        raise SystemExit(f"{SRC} is {im.size}, expected 1024x1024")

    im = im.convert("RGB").resize((SIZE, SIZE), Image.LANCZOS)

    # straight rgba png of this is 117KB, which is silly for something we draw
    # at 26px. 128 colours + dither is indistinguishable even at full size and
    # lands around 34KB. the faint grid in the background survives fine.
    im = im.quantize(colors=128, method=Image.MEDIANCUT, dither=Image.FLOYDSTEINBERG)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    im.save(OUT, "PNG", optimize=True)
    print(f"wrote {OUT} ({SIZE}x{SIZE}, {os.path.getsize(OUT) / 1024:.0f} KB)")


if __name__ == "__main__":
    main()
