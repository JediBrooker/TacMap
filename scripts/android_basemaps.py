#!/usr/bin/env python3
"""Capture the three basemaps (Satellite / Esri / OpenTopoMap terrain) from the
real app for the "Satellite or terrain" fan slide.

Use the DEBUG apk: the FLAG_SECURE workaround below leans on run-as, which
only works on a debuggable build (and the Google Maps SDK is gone anyway -
the app draws its own raster tiles now, nothing release-only to render).

The app defaults block_screen_capture=true (OPSEC) which sets FLAG_SECURE,
and that blanks BOTH screencap (solid black) AND uiautomator dump (no nodes,
so every tap misses). A fresh install resets it to that blocking default.
Fix: flip the pref off via run-as before we navigate, prove the window is
capturable, and restore the original pref in the finally at the end.

    python3 scripts/android_basemaps.py <debug_apk> <out_dir>
"""
import re, subprocess, sys, time, struct, xml.etree.ElementTree as ET
APK, OUT = sys.argv[1], sys.argv[2]
PKG, ACT = "com.tacmap", "com.tacmap/.app.MainActivity"
RELAY = "wss://tacmap-sync.christianbrooker.workers.dev/room/"

# Pillow drives the blank-guard; degrade to a size heuristic if it's missing.
try:
    from PIL import Image
    import io
    _HAVE_PIL = True
except Exception:
    _HAVE_PIL = False

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
    if r is None: return False
    for n in r.iter("node"):
        if n.get("clickable") == "true" and n.get("bounds"):
            x, y = ctr(n.get("bounds"))
            if y < 700 and x < 400 and (best is None or x+y < best[2]): best = (x, y, x+y)
    if best: tap(best[0], best[1]); return True
    return False
def grab_png(): return adb("exec-out", "screencap", "-p").stdout
def png_dims(png):
    """WxH from the IHDR. Correct even on a black FLAG_SECURE frame."""
    return struct.unpack(">I", png[16:20])[0], struct.unpack(">I", png[20:24])[0]
def black_fraction(png):
    """Fraction of near-black pixels, or None if undecidable. A FLAG_SECURE
    window screencaps as ~solid black, which is how we catch it slipping back on."""
    if not _HAVE_PIL:
        return 0.99 if len(png) < 90_000 else 0.0
    try:
        im = Image.open(io.BytesIO(png)).convert("RGB").resize((80, 160))
        px = list(im.getdata())
        blk = sum(1 for r, g, b in px if r < 12 and g < 12 and b < 12)
        return blk / len(px)
    except Exception:
        return None
def snap(n):
    png = grab_png()
    open(f"{OUT}/{n}.png", "wb").write(png)
    bf = black_fraction(png)
    tag = "  <<< WARNING mostly-black, FLAG_SECURE may be on" if (bf is not None and bf > 0.9) else ""
    print(f"  snap {n} {len(png)//1024}KB black={bf if bf is None else round(bf,2)}{tag}")
    return bf
def back(n=1):
    for _ in range(n): adb("shell", "input", "keyevent", "4"); time.sleep(1.2)
def on_map():
    r = dump(); return r is not None and any("centre on my location" in x.get("text","").lower() for x in r.iter("node"))
def ensure_map(tries=5):
    for _ in range(tries):
        if on_map(): return True
        back(1)
    adb("shell", "am", "start", "-n", ACT); time.sleep(3); return on_map()

# ---- FLAG_SECURE / block_screen_capture ----
def read_pref_blocking():
    """Current block_screen_capture from the app prefs, or None if not written yet."""
    xml = adb("shell", "run-as", PKG, "cat", "shared_prefs/opsec.xml").stdout.decode("utf-8", "replace")
    m = re.search(r'name="block_screen_capture"\s+value="(true|false)"', xml)
    return None if not m else (m.group(1) == "true")
def write_opsec(block):
    """Overwrite opsec.xml with capture blocking as asked, plus online basemaps
    on so tiles populate. Needs a debuggable build for run-as."""
    xml = (
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
        "<map>\n"
        '    <boolean name="online_basemaps" value="true" />\n'
        '    <boolean name="online_lookups" value="true" />\n'
        f'    <boolean name="block_screen_capture" value="{"true" if block else "false"}" />\n'
        f'    <string name="relay_url">{RELAY}</string>\n'
        "</map>\n"
    )
    tmp = "/data/local/tmp/opsec.xml"
    p = subprocess.Popen(["adb", "shell", "cat", ">", tmp], stdin=subprocess.PIPE)
    p.communicate(xml.encode())
    r = adb("shell", "run-as", PKG, "cp", tmp, "shared_prefs/opsec.xml")
    err = r.stderr.decode().strip()
    if err:
        print(f"  [!] run-as cp failed: {err} (build must be debuggable)")
        return False
    return True
def relaunch():
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", ACT)
    time.sleep(6)
def disable_secure():
    """Flip FLAG_SECURE off and prove the window is capturable. Returns the
    original pref so restore_secure can put it back."""
    original = read_pref_blocking()
    if original is None:
        original = True
    print(f"  block_screen_capture was: {original}")
    for attempt in range(3):
        write_opsec(False)
        relaunch()
        for lbl in ["While using", "Allow", "OK"]:
            if tapt(lbl, 1.2): break
        ensure_map()
        bf = black_fraction(grab_png())
        if bf is None or bf < 0.9:
            print(f"  FLAG_SECURE cleared (black={None if bf is None else round(bf,2)})")
            return original
        print(f"  still black (attempt {attempt+1}), retrying...")
    print("  [!] could not clear FLAG_SECURE - captures may be black")
    return original
def restore_secure(original):
    print(f"  restoring block_screen_capture -> {original}")
    write_opsec(original)
    relaunch()

SCREEN_W, SCREEN_H = 1080, 2400   # gets overwritten at runtime from screencap
def select_basemap(label):
    ham(); tapt("Layers and Labels"); time.sleep(1)
    # scroll sheet up so basemap rows are actually reachable
    for _ in range(4):
        adb("shell", "input", "swipe", str(SCREEN_W//2), str(int(SCREEN_H*0.8)),
            str(SCREEN_W//2), str(int(SCREEN_H*0.28)), "300"); time.sleep(1)
    tapt(label, p=2.5)
    back(1); time.sleep(1)

# ---- install + launch (debug APK; run-as below needs it debuggable) ----
print("install..."); adb("uninstall", PKG); print(adb("install", "-g", APK).stdout.decode()[-80:])
for p in ("ACCESS_FINE_LOCATION","ACCESS_COARSE_LOCATION","POST_NOTIFICATIONS"): adb("shell","pm","grant",PKG,f"android.permission.{p}")
adb("shell","settings","put","secure","location_mode","3")
adb("emu","geo","fix","150.305","-33.700")
adb("shell","am","start","-n",ACT); time.sleep(9)
adb("emu","geo","fix","150.305","-33.700"); time.sleep(2)
SCREEN_W, SCREEN_H = png_dims(grab_png())
print(f"detected {SCREEN_W}x{SCREEN_H}")
CX, CY = SCREEN_W // 2, int(SCREEN_H * 0.49)

# --- THE FIX: clear FLAG_SECURE before we tap/snap anything ---
print("disabling block_screen_capture (FLAG_SECURE)...")
original_block = disable_secure()

try:
    ensure_map()
    tapt("Centre on My Location", p=3); time.sleep(2)
    # zoom in one notch so terrain detail reads
    adb("shell","input","tap",str(CX),str(CY)); adb("shell","input","tap",str(CX),str(CY)); time.sleep(3)

    # 1) Satellite (Esri is the app's default basemap)
    print("Satellite..."); time.sleep(8); snap("bm-satellite")
    # 2) Esri
    print("Esri..."); select_basemap("Satellite (Esri)"); time.sleep(10); snap("bm-esri")
    # 3) OpenTopoMap terrain - long load, need to pan to trigger tiles
    print("Terrain (OpenTopoMap)..."); select_basemap("Topographic (OpenTopoMap)")
    for _ in range(5):
        time.sleep(10)
        adb("shell","input","swipe","540","1100","560","1180","300"); time.sleep(1)
        adb("shell","input","swipe","560","1180","540","1100","300")
    tapt("Centre on My Location", p=3); time.sleep(6)
    snap("bm-terrain")
    print("done")
finally:
    print("restoring OPSEC pref...")
    restore_secure(original_block)
