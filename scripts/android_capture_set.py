#!/usr/bin/env python3
"""Capture the full single-device marketing set from a booted Android emulator
in ONE session (release APK so Google Maps renders). Resolution-aware, so it
runs unchanged on the phone (tm_phone, portrait) and the tablet (tm_tablet,
rotated to portrait).

Captures, against the live NATO situation joined from <room>:
    hero          wide tactical picture on Google Satellite
    symbols       zoomed-in APP-6 symbology ("Mark the ground")
    unit-sync     the Unit Sync sheet, Connected
    recording     the live REC breadcrumb indicator
    weather       Weather & UAV safety sheet
    import-export Import / Export sheet
    symbol-builder the APP-6 editor (affiliation / echelon / function + preview)
    search        Search sheet

The basemaps fan (scripts/android_basemaps.py) and the GeoPDF hero
(scripts/android_pdf_hero.py) are captured separately.

    python3 scripts/android_capture_set.py <apk> <out_dir> <room>
"""
import re, subprocess, sys, time, xml.etree.ElementTree as ET

APK, OUT, ROOM = sys.argv[1], sys.argv[2], sys.argv[3]
PKG, ACT = "com.tacmap", "com.tacmap/.app.MainActivity"
LON, LAT = "150.305", "-33.700"          # Blue Mountains = the situation centre

def adb(*a, **k): return subprocess.run(["adb", *a], capture_output=True, **k)
def sh(c): return adb("shell", *c.split()).stdout.decode("utf-8", "replace")
def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    x = adb("shell", "cat", "/sdcard/ui.xml").stdout.decode("utf-8", "replace"); x = x[x.find("<?xml"):]
    try: return list(ET.fromstring(x).iter("node"))
    except ET.ParseError: return []
def ctr(b): m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b); return (int(m.group(1))+int(m.group(3)))//2, (int(m.group(2))+int(m.group(4)))//2
def tap(x, y, p=2.0): adb("shell", "input", "tap", str(x), str(y)); time.sleep(p)
def tapt(t, p=2.0, exact=False):
    for n in nodes():
        lab = (n.get("text", "") + n.get("content-desc", ""))
        hit = (t == n.get("text", "")) if exact else (t.lower() in lab.lower())
        if hit and n.get("bounds"):
            x, y = ctr(n.get("bounds")); tap(x, y, p); print("  tapped", repr(t)); return True
    print("  MISS", repr(t)); return False
def ham(p=2.0):
    best = None
    for n in nodes():
        if n.get("clickable") == "true" and n.get("bounds"):
            x, y = ctr(n.get("bounds"))
            if y < 700 and x < 400 and (best is None or x+y < best[2]): best = (x, y, x+y)
    if best: tap(best[0], best[1], p); return True
    print("  hamburger MISS"); return False
def back(n=1):
    for _ in range(n): adb("shell", "input", "keyevent", "4"); time.sleep(1.2)
def on_map():
    return any("centre on my location" in n.get("text", "").lower() for n in nodes())
def ensure_map(tries=5):
    for _ in range(tries):
        if on_map(): return True
        back(1)
    adb("shell", "am", "start", "-n", ACT); time.sleep(3); return on_map()
def snap(n):
    png = adb("exec-out", "screencap", "-p").stdout
    open(f"{OUT}/{n}.png", "wb").write(png); print("  snap", n, len(png)//1024, "KB")

# ---- install + launch (release APK) ----
print("install…"); adb("uninstall", PKG); print(adb("install", "-g", APK).stdout.decode()[-60:])
for p in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS"):
    adb("shell", "pm", "grant", PKG, f"android.permission.{p}")
adb("shell", "settings", "put", "secure", "location_mode", "3")
adb("emu", "geo", "fix", LON, LAT)
adb("shell", "am", "start", "-n", ACT); time.sleep(9)
adb("emu", "geo", "fix", LON, LAT); time.sleep(2)
for lbl in ["While using", "Allow", "OK"]:
    if tapt(lbl, 1.2): break
import struct
def screen_dims():
    """Actual rendered W×H from a screencap (wm size reports the physical panel,
    which is wrong when a landscape-native tablet is rotated to portrait)."""
    png = adb("exec-out", "screencap", "-p").stdout
    return struct.unpack(">I", png[16:20])[0], struct.unpack(">I", png[20:24])[0]
W, H = screen_dims()
CX, CY = W//2, H//2
print(f"screen {W}x{H}")

# ---- basemap: Google Satellite (release APK renders Google Maps) ----
ensure_map(); ham(); tapt("Layers and Labels")
tapt("Satellite", 3, exact=True)      # the plain "Satellite" row = Google
back(1); time.sleep(4)

# ---- join the NATO situation room ----
ensure_map(); ham(); tapt("Unit Sync"); time.sleep(1)
if any("Room code" in n.get("text", "") or "Leave" in n.get("text", "") for n in nodes()):
    tapt("Leave", 2); back(1); time.sleep(1); ham(); tapt("Unit Sync"); time.sleep(1)
ef = None
for n in nodes():
    if n.get("class", "").endswith("EditText") and n.get("bounds"): ef = ctr(n.get("bounds")); break
if ef:
    tap(*ef, p=1.0); adb("shell", "input", "text", ROOM); time.sleep(1.2)
    adb("shell", "input", "keyevent", "4"); time.sleep(1.0)        # BACK dismisses the soft keyboard
    btn = None                                                     # tap "Join / create room" by node
    for n in nodes():
        if "join / create" in n.get("text", "").lower() and n.get("bounds"): btn = ctr(n.get("bounds")); break
    if btn: tap(*btn, p=2.0)
    for _ in range(8):                                             # wait for CONNECTED
        if any(n.get("text", "") == "Connected" for n in nodes()): print("  sync CONNECTED"); break
        time.sleep(2)
back(1); time.sleep(2)

# ---- labels on ----
ensure_map(); ham(); tapt("Layers and Labels")
for lbl in ["Unit Labels", "Task Labels", "Drawing Labels"]:
    ly = None
    for n in nodes():
        if n.get("text", "").strip() == lbl and n.get("bounds"): ly = ctr(n.get("bounds"))[1]; break
    if ly:
        for n in nodes():
            if n.get("checkable") == "true" and n.get("bounds"):
                x, y = ctr(n.get("bounds"))
                if abs(y-ly) < 60 and n.get("checked") == "false": tap(x, y, 0.8); break
back(1); time.sleep(2)

# ---- frame + hero ----
ensure_map(); tapt("Centre on My Location", 3); time.sleep(3)
snap("hero")

# ---- symbols: zoom in one level (double-tap centre) ----
adb("shell", "input", "tap", str(CX), str(CY)); time.sleep(0.12)
adb("shell", "input", "tap", str(CX), str(CY)); time.sleep(3)
snap("symbols")
# zoom back out so later sheets sit over the wide picture
adb("shell", "input", "swipe", str(CX), str(CY-40), str(CX), str(CY+40), "120"); time.sleep(1)

# ---- unit-sync sheet (Connected) ----
ensure_map(); ham(); tapt("Unit Sync"); time.sleep(2); snap("unit-sync"); back(1); time.sleep(1)

# ---- recording (REC indicator) ----
ensure_map(); ham(); tapt("Start Track Recording", 3); time.sleep(2); snap("recording")
ham(); tapt("Stop Track Recording", 2); time.sleep(1)            # stop it again

# ---- weather ----
ensure_map(); ham(); tapt("Weather", 4); time.sleep(2); snap("weather"); back(1); time.sleep(1)

# ---- import / export ----
ensure_map(); ham(); tapt("Import / Export", 3); time.sleep(1); snap("import-export"); back(1); time.sleep(1)

# ---- symbol builder (APP-6 editor) ----
ensure_map(); ham(); tapt("Symbology", 3)
tapt("Add Military Unit", 3); time.sleep(1); snap("symbol-builder")
tapt("Cancel", 2); back(2); time.sleep(1)

# ---- search ----
ensure_map(); ham(); tapt("Search", 3); time.sleep(1); snap("search"); back(1)
print("DONE android_capture_set")
