# Debug verification hooks

Launch-environment switches for on-device verification runs: import a PDF,
point the camera and turn the MGRS grid on without touching the UI, so
screenshots (and `grid_alignment.py`) are repeatable.

**Debug builds only.** iOS reads them inside `#if DEBUG`
(`ios/TacticalMaps/App/DebugHooks.swift`); Android reads them behind
`BuildConfig.DEBUG`. Release builds don't contain the code, so the variables do
nothing there. They're listed in `docs/THREAT_MODEL.md` §9 for auditors.

| Variable | Value | Effect |
|---|---|---|
| `TACMAP_DEBUG_IMPORT_PDF` | absolute path readable by the app | Imports that file through the normal import pipeline, exactly as if it had been picked (copy, parse, crash-guard probe, persist, select). No picker. |
| `TACMAP_DEBUG_CAMERA` | `lat,lon,zoom[,heading]` | Sets the camera once the app is up (after the import above has framed its sheet). Zoom is still clamped to 2...22. Malformed values are ignored. |
| `TACMAP_DEBUG_CALIBRATION_POINT` | `rawPageX,rawPageY` | iOS only: centres the normal camera on this point of the current calibration display at z18 (or DEBUG_CAMERA zoom). It changes no points or calibration state; Add point still uses the real crosshair capture and entry UI. |
| `TACMAP_DEBUG_GRID` | `1` | Turns the MGRS grid overlay on. |
| `TACMAP_DEBUG_OPSEC_ALLOW_SCREENSHOTS` | `1` | Android only: lifts `FLAG_SECURE` for this session so `screencap` works. The persisted OPSEC setting is untouched. |

While any hook is set, a debug build also skips the first-launch location
permission prompt and the first-run tour, so nothing sits on top of the map in a
screenshot.

## iOS (simulator)

The app can read a file inside its own data container, so copy the PDF there
first:

```sh
SIM=9F49DE2E-00B0-446A-A7A0-DBD25599F00E
DATA=$(xcrun simctl get_app_container $SIM com.tacticalmaps.app data)
cp testdata/geopdf/tacmap_grid_sf_iso.pdf "$DATA/tmp/"
SIMCTL_CHILD_TACMAP_DEBUG_IMPORT_PDF="$DATA/tmp/tacmap_grid_sf_iso.pdf" \
SIMCTL_CHILD_TACMAP_DEBUG_CAMERA="37.7660,-122.4437,16" \
SIMCTL_CHILD_TACMAP_DEBUG_GRID=1 \
  xcrun simctl launch --terminate-running-process $SIM com.tacticalmaps.app
xcrun simctl io $SIM screenshot shot.png
```

`simctl launch` passes `SIMCTL_CHILD_*` variables through to the app with the
prefix stripped.

## Android (emulator)

Android has no per-launch environment, so the same names are **string extras on
the launch intent**. `MainActivity` reads them only when `BuildConfig.DEBUG` (a
compile-time constant, so R8 drops the code from release) and only on a fresh
start (not on a configuration-change recreate). Parsing lives in
`android/app/src/main/java/com/tacmap/app/DebugLaunchHooks.kt` and is unit tested.

The app can't read `/data/local/tmp` or another app's storage, so put the PDF in
its own private dir with `run-as` (debug builds are debuggable):

```sh
S=emulator-5560
adb -s $S install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s $S shell "run-as com.tacmap sh -c 'mkdir -p files/dbg && cat > files/dbg/sf.pdf'" \
  < testdata/geopdf/tacmap_grid_sf_iso.pdf
adb -s $S shell am force-stop com.tacmap
adb -s $S shell am start -n com.tacmap/.app.MainActivity \
  --es TACMAP_DEBUG_IMPORT_PDF /data/user/0/com.tacmap/files/dbg/sf.pdf \
  --es TACMAP_DEBUG_CAMERA 37.7660,-122.4437,16,0 \
  --es TACMAP_DEBUG_GRID 1 \
  --es TACMAP_DEBUG_OPSEC_ALLOW_SCREENSHOTS 1
adb -s $S exec-out screencap -p > shot.png
```

- The imported PDF stays the active map, so later runs only need
  `TACMAP_DEBUG_CAMERA` (plus the screenshot extra) to move around it.
- `TACMAP_DEBUG_GRID=1` writes the normal grid toggle, so the grid stays on
  afterwards like a user tap would.
- `TACMAP_DEBUG_OPSEC_ALLOW_SCREENSHOTS=1` lasts for the process only; force-stop
  and relaunch without it and `FLAG_SECURE` is back.
- Use `emulator-5560` for this work; don't drive other emulators.
