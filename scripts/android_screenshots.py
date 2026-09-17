#!/usr/bin/env python3
"""
Capture raw Android marketing screenshots from a booted emulator.

Drives the Compose UI through the accessibility tree (`uiautomator dump`),
tapping the hamburger + menu rows + sheet controls by their visible text /
contentDescription. Compose exports text and contentDescription to the a11y
tree, so this works without any testTag plumbing in the app.

    python3 scripts/android_screenshots.py <apk|-> <out_dir>

Pass a real .apk path to reinstall first (preserving existing data), or "-" to just drive
the app already on the device.

The catch: the app defaults `block_screen_capture=true` (OPSEC), which sets
FLAG_SECURE on the window. FLAG_SECURE blanks BOTH `screencap` (solid black)
AND `uiautomator dump` (null root node) - so every capture and every bit of
navigation dies. On a fresh install the pref is back to its blocking default,
which is exactly the state the old script kept tripping over.

Fix: before capturing we flip `block_screen_capture` off in the app's private
prefs via `run-as` (only works on a *debuggable* build - use the debug APK),
force-stop, relaunch. We verify the window really is capturable (screencap not
mostly-black) and restore the original preference files on the way out.
Set TACMAP_SCREENSHOT_LANGUAGE=en or de. Only isolated emulators are accepted.

Raw filenames match what scripts/compose_store_set.py consumes as `raw_dir`
(hero.png, symbols.png, ...) plus a basemaps/ fan (bm-*.png). The GeoPDF
"pdfmap" raw needs a staged map sheet and stays with scripts/android_pdf_hero.py.
"""
import re, struct, subprocess, sys, time, os, json
from pathlib import Path
import xml.etree.ElementTree as ET

APK = sys.argv[1] if len(sys.argv) > 1 else "-"
OUT = sys.argv[2] if len(sys.argv) > 2 else "."
LANGUAGE = os.environ.get("TACMAP_SCREENSHOT_LANGUAGE", "en")
if LANGUAGE not in ("en", "de"):
    raise SystemExit("TACMAP_SCREENSHOT_LANGUAGE must be en or de")
CATALOG = json.loads((Path(__file__).resolve().parents[1] / "localization/catalog.json").read_text())
COPY = {entry["en"]: entry[LANGUAGE] for entry in CATALOG.values() if entry.get("legacy", True)}
PKG = "com.tacmap"
ACT = "com.tacmap/.app.MainActivity"
LON, LAT = "150.305", "-33.700"          # Blue Mountains, situation centre

os.makedirs(OUT, exist_ok=True)
os.makedirs(os.path.join(OUT, "basemaps"), exist_ok=True)

# Pillow is used elsewhere in the pipeline; if it's missing we degrade the
# blank-guard to a size heuristic rather than hard-fail.
try:
    from PIL import Image
    import io
    _HAVE_PIL = True
except Exception:
    _HAVE_PIL = False


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


# ---- accessibility-tree driving ----------------------------------------

def nodes():
    """Parse the current uiautomator dump into a flat node list. Empty list
    if the dump failed (e.g. FLAG_SECURE still on, or a transient race)."""
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    raw = adb("shell", "cat", "/sdcard/ui.xml").stdout.decode("utf-8", "replace")
    raw = raw[raw.find("<?xml"):]
    if not raw:
        return []
    try:
        return list(ET.fromstring(raw).iter("node"))
    except ET.ParseError:
        return []

def center(bounds):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2

def tap(x, y, pause=2.0):
    adb("shell", "input", "tap", str(int(x)), str(int(y)))
    time.sleep(pause)

def find(text, exact=False):
    """Center of the first node whose text or contentDescription matches."""
    text = COPY.get(text, text)
    for n in nodes():
        lab = (n.get("text", "") or "") + "\x00" + (n.get("content-desc", "") or "")
        hit = (text == n.get("text", "")) if exact else (text.lower() in lab.lower())
        if hit and n.get("bounds"):
            return center(n.get("bounds"))
    return None

def tap_text(text, pause=2.0, exact=False):
    loc = find(text, exact)
    if loc:
        tap(loc[0], loc[1], pause)
        print(f"  tapped '{text}' @ {loc[0]},{loc[1]}")
        return True
    print(f"  [!] not found: '{text}'")
    return False

def tap_hamburger(pause=2.0):
    """Open the menu. The button carries contentDescription 'Menu'; fall back
    to a proportional top-left tap if the dump is momentarily empty."""
    loc = find("Menu")
    if loc:
        tap(loc[0], loc[1], pause)
        print(f"  tapped menu @ {loc[0]},{loc[1]}")
        return True
    # proportional fallback: HUD menu button sits ~8% x / ~20% y
    x, y = int(SCREEN_W * 0.084), int(SCREEN_H * 0.205)
    tap(x, y, pause)
    print(f"  tapped menu (fallback) @ {x},{y}")
    return True

def back(n=1):
    for _ in range(n):
        adb("shell", "input", "keyevent", "4")
        time.sleep(1.2)

def on_map():
    labels = ("centre on my location", "center on my location", "auf meinen standort zentrieren", "meinen standort")
    return any(any(label in ((n.get("text", "") or "") + " " + (n.get("content-desc", "") or "")).lower()
                   for label in labels) for n in nodes())


def ensure_map(tries=5):
    for _ in range(tries):
        if on_map():
            return True
        back(1)
    adb("shell", "am", "start", "-n", ACT)
    time.sleep(3)
    return on_map()


# ---- capture + blank guard ---------------------------------------------

def grab_png():
    return adb("exec-out", "screencap", "-p").stdout

def png_dims(png):
    """WxH straight from the IHDR - no decode, no Pillow."""
    return struct.unpack(">I", png[16:20])[0], struct.unpack(">I", png[20:24])[0]

def black_fraction(png):
    """Fraction of near-black pixels, or None if we can't tell. A FLAG_SECURE
    window screencaps as ~solid black, so this is how we detect it slipped
    back on."""
    if not _HAVE_PIL:
        # crude fallback: an all-black PNG compresses tiny. ~60KB for a 1080p
        # secure frame here vs 200KB+ for real content.
        return 0.99 if len(png) < 90_000 else 0.0
    try:
        im = Image.open(io.BytesIO(png)).convert("RGB")
        im = im.resize((80, 160))
        px = list(im.getdata())
        blk = sum(1 for r, g, b in px if r < 12 and g < 12 and b < 12)
        return blk / len(px)
    except Exception:
        return None

def snap(name, subdir=None):
    png = grab_png()
    path = os.path.join(OUT, subdir, f"{name}.png") if subdir else os.path.join(OUT, f"{name}.png")
    with open(path, "wb") as f:
        f.write(png)
    bf = black_fraction(png)
    tag = ""
    if bf is not None and bf > 0.9:
        tag = "  <<< WARNING mostly-black, FLAG_SECURE may be on"
    print(f"  snap {name} ({len(png)//1024}KB, black={bf if bf is None else round(bf,2)}){tag}")
    return bf


# ---- FLAG_SECURE / block_screen_capture --------------------------------

def read_preferences(name):
    result = adb("shell", "run-as", PKG, "cat", f"shared_prefs/{name}.xml")
    return result.stdout if result.returncode == 0 else None


def write_preferences(name, content):
    adb("shell", "am", "force-stop", PKG, check=True)
    if content is None:
        adb("shell", "run-as", PKG, "rm", "-f", f"shared_prefs/{name}.xml", check=True)
        return
    tmp = f"/data/local/tmp/tacmap-capture-{name}.xml"
    adb("shell", "cat", ">", tmp, input=content, check=True)
    adb("shell", "run-as", PKG, "mkdir", "-p", "shared_prefs", check=True)
    adb("shell", "run-as", PKG, "cp", tmp, f"shared_prefs/{name}.xml", check=True)
    adb("shell", "rm", "-f", tmp, check=True)


def edited_preferences(original, changes):
    tree = ET.fromstring(original) if original else ET.Element("map")
    for name, (kind, value) in changes.items():
        for existing in list(tree):
            if existing.get("name") == name:
                tree.remove(existing)
        entry = ET.SubElement(tree, kind, name=name)
        if kind == "string": entry.text = value
        else: entry.set("value", value)
    return ET.tostring(tree, encoding="utf-8", xml_declaration=True)


def relaunch():
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", ACT)
    time.sleep(6)

def disable_secure():
    """Temporarily configure the isolated screenshot emulator; preserve all prefs."""
    originals = {name: read_preferences(name) for name in ("opsec", "app_language", "localization_qa")}
    try:
        write_preferences("opsec", edited_preferences(originals["opsec"], {
            "block_screen_capture": ("boolean", "false"),
            "online_basemaps": ("boolean", "true"),
            "online_lookups": ("boolean", "true"),
        }))
        write_preferences("app_language", edited_preferences(originals["app_language"], {
            "display_language": ("string", LANGUAGE),
        }))
        write_preferences("localization_qa", edited_preferences(originals["localization_qa"], {
            "expanded_text": ("boolean", "true" if os.environ.get("TACMAP_SCREENSHOT_EXPANDED") == "1" else "false"),
        }))
        relaunch()
        # A cold-start splash is also mostly black; allow the map to render.
        deadline = time.monotonic() + 120
        bf = black_fraction(grab_png())
        while bf is not None and bf > 0.9 and time.monotonic() < deadline:
            time.sleep(3)
            bf = black_fraction(grab_png())
        if bf is not None and bf > 0.9:
            Path(OUT, "capture-failure.png").write_bytes(grab_png())
            raise RuntimeError("Screenshot capture remains blocked")
        return originals
    except BaseException:
        restore_secure(originals)
        raise


def restore_secure(originals):
    for name, content in originals.items():
        write_preferences(name, content)
    relaunch()


# ============================ run =======================================

if adb("shell", "getprop", "ro.kernel.qemu").stdout.strip() != b"1":
    raise SystemExit("Use an isolated Android emulator for screenshot seeding")
print("waiting for boot…"); wait_boot(); time.sleep(3)

if APK not in ("-", "", None) and os.path.exists(APK):
    print("installing apk (preserving existing app data)…")
    print(adb("install", "-r", "-g", APK).stdout.decode()[-120:])
else:
    print("no apk given - driving the installed app")

for perm in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS"):
    adb("shell", "pm", "grant", PKG, f"android.permission.{perm}")
adb("shell", "settings", "put", "secure", "location_mode", "3")
adb("emu", "geo", "fix", LON, LAT)
adb("shell", "am", "start", "-n", ACT); time.sleep(8)
adb("emu", "geo", "fix", LON, LAT); time.sleep(2)
for lbl in ["While using", "Allow", "OK"]:
    if tap_text(lbl, 1.5):
        break

# real rendered size (screencap header, robust to tablet portrait rotation)
_p = grab_png()
SCREEN_W, SCREEN_H = png_dims(_p)
CX, CY = SCREEN_W // 2, SCREEN_H // 2
print(f"screen {SCREEN_W}x{SCREEN_H}  (wm size={sh('wm size').strip()})")

# --- THE FIX: clear FLAG_SECURE so screencap + dump work at all ---
print("disabling block_screen_capture (FLAG_SECURE)…")
original_block = disable_secure()

try:
    if os.environ.get("TACMAP_SCREENSHOT_REVIEW") == "1":
        ensure_map()
        snap("00-map")
        destinations = ["Search", "Symbology", "Drawings", "Layers and Labels", "Weather & UAV Safety", "Import / Export", "TacMap Chat", "Unit Sync", "App Lock", "Settings, Privacy & OPSEC", "About & Credits"]
        for index, destination in enumerate(destinations, 1):
            relaunch()
            ensure_map()
            tap_hamburger()
            for attempt in range(4):
                if find(destination): break
                adb("shell", "input", "swipe", str(CX), str(int(SCREEN_H * .8)), str(CX), str(int(SCREEN_H * .3)), "300")
                time.sleep(.5)
            if not tap_text(destination, 2):
                print("MISSING SCREEN:", destination)
                continue
            name = f"{index:02d}-" + re.sub(r"[^a-z0-9]+", "-", destination.lower()).strip("-")
            snap(name)
            Path(OUT, name + ".xml").write_text(ET.tostring(ET.Element("empty"), encoding="unicode") if not nodes() else "\n".join(ET.tostring(n, encoding="unicode") for n in nodes()))
            if destination in ("Settings, Privacy & OPSEC", "Import / Export", "Drawings"):
                adb("shell", "input", "swipe", str(CX), str(int(SCREEN_H * .8)), str(CX), str(int(SCREEN_H * .35)), "400")
                time.sleep(1)
                snap(name + "-scrolled")
    else:
        # 1) basemap -> Esri Satellite so the hero sits over real imagery, then HUD
        ensure_map()
        if tap_hamburger() and tap_text("Layers and Labels"):
            if not tap_text("Satellite (Esri)", 3.0):
                # sheet may need scrolling to reach basemap rows
                adb("shell", "input", "swipe", str(CX), str(int(SCREEN_H*0.8)),
                    str(CX), str(int(SCREEN_H*0.3)), "300"); time.sleep(1)
                tap_text("Satellite (Esri)", 3.0)
            back(1)
        ensure_map(); time.sleep(7)               # let tiles stream in
        snap("hero")

        # 2) symbols - zoom in a level so the symbology reads big
        ensure_map()
        adb("shell", "input", "tap", str(CX), str(CY)); time.sleep(0.12)
        adb("shell", "input", "tap", str(CX), str(CY)); time.sleep(3)
        snap("symbols")
        adb("shell", "input", "swipe", str(CX), str(CY-40), str(CX), str(CY+40), "150"); time.sleep(1)

        # 3) unit sync dialog
        ensure_map()
        if tap_hamburger() and tap_text("Unit Sync"):
            time.sleep(1)
            snap("unit-sync")
        ensure_map()

        # 4) recording - REC breadcrumb badge
        ensure_map()
        if tap_hamburger() and tap_text("Start Track Recording", 3):
            time.sleep(2); snap("recording")
            if tap_hamburger():
                tap_text("Trackaufzeichnung stoppen" if LANGUAGE == "de" else "Stop Track Recording", 2)   # matches "Stop Track Recording (N pts)"
        ensure_map()

        # 5) weather & UAV safety
        ensure_map()
        if tap_hamburger() and tap_text("Weather & UAV Safety", 4):
            time.sleep(2); snap("weather")
        ensure_map()

        # 6) import / export
        ensure_map()
        if tap_hamburger() and tap_text("Import / Export", 3):
            time.sleep(1); snap("import-export")
        ensure_map()

        # 7) symbol builder - APP-6 editor (New Military Unit)
        ensure_map()
        if tap_hamburger() and tap_text("Symbology", 3):
            if tap_text("Military Unit", 3):          # "Add Military Unit" row
                time.sleep(1); snap("symbol-builder")
                tap_text("Close symbol editor", 2)
        ensure_map()

        # 8) search
        ensure_map()
        if tap_hamburger() and tap_text("Search", 3):
            time.sleep(1); snap("search")
        ensure_map()

        # 9) basemap fan (bm-satellite / bm-terrain / bm-esri) for the fan slide
        def set_basemap_and_snap(label, out):
            ensure_map()
            if tap_hamburger() and tap_text("Layers and Labels"):
                if not tap_text(label, 3.0):
                    adb("shell", "input", "swipe", str(CX), str(int(SCREEN_H*0.8)),
                        str(CX), str(int(SCREEN_H*0.3)), "300"); time.sleep(1)
                    tap_text(label, 3.0)
                back(1)
            ensure_map(); time.sleep(6)
            snap(out, subdir="basemaps")

        set_basemap_and_snap("Satellite (Esri)", "bm-satellite")
        set_basemap_and_snap("Topographic (OpenTopoMap)", "bm-terrain")
        set_basemap_and_snap("Topographic (Esri)", "bm-esri")

finally:
    # always put OPSEC back the way we found it
    print("restoring OPSEC pref…")
    restore_secure(original_block)

print("done. pdfmap raw (pdf-hero.png) is captured by scripts/android_pdf_hero.py")
