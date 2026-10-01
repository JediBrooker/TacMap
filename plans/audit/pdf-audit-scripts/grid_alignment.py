#!/usr/bin/env python3
"""Measure MGRS-overlay vs printed-grid misalignment in a device screenshot.

For screenshots of the synthetic sheets from gen_grid_pdfs.py: printed grid is pure
red #FF0000; TacMap's MGRS overlay is neutral dark grey drawn at alpha 0.85
(iOS MGRSGridRenderer.inkColor 0.18 grey a0.85, Android INK_COLOR 0xD9303030),
so where it overlaps the red line the pixel turns dark red. Both classes are
located with an angle-searched projection profile (robust to map rotation, labels
and occlusion); scale comes from the printed 1000 m spacing so no zoom hook is
needed. Offsets are reported at the top/middle/bottom (or left/middle/right) of
the frame so a rotation mismatch shows up as a growing offset.

  python3 grid_alignment.py shot.png [--max-metres 2] [--crop x0,y0,x1,y1] [--heading DEG]
  python3 grid_alignment.py --selftest
"""
import argparse, sys
import numpy as np
from PIL import Image, ImageDraw

def classify(img):
    r, g, b = (img[:, :, i].astype(float) for i in range(3))
    red = (r - np.maximum(g, b) > 90) & (r > 150)
    grey = (np.abs(r - g) < 20) & (np.abs(g - b) < 20) & (r > 25) & (r < 120)
    overlap = (r - np.maximum(g, b) > 18) & (r - np.maximum(g, b) <= 90) & (r < 150)   # grey a0.85 over red
    return red | overlap, grey | overlap

def rotate_mask(mask, deg):
    if abs(deg) < 1e-9: return mask
    im = Image.fromarray((mask * 255).astype(np.uint8))
    return np.asarray(im.rotate(deg, resample=Image.BILINEAR, expand=False)) > 96

def profile_peaks(mask, axis, min_frac=0.25):
    prof = mask.sum(axis=0 if axis == "x" else 1).astype(float)
    if prof.max() <= 0: return [], prof
    thr = prof.max() * min_frac
    peaks, i, n = [], 0, len(prof)
    while i < n:
        if prof[i] > thr:
            j = i
            while j + 1 < n and prof[j + 1] > thr: j += 1
            lo, hi = max(0, i - 2), min(n, j + 3)
            w = prof[lo:hi]; peaks.append(float((np.arange(lo, hi) * w).sum() / w.sum())); i = j + 1
        else: i += 1
    return peaks, prof

def best_angle(mask, axis, span=4.0, step=0.1):
    best = (-1, 0.0)
    for deg in np.arange(-span, span + 1e-9, step):
        prof = rotate_mask(mask, deg).sum(axis=0 if axis == "x" else 1).astype(float)
        score = (prof ** 2).sum()
        if score > best[0]: best = (score, float(deg))
    return best[1]

def measure(img, heading=0.0, bands=4):
    printed, overlay = classify(img)
    if heading: printed, overlay = rotate_mask(printed, heading), rotate_mask(overlay, heading)
    out = {}
    for axis, name in (("x", "easting lines"), ("y", "northing lines")):
        ap = best_angle(printed, axis); ao = best_angle(overlay, axis)
        rp, ro = rotate_mask(printed, ap), rotate_mask(overlay, ao)
        pk_all, _ = profile_peaks(rp, axis)
        spacing = float(np.median(np.diff(pk_all))) if len(pk_all) >= 2 else float("nan")
        pairs, per_band = [], []
        L = rp.shape[0] if axis == "x" else rp.shape[1]
        for b in range(bands):                      # offsets per stretch of line => catches bending/rotation
            sl = slice(b * L // bands, (b + 1) * L // bands)
            pp, _ = profile_peaks(rp[sl, :] if axis == "x" else rp[:, sl], axis)
            po, _ = profile_peaks(ro[sl, :] if axis == "x" else ro[:, sl], axis)
            band = []
            for o in po:
                if not pp: break
                j = int(np.argmin([abs(o - p) for p in pp])); d = o - pp[j]
                if abs(d) < 0.4 * spacing: band.append(d)
            pairs += band; per_band.append(float(np.median(band)) if band else float("nan"))
        tilt = abs(np.tan(np.radians(ao - ap))) * L / (2 * bands)
        out[name] = dict(printed=len(pk_all), paired=len(pairs), spacing_px=spacing,
                         angle_printed=ap, angle_overlay=ao, band_median_px=[round(v, 2) for v in per_band],
                         median_px=float(np.median(pairs)) if pairs else float("nan"),
                         max_px=float(np.max(np.abs(pairs)) + tilt) if pairs else float("nan"))
    spacing = np.nanmedian([v["spacing_px"] for v in out.values()])
    mpp = 1000.0 / spacing
    worst = max(v["max_px"] for v in out.values()) * mpp
    return out, mpp, worst

def draw_overlay_line(im, pts, rgb=(48, 48, 48), alpha=0.85, width=2):
    lay = Image.new("L", im.size, 0); ImageDraw.Draw(lay).line(pts, fill=255, width=width)
    a = (np.asarray(lay).astype(float) / 255.0 * alpha)[:, :, None]
    base = np.asarray(im).astype(float)
    return Image.fromarray((base * (1 - a) + np.array(rgb, float) * a).astype(np.uint8))

def selftest():
    W = H = 900
    im = Image.new("RGB", (W, H), "white"); d = ImageDraw.Draw(im)
    for k in range(50, W, 100):
        d.line([(k, 0), (k, H)], fill=(255, 0, 0), width=3); d.line([(0, k), (W, k)], fill=(255, 0, 0), width=3)
    for k in range(50, W, 100):
        im = draw_overlay_line(im, [(k + 3, 0), (k + 3, H)]); im = draw_overlay_line(im, [(0, k - 2), (W, k - 2)])
    rep, mpp, worst = measure(np.asarray(im))
    print("selftest:", {k: (round(v["median_px"], 2), v["paired"]) for k, v in rep.items()}, "m/px %.2f worst %.1f m" % (mpp, worst))
    assert abs(rep["easting lines"]["median_px"] - 3) < 0.7 and abs(rep["northing lines"]["median_px"] + 2) < 0.7
    assert abs(mpp - 10) < 0.3
    print("selftest OK")

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("png", nargs="?"); ap.add_argument("--max-metres", type=float, default=2.0)
    ap.add_argument("--crop"); ap.add_argument("--heading", type=float, default=0.0)
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest: selftest(); sys.exit(0)
    img = np.asarray(Image.open(a.png).convert("RGB"))
    if a.crop:
        x0, y0, x1, y1 = map(int, a.crop.split(",")); img = img[y0:y1, x0:x1]
    rep, mpp, worst = measure(img, a.heading)
    for k, v in rep.items(): print("%-15s %s" % (k, {kk: (round(vv, 3) if isinstance(vv, float) else vv) for kk, vv in v.items()}))
    print("scale %.3f m/px ; worst offset %.2f m ; %s" % (mpp, worst, "PASS" if worst <= a.max_metres else "FAIL"))
    sys.exit(0 if worst <= a.max_metres else 1)
