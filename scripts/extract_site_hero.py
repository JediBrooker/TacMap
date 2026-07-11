#!/usr/bin/env python3
"""Pull the device mockup out of the branded store slide for use on the website.

The landing page wants the phone from slide 01 without the marketing banner
sitting above it. We never kept the raw hero.png that compose_store_set.py fed
into the bezel, so the composed slide is the only source left. That's fine, the
screen inside the bezel is still 849px wide and the site renders it at ~280css,
so there's plenty of pixels.

Geometry is lifted straight from scripts/store_screenshots.py render() rather
than eyeballed, so if that layout ever changes this will need a look.

    python3 scripts/extract_site_hero.py
"""

import os
from PIL import Image

SRC = "docs/store/ios/iphone-6.9/01-hero.png"
OUT_JPG = "site/public/assets/screens/hero-device.jpg"
OUT_WEBP = "site/public/assets/screens/hero-device.webp"

# must match scripts/store_screenshots.py
W, H = 1320, 2868
BOTTOM_MARGIN = int(H * 0.045)          # 129
BEZEL = max(14, int(W * 0.013))         # 17
INNER_W, INNER_H = 849, 1844            # phone_top_min binds, not the 0.74*W cap
OUT_R = int(INNER_W * 0.10)             # 84, the bezel corner radius
MAX_W = 720                             # export width, see note in main()


def main():
    src = Image.open(SRC).convert("RGB")
    if src.size != (W, H):
        raise SystemExit(f"{SRC} is {src.size}, expected {(W, H)} - geometry below is stale")

    fw, fh = INNER_W + 2 * BEZEL, INNER_H + 2 * BEZEL
    fx, fy = (W - fw) // 2, (H - BOTTOM_MARGIN) - fh

    # cheap assertion that we're actually on the bezel and not the backdrop
    if src.getpixel((fx + 4, fy + fh // 2)) != (8, 9, 8):
        raise SystemExit("bezel not where we expected, refusing to crop blind")

    dev = src.crop((fx, fy, fx + fw, fy + fh))

    # the page renders this ~300css wide, so 720 is still comfortably retina
    if dev.size[0] > MAX_W:
        dev = dev.resize((MAX_W, round(fh * MAX_W / fw)), Image.LANCZOS)

    # Square crop, no alpha. An alpha png of a satellite photo is ~3MB, so the
    # corners get rounded in css instead. The radius has to be expressed as
    # x/y percentages or it goes elliptical when the image scales:
    #   border-radius: (OUT_R/fw)% / (OUT_R/fh)%
    print(f"css radius: {OUT_R / fw:.4%} / {OUT_R / fh:.4%}")

    os.makedirs(os.path.dirname(OUT_JPG), exist_ok=True)
    dev.save(OUT_JPG, "JPEG", quality=86, optimize=True, progressive=True)
    dev.save(OUT_WEBP, "WEBP", quality=82, method=6)
    for p in (OUT_JPG, OUT_WEBP):
        print(f"wrote {p} ({dev.size[0]}x{dev.size[1]}, {os.path.getsize(p) / 1024:.0f} KB)")


if __name__ == "__main__":
    main()
