#!/usr/bin/env python3
"""
Composite a real topo basemap behind the literal hero capture, and redraw
the app's centre crosshair cleanly.

OpenTopoMap throttles the simulator's tile requests so the captured app's
Terrain basemap mostly comes through as unloaded grey placeholder. We just
fill that grey with a real Esri World Topo basemap (faithful stand-in for
the app's OpenTopoMap terrain).

The app's centre crosshair is permanent UI so it stays, but its
semi-transparent orange glow over the grey leaves a muddy halo when grey
gets replaced. So we detect crosshair centre + exact orange from capture,
strip the old muddy crosshair, then redraw a clean one in the app's colour.

    python3 scripts/topo_hero_composite.py <capture.png> <out.png>
"""
import sys, math, io, urllib.request
from PIL import Image, ImageChops, ImageDraw, ImageFilter

src, out = sys.argv[1], sys.argv[2]
hero = Image.open(src).convert("RGB"); W, H = hero.size
px = hero.load()

# --- detect the crosshair: orange pixels in the map region ---
colcount = [0] * W; rowcount = [0] * H; samples = []
y0, y1 = int(0.18 * H), int(0.90 * H)
for y in range(y0, y1, 2):
    for x in range(0, W, 2):
        r, g, b = px[x, y]
        if r > 200 and 90 < g < 195 and b < 95:      # tactical orange
            colcount[x] += 1; rowcount[y] += 1
            if len(samples) < 8000: samples.append((r, g, b))
cx = max(range(W), key=lambda i: colcount[i])
cy = max(range(H), key=lambda i: rowcount[i])
if samples:
    rs = sorted(s[0] for s in samples); gs = sorted(s[1] for s in samples); bs = sorted(s[2] for s in samples)
    ocol = (rs[len(rs)//2], gs[len(gs)//2], bs[len(bs)//2])
else:
    ocol = (255, 149, 0)

# --- fetch Esri World Topo for the viewport ---
LAT, LON = -33.700, 150.305
def merc(lat, lon):
    x = lon * 20037508.34 / 180.0
    y = math.log(math.tan((90 + lat) * math.pi / 360.0)) / (math.pi / 180.0) * 20037508.34 / 180.0
    return x, y
mx, my = merc(LAT, LON); half_w = 1930.0; half_h = half_w * H / W
bbox = f"{mx-half_w},{my-half_h},{mx+half_w},{my+half_h}"
url = ("https://server.arcgisonline.com/arcgis/rest/services/World_Topo_Map/MapServer/export"
       f"?bbox={bbox}&bboxSR=3857&imageSR=3857&size={W},{H}&format=png&f=image")
topo = Image.open(io.BytesIO(urllib.request.urlopen(
    urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=45).read())).convert("RGB").resize((W, H))

# --- mask: unloaded grey + old orange crosshair (nuke both, fully replaced) ---
hsv = hero.convert("HSV"); hh, ss, vv = hsv.split()
graym = ImageChops.multiply(ss.point(lambda v: 255 if v < 55 else 0),
                            vv.point(lambda v: 255 if 60 < v < 195 else 0))
orange = ImageChops.multiply(hh.point(lambda v: 255 if 10 <= v <= 42 else 0),
                             ss.point(lambda v: 255 if v >= 55 else 0))
region = Image.new("L", (W, H), 0)
ImageDraw.Draw(region).rectangle([0, int(0.165 * H), W, H], fill=255)
orange = ImageChops.multiply(orange, region)
comp = Image.composite(topo, hero, ImageChops.lighter(graym, orange)).convert("RGBA")

# --- redraw clean crosshair in app's orange (hairlines + 26pt ring + glow) ---
lw = max(4, round(W / 300))                  # ~1.5pt @3x
ring = round(W * 0.0645)                      # ~26pt diameter @3x
top, bot = int(0.165 * H), int(0.905 * H)     # keep clear of header + bottom button
glow = Image.new("RGBA", (W, H), (0, 0, 0, 0)); gd = ImageDraw.Draw(glow)
gw = lw * 4; gc = (*ocol, 110)
gd.line([(cx, top), (cx, bot)], fill=gc, width=gw)
gd.line([(0, cy), (W, cy)], fill=gc, width=gw)
gd.ellipse([cx-ring, cy-ring, cx+ring, cy+ring], outline=gc, width=gw)
glow = glow.filter(ImageFilter.GaussianBlur(6))
core = Image.new("RGBA", (W, H), (0, 0, 0, 0)); cd = ImageDraw.Draw(core)
cc = (*ocol, 242)
cd.line([(cx, top), (cx, bot)], fill=cc, width=lw)
cd.line([(0, cy), (W, cy)], fill=cc, width=lw)
cd.ellipse([cx-ring, cy-ring, cx+ring, cy+ring], outline=cc, width=lw)
comp = Image.alpha_composite(Image.alpha_composite(comp, glow), core)
comp.convert("RGB").save(out)
print(f"wrote {out}  crosshair@({cx},{cy}) colour={ocol}")
