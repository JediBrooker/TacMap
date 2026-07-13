#!/usr/bin/env python3
"""Compose the canonical 10-slide branded store set (shared text/order across
iOS + Android) for one device profile. 9 single-device slides via
store_screenshots, basemaps slide as a 3-device fan via fan_slide.

Slide text/eyebrow/accent is identical for every device so the four sets
(iPhone, iPad, Android phone, Android tablet) stay consistent. Only the
raw screenshots and output canvas size differ.

    python3 scripts/compose_store_set.py <profile.json>

profile = {
  "W":1080,"H":2100,
  "raw_dir":"/abs/raws",              # hero.png symbols.png unit-sync.png recording.png
                                      # weather.png import-export.png symbol-builder.png search.png
  "pdf_src":"/abs/pdf-hero.png",      # imported-GeoPDF capture (staged in as pdf-hero.png)
  "basemaps_dir":"/abs/basemaps",     # bm-esri.png bm-terrain.png bm-satellite.png
  "out_dir":"/abs/out",               # writes 01-hero.png ... 10-pdfmap.png
  "fan":{"angle":13,"device_w":0.40,"offx":0.225}
}
"""
import json, os, sys, shutil
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import store_screenshots as ss
import fan_slide

# (number-name, type, src, eyebrow, headline, subcaption, accent)
SPEC = [
 ("01-hero",          "single","hero.png",          "01 · TACTICAL PICTURE","Own the tactical picture","NATO APP-6 symbology over live MGRS and UTM — shared across the unit, end-to-end encrypted.","green"),
 ("02-unit-sync",     "single","unit-sync.png",     "02 · UNIT SYNC","One map, the whole unit","Symbols, drawings and waypoints sync live across iOS and Android — end-to-end encrypted.","blue"),
 ("03-symbols",       "single","symbols.png",       "03 · SYMBOLOGY","Mark the ground","Place NATO APP-6 symbols, then trace routes and areas point by point.","green"),
 ("04-recording",     "single","recording.png",     "04 · GPX TRACKS","Record your route","A live REC breadcrumb with point count — export as standard GPX.","amber"),
 ("05-weather",       "single","weather.png",       "05 · WEATHER + UAV","Go, caution, no-go","Map-centre wind and gusts drive the drone flight-safety call.","amber"),
 ("06-basemaps",      "fan",   None,                 "06 · BASEMAPS & TERRAIN","Satellite or terrain","Esri satellite imagery, Esri topographic terrain, or an OpenStreetMap street map — switch basemap in a tap.","green"),
 ("07-import-export", "single","import-export.png", "07 · IMPORT & EXPORT","In and out, anywhere","Import GeoPDF, KML and KMZ; export GeoJSON and GPX.","blue"),
 ("08-measure",       "single","measure.png",       "08 · MEASURE","Range, area, bearing","Tap out a path or a loop for live distance and area, with each leg's bearing in NATO mils.","blue"),
 ("09-search",        "single","search.png",        "09 · SEARCH","Jump to any grid","Search a place name, or a full / partial MGRS reference.","green"),
 ("10-pdfmap",        "single","pdf-hero.png",      "10 · OFFLINE MAPS","Bring your own map","Import a GeoPDF or defence map sheet — georeferenced on-device and aligned to your live MGRS grid.","amber"),
]

def main():
    p = json.load(open(sys.argv[1]))
    W, H = p["W"], p["H"]
    raw, out = p["raw_dir"], p["out_dir"]
    os.makedirs(out, exist_ok=True)
    if p.get("pdf_src"):
        shutil.copy(p["pdf_src"], os.path.join(raw, "pdf-hero.png"))
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
