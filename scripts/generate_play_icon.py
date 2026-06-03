#!/usr/bin/env python3
"""
Render the Google Play 512x512 hi-res icon for TacticalMaps.

This reproduces the Android *adaptive launcher icon* — background colour
`launcher_background` (#151916) composited with the foreground vector in
`app/src/main/res/drawable/ic_launcher_foreground.xml` — so the store icon
matches what users see on their device home screen.

The adaptive foreground lives in a 108x108 viewport but only the central
72x72 "safe zone" is shown after the launcher's mask + zoom. We map that
safe zone (viewport 18..90) onto the full 512px canvas so the framing
matches the installed icon rather than floating tiny in a sea of background.

Supersampled 4x then downscaled for clean edges. No external assets.

    python3 scripts/generate_play_icon.py
    -> docs/store/android/play-icon-512.png
"""
from __future__ import annotations
import os
from PIL import Image, ImageDraw

OUT_SIZE = 512
SS = 4                      # supersample factor
CANVAS = OUT_SIZE * SS

# Adaptive safe zone: viewport coords 18..90 (72 wide) -> 0..CANVAS
VP_MIN, VP_SPAN = 18.0, 72.0
SCALE = CANVAS / VP_SPAN


def t(x, y):
    """viewport (108-space) -> canvas pixels."""
    return ((x - VP_MIN) * SCALE, (y - VP_MIN) * SCALE)


def w(width):
    """viewport stroke width -> canvas pixels."""
    return width * SCALE


# --- palette (from colors.xml + the vector drawable) ---
BG = (0x15, 0x19, 0x16, 255)        # launcher_background
OUTER = (0x25, 0x31, 0x29, 255)     # panel backing
LEFT = (0x2F, 0x4C, 0x35, 255)      # green half
RIGHT = (0x21, 0x3F, 0x52, 255)     # blue half
GRID = (0x8C, 0xF2, 0x8C, 107)      # hud green @ 0.42
ORANGE = (0xFF, 0xA0, 0x00, 255)    # crosshair
WHITE = (0xFF, 0xFF, 0xFF, 209)     # frame @ 0.82

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "store", "android", "play-icon-512.png")


def rect(d, x0, y0, x1, y1, fill):
    d.rectangle([t(x0, y0), t(x1, y1)], fill=fill)


def line(d, x0, y0, x1, y1, color, width, cap=False):
    p0, p1 = t(x0, y0), t(x1, y1)
    lw = int(round(w(width)))
    d.line([p0, p1], fill=color, width=lw)
    if cap:                       # round line caps
        r = lw / 2
        for (cx, cy) in (p0, p1):
            d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)


def main():
    base = Image.new("RGBA", (CANVAS, CANVAS), BG)
    d = ImageDraw.Draw(base)

    # 1-3: panel backing + green/blue halves
    rect(d, 22, 24, 86, 84, OUTER)
    rect(d, 24, 27, 48, 81, LEFT)
    rect(d, 50, 27, 84, 81, RIGHT)

    # 4: faint MGRS grid (semi-transparent -> own layer, then composite)
    grid = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    gd = ImageDraw.Draw(grid)
    for gx in (32, 44, 56, 68, 80):
        line(gd, gx, 27, gx, 81, GRID, 2)
    for gy in (36, 48, 60, 72):
        line(gd, 24, gy, 84, gy, GRID, 2)
    base = Image.alpha_composite(base, grid)
    d = ImageDraw.Draw(base)

    # 5: orange crosshair (round caps), 6: centre dot
    line(d, 54, 34, 54, 74, ORANGE, 4, cap=True)
    line(d, 34, 54, 74, 54, ORANGE, 4, cap=True)
    cx, cy = t(54, 54)
    rr = w(5)
    d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=ORANGE)

    # 7: white frame (semi-transparent -> own layer)
    frame = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    fd = ImageDraw.Draw(frame)
    for (x0, y0, x1, y1) in (
        (22, 24, 86, 24), (86, 24, 86, 84), (86, 84, 22, 84), (22, 84, 22, 24)
    ):
        line(fd, x0, y0, x1, y1, WHITE, 2)
    base = Image.alpha_composite(base, frame)

    icon = base.resize((OUT_SIZE, OUT_SIZE), Image.LANCZOS)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    icon.save(OUT, "PNG")
    print(f"wrote {OUT}  ({OUT_SIZE}x{OUT_SIZE})")


if __name__ == "__main__":
    main()
