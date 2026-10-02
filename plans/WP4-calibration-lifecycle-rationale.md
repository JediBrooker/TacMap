
# How the four designs were combined, and what was changed after checking the real code

## Taken from the field-user lenses
- **Glove- and night-first UI.**
  - 56 pt/dp targets.
  - +/- zoom buttons.
  - State shown by glyph + word (night mode maps colour to luminance).
  - Leave (✕) placed far from Finish.
  - Explicit Add point at the crosshair; no tap placement.
  - Reticle with an open centre, so the printed intersection stays visible.
- **Stable point numbers.**
- **Moving and editing points.**
  - "Move to crosshair" and "Set here" flows.
  - GPS points gated to ≤ 20 m accuracy and stored with a WGS84 override.
- **Error behaviour.**
  - Every error says why and what to do next.
  - The first point must be a full reference.
- **Shorthand and datums.**
  - Shorthand completion from the map's prediction, with an 8-neighbour square search, so sheets that straddle a 100 km square work.
  - MGRS rejected on AL-lettering datums.
  - The iOS field-user τ uses the sheet diagonal, which works regardless of page units. It was simulated and kept at 0.0005·D (see below).
- **Android.**
  - Session owned by the ViewModel.
  - The render-from-`shown`-state idea for the camera/georef swap.

## Taken from the implementation-risk lenses
- **One sealed authority** for the active selection plus library entries. Every transition is a single write, which removes the two-store crash window documented at `MapViewModel.swift:48-56`.
- **Link-based, fail-closed migration.**
- **An in-flight registry.** Today `isCrashResidueName` deletes `.partial` files during reconcile (`PDFSessionStore.swift` ~604-620 and `ManagedImportedMapFileLifecycle.kt:116-125`). That is a latent bug, and it would get worse with N maps.
- **Import handling.**
  - Watchdog plus crash marker.
  - A nullable journal field on Android, so older builds still decode it.
  - Import menu items disabled while an import runs, instead of silently cancelling.
- **Entry card docked at the top.** On an iPhone SE the crosshair (y = 333) stays visible between the card and the keyboard.
- **Other.**
  - Search behaviour pinned unchanged by `search_contract.json`.
  - A pure decision function for imports.
  - A crop widened to the page box while calibrating, so the collar labels are visible.

## Flaws found in the lenses and fixed (with evidence)

**1. WP1's proposed outlier rule, and several lens rules, false-flag clean fits.**
- `pdf_georef.json` already pins `k = argmin looRms; flag if loo_k > max(5 m, 5*looRms_k)`.
- In simulation (`scratchpad/design/judge/rule_sim.py`: 1:25k and 1:50k, 0.5 pt placement, 3 m reading, 10 m scan warp, 1500 trials each), it named a point on 45-83% of **clean** fits at n = 5-6.
- The iOS implementation-risk rule did so on 6-31%.
- The chosen rule uses a set-level gate (RMS > τ) and names a point only when removing exactly that point makes the rest Good.
  - It flagged 0% of clean fits and named the wrong point 0.0% of the time.
  - For a 1 km blunder it named the right point 94-100% of the time at n = 6.
  - When the geometry cannot tell (symmetric n = 5), it reports an honest "a or b" pair.
- WP1 must regenerate or delete its `flaggedOutliers` field.

**2. The lenses' entry-time warning thresholds cry wolf.**
- The proposed constants were max(3τ, 50 m) and max(6τ, 200 m).
- They warned on 5-33% of *correct* 4th points (`design/judge/entry_sim.py`), because predictions from a 3-point exact fit are poor when extrapolating.
- The chosen threshold scales with leverage: max(3τ·√(1+h), 50 m).
  - 0% false warnings.
  - 1 km slips caught 81-100% of the time.

**3. The tolerance fraction was checked.**
- τ = max(10 m, 0.0003·D) graded only 35-76% of clean fits Good under sloppier conditions (1 pt placement, 20 m warp).
- 0.0005·D graded 93-100% Good.

**4. Stale tiles on a refit would show the page in the wrong place.**
- Some designs keep old tiles while new ones load, or fly the camera separately (Android implementation-risk: `vm.flyTo` via `pendingTarget`).
- With a compensated camera, stale tiles drawn with the old georef show the page offset by the full georef change, which can be kilometres at the n = 3 transition.
- The `pendingTarget` path renders at least one mismatched frame.
- Fix: prefetch the new generation, then swap source, camera and `displayedGeoref` in one main-thread step. On Android this is a single Compose snapshot inside `CustomMapScreen`, which owns the camera (`CustomMapScreen.kt:119`). Capture reads the same installed georef and the live camera.

**5. Android MapScreen is destroyed on every pause.**
- `MainActivity.onPause` (:320-336) calls `DataKey.lock()` and sets `missionKeyReady = false`, which drops MapScreen (:224-227).
- Consequences for the lens designs:
  - Conflated async IO draft writes would race the key lock.
  - A sealed pending-URI store fails because the key is locked when the picker result arrives.
  - A VM-scoped import job would write sealed stores after lock.
- Fixes:
  - Session in the VM.
  - Synchronous draft writes.
  - Pending import held in memory and the Bundle only, with the legacy prefs migrated once and grants cleaned up.
  - Import job kept composition-scoped, with progress in the VM.

**6. iOS header and heading behaviour.**
- The header reads GPS unless browsing (`MapViewModel.swift:455-458`).
- Heading-up keeps rotating the map (`ContentView.swift:953-957`).
- The hamburger stays reachable during calibration (it starts an invisible measure session, :1386-1390).
- Fix: calibration forces browse mode, pauses heading-up, and hides the chrome.

**7. AL-lettering decode (iOS implementation-risk) was rejected.**
- A wrong decode shifts all points consistently, so the fit looks perfect and nothing catches it.

**8. Forcing the MGRS grid on while calibrating (field-user lenses) was rejected.**
- A correct fit draws the overlay exactly on top of the printed lines the user is targeting.
- Replaced by a calibration-local Grid toggle, default off.

**9. The iOS field-user's 1- and 2-point preview georefs and heading compensation were dropped.**
- World overlays are hidden while calibrating anyway.
- Heading compensation would leave a non-north heading after Finish and diverges from the Android camera path.
- The shared rule is: keep the page point under the crosshair and the page's on-screen scale fixed; heading unchanged. Only a small rotation about the crosshair is visible.

**10. Drafts inside the one library file (iOS implementation-risk) would cause write amplification.**
- Every point add would rewrite all entries.
- Drafts moved to their own sealed file. Georef and manual calibration live inside entries, so deleting a map deletes its calibration by construction (D5-19), without a content-hash library.

**11. Other code facts that changed details.**
- Android file names are already opaque (`DocumentImportCopy.kt:95-100`).
- iOS bake names are already opaque (`PDFTiler.swift:44`). Only the iOS import copies need opaque names.
- iOS creates both directories with `.complete` (`ContentView.swift:131`, `PDFTiler.swift:43-44`), so partials fail after the device locks; changed to cufua.
- Android's `onEmptyTap` has no position, but `start` is in scope at `CustomMapTouch.kt:373`.
- Both platforms use the same `sha256:<hex>` content key.

## Parity decisions made explicit
- One state machine, one set of constants, one copy table (English and German, 150+ keys), and one import decision table.
- Limits: PDF 512 MiB and MBTiles 4 GiB, raised on Android and iOS respectively.
- Errors map to message keys through `import_limits.json`.
- Three new generated fixtures (`calibration_input`, `calibration_fit_report`, `import_limits`) land first. They are generated with pyproj, mgrs and numpy by `scripts/gen_calibration_fixtures.py`, and both suites must pass them, so the two implementations cannot diverge.

## Scope control
- Each platform is phased A (pure layer and fixtures), then B (library, which fixes the data-loss findings first), then C (calibration UX), then D (import UX).
- There is an explicit P1 cut list.
- The largest risk on both platforms is the library migration and reconcile rewrite. It is fail-closed, idempotent, and covered by injected-crash tests with frozen legacy fixtures.
