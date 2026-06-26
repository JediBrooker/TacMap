#!/usr/bin/env python3
"""Compose a "fan" marketing slide — three device screenshots fanned in one
tile on the branded background. Used for the "Satellite or terrain" slide so
all three basemaps (Satellite / Esri / Terrain) show at once.

Reuses scripts/store_screenshots.py for the brand background, fonts and bezel.

    python3 scripts/fan_slide.py <config.json>

config = {
  "W":1080,"H":2100,"src_dir":"/abs/raw","out_path":"/abs/out.png",
  "eyebrow":"05 · BASEMAPS & TERRAIN","headline":"Satellite or terrain",
  "subcaption":"...","accent":"green","angle":14,
  "devices":[ {"src":"bm-esri.png","label":"Esri"},        # LEFT  (behind)
              {"src":"bm-terrain.png","label":"Terrain"},  # CENTRE(front)
              {"src":"bm-satellite.png","label":"Satellite"} ]  # RIGHT (behind)
}
"""
import json, os, sys
from PIL import Image, ImageDraw, ImageFilter
import store_screenshots as ss

def framed(shot, inner_w):
    a = shot.size[1] / shot.size[0]
    inner_h = int(inner_w * a)
    bezel = max(10, int(inner_w * 0.035))
    fw, fh = inner_w + 2 * bezel, inner_h + 2 * bezel
    dev = Image.new("RGBA", (fw, fh), (0, 0, 0, 0))
    dd = ImageDraw.Draw(dev)
    r = int(inner_w * 0.12)
    dd.rounded_rectangle([0, 0, fw, fh], radius=r, fill=ss.BEZEL)
    dd.rounded_rectangle([0, 0, fw, fh], radius=r, outline=(255, 255, 255, 30), width=2)
    sr = ss.rounded(shot.resize((inner_w, inner_h), Image.LANCZOS), int(inner_w * 0.09))
    dev.paste(sr, (bezel, bezel), sr)
    return dev

def label_pill(text, accent, size):
    f = ss.font("mono", size)
    tw = ImageDraw.Draw(Image.new("RGB", (8, 8))).textlength(text.upper(), font=f)
    padx, pady = int(size * 0.7), int(size * 0.42)
    w, h = int(tw + 2 * padx), int(size + 2 * pady)
    pill = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    pd = ImageDraw.Draw(pill)
    pd.rounded_rectangle([0, 0, w, h], radius=h // 2, fill=(6, 8, 6, 235))
    pd.rounded_rectangle([0, 0, w, h], radius=h // 2, outline=(*accent, 220), width=2)
    pd.text((padx, pady - int(size * 0.05)), text.upper(), font=f, fill=(*accent, 255))
    return pill

def render_fan(cfg):
    W, H = cfg["W"], cfg["H"]
    S = min(W, H)   # size text/margins off the short side so landscape works too
    accent = ss.ACCENTS.get(cfg.get("accent", "green"), ss.GREEN)
    img = ss.background(W, H, accent).convert("RGBA")
    d = ImageDraw.Draw(img, "RGBA")
    margin = int(S * 0.075)

    # header (matches store_screenshots)
    wm = ss.font("xbold", int(S * 0.044)); y = int(H * 0.05)
    d.text((margin, y), "Tac", font=wm, fill=ss.INK); tw = d.textlength("Tac", font=wm)
    d.text((margin + tw, y), "Map", font=wm, fill=ss.GREEN); y += int(S * 0.044 * 1.5)
    eb = ss.font("mono", int(S * 0.0205)); ry = y + int(S * 0.012)
    d.line([(margin, ry), (margin + int(S * 0.05), ry)], fill=accent, width=2)
    d.text((margin + int(S * 0.066), y), cfg.get("eyebrow", "").upper(), font=eb, fill=accent)
    y += int(S * 0.052)
    hl = ss.font("xbold", int(S * 0.076))
    for line in ss.wrap(d, cfg["headline"], hl, W - 2 * margin):
        d.text((margin, y), line, font=hl, fill=ss.INK); y += int(S * 0.076 * 1.08)
    y += int(S * 0.018)
    sub = ss.font("medium", int(S * 0.0335))
    for line in ss.wrap(d, cfg.get("subcaption", ""), sub, int((W - 2 * margin) * 0.96)):
        d.text((margin, y), line, font=sub, fill=ss.INK2); y += int(S * 0.0335 * 1.34)
    text_bottom = y

    left, centre, right = cfg["devices"]
    inner_w = int(W * cfg.get("device_w", 0.40))
    fL = framed(Image.open(os.path.join(cfg["src_dir"], left["src"])).convert("RGB"), inner_w)
    fC = framed(Image.open(os.path.join(cfg["src_dir"], centre["src"])).convert("RGB"), inner_w)
    fR = framed(Image.open(os.path.join(cfg["src_dir"], right["src"])).convert("RGB"), inner_w)

    ang = cfg.get("angle", 14)
    offx = int(W * cfg.get("offx", 0.225))
    area_top = text_bottom + int(H * 0.015)
    area_bot = H - int(H * 0.05)
    cy = (area_top + area_bot) // 2
    cxL, cxR, cxC = W // 2 - offx, W // 2 + offx, W // 2
    dy = int(H * 0.025)   # side phones sit a touch lower
    seq = [(fL, -ang, cxL, cy + dy, left),
           (fR,  ang, cxR, cy + dy, right),
           (fC,   0,  cxC, cy,      centre)]   # centre last = on top

    for dev, a, px, py, meta in seq:
        rot = dev.rotate(a, expand=True, resample=Image.BICUBIC)
        sx, sy = px - rot.size[0] // 2, py - rot.size[1] // 2
        shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
        shadow.paste((0, 0, 0, 150), (sx, sy + int(H * 0.006)), rot.split()[-1])
        shadow = shadow.filter(ImageFilter.GaussianBlur(int(W * 0.018)))
        img = Image.alpha_composite(img, shadow)
        img.paste(rot, (sx, sy), rot)

    # labels — pinned along the bottom band, left/centre/right
    psize = int(S * 0.030)
    ly = area_bot - int(H * 0.006)
    for px, meta in [(cxL, left), (cxR, right), (cxC, centre)]:
        pill = label_pill(meta["label"], accent, psize)
        img.alpha_composite(pill, (px - pill.size[0] // 2, ly - pill.size[1]))

    ImageDraw.Draw(img, "RGBA").rectangle([0, H - 6, W, H], fill=accent)
    img.convert("RGB").save(cfg["out_path"], "PNG")
    print("wrote", cfg["out_path"])

if __name__ == "__main__":
    render_fan(json.load(open(sys.argv[1])))
