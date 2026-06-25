#!/usr/bin/env python3
"""
Composite a NATO APP-6 company-attack overlay onto the Android Esri HUD hero.

Android's symbols are Google-Maps GroundOverlays, which don't render on a
locally-signed build (the Maps key is whitelisted to the Play signing cert), so
the iOS sync-push capture trick can't be used. This re-draws the SAME situation
(friend rectangles, hostile diamonds, phase lines, objective, axis, assembly
area) faithfully in code, matching the iOS hero's composition, so the Google
Play hero also leads with symbology.

    python3 scripts/android_symbology_hero.py <base a01-hud.png> <out a-hero.png>
"""
import sys, os, math
from PIL import Image, ImageDraw, ImageFont, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
FONTS = os.path.join(HERE, "fonts")
def font(name, size):
    return ImageFont.truetype(os.path.join(FONTS, {"bold":"Archivo-Bold.ttf","mono":"JetBrainsMono-Bold.ttf"}[name]), size)

base_path, out_path = sys.argv[1], sys.argv[2]
im = Image.open(base_path).convert("RGB")
W, H = im.size
ov = Image.new("RGBA", (W, H), (0, 0, 0, 0))
d = ImageDraw.Draw(ov)

BLACK=(0,0,0,255); WHITE=(255,255,255,255)
FRIEND=(128,224,255,255); HOSTILE=(255,128,128,255)
AMBER=(226,164,0,255); BLUE=(14,95,216,255); GREEN=(30,138,52,255); RED=(216,40,31,255)

def label(x, y, text, anchor="lm", size=26):
    f = font("bold", size)
    d.text((x, y), text, font=f, fill=WHITE, anchor=anchor,
           stroke_width=4, stroke_fill=(0,0,0,235))

def glide(pts, color, width, halo=True):
    if halo:
        d.line(pts, fill=(0,0,0,170), width=width+6, joint="curve")
    d.line(pts, fill=color, width=width, joint="curve")

def echelon(cx, top, ech):
    if ech == "platoon":
        for dx in (-13, 0, 13):
            d.ellipse([cx+dx-4, top-16, cx+dx+4, top-8], fill=BLACK)
    elif ech == "section":
        d.ellipse([cx-4, top-16, cx+4, top-8], fill=BLACK)
    elif ech == "company":
        d.rectangle([cx-3, top-24, cx+3, top-6], fill=BLACK)

def friend(cx, cy, ech, fn, hq=False, name=None):
    w, h = 84, 54
    x0, y0, x1, y1 = cx-w//2, cy-h//2, cx+w//2, cy+h//2
    d.rectangle([x0, y0, x1, y1], fill=FRIEND, outline=BLACK, width=4)
    if fn == "infantry":
        d.line([x0+5, y0+5, x1-5, y1-5], fill=BLACK, width=5)
        d.line([x1-5, y0+5, x0+5, y1-5], fill=BLACK, width=5)
    elif fn == "antiTank":
        d.line([x0+8, y1-7, cx, y0+7], fill=BLACK, width=5)
        d.line([cx, y0+7, x1-8, y1-7], fill=BLACK, width=5)
    echelon(cx, y0, ech)
    if hq:
        d.line([x0, y1, x0, y1+34], fill=BLACK, width=4)
    if name:
        label(x1+12, cy, name)

def hostile(cx, cy, ech, fn, name=None):
    r = 42
    pts = [(cx, cy-r), (cx+r, cy), (cx, cy+r), (cx-r, cy)]
    d.polygon(pts, fill=HOSTILE, outline=BLACK, width=4)
    s = r*0.42
    d.line([cx-s, cy-s, cx+s, cy+s], fill=BLACK, width=5)
    d.line([cx+s, cy-s, cx-s, cy+s], fill=BLACK, width=5)
    echelon(cx, cy-r, ech)
    if name:
        label(cx+r+10, cy, name)

def area_ellipse(cx, cy, rx, ry, stroke, fill, name=None):
    d.ellipse([cx-rx, cy-ry, cx+rx, cy+ry], outline=stroke, width=5,
              fill=(fill[0], fill[1], fill[2], 46))
    if name:
        label(cx, cy-ry-6, name, anchor="mb")

def area_rect(x0, y0, x1, y1, stroke, fill, name=None):
    d.rectangle([x0, y0, x1, y1], outline=stroke, width=4,
                fill=(fill[0], fill[1], fill[2], 38))
    if name:
        label(x0+6, y0-6, name, anchor="lb")

# --- compose the situation (screen space, mirrors the iOS hero) ---
# Objective + enemy (north / top)
area_ellipse(540, 720, 205, 150, RED, RED, "OBJ FALCON")
hostile(478, 695, "platoon", "infantry", "EN")
hostile(612, 745, "section", "infantry", "EN")
# Phase line + line of departure
glide([(195, 980), (885, 980)], AMBER, 6); label(205, 952, "PL BLUE", size=24)
glide([(195, 1180), (885, 1180)], AMBER, 6); label(205, 1152, "LD / LC", size=24)
# Axis of advance (south -> objective)
glide([(545, 1560), (548, 1360), (532, 1160), (540, 980), (540, 815)], BLUE, 8)
label(560, 1300, "AXIS DAGGER", size=24)
# Assembly area + friendly assault element (south / bottom)
area_rect(295, 1455, 705, 1775, GREEN, GREEN, "AA EAGLE")
friend(360, 1545, "platoon", "infantry", name="1 PL")
friend(650, 1545, "platoon", "infantry", name="2 PL")
friend(500, 1660, "company", "infantry", hq=True, name="CO HQ")
friend(775, 1470, "platoon", "antiTank", name="SBF")

# --- Unit Sync indicator in the header (the base capture was disconnected) ---
# Centred on the "Live Location … Accuracy" row, matching the app's blue chip.
def sync_chip(cx, cy):
    blue = (79, 168, 255, 255)
    ix = cx - 70
    d.ellipse([ix-3, cy-3, ix+3, cy+3], fill=blue)          # source dot
    for r in (9, 16):                                        # broadcast arcs (both sides)
        d.arc([ix-r, cy-r, ix+r, cy+r], -55, 55, fill=blue, width=3)
        d.arc([ix-r, cy-r, ix+r, cy+r], 125, 235, fill=blue, width=3)
    d.text((ix+24, cy), "Unit Sync", font=font("bold", 27), fill=blue, anchor="lm")
sync_chip(540, 398)

ov = ov.filter(ImageFilter.GaussianBlur(0.4))
out = Image.alpha_composite(im.convert("RGBA"), ov).convert("RGB")
out.save(out_path, "PNG")
print("wrote", out_path, out.size)
