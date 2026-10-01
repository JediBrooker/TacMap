
# Shared WP2 contract: iOS and Android must match exactly

All constants below live in the new fixture `testdata/pdf_tile_render.json`, section `constants`. Both suites assert them.

## A. Tiles and the camera

- **Tiles:** standard XYZ, 256 logical units (pt/dp) per tile.
  - `tilePx = round(256 * min(displayDensity, 3) / 16) * 16`
  - Examples: 1.0→256, 1.5→384, 2.0→512, 2.625→672, 2.75→704, 3.0→768, 3.5→768.
  - A job pixel (u, v) (y down, origin at the top-left of tile (x0, y0)) maps to world 256-units as `wx = x0*256 + u*256/tilePx`, `wy = y0*256 + v*256/tilePx`. That point goes to lat/lon via spherical Web Mercator at zoom z, then through `georef.toPage`. This equals WP1's `tileWarp` convention.
- **Camera zoom limits:** 2...22 for every source. Pinch clamps only to these limits. Programmatic targets are clamped to the same range; iOS `flyTo` keeps its 2...19 region-fit heuristic.
- **Tile zoom:** `tileZoom = clamp(round(cameraZoom), source.minZoom, source.maxZoom)`. Above maxZoom, tiles are overzoomed by scaling.
- **Underzoom guard:** when `tileZoom - cameraZoom > 2`, nothing is requested or drawn for that source.

## B. Tile view (all raster sources)

- **Visible tiles:**
  - A tile is visible when its square, inflated by 1 pt/dp, intersects the rotated viewport rectangle (separating-axis test).
  - Columns stay unwrapped for layout; the index is wrapped modulo 2^z.
  - Tiles are ordered by the distance from the tile centre to the viewport centre.
- **Frames:** one grid origin per frame. `frame = origin + (col - col0, row - row0) * edge`. Tiles are drawn at exact float frames with antialiasing off and bilinear filtering, with no inflation and no rounding.
- **Fallback, per missing visible tile** (a tile that is neither loaded nor EMPTY):
  1. Use the nearest loaded ancestor, up to 4 levels up, or the source's `fallbackZoom(tz)` level if that is deeper. Draw its sub-rect: `unitRect = ((x mod 2^k)/2^k, (y mod 2^k)/2^k, 1/2^k, 1/2^k)`, y-down.
  2. Otherwise, draw its loaded children at z+1.
  - Draw order: ancestors (coarsest first), then children, then own tiles.
- **Requests, in priority order:**
  1. the fallbackZoom ancestors of missing tiles (deduped)
  2. the missing visible tiles, in visible order
  - Requests whose tiles leave the wanted set are cancelled.
  - Source completions are always asynchronous on the main thread.
- **EMPTY tiles** count as loaded and draw nothing.
- **Tile cache:** memory-only, sized in bytes by physical RAM.

  | Physical RAM | Cache |
  |---|---|
  | < 3.5 GiB, or Android `isLowRamDevice` | 64 MB |
  | < 6 GiB | 128 MB |
  | ≥ 6 GiB | 192 MB |

  - The cache clears when the source identity changes.
  - On a memory warning it is trimmed to the tiles in the current draw plan.
  - No rendered tile is ever written to disk. The explicit bake is the only disk output.

## C. PDF geometry and zoom policy

Page space is WP1's raw user space: y up, box origins included, /Rotate ignored.

**Clip polygon:**
- `clip = crop ∩ pageBox`, where pageBox = CropBox ∩ MediaBox. Compute it with Sutherland-Hodgman.
- Area < 1 pt², or any non-finite vertex, gives failure `pageGeometry`.
- Both renderers clip to it explicitly. CoreGraphics does not clip to the CropBox, and pdfium with a matrix may not either. The USGS crop extends past its page box.

**Footprint:** the clip polygon densified to 32 segments per edge, each vertex through `toWGS84`, then into Web Mercator z0 world units.

**Tile classification:**
- A tile is OUTSIDE if the footprint does not intersect the tile square; it becomes EMPTY with no render.
- Otherwise it is EDGE or INSIDE.
- The crop-intersecting tile set per z is pinned by the fixture.

**mercMetresPerPoint:**
- Let `c` be the mean of the clip polygon's vertices.
- Let `f(p) = (R*lon, R*ln(tan(pi/4 + lat/2)))` of `toWGS84(p)`, in radians, with `R = 6378137`.
- `J` = central differences with h = 1 pt.
- `mercMetresPerPoint = sqrt(|det J|)`.

**Zoom formulas:**
- `detailZoomRaw = log2(4 * 156543.03392804097 / mercMetresPerPoint)`. At this zoom, one pt/dp covers 1/4 PDF point.
- `detailZoom = clamp(ceil(detailZoomRaw - 0.05), 0, 22)`. This is the source's `maxZoom`; the source's `minZoom` is 0.
- `pxPerPt(z) = (tilePx / 256) * mercMetresPerPoint * 2^z / 156543.03392804097`.
- USGS, approximately (the fixture is authoritative): mercMetresPerPoint ≈ 10.71, detailZoomRaw ≈ 15.83 → 16. pxPerPt at 768 px: z12 ≈ 0.84, z13 ≈ 1.68.

**Base raster:**
- Region = the bbox of the clip polygon.
- `s = min(sqrt(budget / (w*h)), 4096 / max(w, h), 4.0)`.
  - Budget: 6,000,000 px normally; 3,000,000 px when RAM < 3.5 GiB or on a low-RAM device.
- Size: `W = max(1, ceil(w*s))`, `H = max(1, ceil(h*s))`. The mapping is region → [0, W] × [0, H] exactly.
- Mip chain: halve (ceil) until the long side is ≤ 256.
- `baseMaxZoom` = the largest z in [0, detailZoom] with `pxPerPt(z) ≤ min(W/w, H/h)`. Upsample tolerance is 1.0.
- USGS at 768 px with 6 Mpx: s ≈ 1.304, 2254 × 2663, baseMaxZoom 12. At 512 px: 13.

**Render path per job at zoom z:**
- `z ≤ baseMaxZoom`: sample the base raster. No PDF work. Use the smallest mip level whose density is at least the required density.
- Otherwise, if the warp plan has exactly 1 cell: direct vector draw.
- Otherwise: staged. Draw the page bbox of the non-OUTSIDE cells, intersected with the clip bbox, once at `min(1.25 × required, cap)`, where staged pixels ≤ 2 × job pixels. Then warp it per cell like a raster.
- `required` = the maximum over cells of the column norms of the pageToPx linear part.

## D. Warp planner (pinned by fixture `warp`)

**Root and depth:**
- Root = the job rect `[0, cols*tilePx) × [0, rows*tilePx)`, intersected with the footprint's pixel bbox (floor/ceil, expanded by 2 px).
- `maxDepth = 4 + ceil(log2(max(cols, rows)))`.

**For each rect [l, r) × [t, b):**
1. Evaluate page points at TL, TR, BL, BR and C = ((l+r)/2, (t+b)/2).
2. Build the affine px→page from TL, TR, BL: `P_TL + (P_TR - P_TL)(u - l)/w + (P_BL - P_TL)(v - t)/h`. Its inverse is `pageToPx`.
3. `err = max(|pageToPx(P_BR) - BR|, |pageToPx(P_C) - C|)` in output pixels.
4. Split into 4 at `mx = l + floor(w/2)`, `my = t + floor(h/2)` (order TL, TR, BL, BR) only while all of these hold:
   - `err > 0.25`
   - `depth < maxDepth`
   - `w ≥ 32` and `h ≥ 32`
5. A non-finite sample or a singular affine forces a split. At the limit, the cell is dropped and left transparent.

**Emitted cells:**
- Each cell is classified by its page quad against the clip polygon.
- OUTSIDE cells are skipped. INSIDE and EDGE cells are drawn.

**Drawing a cell:**
1. Integer clip to the cell rect.
2. Antialiased clip to the clip polygon.
3. Fill with paper white #FFFFFF.
4. Draw the page or the raster.

- Pixels off the clip polygon have alpha 0.
- The neatline must lie within 0.5 px of the projected clip polygon.
- Tiles classified OUTSIDE become EMPTY.

**Expected cell counts** (768 px, 1×1 jobs, all sheet types): z≥14 → 1; z12-13 → 4; z10-11 → 16; ≤ z9 → up to 64-256. The fixture pins the exact counts.

## E. Render scheduling (identical rules; platform lanes differ)

- **Bands:** VISIBLE, then FALLBACK, then BAKE. Within a band, jobs are ordered by squared distance from the job centre to the viewport centre.
- **Base raster band:** VISIBLE when `tz ≤ baseMaxZoom`; otherwise FALLBACK.
- **Vector jobs** (direct or staged) start only once the tile zoom has been unchanged for ≥ 150 ms.
- **Heavy flag:**
  - heavy when the EWMA (α = 0.3) of wall time per vector call is > 60 ms
  - light until the first sample exists
- **Live job formation:**
  - Light: a 1×1 job.
  - Heavy: start from the best pending VISIBLE tile. Grow greedily by adding, in order, one column to the right, one row down, one column to the left, then one row up. Accept an addition only if every tile in it is a pending VISIBLE tile at the same z. Limits: ≤ 6 tiles, ≤ 3 per side. Pinned by fixture `jobFormation`.
- **Bake jobs:**
  - heavy: aligned 3-column × 2-row blocks, `x0 = floor(x/3)*3`, `y0 = floor(y/2)*2`
  - light: 1×1
  - only intersecting tiles are written
  - a bake job never starts while any VISIBLE job is pending, and at most one bake job is in flight
- **Cancellation:**
  - A queued tile whose request is cancelled is removed.
  - A running job completes. Its tiles that are no longer wanted go to a per-source orphan cache of 8 tiles, which `loadTile` consults first.
- **Background and foreground:**
  - In the background, VISIBLE and FALLBACK dispatch stops. The bake continues.
  - While the app is in the foreground and a bake runs, the screen is kept on.
  - The bake pauses at critical/severe thermal state.

## F. Tile source contract (PDF)

`fallbackZoom(tz)`:
- with a valid bake: `min(tz - 1, bake.maxZoom)`
- else, once the base raster is ready: `min(tz - 1, baseMaxZoom)`
- else: none

Load order:
1. failed → nothing
2. OUTSIDE → EMPTY
3. orphan cache
4. bake tile. A missing row for an intersecting tile falls through to a live render.
5. `z ≤ baseMaxZoom` → base raster
6. otherwise → vector job

**Bake validity:**
- `tacmap_bake_key` equals the session `bakeKey`, where `bakeKey = sha256("tacmap-bake-v1|" + canonical georef JSON + "|" + tilePx + "|" + rendererVersion)`, and `rendererVersion = 1`.
- `tacmap_tile_px` equals the current tilePx.
- The key is compared on-device only.

## G. Render status

**States:**
- `none` (no PDF)
- `preparing`: from source creation until the base raster is ready or the first non-EMPTY tile is delivered
- `ready`
- `failed(reason)`: sticky; nothing is rendered until Try Again

**Failure reasons:** `cannotOpen`, `passwordProtected`, `pageMissing`, `pageGeometry`, `blank`, `outOfMemory`, `renderError`.
- `renderError` = 3 consecutive job failures.
- `blank` = sample every 4th pixel of the full-resolution base raster; blank when no sample has alpha > 0 and min(R, G, B) ≤ 250.

**Header label:**

| State | Label | Colour |
|---|---|---|
| preparing for ≥ 300 ms | "Drawing map…" | amber #FFC247 |
| failed | "Map couldn't be drawn" | red #FF5A5A |
| ready | "Offline basemap" | green #74E38A (existing) |

## H. Visibility

- Persisted boolean key `importedMapVisible` (iOS UserDefaults key `layers.importedMapVisible`), default true. The old iOS `layers.pdfOverlayVisible` is ignored.
- It is forced on while calibrating.
- When hidden: the PDF tiles are not drawn, the dark background shows (0x121212 / white 0.07), overlays stay, the cache is kept, and a capsule says "Imported map hidden".
- No online tiles are substituted.

## I. Crash-loop guard (pinned by fixture `crashGuard`)

**File:** a plaintext JSON file outside backups, written atomically with fsync. It holds only random UUIDs:

```
{"v":1,"inProgress":{"kind":"import|base|vector|bake","token":"<uuid>","op":"<opaque android operationKey, optional>"}|null,
 "suspect":"<uuid>"|null,"verified":{"<uuid>":["base","vector"]}}
```

At most 16 verified tokens are kept, least recently used first out. The token is a random UUID stored in the sealed session (`renderGuardToken`).

**Arming:**
- `import`: always, around the import probe (the base raster render inside the import worker, before the map is persisted).
- `base` and `vector`: only when that kind is not yet verified for the token and the app is in the foreground. Disarmed without verification on entering the background.
- `bake`: for the whole bake.
- On completion: add the kind to verified (import verifies base) and clear `inProgress`.

**Launch decision:**

| Condition | Decision |
|---|---|
| `inProgress.kind == import` | `importInterrupted` (Android abandons the matching pending-import replay) |
| `inProgress.kind == bake` | `bakeInterrupted` (clean the bake work directory) |
| `inProgress` base/vector, token == restored PDF token | `suppress`, and set `suspect = token` |
| `suspect == restored token` | `suppress` |
| otherwise | `none` |

`inProgress` is cleared after the decision.

**Suppress:** publish the default online basemap in memory only. The durable selection and the PDF are untouched.

**Resolution:**
- Open Anyway: clear `suspect` and publish the PDF.
- Delete Map…: the existing delete flow, then clear `suspect`.
- Not Now: keep `suspect`, so the next launch shows the alert again.

## J. Bake ("Generate Offline Tiles")

- **Options:**
  - Enabled only for non-provisional georefs.
  - With D = detailZoom, candidate maxZooms are {D-2, D-1, D}, clamped to 0..22.
  - `tiles(m)` = Σ over z = 0..m of the crop-intersecting tiles.
  - Drop candidates with `tiles > 6000`.
  - Default = D-1 if it survives, else the largest survivor. If none survive → `tooLarge`.
- **Estimates:**
  - Bytes = tiles × mean encoded bytes of up to 3 INSIDE tiles near the crop centre at the default maxZoom (cached or rendered) × 1.2.
  - An option is disabled when free space < 2 × its estimate.
  - Time = jobs × job EWMA + tiles × sampled encode time, shown as "about N min" (minimum 1).
- **Encoding (formats differ; files are device-local):**
  - iOS: JPEG q0.85 for fully opaque tiles, otherwise PNG.
  - Android: WebP lossy q85.
  - MBTiles metadata: `name`, `format`, `minzoom=0`, `maxzoom`, `bounds` (footprint bbox), `tacmap_bake_key`, `tacmap_tile_px`, `tacmap_renderer=1`.
- **Writing and publishing:**
  - Write to `<app support or filesDir>/pdf_bake_work/<uuid>.mbtiles.partial`, outside the reconcile roots. Commit every 64 tiles. SQLite `journal_mode=OFF`.
  - Publish = move to `offline_tiles/<uuid>.mbtiles`, then persist `bake` in the sealed session.
  - The active map never changes. The PDF is kept.
- **Invalidation:**
  - A georef change clears `bake`, and the file is reaped by reconcile.
  - Deleting the PDF cancels any running bake and deletes the PDF and its bake.
  - Switching the visible basemap does not cancel the bake.
- **States:** idle, estimating, confirming, running(done, total), failed(error). A finished bake returns to idle with a message.
- **Errors:** `notCalibrated`, `tooLarge`, `noSpace`, `writeFailed`, `renderFailed`, `sourceChanged`. `interrupted` comes only from the launch notice.

## K. Memory and lanes

- Base raster budget as in C.
- Orphan cache: 8 tiles.
- iOS: vector lanes = 2 when RAM ≥ 3.5 GiB, else 1; 1 at `.serious` thermal state or in Low Power Mode.
- Android: 1 pdfium thread.
- Staged buffer ≤ 2 × job pixels.

## L. User-facing strings

Identical English source strings, localized through each platform's existing pipeline (`%1$@` on iOS = `%1$s` on Android).

**Toggle and notices:**
- "Show Imported Map"
- "Imported map hidden"
- "Drawing map…"
- "Map couldn't be drawn"

**Failure alert:**
- Title: "Couldn't draw “%1$@”"
- Messages:
  - cannotOpen: "The PDF file is missing or can't be read."
  - passwordProtected: "This PDF is password-protected. Remove the password and import it again."
  - pageMissing: "The georeferenced page is missing from this PDF."
  - pageGeometry: "This PDF's page layout doesn't match its georeference."
  - blank: "Nothing on this PDF page could be drawn."
  - outOfMemory: "This PDF is too large to draw on this device."
  - renderError: "TacMap couldn't draw this PDF."
- Buttons: "Try Again", "Use Online Map", "Not Now".

**Crash recovery:**
- Title: "TacMap closed while drawing “%1$@”"
- Message: "The map wasn't opened automatically in case it causes the same problem. The online map is shown instead."
- Buttons: "Open Anyway", "Delete Map…", "Not Now".

**Import interrupted:**
- Title: "TacMap closed while preparing an imported map"
- Message: "The map wasn't imported. Try again, or print the PDF to a new file and import that copy."
- Button: "OK"

**Bake interrupted:**
- Title: "Offline tiles not finished"
- Message: "TacMap closed before the offline tiles were finished. Nothing was saved."
- Button: "OK"

**Bake: Layers section**
- Button: "Generate Offline Tiles…"
- Caption: "Pre-renders this map so it opens and zooms instantly. The PDF is kept."
- Disabled caption: "Calibrate this map before generating offline tiles."

**Bake: confirm**
- Title: "Generate offline tiles?"
- Option row: "Up to zoom %1$d · %2$@ tiles · about %3$@", with the suffix " · not enough free space" when disabled.
- Message: "“%1$@” stays on this device and remains the active map. This takes about %2$@."
- Buttons: "Generate", "Cancel".
- While estimating: "Estimating size…"

**Bake: progress and result**
- Running: "Generating offline tiles — %1$@/%2$@"
- Chip: "Offline tiles %1$d%" with "Cancel"
- Done: "Offline tiles ready"
- Layers info: "Offline tiles: zoom %1$d–%2$d · %3$@", with the button "Remove Offline Tiles"

**Bake: error alert**
- Title: "Offline tiles"
- Messages:
  - noSpace: "Not enough free space. About %1$@ is needed."
  - writeFailed: "The offline tiles couldn't be saved. Nothing was changed."
  - renderFailed: "Part of this map couldn't be drawn, so no offline tiles were saved."
  - sourceChanged: "The map changed while tiles were being generated. Nothing was saved."
  - tooLarge: "This map is too large to pre-render on this device."

**Remove:** "Bakes this calibrated map…" and "Couldn't generate tiles — calibrate the PDF first (3+ fiduciaries)."

## M. Flows

**Import:**
1. Parse (WP1).
2. Arm the import guard.
3. Render the base raster and run the blank check in the import worker.
4. Complete the guard.
5. Persist and select.
6. First paint is immediate.

**Launch:** decide the guard outcome, then restore (or suppress), then show any notice.

**Bake:**
1. Layers button.
2. Estimating.
3. Confirm with the zoom options.
4. Running: chip plus Layers row. It survives sheet dismissal and configuration changes.
5. Publish, or a typed error.

## N. Optional double-tap (D3-16)

Both platforms ship it or neither does:
- Double-tap: +1 zoom about the tap point, animated over 250 ms.
- Two-finger tap: -1 zoom about the centroid.
- Only in browse mode. Draw, measure and calibrate modes are unaffected.

## O. New shared fixtures

**`testdata/pdf_tile_render.json`:**
- Generated by the new `scripts/gen_tile_render_fixture.py`, using pyproj and numpy. It reads the sheets' crs, datum, affine and crop from `pdf_georef.json` and does not edit WP1's file.
- Loaded by iOS `SharedVectorsTests` and Android `SharedVectorsTest`, or by dedicated `PdfTileRenderFixtureTest(s)`.

| Section | Contents |
|---|---|
| `constants` | every number above |
| `tilePx` | density → px |
| `zoomPolicy[]` | per sheet id (sf_iso, rot5_iso, offset_iso, rot90_iso, cbr50k_iso, lcc_lgile, geog_iso, usgs_sf_north): clipPolygon, mercMetresPerPoint (1e-6 rel), detailZoomRaw (1e-6), detailZoom (exact), pxPerPt at z10..18 for tilePx 512/672/768 (1e-9 rel), basePlan normal/lowRam (region, W, H exact), baseMaxZoom per tilePx (exact) |
| `warp[]` | sheet, job (z, x0, y0, cols, rows), tilePx → cellCount (exact), maxErrorPx (≤ 0.25 asserted), cells (rect exact, pageToPx 1e-6 rel) for jobs fully inside the footprint; counts only for clipped jobs |
| `coverage[]` | sheet, z → count plus the sorted tile list (z ≤ 14) or sha256 of the list |
| `bakeOptions[]` | sheet → D, options (maxZoom, tiles), default |
| `jobFormation[]` | seed, pending set, heavy → job rect |
| `drawPlan[]` | visible tiles, cache states, fallbackZoom → items (source, dest, unitRect, order) and requests |
| `crashGuard[]` | event sequences → persisted state and launch decision |
| `cameraZoom[]` | pinch cases: start zoom and scale → result; underzoom cases |

**New synthetic PDFs** from the same generator:
- `testdata/geopdf/tacmap_render_markers.pdf`:
  - MediaBox [100 100 700 500], an inset CropBox, and /Rotate 90
  - ISO /VP georef
  - red 4 pt squares at known page points, whose expected tile px at 768 and 512 are listed in the fixture
  - a blue square in an optional-content group that is OFF by default. It must not be drawn.
- `tacmap_render_blank.pdf`.

Update the `testdata/README.md` table.
