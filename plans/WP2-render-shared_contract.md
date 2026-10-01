
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

**Amendment 2026-10-02 (G3): base raster and staged region, identical on both platforms.**
- Base raster: make a transparent W × H bitmap mapping region → [0, W] × [0, H]. Clip to the clip polygon, fill it with paper white #FFFFFF, then draw the page. Pixels outside the clip polygon stay alpha 0. The blank check (G) runs on this clipped, paper-filled full-resolution bitmap. Marks that are outside the clip polygon but inside its bbox never count. This is the current iOS `renderRegion` behaviour; Android `renderBase` must match it.
- Staged region (both platforms, this is the current iOS `stagedPlan`):
  1. `bbox` = the page bbox of every emitted (non-OUTSIDE) cell's page quad.
  2. `pad = 2 / required` pt. Grow the bbox by `pad` on all four sides.
  3. `region = paddedBbox ∩ clipBBox`. If it is empty, or has width or height ≤ 0, the staged draw is skipped.
  4. `cap = sqrt(2 × jobPixels / (rw × rh))`, `d = min(1.25 × required, cap)`, where `jobPixels = cols × rows × tilePx²`.
  5. `W = max(1, ceil(rw × d))`, `H = max(1, ceil(rh × d))`.
  6. `maxPx = floor(2 × jobPixels)`. While `W × H > maxPx` and `d > 0`: set `d = d × 0.99` and recompute W and H.
  - The staged bitmap therefore never exceeds 2 × job pixels, even after the ceil.

**Amendment 2026-10-02 (r1): staged region failures and exact evaluation (R5, D5; pinned by fixture `stagedRegion`).**
- Step 3 failures are `renderError` on both platforms, counted by G2: no emitted cell, an empty region, a region with width or height ≤ 0, or `required` ≤ 0 or non-finite. The staged draw is never turned into a paper-white success; Android drops its pad 0 fallback.
- Evaluate in doubles exactly as written: `pad = 2 / required`; `x0 = max(bx0 - pad, clipX0)`, `y0 = max(by0 - pad, clipY0)`, `x1 = min(bx1 + pad, clipX1)`, `y1 = min(by1 + pad, clipY1)`; `cap = sqrt((2 × jobPixels) / (rw × rh))`; each shrink step is `d = d × 0.99`.
- The G3 blank rule (marks outside the clip polygon but inside its bbox never count) is pinned by the `stagedRegion` blankCheck case on `geopdf/tacmap_render_blank_corner.pdf`: its import fails with `blank`.

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

**Amendment 2026-10-02 (E1): heavy EWMA scope and inputs.**
- There is one estimator per document session: one per open PDF render service / tile source for a document identity (contentKey + page). On iOS that is `PDFRenderService`; on Android it is the per-document `PdfRenderSession`/queue.
  - The live tile source, the bake and the bake estimate for the same document share that one estimator.
  - A newly opened document starts light, with no sample.
  - Android drops its process-global `PdfRenderExecutor` timer and heavy flag.
- Fed by: the wall time of every vector job (direct or staged) in any band (VISIBLE, FALLBACK, BAKE), including the bake-estimate sample jobs. Each job adds one sample. Wall time is measured from the start of the page draw on the render thread to the end of the warp, so queue wait is excluded.
- Not fed by:
  - a job whose tiles all came back EMPTY
  - a failed job, or a job cancelled before it started (r1)
  - any raster-sampled job (z ≤ baseMaxZoom)
- `heavy = EWMA > 60 ms` (strictly greater). The session is light while it has no sample.

**Amendment 2026-10-02 (r1): started jobs and timing (R2, R3).**
- R2: a job counts once it has started executing, whether or not anyone still waits on it. A started vector job that delivers an image feeds the EWMA even when every waiter has gone. A job cancelled before it started feeds nothing. A started job runs to completion (E, Cancellation). Pinned by the `failureAccounting` steps carrying `feedsEwma`.
- R3: the sample is the wall time of the page draw through the end of the warp only. It excludes the document/page open, the crash guard arm (and its fsync) and any base raster fetch. Android starts its timer inside `withPage`, after `open()` and after the guard arm.

**Amendment 2026-10-02 (E2): bake job formation (pinned by fixture `bakeJobFormation`).**
- Raster-sampled levels (`z ≤ baseMaxZoom`) always use 1×1 jobs, whatever heavy says. With no base raster (baseMaxZoom none), every level is a vector level.
- For vector levels (`z > baseMaxZoom`), heavy is read once, when the level starts, and that value is used for every job in the level:
  - heavy: aligned 3×2 blocks, clamped at the world edge
  - light: 1×1
  - A heavy flip in the middle of a level takes effect at the next level.
- A level's crop-intersecting tiles are walked row-major (y, then x). A job is formed at the first tile that no earlier job of that level already covers, and jobs are dispatched in that order.
- A job writes, and counts toward progress, only its crop-intersecting tiles.

**Amendment 2026-10-02 (E3): scheduler inputs.**
- Every wanted-tiles callback that is not ignored (see below) calls `setViewport(centre, tileZoom)`:
  - `centre` = the TileMath viewport centre unit of the camera, i.e. the rotated viewport's centre in world units. It is not the centroid of the requested tiles.
  - `tileZoom` = the source's tile zoom (A).
  - This call happens even when no tile at tz is requested.
- EMPTY results are never parked in the orphan cache. Only images are parked.
- While the PDF layer is hidden (H), or the underzoom guard (A) hides the source, wanted-tile callbacks are ignored on both platforms. Nothing happens: no setViewport, no settle restart, no re-banding and no base-raster kick. Request cancellation (B) is unaffected.

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

**Amendment 2026-10-02 (G1): step 1.** `failed` means no request is started and nothing is delivered. The result is never EMPTY: iOS returns a nil request, and Android returns `null` ("couldn't load"), not `TileSource.EMPTY`. The tile therefore stays missing and its fallback keeps drawing (B). Android must make sure this doesn't turn into a tight reload loop.

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

**Amendment 2026-10-02 (G1): what a sticky failure looks like.**
- The failed tile source stays attached to the tile view. iOS stops swapping the view's source to nil.
  - Already-cached tiles keep drawing.
  - Fallback ancestors and children found in the cache keep drawing.
- The failed source starts no render and delivers no new tile (F, step 1).
- Results of jobs that were still running when the failure landed are dropped.
- The header shows "Map couldn't be drawn" (red), and the failure alert offers Try Again. This is Android's current behaviour.
- Try Again builds a new source with a new identity. That clears the cache and resets the failure state and the run counter.
- "Nothing is rendered until Try Again" above means no new renders.

**Amendment 2026-10-02 (r3): the Layers failed row (OD2-R2-5).** On both platforms the Layers failed row shows "Map couldn't be drawn", then the reason line, then Try Again. The reason line uses the same `pdf_render_reason_*` catalogue entries as the failure alert's message (iOS behaviour; Android adds the line).

**Amendment 2026-10-02 (G2): failure accounting (pinned by fixture `failureAccounting`).** This applies to the live tile source only.

| Event | Effect |
|---|---|
| document open/parse fails: `cannotOpen`, `passwordProtected`, `pageMissing`, `pageGeometry` | sticky at once with that reason |
| the document-level blank verdict on the base raster | sticky at once, `blank` |
| a live job fails with `passwordProtected`, `pageMissing`, `pageGeometry` or `cannotOpen` (any path) | sticky at once with that reason |
| a live job fails with `outOfMemory` (an OOM exception or a failed bitmap allocation), on any path: vector, staged, base raster render, or base raster sample | counted |
| a live job fails with `renderError` or any other or unknown error, on any of the same paths | counted, as `renderError` |
| a live job succeeds on any path, with any mix of image and EMPTY tiles | run = 0 |
| a job cancelled before it started (r1) | no effect |
| bake jobs and bake-estimate sample jobs, success or failure | no effect (reported to the bake) |
| any event while already failed | ignored |
| Try Again | not failed, run = 0 |

- When the run reaches 3 consecutive counted failures, the source goes sticky with the reason of the 3rd failure: `outOfMemory` if that failure was outOfMemory, otherwise `renderError`.
- Entering failed also sets run = 0.
- The raster-sample path is counted on both platforms. On Android, exceptions from `loadTile`'s raster path are caught and counted; they must not escape.
- `blank` only ever comes from the document-level base raster check. A job never reports it.
- Note: the coordinator's list didn't place `cannotOpen`. It is treated as immediately sticky here because it is a document-level failure.

**Amendment 2026-10-02 (r1): base raster lifecycle and started jobs (R1, R2; pinned by fixture `failureAccounting`).**
- R2: a live job is counted once it has started executing, whether or not anyone still waits: it records jobOk or jobFailed in the run. A job cancelled before it started has no effect. iOS counts per started job, not per live waiter.
- R1, base raster:
  - Started once per tile source, at init or the first non-ignored wanted callback, at any z. Never re-started from a callback at z > baseMaxZoom.
  - Success: mark ready, record jobOk (it resets the run), ask the view to re-plan.
  - Blank verdict: sticky `blank`.
  - Failure (`renderError` or `outOfMemory`): counted once. It may be re-attempted only when a tile at z ≤ baseMaxZoom is wanted and no attempt is in flight; each attempt is counted. Document-level reasons are sticky at once, as above.
  - No half-budget OOM retry, and basePlan / baseMaxZoom never change mid-session. Android drops the halving.
  - Try Again builds a new source, so the base starts over.

**Amendment 2026-10-02 (r3): the bake's base raster request is its own (AND-R2-1).**
- A bake or bake-estimate base raster request is never adopted by the live source. The live source only acts on the results of base attempts it started itself under R1; it never picks up a base request some bake started as if it were its own attempt.
- A bake-started success or failure has no effect on the live lifecycle, its status, replan or G2. How the underlying render is shared is up to the platform: iOS dedupes one in-flight render per plan between waiters (a live attempt that joins it is still the live source's own attempt, at live priority); Android keeps separate live and bake slots, and the bake may reuse a base raster the live source already finished. Either way only attempts the live source started reach R1/G2.
- A bake-started base success or failure does nothing to the live lifecycle: no ready, no replan, no G2 event (bake jobs have no G2 effect, see the table above). A live attempt made afterwards may be answered at once from the service's finished base raster; that counts as that live attempt.
- A live attempt always waits at live priority, never at the BAKE band.

## H. Visibility

- Persisted boolean key `importedMapVisible` (iOS UserDefaults key `layers.importedMapVisible`), default true. The old iOS `layers.pdfOverlayVisible` is ignored.
- It is forced on while calibrating.
- When hidden: the PDF tiles are not drawn, the dark background shows (0x121212 / white 0.07), overlays stay, the cache is kept, and a capsule says "Imported map hidden".
- No online tiles are substituted.

**Amendment 2026-10-02 (J1): the toggle while calibrating.** On both platforms, "Show Imported Map" is shown on and disabled, so it isn't editable. Its stored value is untouched and comes back after calibration. This is Android's behaviour.

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

**Amendment 2026-10-02 (K1): the bake marker gets its own slot (pinned by fixture `crashGuard`).**

The file is still `"v":1`. A reader treats a missing `bakeInProgress` as null.

```
{"v":1,"inProgress":{"kind":"import|base|vector","token":"<uuid>","op":"<optional>"}|null,
 "bakeInProgress":{"token":"<uuid>"}|null,
 "suspect":"<uuid>"|null,"verified":{"<uuid>":["base","vector"]}}
```

- `arm(bake, t)`:
  - Always sets `bakeInProgress = {token: t}`, replacing any earlier bake marker (one bake at a time).
  - It never touches `inProgress`.
- `complete(bake, t)`:
  - Clears `bakeInProgress` only when its token is `t`.
  - Verifies nothing.
- `import`:
  - Arming still always replaces `inProgress`.
  - Neither `arm(import)` nor `complete(import)` ever reads or clears `bakeInProgress`.
- `base` and `vector`:
  - They arm under the old rules: in the foreground, not yet verified, and `inProgress` empty.
  - `bakeInProgress` does not block them, so first renders are guarded during a bake too.
- `disarmBackground` never touches `bakeInProgress`.
- `launch` returns `{decision, bakeInterrupted, op?}`:
  - `decision` is the first matching row of the launch table, with the bake row removed: `importInterrupted` (with `op`), then `suppress` for a base/vector marker, then `suppress` for `suspect`, then `none`.
  - `bakeInterrupted = bakeInProgress != null`, or a legacy `inProgress.kind == "bake"` written by an older build. When it is true, clean the bake work directory.
  - Both values are reported, so an interrupted import and an interrupted bake both show their notices. Show the decision's alert first, then "Offline tiles not finished".
  - `inProgress` and `bakeInProgress` are both cleared after the decision.
- A crash at any point during a bake, including during an import that overlapped it, yields `bakeInterrupted` on the next launch.

**Amendment 2026-10-02 (r1): the estimate arms the bake slot, and the verified order (F3, OD-F8).**
- F3: the bake estimate (J2) arms `bakeInProgress` too: `arm(bake, t)` before its first sample render, with the same token the bake uses, and `complete(bake, t)` when the estimate ends, fails or is cancelled. Generate then arms again for the bake. A crash mid-estimate gives `bakeInterrupted` on the next launch.
- OD-F8: the K1 schema has no `order` key. The verified LRU order (the fixture's `verifiedOrder` / `initialVerifiedOrder`) is the member order of the `verified` object, oldest first, which is what Android writes and reads. iOS stops writing `order`, writes `verified` oldest first, and ignores `order` when it reads a file from an older build. `JSONSerialization` loses member order, so iOS needs an order-preserving read of that one object.

**Amendment 2026-10-02 (H1): suppress.** The suppress path and the failure alert's "Use Online Map" both publish the user's preferred online basemap style, in memory only. That is the style persisted with the active-map selection (Android `preferredOnlineStyle`). The built-in default style is used only when no style is stored. This is Android's behaviour; iOS stops using `OnlineRasterBasemapSource.makeDefault()` for these paths. The durable selection and the PDF are untouched, as before.
  - Clarification (iOS implementation, 2026-10-02): "in memory only" applies to the suppress path. Android's "Use Online Map" is `restoreOnlineBasemap()`, a normal durable selection of the preferred style. iOS matches that: it selects the preferred style durably, as it already selected the default style durably before. iOS keeps the preferred style in the sealed selection file (`preferredOnline`, written on every online selection, read with the active online style as a fallback for older files).

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
  - iOS: JPEG 2000 q0.9 for fully opaque tiles, otherwise PNG, see the J3 table. It was JPEG q0.85 until the 2026-10-02 J3 measurement.
  - Android: WebP lossless (effort 100, raised from 85 in r2 for OD-F3), see the J3 table. It was WebP lossy q85 until the 2026-10-02 J3 measurement.
  - MBTiles metadata: `name`, `format`, `minzoom=0`, `maxzoom`, `bounds` (footprint bbox), `tacmap_bake_key`, `tacmap_tile_px`, `tacmap_renderer=1`.
- **Writing and publishing:**
  - Write to `<app support or filesDir>/pdf_bake_work/<uuid>.mbtiles.partial`, outside the reconcile roots. Commit every 64 tiles. SQLite `journal_mode=OFF`.
  - Amendment 2026-10-02 (r2): `OFF`, or `MEMORY` where the platform SQLite refuses OFF (iOS system SQLite runs in defensive mode and answers `delete`). The writer reads back the PRAGMA result and fails the bake if neither OFF nor MEMORY took. Either way nothing journal-like is written next to the `.partial`.
  - Publish = move to `offline_tiles/<uuid>.mbtiles`, then persist `bake` in the sealed session.
  - The active map never changes. The PDF is kept.
- **Invalidation:**
  - A georef change clears `bake`, and the file is reaped by reconcile.
  - Deleting the PDF cancels any running bake and deletes the PDF and its bake.
  - Switching the visible basemap does not cancel the bake.
- **States:** idle, estimating, confirming, running(done, total), failed(error). A finished bake returns to idle with a message.
- **Errors:** `notCalibrated`, `tooLarge`, `noSpace`, `writeFailed`, `renderFailed`, `sourceChanged`. `interrupted` comes only from the launch notice.

**Amendment 2026-10-02 (r1): metadata name and publish results (F2, R6).**
- F2: MBTiles metadata `name` is always the neutral `"TacMap offline tiles"` (`constants.bake.mbtilesName`), never the PDF display name. Both platforms.
- R6: `attachBake` returns `attached`, `sourceChanged` or `writeFailed` on both. A seal or commit failure of the session is `writeFailed`; a full disk goes through the noSpace classifier first. `sourceChanged` only when the session no longer matches the bake.
- R6: MBTiles writers keep the FIRST error code. A later error (say BEGIN after `SQLITE_FULL`) never replaces it (iOS change).

**Amendment 2026-10-02 (J1): confirm flow, the same on both (Android's flow).**
- Layers → "Generate Offline Tiles…" → a modal "Estimating size…" with Cancel → the confirm dialog.
- The confirm dialog is titled "Generate offline tiles?". It has one radio row per kept option, then Generate and Cancel. Tapping a row only selects it; Generate starts the bake.
- The message's "about N min" is `minutes` of the selected option, so it follows the selection.
- Each row shows the option's `bytes`. A row with `enoughSpace == false` is disabled, can't be selected, and carries the " · not enough free space" suffix.
- Initial selection and the Generate button follow the J2 rules below. The confirm is always shown, even when nothing fits. iOS drops its estimate-time noSpace failure.
- Once a bake exists, Layers shows only the info row and "Remove Offline Tiles". "Generate Offline Tiles…" is hidden until the bake is removed.
- The done message ("Offline tiles ready") is a transient platform notice: a Toast on Android, a capsule on iOS.

**Amendment 2026-10-02 (r1): bake UI parity (OD-F7, OD-F9; pinned by fixture `bakeFormat`).**
- OD-F9: "Generate Offline Tiles…" is disabled while the PDF source is failed (sticky G1). The failed row's Try Again is the way out.
- OD-F7, estimating step (J1, Android): title "Generate offline tiles?", body "Estimating size…", with Cancel.
- OD-F7: the confirm message sits above the option rows on both.
- OD-F7: tile counts and sizes come from one pure function per platform:
  - tile counts are locale-grouped integers (en `1,646`, de `1.646`).
  - sizes: MB = 10^6 bytes, the decimal unit `ByteCountFormatter(.file)` and `Formatter.formatShortFileSize` both use today. One decimal below 100 MB, whole MB at or above, GB with one decimal at or above 1000 MB. Rounding is integer half-up, a size > 0 shows at least 0.1 MB, one U+0020 space before `MB`/`GB`. The fixture's `bakeFormat.rules` has the exact steps.
- OD-F6: de "{1} min" (unit symbol, no full stop), so "Das dauert ca. 1 min." ends with one stop.

**Amendment 2026-10-02 (r3): separators and Remove Offline Tiles (PAR-R2-1, R2-S2).**
- PAR-R2-1: the grouping and decimal separators in tile counts and sizes follow the app UI language, never the device region or locale. en: grouping `,`, decimal `.` (`1,646`, `1.5 MB`). de: grouping `.`, decimal `,` (`1.646`, `1,5 MB`). With the app on "system", the language the app actually resolved (en or de) decides; any other device language falls back to en. Android stops using the device locale's `DecimalFormatSymbols`. Both platforms test it under a device locale that is neither en nor de (for example de-CH, fr-FR).
- R2-S2: "Remove Offline Tiles" clears the bake record in the sealed session first, then deletes that bake file and its `-journal`, `-wal` and `-shm` sidecars directly: only plain files (no symlinks, no directories) directly inside `offline_tiles`, under the managed-files lock the reconcile and the bake publish also take. It does not wait for the reconcile, so it works while the stored PDF is missing (when the reconcile deletes nothing). If the record can't be persisted, nothing is deleted. The reconcile itself is unchanged.

**Amendment 2026-10-02 (r4): bake-only sweep and Remove prefix (R3-2, R3-5).**
- Remove only deletes a file whose name is `tacmap-bake-<non-empty>.mbtiles`.
- A bake-only sweep runs after Remove and at every launch (iOS: at the start of every active-map restore, i.e. launch, unlock and Retry). It deletes regular, non-symlink `tacmap-bake-*.mbtiles` files and their `-journal`/`-wal`/`-shm` sidecars directly inside `offline_tiles` that the sealed session's bake record does not name, under the managed-files lock the bake publish takes, reading the record inside the same lock. No recursion.
- The sweep runs only on an authoritative read of the sealed session: a decoded record (a bake record the app would not have written, per the shared validBake rule, names nothing, and is likewise ignored by restore and the reconcile keep set) or no record while neither the active nor the retained selector is a PDF or unavailable. Locked or unreadable store, undecodable or legacy plaintext record, or no session record while the active or retained selector is, or may be (unavailable), a PDF: skip, delete nothing.
- It does not depend on the stored PDF being present. The managed-file reconcile is unchanged.

**Amendment 2026-10-02 (J2): the bake estimate (pinned by fixture `bakeEstimate`).** Each platform implements it as one pure function. Only the samples and the free-space read touch the outside world.

*Option tiles.*
- Count crop-intersecting tiles for z = 0, 1, … up to the largest candidate. Each level is counted with the limit `6000 - runningTotal` and returns early once it passes that limit (iOS `tiles(z:limit:)`).
- As soon as the running total passes 6000 at some level z, stop counting:
  - every candidate m ≥ z is dropped
  - every candidate m < z keeps its exact `tiles(m)`
  - This gives the same result as `bakeOptions`.
- Android: `level()`/`count()` take that limit and return early, use primitive arrays, and check `ensureActive` between levels.

*Samples.*
- `z` = the default maxZoom (J options).
- The pool is the INSIDE tiles at z. If there are none, it is every crop-intersecting tile at z.
- Centre: `toWGS84(clipMean)` goes to Web Mercator z0 units (X0, Y0), then `cx = X0 × 2^z / 256` and `cy = Y0 × 2^z / 256`.
- Rank the pool by `d2 = (x + 0.5 - cx)² + (y + 0.5 - cy)²` ascending, with ties broken by y and then x. Take the first 3, or fewer if the pool is smaller.
- If `toWGS84(clipMean)` fails, take the first 3 of the pool in row-major order.
- Each sample runs as a 1×1 BAKE-band job through the bake path: base-raster sample if z ≤ baseMaxZoom, vector otherwise. Vector samples feed the EWMA (E1).
  - `jobMs` = the E1 timing: page draw through the end of the warp, excluding open, guard arm and the base raster fetch (r1 R3, replaces "wall time from dispatch to result").
  - The image is then encoded with the bake encoder: `encodeMs` is the encode wall time, `bytes` the encoded size.
- A sample counts only when its job delivered an image and the encode succeeded. EMPTY, failed and encode-failed samples are skipped. Cancellation aborts the estimate.

*Formula.* All arithmetic is IEEE double, evaluated left to right as written. `n` = the number of counted samples.
- `jobMs`:
  - the session EWMA, read after the samples ran, if it has at least one sample
  - otherwise, if n > 0, `Σ jobMs / n` over the counted samples
  - otherwise **100**
- `encodeMs` = `Σ encodeMs / n` if n > 0, otherwise **5**.
- `meanTileBytes` = `Double(Σ bytes) / n` if n > 0, otherwise **250000** (raised from 20000 by the J3 measurements below, first to 150000 for Android, then to 250000 for iOS; the fixture's `bakeEstimate.fallback.tileBytes` is the authority).
- `heavy` = the EWMA has a sample and `EWMA > 60`.
- For each kept option m with `tiles(m)`:
  - `jobs(m)` = Σ over z = 0..m of the number of jobs E2 forms for level z, using `heavyAtLevelStart = heavy` for every level. Raster levels are therefore 1 job per tile.
  - `bytes(m) = ceil(Double(tiles(m)) × meanTileBytes × 1.2)`. This rounds up, so Android stops truncating.
  - `neededBytes(m) = 2 × bytes(m)`, an integer.
  - `enoughSpace(m)` = free space unknown, or `freeBytes ≥ neededBytes(m)`. Free space is iOS `volumeAvailableCapacityForImportantUsage` of Application Support, or Android `StatFs(filesDir).availableBytes`. If it can't be read, it is unknown.
  - `estimatedMs(m) = Double(jobs(m)) × jobMs + Double(tiles(m)) × encodeMs`. There is no separate raster term; iOS drops its `rasterTiles × meanRender × 0.25`.
  - `minutes(m) = max(1, ceil(estimatedMs(m) / 60000))`, shown as "about N min".
- Initial selection:
  1. the default option, if `enoughSpace`
  2. else the largest-maxZoom option that has `enoughSpace`
  3. else the default
- Generate is enabled iff the selected option has `enoughSpace`.
- Errors:
  - The estimate itself failing (the page won't open, a context error) gives `renderFailed`.
  - No kept candidate gives `tooLarge`.

*Amendment 2026-10-02 (r1): estimate failures and timing (R3, R4, F3).*
- R3: Android's estimate awaits the base raster before the sample loop, so no sample's `jobMs` includes the base fetch.
- R4: a document-level failure in any sample (`cannotOpen`, `passwordProtected`, `pageMissing`, `pageGeometry`, or a blank base raster) fails the whole estimate as `renderFailed` on both. Other sample failures are skipped, and the fallbacks apply if none count. iOS `measure()` checks `raster.blank` and the document-class errors.
- F3: the estimate runs under the K1 bake slot (I, r1).

*No space while baking.* `SQLITE_FULL`, `ENOSPC`, Android `SQLiteFullException`, or an IOException carrying ENOSPC, raised while writing, committing, moving or fsyncing, map to `noSpace(neededBytes(selected option))` on both platforms. iOS stops reporting the current free bytes. Any other write-path or filesystem error, and any unclassified error, gives `writeFailed`. Render and encode errors give `renderFailed`.

**Amendment 2026-10-02 (J3): bake encoding quality gate.**
- Both suites gate at PSNR ≥ **35.0 dB**. PSNR here is RGB, 8 bit, peak 255, over the pixels that are opaque in the live tile. It is computed for baked vs live render of the same tile, and the gate is the minimum over the tiles checked.
  - The rot5 fixture sheet: an INSIDE tile per checked zoom.
  - A dense-linework sheet.
- Android may change its encoding to pass, for example to lossless WebP. The choice is the Android agent's.
- Whichever encoding ships, record the following in the table below, in the same change:
  - the encoding: format, lossy or lossless, and quality
  - the MBTiles `format` metadata value
  - the minimum PSNR on rot5 and on dense linework
  - the mean and max encoded bytes/tile measured on `samples/USGS_SF_North.pdf`, at tilePx 768 at the default maxZoom (z15), over at least the 3 J2 sample tiles plus 10 INSIDE tiles
- If a platform's mean bytes/tile there exceeds the shared fallback `tileBytes` (now 250000, `bakeEstimate.fallback.tileBytes`), raise `BAKE_FALLBACK_TILE_BYTES` in `scripts/gen_pdf_tile_render.py` to that mean rounded up to the next 10000. Then regenerate the fixture (never hand edit it), and update both platforms' constants to the new value.
- Update the `constants.bake` encoding fields through the generator in the same way.

| Platform | Encoding | MBTiles format | min PSNR rot5 | min PSNR dense | USGS bytes/tile mean / max |
|---|---|---|---|---|---|
| iOS | JPEG 2000 (ImageIO `public.jpeg-2000`) lossy q0.9 when fully opaque, else PNG (lossless) | `jp2` | 62.5 dB at 768 px, 63.4 dB at 512 px (one INSIDE tile per zoom, baseMaxZoom..D) | 50.0 dB at 768 px, 54.6 dB at 512 px (41 diagonals + red grid on the sf_iso page box) | 248349 / 316438 (13 tiles: the 3 J2 samples + 10 INSIDE tiles spread evenly over the row-major INSIDE list, not the Android 13; min PSNR 36.98 dB; simulator). JPEG q0.85 for comparison: 247509 / 370763 and 32.1 dB USGS, 26.4 dB dense, which fails; q0.98 still only 32.5 / 26.4 dB because ImageIO always subsamples chroma. Lossless PNG: 790579 mean |
| Android | WebP lossless, `Bitmap.CompressFormat.WEBP_LOSSLESS` effort 100 (r2, was 85; API < 30: legacy `WEBP` at 100, which is libwebp lossless); skia's VP8X + ICCP wrapper stripped to the simple lossless layout (r2, OD-F3, pixels unchanged) | `webp` | 99.0 dB at 768 and 512 px (MSE 0 on every fixture `psnr` tile, z13-16 at 768, z14-16 at 512; r2 re-measure) | 99.0 dB at 768 and 512 px (MSE 0, `tacmap_render_dense.pdf`, z14-16; r2 re-measure) | 141380 / 196880 at effort 100 (13 tiles: the 3 J2 samples + the next 10 INSIDE by d2; emulator API 36, r2); effort 85 was 142373 / 198374. Encode 25.0 ms/tile at 100 vs 19.5 at 85 (1.29x). A PIL (libwebp 1.6, default lossless m4) re-encode of the same 13 tiles: 101167 / 131432, so 1.40x. Whole z0-15 USGS bake at 672 px (instrumented, same PdfBaker as Generate): effort 100 30457856 bytes file, 30065706 in tile blobs vs 19425782 PIL (1.55x), 7.4 s wall (UI Generate to published: 9.7 s); effort 85 34267136 / 33856056 (1.74x), 6.7-6.8 s (UI 9.2 s). Cause: skia always runs libwebp lossless at method 0, `quality` is the only knob; libwebp m1 on the same tiles is 1.03x, so getting within 1.2x needs a native encoder (follow-up). Lossy q85 for comparison: 81719 / 107178, and 32.2-34.4 dB on rot5 / dense, which fails |

- Android's mean (142855) is over the old 20000 fallback, so `BAKE_FALLBACK_TILE_BYTES` is now 150000 in the generator and the fixture was regenerated. Both platforms' fallback constants have to say 150000. Effort 100 only bought 0.7% (141862 mean) for 1.3x the encode time, so effort stays at 85.
- iOS's mean (248349) is over 150000 too, so `BAKE_FALLBACK_TILE_BYTES` is now **250000** and the fixture was regenerated again. Both platforms' fallback constants have to say 250000. `constants.bake` carries `iosJpeg2000Quality` 0.9 and `iosMbtilesFormat` "jp2" (replacing `iosJpegQuality`).
- iOS JPEG 2000 decodes at about 35-40 ms per 768 px tile on the simulator, against about 4 ms for JPEG. That is still well under a live vector draw of the same tile.

**Amendment 2026-10-02 (r1): gate inputs and wording (D1, D4, D8, D9; pinned by fixture `psnr`).**
- D9: the shared fallback is 250000; the J3 raise rule above said 20000 before r1. The two USGS rows are over different tiles: iOS took the 3 J2 samples plus 10 INSIDE tiles spread evenly over the row-major INSIDE list (step `floor(n/10)`, from `step/2`, samples excluded); Android took the 13 INSIDE tiles nearest the centre by J2 `d2` (the 3 samples plus the next 10).
- D1: the dense-linework sheet is `testdata/geopdf/tacmap_render_dense.pdf` (fixture `dense`: 41 black 0.25 pt diagonals and a red 0.3 pt grid every 12 pt, ISO /VP), on both platforms. It replaces iOS's inline drawing on the sf_iso box and Android's 1.5 pt grid sheet.
- D1: each gate checks the fixture's `psnr` tiles: for tilePx 768 and 512, z = baseMaxZoom..D, the middle INSIDE tile in row-major order per z, on rot5_iso and on the dense sheet. It asserts at least one vector zoom (z > baseMaxZoom) was checked. The min PSNR columns above were measured before this rule; re-measure them with it.
- D4: both platforms ship a production `PSNR_GATE_DB` = 35.0 and assert it equals `constants.bake.psnrGateDb`.
- D8: tests compare fixture values to the code constants, never to literals.
- Caveat for the USGS numbers: the iOS render of `USGS_SF_North.pdf` shows the orthoimage. Its content is marked `/OC` with an OCMD (`/P /AllOn` over Orthoimage + Images), and Images is in the default `/OFF` list, so per the PDF spec (and MuPDF) the orthoimage should be hidden. CoreGraphics honours a plain OCG (the markers fixture passes) but apparently not this OCMD. That inflates the iOS bytes/tile. Android's much smaller lossless mean suggests pdfium hides it. Flagged as a separate iOS renderer issue, not fixed in WP2.

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

**Amendment 2026-10-02 (L1): dead iOS catalogue entries.**
- Remove these three entries from `localization/catalog.json` (and from the source inventory and reviews if they are listed there), but only if no code references them:
  - `ui_bakes_this_calibrated_map_into_an_offline_tile_s_f73ad763`
  - `ui_generate_offline_tiles_d8b4f85a`
  - `ui_offline_tiles_2c47000d`
- Then run `python3 scripts/generate_localizations.py`.
- If the legacy display-hash fixture lists them, regenerate it with its own tool as `testdata/README.md` describes. Never hand edit it.
- `python3 scripts/check_localizations.py` must pass.

**Amendment 2026-10-02 (L2 + H1): Layers subtitle and header colour.**
- The Android Layers PDF subtitle switches on the placement origin. It uses the same catalogue entries iOS `LayersSheet.georefLabel` uses:

  | Placement origin | Catalogue entry |
  |---|---|
  | Adobe/ISO /VP | `pdfGeorefAdobeLabel` |
  | LGIDict | "Georeferenced (GeoPDF LGIDict)" |
  | manual (fiduciaries) | "Manually placed bounds" |
  | provisional | "No georeferencing — using map-centre fallback" |

  It no longer shows the LGIDict label for every GeoPDF.
- The iOS ready header colour comes from `PDFRenderStatusColors.ready` (#74E38A), not a literal colour.

**Amendment 2026-10-02 (r1): one set of subtitle keys (B6).** Both platforms use one shared set of Layers subtitle catalogue keys. Android drops its own duplicates (`layers_pdf_georeferenced`, `layers_pdf_manual_bounds`, `layers_pdf_no_georeferencing`). Update the legacy display-hash golden only after confirming no existing text changed.

## M. Flows

**Import:**
1. Parse (WP1).
2. Arm the import guard.
3. Render the base raster and run the blank check in the import worker.
4. Complete the guard.
5. Persist and select.
6. First paint is immediate.

**Launch:** decide the guard outcome, then restore (or suppress), then show any notice.

**Amendment 2026-10-02 (r1): import framing and an unreadable stored PDF (OD-IMPORT, OD-F4).**
- OD-IMPORT: after an import, both platforms frame the whole sheet: fit the camera to the sheet footprint with the usual fit padding (Android's behaviour). iOS stops filling and cropping the sides.
- OD-F4: restoring a stored PDF that is missing, corrupt, truncated or blank keeps the PDF selection and goes to the G1 failed state (the cannotOpen or blank alert with Try Again, Use Online Map, Not Now; the Layers failed row with Try Again), as Android does. Once the file is back, Try Again or the next launch recovers. "The saved basemap is locked or unreadable" is only for real selection-store unlock or decrypt failures.

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
| `bakeJobFormation[]` (amendment 2026-10-02, E2) | baseMaxZoom plus per-level row-major tiles, heavyAtLevelStart and heavyAfterFirstJob → path, heavyUsed and the ordered jobs |
| `failureAccounting[]` (amendment 2026-10-02, G2) | event sequences → `{failed, consecutiveFailures}` after each step |
| `failureAccounting[]` (amendment 2026-10-02 r1, R1 + R2) | adds `waiters`, `allEmpty` and `feedsEwma` steps, and base raster lifecycle cases (`baseMaxZoom`, `wanted`, `baseDone` → `base`, `startsBaseAttempt`, `replan`) |
| `stagedRegion[]` (amendment 2026-10-02 r1, R5 + D5) | cellBbox, required, clipBBox, jobPixels → pad, region, cap, d, W, H, shrinkSteps or renderError; plus the blankCheck case on `tacmap_render_blank_corner.pdf` |
| `bakeFormat` (amendment 2026-10-02 r1, OD-F7) | tiles and bytes → en/de count and size strings |
| `psnr`, `dense` (amendment 2026-10-02 r1, D1) | the J3 gate sheets, tilePx, zoom rule and the exact tiles per zoom; the dense sheet's georef and zoom policy |
| `bakeEstimate` (amendment 2026-10-02, J2) | `fallback` constants, per-sheet `sampleSelection`, and `cases` (sheet, tilePx, baseMaxZoom, options, samples, ewmaMs, freeBytes → jobMs/encodeMs/meanTileBytes with their source, heavy, and per-option jobsPerLevel, jobs, bytes, neededBytes, enoughSpace, estimatedMs, minutes, plus initialSelection and generateEnabled) |

**Amendment 2026-10-02 (K1): `crashGuard[]` shape.**
- Every `state` now carries `bakeInProgress`, and every `launch` result carries `bakeInterrupted`.
- `decision` is never `bakeInterrupted` any more.
- New cases cover import-during-bake. A legacy case starts from `initialState` and `initialVerifiedOrder` instead of an empty store.

**New synthetic PDFs** from the same generator:
- `testdata/geopdf/tacmap_render_markers.pdf`:
  - MediaBox [100 100 700 500], an inset CropBox, and /Rotate 90
  - ISO /VP georef
  - red 4 pt squares at known page points, whose expected tile px at 768 and 512 are listed in the fixture
  - a blue square in an optional-content group that is OFF by default. It must not be drawn.
- `tacmap_render_blank.pdf`.
- (r1) `tacmap_render_dense.pdf`, the J3 dense-linework sheet, and `tacmap_render_blank_corner.pdf`, a /Bounds quad with black marks outside it in the BBox corners that must still import as `blank`.

Update the `testdata/README.md` table.
