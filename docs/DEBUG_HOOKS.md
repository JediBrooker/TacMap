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
| `TACMAP_DEBUG_DEVICE_AUDIT` | `1` | Opt-in read-only observations. iOS: actual camera/viewport and opaque source identity/type/context (`TASK4_CAMERA` log), plus completed PDF job/cell geometry (`TASK4_JOB`, at most 64 jobs/8192 drawn cells per process) and bounded actual compositor-painted tile geometry/quality (`TASK4_FRAME`). Android: bounded in-memory completed PDF jobs/cells and actually painted tile geometry; instrumentation may save a snapshot. No title/path/content, content hashes or keys; render inputs and calibration behavior are untouched. |
| `TACMAP_UITEST_ONLINE` / `TACMAP_UITEST_OFFLINE_BASEMAP` | `1` | iOS Debug screenshot harness only: transiently enables both online gates or disables online basemaps. Saved OPSEC choices are unchanged; Release ignores these inputs. |
| `TACMAP_UITEST_NIGHT_MODE` | `1` | iOS Debug screenshot harness only: transient night mode; saved choice unchanged and Release ignores it. |
| `TACMAP_DEBUG_GRID` | `1` | Turns the MGRS grid overlay on. |
| `TACMAP_DEBUG_OPSEC_ALLOW_SCREENSHOTS` | `1` | Android only: lifts `FLAG_SECURE` for this session so `screencap` works. The persisted OPSEC setting is untouched. |

While a `DebugHooks` input is set, a debug build also skips the first-launch location
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

Android device audit observations require both `BuildConfig.DEBUG` and the explicit
`TACMAP_DEBUG_DEVICE_AUDIT=1` intent extra. The observer is off by default, emits no
log or file itself, and retains at most 64 completed jobs / 8192 cells plus one
frame of at most 64 painted tile items. Eviction, omission and truncation are
reported, so incomplete observations cannot count as complete evidence. Records
contain opaque source identity, canonical z/x/y, job rows/columns, tile size,
actual cell rectangles/transforms/error and painted pixel bounds. Tile geometry
reveals the viewed area, so only opt into an audit when that local observation is
wanted. Vector observations occur after the renderer's EWMA timing sample; raster
bake observations occur after its measured draw time. Render inputs, caches,
tolerances, OPSEC preferences and network behavior are unchanged. The screenshot
extra is separate. R8 removes the observer and its call sites from Release.

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

The iOS job observer is entirely `#if DEBUG` and requires the exact environment
value `TACMAP_DEBUG_DEVICE_AUDIT=1`. It observes existing completed plans on render
workers after the vector timing sample has been captured; a lock reserves its
64-job/8192-cell process budget. It logs complete admitted jobs, including existing
cell rectangles, page-to-pixel transforms, errors and page quads, with only an
ephemeral render-context ID. Camera records expose that same opaque context ID to
bind jobs to the actual source. It changes no render plan, inputs, tolerance or
output and includes no file name/path, content hash, content or key material.
Missing or capacity-excluded observations are not evidence of a successful job.

The iOS compositor additionally exposes only the actual image-backed tile layers
it has applied: own/ancestor/child classification, source tile index, actual layer
frame/contents rectangle and bitmap dimensions. These main-owned observations
share the actual camera, opaque source/context ID and runtime generation, retain
at most 64 items and emit at most 64 changed frames per view. A frame with more
than 64 actual items is explicitly incomplete. No image pixels are read or logged.
All storage/calls are DEBUG-only and exactly opted in; no rendering is changed.

The iOS DEBUG audit transports admitted job/frame JSON as `TASK4_CHUNK` records: opaque UUID, kind, zero-based index, total chunk count, total byte count and base64 of at most 600 bytes (approximately 800 logged characters). Each record is capped at 2 MiB and 4096 chunks; an oversized record emits only `TASK4_OMITTED`. Consumers must reject missing, duplicate/conflicting or invalid chunks and decode only a complete group. Existing job/cell/frame admission caps remain unchanged. This transport is release-excluded, exact opt-in and contains only the already admitted geometry; prior truncated unified-log JSON remains unusable evidence.

The exact-opt-in iOS DEBUG frame also joins actual painted CGImage opaque identity to at most 128 weak CGImage delivery observations per source, owned on Main. Only an already decoded baked image is labelled `decoded-bake`; other delivered images are `live`. The weak reference follows the bitmap actually retained by cache/layers, not a temporary UIImage wrapper; no bitmap is retained strongly by this audit. Missing/deallocated/evicted provenance is `unproved`, not a baked-path assertion. Existing cache, render, fallback and delivery behavior is unchanged; all origin observation state and calls are Release-excluded.
