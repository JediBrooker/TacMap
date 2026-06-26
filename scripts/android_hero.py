#!/usr/bin/env python3
"""
Capture a REAL Android hero screenshot: the NATO tactical situation (pushed to a
Unit Sync room by scripts/sync_push_situation.mjs) rendered by the live app over
the Esri Satellite basemap.

The Google base layer is blank on locally-signed builds (Maps key whitelisted to
the Play signing cert), but the Esri basemap is a TileOverlay that needs no key,
and the markers/polylines/polygons render on top of it regardless — so this is a
genuine capture, no compositing.

    python3 scripts/android_hero.py <apk> <out_dir> <room_code>
"""
import re, subprocess, sys, time, xml.etree.ElementTree as ET

APK, OUT = sys.argv[1], sys.argv[2]
ROOM = sys.argv[3] if len(sys.argv) > 3 else "ANVIL-7"
PKG = "com.tacmap"
ACT = "com.tacmap/.app.MainActivity"
W, H = 1080, 2400
CX, CY = 540, 1180   # map centre (crosshair / device location)

def adb(*a, **k): return subprocess.run(["adb", *a], capture_output=True, **k)
def sh(cmd): return adb("shell", *cmd.split()).stdout.decode("utf-8", "replace")

def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml").stdout.decode("utf-8", "replace")
    xml = xml[xml.find("<?xml"):]
    try: return ET.fromstring(xml)
    except ET.ParseError: return None

def center(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    x1, y1, x2, y2 = map(int, m.groups()); return (x1 + x2) // 2, (y1 + y2) // 2

def tap(x, y, pause=2.0):
    adb("shell", "input", "tap", str(x), str(y)); time.sleep(pause)

def tap_text(text, pause=2.0):
    root = dump()
    if root is None: print(f"  [!] no ui dump for '{text}'"); return False
    for n in root.iter("node"):
        t = n.get("text", ""); d = n.get("content-desc", "")
        if (text.lower() in t.lower()) or (text.lower() in d.lower()):
            if n.get("bounds"):
                x, y = center(n.get("bounds")); tap(x, y, pause)
                print(f"  tapped '{text}' @ {x},{y}"); return True
    print(f"  [!] not found: '{text}'"); return False

def tap_hamburger(pause=2.0):
    root = dump(); best = None
    for n in root.iter("node"):
        if n.get("clickable") == "true" and n.get("bounds"):
            x, y = center(n.get("bounds"))
            if y < 700 and x < 400 and (best is None or (y + x) < best[2]):
                best = (x, y, y + x)
    if best: tap(best[0], best[1], pause); print(f"  menu @ {best[0]},{best[1]}"); return True
    print("  [!] hamburger not found"); return False

def snap(name):
    png = adb("exec-out", "screencap", "-p").stdout
    with open(f"{OUT}/{name}.png", "wb") as f: f.write(png)
    print(f"  snap {name} ({len(png)//1024}KB)")

def back(n=1):
    for _ in range(n): adb("shell", "input", "keyevent", "4"); time.sleep(1.2)

def on_map():
    root = dump()
    return root is not None and any(
        "centre on my location" in n.get("text", "").lower() for n in root.iter("node"))

def ensure_map(tries=5):
    for _ in range(tries):
        if on_map(): return True
        back(1)
    adb("shell", "am", "start", "-n", ACT); time.sleep(3); return on_map()

def double_tap(x, y):
    adb("shell", "input", "tap", str(x), str(y))
    adb("shell", "input", "tap", str(x), str(y))   # back-to-back = zoom in one level
    time.sleep(3.5)

def enable_label_switches():
    """In the Layers sheet, turn ON Unit/Task/Drawing label switches.
    Each ToggleRow is [Text | Switch] SpaceBetween; the Switch is a checkable
    node at the right edge on the same row — tap it only if not already on."""
    for label in ["Unit Labels", "Task Labels", "Drawing Labels"]:
        root = dump()
        if root is None: continue
        ly = None
        for n in root.iter("node"):
            if n.get("text", "").strip() == label and n.get("bounds"):
                ly = center(n.get("bounds"))[1]; break
        if ly is None: print(f"  [!] label row not found: {label}"); continue
        # find the nearest checkable node on that row
        best = None
        for n in root.iter("node"):
            if n.get("checkable") == "true" and n.get("bounds"):
                x, y = center(n.get("bounds"))
                if abs(y - ly) < 60:
                    if best is None or abs(y - ly) < best[3]:
                        best = (x, y, n.get("checked") == "true", abs(y - ly))
        if best is None:
            print(f"  [!] no switch for {label}, tapping right edge")
            tap(W - 90, ly, 1.2); continue
        x, y, checked, _ = best
        if checked: print(f"  {label} already on")
        else: tap(x, y, 1.2); print(f"  {label} -> on @ {x},{y}")

# ---- run ----
print("install…")
adb("uninstall", PKG)
print(adb("install", "-g", APK).stdout.decode()[-120:])
for p in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS"):
    adb("shell", "pm", "grant", PKG, f"android.permission.{p}")
adb("emu", "geo", "fix", "150.305", "-33.700")
adb("shell", "am", "start", "-n", ACT); time.sleep(10)
adb("emu", "geo", "fix", "150.305", "-33.700"); time.sleep(2)
for lbl in ["While using the app", "Allow", "OK"]:
    if tap_text(lbl, pause=1.5): break
print(f"size={sh('wm size').strip()}")
# adapt to the actual screen (phone vs tablet) — derive map centre + edge x
_m = re.search(r'(\d+)x(\d+)', sh('wm size'))
if _m:
    W = int(_m.group(1)); H = int(_m.group(2))
    CX, CY = W // 2, int(H * 0.49)
    print(f"detected {W}x{H} centre={CX},{CY}")

# 1) keep the default Google Satellite basemap (the release cert is now
#    whitelisted on the Maps key, so it authenticates + renders) and turn
#    the unit/task/drawing labels on while the Layers sheet is open
ensure_map()
if tap_hamburger() and tap_text("Layers and Labels"):
    enable_label_switches()
back(1); time.sleep(1)           # close the sheet
ensure_map(); time.sleep(10)     # let Google satellite tiles stream in

# 3) join the Unit Sync room (renders the pushed situation; indicator -> Connected)
if tap_hamburger() and tap_text("Unit Sync"):
    root = dump(); field = None
    for n in (root.iter("node") if root is not None else []):
        if n.get("class", "").endswith("EditText") and n.get("bounds"):
            field = center(n.get("bounds")); break
    if field:
        tap(*field, pause=1.0)
        adb("shell", "input", "text", ROOM); time.sleep(1)
        adb("shell", "input", "keyevent", "111"); time.sleep(1)   # hide keyboard
        tap_text("create room", pause=8.0)   # the "Join / create room" button
                                              # (NOT "Unit join code", the field label)
    back(1); time.sleep(1)        # close the dialog
ensure_map(); time.sleep(4)

# 4) recentre on device location, then capture at three zoom levels to pick framing
tap_text("Centre on My Location", pause=3.0)
time.sleep(3)
snap("a01-hero-z0")
double_tap(CX, CY); snap("a01-hero-z1")
double_tap(CX, CY); snap("a01-hero-z2")
print("done")
