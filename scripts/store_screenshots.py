#!/usr/bin/env python3
"""
Compose stylised, store-ready marketing screenshots from raw app captures.

Each slide = on-brand tactical background (gradient + faint MGRS grid + green
glow + corner reticle) -> a wordmark + accent eyebrow + bold headline +
subcaption -> the raw screenshot inside a rounded device bezel, bottom-anchored.

Output dimensions are set per store target, so the raw capture resolution does
not matter (it is scaled to fit the bezel). Brand fonts live in scripts/fonts/.

    python3 scripts/store_screenshots.py <config.json>

config.json = {
  "W": 1320, "H": 2868,            # output canvas (store-spec)
  "out_dir": "/abs/out",
  "src_dir": "/abs/raw",
  "slides": [
    {"src": "m01-hud.png", "eyebrow": "01 · NAVIGATION",
     "headline": "Your grid, live.", "subcaption": "MGRS + UTM ...",
     "accent": "green"}            # green | amber | blue
  ]
}
"""
from __future__ import annotations
import json, math, os, sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
FONTS = os.path.join(HERE, "fonts")

# --- brand palette (from site.css) ---
BG_TOP   = (11, 14, 11)
BG_BOT   = (7, 9, 7)
INK      = (233, 240, 232)
INK2     = (182, 192, 179)
MUTED    = (124, 136, 122)
GREEN    = (116, 227, 138)
AMBER    = (244, 161, 42)
BLUE     = (79, 168, 255)
GRID     = (124, 227, 138)
BEZEL    = (8, 9, 8)

ACCENTS = {"green": GREEN, "amber": AMBER, "blue": BLUE}

def font(name, size):
    paths = {
        "xbold":  "Archivo-ExtraBold.ttf",
        "bold":   "Archivo-Bold.ttf",
        "semi":   "Archivo-SemiBold.ttf",
        "medium": "Archivo-Medium.ttf",
        "mono":   "JetBrainsMono-Bold.ttf",
        "monomed":"JetBrainsMono-Medium.ttf",
    }
    return ImageFont.truetype(os.path.join(FONTS, paths[name]), size)

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def background(W, H, accent):
    img = Image.new("RGB", (W, H), BG_TOP)
    px = img.load()
    # vertical gradient
    for y in range(H):
        c = lerp(BG_TOP, BG_BOT, y / H)
        for x in range(W):
            px[x, y] = c
    # green radial glow, top-centre
    glow = Image.radial_gradient("L").resize((int(W * 1.5), int(W * 1.5)))
    gl = Image.new("RGB", glow.size, accent)
    galpha = glow.point(lambda v: int((255 - v) * 0.18))  # bright centre, fade out
    img.paste(gl, (int(W * 0.5 - glow.size[0] / 2), int(H * 0.20 - glow.size[1] / 2)),
              galpha)
    # faint tactical grid (top-weighted)
    grid = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    gd = ImageDraw.Draw(grid)
    step = max(40, int(W / 14))
    for x in range(0, W, step):
        gd.line([(x, 0), (x, H)], fill=(*GRID, 16), width=1)
    for y in range(0, H, step):
        gd.line([(0, y), (W, y)], fill=(*GRID, 16), width=1)
    # fade the grid out toward the bottom
    fade = Image.new("L", (W, H), 0)
    fd = fade.load()
    for y in range(H):
        a = max(0, 1 - y / (H * 0.62))
        v = int(255 * a)
        for x in range(0, W, 4):
            fd[x, y] = v
    grid.putalpha(Image.composite(grid.getchannel("A"), Image.new("L", (W, H), 0), fade))
    img = Image.alpha_composite(img.convert("RGBA"), grid).convert("RGB")
    # corner reticle, top-right
    d = ImageDraw.Draw(img, "RGBA")
    cx, cy, r = int(W * 0.88), int(H * 0.075), int(W * 0.05)
    d.arc([cx - r, cy - r, cx + r, cy + r], 0, 360, fill=(*AMBER, 90), width=2)
    d.line([(cx, cy - r - 10), (cx, cy - r + 14)], fill=(*AMBER, 90), width=2)
    d.line([(cx, cy + r - 14), (cx, cy + r + 10)], fill=(*AMBER, 90), width=2)
    d.line([(cx - r - 10, cy), (cx - r + 14, cy)], fill=(*AMBER, 90), width=2)
    d.line([(cx + r - 14, cy), (cx + r + 10, cy)], fill=(*AMBER, 90), width=2)
    return img

def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.size[0], img.size[1]],
                                           radius=radius, fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out

def wrap(draw, text, fnt, max_w):
    words, lines, cur = text.split(), [], ""
    for w in words:
        t = (cur + " " + w).strip()
        if draw.textlength(t, font=fnt) <= max_w:
            cur = t
        else:
            if cur:
                lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines

def render(cfg_slide, W, H, src_dir, out_path):
    accent = ACCENTS.get(cfg_slide.get("accent", "green"), GREEN)
    img = background(W, H, accent)
    d = ImageDraw.Draw(img, "RGBA")
    margin = int(W * 0.075)

    # --- header: TacMap wordmark ---
    wm = font("xbold", int(W * 0.044))
    y = int(H * 0.05)
    d.text((margin, y), "Tac", font=wm, fill=INK)
    tac_w = d.textlength("Tac", font=wm)
    d.text((margin + tac_w, y), "Map", font=wm, fill=GREEN)
    y += int(W * 0.044 * 1.5)

    # --- accent eyebrow (mono) with leading rule ---
    eb = font("mono", int(W * 0.0205))
    rule_y = y + int(W * 0.012)
    d.line([(margin, rule_y), (margin + int(W * 0.05), rule_y)], fill=accent, width=2)
    d.text((margin + int(W * 0.066), y), cfg_slide.get("eyebrow", "").upper(),
           font=eb, fill=accent)
    y += int(W * 0.052)

    # --- headline ---
    hl = font("xbold", int(W * 0.076))
    for line in wrap(d, cfg_slide["headline"], hl, W - 2 * margin):
        d.text((margin, y), line, font=hl, fill=INK)
        y += int(W * 0.076 * 1.08)
    y += int(W * 0.018)

    # --- subcaption ---
    sub = font("medium", int(W * 0.0335))
    for line in wrap(d, cfg_slide.get("subcaption", ""), sub, int((W - 2 * margin) * 0.96)):
        d.text((margin, y), line, font=sub, fill=INK2)
        y += int(W * 0.0335 * 1.34)

    text_bottom = y

    # --- device frame (bottom-anchored) ---
    shot = Image.open(os.path.join(src_dir, cfg_slide["src"])).convert("RGB")
    a = shot.size[1] / shot.size[0]            # height / width
    bottom_margin = int(H * 0.045)
    bezel = max(14, int(W * 0.013))
    phone_top_min = max(int(H * 0.30), text_bottom + int(H * 0.03))
    avail_h = (H - bottom_margin) - phone_top_min
    inner_w = min(int(W * 0.74), int((avail_h - 2 * bezel) / a))
    inner_h = int(inner_w * a)
    fw, fh = inner_w + 2 * bezel, inner_h + 2 * bezel
    fx = (W - fw) // 2
    fy = (H - bottom_margin) - fh

    # soft drop shadow
    sh = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle([fx, fy + int(H * 0.012), fx + fw, fy + fh + int(H * 0.012)],
                                         radius=int(inner_w * 0.09), fill=(0, 0, 0, 150))
    sh = sh.filter(ImageFilter.GaussianBlur(int(W * 0.03)))
    img = Image.alpha_composite(img.convert("RGBA"), sh).convert("RGB")
    d = ImageDraw.Draw(img, "RGBA")

    # bezel
    out_r = int(inner_w * 0.10)
    d.rounded_rectangle([fx, fy, fx + fw, fy + fh], radius=out_r, fill=BEZEL)
    d.rounded_rectangle([fx, fy, fx + fw, fy + fh], radius=out_r,
                        outline=(255, 255, 255, 28), width=2)
    # screenshot
    shot_r = rounded(shot.resize((inner_w, inner_h), Image.LANCZOS), int(inner_w * 0.075))
    img.paste(shot_r, (fx + bezel, fy + bezel), shot_r)
    # thin accent edge along the bottom of the canvas
    d.rectangle([0, H - 6, W, H], fill=accent)

    img.save(out_path, "PNG")
    return out_path

def main():
    cfg = json.load(open(sys.argv[1]))
    W, H = cfg["W"], cfg["H"]
    os.makedirs(cfg["out_dir"], exist_ok=True)
    for i, s in enumerate(cfg["slides"], 1):
        out = os.path.join(cfg["out_dir"], f"{i:02d}-{os.path.splitext(s['src'])[0]}.png")
        render(s, W, H, cfg["src_dir"], out)
        print("wrote", out)

if __name__ == "__main__":
    main()
