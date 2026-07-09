#!/usr/bin/env python3
"""
Capture raw Android marketing screenshots from a booted emulator.

Text-driven via uiautomator dump (basically the Android version of XCUITest):
hamburger is found as top-left clickable node, menu rows and sheet controls
by their visible text. Best-effort - missing control just logs and skips.

    python3 scripts/android_screenshots.py <apk> <out_dir>
"""
import re, subprocess, sys, time, xml.etree.ElementTree as ET

APK, OUT = sys.argv[1], sys.argv[2]
PKG = "com.tacmap"
ACT = "com.tacmap/.app.MainActivity"

def adb(*a, **k):
    return subprocess.run(["adb", *a], capture_output=True, **k)

def sh(cmd):
    return adb("shell", *cmd.split()).stdout.decode("utf-8", "replace")

def wait_boot(timeout=180):
    t = time.time()
    while time.time() - t < timeout:
        if adb("shell", "getprop", "sys.boot_completed").stdout.strip() == b"1":
            return True
        time.sleep(2)
    return False

def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml").stdout.decode("utf-8", "replace")
    xml = xml[xml.find("<?xml"):]
    try:
        return ET.fromstring(xml)
    except ET.ParseError:
        return None

def center(bounds):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2

def tap(x, y, pause=2.0):
    adb("shell", "input", "tap", str(x), str(y)); time.sleep(pause)

def tap_text(text, pause=2.0, contains=True):
    root = dump()
    if root is None:
        print(f"  [!] no ui dump for '{text}'"); return False
    for n in root.iter("node"):
        t = n.get("text", ""); d = n.get("content-desc", "")
        hit = (text.lower() in t.lower()) if contains else (t == text)
        hit = hit or (text.lower() in d.lower())
        if hit and n.get("bounds"):
            x, y = center(n.get("bounds")); tap(x, y, pause)
            print(f"  tapped '{text}' @ {x},{y}"); return True
    print(f"  [!] not found: '{text}'"); return False

def tap_hamburger(pause=2.0):
    """Top-left clickable node = menu button (no content-desc on it)."""
    root = dump()
    best = None
    for n in root.iter("node"):
        if n.get("clickable") == "true" and n.get("bounds"):
            x, y = center(n.get("bounds"))
            if y < 700 and x < 400:                  # top-left region
                if best is None or (y + x) < best[2]:
                    best = (x, y, y + x)
    if best:
        tap(best[0], best[1], pause); print(f"  tapped menu @ {best[0]},{best[1]}"); return True
    print("  [!] hamburger not found"); return False

def snap(name):
    png = adb("exec-out", "screencap", "-p").stdout
    with open(f"{OUT}/{name}.png", "wb") as f:
        f.write(png)
    print(f"  snap {name} ({len(png)//1024}KB)")

def back(n=1):
    for _ in range(n):
        adb("shell", "input", "keyevent", "4"); time.sleep(1.2)

# ---- run ----
print("waiting for boot…"); wait_boot()
time.sleep(4)
adb("uninstall", PKG)  # nuke any prior debug install, release cert is different
print("installing apk…"); print(adb("install", "-g", APK).stdout.decode()[-200:])
adb("shell", "pm", "grant", PKG, "android.permission.ACCESS_FINE_LOCATION")
adb("shell", "pm", "grant", PKG, "android.permission.ACCESS_COARSE_LOCATION")
try: adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
except Exception: pass
# location: Blue Mountains (lon lat order for geo fix)
adb("emu", "geo", "fix", "150.305", "-33.700")
print("launching…"); adb("shell", "am", "start", "-n", ACT); time.sleep(10)
adb("emu", "geo", "fix", "150.305", "-33.700"); time.sleep(2)

# dismiss any permission dialog that slipped through
for lbl in ["While using the app", "Allow", "OK"]:
    if tap_text(lbl, pause=1.5): break

print(f"size={sh('wm size').strip()} density={sh('wm density').strip()}")

def on_map():
    root = dump()
    if root is None:
        return False
    return any("centre on my location" in n.get("text", "").lower() for n in root.iter("node"))

def ensure_map(tries=5):
    for _ in range(tries):
        if on_map():
            return True
        back(1)
    adb("shell", "am", "start", "-n", ACT); time.sleep(3)
    return on_map()

# 1) Layers sheet - capture it, then switch basemap to Esri so the map
#    actually renders. Google base map is blank on locally-signed builds
#    b/c the Maps key is whitelisted to the Play signing cert. Esri tile
#    overlay draws regardless and showcases that basemap anyway.
ensure_map()
if tap_hamburger() and tap_text("Layers and Labels"):
    snap("a04-layers")
    tap_text("Satellite (Esri)", pause=3.0)
ensure_map()
time.sleep(7)   # let Esri tiles stream in

# 2) HUD over the Esri basemap
snap("a01-hud")

# 3) the remaining sheets
for label, name in [("Weather", "a03-weather"),
                    ("Import / Export", "a05-import-export"),
                    ("Search", "a06-search")]:
    print(f"-> {label}")
    ensure_map()
    if tap_hamburger() and tap_text(label):
        snap(name)
    ensure_map()

# 4) Unit Sync last - its dialog + keyboard are the messiest to dismiss
print("-> Unit Sync")
ensure_map()
if tap_hamburger() and tap_text("Unit Sync"):
    root = dump(); field = None
    for n in (root.iter("node") if root is not None else []):
        if n.get("class", "").endswith("EditText") and n.get("bounds"):
            field = center(n.get("bounds")); break
    if field:
        tap(*field, pause=1.0)
        adb("shell", "input", "text", "WOLFPACK-6"); time.sleep(1)
        adb("shell", "input", "keyevent", "111"); time.sleep(1)  # kill the keyboard
        tap_text("Join", pause=5.0)
    snap("a02-unit-sync")
ensure_map()
print("done")
