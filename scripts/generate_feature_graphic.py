#!/usr/bin/env python3
"""
Generate the Google Play feature graphic (1024x500 PNG) for TacticalMaps.

Dark tactical theme with MGRS grid, NATO APP-6 symbols, and 1.2.0
headline (Unit Sync with live presence). Uses brand fonts from
scripts/fonts/.

    python3 scripts/generate_feature_graphic.py
    -> docs/store/android/feature-graphic.png
"""
from __future__ import annotations
import math, os
from PIL import Image, ImageDraw, ImageFont

W, H = 1024, 500
BG = (0x15, 0x19, 0x16)
GREEN = (0x8C, 0xF2, 0x8C)
ORANGE = (0xF2, 0xA2, 0x4A)
BLUE = (0x5A, 0xA0, 0xFF)
WHITE = (0xF2, 0xF5, 0xF2)
GREY = (0xB8, 0xC4, 0xBC)
DIM = (0x9A, 0xA6, 0x9E)
GRID = (0x2A, 0x3A, 0x30)
GRID_BRIGHT = (0x3A, 0x52, 0x44)

FRIEND_FILL = (0x9F, 0xD8, 0xEE)
FRIEND_FRAME = (0x12, 0x36, 0x4B)
HOSTILE_FILL = (0xF0, 0xA6, 0xA6)
HOSTILE_FRAME = (0x5E, 0x17, 0x17)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONTS = os.path.join(ROOT, "scripts", "fonts")
OUT = os.path.join(ROOT, "docs", "store", "android", "feature-graphic.png")


def font(name, size):
    p = os.path.join(FONTS, name)
    if os.path.exists(p):
        return ImageFont.truetype(p, size)
    return ImageFont.load_default()


f_title = font("Archivo-ExtraBold.ttf", 88)
f_subtitle = font("Archivo-SemiBold.ttf", 28)
f_tag = font("JetBrainsMono-Medium.ttf", 21)
f_mgrs = font("JetBrainsMono-Bold.ttf", 30)
f_small = font("JetBrainsMono-Regular.ttf", 17)
f_cap = font("JetBrainsMono-Medium.ttf", 16)
f_pill = font("Archivo-Bold.ttf", 15)


def friend_frame(d, cx, cy, w, h):
    x0, y0, x1, y1 = cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2
    d.rectangle([x0, y0, x1, y1], fill=FRIEND_FILL, outline=FRIEND_FRAME, width=3)
    return x0, y0, x1, y1


def hostile_frame(d, cx, cy, s):
    pts = [(cx, cy - s), (cx + s, cy), (cx, cy + s), (cx - s, cy)]
    d.polygon(pts, fill=HOSTILE_FILL)
    d.line(pts + [pts[0]], fill=HOSTILE_FRAME, width=3)
    return cx - s * 0.62, cy - s * 0.62, cx + s * 0.62, cy + s * 0.62


def icon_infantry(d, box, col):
    x0, y0, x1, y1 = box
    d.line([(x0, y0), (x1, y1)], fill=col, width=3)
    d.line([(x0, y1), (x1, y0)], fill=col, width=3)


def icon_armour(d, box, col):
    x0, y0, x1, y1 = box
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    ew, eh = (x1 - x0) * 0.6, (y1 - y0) * 0.44
    d.ellipse([cx - ew / 2, cy - eh / 2, cx + ew / 2, cy + eh / 2], outline=col, width=3)


def echelon_company(d, cx, top, col):
    d.line([(cx, top - 16), (cx, top - 4)], fill=col, width=3)


def echelon_battalion(d, cx, top, col):
    for dx in (-8, 0, 8):
        d.line([(cx + dx, top - 16), (cx + dx, top - 4)], fill=col, width=3)


def staff(d, cx, bottom, col):
    d.line([(cx, bottom), (cx, bottom + 18)], fill=col, width=2)
    d.ellipse([cx - 4, bottom + 16, cx + 4, bottom + 24], fill=col)


def presence_marker(d, cx, cy, r, heading_deg, callsign, col=BLUE):
    """Presence dot with heading wedge + callsign label."""
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col, outline=WHITE, width=2)
    rad = math.radians(heading_deg - 90)
    tx = cx + (r + 10) * math.cos(rad)
    ty = cy + (r + 10) * math.sin(rad)
    d.line([(cx, cy), (tx, ty)], fill=col, width=2)
    lw = d.textlength(callsign, font=f_cap)
    d.text((cx - lw / 2, cy + r + 6), callsign, font=f_cap, fill=col)


def pill(d, x, y, text, bg, fg):
    """Rounded pill badge"""
    tw = d.textlength(text, font=f_pill)
    ph = 24
    pw = tw + 20
    d.rounded_rectangle([x, y, x + pw, y + ph], radius=ph // 2, fill=bg)
    d.text((x + 10, y + 4), text, font=f_pill, fill=fg)
    return pw


def main():
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)

    # MGRS grid
    step = 64
    for x in range(0, W, step):
        d.line([(x, 0), (x, H)], fill=GRID, width=1)
    for y in range(0, H, step):
        d.line([(0, y), (W, y)], fill=GRID, width=1)

    # crosshair + compass rings on the right
    cx, cy = 790, 180
    for rr in (80, 50, 22):
        d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], outline=GRID_BRIGHT, width=1)
    d.line([(cx, 70), (cx, 440)], fill=GRID_BRIGHT, width=1)
    d.line([(640, cy), (960, cy)], fill=GRID_BRIGHT, width=1)
    d.polygon([(cx, cy - 80 - 14), (cx - 7, cy - 80 + 2), (cx + 7, cy - 80 + 2)], fill=GREEN)
    d.text((cx - 6, cy - 80 - 36), "N", font=f_cap, fill=GREEN)

    # NATO symbols
    box = friend_frame(d, 730, 260, 100, 70)
    icon_infantry(d, box, FRIEND_FRAME)
    echelon_company(d, 730, 260 - 35, FRIEND_FILL)
    staff(d, 730, 260 + 35, FRIEND_FILL)

    box = friend_frame(d, 896, 326, 90, 64)
    icon_armour(d, box, FRIEND_FRAME)
    staff(d, 896, 326 + 32, FRIEND_FILL)

    box = hostile_frame(d, 680, 380, 44)
    icon_infantry(d, box, HOSTILE_FRAME)
    staff(d, 680, 380 + 44, HOSTILE_FILL)

    # HQ marker w/ battalion echelon
    box = friend_frame(d, 850, 428, 80, 56)
    icon_infantry(d, box, FRIEND_FRAME)
    echelon_battalion(d, 850, 428 - 28, FRIEND_FILL)
    # HQ flag (small staff top-left)
    hq_x, hq_y = 850 - 40, 428 - 28
    d.line([(hq_x, hq_y), (hq_x, hq_y - 20)], fill=FRIEND_FILL, width=2)

    # presence markers (1.2.0 feature)
    presence_marker(d, 760, 330, 8, 350, "ALPHA-1")
    presence_marker(d, 870, 260, 8, 10, "BRAVO-2")
    presence_marker(d, 820, 390, 8, 270, "CHARLIE-3")

    # phase line, amber dashed
    for x in range(640, 980, 16):
        if (x // 16) % 2 == 0:
            d.line([(x, 300), (x + 12, 300)], fill=ORANGE, width=2)
    d.text((644, 304), "PL SABRE", font=f_cap, fill=ORANGE)

    # left column: wordmark + tagline
    x0 = 56
    d.text((x0, 56), "TACTICAL MAPPING", font=f_small, fill=ORANGE)
    ty = 82
    d.text((x0, ty), "TacMap", font=f_title, fill=WHITE)

    d.text((x0, 182), "One map, the whole unit.", font=f_subtitle, fill=GREEN)

    # feature bullets
    by = 228
    d.text((x0, by), "Live MGRS  ·  NATO APP-6 symbols", font=f_tag, fill=GREY)
    d.text((x0, by + 28), "Unit Sync  ·  E2E encrypted", font=f_tag, fill=GREY)
    d.text((x0, by + 56), "GeoPDF import  ·  GeoJSON export", font=f_tag, fill=GREY)

    # mgrs chip
    chip_y = 340
    label = "56HKH 50215 67845"
    pad = 14
    lw = d.textlength(label, font=f_mgrs)
    d.rounded_rectangle([x0, chip_y, x0 + lw + pad * 2, chip_y + 50],
                        radius=8, outline=GREEN, width=2)
    d.text((x0 + pad, chip_y + 10), label, font=f_mgrs, fill=GREEN)

    # status pills
    py = 406
    pw = pill(d, x0, py, "CONNECTED", (0x1A, 0x3D, 0x1A), GREEN)
    pill(d, x0 + pw + 10, py, "E2E ENCRYPTED", (0x1A, 0x2D, 0x3D), BLUE)

    # bottom tagline
    d.text((x0, 450), "Buy once  ·  Works offline  ·  No subscription",
           font=f_small, fill=DIM)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    img.save(OUT, "PNG")
    print(f"wrote {OUT}  ({W}x{H})")


if __name__ == "__main__":
    main()
