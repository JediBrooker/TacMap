#!/usr/bin/env python3
"""
Simulate a GPX-recording scene on a clean HUD capture: overlay a recorded
breadcrumb track + the app's REC indicator. App records & exports tracks
but doesn't draw the live polyline on map yet, so this visualises what a
recording in progress looks like for the store screenshot.

    python3 scripts/_recording_overlay.py <base.png> <out.png> <pts> <pillYfrac>
"""
import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import os

HERE = os.path.dirname(os.path.abspath(__file__))
FONTS = os.path.join(HERE, "fonts")
def font(name, size):
    return ImageFont.truetype(os.path.join(FONTS, {"xbold":"Archivo-ExtraBold.ttf","bold":"Archivo-Bold.ttf","mono":"JetBrainsMono-Bold.ttf"}[name]), size)

base_path, out_path = sys.argv[1], sys.argv[2]
PTS = sys.argv[3] if len(sys.argv) > 3 else "312"
PILL_Y = float(sys.argv[4]) if len(sys.argv) > 4 else 0.175

im = Image.open(base_path).convert("RGB")
W, H = im.size

# --- recorded route (normalised control points), winds toward crosshair ---
ctrl = [(0.20,0.84),(0.31,0.80),(0.27,0.71),(0.40,0.69),(0.45,0.60),
        (0.35,0.555),(0.44,0.515),(0.505,0.475)]

def catmull(points, samples=24):
    pts = [points[0]] + points + [points[-1]]
    out = []
    for i in range(1, len(pts)-2):
        p0,p1,p2,p3 = pts[i-1],pts[i],pts[i+1],pts[i+2]
        for s in range(samples):
            t=s/samples; t2=t*t; t3=t2*t
            x=0.5*((2*p1[0])+(-p0[0]+p2[0])*t+(2*p0[0]-5*p1[0]+4*p2[0]-p3[0])*t2+(-p0[0]+3*p1[0]-3*p2[0]+p3[0])*t3)
            y=0.5*((2*p1[1])+(-p0[1]+p2[1])*t+(2*p0[1]-5*p1[1]+4*p2[1]-p3[1])*t2+(-p0[1]+3*p1[1]-3*p2[1]+p3[1])*t3)
            out.append((x,y))
    out.append(points[-1])
    return out

path = [(int(x*W), int(y*H)) for x,y in catmull(ctrl)]

CY_HALO = (45,210,255,70)
CY_MID  = (45,210,255,170)
CY_CORE = (215,248,255,255)

ov = Image.new("RGBA", (W,H), (0,0,0,0))
d = ImageDraw.Draw(ov)
d.line(path, fill=CY_HALO, width=int(W*0.024), joint="curve")
d.line(path, fill=CY_MID,  width=int(W*0.013), joint="curve")
d.line(path, fill=CY_CORE, width=max(3,int(W*0.0055)), joint="curve")
# breadcrumb dots at control points
for x,y in [(int(a*W),int(b*H)) for a,b in ctrl[1:-1]]:
    r=int(W*0.009)
    d.ellipse([x-r,y-r,x+r,y+r], fill=(45,210,255,210), outline=(235,250,255,255), width=2)
ov = ov.filter(ImageFilter.GaussianBlur(0.6))
im = Image.alpha_composite(im.convert("RGBA"), ov).convert("RGB")
d = ImageDraw.Draw(im, "RGBA")

# start flag (hollow ring) + live position dot (glowing) at path ends
sx,sy = path[0]
d.ellipse([sx-int(W*0.013),sy-int(W*0.013),sx+int(W*0.013),sy+int(W*0.013)], outline=(235,250,255,255), width=max(3,int(W*0.004)))
ex,ey = path[-1]
for r,a in [(int(W*0.030),60),(int(W*0.020),110)]:
    d.ellipse([ex-r,ey-r,ex+r,ey+r], fill=(45,210,255,a))
r=int(W*0.012)
d.ellipse([ex-r,ey-r,ex+r,ey+r], fill=(45,210,255,255), outline=(255,255,255,255), width=max(2,int(W*0.004)))

# --- REC pill (matches the app's RecordingIndicator: red, dot, REC, pts) ---
recf = font("xbold", int(W*0.034))
ptsf = font("mono", int(W*0.030))
rec_txt, pts_txt = "REC", f"·  {PTS} pts"
gap = int(W*0.022); dot_r = int(W*0.011); padx = int(W*0.034); pady = int(W*0.020)
rw = d.textlength(rec_txt, font=recf); pw = d.textlength(pts_txt, font=ptsf)
inner = dot_r*2 + gap + rw + int(W*0.022) + pw
cap_w = int(inner + padx*2); cap_h = int(recf.size + pady*2)
cx = (W - cap_w)//2; cy = int(H*PILL_Y)
d.rounded_rectangle([cx,cy,cx+cap_w,cy+cap_h], radius=cap_h//2, fill=(214,46,46,235))
ix = cx + padx; mid = cy + cap_h//2
d.ellipse([ix,mid-dot_r,ix+dot_r*2,mid+dot_r*2-dot_r], fill=(255,255,255,255))
ix += dot_r*2 + gap
d.text((ix, mid - recf.size//2 - int(W*0.004)), rec_txt, font=recf, fill=(255,255,255,255))
ix += rw + int(W*0.022)
d.text((ix, mid - ptsf.size//2 - int(W*0.002)), pts_txt, font=ptsf, fill=(255,255,255,235))

im.save(out_path, "PNG")
print("wrote", out_path, im.size)
