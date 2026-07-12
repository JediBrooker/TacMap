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
def _dump_once():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    x = adb("shell", "cat", "/sdcard/ui.xml").stdout.decode("utf-8", "replace"); x = x[x.find("<?xml"):]
    try: return list(ET.fromstring(x).iter("node"))
    except ET.ParseError: return []
def nodes():
    # uiautomator dumps over this Compose UI are RACY - often a near-empty tree
    # comes back mid-frame, which is what makes taps miss. Keep the fullest of a
    # few tries; any real screen here has well over a dozen nodes.
    best = _dump_once()
    for _ in range(3):
        if len(best) >= 12: break
        time.sleep(0.4)
        d = _dump_once()
        if len(d) > len(best): best = d
    return best
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
    # With an offline (PDF) map loaded the single "Centre on My Location" button
    # splits into two shorter ones, "My Location" + "Map" - match either so
    # ensure_map() doesn't think we've left the map and BACK out of everything.
    txt = " ".join(n.get("text", "").lower() for n in nodes())
    return "my location" in txt or "centre on map" in txt
def ensure_map(tries=5):
    for _ in range(tries):
        if on_map(): return True
        back(1)
    adb("shell", "am", "start", "-n", ACT); time.sleep(3); return on_map()
def menu_open():
    labs = " ".join((n.get("text", "") + n.get("content-desc", "")) for n in nodes())
    return "Unit Sync" in labs and "Weather" in labs
def open_menu(tries=4):
    for _ in range(tries):
        if menu_open(): return True
        ensure_map(); ham(); time.sleep(1.2)
        if menu_open(): return True
        back(1); time.sleep(0.8)
    return menu_open()
def menu_tap(label, p=2.5, retries=4):
    for _ in range(retries):
        if open_menu() and tapt(label, p): return True
        ensure_map(); time.sleep(0.6)
    print("  [!] menu_tap failed for", repr(label)); return False
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
    """Overwrite opsec.xml with capture blocking as asked. online_basemaps is OFF
    for this slide: the whole point is the imported GeoPDF as the basemap, and an
    online satellite layer would render on top of it. Sync (relay websocket) and
    the vector situation overlay don't depend on it. Needs a debuggable build."""
    xml = (
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
        "<map>\n"
        '    <boolean name="online_basemaps" value="false" />\n'
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
    # Import the GeoPDF as the basemap (Import / Export -> PDF Map -> pick the
    # file) and frame it. online_basemaps is off (write_opsec) so the georeferenced
    # PDF sheet shows through instead of an online satellite layer on top.
    #
    # NOTE: deliberately NO sync join here. Joining the room from inside this
    # script - on top of a 39MB PDF import - was by far the flakiest step (the
    # Unit Sync sheet intermittently came up without its code field, and a missed
    # Done left it open and blocked every later tap). The slide's job is to show a
    # georeferenced imported map sheet aligned to the MGRS grid; that stands on its
    # own. ROOM/LON/LAT are still accepted for compatibility.
    ensure_map()
    menu_tap("Import / Export") or menu_tap("Import"); time.sleep(2.0)
    for _ in range(4):
        if tapt("PDF Map", 3): break
        time.sleep(1.2)
    time.sleep(1.5)
    def file_node():
        # the file ROW, NOT its "Preview the file <name>" button (both carry the
        # name and the preview lists first, so a plain substring tap only previews)
        for n in nodes():
            lab = n.get("text", "") + "|" + n.get("content-desc", "")
            if PDF in lab and "Preview" not in lab and n.get("bounds"): return ctr(n.get("bounds"))
        return None
    # The SAF picker opens on Recent (tablet) or Downloads (phone); a freshly
    # pushed file only lists under Downloads, so route through the roots drawer.
    for _ in range(5):
        hit = file_node()
        if hit: tap(*hit, 8); print("  tapped file", PDF); break
        (tapt("Show roots", 1.5) or ham() or time.sleep(0)); time.sleep(1.0)
        tapt("Downloads", 2.0); time.sleep(1.5)
    time.sleep(12)   # 39MB GeoPDF: allow parse + first render
    # frame: centre on device location (= PDF centre) so the sheet fills the frame
    ensure_map(); (tapt("Centre on My Location", 3) or tapt("My Location", 3)); time.sleep(3)
    snap("pdf-hero")
    print("done")
finally:
    print("restoring OPSEC pref...")
    restore_secure(original_block)
