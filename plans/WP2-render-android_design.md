
# WP2 Android: the PDF as an on-demand Web Mercator tile source (final judged design)

All line numbers are on HEAD 77504db, relative to `android/app/src/main/java/com/tacmap/`. Merge order is WP1, then WP2, then WP4.

## What WP2 needs from WP1

**`PdfGeoreference`:**
- `page`, `crop`, `origin` (PROVISIONAL gates the bake)
- `toPage(lat, lon)` and `toWgs84(PagePoint)`. Both return null outside the projection domain, never NaN.
- `fingerprint()`
- the pure 256-logical-px `tilePixelToPage`

**Session DTO:** `PdfSessionStore.PersistedPdfSource` (368-380) gains these optional fields:
- `pageGeometry: PdfPageGeometry(mediaBox, cropBox, rotate)`. Floats from WP1's PDFBox parse, because PdfRenderer exposes only int sizes. If a session lacks it, WP2 reads it once with PDFBox on the render thread and persists it.
- `renderGuardToken: String`. A random UUID, minted in the v2 migration.
- `bake: PersistedPdfBake(fileName, bakeKey, minZoom, maxZoom, tilePx, bytes)`.
- `contentKey`. Computed on the import IO worker.

**Seam while WP1 is in flight:** `interface PageGeoreference { val pageIndex: Int; val cropPolygon: List<PagePoint>; val isProvisional: Boolean; val digest: String; fun toPage(lat: Double, lon: Double): PagePoint?; fun toWgs84(p: PagePoint): Wgs84Coordinate? }`.

## 1. New files

### `map/render/TileDrawPlanner.kt` (pure JVM)
- `TileIndex.parent`, `ancestor(levelsUp)`, `children`, `unitRect(inAncestor)`.
- `data class VisibleTile(index, col /*unwrapped*/, row)`.
- `class TileGrid(camera, tileZoom)` with `frame(v): RectF`. Frames are in dp and equal `origin + (col - col0, row - row0) * edge`, so shared edges are bit-identical.
- `object TileDrawPlanner { const val MAX_ANCESTOR_LEVELS = 4; fun plan(visible, state: (TileIndex) -> TileCacheState, fallbackZoom: Int?): DrawPlan(items, requests) }`. It follows the shared rule.

### `map/render/TileBitmapCache.kt` (owned by MapViewModel, so it survives rotation)
- `LruCache` sized in KB.
- `bind(cacheKey: String)` evicts only when the key changes.
- `entry(t)`, `put`, `trim(keeping)`.
- Entries are `Bitmap` or `EMPTY`.
- **Tiles are no longer recycled on eviction.** A recorded frame may still reference them, so we rely on NativeAllocationRegistry. `EMPTY` is never recycled.
- `TileMemoryBudget.bytes(ctx)` returns 64/128/192 MB by the shared RAM tiers (`isLowRamDevice` counts as the lowest tier).

### `map/render/pdf/PdfTileGeometry.kt` (pure)
Kotlin twins of the iOS types:
- `PdfFootprint(georef, pageBox)`: clip polygon = crop ∩ (CropBox ∩ MediaBox), densified to 32 points per edge, with `classify` and `jobPixelBounds`.
- `TileJob`
- `PdfWarpCell(clip: IntRect, pageToPx: Affine2, errorPx, coverage)`
- `object PdfTileWarp { fun plan(job, tilePx, footprint, pageForWorld): List<PdfWarpCell> }`. All maths is in Double. Floats are produced only for the final `Matrix`.
- `PdfZoomPolicy` (`tilePx(density)`, `pxPerPoint`, `baseRasterPlan`, `baseMaxZoom`)
- `PdfJobFormation`, `PdfBakePlan`
- `Affine2(a..f)` with `map`, `inverse`, `then`, `fromThreePoints`, and a `toMatrix()` extension. Reuse WP1's affine if it lands.

### `map/render/pdf/PdfPageFrame.kt` (pure) and `PdfDisplaySizeProbe.kt`
- `class PdfPageFrame(g: PdfPageGeometry, rendererW: Int, rendererH: Int, mode: DisplaySizeMode /*FLOAT|TRUNCATED*/)` provides `userToRenderer: Affine2` and `validate(): PdfRenderFailure?`.
- The pdfium box is CropBox ∩ MediaBox. Set `u = x - box.left` and `v = box.top - y`. Then rotate:
  - rotate 0: `(u, v)`
  - rotate 90: `(Hf - v, u)`
  - rotate 180: `(Wf - u, Hf - v)`
  - rotate 270: `(v, Wf - u)`
  Then scale by the display size over the rotated float size. In TRUNCATED mode the display size is `rendererW/H`; in FLOAT mode it is the float size.
- `validate` fails closed with `pageGeometry` when the rotated float size and `page.width/height` differ by more than 1 pt (D3-05).
- **Display-size probe:** once per process, on the executor. It writes a roughly 300-byte PDF to `cacheDir`: MediaBox 0 0 100.5 60.5 with a black fill. It renders that with an identity matrix into a transparent 110×70 bitmap and reads alpha at column 100:
  - alpha about 128 means FLOAT
  - alpha 0 means TRUNCATED
  - anything unclear means FLOAT, and the result is logged
  The API 35+ PdfRenderer is updatable, so the behaviour can change without an app update. That is why this runs at runtime.

### `map/render/pdf/PdfRenderExecutor.kt` (the only thread that touches pdfium)
It uses a global lock.

```kotlin
internal interface PdfPageBackend : Closeable { val widthPt: Int; val heightPt: Int; fun render(dst: Bitmap, clip: Rect, m: Matrix) } // real = PdfRenderer.Page (RENDER_MODE_FOR_DISPLAY); fake in JVM tests
internal object PdfRenderExecutor {
  enum class Band { VISIBLE, FALLBACK, BAKE }
  suspend fun <T : Any> run(session: PdfRenderSession, band: Band, key: TileIndex?, onOrphan: (T) -> Unit = {}, block: PdfPageBackend.() -> T): T
  fun wanted(session, ordered: List<TileIndex>, tileZoom: Int)
  val callEwmaMs: Double              // α 0.3; heavy when > 60
  @Volatile var foreground: Boolean
  fun onTrimMemory(level: Int)
}
```

- It runs as one `Thread("PdfRender")` at `THREAD_PRIORITY_DEFAULT + THREAD_PRIORITY_LESS_FAVORABLE`. Do not use BACKGROUND, because that cgroup pins the thread to little cores.
- It pulls from a lock-guarded PriorityQueue.
- It follows the same shared rules as iOS:
  - pick by (band, distance to the viewport centre)
  - vector jobs start only after tileZoom has been stable for 150 ms
  - job formation: heavy grows up to 6 tiles with at most 3 per side; light is 1×1; bake uses `bakeBlock`
  - a bake job never starts while a VISIBLE job is pending, and at most 1 bake job is in flight
  - cancelling removes a queued waiter; a running job completes and routes its tiles to `onOrphan`
- **Every pdfium caller goes through this executor:** the import probe, the display-size probe and the bake. WP4 and WP5 must use it too.

### `map/render/pdf/PdfRenderSession.kt` (reference-counted registry keyed by `(canonicalPath, contentKey, pageIndex)`)
- On the executor it opens, in order: `ParcelFileDescriptor`, then `PdfRenderer`, then `openPage(page)`. The page is kept open.
- Error mapping:
  - SecurityException: `passwordProtected`
  - IOException or IllegalArgumentException: `cannotOpen`
  - page index out of range: `pageMissing`
  - `frame.validate()` failing: `pageGeometry`
- **Base raster (`PdfBaseRaster`):** single flight. One `page.render` of the plan region into ARGB_8888 with `M = regionToPx ∘ frame.userToRenderer`, then a blank check. Mips are built on `Dispatchers.Default` by 2x `createScaledBitmap(filter = true)`, which fixes D3-15. The result is immutable and software (never HARDWARE, because the raster lane reads it).
- It closes the page after 90 s idle and on `TRIM_MEMORY_UI_HIDDEN` or worse, unless a bake is running. The base raster is kept; the page reopens lazily.
- **Defensive:** if `render` throws `IllegalStateException` after an earlier success on the same page, switch that session to reopening the page for every call (HEAD comment at PdfPageRenderer.kt:159-161).
- `OutOfMemoryError` is caught only around our own bitmap allocations. Trim the caches and retry once at half budget; if that fails, report `outOfMemory`. It is never swallowed as success (D5-15).

### `map/render/pdf/PdfTileRenderer.kt` (one function for live and bake)
`renderJob(job, tilePx, cells, footprint, source: Vector | Staged | Raster(base), cancelled): Map<TileIndex, TileCacheEntry>`

1. Take a scratch bitmap of `cols*tilePx × rows*tilePx` and `eraseColor(WHITE)` (paper).
2. For each non-OUTSIDE cell:
   - **Vector:** `page.render(scratch, cell.clip, (cell.pageToPx ∘ frame.rendererToUser).toMatrix())`. Check `cancelled()` between cells.
   - **Raster or staged:** `canvas.clipRect(cell.clip)`, `concat(pageToPx ∘ rasterToPage)`, then `drawBitmap(level, FILTER)`.
3. If the job has any EDGE cell: the output is a transparent bitmap. Draw `drawPath(footprint.cropPathPx(job, tilePx), Paint(ANTI_ALIAS) with BitmapShader(scratch))`. The crop path is built from the densified projected clip polygon. Off-sheet pixels get alpha 0 and the neatline is antialiased (D3-12, D1-08).
   If all cells are INSIDE, the output is the scratch itself.
4. Slice each tile with `Bitmap.createBitmap(out, …)`. OUTSIDE tiles become `EMPTY`.
   - Live tiles: `copy(Config.HARDWARE, false)` on the raster lane, then recycle the software copy. `TileRenderFlags.hardwareTiles` is set to false in tests.
   - Bake tiles stay ARGB_8888 so they can be encoded.

Staged jobs render the page bbox once at `min(1.25 × required, cap)`, where the cap keeps staged pixels at or below 2× the job's pixels, then warp as raster.

### `map/render/pdf/PdfTileSource.kt` (TileSource)
- `minZoom = 0`, `maxZoom = detailZoom`, `tileSizePx = tilePx`.
- `cacheKey = "pdf:$contentKey:$digest:$tilePx"`.
- `hasContent = classify != OUTSIDE`.
- `fallbackZoom(tz)` follows the shared rule.
- `loadTile` order (mirrors iOS):
  1. failed: null
  2. OUTSIDE: `EMPTY`
  3. orphan cache (8 tiles)
  4. bake read on IO (`decodeBoundedTile`)
  5. z ≤ baseMaxZoom: await the base raster, then sample on `Dispatchers.Default.limitedParallelism(2)`
  6. otherwise: `PdfRenderExecutor.run(VISIBLE)`
- `attachBake(reader)`.
- `state: StateFlow<PdfRenderStatus>`. Three consecutive job failures give `renderError`.

### `map/PdfMapRuntime.kt` (owned by MapViewModel, survives configuration changes)
- Holds the session reference, the current `PdfTileSource`, `status`, `showPreparingLabel` (after 300 ms), `recovery: StateFlow<PdfRecovery?>` and crash-guard hooks.
- `tileSource(pdf, density)` is keyed by `(identity, digest, tilePx)`.
- A WP4 refit swaps only the cheap source; the page and base raster stay.
- Also: `retry()`, `onTrimMemory()`, `setForeground()`.

### `map/render/pdf/PdfRenderGuard.kt`
- Pure reducer plus a file at `noBackupFilesDir/pdf_render_guard.json`.
- Written by temp file, fsync, then `Files.move(ATOMIC_MOVE)`, the same pattern as `persistActiveMapMigrationMarker` (ActiveMapSelectionStore.kt:23-53).
- The IMPORT entry also stores the opaque `operationKey`, so the pending-import replay can be abandoned.

### `calibration/PdfBakeManager.kt` (app scope, created in `TacticalApp.onCreate`, `SupervisorJob + Dispatchers.Default`)
- `state: StateFlow<BakeState>` with Idle, Estimating, Confirming(proposal), Running(done, total), Failed(PdfBakeError), plus a one-shot `finished` event.
- `prepare(pdf, runtime)`, `start(option)`, `cancel()`, `cancel(contentKey)`, `cleanWorkDirectory()` (runs at launch).
- Survives sheet dismissal, Activity recreation and Activity finish.

### `calibration/PdfBaker.kt` (replaces PdfTiler)
- Uses the same session and executor at the BAKE band.
- Walks z from 0 to maxZoom over intersecting tiles in `bakeBlock` jobs.
- Encoding on a 2-thread `Dispatchers.Default` pool, fed by a bounded channel of 8: `WEBP_LOSSY` q85 on API 30+, `WEBP` q85 on 26-29.
- A single IO writer thread writes into `filesDir/pdf_bake_work/<uuid>.mbtiles.partial`. That directory is outside the reconcile roots, because at HEAD reconcile deletes any `*.mbtiles.partial`.
- Commits every 64 tiles.
- Publish runs under the store monitor via `ActiveMapSelectionStore.withManagedFilesLock {}`:
  1. rename into `filesDir/offline_tiles/`
  2. `PdfSessionStore.attachBake(token, bakeKey, ref)`
  If the session is gone or its fingerprint changed, the output is deleted with `sourceChanged`. The active map never changes (D5-05, D5-06).
- `ensureActive()` between jobs. Pauses while `PowerManager.currentThermalStatus ≥ THERMAL_STATUS_SEVERE` (API 29+).

## 2. Existing code to change (HEAD lines)

**`map/render/TileSource.kt:32-38`**
- Add defaults: `val cacheKey: String get() = toString()`, `fun hasContent(t) = true`, `fun fallbackZoom(tz): Int? = null`, `fun onWanted(ordered, tz) {}`.
- Add `companion object { val EMPTY: Bitmap }` (1×1 transparent, never recycled).
- KDoc: `maxZoom` is the native max; the camera may overzoom.
- Online source gets `cacheKey "online:${style}"`. `OfflineRasterTileSource` (227-254) gets `"offline:<path>"`.

**`map/render/TileMapView.kt`**
- New parameters: `cache: TileBitmapCache`, `hidden: Boolean`.
- Remove the internal LRU (68-83). Stop evicting on dispose (100-105); instead `cache.bind(source.cacheKey)`.
- `tiles` (108-113): `TileMath.visibleTiles` (rotated filter, unwrapped cols, centre order) plus the underzoom guard, then `TileDrawPlanner.plan`. Call `source.onWanted`.
- `LaunchedEffect` (116-126): `reconcile(wanted = LinkedHashSet(plan.requests))`. `ScopedTileLoadCoordinator.reconcile` (37-64) is unchanged; its KDoc states that iteration order is the priority contract. In the load lambda, EMPTY stays EMPTY.
- Gesture clamp (151-155): `MapCamera.MIN_ZOOM..MAX_ZOOM`.
- Draw (192-216):
  - pass 1: plan items, ancestors then children
  - pass 2: own tiles
  - each drawn with `nativeCanvas.drawBitmap(bmp, srcRect(unitRect × bmp size), RectF(grid.frame × density), Paint(FILTER_BITMAP_FLAG) with isAntiAlias = false)`
  - no ±0.5 dp inflation and no rounding (D4-16, D3-11)
  - `hidden` draws only the background

**`map/render/TileMath.kt`**
- `tileZoom` (21-22) is unchanged.
- `visibleTiles` (30-64) returns `List<VisibleTile>`:
  - separating-axis test against the rotated viewport, inflated by 1 dp
  - unwrapped columns
  - sorted by distance
- `tileFrame` (69-79) is replaced by `TileGrid`.
- Add `const val MAX_UNDERZOOM_LEVELS = 2.0`.

**`map/render/MapCamera.kt:20`**
- Add `companion object { const val MIN_ZOOM = 2.0; const val MAX_ZOOM = 22.0 }`.

**`map/CustomMapScreen.kt`**
- 125-132: `is PdfMapSource -> pdfRuntime.tileSource(mapSource, density)`. When `!(importedMapVisible || calibrating)`, pass `hidden = true`; the source stays so the cache is kept.
- Show a "Imported map hidden" notice styled like 326-328.
- 240-243: delete the `PdfGroundLayer` call.
- 136-147: coerce the pending target zoom into 2..22.
- 306-307: pass `MapCamera.MIN_ZOOM/MAX_ZOOM` (D3-08). `applyCustomMapTransform` (CustomMapTouch.kt:440-478) is unchanged.
- 271: `CalibrationFiduciariesLayer` gets page-projected positions, `georef.toWgs84(fid.pagePoint)`. This is the shim until WP4.

**`map/render/CustomMapOverlays.kt:803-884`**
- Delete `PdfGroundLayer` and its imports.

**`calibration/PdfPageRenderer.kt`**
- Delete 26-60 and 77-212.
- Keep `firstPageInfo` (68-75) for the preflight. `openDescriptor` (214-223) moves into the session.

**`calibration/PdfTiler.kt`**
- Delete the whole file. The `.partial` and publish logic (53-130) moves to `PdfBaker`. `zoomRange`/`MAX_TILES` (223-245) are replaced by `PdfBakePlan`.

**`calibration/MBTilesWriter.kt`**
- `create` (84-106) runs `PRAGMA journal_mode=OFF` and `synchronous=OFF`.
- `writeMetadata` (34-63) gains `extras: Map<String, String>` (`tacmap_bake_key`, `tacmap_tile_px`, `tacmap_renderer`) and `format = "webp"`.
- `putTile` uses a compiled `SQLiteStatement`.

**`calibration/PdfMapSource.kt:12-36`**
- Gains `georef` (WP1), `pageGeometry`, `contentKey`, `renderGuardToken`, `bake`.
- Coverage (76-105) comes from WP1's `toWgs84` of the crop.

**`calibration/PdfSessionStore.kt`**
- DTO (368-380) gets the new optional fields. Add `attachBake`/`clearBake`.
- `save` (94-98) uses `source.contentKey` (checked against a size and mtime memo) instead of hashing on Main (D5-08). The call path is MapViewModel.kt:425-431.
- A changed georef fingerprint clears `bake`.

**`calibration/ActiveMapSelectionStore.kt`**
- `reconcileManagedImportedMapFiles` (273-313) gains a `currentPdfBakeFile: () -> File?` parameter. The PDF branch (283-287) keeps `setOf(pdf) + bakeFile`.
- Add `withManagedFilesLock`. The work directory is never listed.

**`map/MapViewModel.kt`**
- Owns `val tileCache` and `val pdfRuntime`.
- `init` (291-301) and `restoreActiveMapSource` (312-349): when the restored source is a `PdfMapSource` and `PdfRenderGuard.launchDecision(token) == Suppress`, set `_mapSource.value = onlineBasemap()` in memory only and set `pdfRecovery`.
- `unloadPdfMap` (234-250) calls `bakeManager.cancel(contentKey)` first.
- `onCleared` (667-679) releases the runtime.
- Add `openSuspectAnyway()` and `useOnlineMap()`. The latter goes through `restoreOnlineBasemap()` (211), which keeps the retained PDF.

**`map/MapScreen.kt`**
- Header (224-238): same states as iOS, amber `0xFFFFC247` for "Drawing map…" and red for failed.
- Delete `tilingProgress` (379) and the modal dialog (1950-1969).
- Add `rememberPersistedBoolean("importedMapVisible", true)` after 395.
- `onGenerateTiles` (2012-2040) becomes `bakeManager.prepare`, then the confirm dialog, then `start`.
- Add the progress chip with Cancel on the map chrome, and `LocalView.current.keepScreenOn` while running.
- Add the failure dialog, recovery dialog, and import-interrupted and bake-interrupted dialogs.
- `importSelectedPdf` (445-466): the session registry already holds the probed session.
- Pending import replay (595-613): if the guard reports `importInterrupted` for this `operationKey`, call `onCompleteDocumentImport` without processing and show the notice.

**`map/LayersSheet.kt`**
- Params (84-93) gain `importedMapVisible`, `onImportedMapVisibleChange`, `bakeState`, `onCancelBake`, `onRemoveBake`, `pdfBakeAllowed`, `renderFailed`, `onRetryRender`.
- Section (174-201):
  - "Show Imported Map" switch
  - Generate button, disabled with the calibrate caption for provisional maps
  - progress and Cancel while running
  - "Offline tiles: zoom a–b · size" plus "Remove Offline Tiles" when done
  - new caption text
  - "Map couldn't be drawn" with Try Again

**`map/MapScreenHelpers.kt`**
- `pdfPointFor` (65-80) becomes `georef.toPage(lat, lng)`, accepted only inside the footprint (calibration shim).
- `preflightPdfImport` (424-439): remove the /Rotate rejection (433-437), because PdfPageFrame now renders rotated pages. Coordinate this with WP1/WP5.
- `importPdfMapSource` (441-500), after WP1's parse and on IO:
  1. compute `contentKey`
  2. mint the token
  3. `PdfRenderGuard.arm(IMPORT, token, operationKey)`
  4. `PdfRenderSessions.prepareForImport(...)` on the executor: open, validate the frame, base raster, blank check. The session stays in the registry with a 30 s TTL.
  5. `complete`
  A failure maps to a `PdfImportRejectedException` carrying the shared failure copy.

**`app/TacticalApp.kt:32-44`**
- `pdfBakeManager = PdfBakeManager(this)`.
- `registerComponentCallbacks` forwards `onTrimMemory` to `MemoryPressure`, which feeds the executor, the runtime and the cache trim.
- Launch: `PdfBakeManager.cleanWorkDirectory()`.

**Localization:** every shared string goes through `L10n`/`LocalizedStringIds` plus the localization fixtures.

## 3. Threading

| Where | What runs there |
|---|---|
| Main | composition, planner, cache and coordinator (no decode, render or hash) |
| RenderThread | no texture uploads (live tiles are HARDWARE) |
| PdfRender thread | all pdfium work |
| Raster lane (Default ×2) | base sampling, mips, slicing, HARDWARE copies |
| IO | bake reads, SQLite writer |
| Default ×2 | WebP encode |

## 4. Memory (6 GB phone at 2.625×, USGS)
- Tile LRU: 192 MB tier cap (a 24-tile level at 672 px is about 43 MB).
- Base raster: 6 Mpx (24 MB) plus mips (8 MB).
- Scratch: ≤ 6 × 1.8 MB.
- Staged: ≤ 2× job.
- Orphans: ≤ 8 tiles.
- pdfium image cache: ≤ 100 MB, plus the parsed page. The benchmark measures this.
- `onTrimMemory`:
  - RUNNING_LOW or worse: cache down to the plan, orphans dropped
  - UI_HIDDEN or worse: page closed unless baking
  - BACKGROUND or worse: session closed unless baking, base raster kept

## 5. Phases (each shippable)
1. Pure layer and JVM tests: planner, grid, warp, policy, footprint, frame, formation, bake plan, guard reducer.
2. TileBitmapCache and TileMapView fallback, exact frames and zoom limits, verified on MBTiles first.
3. Executor, session and probe, renderer, PdfTileSource, runtime. Delete PdfGroundLayer. Toggle, status and failure UI. **Run the benchmark gate here.**
4. Crash guard, the import probe and the restore path.
5. PdfBaker, PdfBakeManager, DTO fields, reconcile, bake UI.
6. Content key off Main.
7. Optional: double-tap, only if iOS ships it too.

## 6. Tests

**JVM (`src/test`):**
- `PdfTileRenderFixtureTest` asserts every shared section of `testdata/pdf_tile_render.json`, same tolerances as iOS.
- `PdfPageFrameTest`: rotations 0/90/180/270 × CropBox offset × fractional size × FLOAT/TRUNCATED, round trip ≤ 1e-9; `validate` fails on a mismatch.
- `TileDrawPlannerTest`.
- `TileMathTest`: rotated filter vs brute force; unwrapped cols; bit-identical shared edges.
- `PdfRenderExecutorTest` (fake backend):
  - settle 150 ms
  - growth ≤ 6 / ≤ 3
  - band order
  - queued cancel
  - orphan hand-over
  - bake yields to visible
  - EWMA heavy flip
- `PdfRenderGuardTest`: fixture transitions plus atomic write.
- `PdfBakePlanTest`.
- `ApplyCustomMapTransformTest`: z15 × 1.02 in 2..22 gives about 15.0286; z18 allowed over a maxZoom-16 source.
- `ActiveMapSelectionStoreReconcileTest`: PDF and bake kept; a stale bake reaped; the work dir untouched.
- `TileBitmapCacheTest`: bind semantics, no recycle on evict, EMPTY safe.
- `PdfSessionStore` DTO round trip.
- Delete `PdfPageRendererSizingTest`.

**Instrumented (`src/androidTest`)** on emulators at API 26/30/34/35/36 plus one mid-range device:
- `PdfPageFrameInstrumentedTest`: marker centroids ≤ 0.5 px on `tacmap_render_markers.pdf`, `rot90_iso` and `offset_iso`. Logs the probe mode.
- `PdfTileRenderInstrumentedTest`:
  - alpha outside the crop
  - seam continuity on rot5_iso
  - base vs vector, staged vs direct
  - OCG-off square not drawn
  - 200 renders on one open page
- `PdfBakeInstrumentedTest`:
  - a counting backend sees one PdfRenderer and one page
  - cancel removes `.partial`
  - survives `ActivityScenario.recreate()`
  - PDF still selectable
  - PSNR ≥ 35 dB
  - a basemap switch mid-bake keeps `.partial`
- Compose tests: chip and Cancel after sheet dismissal and recreate; Generate disabled while running or provisional; the toggle hides the layer; a corrupt PDF shows the dialog; the suppress path shows online plus the dialog.
- Update `PdfImportHardeningInstrumentedTest:51,197-227` and `PdfImportSmokeTest:55`.

**`PdfRenderBenchmark`** (`@LargeTest`, skips without USGS). Measures:
- open
- probe
- base raster at 3 and 6 Mpx
- vector jobs z13-17 at 1×1 vs grown 6 (p50/p90)
- `Debug.getNativeHeapAllocatedSize` delta
- bake time and bytes

It decides the heavy threshold and whether WebP q85 softness is acceptable.

**Jank (JankStats):**
- Scripted pinch z12->17->12 and pans: ≤ 1% janky frames, no tile-attributable frame over 32 ms.
- Screenshots crossing x.5: 0 background pixels inside the sheet.

**On device:** the plan §6 sweep, `grid_alignment.py` ≤ 1 px, heading-up, rotation, kill and relaunch, delete (bake file gone), bake with the screen off during GPX recording.

## 7. Risks
- **Image-bound heavy sheets.** If pdfium re-decodes USGS's 108 Mpx soft-masked image on every call, a job costs about 0.5-3 s on mid-range phones. Coalescing, the fallback and the bake mitigate this; the benchmark gate sets the constants.
- **Native memory outside our budgets.** pdfium's cache and parsed page.
- **HARDWARE bitmaps.** Driver quirks; keep the debug flag.
- **`RENDER_MODE_FOR_DISPLAY` enables LCD text.** Faint fringes under rotation, the same as today.
- **Frame mapping.** Depends on the probe and instrumented pinning across API levels.
- **Bake paused by the cached-app freezer.** Process death discards the bake, and the launch notice reports it.
- **The reconcile keep-set change touches deletion authority.** Needs dedicated tests.
