
# Shared contract for WP4 (calibration) and WP5 (import lifecycle), iOS and Android

Both platforms must behave the same for the user: same states, numbers, thresholds, copy, flow and error messages. Everything in this section is binding for both implementations. It is pinned by three new shared fixtures (section 11), which land before any UI work.

## 0. Dependencies and landing order

**Landing order**
1. WP1: `PdfGeoreference`, the fiduciary fitter, `.provisional`, the datum table, and a parser that takes an already-open document.
2. WP2: the PDF tile source, keyed by georef generation, plus prefetch and the base raster.
3. WP5 storage: library, migration, reconcile, delete.
4. WP5 import: pipeline, progress, page choice, limits.
5. WP4 calibration.

**What WP4/WP5 require from WP1.** Adapt names at one adapter per platform (`GeorefAdapters`).
- Codable/Serializable `PdfGeoreference`:
  - fields: page, crs, datum, affine, crop, origin (`adobeVP | lgiDict | fiduciaries | provisional`), fit
  - `toWGS84(page) -> LatLon?` and `toPage(lat, lon) -> PagePoint?`
- `PdfGeoreference.provisional(pageBox, rotate, centre)`: geographic WGS84, upright after `/Rotate`, nominal 1:50,000, aspect preserved, page centre on `centre`.
- `GeoDatum` from the `pdf_georef.json` `datums.table`, with `ellipsoid`, `toWGS84` and `fromWGS84`.
- TM forward/inverse on any ellipsoid.
- `fitFiducials(inputs: [(page, latLonInSheetDatum)], datum, planeZone, south, cropBox)`, returning:
  - `affine`, per-point plane residual vectors, and the page-covariance eigenvalue ratio
  - or a typed failure: `degenerate(ratio)` / `invalid`
- `georeferences(document, pageIndices)` working on an already-open `CGPDFDocument` / `PDDocument`.

**What WP4 requires from WP2.**
- A PDF tile source keyed `pdf:<entryId>:<generation>` that renders from any `PdfGeoreference` and honours `georef.crop`.
- `prefetch(tiles, deadline)` using the base-raster fast path.
- Parent-tile fallback.
- WP2 owns the bake. A bake adds a derived MBTiles library entry and never deletes the PDF.

**Amendment 2026-10-02 (WP2 merge): decisions binding on both platforms.**
- M1 A bake is a record on the PDF's library entry (same JSON keys on both), not a derived MBTiles entry. `derivedFromId` / `derived` stay only to read legacy migrated data; no app path creates a derived entry.
- M2 The WP2 crash-guard token (`renderGuardToken`) lives on the library entry, minted at import or migration; an entry without one falls back to its (random, sealed) entry id.
- M3 Bake attach requires the same entry, content key, token and bakeKey(current georef, tilePx), else `sourceChanged`; invalid record or failed library write is `writeFailed`. Any georef change (commit calibration, revert to embedded, change page) drops the bake; reconcile and sweep delete the file.
- M4 The bake-only sweep keeps every entry's bake name and runs only on a trustworthy library read (loaded, or empty with no legacy stores left); skipped when locked, corrupt or a migration is pending. Runs on every restore, after Remove and after Delete, except when `recoveryPreservesOrphans` is true.
- M5 Delete Map is one library write: a failed write deletes nothing and offers Retry. Delete stops a running bake/estimate first and deletes the bake file + sidecars directly.
- M6 For the active PDF, WP2 OD-F4 wins over "unavailable, go online": a missing or changed file keeps the selection and fails as cannotOpen with Try Again (background hash mismatch included). The Layers row still says `map_state_unavailable` until a Try Again verifies the bytes. MBTiles entries keep the s-original behaviour.
- M7 The WP2 import probe runs inside the import pipeline after the page decision and before the library write (picked pages included); a probe failure is `MapImportError.cannotDraw(reason)` with WP2's reason copy and commits nothing. Duplicates and Choose page skip it.
- M8 Calibration draws through the WP2 tile source with the s7.2 "without prefetch" fallback (plain swap, brief placeholder); each refit gets a new tile source without the bake, sharing the document's base raster; source + anchored camera swap in one main-thread step.
- M9 Layers: Imported maps section plus WP2's block for the on-screen PDF (toggle on+disabled while calibrating, failed row, bake rows). The row menu's Generate offline tiles starts WP2's flow for that entry without switching maps, hidden once it has a bake; Remove only in the on-screen block.
- M10 Header order: calibrating, then failed, then drawing. Use Online Map and the crash-guard fallback use the library's preferred online style; picking the suspect map in Layers counts as Open Anyway; calibration auto-resume at launch is skipped while a crash suspect is pending.
- Known gap: on the single migrating launch Android can't suppress a crash suspect (no library yet); iOS can. Accepted.

**Amendment 2026-10-02 (r1): WP2 glue, binding on both.**
- D9 (M3 order) `attachBake` checks, in order: entry + pdf exist, then contentKey and renderGuardToken match (else `sourceChanged`), then the record is valid, i.e. a plain `tacmap-bake-<id>.mbtiles` name (else `invalidBake`, shown as `writeFailed`), then `bakeKey(effective georef, tilePx)` matches (else `sourceChanged`), then the write (`writeFailed`). `removeBake` with no entry or a record whose fileName isn't the entry's is `sourceChanged` and writes nothing (both). `bakeFileNames` = the valid records' names that match the generated bake form.
- F1 (M4/M8/M13) A calibration display never attaches or draws a bake. A bake published while calibrating only updates the library record; the runtime attaches a bake to a tile source only when that source was built from the entry's effective georef and no calibration session is up.
- OD-F10 The WP2 block's subtitle for a PDF with `pdf.manual` is the s10 state label (`map_state_calibrated` / `map_state_calibrated_exact`). "Manually placed bounds" is only for the legacy manual-bounds origin.
- OD-F9 Generate offline tiles from the row menu for an entry that isn't the on-screen map confirms with `pdf_bake_confirm_message_inactive`; the on-screen map keeps `pdf_bake_confirm_message`.

**Change requested of WP1's fixture.** The outlier proposal in `pdf_georef.json` (`fiduciaryFits.rules.outliers` and `flaggedOutliers`) is replaced by rule 6.4 below. WP1 regenerates that field with rule 6.4, or deletes it.
- Evidence (scratchpad `design/judge/rule_sim.py`): the WP1 proposal, `max(5 m, 5*looRms)` with no set-level gate, falsely names an outlier on **45-83% of clean 5-6 point fits** (1:25k/1:50k, 0.5 pt placement, 3 m reading, 10 m scan warp).
- Rule 6.4 falsely names an outlier on 0%, and never named the wrong point in any run.

## 1. Terms

- **page**: raw PDF user space (WP1 convention).
- **pageBox**: the CropBox ∩ MediaBox polygon of the chosen page, as 4 user-space points.
  - While calibrating, the displayed georef's `crop` is replaced by `pageBox`, so the collar and tick labels stay visible.
  - Outside calibration, `crop` is the georef's own crop.
- **displayedGeoref / generation**: the georef the map is actually drawing *right now* (the one that has been installed), and a monotonically increasing integer.
  - Page points are always captured through `displayedGeoref`.
  - Markers are always drawn through `displayedGeoref`.
- **τ (toleranceM)** and **D (diagonalM)**: see 6.1.
- **n**: the number of points.

## 2. Calibration flow (the state machine)

### 2.1 Entry points (the only three)

**E1. After import.** Calibration starts automatically when:
- an import produces a PDF entry with no usable georef on a single-page file, or
- the user picks a page in the page picker, or
- the user taps "Calibrate now" on the rejected-georef alert.

**E2. From Layers.**
- Row tap on an entry in state `needsCalibration` or `rejected`.
- The row menu item "Calibrate / refine…" on any PDF entry, including GeoPDFs.

**E3. Auto-resume at launch.** If a draft with `active == true` exists and its entry exists, calibration re-enters that entry after the library restore, and shows the toast `calibration_resumed`.

**Amendment 2026-10-02 (r1), E3** (pinned by `import_limits.json` `lifecycle.autoResume`):
- Runs once, after the first Loaded restore (launch, or the unlock / Retry that makes a Locked library Loaded, F3). Skipped while any crash suspect is pending (M10, C2); the draft stays `active` for the next launch.
- C8: while a non-durable calibration preview draws, the WP2 guard is armed with the previewed entry's token, so a crash there makes that entry the suspect and E3 skips it.
- OD-F1: frames the sheet exactly like an E1/E2 start; `calibration_off_sheet` still wins the status line (s10).
- C6: reopens the entry card on `draft.pending`; rewrites the draft `active = true` at once; the `calibration_resumed` toast only when the draft has at least 1 point.

### 2.2 Start preconditions
- The library is Loaded (not locked or corrupt).
- The entry's file is present.
- Not already calibrating.

**Amendment 2026-10-02 (r1), C2:** starting calibration (E1/E2) on the pending crash suspect resolves the guard as Open Anyway first, then starts and publishes (same as picking it in Layers). Starting on any other entry while a suspect is pending leaves the recovery dialog as it is.

### 2.3 On start (both platforms, in this order)
1. End measure, drawing and free-draw.
2. Clear symbol and drawing selection and the drawing preview.
3. Close the point/long-press menu and quick-add.
4. Pause heading-up:
   - device-heading updates are ignored;
   - the rotation gesture is enabled;
   - the user's orientation setting is NOT changed.
5. Suppress first-fix auto-recentre.
6. Force the header to read the crosshair (browse mode on).
7. Hide chrome and overlays (section 10).
8. If the entry has no effective georef (`needsCalibration` / `rejected`):
   - publish it as a **non-durable preview**, with `displayedGeoref = provisional(pageBox, rotate, cameraCentre)`, and frame the page once;
   - the durable active selection is unchanged, so an uncalibrated PDF is never a durable basemap (D2-06, D5-02).
9. If the entry has an effective georef and is not the active entry:
   - activate it durably with framing;
   - base = its effective georef.
10. Load the draft for `contentKey#pageIndex`:
    - if it exists and differs from the saved seed, show the resume prompt (`calibration_resume_*`);
      - Resume uses the draft;
      - Start over deletes the draft and uses the seed.
    - If the draft is `active` (E3), resume without asking.
11. Seed, in priority order:
    - the draft;
    - otherwise the saved manual calibration (`datumId` + points);
    - otherwise no points.
12. If there is no datum (plain PDF, no seed), show the datum sheet first.
    - A GeoPDF preselects its embedded datum.

### 2.4 Phases
- `placing` (default; may have `selectedId`)
- `entering(pending: {page, editingId?})`
- `moving(id)`
- `datumSheet`
- `pointsSheet`
- `leaveDialog`
- `finishConfirm`
- `resumePrompt`

### 2.5 Mutations
- `add`, `move`, `editReference`, `delete`, `setDatum`.
- Each mutation, in order:
  1. pushes the previous state onto the undo stack (depth 50, memory only, cleared on session end);
  2. refits synchronously;
  3. recomputes the report;
  4. updates `displayedGeoref` (7.1);
  5. writes the draft synchronously (8.1).
- `undo` restores the previous state; refit and draft write follow.
- Point numbers are stable:
  - `number = nextNumber++`;
  - they are never renumbered;
  - a delete leaves a gap.
- Point delete needs no confirmation. It is undoable, and shows the `calibration_point_deleted` toast.

### 2.6 Finish
Finish is evaluated by `finishability` (6.6).

**`blocked`**
- The Finish button is disabled.
- The reason is always visible in the status line.

**`confirm(reasons)`**
- Show an alert: title `calibration_finish_confirm_title`, then one line per reason.
- Buttons:
  - [Add more points] (cancel role, default)
  - [Finish anyway] `calibration_finish_anyway`

**`ready`, or confirmed**
- Commit in ONE library write:
  - `entry.pdf.manual = {datumId, points, georef: fit, n, rmsM, grade}`;
  - the entry becomes the durable active selection.
- Then publish without reframing: the camera does not move.

**On success**
1. Delete the draft (best effort).
2. End the session and restore heading-up and chrome.
3. Show the toast `calibration_done` or `calibration_done_exact`.

**On failure**
- Stay in calibration; the draft is kept.
- Show an alert with `calibration_save_failed` and the buttons [Retry] and [Not now].
- Never a silent no-op (D2-09, D5-11).

### 2.7 Leave (the ✕ button at top-left; Android Back does the same)

**Not dirty** (state equals the start seed): end the session immediately.

**Dirty**: show a dialog titled `calibration_leave_title` with `calibration_leave_message` and these buttons:

| Button | Key | Effect |
|---|---|---|
| Keep points for later | `calibration_leave_keep` | draft `active = false` |
| Discard changes | `calibration_leave_discard` | destructive; deletes the draft |
| Continue calibrating | `calibration_leave_continue` | cancel |

On leaving, either way:
- **Preview**: restore the previous durable source, published without reframing.
- **Georeferenced entry**: rendering returns to its effective georef.

**Amendment 2026-10-02 (r1), C1:** "not dirty" still rewrites an existing draft with `active = false` before ending (add + Cancel, or add + undo, leaves a draft behind). No draft, nothing written. Pinned by `lifecycle.draftActive`.

### 2.8 Suspend
If the active map source changes to a different entry mid-session (for example a Retry of an older persistence alert):
- end the session and keep the draft (`active = false`);
- show the toast `calibration_paused`.

Commit re-checks that `entryId`, `contentKey` and `pageIndex` all match the target. On mismatch nothing is written (D5-10).

**Amendment 2026-10-02 (r1):**
- C1: suspend rewrites an existing draft `active = false` whether or not the state is dirty.
- C3: an unlock / Retry restore whose durable selection is unchanged does not republish while a calibration display (preview or refit) is up, so it is not a suspend. Only a real change of the durable selection suspends.

## 3. Constants (from `testdata/import_limits.json`; both platforms read it in tests)

### Calibration

| Constant | Value |
|---|---|
| maxCalibrationPoints | 50 |
| undoDepth | 50 (not persisted) |
| maxDrafts | 16, LRU by `updatedAt` |
| gpsMaxAccuracyM | 20 ("Use my position" is enabled only at ≤ 20 m; Android also requires Precise) |
| degenerateEigenRatio | 0.02 (WP1) |
| spreadMinFraction | 0.25 of pageBox width *or* height |
| tolerance τ | `max(10 m, 0.0005 × D)` |
| grade: Good | RMS ≤ τ |
| grade: Fair | RMS ≤ 3τ |
| grade: Poor | otherwise |
| implausibleAnisotropy | 1.5 (σmax/σmin of the page→plane linear part) |
| plausibleScale | 1:1,000 to 1:5,000,000 (see below) |
| offEarthLat | 85° |
| entryWarn | `d > max(3 × τ × sqrt(1+h), 50 m)` |
| residualLineMinScreen | 6 pt/dp |
| moveToCrosshairMinScreen | 4 pt/dp |
| markerHitRadius | 24 pt/dp |
| zoomHint | shown when on-screen size of 1 page pt < 1.0 screen pt/dp |
| cameraZoomRange | [2, 22] |
| transitionPrefetchDeadline | 400 ms |

Scale denominator = `sqrt(|det L|) / 0.00035277778` (metres per page pt ÷ metres per pt at 1:1).

### Import

| Constant | Value |
|---|---|
| pdfMaxBytes | 512 MiB (Android raised from 256) |
| mbtilesMaxBytes | 4 GiB (iOS raised from 2) |
| maxPages | 500 |
| georefScanPages | 50 (pages 0-49, plus the catalog /VP) |
| page side | 36 to 14,400 pt, for both CropBox and MediaBox |
| parseTimeout | 30 s |
| freeSpace | fileSize + 64 MiB |
| maxLibraryEntries | 100 |
| progressHudDelay | 300 ms |
| thumbnail | 120 pt/dp wide |
| thumbnail cache | LRU 24 |

**Amendment 2026-10-02 (r1), OD-F6:** byte values shown to the user (`map_import_too_large {limit}`, `map_import_no_space {size}`, the `map_library_footer {size}`) use the WP2 `bakeFormat` size rule (`pdf_tile_render.json`): 512 MiB reads "537 MB", 4 GiB "4.3 GB" (de "4,3 GB"). Pinned by `import_limits.json` `sizeDisplay` and `prechecks[].expect.argText`.

## 4. Coordinate entry (`CoordinateInputParser`; pure; the same cases pass on both platforms)

### 4.1 Normalisation
- Trim the input.
- Uppercase letters for grid input.
- Map `−` (U+2212) to `-`.
- Map primes `′ ’ '` to `'`, and `″ ” ''` to `"`.
- Collapse runs of whitespace.

### 4.2 Accepted forms, tried in this order

1. **Full MGRS**
   - Pattern: `zone(1-60) band(C-X, not I/O) col row digits`, with any spacing and any case.
   - Digits are 4, 6, 8 or 10 in total, split in half. Split groups must have equal length.
   - The 100 km column letter must belong to the zone's set:
     - zone%3==1: A-H
     - zone%3==2: J-R
     - zone%3==0: S-Z
     - (this is iOS `isSafeUTMMGRS`, ported to Android.)
   - The row must be A-V, excluding I and O.
   - The parsed south-west corner must fall within the stated band ±1 band. Otherwise `bandMismatch`.
   - E/N come from NGA `MGRS.parse().toUTM()`. Never go through WGS84 lat/lon.
2. **MGRS shorthand**: `digits` (`349522`, `349 522`) or `square + digits` (`LH 349 522`). Zone, band and square are completed as follows:
   - If `displayedGeoref` is not provisional:
     - predict the pending point's position with it, then compute the zone/band/square of that position in the sheet datum's UTM;
     - for digits-only input, try that 100 km square and its 8 neighbours, and pick the candidate whose E/N is nearest the prediction;
     - interpretation: `calibration_completed_from_map`.
   - Otherwise, take the zone/band/square from the most recent grid-type point (MGRS or UTM). Interpretation: `calibration_completed_from {number}`.
   - First point and no context: `needsFullReference`.
3. **UTM**: `ZZL E N`, optionally with `mE`/`mN`, where `L` is a band letter; or `ZZ north|south E N`.
   - Letter N or S: first try it as a band.
   - If the inverse latitude is not within that band ±0.5°, reinterpret it as a hemisphere.
   - Any other band letter that mismatches gives `bandMismatch`.
   - This makes our own readout `56S 334000mE 6250000mN` parse as the southern hemisphere.
4. **Decimal degrees**
   - Signed numbers, or N/S/E/W suffixes or prefixes. `O` means east (German).
   - `°` is optional.
   - Hemisphere letters may reverse the order (lon first).
   - German decimal comma is accepted when the two values are separated by `;`, or when both values use a comma and are separated by whitespace.
5. **DM and DMS**
   - `33°52.5'S 151°12.9'E`, `33°52'30"S 151°12'55"E`, `33 52 30 S 151 12 55 E`.
   - Space-only forms require hemisphere letters.

### 4.3 Errors (code → message key)

| Code | Message key |
|---|---|
| `empty` | (Save disabled, no text) |
| `unrecognised` | `calibration_err_unrecognised` |
| `tooCoarse` (0 or 2 digits; or feature + 4 digits) | `calibration_err_too_coarse` |
| `unequalDigits` | `calibration_err_odd_digits` |
| `needsFullReference` | `calibration_err_needs_full` |
| `invalidSquare(square, zone)` | `calibration_err_square` |
| `bandMismatch(band)` | `calibration_err_band` |
| `polarUnsupported` (UPS, or \|lat\| outside -80..84 for grid) | `calibration_err_polar` |
| `outOfRange` (\|lat\|>90, \|lon\|>180, min/sec ≥ 60) | `calibration_err_range` |
| `oldLettering(datum)` | `calibration_err_old_lettering` |

`oldLettering` applies to MGRS or MGRS shorthand when the sheet datum's ellipsoid is Clarke1866, Clarke1880IGN or Bessel1841: datums NAD27, NAD27_*, TOKYO, CH1903, NTF. UTM and lat/lon remain accepted for those datums.
- AL-lettering decoding is deliberately NOT implemented.
- A wrong decode shifts every point by the same ~1000 km, so the fit would look perfect.

### 4.4 Interpretation line (live, per keystroke; parse takes < 1 ms)
- The line shows `calibration_reads_as {canonical}`, plus a precision phrase, plus the datum display name.
- Canonical text is format-only and not localised:
  - `56H LH 34900 52200`
  - `UTM 55H 691000 6089000`
  - `33.85680° S, 151.21530° E`
- Precision phrase:
  - intersection: `calibration_cell_intersection {size}`
  - feature: `calibration_cell_feature {size}{half}`
  - UTM and lat/lon show no cell.

### 4.5 Kind segment [Grid intersection | Feature]
- Shown only for MGRS input with 4, 6 or 8 digits. The default is Intersection.
- Intersection uses the south-west corner of the cell, which is exact at a grid-line crossing.
- Feature uses the cell centre.
- Feature is not allowed with 4 digits (`tooCoarse`).

### 4.6 Search
Search behaviour does NOT change in this package: `search_contract.json` must pass unchanged.
- The new parser reuses these existing helpers:
  - iOS: `fullGridParts`, `isSafeUTMMGRS` and the decimal parser.
  - Android: `MgrsCoordinateResolver`, `parseDecimalCoordinate` and `partialGridResult`.
- Giving Search DMS and UTM input is a follow-up.

## 5. Reference resolution and fit plane

**Resolution** (every point is converted to lat/lon in the sheet datum):
- **Grid reference**:
  1. apply the kind offset (feature → cell/2 on E and N);
  2. TM inverse on the **sheet datum ellipsoid** (zone, south).
- **Geographic reference**: taken as sheet datum, unless `datumOverride == "WGS84"` (GPS points and migrated v1 points). In that case apply `datum.fromWGS84`.

**Plane zone.** Zone and hemisphere of the **lowest-numbered remaining point**:
- the grid zone, if that point was typed as grid;
- otherwise `floor((lon+180)/6)+1` and the sign of lat.
- There are no Norway/Svalbard exceptions (same rule as WP1).
- **Amendment 2026-10-02 (r1), A2/B1:** the zone is clamped to 1..60, so lon exactly 180 is zone 60 and -180 is zone 1 (`GeoCrs.standardUtmZone` on Android, `FiduciaryFitter.standardZone` on iOS). Never null, never 61. Pinned by `first_point_lon_180_zone_60` / `first_point_lon_minus_180_zone_1`.

**Residual and RMS.**
- Residual = plane distance in metres between `affine(page_i)` and `plane_i`.
- RMS = `sqrt(Σ r²/n)` (plain).
- `looRms_i` = RMS of the (n−1)-point fit on its own points. It is +∞ when that subset is degenerate.

## 6. Fit report (`CalibrationFitReport`; pure; pinned by `calibration_fit_report.json`)

### 6.1 Tolerance and diagonal
- **D** = the larger plane length of the two pageBox diagonals mapped through the fit (metres).
- **τ** = `max(10, 0.0005 × D)`. Example values:

| Sheet | D | τ |
|---|---|---|
| A1 page, 1:50k | ≈ 51 km | 25.7 m |
| 1:25k | — | ~13 m |
| small excerpts | — | 10 m floor |

Simulated at 0.5 pt placement, 3 m reading, 10 m scan warp: 100% of clean fits grade Good. With 1 pt placement and 20 m warp: 93-98%.

### 6.2 Grade
- n ≥ 4: Good / Fair / Poor, per section 3.
- n = 3: `exact` (unchecked, no grade).

### 6.3 Blocked (checked in this order, for n ≥ 3)
1. **`invalid`**: any of
   - the fit is non-finite or non-invertible;
   - any pageBox corner's `toWGS84` is nil or non-finite;
   - any corner has |lat| > 85°.
2. **`degenerate`**: eigen ratio < 0.02.
3. **`implausible`**: anisotropy > 1.5, or scale outside 1:1,000 to 1:5,000,000. This catches zone and square-letter typos (D2-09).

For n < 3 the state is `needMore(3−n)`.

**Amendment 2026-10-02 (r1), B3:** the order is `degenerate`, then `invalid`, then `implausible`. `invalid` can't be evaluated without the fit WP1 refuses for a degenerate set (`d2_10_collar_zone_typo_still_degenerate`).

### 6.4 Outliers (n ≥ 5 and RMS > τ only)
- Let S = { i : looRms_i ≤ τ } (the points whose removal makes the rest Good).
- |S| = 1: `outlier(number, loo_i)`, where loo_i is point i's distance to the (n−1) fit. Message `calibration_outlier`.
- |S| ≥ 2: `ambiguous(a, b)`, the two smallest looRms (ties go to the lower number). Message `calibration_ambiguous`.
- |S| = 0: `disagree(maxResidual)`. Message `calibration_disagree`.
- At n = 4 with RMS > τ: `disagreeAddFifth(maxResidual)`. Message `calibration_disagree_add_fifth`.

Simulated results:

| Case | Result |
|---|---|
| 1 km blunder, n = 6 | named correctly 94-100% |
| 1 km blunder, n = 5, random layouts | named 78-90%, otherwise ambiguous |
| 1 km blunder, n = 5, symmetric 4-corners + centre | ~20% named, ~80% ambiguous (the geometry cannot tell) |
| Any case | wrong point never named (0.0%) at 0.5 pt placement |

### 6.5 Spread
- `spreadLow`, for n ≥ 3: the points' page bbox spans < 25% of the pageBox width or height.
- `nextCorner`: shown while n < 3 or `spreadLow`.
  - Apply `/Rotate` to get the upright corners.
  - Inset the corners 10%.
  - Pick the corner that maximises the minimum page distance to the existing points. Ties go in the order top-left, top-right, bottom-right, bottom-left.
  - Messages `calibration_next_corner_{top_left|top_right|bottom_right|bottom_left}`.
- **Amendment 2026-10-02 (r1), B2/OD-F8:** the next-corner hint is the secondary line whenever `nextCorner` is set, n = 0 included (`expect.secondaryHint`). The zoom hint replaces it only while the crosshair is on the sheet; off the sheet there is no zoom hint.

### 6.6 Finishability
- `blocked(reason)`, from 6.3 or `needMore`.
- Otherwise `confirm(reasons)`, where the reasons are the non-empty subset, in this order, of:
  - `exact` (n = 3)
  - `poor` (grade Poor)
  - `outlier` or `ambiguous`
  - `spreadLow`
- Otherwise `ready`. A Fair grade or `disagree` alone is `ready`; the grade is shown but no confirm is asked.

### 6.7 Entry-time check (before a pending point is saved)

**Predictor, in priority order:**
1. When editing point k and the other points are ≥ 3 and non-degenerate: the fit without k, with leverage h of k's page point.
2. When n ≥ 3 and the fit is not blocked: the current fit, with h = x0ᵀ(AᵀA)⁻¹x0, where A has rows [x y 1] of the current points' page coordinates and x0 = [px py 1].
3. When the base georef is non-provisional (GeoPDF refine): the base, with h = 0.
4. Otherwise: no check.

**Check.**
- d = ground distance between the predicted WGS84 position and the typed reference's WGS84 position.
- Warn when `d > max(3 × τ_pred × sqrt(1+h), 50 m)`.
- UI: the warning line `calibration_warn_far {distance}`. The Save button becomes [Save anyway].

Simulated with 3 prior points: 0% false warnings. 1 km slips caught 81-100%, 300 m caught 42-98%. The constant thresholds the lens designs proposed (3τ/50, 6τ/200) false-warned on **5-33%** of correct 4th points; this rule replaces them.

## 7. Display, camera and input rules

### 7.1 Displayed georef
- `displayedGeoref` = the latest fit if n ≥ 3 and the fit is not blocked; otherwise the base (provisional, embedded or saved).
- It is never changed while the entry card is open.

### 7.2 Installing a new generation (prefetch, then an atomic swap)
When the session publishes `(georef, generation)`, the map layer:
1. computes the target camera with 7.3 against the *current* camera;
2. builds the new WP2 tile source and prefetches the tiles visible at the target camera, with a 400 ms deadline;
3. in one main-thread step, installs the new source, the new camera and `displayedGeoref = new`, and then renders.

A newer generation cancels an older, unfinished one.

No frame may draw the new georef with the old camera, or old tiles with the new camera. Stale tiles are NOT kept across a swap, because they would show the page offset by the georef delta.

Without WP2: plain swap plus camera set in one step. A brief dark placeholder is accepted.

### 7.3 Camera anchor (pure; `CalibrationCameraAnchor`)
1. `p* = old.toPage(camera.centre)`.
2. `newCentre = new.toWGS84(p*)`.
3. `s(g) = sqrt|det J|`, where J = ∂W/∂page at p* by central differences with h = 1 page pt, and W = the Web Mercator world coordinates (zoom-0, 256-unit world) of `g.toWGS84(page)`.
4. `newZoom = clamp(zoom + log2(s(old)/s(new)), 2, 22)`.
5. **Heading unchanged.**

If any step returns nil, the camera is left unchanged. The page point under the crosshair and the page's on-screen scale stay fixed.

### 7.4 Capture ("Add point" and "Set here")
- `page = displayedGeoref.toPage(liveCamera.centre)`, read at tap time from the live camera, not from a published or debounced value.
- The capture is accepted only if the page point lies inside pageBox. Otherwise the status line shows `calibration_off_sheet` and Add is disabled.
- **Zoom hint**: if `2^zoom × s(displayed)` < 1.0, show `calibration_zoom_hint` as the secondary line. It does not block.

### 7.5 Markers (drawn at `displayedGeoref.toWGS84(point.page)`, never at the typed position; D2-13)
- A "+" at the exact point, with a number badge offset up and right on a leader line, so the badge never hides the intersection.

| State | Drawing |
|---|---|
| placed | white "+", orange badge |
| selected | white ring |
| flagged | red badge with an "!" glyph (a glyph, not colour only) |
| pending | dashed ring |
| moving | a ghost at the old position |

- Residual line (n ≥ 3 and the displayed georef is the fit): from the marker to the screen position of the typed reference, when ≥ 6 pt/dp long.
- Tapping within 24 pt/dp of a marker selects it. A tap never places a point.

### 7.6 Reticle
- Replaces the normal crosshair while calibrating.
- 1 pt/dp white lines with a 1 pt black halo.
- A 6 pt/dp open gap at the centre, with no glow or ring, so the printed intersection stays visible.

### 7.7 Gestures and zoom
- Pan, pinch and rotate stay live (D2-01).
- Zoom buttons [+] and [−], 56 pt/dp each, on the right edge, change zoom by ±1 around the centre.
- Selecting a point flies to it at the current zoom and heading.

### 7.8 Header while calibrating
- The basemap label is `calibration_header_label`, in amber.
- **Provisional**: the coordinate block is replaced by `calibration_not_georeferenced` in amber. Distance, elevation and grid-magnetic are hidden, and drop-pin is disabled.
- **Otherwise**: the crosshair coordinate, plus the tag `calibration_preview_tag` whenever the displayed georef differs from the entry's saved effective georef.
- **Amendment 2026-10-02 (r1), OD-F15:** while provisional the hidden coordinate is hidden from VoiceOver/TalkBack too; the header reads `calibration_not_georeferenced` only.

### 7.9 Overlays while calibrating (effective visibility; persisted settings untouched)
- Hidden: symbology, drawings, all labels, presence peers, terrain heat-map, measure.
- The user-location dot is hidden while provisional.
- The MGRS grid is shown only when the calibration-local [Grid] toggle is on (default off) and the georef is not provisional. When on, it would sit exactly over the printed lines being targeted, hence the default.

### 7.10 Chrome hidden while calibrating
- Hamburger menu (Search, Layers, Import, Measure, Sync and the rest).
- Quick-add.
- Mission undo/redo.
- Lock button.
- Chat shortcut.
- Centre / My Location / Map pills.
- The long-press point menu.

The compass and the night-mode toggle stay.

## 8. Persistence

### 8.1 Draft (`CalibrationDraftStore`)

**Storage**
- One sealed file:
  - iOS: `Application Support/calibration-drafts.json`, label `calibration/drafts_v1`.
  - Android: `filesDir/calibration_drafts.json`.
- Shape: `{schemaVersion:1, drafts:{"<contentKey>#<pageIndex>": Draft}}`.
- `Draft = {contentKey, pageIndex, entryId, datumId?, points:[CalibrationPoint], nextNumber, pending?:{page, editingId?}, active:Bool, updatedAtMs}`.
- The typed text of an unsaved entry is not persisted.

**Writing**
- Written synchronously after every mutation, and at `beginAdd` (pending).
- Writes are never deferred. On Android the mission key is locked on every `onPause`.
- If the write fails (key locked), set `draftUnsaved`, show the `calibration_draft_unsaved` chip, and retry on unlock.

**Loading and removal**
- Load verifies that the embedded `contentKey` and `pageIndex` equal the key. On mismatch the draft is ignored and deleted (D5-10).
- The draft is deleted on:
  - Finish success
  - Discard
  - entry delete
  - reconcile, if its `contentKey` is not in the library
- Maximum 16 drafts, evicted by LRU.

**Amendment 2026-10-02 (r1), active flag** (C1, C5, C6; every step pinned by `lifecycle.draftActive`):
- `true` from `beginAdd` and every mutation, `cancelEntry`, `undo`, a Resume (prompt or E3, written at once), and through process death.
- `false` on Keep points for later, on any leave that ends the session with a draft present (dirty or not), and on suspend.
- The datum chosen on the s2.3 step 12 sheet at 0 points is part of the start seed: not dirty, no draft.

### 8.2 Library (`ImportedMapLibrary`; the ONE authority for active selection and entries)

**Storage**
- One sealed file:
  - iOS: `Application Support/imported-map-library.json`, label `map_library/v1`.
  - Android: `filesDir/imported_map_library.json`.
- Shape: `{schemaVersion:1, active: {kind:"online", style} | {kind:"entry", id}, preferredOnlineStyle, entries:[Entry]}`.

**Entry fields**
- `id` (UUID)
- `kind: pdf|mbtiles`
- `fileName`: an opaque path relative to the app storage root:
  - iOS: `ImportedMaps/map-<uuid>.pdf|.mbtiles`
  - Android: `pdf_maps/import-<16hex>.pdf`, `mbtiles/import-<16hex>.mbtiles`
  - baked tiles: `offline_tiles/...`
- `displayName`: the source file stem; stored only here, sealed.
- `contentKey`: `sha256:<64 lowercase hex>`, the existing format on both platforms. Optional only for migrated MBTiles.
- `byteCount`
- `fileModifiedAtMs`
- `importedAtMs`
- `derivedFromId?`
- `pdf?`:
  - `pageCount`
  - `pageIndex`
  - `rotate`
  - `pageBox`
  - `embedded: PdfGeoreference?`
  - `embeddedIssue?` (a WP1 reason code)
  - `manual?: ManualCalibration`, where `ManualCalibration = {datumId, points, georef, n, rmsM?, grade?, savedAtMs}`
  - `firstRenderPending` (reserved for WP2 D5-07)

**Derived state and effective georef**
- `mbtiles` → `offlineTiles` (or `derived` if `derivedFromId` is set).
- `pdf.manual` → `calibrated`.
- else `embedded` → `geoPDF`.
- else `embeddedIssue` → `rejected`.
- else `needsCalibration`.
- If the file is missing or size/mtime mismatch, `unavailable`, which overrides all of the above.
- Effective georef = `manual.georef ?? embedded`.
- Only `geoPDF`, `calibrated` and MBTiles entries can be the durable active selection.

**Commit points: every transition is exactly one sealed library write, followed by publication.**

| Transition | Library write |
|---|---|
| select online | `active = online(style)` |
| activate entry | `active = entry(id)` |
| import commit | add entry, plus `active` if the entry has a georef or is MBTiles |
| commit calibration | set `manual`; `active = entry` |
| revert to embedded (P1) | set `manual = nil` |
| change page (P1) | set `pageIndex`, `embedded`, `embeddedIssue`; `manual = nil`; draft deleted |
| delete | see below |

**Delete (D5-04, D5-19)**
1. Show confirmation `map_delete_title` / `map_delete_message`.
2. One write, which:
   - removes the entry and its derived entries;
   - sets `active = online(preferredOnlineStyle)` if any of them was active.
3. Publish online.
4. Close MBTiles handles.
5. Delete the draft.
6. Unlink the files, including `-wal`, `-shm` and `-journal` (best effort).
7. Reconcile.

A crash at any step only leaves orphans, which the next reconcile removes. There is no separate calibration library any more, so deleting an entry deletes its calibration by construction.

**Reconcile**
- Keep set = every entry's file and its SQLite sidecars, plus the in-memory **in-flight registry** (import and bake `.partial` files and not-yet-committed imports).
- Runs after every library write, and at launch once the library is Loaded.
- Never runs when the library is Locked or Corrupt, or while legacy state is still unmigrated.
- Importing B never deletes A (D5-03).

**Restore at launch**
1. Load the library.
2. Build the active entry's source using a size + mtime check only. There is no hashing on the main thread (D5-08).
3. Publish.
4. On a utility/IO queue, SHA-256 the active file once per launch.
   - On mismatch: mark it `unavailable`, publish online, and show `map_state_unavailable`.

**Migration (one-time, synchronous at first launch after upgrade; fail-closed; idempotent)**
- **Trigger**: the library is Empty and the legacy stores are readable.
- **Inputs**:
  - iOS: `ActiveMapSelectionStore` v1/v2, and `PDFSessionStore` active + `pdf_calibrations_v1` (as updated by WP1).
  - Android: `ActiveMapSelectionStore` active + retained, and `PdfSessionStore` `KEY_PDF` + `KEY_LIBRARY`.
- **Conversion**:
  - The retained or active imported map becomes an entry. Active is preserved only if that entry is `geoPDF`, `calibrated` or MBTiles.
  - A GeoPDF source becomes `embedded` (re-parsed through WP1 if the store is v1).
  - v1 fiduciaries become `manual`: points of type geographic with `datumOverride "WGS84"`, input text = the stored MGRS, `datumId WGS84`, refit through WP1.
  - A legacy camera-fallback entry becomes `needsCalibration`, and never active.
  - Calibrations in the legacy content-hash library for files that no longer exist are dropped (D5-19).
- **iOS files**:
  1. `linkItem` the old name to `ImportedMaps/map-<uuid>.<ext>` (copy if linking fails);
  2. write the library;
  3. only then clear the legacy stores and unlink the old names.
  - Android names are already opaque.
- If the previously active PDF became `needsCalibration`, show `map_migration_uncalibrated {name}` once.
- **Locked or corrupt legacy stores**: no migration, no deletion. The existing "saved basemap locked/unreadable" issue is shown.
- **Downgrade**: an older build sees no v2 selector and shows its recovery message. Nothing is deleted.

**Amendment 2026-10-02 (r1), library load and recovery** (pinned by `import_limits.json` `libraryLoad`):
- S1/D1 Load status. **Corrupt**: the file is present but won't decrypt, decode or has a newer schema (it is quarantined to `.corrupt-<epoch>`); or the file is absent while an `imported-map-library.json.corrupt-*` / `imported_map_library.json.corrupt-*` sibling exists; or the file is absent while the platform's sealed-store record says this path was written before (iOS `SealedMigrationPolicy.requiresSealed`; Android the same check if its sealed store keeps one). A vanished-after-written or quarantined library is Corrupt, never Empty. **Empty** only when none was ever written. The legacy stores follow the same rule: a quarantined legacy selector (iOS `ActiveMapSelectionStore.legacySnapshot` / `legacyPresent`) blocks migration, it never reads as "no legacy left".
- No reconcile, bake sweep or draft prune ever runs from a synthesized or reset state: only from a state read back as Loaded, a genuine first-launch Empty, or the result of a library write made from one of those. A `LibraryState()` standing in for Locked, Corrupt or a blocked migration drives nothing.
- S2 Retry on Corrupt rebuilds, it never deletes a user file. Every opaque map file in the import directories that no in-flight import owns (bake files `tacmap-bake-*` excluded) is re-adopted: contentKey re-hashed, `displayName = map_recovered_name {n}` (n in mtime order), PDFs re-inspected with s9.5 (marker + watchdog) and placed by the s9.6 first-valid-page rule without a picker; a PDF that fails inspection is adopted with `pageCount 0`, which is always `unavailable` (Delete only). MBTiles are validated, a failure is adopted as `unavailable` too. `active = online(default style)`. One library write; if it fails nothing changes and the alert stays. The sealed optional `recoveryPreservesOrphans` flag is set true on rebuild and retained by all later transitions; older libraries omit it (false). It disables reconcile, bake sweep and draft prune on recovery, cold launches, and later writes because lost ownership cannot authorize deletion. All drafts, generated bakes and map files beyond the recovery entry cap survive. Explicit Delete Map and Remove Offline Tiles still remove their known owned files; the `.corrupt-*` copy is kept. Alert copy `map_library_corrupt_message` with [Retry] [Not now]; import stays unavailable until Loaded.
- S3 Migration is fail-closed: a legacy PDF that is present but can't be read or converted (session/prefs won't open, hash IO fails, link/copy fails, document or page won't open, no relative path) blocks the migration. Nothing is written, cleared or deleted; the locked/unreadable issue with Retry shows. A legacy PDF whose file is gone is dropped (D5-19), that isn't a failure.
- S4 An authenticated legacy read that names nothing that still exists writes an empty library and clears the legacy stores (then it is Loaded). Locked forever is not an outcome.
- D8 Legacy stores still present at any Loaded restore are cleared, after the library write is durable; drafts are saved after the library write.
- 2026-10-02 handoff acceptance amendment: the S2 preservation rule above supersedes the earlier permission to sweep derived bakes after rebuilding. A successful recovery write does not prove ownership of unmatched files or drafts.
- F3 Locked at launch shows the `map_library_locked` issue with Retry; Retry and mission-data unlock re-run migration + restore (sweep included, both apps). The WP2 crash-guard launch decision is taken lazily at the first Loaded restore (as iOS); an in-progress marker is never cleared before a token is known.
- OD-F13 After the migrating launch the camera frames the migrated active map once (as iOS).
- D10 Downgrading to a pre-2.2 build loses imported maps (its own cleanup doesn't know the opaque files); they must be re-imported. Release notes + THREAT_MODEL say so; no code.

## 9. Import lifecycle

### 9.1 Stages and progress
- Stages: `copying(bytesDone/total)` → `reading(page i of n)` → `saving`.
- The progress card appears after 300 ms and has [Cancel].
- Import menu items are disabled while an import runs (D5-09). They are unreachable during calibration.
- **Cancel** stops within 1 MiB of copy or at the next page, removes partials, and shows the toast `map_import_cancelled`.

**Amendment 2026-10-02 (r1), stages** (pinned by `lifecycle.importPipeline`):
- E5 Order: pre-checks, copy + hash, dedupe (s9.4), inspect (s9.5), decision (s9.6), probe (M7), write. A duplicate skips inspect, decision and probe.
- E2 [Cancel] shows in `copying` and `reading` only, hidden in `saving` on both. The cancel flag is re-checked after the copy, after the inspection and after the probe; if set the copy is discarded, nothing is written and `map_import_cancelled` shows.
- OD-F5 Every s9.2 / s9.5 / M7 failure is an alert with [OK] on both (iOS style), never a toast. Cancelled and duplicate stay toasts. `map_import_page_used` shows after the write (E7).

### 9.2 Pre-checks
- Library Loaded; otherwise `map_import_unlock_first`.
- Entries < 100; otherwise `map_import_library_full`.
- Source size ≤ the limit; otherwise `map_import_too_large {limit}`.
- Free space ≥ size + 64 MiB; otherwise `map_import_no_space {size}`.

### 9.3 Copy
- One pass: copy + SHA-256 (the hash is the `contentKey`) + progress.
- Written to an opaque `.partial` that is registered in-flight, then renamed.
- No second full read.

### 9.4 Dedupe
If the `contentKey` already exists in the library:
- delete the copy;
- if the existing entry has a georef, activate it; otherwise start calibration on it;
- show the toast `map_import_duplicate {name}`.
- **Amendment 2026-10-02 (r1), E9:** if the existing entry is `unavailable`, the new copy has its exact contentKey, so it is kept: one write re-links the entry (fileName, byteCount, fileModifiedAtMs), then the old file goes; calibration and bake record stay, then the rule above (`decisions[].outcome.relink`).

### 9.5 Inspect the PDF (opened once, off the main thread)
- Watchdog: 30 s; on expiry `map_import_too_complex`. The worker is abandoned, because CGPDF and PDFBox cannot be interrupted.
- Checks, with their errors:
  - encrypted and not openable with an empty password → `map_import_password`
  - pages > 500 → `map_import_too_many_pages`
  - any page box outside 36 to 14,400 pt → `map_import_page_size`
  - unreadable → `map_import_invalid_pdf`
- WP1 georef for pages 0-49 and the catalog /VP.
- `/Rotate` is recorded and accepted, not rejected.
- Android catches `OutOfMemoryError` and `StackOverflowError` ONLY at the inspector boundary and maps them to `map_import_too_complex`, never to success (D5-15).

### 9.6 Page and outcome decision (pure `ImportDecision`; identical on both platforms)
1. **Some page has a valid georef.** Use the first such page, add the entry and activate it (framed).
   - If that page ≠ 0, show the toast `map_import_page_used {page}{pages}`.
2. **No valid georef, and some page has a declared-but-unusable georef.**
   - If there is exactly one page, or the only declaring page is the first: add the entry as `rejected` (not active) and show an alert with `map_import_georef_rejected {reason}` and the buttons [Calibrate now] [Later].
   - If there are several pages: go to the page picker. Pages with a declared georef carry the badge `map_import_page_badge_rejected`.
3. **No georef at all.**
   - 1 page: add the entry as `needsCalibration` and start calibration (E1).
   - More than 1 page: page picker (D5-13); after the user picks, add the entry and start calibration. Cancelling the picker deletes the copy and adds nothing.

The page picker holds the prepared copy in memory and in-flight. If the process dies before the user picks, the copy is an orphan that reconcile removes.

### 9.7 MBTiles imports
- Copy + hash + validate, then add a `mbtiles` entry and activate it.

### 9.8 Crash-loop breaker (import)
- A marker is written durably before WP1 or PDFBox parsing starts. iOS: `ImportedMaps/.import-inspecting`, containing only a UUID. Android: `inspectStartedAtEpochMs` in the copy journal.
- If the marker is found at launch: remove the partial and the marker, do not retry, and show `map_import_interrupted` once.
- A copy-phase interruption is retried as today (Android token replay). WP2 owns first-render D5-07.

### 9.9 Storage hygiene
- **iOS**:
  - `ImportedMaps` and `offline_tiles` are created with `completeUntilFirstUserAuthentication` (not `.complete`).
  - `isExcludedFromBackup` is set on both directories and on every file after create, rename or bake publish, and swept at launch (D5-14).
  - Opaque names.
- **Android**:
  - The picked URI is never written to plaintext prefs (D5-18). It is held in memory and in `savedInstanceState` only.
  - The legacy `pending_document_import_v1` prefs are read once and cleared.
  - Orphaned persisted URI grants are released at startup.
  - Backup is already disabled.

## 10. UI layout parity

**Bottom calibration panel** (inline, non-modal; the map stays live). Every target is ≥ 56 pt/dp.
- Line 1: a state glyph plus the primary status (≤ 2 lines), and a secondary hint line.
  - Glyphs: SF Symbol / Material equivalents of check, warning, error, info.
  - State is shown by glyph + word, never by colour alone (night mode maps colour to luminance).
  - Primary status priority:
    1. `calibration_off_sheet` when the crosshair is off-sheet
    2. intro (n = 0)
    3. blocked reason
    4. outlier / ambiguous / disagree
    5. fit summary or exact-fit text
    6. `calibration_need_points`
  - Secondary hint: zoom hint, or spread / next-corner hint.
  - The `draftUnsaved` chip appears above line 1.
- Line 2: [Undo], [datum chip showing the datum display name], [Grid toggle], [Points (n)].
- Line 3: [+ Add point] (primary, wide), [Finish].
- Selected state replaces line 3 with [Move], [Edit coordinate], [Delete], [Done].
- Moving state: status `calibration_moving {number}` and the buttons [Cancel], [Set here].

**Leave button**: ✕ floating at top-left, well away from Finish (D2-08).

**Zoom buttons**: [+] and [−] on the right edge.

**Entry card**: inline, docked at the TOP of the screen, so on the smallest phones the crosshair stays visible between the card and the keyboard. The map stays pannable. Contents:
- Title `calibration_entry_title {number}`.
- A large monospaced field with placeholder `calibration_entry_placeholder`. ASCII keyboard, all-caps, no autocorrect.
- The interpretation line, or the error line.
- The Kind segment (4.5).
- The far warning (6.7).
- [Use my position (±x m)], enabled only at ≤ 20 m accuracy.
- [Move to crosshair], shown when the crosshair is > 4 pt/dp from the pending marker.
- [Hide keyboard].
- A collapsed Label field.
- [Cancel] and [Save point] / [Save anyway].

**Points sheet**: a modal half-height sheet. The title `calibration_points_title`, then the fit summary, then the rows.
- Each row: `#number`, the canonical reference, a kind glyph, the residual (`calibration_row_residual`, or "—" at n = 3), and a status glyph:
  - check when ≤ τ
  - warning when ≤ 3τ
  - error when > 3τ or flagged
- Row tap selects the point, closes the sheet and flies to the point.

**Datum sheet**: a modal list with title and help text.
- Fixed order: WGS84, GDA2020, GDA94, NAD83, ETRS89, ED50, NAD27 (+ NAD27_CONUS_EAST/WEST/ALASKA/CANADA), OSGB36, SK42, TOKYO, CH1903, NTF.
- The last row is `calibration_datum_unsure`, which selects WGS84.
- A footnote shows `calibration_datum_national_grid_note`.
- Changing the datum is undoable and shows the toast `calibration_datum_changed`.

**Layers → Imported maps section** (replaces every retained/Unload/Delete-PDF control):
- Rows: a radio button for the active entry, the name, a state subtitle, and the size. Derived MBTiles rows are indented under their parent.
  - If a draft exists, the subtitle is `map_state_draft`.
- Row tap: activate the entry if it has a georef or is MBTiles; otherwise start calibration.
- A 48 pt/dp overflow menu:
  - Calibrate / refine… (PDF)
  - Choose page… (PDF, pageCount > 1; P1)
  - Generate offline tiles… (WP2)
  - Use the PDF's own georeferencing (only when manual and embedded both exist; P1)
  - Delete…
- Footer `map_library_footer`.
- If there are no entries, `map_library_empty`. If the library is locked, `map_library_locked`.

**Amendment 2026-10-02 (r1), panel and Layers:**
- A1/OD-F11 Datum names (chip, sheet rows, interpretation line, `calibration_datum_changed`, `calibration_err_old_lettering`) come from one table, `calibration_input.json` `display.datumDisplayNames`, not localised. Cell sizes in `calibration_cell_*` use `display.cellSizeText` ("1 km", "100 m"; never "1.00 km").
- OD-F4 Line 1 primary status fits 2 lines on a compact phone in both languages (de intro shortened).
- E1 Choose page asks `map_change_page_confirm` first when `pdf.manual` exists; picking the current page is a no-op. E4 if the picked page has a valid georef the entry is activated (framed); otherwise calibration starts on it, and if it was the active entry the change-page write also sets `active = online(preferredOnlineStyle)`. E14 that picker's message is `map_choose_page_message {pages}`; `map_import_choose_page_message` is for the import picker only. Pinned by `lifecycle.choosePage`.
- OD-F14 The footer `{size}` counts every entry's file plus its bake file.

## 11. Copy (localization/catalog.json)

Every key below has:
- `platforms: ["ios","android"]`, `legacy: false`
- a typed `accessor` (the camelCase of the key)
- a `context`
- named string `parameters`

German uses informal "du", German quotes „…“, and the glossary term Passpunkt. English says "point"; "fiduciary" is retired from the UI. Add a glossary row: `calibration point | Passpunkt | control point used for manual calibration`. After adding keys: generate → check → record reviews, following the `localization/README.md` workflow.

**New plural families**
- `calibration_point`: en one "%d point" / other "%d points"; de one "%d Passpunkt" / other "%d Passpunkte".
- `imported_map`: en one "%d map" / other "%d maps"; de "%d Karte" / "%d Karten".

**Reused existing keys**: Finish, Undo, Cancel, Delete, Retry, Not Now, OK. Distances are formatted with `DisplayFormat.distance`.

**Amendment 2026-10-02 (r1), formats and new keys:**
- OD-F12 `{rms}` (in `calibration_fit_summary`, `calibration_done`, `calibration_finish_confirm_poor`, `map_state_calibrated`): one decimal below 9.95 m ("0.4 m", de "0,4 m"), else `DisplayFormat.distance` ("26 m"). Pinned by `calibration_fit_report.json` `rmsDisplay`.
- `map_library_corrupt_message` — The saved map library couldn't be read. A recovery copy was kept and no map files were deleted. Tap Retry to rebuild the list from the maps on this device; their names and calibrations can't be restored. | Die gespeicherte Kartenbibliothek konnte nicht gelesen werden. Eine Wiederherstellungskopie blieb erhalten und keine Kartendatei wurde gelöscht. Tippe auf „Erneut versuchen“, um die Liste aus den Karten auf diesem Gerät neu aufzubauen. Namen und Kalibrierungen lassen sich nicht wiederherstellen.
- `map_recovered_name {number}` — Recovered map {number} | Wiederhergestellte Karte {number}
- `map_choose_page_message {pages}` — This PDF has {pages} pages. Choose the page with the map. | Dieses PDF hat {pages} Seiten. Wähle die Seite mit der Karte.
- `pdf_bake_confirm_message_inactive {name}{duration}` — “{name}” stays on this device and the map on screen doesn't change. This takes about {duration}. | „{name}“ bleibt auf diesem Gerät, die angezeigte Karte ändert sich nicht. Das dauert ca. {duration}.
- de `calibration_intro` is now: Lege das Fadenkreuz auf einen bekannten Punkt. Tippe auf „Punkt hinzufügen“.

### Calibration
- `calibration_header_label` — Calibrating | Kalibrierung läuft
- `calibration_not_georeferenced` — NOT GEOREFERENCED | NICHT GEOREFERENZIERT
- `calibration_preview_tag` — PREVIEW | VORSCHAU
- `calibration_intro` — Place the crosshair on a known point, then tap Add point. | Verschiebe und zoome die Karte, bis das Fadenkreuz genau auf einem Gitterkreuz oder einem eindeutigen Punkt liegt, und tippe dann auf „Punkt hinzufügen“.
- `calibration_add_point` — Add point | Punkt hinzufügen
- `calibration_points_button` — Points | Passpunkte
- `calibration_grid_toggle` — Grid | Gitter
- `calibration_zoom_in` / `calibration_zoom_out` / `calibration_close` (a11y) — Zoom in / Zoom out / Leave calibration | Hineinzoomen / Herauszoomen / Kalibrierung verlassen
- `calibration_need_points {placed}` — {placed} of 3 points placed. Spread them across the sheet. | {placed} von 3 Passpunkten gesetzt. Verteile sie über das ganze Blatt.
- `calibration_next_corner_top_left` — Next: add a point near the top-left corner. | Als Nächstes: Setze einen Passpunkt nahe der Ecke oben links.
- `calibration_next_corner_top_right` — (…) top-right corner. | (…) Ecke oben rechts.
- `calibration_next_corner_bottom_right` — (…) bottom-right corner. | (…) Ecke unten rechts.
- `calibration_next_corner_bottom_left` — (…) bottom-left corner. | (…) Ecke unten links.
- `calibration_off_sheet` — The crosshair is off the map sheet. | Das Fadenkreuz liegt außerhalb des Kartenblatts.
- `calibration_zoom_hint` — Zoom in to place the point precisely. | Zoome hinein, um den Punkt genau zu setzen.
- `calibration_max_points {max}` — You've placed the maximum of {max} points. | Du hast die Höchstzahl von {max} Passpunkten gesetzt.
- `calibration_exact_fit` — 3 points: exact fit, not checked. Add a 4th point to check accuracy. | 3 Passpunkte: exakte Anpassung, ungeprüft. Füge einen 4. Passpunkt hinzu, um die Genauigkeit zu prüfen.
- `calibration_fit_summary {points}{rms}{grade}` — {points} · RMS {rms} · {grade} | {points} · RMS {rms} · {grade}
- `calibration_grade_good` / `_fair` / `_poor` — Good / Fair / Poor | Gut / Mäßig / Schlecht
- `calibration_disagree_add_fifth {distance}` — Points disagree by up to {distance}. Add a 5th point so TacMap can find the wrong one. | Die Passpunkte weichen um bis zu {distance} ab. Füge einen 5. Passpunkt hinzu, damit TacMap den falschen findet.
- `calibration_outlier {number}{distance}` — Point {number} doesn't match the others ({distance} off). Check its figures, move it or delete it. | Passpunkt {number} passt nicht zu den anderen ({distance} Abweichung). Prüfe seine Ziffern, verschiebe oder lösche ihn.
- `calibration_ambiguous {first}{second}` — Point {first} or {second} is wrong. Add another point to find out which. | Passpunkt {first} oder {second} ist falsch. Füge einen weiteren hinzu, um herauszufinden, welcher.
- `calibration_disagree {distance}` — Points disagree by up to {distance}. Check the points with the largest error. | Die Passpunkte weichen um bis zu {distance} ab. Prüfe die Passpunkte mit der größten Abweichung.
- `calibration_degenerate` — The points are almost in a line or too close together. Add one well away from the others. | Die Passpunkte liegen fast auf einer Linie oder zu dicht beieinander. Setze einen weit abseits der anderen.
- `calibration_spread_low` — The points cover only a small part of the sheet. | Die Passpunkte decken nur einen kleinen Teil des Blatts ab.
- `calibration_implausible` — These points can't all be right: the map would be stretched or scaled impossibly. Check each point's zone and square letters. | Diese Passpunkte können nicht alle stimmen: Die Karte wäre unmöglich verzerrt oder skaliert. Prüfe bei jedem Passpunkt Zone und Quadratbuchstaben.
- `calibration_invalid` — These points put part of the sheet off the Earth. Check each coordinate, especially the grid zone. | Mit diesen Passpunkten läge ein Teil des Blatts außerhalb der Erde. Prüfe jede Koordinate, besonders die Gitterzone.
- `calibration_draft_unsaved` — Points not saved on this device yet. Unlock mission data to save them. | Passpunkte noch nicht auf diesem Gerät gespeichert. Entsperre die Einsatzdaten, um sie zu speichern.

### Entry card
- `calibration_entry_title {number}` — Point {number} | Passpunkt {number}
- `calibration_entry_placeholder` — MGRS, UTM or lat/long | MGRS, UTM oder Breite/Länge
- `calibration_kind_intersection` — Grid intersection | Gitterkreuz
- `calibration_kind_feature` — Feature | Geländepunkt
- `calibration_reads_as {text}` — Reads as {text} | Gelesen als {text}
- `calibration_cell_intersection {size}` — corner of the {size} square | Ecke des {size}-Quadrats
- `calibration_cell_feature {size}{half}` — centre of the {size} square (±{half}) | Mitte des {size}-Quadrats (±{half})
- `calibration_completed_from {number}` — Zone and square from point {number} | Zone und Quadrat aus Passpunkt {number}
- `calibration_completed_from_map` — Zone and square from the map | Zone und Quadrat aus der Karte
- `calibration_err_unrecognised` — Not a recognised MGRS, UTM or lat/long coordinate. | Keine erkannte MGRS-, UTM- oder Breiten-/Längenangabe.
- `calibration_err_too_coarse` — Too coarse. Use at least 4 figures at a grid intersection (e.g. LH 34 52), or 6 for a feature. | Zu ungenau. Gib an einem Gitterkreuz mindestens 4 Ziffern ein (z. B. LH 34 52), bei einem Geländepunkt mindestens 6.
- `calibration_err_odd_digits` — Enter the same number of easting and northing figures. | Gib gleich viele Ziffern für Rechts- und Hochwert ein.
- `calibration_err_needs_full` — For the first point, enter the full reference with zone and square, e.g. 56H LH 349 522. | Gib für den ersten Passpunkt die vollständige Koordinate mit Zone und Quadrat ein, z. B. 56H LH 349 522.
- `calibration_err_square {square}{zone}` — {square} isn't a valid 100 km square in zone {zone}. | {square} ist in Zone {zone} kein gültiges 100-km-Quadrat.
- `calibration_err_band {band}` — The northing isn't in latitude band {band}. Check the band letter. | Der Hochwert liegt nicht im Breitenband {band}. Prüfe den Bandbuchstaben.
- `calibration_err_polar` — Polar (UPS) references aren't supported. Enter latitude/longitude. | Polare (UPS-)Koordinaten werden nicht unterstützt. Gib Breite/Länge ein.
- `calibration_err_range` — Latitude must be within ±90° and longitude within ±180°, with minutes and seconds under 60. | Die Breite muss zwischen −90° und 90° und die Länge zwischen −180° und 180° liegen, Minuten und Sekunden unter 60.
- `calibration_err_old_lettering {datum}` — Sheets on {datum} use the older MGRS lettering, which TacMap can't read yet. Enter UTM or latitude/longitude instead. | Blätter im Datum {datum} verwenden die ältere MGRS-Beschriftung, die TacMap noch nicht lesen kann. Gib stattdessen UTM oder Breite/Länge ein.
- `calibration_warn_far {distance}` — This is {distance} from where the map currently puts the crosshair. Check the figures. | Das ist {distance} von der Stelle entfernt, an der die Karte das Fadenkreuz derzeit verortet. Prüfe die Ziffern.
- `calibration_save` — Save point | Punkt speichern
- `calibration_save_anyway` — Save anyway | Trotzdem speichern
- `calibration_use_gps {accuracy}` — Use my position (±{accuracy}) | Meine Position verwenden (±{accuracy})
- `calibration_gps_too_coarse {accuracy}` — GPS accuracy is ±{accuracy}. Wait for ±20 m or better. | Die GPS-Genauigkeit beträgt ±{accuracy}. Warte auf ±20 m oder besser.
- `calibration_move_to_crosshair` — Move to crosshair | Zum Fadenkreuz verschieben
- `calibration_hide_keyboard` — Hide keyboard | Tastatur ausblenden
- `calibration_label_placeholder` — Label (optional) | Bezeichnung (optional)

### Points, move, datum
- `calibration_points_title` — Calibration points | Passpunkte
- `calibration_row_residual {distance}` — {distance} off | {distance} Abweichung
- `calibration_action_move` — Move | Verschieben
- `calibration_action_edit` — Edit coordinate | Koordinate bearbeiten
- `calibration_action_done` — Done | Fertig
- `calibration_moving {number}` — Move point {number}: put the crosshair on the right spot, then tap Set here. | Passpunkt {number} verschieben: Bringe das Fadenkreuz auf die richtige Stelle und tippe auf „Hier setzen“.
- `calibration_set_here` — Set here | Hier setzen
- `calibration_point_deleted {number}` — Point {number} deleted. | Passpunkt {number} gelöscht.
- `calibration_datum_title` — Datum printed on the map | Kartendatum (auf der Karte angegeben)
- `calibration_datum_help` — Choose the horizontal datum shown in the sheet's margin or legend. The wrong datum can shift the map by 200 m or more. | Wähle das Lagedatum aus dem Kartenrand oder der Legende. Ein falsches Datum kann die Karte um 200 m oder mehr verschieben.
- `calibration_datum_unsure` — Not sure (use WGS84) | Nicht sicher (WGS84 verwenden)
- `calibration_datum_national_grid_note` — National grids such as the British National Grid can't be typed. Enter latitude/longitude instead. | Landeskoordinatensysteme wie das britische National Grid können nicht eingegeben werden. Gib stattdessen Breite/Länge ein.
- `calibration_datum_changed {datum}` — Datum changed to {datum}. All points were re-read. | Datum auf {datum} geändert. Alle Passpunkte wurden neu gelesen.

### Finish, leave, resume
- `calibration_finish_confirm_title` — Finish with warnings? | Mit Warnungen abschließen?
- `calibration_finish_confirm_exact` — With only 3 points a typing mistake can't be detected. | Mit nur 3 Passpunkten lässt sich ein Tippfehler nicht erkennen.
- `calibration_finish_confirm_poor {rms}` — The fit is poor (RMS {rms}). Positions read from this map may be wrong. | Die Anpassung ist schlecht (RMS {rms}). Von dieser Karte abgelesene Positionen können falsch sein.
- `calibration_finish_confirm_spread` — The points cover only a small part of the sheet, so its edges may be off. | Die Passpunkte decken nur einen kleinen Teil des Blatts ab, daher können die Ränder abweichen.
- Outlier and ambiguous reasons reuse `calibration_outlier` and `calibration_ambiguous`.
- `calibration_finish_anyway` — Finish anyway | Trotzdem abschließen
- `calibration_add_more` — Add more points | Weitere Passpunkte setzen
- `calibration_save_failed` — The calibration couldn't be saved securely. Your points are kept. Check storage, then tap Retry. | Die Kalibrierung konnte nicht sicher gespeichert werden. Deine Passpunkte bleiben erhalten. Prüfe den Speicherplatz und tippe dann auf „Erneut versuchen“.
- `calibration_done {points}{rms}` — Map calibrated · {points} · RMS {rms} | Karte kalibriert · {points} · RMS {rms}
- `calibration_done_exact` — Map calibrated with 3 points (unchecked). | Karte mit 3 Passpunkten kalibriert (ungeprüft).
- `calibration_leave_title` — Leave calibration? | Kalibrierung verlassen?
- `calibration_leave_message` — You can keep your points on this device and finish later. | Du kannst deine Passpunkte auf diesem Gerät behalten und später abschließen.
- `calibration_leave_keep` — Keep points for later | Passpunkte für später behalten
- `calibration_leave_discard` — Discard changes | Änderungen verwerfen
- `calibration_leave_continue` — Continue calibrating | Weiter kalibrieren
- `calibration_resume_title` — Resume calibration? | Kalibrierung fortsetzen?
- `calibration_resume_message {points}{age}` — An unfinished calibration of this map has {points}, last changed {age}. | Eine unvollständige Kalibrierung dieser Karte hat {points}, zuletzt geändert {age}.
- `calibration_resume` — Resume | Fortsetzen
- `calibration_start_over` — Start over | Neu beginnen
- `calibration_resumed {points}` — Calibration resumed ({points}). | Kalibrierung fortgesetzt ({points}).
- `calibration_paused` — Calibration paused because the map changed. Your points are kept. | Kalibrierung pausiert, weil die Karte gewechselt wurde. Deine Passpunkte bleiben erhalten.

### Library
- `map_library_section` — Imported maps | Importierte Karten
- `map_library_footer {maps}{size}` — {maps} · {size} on this device. Imported maps stay on this device until you delete them and aren't included in backups. | {maps} · {size} auf diesem Gerät. Importierte Karten bleiben auf diesem Gerät, bis du sie löschst, und sind nicht in Backups enthalten.
- `map_library_empty` — No imported maps. Use Import to add a PDF, GeoPDF or MBTiles map. | Keine importierten Karten. Füge über „Importieren“ eine PDF-, GeoPDF- oder MBTiles-Karte hinzu.
- `map_library_locked` — Unlock mission data to see and change imported maps. | Entsperre die Einsatzdaten, um importierte Karten zu sehen und zu ändern.
- `map_state_geopdf` — GeoPDF | GeoPDF
- `map_state_calibrated {points}{rms}` — Calibrated · {points} · RMS {rms} | Kalibriert · {points} · RMS {rms}
- `map_state_calibrated_exact {points}` — Calibrated · {points} (unchecked) | Kalibriert · {points} (ungeprüft)
- `map_state_needs_calibration` — Not georeferenced – tap to calibrate | Nicht georeferenziert – zum Kalibrieren tippen
- `map_state_rejected` — Georeferencing unreadable – tap to calibrate | Georeferenzierung nicht lesbar – zum Kalibrieren tippen
- `map_state_draft {points}` — Calibration in progress · {points} | Kalibrierung läuft · {points}
- `map_state_offline_tiles` — Offline tiles | Offline-Kacheln
- `map_state_derived {name}` — Offline tiles from “{name}” | Offline-Kacheln aus „{name}“
- `map_state_unavailable` — File missing or changed – delete it and import again | Datei fehlt oder wurde geändert – lösche sie und importiere sie erneut
- `map_action_calibrate` — Calibrate / refine… | Kalibrieren / verfeinern …
- `map_action_choose_page` — Choose page… | Seite wählen …
- `map_action_use_embedded` — Use the PDF's own georeferencing | Georeferenzierung des PDFs verwenden
- `map_action_delete` — Delete… | Löschen …
- `map_delete_title {name}` — Delete “{name}”? | „{name}“ löschen?
- `map_delete_message` — Removes the map file, its calibration and any offline tiles made from it from this device. Mission objects aren't affected. This can't be undone. | Entfernt die Kartendatei, ihre Kalibrierung und daraus erstellte Offline-Kacheln von diesem Gerät. Einsatzobjekte bleiben erhalten. Das kann nicht rückgängig gemacht werden.
- `map_delete_failed {detail}` — The map couldn't be deleted: {detail} | Die Karte konnte nicht gelöscht werden: {detail}
- `map_change_page_confirm` — Changing the page removes this map's calibration. | Beim Seitenwechsel wird die Kalibrierung dieser Karte entfernt.
- `map_use_embedded_confirm` — Remove your calibration and use the georeferencing stored in the PDF? | Deine Kalibrierung entfernen und die im PDF gespeicherte Georeferenzierung verwenden?
- `map_migration_uncalibrated {name}` — “{name}” was never georeferenced, so it's no longer shown as a basemap. Calibrate it from Layers. | „{name}“ war nie georeferenziert und wird daher nicht mehr als Grundkarte angezeigt. Kalibriere sie über „Ebenen“.

### Import
- `map_import_copying {percent}` — Importing map… {percent} | Karte wird importiert … {percent}
- `map_import_reading {page}{pages}` — Reading map… page {page} of {pages} | Karte wird gelesen … Seite {page} von {pages}
- `map_import_saving` — Saving map… | Karte wird gespeichert …
- `map_import_cancelled` — Import cancelled. | Import abgebrochen.
- `map_import_duplicate {name}` — Already in your maps as “{name}”. Opened it. | Bereits als „{name}“ in deinen Karten. Sie wurde geöffnet.
- `map_import_page_used {page}{pages}` — Using page {page} of {pages}, which has the map's georeferencing. | Seite {page} von {pages} wird verwendet, da sie die Georeferenzierung enthält.
- `map_import_georef_rejected {reason}` — This PDF has georeferencing TacMap can't use ({reason}). You can calibrate it with known points. | Dieses PDF enthält eine Georeferenzierung, die TacMap nicht verwenden kann ({reason}). Du kannst es mit bekannten Punkten kalibrieren.
- `map_import_calibrate_now` — Calibrate now | Jetzt kalibrieren
- `map_import_later` — Later | Später
- `map_import_choose_page_title` — Choose the map page | Kartenseite wählen
- `map_import_choose_page_message {pages}` — This PDF has {pages} pages and none has usable georeferencing. Choose the page with the map. | Dieses PDF hat {pages} Seiten, keine davon mit verwendbarer Georeferenzierung. Wähle die Seite mit der Karte.
- `map_import_page_label {page}` — Page {page} | Seite {page}
- `map_import_page_badge_rejected` — Unreadable map data | Kartendaten nicht lesbar
- `map_import_too_large {limit}` — This file is larger than the {limit} import limit. | Diese Datei überschreitet die Importgrenze von {limit}.
- `map_import_no_space {size}` — Not enough free storage to import this map ({size} needed). | Nicht genug freier Speicher für diese Karte ({size} benötigt).
- `map_import_password` — This PDF is password-protected. Remove the password, then import it again. | Dieses PDF ist passwortgeschützt. Entferne das Passwort und importiere es erneut.
- `map_import_too_many_pages {limit}` — This PDF has more than {limit} pages. Import a single sheet instead. | Dieses PDF hat mehr als {limit} Seiten. Importiere stattdessen ein einzelnes Blatt.
- `map_import_page_size` — This PDF's page size is outside what TacMap can display. | Die Seitengröße dieses PDFs liegt außerhalb dessen, was TacMap darstellen kann.
- `map_import_too_complex` — This PDF is too complex to read safely on this device. | Dieses PDF ist zu komplex, um es auf diesem Gerät sicher zu lesen.
- `map_import_invalid_pdf` — This file isn't a readable PDF. | Diese Datei ist kein lesbares PDF.
- `map_import_invalid_mbtiles` — This file isn't a readable MBTiles map. | Diese Datei ist keine lesbare MBTiles-Karte.
- `map_import_library_full {limit}` — You have {limit} imported maps. Delete one to import another. | Du hast {limit} importierte Karten. Lösche eine, um eine weitere zu importieren.
- `map_import_unlock_first` — Unlock mission data before importing a map. | Entsperre die Einsatzdaten, bevor du eine Karte importierst.
- `map_import_interrupted` — The last map import didn't finish and was removed. | Der letzte Kartenimport wurde nicht abgeschlossen und entfernt.
- `map_import_read_failed {detail}` — The map couldn't be imported: {detail} | Die Karte konnte nicht importiert werden: {detail}

### Retire (remove from catalog once unused)
- iOS calibration overlay and input-sheet strings: "Add fiduciary #{1}", "Tap a known feature…", "{1}/3 fiduciaries placed…", "Previous fit RMS…", "Calibrate with fiduciaries…", "Currently calibrated with {1} fiduciaries".
- Android: "Calibrate PDF Map", "Calibration RMS {1}m", "Fiduciary #{1}", "{1}/3 fiduciaries placed…", "Need at least 3 fiduciaries", "Tap inside the PDF map.", "Unload PDF Map", "Unload Offline Tiles", "This PDF is larger than TacMap's 256 MB import limit.", "TacMap could not read the first page…", "TacMap could not safely inspect this PDF's page rotation…".
- `pdf_rotation_unsupported`, once WP1 and WP2 support `/Rotate`.
- **Amendment 2026-10-02 (r1), L1:** done for every entry above that no code references (checked on both platforms), plus the old iOS input-sheet and Layers retained-map strings and the Android `layers_delete_imported_*` / `layers_pdf_fiduciary_count` / `calibration_use_current_location` / `calibration_collinear` / `calibration_failed` the merge left. The Android `AffineTransform2D` errors ("Need at least 3 fiduciaries" and its two siblings) are still referenced and stay. Legacy hash golden updated after proving no surviving legacy text changed.

## 12. Shared fixtures (generated, never hand-edited)

Generator: `scripts/gen_calibration_fixtures.py` (pyproj + the `mgrs` package + numpy, the same venv as `gen_test_geopdfs.py`). Both unit-test suites load the same files. The georef JSON shape is WP1's `pdf_georef.json` shape.

### `testdata/calibration_input.json` (about 70 cases)
- Shape: `{schemaVersion, tolerance:{metres:0.001, degrees:1e-9}, cases:[{id, input, context:{datumId, isFirstPoint, kind, gridAnchor?:{zone,band,square,fromPoint}, predicted?:{lat,lon}}, expect:{ok:{source, zone?, south?, band?, easting?, northing?, digits?, cellSizeM?, lat?, lon?, completedFrom?, canonical}} | {error, args}}]}`.
- Must include:
  - every accepted form in 4.2;
  - `56HAH3490052288` → `invalidSquare`;
  - `56HLH` and `56HLH34` → `tooCoarse`;
  - feature with 4 digits → `tooCoarse`;
  - shorthand with a map prediction straddling a 100 km square edge;
  - the band-S vs hemisphere-S cases, including our own `utm()` readout;
  - DMS with seconds = 60 → `outOfRange`;
  - German decimal comma with `;` and with whitespace;
  - `O` for east;
  - NAD27 and TOKYO MGRS → `oldLettering`; NAD27 UTM accepted;
  - UPS → `polarUnsupported`;
  - an odd digit split → `unequalDigits`.

### `testdata/calibration_fit_report.json`
- **`cases[]`**: `{id, datumId, pageBox, rotate, points:[{number, page, reference, kind, datumOverride?}], expect:{planeZone, south, n, diagonalM, toleranceM, rmsM, maxM, residualsM{number:m}, looRmsM{number:m}, grade|null, blocked?|confirm[]|"ready", outlier?, ambiguous?, disagreeM?, spreadLow, nextCorner?, anisotropy, scaleDenominator}}`. Tolerance 0.01 m, 1e-4 ratios. Must include:
  - D2-10 collar set (100,50), (550,53), (1000,49) → `degenerate`;
  - D2-05 ED50 `32U NA 00000 40000` resolved within 5 m of its WGS84 truth;
  - D2-03 1:50k UTM sheet at 45°N, n = 3 and 4, hold-out < 1 m;
  - 3-point exact;
  - clean n = 6 with 10 m warp → Good, no outlier;
  - a 1 km blunder at:
    - n = 4 → Poor + `disagreeAddFifth`
    - n = 5 symmetric → `ambiguous`
    - n = 6 → `outlier`
  - a zone typo → `invalid`; a square typo → `implausible`;
  - `spreadLow` + `nextCorner`;
  - a GPS override point;
  - a zone-boundary straddle;
  - deleting point 1 changes the plane zone.
- **`entryChecks[]`**: `{id, fromCase, editing?, pending:{page, reference}, expect:{predictor, leverage, distanceM, thresholdM, warn}}`.
- **`cameraAnchor[]`**: `{id, old:georef, new:georef, camera:{lat,lon,zoom,heading,viewport:[w,h]}, expect:{pagePoint, lat, lon, zoom, heading}}`. Tolerances 1e-9 deg and 1e-6 zoom. Cases: provisional→fit, fit→fit, a `/Rotate 90` page, and a clamp at zoom 22.
- **`capture[]`**: `{georef, pageBox, camera, expect:{page, onSheet, screenPtPerPagePt, zoomHint}}`.

### `testdata/import_limits.json`
- Every constant in section 3.
- `errors: {tooLarge, noSpace, password, tooManyPages, pageSize, tooComplex, invalidPdf, invalidMbtiles, libraryFull, locked, interrupted, cancelled, failed}`, each mapped to its message key.
- `decisions[]`: pure `ImportDecision` vectors of the form `{pageCount, pages:[{valid|rejected|none}], duplicate} → outcome`.

**Amendment 2026-10-02 (r1), new fixture sections** (both suites load and assert them):
- `calibration_input.json`: `display` {datumDisplayNames, cellSizeText, interpretationCells}; cases `utm_leading_zero_figures`, `dd_lon_exactly_180`, `dd_lon_exactly_minus_180`.
- `calibration_fit_report.json`: `rmsDisplay`; `cases[].expect.secondaryHint`; cases `first_point_lon_180_zone_60`, `first_point_lon_minus_180_zone_1`.
- `import_limits.json`: `lifecycle.draftActive`, `lifecycle.autoResume`, `lifecycle.suspectStart`, `lifecycle.choosePage`, `lifecycle.importPipeline`, `libraryLoad`, `sizeDisplay`; `prechecks[].expect.argText`; `decisions[].outcome.relink` + `duplicate_of_unavailable_entry`; entry state `recovered_uninspectable_pdf`.

### `testdata/pdf_georef.json` (WP1)
- The `fiduciaryFits` outlier field is aligned to rule 6.4.
