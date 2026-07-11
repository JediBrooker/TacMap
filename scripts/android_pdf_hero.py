#!/usr/bin/env python3
"""Capture the GeoPDF-import hero: imported US Topo (GeoPDF) basemap with NATO
situation overlaid (black symbology on topo, like the satellite hero).

Device location is set to situation/PDF centre so "Centre on My Location"
frames it deterministically. The US Topo collar offsets the PDF's auto-fly
so we don't rely on that. GeoPDF must already be at /sdcard/Download/<pdf>,
and the situation pushed (re-centred) to <room>.

Use the DEBUG apk: the FLAG_SECURE workaround below leans on run-as, which
only works on a debuggable build. The app defaults block_screen_capture=true
(OPSEC / FLAG_SECURE), which blanks BOTH screencap (solid black) AND
uiautomator dump (no nodes, so every tap misses); a fresh install resets it
to that blocking default. Fix: flip the pref off via run-as before we
navigate, prove the window is capturable, restore the original in finally.

    python3 scripts/android_pdf_hero.py <debug_apk> <out_dir> <room> <pdf_basename> <lon> <lat>
"""
import re, subprocess, sys, time, struct, xml.etree.ElementTree as ET
APK, OUT, ROOM, PDF, LON, LAT = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5], sys.argv[6]
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
def back(n=1):
    for _ in range(n): adb("shell", "input", "keyevent", "4"); time.sleep(1.2)
def on_map():
    return any("centre on my location" in n.get("text", "").lower() for n in nodes())
def ensure_map(tries=5):
    for _ in range(tries):
        if on_map(): return True
        back(1)
    adb("shell", "am", "start", "-n", ACT); time.sleep(3); return on_map()
def grab_png(): return adb("exec-out", "screencap", "-p").stdout
def png_dims(png):
    """WxH from the IHDR. Correct even on a black FLAG_SECURE frame, and robust
    to a landscape tablet rotated to portrait (unlike wm size)."""
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

# ---- FLAG_SECURE / block_screen_capture ----
def read_pref_blocking():
    """Current block_screen_capture from the app prefs, or None if not written yet."""
    xml = adb("shell", "run-as", PKG, "cat", "shared_prefs/opsec.xml").stdout.decode("utf-8", "replace")
    m = re.search(r'name="block_screen_capture"\s+value="(true|false)"', xml)
    return None if not m else (m.group(1) == "true")
def write_opsec(block):
    """Overwrite opsec.xml with capture blocking as asked, plus online basemaps +
    lookups on so tiles and weather populate. Needs a debuggable build for run-as."""
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

# ---- install + launch (debug APK; run-as below needs it debuggable) ----
print("install..."); adb("uninstall", PKG); print(adb("install", "-g", APK).stdout.decode()[-60:])
for p in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS"): adb("shell", "pm", "grant", PKG, f"android.permission.{p}")
adb("shell", "settings", "put", "secure", "location_mode", "3")
adb("emu", "geo", "fix", LON, LAT)
adb("shell", "am", "start", "-n", ACT); time.sleep(8)
adb("emu", "geo", "fix", LON, LAT); time.sleep(2)
W, H = png_dims(grab_png())
print(f"screen {W}x{H}")

# --- THE FIX: clear FLAG_SECURE before we tap/snap anything ---
print("disabling block_screen_capture (FLAG_SECURE)...")
original_block = disable_secure()

try:
    # load the GeoPDF basemap
    ensure_map()
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
finally:
    print("restoring OPSEC pref...")
    restore_secure(original_block)
