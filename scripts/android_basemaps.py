#!/usr/bin/env python3
"""Capture the three basemaps (Satellite / Esri / OpenTopoMap terrain) from the
real app for the "Satellite or terrain" fan slide.

    python3 scripts/android_basemaps.py <apk> <out_dir>
"""
import re, subprocess, sys, time, xml.etree.ElementTree as ET
APK, OUT = sys.argv[1], sys.argv[2]
PKG, ACT = "com.tacmap", "com.tacmap/.app.MainActivity"

def adb(*a, **k): return subprocess.run(["adb", *a], capture_output=True, **k)
def sh(c): return adb("shell", *c.split()).stdout.decode("utf-8", "replace")
def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    x = adb("shell", "cat", "/sdcard/ui.xml").stdout.decode("utf-8", "replace"); x = x[x.find("<?xml"):]
    try: return ET.fromstring(x)
    except ET.ParseError: return None
def ctr(b): m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b); return (int(m.group(1))+int(m.group(3)))//2, (int(m.group(2))+int(m.group(4)))//2
def find(t):
    r = dump()
    if r is None: return None
    for n in r.iter("node"):
        if t.lower() in (n.get("text","")+n.get("content-desc","")).lower() and n.get("bounds"): return ctr(n.get("bounds"))
    return None
def tap(x, y, p=2.0): adb("shell", "input", "tap", str(x), str(y)); time.sleep(p)
def tapt(t, p=2.0):
    loc = find(t)
    if loc: tap(*loc, p); print("  tapped", t); return True
    print("  MISS", t); return False
def ham():
    r = dump(); best = None
    for n in r.iter("node"):
        if n.get("clickable") == "true" and n.get("bounds"):
            x, y = ctr(n.get("bounds"))
            if y < 700 and x < 400 and (best is None or x+y < best[2]): best = (x, y, x+y)
    if best: tap(best[0], best[1]); return True
    return False
def snap(n):
    png = adb("exec-out", "screencap", "-p").stdout
    open(f"{OUT}/{n}.png", "wb").write(png); print("  snap", n, len(png)//1024, "KB")
def back(n=1):
    for _ in range(n): adb("shell", "input", "keyevent", "4"); time.sleep(1.2)
def on_map():
    r = dump(); return r is not None and any("centre on my location" in x.get("text","").lower() for x in r.iter("node"))
SCREEN_W, SCREEN_H = 1080, 2400   # overwritten from `wm size` at runtime
def select_basemap(label):
    ham(); tapt("Layers and Labels"); time.sleep(1)
    # scroll the sheet up so the basemap rows are reachable
    for _ in range(4):
        adb("shell", "input", "swipe", str(SCREEN_W//2), str(int(SCREEN_H*0.8)),
            str(SCREEN_W//2), str(int(SCREEN_H*0.28)), "300"); time.sleep(1)
    tapt(label, p=2.5)
    back(1); time.sleep(1)

print("install…"); adb("uninstall", PKG); print(adb("install", "-g", APK).stdout.decode()[-80:])
for p in ("ACCESS_FINE_LOCATION","ACCESS_COARSE_LOCATION","POST_NOTIFICATIONS"): adb("shell","pm","grant",PKG,f"android.permission.{p}")
adb("shell","settings","put","secure","location_mode","3")
adb("emu","geo","fix","150.305","-33.700")
adb("shell","am","start","-n",ACT); time.sleep(9)
adb("emu","geo","fix","150.305","-33.700"); time.sleep(2)
print(f"size={sh('wm size').strip()}")
_m = re.search(r'(\d+)x(\d+)', sh('wm size'))
if _m:
    SCREEN_W, SCREEN_H = int(_m.group(1)), int(_m.group(2))
    print(f"detected {SCREEN_W}x{SCREEN_H}")
CX, CY = SCREEN_W // 2, int(SCREEN_H * 0.49)
tapt("Centre on My Location", p=3); time.sleep(2)
# zoom in one notch so terrain detail reads
adb("shell","input","tap",str(CX),str(CY)); adb("shell","input","tap",str(CX),str(CY)); time.sleep(3)

# 1) Satellite (default)
print("Satellite…"); time.sleep(8); snap("bm-satellite")
# 2) Esri
print("Esri…"); select_basemap("Satellite (Esri)"); time.sleep(10); snap("bm-esri")
# 3) OpenTopoMap terrain — long load + pan to trigger tiles
print("Terrain (OpenTopoMap)…"); select_basemap("Terrain (OpenTopoMap)")
for _ in range(5):
    time.sleep(10)
    adb("shell","input","swipe","540","1100","560","1180","300"); time.sleep(1)
    adb("shell","input","swipe","560","1180","540","1100","300")
tapt("Centre on My Location", p=3); time.sleep(6)
snap("bm-terrain")
print("done")
