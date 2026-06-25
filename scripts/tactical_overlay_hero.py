#!/usr/bin/env python3
"""
Composite a RICH NATO APP-6 operational overlay onto a HUD backdrop, in the
style of a real division/corps operations graphic: crenellated FLOT/FEBA
forward lines (the signature comb graphic), echelon unit symbols (friend
rectangles / hostile diamonds, bde/div), boundaries with echelon labels,
boxed objectives, a strongpoint, and axes of advance.

The app's own drawing tools render plain polylines — they have no crenellated
FLOT/boundary graphics — so this is a designed marketing composite over the
app's real HUD chrome (header, MGRS/UTM readout, crosshair, controls).

    python3 scripts/tactical_overlay_hero.py <base.png> <out.png> <sync_y_frac>
"""
import sys, os, math
from PIL import Image, ImageDraw, ImageFont, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__)); FONTS = os.path.join(HERE, "fonts")
def font(name, size):
    return ImageFont.truetype(os.path.join(FONTS, {"bold":"Archivo-Bold.ttf","xbold":"Archivo-ExtraBold.ttf","mono":"JetBrainsMono-Bold.ttf"}[name]), size)

base_path, out_path = sys.argv[1], sys.argv[2]
SYNC_Y = float(sys.argv[3]) if len(sys.argv) > 3 else 0.16
im = Image.open(base_path).convert("RGB"); W, H = im.size
ov = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(ov)
S = W / 1080.0                                  # scale factor

BLACK=(0,0,0,255); WHITE=(255,255,255,255)
FRIEND=(128,224,255,255); HOSTILE=(255,128,128,255)
RED=(255,86,74,255); FRIENDLINE=(96,168,255,255); AMBER=(246,200,84,255)
GREEN=(74,222,128,255); BLUE=(96,168,255,255); CTRL=(238,242,236,255)
def P(nx, ny): return (nx*W, ny*H)

def label(x, y, text, anchor="lm", size=24, fill=WHITE):
    d.text((x, y), text, font=font("bold", int(size*S)), fill=fill, anchor=anchor,
           stroke_width=max(3,int(4*S)), stroke_fill=(0,0,0,235))

# ---- geometry: resample a polyline with cumulative arc-length + left normal ----
def resample(pts, step):
    out=[]; s=0.0
    for i in range(len(pts)-1):
        x0,y0=pts[i]; x1,y1=pts[i+1]; dx,dy=x1-x0,y1-y0; L=math.hypot(dx,dy)
        if L==0: continue
        ux,uy=dx/L,dy/L; nx,ny=-uy,ux; k=0
        while k*step < L:
            t=k*step; out.append((x0+ux*t, y0+uy*t, nx, ny, s+t)); k+=1
        s+=L
    x,y=pts[-1]; out.append((x,y,0,0,s)); return out

def crenellate(pts, color, width, period, height, side=1):
    """Square-wave (battlement) line — the FLOT/FEBA forward-line graphic.
    Drawn with a dark halo so it reads on dark satellite imagery."""
    samp=resample(pts, max(2, 3*S)); path=[]
    half=period/2.0
    for (x,y,nx,ny,s) in samp:
        off = height if (int(s//half) % 2 == 1) else 0
        path.append((x + nx*off*side, y + ny*off*side))
    d.line(path, fill=(0,0,0,180), width=int(width+6*S), joint="curve")
    d.line(path, fill=color, width=int(width), joint="curve")

def arrow(pts, color, width):
    d.line(pts, fill=(0,0,0,150), width=int(width+4*S), joint="curve")
    d.line(pts, fill=color, width=int(width), joint="curve")
    (x0,y0),(x1,y1)=pts[-2],pts[-1]; ang=math.atan2(y1-y0,x1-x0); a=22*S*1.0
    hl=26*S
    for da in (math.radians(150), math.radians(-150)):
        d.line([(x1,y1),(x1+hl*math.cos(ang+da), y1+hl*math.sin(ang+da))], fill=color, width=int(width))

def echelon(cx, top, code):
    g=BLACK
    if code in ("platoon","section","team"):
        n={"team":0,"section":1,"platoon":3}[code]
        for i in range(n):
            x=cx+(i-(n-1)/2)*13*S
            d.ellipse([x-4*S,top-16*S,x+4*S,top-8*S], fill=g)
    elif code in ("I","II","III"):
        n=len(code)
        for i in range(n):
            x=cx+(i-(n-1)/2)*9*S
            d.rectangle([x-2.5*S,top-26*S,x+2.5*S,top-6*S], fill=g)
    elif code in ("X","XX","XXX"):
        n=len(code)
        for i in range(n):
            x=cx+(i-(n-1)/2)*15*S; s=10*S
            d.line([x-s,top-26*S,x+s,top-6*S], fill=g, width=int(3*S))
            d.line([x+s,top-26*S,x-s,top-6*S], fill=g, width=int(3*S))

def unit(nx, ny, aff, ech, fn="infantry", hq=False, name=None):
    cx,cy=P(nx,ny)
    if aff=="friend":
        w,h=84*S,54*S; x0,y0,x1,y1=cx-w/2,cy-h/2,cx+w/2,cy+h/2
        d.rectangle([x0,y0,x1,y1], fill=FRIEND, outline=BLACK, width=int(4*S))
        if fn=="infantry":
            d.line([x0+6*S,y0+6*S,x1-6*S,y1-6*S], fill=BLACK, width=int(5*S))
            d.line([x1-6*S,y0+6*S,x0+6*S,y1-6*S], fill=BLACK, width=int(5*S))
        elif fn=="armour":
            d.rounded_rectangle([cx-w*0.3,cy-h*0.2,cx+w*0.3,cy+h*0.2], radius=h*0.2, outline=BLACK, width=int(4*S))
        echelon(cx,y0,ech)
        if hq: d.line([x0,y1,x0,y1+34*S], fill=BLACK, width=int(4*S))
        if name: label(x1+10*S, cy, name, size=22)
    else:
        r=42*S; pts=[(cx,cy-r),(cx+r,cy),(cx,cy+r),(cx-r,cy)]
        d.polygon(pts, fill=HOSTILE, outline=BLACK, width=int(4*S))
        s=r*0.42
        d.line([cx-s,cy-s,cx+s,cy+s], fill=BLACK, width=int(5*S))
        d.line([cx+s,cy-s,cx-s,cy+s], fill=BLACK, width=int(5*S))
        echelon(cx,cy-r,ech)
        if name: label(cx+r+8*S, cy, name, size=22)

def boundary(pts, ech="XX"):
    p=[P(*q) for q in pts]
    d.line(p, fill=(0,0,0,190), width=int(9*S), joint="curve")
    d.line(p, fill=CTRL, width=int(4*S), joint="curve")
    mx,my=p[len(p)//2]
    bw,bh=48*S,32*S
    d.rectangle([mx-bw/2,my-bh/2,mx+bw/2,my+bh/2], fill=(15,18,15,255), outline=CTRL, width=int(2.5*S))
    d.text((mx,my), ech, font=font("xbold",int(20*S)), fill=CTRL, anchor="mm")

def objective(nx0,ny0,nx1,ny1,name):
    x0,y0=P(nx0,ny0); x1,y1=P(nx1,ny1)
    d.rectangle([x0,y0,x1,y1], outline=(0,0,0,180), width=int(9*S))
    d.rectangle([x0,y0,x1,y1], outline=AMBER, width=int(4*S))
    label((x0+x1)/2, y0-8*S, name, anchor="mb", size=22, fill=AMBER)

def strongpoint(nx,ny,nr,color=FRIENDLINE):
    cx,cy=P(nx,ny); r=nr*W
    d.ellipse([cx-r-2*S,cy-r-2*S,cx+r+2*S,cy+r+2*S], outline=(0,0,0,190), width=int(8*S))
    teeth=20
    for i in range(teeth):
        a=i/teeth*2*math.pi
        x1,y1=cx+r*math.cos(a),cy+r*math.sin(a)
        x2,y2=cx+(r+14*S)*math.cos(a+0.13),cy+(r+14*S)*math.sin(a+0.13)
        x3,y3=cx+r*math.cos(a+0.26),cy+r*math.sin(a+0.26)
        d.line([(x1,y1),(x2,y2),(x3,y3)], fill=color, width=int(4*S))
    d.ellipse([cx-r,cy-r,cx+r,cy+r], outline=color, width=int(4*S))

# ============ THE SITUATION (division attack, enemy north) ============
# Boxed objectives in enemy depth (clear of the header + compass/lock chips)
objective(0.15,0.235,0.37,0.335,"OBJ FALCON")
objective(0.50,0.225,0.72,0.330,"OBJ HILL 223")
# Boundaries (friendly sectors) up to the FEBA
boundary([(0.36,0.90),(0.355,0.71),(0.36,0.575)], "XX")
boundary([(0.66,0.90),(0.665,0.71),(0.66,0.565)], "XX")
# Enemy forward line of resistance (red battlements, teeth toward friendly/south)
crenellate([P(0.06,0.44),P(0.30,0.475),P(0.53,0.44),P(0.76,0.475),P(0.94,0.445)],
           RED, 6*S, period=46*S, height=22*S, side=1)
# Friendly FEBA (blue battlements, teeth toward enemy/north)
crenellate([P(0.05,0.565),P(0.30,0.535),P(0.52,0.585),P(0.74,0.535),P(0.95,0.565)],
           FRIENDLINE, 6*S, period=46*S, height=22*S, side=-1)
# Axes of advance (red), friendly -> objectives
arrow([P(0.265,0.62),P(0.27,0.49),P(0.265,0.345)], RED, 6*S)
arrow([P(0.62,0.62),P(0.625,0.49),P(0.62,0.34)], RED, 6*S)
# Friendly fortified locality (strongpoint), west flank
strongpoint(0.135,0.75,0.05)
# Enemy formations (on the objectives + forward of the line)
unit(0.255,0.292,"hostile","X",name="EN")
unit(0.615,0.282,"hostile","XX",name="EN")
unit(0.45,0.40,"hostile","X",name="EN")
# Friendly formations (south of the FEBA)
unit(0.20,0.68,"friend","X",name="1 BDE")
unit(0.50,0.655,"friend","X",name="2 BDE")
unit(0.81,0.68,"friend","X",name="3 BDE")
unit(0.35,0.775,"friend","II",fn="armour",name="C SQN")
unit(0.66,0.78,"friend","II",name="2 RAR")
unit(0.50,0.86,"friend","XX",hq=True,name="DIV HQ")

# ---- Unit Sync indicator in the header (blue chip on the Live-Location row) ----
def sync_chip(cy):
    cx=W*0.5; blue=(79,168,255,255); ix=cx-72*S
    d.ellipse([ix-3*S,cy-3*S,ix+3*S,cy+3*S], fill=blue)
    for r in (9*S,16*S):
        d.arc([ix-r,cy-r,ix+r,cy+r], -55,55, fill=blue, width=int(3*S))
        d.arc([ix-r,cy-r,ix+r,cy+r], 125,235, fill=blue, width=int(3*S))
    d.text((ix+24*S,cy),"Unit Sync", font=font("bold",int(27*S)), fill=blue, anchor="lm")
sync_chip(SYNC_Y*H)

ov=ov.filter(ImageFilter.GaussianBlur(0.4*S))
Image.alpha_composite(im.convert("RGBA"),ov).convert("RGB").save(out_path,"PNG")
print("wrote",out_path,(W,H))
