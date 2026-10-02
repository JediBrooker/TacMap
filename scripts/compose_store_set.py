#!/usr/bin/env python3
"""Compose the canonical 8-slide branded store set (shared text/order across
iOS + Android) for one device profile. Every slide is a single device shot;
fan_slide is still wired up for a "fan" entry if one comes back.

Slide text/eyebrow/accent is identical for every device so the four sets
(iPhone, iPad, Android phone, Android tablet) stay consistent. Only the
raw screenshots and output canvas size differ. Play caps each device type at
8 screenshots and wants 9:16 for phones AND tablets, hence 8 slides and the
1080x1920 / 1440x2560 Android canvases.

    python3 scripts/compose_store_set.py <profile.json>

profile = {
  "W":1080,"H":1920,
  "raw_dir":"/abs/raws",              # hero.png line-of-sight.png night-mode.png unit-sync.png
                                      # sun-moon.png pdf-hero.png symbol-builder.png export.png
  "out_dir":"/abs/out"                # writes 01-hero.png ... 08-export.png
}
"""
import json, os, sys, shutil
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import store_screenshots as ss
import fan_slide

# (number-name, type, src, eyebrow, headline, subcaption, accent)
SPEC = [
 ("01-hero",           "single","hero.png",          "01 · TACTICAL PICTURE","Own the tactical picture","NATO APP-6 units, tasks and range rings over a live MGRS grid.","green"),
 ("02-line-of-sight",  "single","line-of-sight.png", "02 · ELEVATION & LINE OF SIGHT","See the ground first","Profile any line and check what an observer can see, dead ground marked. Heights need online lookups.","amber"),
 ("03-night-mode",     "single","night-mode.png",    "03 · NIGHT MODE","Keep your night vision","One tap turns every screen red and dim, menus and dialogs included.","amber"),
 ("04-unit-sync",      "single","unit-sync.png",     "04 · UNIT SYNC & CHAT","One map, the whole unit","Mission payloads and TacMap Chat stay end-to-end encrypted; the relay sees routing, session and traffic metadata.","blue"),
 ("05-sun-moon",       "single","sun-moon.png",      "05 · SUN & MOON","First light to last light","BMNT, EENT, sunrise, moonrise and illumination for any point, worked out offline.","green"),
 ("06-pdfmap",         "single","pdf-hero.png",      "06 · OFFLINE MAPS","Bring your own map","Import a GeoPDF or scanned map sheet and mark it up, no signal needed.","amber"),
 ("07-symbol-builder", "single","symbol-builder.png","07 · SYMBOLOGY","Build any unit","NATO APP-6 units, tasks and markers, plus your own custom symbol packs.","blue"),
 ("08-export",         "single","export.png",        "08 · IMPORT & EXPORT","Your data, anywhere","Export KML, KMZ with symbol images, GeoJSON and GPX for Google Earth, ATAK and GIS.","green"),
]

def main():
    p = json.load(open(sys.argv[1]))
    W, H = p["W"], p["H"]
    raw, out = p["raw_dir"], p["out_dir"]
    os.makedirs(out, exist_ok=True)
    fan = p.get("fan", {})
    for name, kind, src, eb, hl, sub, ac in SPEC:
        op = os.path.join(out, name + ".png")
        if kind == "single":
            ss.render({"src": src, "eyebrow": eb, "headline": hl, "subcaption": sub, "accent": ac},
                      W, H, raw, op)
        else:
            fan_slide.render_fan({
                "W": W, "H": H, "src_dir": p["basemaps_dir"], "out_path": op,
                "eyebrow": eb, "headline": hl, "subcaption": sub, "accent": ac,
                "angle": fan.get("angle", 13), "device_w": fan.get("device_w", 0.40),
                "offx": fan.get("offx", 0.225),
                "devices": [{"src": "bm-esri.png", "label": "Topographic"},
                            {"src": "bm-satellite.png", "label": "Satellite"},
                            {"src": "bm-street.png", "label": "Street"}]})
        print("wrote", op)

if __name__ == "__main__":
    main()
