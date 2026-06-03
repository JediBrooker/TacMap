#!/usr/bin/env python3
"""
Generate the Google Play feature graphic (1024x500 PNG) for TacticalMaps.

On-brand with the app: dark field background (#151916), tactical-green HUD
type (#8CF28C), a faint MGRS grid + crosshair, a live-style grid reference,
and NATO APP-6 military symbols (friend / hostile affiliation frames) placed
as map markers — mirroring the app's milsymbol feature. No external assets:
uses macOS system fonts and Pillow.

    python3 scripts/generate_feature_graphic.py
    -> docs/store/android/feature-graphic.png
"""
from __future__ import annotations
import os
from PIL import Image, ImageDraw, ImageFont

W, H = 1024, 500
BG = (0x15, 0x19, 0x16)
GREEN = (0x8C, 0xF2, 0x8C)
ORANGE = (0xF2, 0xA2, 0x4A)
WHITE = (0xF2, 0xF5, 0xF2)
GREY = (0xB8, 0xC4, 0xBC)
DIM = (0x9A, 0xA6, 0x9E)
GRID = (0x2A, 0x3A, 0x30)
GRID_BRIGHT = (0x3A, 0x52, 0x44)

# APP-6 affiliation colours (recognisable: friend = blue, hostile = red)
FRIEND_FILL = (0x9F, 0xD8, 0xEE)
FRIEND_FRAME = (0x12, 0x36, 0x4B)
HOSTILE_FILL = (0xF0, 0xA6, 0xA6)
HOSTILE_FRAME = (0x5E, 0x17, 0x17)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "store", "android", "feature-graphic.png")


def font(paths, size):
    for p in paths:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except OSError:
                pass
    return ImageFont.load_default()


SANS_BOLD = ["/System/Library/Fonts/SFNS.ttf",
             "/System/Library/Fonts/HelveticaNeue.ttc"]
MONO = ["/System/Library/Fonts/Menlo.ttc",
        "/System/Library/Fonts/SFNSMono.ttf"]

f_title = font(SANS_BOLD, 92)
f_tag = font(MONO, 25)
f_mgrs = font(MONO, 33)
f_small = font(MONO, 21)
f_cap = font(MONO, 19)


# ---- NATO APP-6 symbol primitives -------------------------------------------
def friend_frame(d, cx, cy, w, h):
    """Friendly affiliation = rectangle frame."""
    x0, y0, x1, y1 = cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2
    d.rectangle([x0, y0, x1, y1], fill=FRIEND_FILL, outline=FRIEND_FRAME, width=4)
    return x0, y0, x1, y1


def hostile_frame(d, cx, cy, s):
    """Hostile affiliation = diamond (square on point)."""
    pts = [(cx, cy - s), (cx + s, cy), (cx, cy + s), (cx - s, cy)]
    d.polygon(pts, fill=HOSTILE_FILL)
    d.line(pts + [pts[0]], fill=HOSTILE_FRAME, width=4)
    return cx - s * 0.62, cy - s * 0.62, cx + s * 0.62, cy + s * 0.62


def icon_infantry(d, box, col):
    x0, y0, x1, y1 = box
    d.line([(x0, y0), (x1, y1)], fill=col, width=4)
    d.line([(x0, y1), (x1, y0)], fill=col, width=4)


def icon_armour(d, box, col):
    x0, y0, x1, y1 = box
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    ew, eh = (x1 - x0) * 0.62, (y1 - y0) * 0.46
    d.ellipse([cx - ew / 2, cy - eh / 2, cx + ew / 2, cy + eh / 2], outline=col, width=4)


def echelon_company(d, cx, top, col):
    """Company echelon = single vertical bar above the frame."""
    d.line([(cx, top - 20), (cx, top - 6)], fill=col, width=4)


def staff(d, cx, bottom, col):
    """Short staff + anchor dot to the ground position."""
    d.line([(cx, bottom), (cx, bottom + 22)], fill=col, width=3)
    d.ellipse([cx - 5, bottom + 20, cx + 5, bottom + 30], fill=col)


def main():
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)

    # --- faint MGRS grid ---
    step = 64
    for x in range(0, W, step):
        d.line([(x, 0), (x, H)], fill=GRID, width=1)
    for y in range(0, H, step):
        d.line([(0, y), (W, y)], fill=GRID, width=1)

    # --- faint compass + crosshair anchoring the symbol cluster ---
    cx, cy = 828, 168
    for rr in (96, 60, 26):
        d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], outline=GRID_BRIGHT, width=2)
    d.line([(cx, 40), (cx, 470)], fill=GRID_BRIGHT, width=1)
    d.line([(640, cy), (1020, cy)], fill=GRID_BRIGHT, width=1)
    d.polygon([(cx, cy - 96 - 16), (cx - 8, cy - 96 + 2), (cx + 8, cy - 96 + 2)], fill=GREEN)
    d.text((cx - 7, cy - 96 - 44), "N", font=f_cap, fill=GREEN)

    # --- APP-6 markers (friend infantry hero, friend armour, hostile) ---
    # friendly infantry, company echelon
    fx, fy, fw, fh = 770, 250, 122, 86
    box = friend_frame(d, fx, fy, fw, fh)
    icon_infantry(d, box, FRIEND_FRAME)
    echelon_company(d, fx, fy - fh / 2, FRIEND_FILL)
    staff(d, fx, fy + fh / 2, FRIEND_FILL)

    # friendly armour
    ax, ay, aw, ah = 936, 322, 112, 78
    box = friend_frame(d, ax, ay, aw, ah)
    icon_armour(d, box, FRIEND_FRAME)
    staff(d, ax, ay + ah / 2, FRIEND_FILL)

    # hostile infantry
    hx, hy, hs = 716, 372, 56
    box = hostile_frame(d, hx, hy, hs)
    icon_infantry(d, box, HOSTILE_FRAME)
    staff(d, hx, hy + hs, HOSTILE_FILL)

    # caption
    d.text((648, 452), "NATO APP-6 SYMBOLOGY", font=f_cap, fill=GREEN)

    # --- left column: wordmark + tagline + live grid ref ---
    x0 = 60
    d.text((x0, 86), "FIELD NAVIGATION", font=f_small, fill=ORANGE)
    ty = 120
    d.text((x0, ty), "Tactical", font=f_title, fill=WHITE)
    tw = d.textlength("Tactical", font=f_title)
    d.text((x0 + tw, ty), "Maps", font=f_title, fill=GREEN)

    d.text((x0, 236), "Live MGRS  ·  GeoPDF basemaps", font=f_tag, fill=GREY)
    d.text((x0, 268), "APP-6 symbols  ·  GeoJSON export", font=f_tag, fill=GREY)

    chip_y = 314
    label = "56HLH 13225 37516"
    pad = 16
    lw = d.textlength(label, font=f_mgrs)
    d.rounded_rectangle([x0, chip_y, x0 + lw + pad * 2, chip_y + 60],
                        radius=10, outline=GREEN, width=2)
    d.text((x0 + pad, chip_y + 13), label, font=f_mgrs, fill=GREEN)
    d.text((x0, chip_y + 76), "0640 mils   ·   112 m MSL", font=f_small, fill=DIM)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    img.save(OUT, "PNG")
    print(f"wrote {OUT}  ({W}x{H})")


if __name__ == "__main__":
    main()
