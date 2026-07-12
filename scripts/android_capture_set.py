#!/usr/bin/env python3
"""Capture full single-device marketing set from a booted Android emulator
in ONE session. Resolution-aware so it runs unchanged on phone (tm_phone,
portrait) and tablet (tm_tablet, rotated to portrait).

Use the DEBUG apk. The old note here said "release APK so Google Maps
renders" but the Google Maps SDK is gone - the app draws its own raster
tiles now, so there's nothing release-only to render. Debug is required
anyway: the FLAG_SECURE workaround below leans on run-as, which only works
on a debuggable build.

Captures against the live NATO situation joined from <room>:
    hero          wide tactical picture on Esri Satellite
    symbols       zoomed-in APP-6 symbology ("Mark the ground")
    unit-sync     Unit Sync sheet, Connected
    recording     live REC breadcrumb indicator
    weather       Weather & UAV safety sheet
    import-export Import / Export sheet
    symbol-builder APP-6 editor (affiliation / echelon / function + preview)
    search        Search sheet

Basemaps fan (android_basemaps.py) and GeoPDF hero (android_pdf_hero.py)
are captured seperately.

The catch: the app defaults block_screen_capture=true (OPSEC), which sets
FLAG_SECURE on the window. FLAG_SECURE blanks BOTH screencap (solid black)
AND uiautomator dump (null root node) - so every snap AND every tap dies.
A fresh install resets the pref to that blocking default. Fix: before we
navigate anything, flip the pref off in the app's private prefs via run-as
(debuggable build only), force-stop, relaunch, and prove the window is
capturable. Original pref is restored in the finally block at the end.

    python3 scripts/android_capture_set.py <debug_apk> <out_dir> <room>
"""
import re, subprocess, sys, time, struct, xml.etree.ElementTree as ET

APK, OUT, ROOM = sys.argv[1], sys.argv[2], sys.argv[3]
PKG, ACT = "com.tacmap", "com.tacmap/.app.MainActivity"
LON, LAT = "150.305", "-33.700"          # Blue Mountains, situation centre
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
    # uiautomator dumps over this Compose UI are RACY - it often returns a
    # near-empty tree (1-5 nodes) mid-frame, which is what makes ham()/tapt()
    # intermittently miss. Re-dump until the tree looks complete, keeping the
    # fullest we saw. Any real screen here has well over a dozen nodes.
    best = _dump_once()
    for _ in range(3):
        if len(best) >= 12: break
        time.sleep(0.4)
        d = _dump_once()
        if len(d) > len(best): best = d
    return best
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
def menu_open():
    """The hamburger icon rail is open when its item descriptions are exposed."""
    labs = " ".join((n.get("text", "") + n.get("content-desc", "")) for n in nodes())
    return "Unit Sync" in labs and "Weather" in labs
def open_menu(tries=4):
    """Open the hamburger rail and VERIFY it opened - ham() alone is flaky once
    the situation adds clickable annotations near the top-left, so retry."""
    for _ in range(tries):
        if menu_open(): return True
        ensure_map(); ham(1.2)
        if menu_open(): return True
        back(1); time.sleep(0.8)          # a stray sheet may be covering it
    return menu_open()
def menu_tap(label, p=2.5, retries=4):
    """Open the rail (verified) then tap a menu item, retrying the whole thing.
    The rail dump is still occasionally partial, so give each item several
    open+tap attempts before giving up - a missed slide is worse than slow."""
    for _ in range(retries):
        if open_menu() and tapt(label, p): return True
        ensure_map(); time.sleep(0.6)
    print("  [!] menu_tap failed for", repr(label)); return False

def grab_png():
    return adb("exec-out", "screencap", "-p").stdout
def png_dims(png):
    """WxH from the IHDR. Correct even on a black FLAG_SECURE frame."""
    return struct.unpack(">I", png[16:20])[0], struct.unpack(">I", png[20:24])[0]
def black_fraction(png):
    """Fraction of near-black pixels, or None if undecidable. A FLAG_SECURE
    window screencaps as ~solid black, which is how we catch it slipping back on."""
    if not _HAVE_PIL:
        return 0.99 if len(png) < 90_000 else 0.0   # secure frame compresses tiny
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
        original = True                       # fresh install default
    print(f"  block_screen_capture was: {original}")
    for attempt in range(3):
        write_opsec(False)
        relaunch()
        for lbl in ["While using", "Allow", "OK"]:   # relaunch may resurface a perm prompt
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
for p in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS"):
    adb("shell", "pm", "grant", PKG, f"android.permission.{p}")
adb("shell", "settings", "put", "secure", "location_mode", "3")
adb("emu", "geo", "fix", LON, LAT)
adb("shell", "am", "start", "-n", ACT); time.sleep(9)
adb("emu", "geo", "fix", LON, LAT); time.sleep(2)
for lbl in ["While using", "Allow", "OK"]:
    if tapt(lbl, 1.2): break
def screen_dims():
    """Actual rendered WxH from screencap. wm size gives the physical panel
    which is wrong when landscape tablet is rotated to portrait."""
    return png_dims(grab_png())
W, H = screen_dims()
CX, CY = W//2, H//2
print(f"screen {W}x{H}")

# --- THE FIX: clear FLAG_SECURE before we tap/snap anything ---
print("disabling block_screen_capture (FLAG_SECURE)...")
original_block = disable_secure()

try:
    # ---- basemap: Esri Satellite (the app's default; custom raster renderer) ----
    menu_tap("Layers and Labels")
    if not tapt("Satellite (Esri)", 3):
        # the basemap rows can sit below the fold - scroll the sheet up and retry
        adb("shell", "input", "swipe", str(CX), str(int(H*0.8)), str(CX), str(int(H*0.3)), "300"); time.sleep(1)
        tapt("Satellite (Esri)", 3)
    back(1); time.sleep(4)

    # ---- join the NATO situation room ----
    # Two gotchas learned the hard way: (1) the join-code field is a Compose
    # EditText but `input text` silently drops chars, so type-read-back-retry
    # until it matches - a truncated code trips the <14-char weak-code guard and
    # the Join button just no-ops. (2) do NOT press BACK to dismiss the IME: on
    # this Compose ModalBottomSheet BACK closes the whole sheet, so Join is gone
    # before we can tap it. Tap "Join / create" directly - it sits above any IME.
    menu_tap("Unit Sync"); time.sleep(1.5)
    if any("Room code" in n.get("text", "") or "Leave" in n.get("text", "") for n in nodes()):
        tapt("Leave", 2); menu_tap("Unit Sync"); time.sleep(1.5)
    def code_field():
        for n in nodes():
            if n.get("class", "").endswith("EditText") and n.get("bounds"): return n
        return None
    fn = code_field()
    if fn is not None:
        tap(*ctr(fn.get("bounds")), 1.2)
        # `input text <wholestring>` drops trailing chars on this Compose field
        # (consistently loses the last couple), so type char-by-char with a small
        # gap and read back, retrying until the field matches ROOM exactly.
        for _ in range(4):
            f = code_field(); cur = f.get("text", "") if f is not None else ""
            if cur == ROOM: break
            for _ in range(len(cur) + 8): adb("shell", "input", "keyevent", "67")   # KEYCODE_DEL clear
            time.sleep(0.4)
            for ch in ROOM:
                adb("shell", "input", "text", ch); time.sleep(0.14)
            time.sleep(0.7)
        f = code_field()
        print("  join code entered:", repr(f.get("text", "") if f is not None else None))
        btn = None                                                     # find "Join / create" button by node
        for n in nodes():
            if "join / create" in n.get("text", "").lower() and n.get("bounds"): btn = ctr(n.get("bounds")); break
        if btn: tap(*btn, p=2.0)
        for _ in range(10):                                            # wait for CONNECTED
            if any(n.get("text", "") == "Connected" for n in nodes()): print("  sync CONNECTED"); break
            time.sleep(2)
        else:
            print("  [!] sync did NOT reach Connected")
    else:
        print("  [!] join-code field not found")
    if not tapt("Done", 1.5): back(1)
    time.sleep(2)

    # ---- labels on ----
    menu_tap("Layers and Labels")
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
    menu_tap("Unit Sync"); time.sleep(2); snap("unit-sync"); back(1); time.sleep(1)

    # ---- recording (REC indicator) ----
    menu_tap("Start Track Recording", 3); time.sleep(2); snap("recording")
    menu_tap("Stop Track Recording", 2); time.sleep(1)            # stop it again

    # ---- weather ----
    menu_tap("Weather", 4); time.sleep(2); snap("weather"); back(1); time.sleep(1)

    # ---- import / export ----
    menu_tap("Import / Export", 3); time.sleep(1); snap("import-export"); back(1); time.sleep(1)

    # ---- symbol builder (APP-6 editor) ----
    menu_tap("Symbology", 3)
    tapt("Add Military Unit", 3); time.sleep(1); snap("symbol-builder")
    tapt("Cancel", 2); back(2); time.sleep(1)

    # ---- search ----
    menu_tap("Search", 3); time.sleep(1); snap("search"); back(1)
    print("DONE android_capture_set")
finally:
    # always put OPSEC back the way we found it
    print("restoring OPSEC pref...")
    restore_secure(original_block)
