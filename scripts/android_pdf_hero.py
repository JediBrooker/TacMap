#!/usr/bin/env python3
"""Capture the GeoPDF-import hero: imported US Topo (GeoPDF) basemap with NATO
situation overlaid (black symbology on topo, like the satellite hero).

Device location is set to situation/PDF centre so "Centre on My Location"
frames it deterministically. The US Topo collar offsets the PDF's auto-fly
so we don't rely on that. GeoPDF must already be at /sdcard/Download/<pdf>,
and the situation pushed (re-centred) to <room>.

    python3 scripts/android_pdf_hero.py <apk> <out_dir> <room> <pdf_basename> <lon> <lat>
"""
import re, subprocess, sys, time, xml.etree.ElementTree as ET
APK, OUT, ROOM, PDF, LON, LAT = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5], sys.argv[6]
PKG, ACT = "com.tacmap", "com.tacmap/.app.MainActivity"

def adb(*a, **k): return subprocess.run(["adb", *a], capture_output=True, **k)
def sh(c): return adb("shell", *c.split()).stdout.decode("utf-8", "replace")
def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    x = adb("shell", "cat", "/sdcard/ui.xml").stdout.decode("utf-8", "replace"); x = x[x.find("<?xml"):]
    try: return list(ET.fromstring(x).iter("node"))
    except ET.ParseError: return []
def ctr(b): m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b); return (int(m.group(1))+int(m.group(3)))//2, (int(m.group(2))+int(m.group(4)))//2
def tap(x, y, p=2.0): adb("shell", "input", "tap", str(x), str(y)); time.sleep(p)
def tapt(t, p=2.0):
    for n in nodes():
        if t.lower() in (n.get("text", "")+n.get("content-desc", "")).lower() and n.get("bounds"):
            x, y = ctr(n.get("bounds")); tap(x, y, p); print("  tapped", t); return True
    print("  MISS", t); return False
def ham():
    best = None
    for n in nodes():
        if n.get("clickable") == "true" and n.get("bounds"):
            x, y = ctr(n.get("bounds"))
            if y < 700 and x < 400 and (best is None or x+y < best[2]): best = (x, y, x+y)
    if best: tap(best[0], best[1]); return True
def snap(n):
    png = adb("exec-out", "screencap", "-p").stdout
    open(f"{OUT}/{n}.png", "wb").write(png); print("  snap", n, len(png)//1024, "KB")

print("install…"); adb("uninstall", PKG); print(adb("install", "-g", APK).stdout.decode()[-60:])
for p in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS"): adb("shell", "pm", "grant", PKG, f"android.permission.{p}")
adb("shell", "settings", "put", "secure", "location_mode", "3")
adb("emu", "geo", "fix", LON, LAT)
adb("shell", "am", "start", "-n", ACT); time.sleep(8)
adb("emu", "geo", "fix", LON, LAT); time.sleep(2)
_m = re.search(r'(\d+)x(\d+)', sh('wm size')); W, H = (int(_m.group(1)), int(_m.group(2))) if _m else (1080, 2400)
print(f"screen {W}x{H}")

# load the GeoPDF basemap
ham(); (tapt("Import / Export") or tapt("Import")); tapt("PDF Map", 3); tapt(PDF, 8); time.sleep(5)
# join the (re-centred) situation room from a fresh dialog
ham(); tapt("Unit Sync"); time.sleep(1)
if any("Room code" in n.get("text", "") for n in nodes()):
    tapt("Leave room", 2); adb("shell", "input", "keyevent", "4"); time.sleep(1); ham(); tapt("Unit Sync"); time.sleep(1)
ef = None
for n in nodes():
    if n.get("class", "").endswith("EditText") and n.get("bounds"): ef = ctr(n.get("bounds")); break
if ef:
    tap(*ef, p=1.0); adb("shell", "input", "text", ROOM); time.sleep(1.5)
    print("  field:", [n.get("text") for n in nodes() if n.get("class", "").endswith("EditText")])
    adb("shell", "input", "keyevent", "111"); time.sleep(1.2); tapt("create room", 10)
adb("shell", "input", "keyevent", "4"); time.sleep(2)
# frame: centre on device location (= PDF/situation centre)
tapt("Centre on My Location", 3); time.sleep(3)
# labels on
ham(); tapt("Layers and Labels")
for lbl in ["Unit Labels", "Task Labels", "Drawing Labels"]:
    ly = None
    for n in nodes():
        if n.get("text", "").strip() == lbl and n.get("bounds"): ly = ctr(n.get("bounds"))[1]; break
    if ly:
        for n in nodes():
            if n.get("checkable") == "true" and n.get("bounds"):
                x, y = ctr(n.get("bounds"))
                if abs(y-ly) < 60 and n.get("checked") == "false": tap(x, y, 1.0); break
adb("shell", "input", "keyevent", "4"); time.sleep(2)
snap("pdf-hero")
print("done")
