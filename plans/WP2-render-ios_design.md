
# WP2 iOS: the PDF as an on-demand Web Mercator tile source (final judged design)

All line numbers are on HEAD 77504db. Paths are relative to the repo root.

**Merge order:** WP1, then WP2, then WP4. WP3 (MGRS) touches only the overlay stack in `TileMapContainer.attach`, so rebase it trivially.

**What WP2 consumes from WP1:**
- `PdfGeoreference` with:
  - `page` (0-based; CGPDF pages are 1-based, so use `page+1`)
  - `crop` (page-space polygon)
  - `origin` (`.provisional` gates the bake)
  - `toPage(lat,lon)` and `toWGS84(page)`, both returning nil outside the domain, never NaN
- the pure 256-logical-px `tilePixel -> page` function (matches `testdata/pdf_georef.json` `tileWarp`)
- two optional fields on the v2 sealed session DTO:
  - `renderGuardToken: String?`, minted as a random UUID during WP1's v1->v2 migration
  - `bake: PersistedPDFBake?`, holding `{fileName, bakeKey, minZoom, maxZoom, tilePx, bytes}`

If WP1 has not merged when WP2 starts, code against a seam and conform WP1's type later: `protocol PDFPageGeoreferencing { var pageIndex: Int; var cropPolygon: [CGPoint]; var isProvisional: Bool; var digest: String; func toPage(_:CLLocationCoordinate2D)->CGPoint?; func toWGS84(_:CGPoint)->CLLocationCoordinate2D? }`, with `extension PdfGeoreference: PDFPageGeoreferencing {}`.

## 1. New files

### `Map/Renderer/TileDrawPlanner.swift` (pure, no UIKit)

```swift
extension TileIndex { var parent: TileIndex?; func ancestor(levelsUp:) -> TileIndex?; var children: [TileIndex]
  func unitRect(inAncestor a: TileIndex) -> CGRect /* 0..1, y-down */ }
struct VisibleTile: Hashable { let index: TileIndex /*x wrapped*/; let col: Int /*unwrapped*/; let row: Int }
struct TileGrid { init(camera: MapCamera, tileZoom: Int); let edge: CGFloat
  func frame(_ v: VisibleTile) -> CGRect }  // one origin per frame: origin + (col-col0, row-row0)*edge, so shared edges are bit-identical
struct TileDrawItem: Equatable { let source: TileIndex; let dest: VisibleTile; let unitRect: CGRect; let order: Int }
enum TileDrawPlanner { static let maxAncestorLevels = 4
  static func plan(visible: [VisibleTile], state: (TileIndex) -> TileCacheState /* .image/.empty/.missing */,
                   fallbackZoom: Int?) -> (items: [TileDrawItem], requests: [TileIndex]) }
```

The planner implements the shared fallback rule in shared_contract. Requests come out as fallbackZoom ancestors first, then missing visible tiles in centre-distance order.

### `Map/Renderer/TileImageCache.swift`

- A byte LRU of `enum TileCacheEntry { case image(CGImage), empty }`.
- Cost is `bytesPerRow*height`; an empty entry costs 64.
- API: `insert`, `entry(for:)`, `trim(keeping: Set<TileIndex>)`, `removeAll()`.
- `static func byteLimit(physicalMemory:) -> Int` returns 64/128/192 MB by the shared RAM tiers.

### `Map/Renderer/TileLayerCompositor.swift`

- Owns `tileRoot: CALayer` and a layer pool keyed by `(dest, source)`.
- `apply(items:grid:image:)` runs inside `CATransaction.setDisableActions(true)`. For each item it sets:
  - `contents` = CGImage
  - `contentsRect` = unitRect
  - `contentsGravity` = .resize
  - `frame` = grid frame
  - `zPosition` = order
  - `allowsEdgeAntialiasing` = false
  - `magnificationFilter` and `minificationFilter` = .linear
- Unused layers go back to the pool with `contents=nil`.

### `Map/Renderer/PDF/PDFTileGeometry.swift` (pure; implements the shared algorithms exactly)

```swift
enum TileCoverage { case outside, edge, inside }
struct PDFFootprint {            // clip polygon = crop ∩ CGPDFPage cropBox (Sutherland–Hodgman); densified 32/edge -> toWGS84 -> Web Mercator z0 world
  init(georef: PDFPageGeoreferencing, pageBox: CGRect) throws(PDFRenderFailure)   // .pageGeometry if area < 1 pt² or any vertex non-finite
  let clipPolygonPage: [CGPoint]; let clipPathPage: CGPath; let mercatorPolygon: [CGPoint]
  func classify(_ t: TileIndex) -> TileCoverage; func jobPixelBounds(_ job: TileJob, tilePx: Int) -> CGRect? }
struct TileJob: Hashable { let z, x0, y0, cols, rows: Int; var tiles: [TileIndex] }
struct PDFWarpCell { let rect: CGRect /*integral job px*/; let pageToPx: CGAffineTransform; let errorPx: Double; let coverage: TileCoverage }
enum PDFTileWarp { static func plan(job: TileJob, tilePx: Int, footprint: PDFFootprint,
                                    pageForWorld: (Double, Double) -> CGPoint?) -> [PDFWarpCell] }
struct PDFZoomPolicy { init(georef:, footprint:) throws; let mercMetresPerPoint, detailZoomRaw: Double; let detailZoom: Int
  static func tilePx(density: CGFloat) -> Int; func pxPerPoint(z: Int, tilePx: Int) -> Double
  func baseRasterPlan(budgetPixels: Int) -> PDFBaseRasterPlan; func baseMaxZoom(basePxPerPt: Double, tilePx: Int) -> Int }
enum PDFJobFormation { static func grow(seed: TileIndex, pending: Set<TileIndex>, maxTiles: Int, maxSide: Int) -> TileJob
                       static func bakeBlock(for t: TileIndex, heavy: Bool) -> TileJob }
enum PDFBakePlan { static func tiles(z: Int, footprint:) -> [TileIndex]; static func options(policy:, footprint:) -> [PDFBakeOption]
                   static func defaultOption(_ :[PDFBakeOption], detailZoom: Int) -> PDFBakeOption? }
```

### `Map/Renderer/PDF/PDFTileRenderer.swift` (CoreGraphics only, no PDFKit)

```swift
struct PDFPageRaster { let levels: [CGImage] /*mip chain, finest first*/; let pageRect: CGRect; let pxPerPt: Double }
enum PDFRenderSourceKind { case vector(CGPDFPage), staged(CGPDFPage), raster(PDFPageRaster) }
enum PDFTileRenderer { static let version = 1
  static func renderBaseRaster(page: CGPDFPage, plan: PDFBaseRasterPlan, footprint: PDFFootprint) throws(PDFRenderFailure) -> PDFPageRaster
  static func renderJob(_ job: TileJob, tilePx: Int, cells: [PDFWarpCell], footprint: PDFFootprint,
                        source: PDFRenderSourceKind, isCancelled: () -> Bool) throws(PDFRenderFailure) -> [TileIndex: TileCacheEntry]
  static func isBlank(_ r: PDFPageRaster) -> Bool }
```

The job context is BGRA (premultipliedFirst | byteOrder32Little), sRGB, transparent, flipped to y-down, with font smoothing off. For each non-outside cell:
1. `saveGState`
2. `clip(to: cell.rect)`
3. `concatenate(cell.pageToPx)`
4. `addPath(footprint.clipPathPage)` then `clip()`. This clip is antialiased.
5. Fill white.
6. Draw one of:
   - `drawPDFPage(page)`. Raw user space is verified: CG ignores /Rotate and the MediaBox origin and does not clip to the CropBox, which is why step 4 includes the page box.
   - `draw(level, in: raster.pageRect)`, using the smallest mip level whose density is at least the required px/pt, with `interpolationQuality .medium`.
7. `restoreGState`

After drawing, copy rows into one CGImage per tile so no tile retains the job buffer. A tile whose classification is outside becomes `.empty`.

**Staged path:** render the page bbox of the non-outside cells, intersected with the clip bbox, once into a page-aligned bitmap at density `min(1.25*required, cap)`, with staged pixels capped at ≤ 2× job pixels. Then draw it per cell like a raster.

**Base raster:** one `drawPDFPage` into the plan's region, rendered on a short-lived dedicated `CGPDFDocument` so full-page caches are freed afterwards. Build mips by halving until the long side is ≤ 256.

### `Map/Renderer/PDF/PDFRenderService.swift` (one per document identity)

```swift
struct PDFDocumentIdentity: Hashable { let contentKey: String; let pageIndex: Int }
final class PDFRenderService {
  static func acquire(_ id: PDFDocumentIdentity, url: URL) -> PDFRenderService   // main-only registry, refcounted, 30 s idle TTL after last release
  func release()
  let laneCount: Int                          // 2 if physicalMemory ≥ 3.5 GiB else 1; forced to 1 while thermalState ≥ .serious or Low Power Mode
  func baseRaster(plan:, footprint:, band: PDFRenderBand, _ done: @escaping (Result<PDFPageRaster, PDFRenderFailure>) -> Void)   // single-flight
  func adoptBaseRaster(_ r: PDFPageRaster, planKey: String)       // hand-off from the import probe
  func request(tile: TileIndex, band: PDFRenderBand, jobFor: @escaping (TileIndex, Set<TileIndex>) -> TileJob,
               render: @escaping (TileJob, CGPDFPage) throws -> [TileIndex: TileCacheEntry],
               completion: @escaping (TileCacheEntry?) -> Void) -> RasterTileRequest
  func wanted(_ ordered: [TileIndex], tileZoom: Int)              // reprioritise + record tileZoomChangedAt
  private(set) var callEWMAms: Double                             // α 0.3; heavy when > 60
  func setForeground(_ fg: Bool); func trimMemory()               // trim closes lane documents; reopened lazily
}
enum PDFRenderBand: Int, Comparable { case visible, fallback, bake }
```

- Lanes are serial `DispatchQueue`s (qos `.userInitiated`, `.utility` for bake jobs via `DispatchWorkItem` qos).
- Each lane owns its own lazily opened `CGPDFDocument`/`CGPDFPage`. CGPDF objects never cross lanes.
- Do not use Swift concurrency for 200+ ms blocking draws.
- The pull-based scheduler state lives on main: a pending map keyed by tile, a running set, and busy flags per lane.
- When a lane frees, main picks the best pending tile by `(band, distance to viewport centre)` and forms the job:
  - heavy: `grow` from pending visible tiles, max 6 tiles and 3 per side
  - light: 1×1
  - bake: `bakeBlock`
- Vector jobs start only once `tileZoom` has been unchanged for 150 ms.
- Bake jobs are never started while a visible job is pending, and at most 1 bake job is in flight.
- Cancelled queued tiles are dropped. Running jobs finish, and their unwanted tiles go to the source's orphan cache (8 tiles).
- `rasterQueue` is an `OperationQueue` (maxConcurrent 2, `.userInitiated`) that serves base-raster sampling and bake-tile reads/decodes, so it never waits behind a vector job.
- Completions always hop to main asynchronously.
- In the background, visible/fallback dispatch stops; bake continues.

### `Map/Renderer/PDF/PDFTileSource.swift`

```swift
final class PDFTileSource: RasterTileSource {
  init(service: PDFRenderService, georef: PDFPageGeoreferencing, footprint: PDFFootprint, policy: PDFZoomPolicy,
       tilePx: Int, basePlan: PDFBaseRasterPlan, bake: PDFBakeReader?, onStatus: @escaping (PDFRenderStatus) -> Void)
  var minZoom: Int { 0 }; var maxZoom: Int { policy.detailZoom }; var tilePixelSize: Int { tilePx }
  func hasContent(_ t: TileIndex) -> Bool               // footprint.classify(t) != .outside
  func fallbackZoom(forTileZoom tz: Int) -> Int?        // bake ? min(tz-1, bake.maxZoom) : (base ready ? min(tz-1, baseMaxZoom) : nil)
  func wantedTilesDidChange(_ ordered: [TileIndex], tileZoom: Int)
  func loadTile(_ t: TileIndex, completion: @escaping (UIImage?) -> Void) -> RasterTileRequest?
  func attachBake(_ reader: PDFBakeReader?)
}
```

`loadTile` steps, in order:
1. If failed: return nil and do no work (D5-16).
2. If outside: return the empty sentinel asynchronously.
3. Take from the orphan cache.
4. Bake read on rasterQueue when `z` is in the bake range. A missing row for an intersecting tile falls through to a live render.
5. If `z ≤ baseMaxZoom`: await the base raster, then sample it on rasterQueue.
6. Otherwise: `service.request(band:.visible)` with the vector path (1 cell) or the staged path (>1 cell).

Status is reported `.preparing` until the base raster is ready or the first non-empty tile is delivered, then `.ready`. `.failed(reason)` is sticky. Three consecutive job failures give `.renderError`. `CGContext` nil gives `.outOfMemory`. Open failure gives `.cannotOpen`. Encrypted after `unlockWithPassword("")` gives `.passwordProtected`. A missing page gives `.pageMissing`. A blank base raster gives `.blank`.

### `Map/Renderer/PDF/PDFMapRuntime.swift` (`@MainActor ObservableObject`, owned by MapViewModel)

- `@Published status: PDFRenderStatus` (`.none/.preparing/.ready/.failed(PDFRenderFailure)`)
- `@Published showPreparingLabel` (true after 300 ms of `.preparing`)
- `func tileSource(for pdf: PDFMapSource, screenScale: CGFloat) -> PDFTileSource?` is cached by `(identity, georef.digest, tilePx)`. It returns nil if `pdf.georef == nil`. Service and base raster are kept across a georef change, because the base raster is in page space.
- `retry()`, `trimMemory()`, `setForeground(_:)`
- Crash-guard hooks: arm the first `base`/`vector` renders per token in the foreground.

### `Map/Renderer/PDF/PDFRenderGuard.swift`

- A pure `PDFRenderGuardReducer` (shared state machine, fixture-driven).
- `final class PDFRenderGuard` persists `Library/Application Support/pdf_render_guard.json`:
  - written with `Data.write(.atomic)` plus `FileHandle.synchronize()`
  - `isExcludedFromBackup`
  - protection `.completeUntilFirstUserAuthentication`
  - contains only random UUIDs
- API: `arm(kind:token:) -> Bool`, `complete(kind:token:)`, `disarmForBackground()`, `launchDecision(restoredToken:) -> PDFLaunchDecision`, `resolveSuspect(.openAnyway|.deleted|.notNow)`.

### `Calibration/PDFBakeController.swift` (`@MainActor ObservableObject`, `static let shared`, survives sheet dismissal)

- States: `idle`, `estimating`, `confirming(PDFBakeProposal)`, `running(done:total:)`, `failed(PDFBakeError)`. On finish it returns to `idle` with a one-shot `finishedMessage`.
- API:
  - `prepare(pdf:runtime:)` renders and encodes up to 3 inside tiles at the default option's max zoom (reusing cached tiles), then builds options with bytes and time estimates
  - `start(option:)`
  - `cancel()`
  - `cancel(contentKey:)`
  - `static cleanWorkDirectory()` runs at launch
- The worker is `Calibration/PDFBakeWorker.swift`, which replaces `PDFTiler.swift`. Flow:
  1. Acquire the same `PDFRenderService`.
  2. For z = 0 up to maxZoom, walk the intersecting tiles in bake blocks. Submit at band `.bake` and await.
  3. Encode on a `.utility` queue with ImageIO:
     - fully opaque tile: JPEG q0.85
     - otherwise: PNG
  4. Write with `MBTilesWriter` into `Application Support/pdf_bake_work/<uuid>.mbtiles.partial`. The work directory lives outside the reconcile roots, so a map switch mid-bake cannot delete it.
  5. Commit every 64 tiles.
- Publish steps:
  1. close, then fsync
  2. check the session still has the same contentKey and bakeKey, otherwise `.sourceChanged`, delete the output, and stop
  3. move the file to `Application Support/offline_tiles/<uuid>.mbtiles`
  4. set `.completeUntilFirstUserAuthentication` and `isExcludedFromBackup`
  5. persist `bake` into the sealed session (on failure, delete the file and report `.writeFailed`)
  6. `runtime.attachBake`
- The active map is never switched (D5-05, D5-06).
- `beginBackgroundTask`: the expiration handler only ends the assertion. The process may be suspended and resumes in the foreground; if it is killed, the launch guard reports `bakeInterrupted`.
- `isIdleTimerDisabled = true` while running in the foreground.
- Pause while `thermalState == .critical`.
- `volumeAvailableCapacityForImportantUsage` is checked at confirm and on write errors (ENOSPC gives `.noSpace`).

### `Map/PDFPageMarkersView.swift` (calibration shim until WP4)

- Numbered fiduciary and pending markers drawn at `georef.toWGS84(pagePoint)` and then projected.
- Inserted after `drawings` in `attach` (TileMapContainer.swift:250).

## 2. Existing code to change (HEAD lines)

### `Map/Renderer/TileMapView.swift`
- **Remove `draw(_:)` (71-94) entirely.**
  - Add `tileRoot` at `layer.insertSublayer(_, at: 0)`. Heading is applied as `tileRoot.setAffineTransform(CGAffineTransform(rotationAngle: -heading))` about the bounds centre.
  - Add `func layoutTiles()`: compute tz, check the underzoom guard, run the planner, then `compositor.apply`, cancel `inFlight` entries that are no longer wanted, issue requests, and call `source.wantedTilesDidChange`.
- `camera` didSet (11-17): replace `setNeedsDisplay` with `layoutTiles()`.
- `source` didSet (20-27): add `cache.removeAll()` and `layoutTiles()`. `layoutSubviews` (61-67) sets `tileRoot.frame` and relayouts.
- Replace the NSCache (42, 55) with `TileImageCache(byteLimit:)`. `didReceiveMemoryWarning` trims to the current plan's tiles.
- `loadTile` (98-112):
  - register the `inFlight` placeholder *before* calling the source, so a synchronous completion cannot wedge the key
  - map the empty sentinel (by `===`) to `.empty`
  - relayout on arrival, coalesced to one per runloop turn
- `cancelAllLoads` (114-117) is unchanged.
- `handlePinch` (154-172): replace the clamp at 162-165 with `MapCamera.zoomLimits` (2...22). This is the D3-08 fix.
- Add `var tilesHidden: Bool`, which sets `tileRoot.isHidden` and cancels loads.

### `Map/Renderer/TileMath.swift`
- `tileZoom` (17-19) is unchanged.
- `visibleTiles` (28-65) returns `[VisibleTile]`:
  - unwrapped col, which fixes antimeridian placement
  - dropped when the tile square inflated by 1 pt fails a SAT test against the rotated viewport
  - sorted by distance from the centre
- `tileFrame` (71-85) is replaced by `TileGrid`, which removes the per-tile MapCamera construction.
- Add `static let maxUnderzoomLevels = 2.0`.

### `Map/Renderer/MapCamera.swift`
- Add `static let zoomLimits: ClosedRange<Double> = 2...22`.

### `Map/Renderer/RasterTileSource.swift`
- Protocol (26-34): add `hasContent(_:)`, `fallbackZoom(forTileZoom:)` and `wantedTilesDidChange(_:tileZoom:)`, with defaults `true`/`nil`/no-op in an extension.
- Add `enum RasterTileSourceEmpty { static let image: UIImage }`, a 1×1 transparent image.
- Online `fetch` (128) and Offline `loadTile` (182-190): decode with `UIImage(data:)?.preparingForDisplay()` off main (D3-11).

### `Map/Renderer/TileMapContainer.swift`
- `updateUIView` (111-113):
  - `syncSource(view:mapSource:onlineBasemaps:importedMapVisible: visibility.importedMapVisible || calibration.isCalibrating)`
  - delete the `syncPDF` call
- `syncSource` (575-593) adds a `case let pdf as PDFMapSource`:
  - `key = "pdf:\(pdf.contentKey ?? pdf.url.path):\(georef.digest):\(tilePx)"`
  - `make = { mapVM.pdfRuntime.tileSource(for: pdf, screenScale: view.traitCollection.displayScale) }`
  - when hidden, `view.tilesHidden = true` and the key is unchanged, so the cache is kept
- Delete `pdfView`/`pdfMask`/`pdfSourceID` (171-174), `syncPDF` (376-406), and the PDF block in `reprojectOverlays` (311-314). Add `markersView?.reproject()`.
- `wireEditing` (296-305):
  - `editing.pdfScreenTapToPDFPoint = { pt in guard let g = currentGeoref, let p = g.toPage(view.camera.coordinate(for: pt)), footprint.contains(p) else { return nil }; return p }`
  - `refreshCalibrationMarkers` and `syncCalibrationMarkers` (414-424) drive `PDFPageMarkersView`
- `flyTo` (642-651) keeps its 2...19 framing heuristic.

### `Calibration/PDFOverlay.swift`
- Delete the whole file (23-383).

### `Calibration/PDFTiler.swift`
- Delete (1-238). Replaced by `PDFBakeWorker`.

### `Calibration/PDFMapSource.swift`
- Delete `cachedImage`/lock (35-36), `cachedRenderedImage` (80-84) and `renderedImage` (89-104).
- `pdfRenderRect` (30, 67) and the PDFKit `mediaBox` helper (70-75) go with WP1's georef.
- Expose `renderGuardToken` and `bake` from the session DTO.

### `Calibration/MBTilesWriter.swift`
- `init` (20-28):
  - create the empty file first with `FileManager.createFile(attributes: [.protectionKey: .completeUntilFirstUserAuthentication])`
  - then open it
  - `PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF`
- `putTile` (45-59): reuse one prepared INSERT.
- `writeMetadata` (32-42): gains `extra: [String:String]` for `tacmap_bake_key`, `tacmap_tile_px`, `tacmap_renderer`, and `format "jpg"`.

### `Calibration/ActiveMapSelectionStore.swift`
- `reconcileManagedImportedMapFiles` (234-270): the PDF branch (241-246) keeps `Set([source.url]) ∪ {offline_tiles/<bake.fileName> if the file exists}`. Pass the Set at 266-269.

### `Calibration/PDFSessionStore.swift`
- `contentKey(for:)` (464-478): memoise under an `NSLock`, keyed by `(standardized path, fileSize, contentModificationDate, fileResourceIdentifier)`. This removes the repeated full-file SHA-256 passes from main at 177, 293 and 297 (D5-08).
- Save and load the two new optional DTO fields.
- When a save changes the georef digest, drop `bake`, and the reconcile reaps the file.

### `Drawing/LayerVisibility.swift` (16, 46, 64)
- Rename the property to `importedMapVisible` with key `layers.importedMapVisible`, default true.
- The old `layers.pdfOverlayVisible` is never read, because the old toggle was dead and a persisted `false` must not hide maps.

### `Drawing/LayersSheet.swift`
- Delete the tiling `@State` (18-20), the "Offline tiles" alert (100-105) and `generateTiles` (142-166).
- Toggle at 174: `$visibility.importedMapVisible`, labelled "Show Imported Map". The subtitle (177-181) derives from `georef.origin`, which fixes the "LGIDict" label for /VP sheets.
- The bake UI (197-218) is replaced by `PDFBakeRow(pdf:)`, which observes `PDFBakeController.shared` and follows the shared flow and copy.
- Add a failure row "Map couldn't be drawn" with a Try Again action when the runtime status is `.failed`.

### `App/ContentView.swift`
- `ImportedMapWorker.preparePDF` (402-438), after WP1's parse and before return:
  1. mint the token
  2. `PDFRenderGuard.arm(.importProbe)`
  3. `PDFFootprint` / `PDFZoomPolicy`
  4. render the base raster on the lane (awaited via continuation)
  5. blank check
  6. `complete(.importProbe)` and mark base verified
  7. put `baseRaster` in the payload (wrapped `@unchecked Sendable`)
  Failures map to import errors with the shared failure copy.
- `handleImport` (1946-1988): `service.adoptBaseRaster` before `selectMapSource`, so the first paint is instant.
- `basemapLabel`/`basemapColor` (585-594): preparing (after 300 ms) shows "Drawing map…" in amber #FFC247; failed shows "Map couldn't be drawn" in red #FF5A5A; otherwise the existing labels.
- Add the failure alert, crash-recovery alert, import-interrupted and bake-interrupted alerts, the "Imported map hidden" capsule (styled like the no-basemap notice), and the bake progress chip with Cancel in the map chrome.

### `Map/MapViewModel.swift`
- Add `let pdfRuntime = PDFMapRuntime()` and `@Published var pdfCrashSuspect: PDFMapSource?`.
- `restoreActiveMapSelection` (254-273), `.restored` branch: if the source is a PDF and `PDFRenderGuard.launchDecision(restoredToken:) == .suppress`, then `publishRestored(OnlineRasterBasemapSource.makeDefault())` (memory only; the durable selection stays the PDF, so the reconcile keeps it) and set `pdfCrashSuspect`.
- `.deleteImported` (327-350): first call `PDFBakeController.shared.cancel(contentKey:)`.
- `publishMapSource` (402-410): reset the runtime status.

### `App/TacticalMapsApp`
- Call `PDFBakeController.cleanWorkDirectory()` and `PDFRenderGuard.shared.launchDecision` for the import/bake notices at launch.

### Localization
- Every shared string goes through L10n/Messages for all languages.

## 3. Threading

| Where | What runs there |
|---|---|
| Main | layout, planner, cache, scheduler bookkeeping, status. Never CGPDF, never decode. A debug `dispatchPrecondition(.notOnQueue(.main))` sits in renderer entry points (D3-13). |
| Lanes (1-2) | vector, staged and base-raster draws |
| rasterQueue | base sampling, bake reads/decodes |
| utility encode queue | bake JPEG/PNG encoding |
| Bake writer | a serial queue that owns the SQLite handle |

## 4. Memory (4 GB phone, USGS)
- Tile LRU: 128 MB.
- Base raster: 6 Mpx (24 MB) plus mips (+8 MB).
- Per lane: document caches about 43-75 MB, purgeable and closed on memory warning; job scratch ≤ 14 MB; staged bitmap ≤ 2× job pixels.
- Orphan cache: ≤ 8 tiles.
- Target peak about +300 MB over baseline, plus one bake job while baking.
- Memory warning: the cache is trimmed to the current plan, lane documents are closed, the orphan cache is dropped, and the base raster is kept.

## 5. Phases (each shippable)
1. TileDrawPlanner, TileImageCache, TileLayerCompositor, decode-off-main, cancellation, zoom limits, underzoom guard, antimeridian fix. This benefits every basemap; verify on MBTiles and online first.
2. Geometry, policy and warp; the renderer (raster, direct, staged); the service and scheduler; PDFTileSource and PDFMapRuntime. Then wire syncSource, visibility, status and the calibration shim, and delete the overlay.
3. Crash guard, the import probe and the restore path.
4. Bake: controller, worker, writer, DTO fields, reconcile, Layers row and chip.
5. Content-key memo.
6. Optional: double-tap (only if Android also ships it).

## 6. Tests

### Unit tests (XCTest)
- **`PDFTileRenderFixtureTests`**: loads `testdata/pdf_tile_render.json` and `pdf_georef.json`, and asserts every shared section with the fixture tolerances:
  - tilePx, zoomPolicy, basePlan and baseMaxZoom
  - warp: exact counts, max error ≤ 0.25, exact rects for unclipped cases
  - coverage, bakeOptions, jobFormation, drawPlan, crashGuard, cameraZoom
- **`PDFTileRendererTests`**, on `testdata/geopdf/tacmap_render_markers.pdf` and WP1's rot5_iso, offset_iso and rot90_iso:
  - marker centroids land within 0.5 px of the fixture tile px at 768 and 512 (D3-05, D3-06, rotate, offset)
  - alpha is 0 at the fixture's outside samples and 255 inside (D3-12)
  - a 1-pt line on rot5_iso is continuous within 1 px across a seam rendered by two separate jobs (D3-01, D2-04, D4-09)
  - base raster vs vector marker within 1 px at baseMaxZoom and baseMaxZoom+1
  - staged vs direct within 1 px
  - the OCG-off square is not drawn (gate)
  - a CGPDFContext-generated encrypted PDF fails with `.passwordProtected`
  - a blank page fails with `.blank`
- **`TileLayerCompositorTests`**: snapshot via `tileRoot.render(in:)`:
  - the ancestor contentsRect quadrant orientation
  - a 3×3 opaque grid at 17° heading with a fractional origin has 0 background pixels (D4-16)
  - no background pixels while going from a z13-only cache to zoom 13.6 (D3-11)
- **`TileMathTests`**: rotated filter vs brute force; unwrapped columns at ±180; underzoom guard.
- **`PDFRenderSchedulerTests`** (fake render closure, injectable clock):
  - the 150 ms settle
  - heavy growth to ≤ 6 tiles and ≤ 3 per side
  - pre-start cancel drops the job
  - a running job's unwanted tiles go to the orphan cache
  - band order
  - bake never starts while visible is pending
  - EWMA flips at 60 ms
- **`PDFRenderGuardTests`**: fixture transitions, plus a durable write and re-read.
- **`PDFBakeTests`**, baking sf_iso to a small maxZoom:
  - the PDF still exists and `session.bake` is set
  - PNG edge tiles have alpha
  - JPEG interior PSNR ≥ 35 dB vs live
  - cancel removes the `.partial`
  - `.sourceChanged` discards
  - `.partial` and final have `FileAttributeKey.protectionKey == .completeUntilFirstUserAuthentication`
  - a mid-bake reconcile leaves the work dir intact
  - the reconcile keeps the PDF and the bake; deleting removes both
- **`TileMapViewTests`**:
  - a pinch of 1.01 at z18 over a maxZoom-16 source gives about 18.014
  - a synchronous completion does not wedge
  - `tilesHidden` works
  - a source swap clears the cache
- **ContentKey memo:** one hash per identity.
- **Existing tests to update:**
  - `PDFImportSmokeTests` 10-20 and 52: delete
  - `PDFImportSmokeTests` 115-133: assert the marker location via the renderer
  - `AffineFitterTests` 182-240: delete the overlay placement tests
  - the `RasterTileSource` test doubles
- The USGS tests skip when the sample file is absent.

### On device (A15 iPhone, a 3 GB 2x device, and a 2x iPad)
- **Pinch sweep** z8->22->8 over USGS and a light sheet, with Instruments Time Profiler and Animation Hitches:
  - ≤ 2 ms of main-thread tile work per frame
  - no hitch over 33 ms
  - no background-coloured frames inside the sheet once the base raster exists
- **Time-to-crisp** via os_signpost:
  - light viewport ≤ 0.5 s
  - USGS z16 viewport ≤ 2.0 s on A15, with the fallback visible throughout
  - import to first pixels ≤ 3 s for USGS
- **Memory:** VM Tracker peak; Simulate Memory Warning.
- **Alignment:** `scripts/grid_alignment.py` ≤ 1 px at z10/12/14/16/18 with WP3's grid; a 4° calibrated plain sheet; heading 30°; antimeridian pan on an online source.
- **Bake:**
  - start, dismiss the sheet, reopen and see progress
  - lock for 60 s with GPX recording
  - cancel
  - kill mid-bake, relaunch, see the notice and no residue
  - delete the map and confirm the PDF and bake are both gone
- **Crash guard:** a DEBUG `TACMAP_PDF_ABORT_FIRST_VECTOR=1` aborts; relaunch shows online plus the alert. Check the Open Anyway, Not Now and Delete paths.
- **Night mode:** colorMultiply still tints CALayer tiles.
- **Visibility:** toggle on and off; toggle during calibration.

## 7. Risks
- **Heavy PDFs.** The per-draw floor is about 225 ms on A15 for USGS, and job coalescing only amortises it. The fallback keeps the map covered, and the bake gives an instant open.
- **CALayer rewrite.** It touches every basemap: heading rotation, z-order below the overlays, and the contentsRect orientation. The snapshot tests are the guard.
- **Optional content.** CG may draw optional content that the producer hides. The OCG gate test decides; if it fails, switch the vector draw to PDFKit `PDFPage.draw(with: .mediaBox, to:)` after concatenating the inverse of `page.transform(for: .mediaBox)`, with one PDFDocument per lane.
- **Annotations.** CG does not draw annotations, whereas Android's pdfium does. This is a documented parity gap.
- **Georef swap.** A source swap on a georef change clears the cache, so there is a brief fallback-only flash. WP4 may add stale-generation fallback.
- **Merge conflicts** with WP1 (PDFMapSource, PDFSessionStore, preparePDF) and WP5 (import worker).
