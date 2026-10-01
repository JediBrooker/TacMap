
# iOS design for WP4 + WP5

All line references are HEAD `77504db`. Paths are under `ios/TacticalMaps/`.

The design assumes WP1's `PdfGeoreference` and fitter, and WP2's `PdfTileSource`. Both are accessed only through `Calibration/GeorefAdapters.swift`.

The shared contract fixes every number, rule, message key and fixture. This section says where the code goes.

## 1. File plan

### New files: pure logic (XCTest without UI)

**`MGRS/CoordinateInputParser.swift`**
- `enum CoordinateInputParser { static func parse(_ raw: String, context: CoordinateParseContext) -> Result<ParsedReference, CoordinateParseError> }`
- `struct CoordinateParseContext { datumID: String; isFirstPoint: Bool; kind: CalibrationPointKind; gridAnchor: GridAnchor?; predicted: CLLocationCoordinate2D? }`
- Reuses code from `MGRSFormatter.swift`:
  - `fullGridParts` (:198-206)
  - `isSafeUTMMGRS` (:211-226); both change from `private` to `internal`.
- Also reuses the decimal logic of `OfflineSearchEngine.decimalCoordinate` (`SearchSheet.swift:308-326`), which becomes `internal static` so the parser can share it.
- Search itself does not change.

**`Calibration/CalibrationPoint.swift`**
- `struct PagePoint`, unless WP1 provides one.
- `enum CalibrationPointKind { intersection, feature }`
- `enum CalibrationReference: Codable { grid(zone:, south:, easting:, northing:, cellSizeM:, source: .mgrs|.utm), geographic(latitude:, longitude:) }`
- `struct CalibrationPoint: Codable, Identifiable { id: UUID; number: Int; page: PagePoint; input: String; reference; kind; datumOverride: String?; label: String? }`
- `func resolved(sheetDatum: GeoDatum) -> (lat: Double, lon: Double)?`, which returns lat/lon in the sheet datum (shared §5).

**`Calibration/CalibrationState.swift`**
- `struct CalibrationTarget: Codable, Equatable { entryID: UUID; contentKey: String; pageIndex: Int; pageBox: [PagePoint]; rotate: Int }`
- `struct CalibrationState: Codable, Equatable { target; datumID: String?; points: [CalibrationPoint]; nextNumber: Int; func applying(_ e: CalibrationEdit) -> CalibrationState }`
- `enum CalibrationEdit { add(CalibrationPoint), move(UUID, PagePoint), editReference(UUID, input: String, reference: CalibrationReference, kind: CalibrationPointKind, datumOverride: String?, label: String?), delete(UUID), setDatum(String) }`

**`Calibration/CalibrationFitReport.swift`**
- `static func evaluate(_ s: CalibrationState, fitter: FiduciaryFitting) -> FitReport`
- `struct FitReport`, with these fields:
  - `n`, `planeZone`, `south`, `diagonalM`, `toleranceM`
  - `rmsM`, `maxM`, `residualsM: [UUID: Double]`, `looRmsM`, `looM`
  - `grade: FitGrade?`, `outlier: UUID?`, `ambiguous: [UUID]`
  - `disagreeM`, `disagreeAddFifth`, `spreadLow`, `nextCorner: PageCorner?`
  - `anisotropy`, `scaleDenominator`
  - `finishability: Finishability`
  - `georef: PdfGeoreference?`, the fit with `crop = pageBox`
- `enum Finishability { blocked(BlockReason), confirm([ConfirmReason]), ready }`
- `static func entryCheck(state:, report:, pending: PagePoint, reference: CalibrationPoint, editing: UUID?, base: PdfGeoreference) -> EntryCheck?`, which carries `distanceM`, `thresholdM` and `warn`.
- The leave-one-out pass is n calls to the WP1 fitter; n ≤ 50, so it takes under 1 ms.

**`Calibration/CalibrationCameraAnchor.swift`**
- `static func adjust(camera: MapCamera, from: PdfGeoreference, to: PdfGeoreference, zoomRange: ClosedRange<Double> = 2...22) -> MapCamera?`
- `static func screenPointsPerPagePoint(_ g: PdfGeoreference, at: PagePoint, zoom: Double) -> Double?`

**`Calibration/ImportLimits.swift`**
- The constants from `import_limits.json`.
- `enum ImportDecision { static func decide(_ inspection: PDFInspection, duplicateOf: ImportedMapEntry?) -> ImportOutcome }`

**`Calibration/GeorefAdapters.swift`**
- The WP1/WP2 seam.
- `protocol FiduciaryFitting` is the fitter wrapper.

### New files: storage

**`Calibration/CalibrationDraftStore.swift`**
- `enum CalibrationDraftStore`, with `load(key:) -> SafeStore.Load<CalibrationDraft>`, `save(_:) throws`, `delete(key:)`, `prune(keeping contentKeys: Set<String>)`, `activeDraft() -> CalibrationDraft?`.
- Label `calibration/drafts_v1`; file in Application Support.

**`Calibration/ImportedMapLibrary.swift`**
- `enum ImportedMapLibrary`, with `load() -> SafeStore.Load<LibraryState>`, `write(_:) throws`, `source(for: ImportedMapEntry) -> MapSource?` (size+mtime check only), `entryState(_:) -> ImportedMapState`, `managedFiles(_ state:) -> Set<URL>`.
- `struct LibraryState: Codable { schemaVersion; active: MapSelection?; preferredOnlineStyle; entries: [ImportedMapEntry] }`
- Label `map_library/v1`; file `Application Support/imported-map-library.json`.
- `enum InFlightImportFiles`: a locked, process-wide `Set<URL>`.

**`Calibration/ImportedMapLibraryMigration.swift`**
- `static func migrateIfNeeded() -> MigrationResult`, following shared §8.2.
- Reads through the legacy readers kept in `ActiveMapSelectionStore` and `PDFSessionStore`.

**`Calibration/ImportedMapStorage.swift`**
- Moved from `ContentView.swift:6-134` (`ImportedMapFileCopier`).
- `importedMapsDirectory()` and new `offlineTilesDirectory()`:
  - created with `.completeUntilFirstUserAuthentication`;
  - `isExcludedFromBackup` set on every call.
- `opaqueDestination(id:ext:)` replaces `uniqueDestination` (:102-123).
- `copy(... observeChunk: (Data) throws -> Void)` feeds SHA-256 and progress (hooked into the chunk loop at :74-86).
- `excludeFromBackup(_ url:)`
- `sweepBackupExclusion()`, called at launch.

### New files: app layer and UI

**`App/MapImportPipeline.swift`**
- Replaces `ImportedMapWorker` (`ContentView.swift:376-473`) and `PDFMapImporter.swift`, which is deleted.
- `static func preparePDF(url:, progress: @Sendable (ImportProgress) -> Void) async throws -> PreparedPDFImport`
- `static func prepareMBTiles(url:, progress:) async throws -> PreparedMBTilesImport`
- `struct PDFInspector { static func inspect(_ url: URL, cancel: () -> Bool) throws -> PDFInspection }`:
  - one `CGPDFDocument`;
  - `isEncrypted` with `unlockWithPassword("")`;
  - page count and box limits;
  - WP1 `georeferences(document:, pages: 0..<min(50, n))` plus catalog.

**`App/MapImportController.swift`**
- `@MainActor final class MapImportController: ObservableObject`
- `@Published progress: ImportProgress?`, `@Published pagePicker: PreparedPDFImport?`, `@Published rejectedPrompt: ImportedMapEntry?`
- `func importPDF(_ result:)`, `func importMBTiles(_ result:)`, `func cancel()`, `func choosePage(_ index: Int)`, `func cancelPagePicker()`
- Owns `importWorkTask`, which replaces `ContentView.swift:517`.

**UI files**
- `App/ImportProgressHUD.swift`
- `Calibration/PDFPagePickerSheet.swift`
- `Calibration/CalibrationPanel.swift`
- `Calibration/CalibrationEntryCard.swift`
- `Calibration/CalibrationPointsSheet.swift`
- `Calibration/CalibrationDatumSheet.swift`
- `Calibration/CalibrationReticle.swift`
- `Map/Renderer/CalibrationMarkersOverlayView.swift`
- `Drawing/ImportedMapsSection.swift`

### Rewritten

**`Calibration/CalibrationSession.swift`** (whole file, :12-105). This is the `@MainActor` shell, §3.

### Deleted

- `Calibration/CalibrationOverlay.swift`
- `Calibration/CalibrationInputSheet.swift`
- `Calibration/PDFMapImporter.swift`
- In `PDFOverlay.swift`:
  - `pdfPoint(forScreenTap:)` (:282-300)
  - `localPoint` (:302)
  - `syncFiduciaryMarkers` (:312-341)
  - `makeMarker` / `makePendingMarker` (:343-382)

### Modified

Detailed in §4-§8:
- `App/ContentView.swift`
- `Map/MapViewModel.swift`
- `Map/Renderer/TileMapContainer.swift`
- `Map/Renderer/TileMapView.swift` (small, coordinated with WP2)
- `Map/Renderer/MapEditingController.swift`
- `Map/MGRSHeaderView.swift`
- `Map/MapSideToolbar.swift` (`HamburgerMenu`: `importsEnabled`)
- `Drawing/LayersSheet.swift`
- `Calibration/ActiveMapSelectionStore.swift` (becomes a legacy reader)
- `Calibration/PDFSessionStore.swift` (legacy reader plus `contentKey`)
- `Calibration/PDFTiler.swift:41-46` (uses `ImportedMapStorage.offlineTilesDirectory()`)
- `Resources/*.lproj`, generated from the catalog

## 2. Pure layer notes

- The fit plane, τ, grades, the outlier set S, blocked/confirm and the entry check follow shared §5-6 exactly.
- `FitReport.georef` is WP1's fit, returned with `crop` set to `pageBox`.
- `PageCorner` naming applies `rotate` so that the corners match the upright view.
- Datum resolution uses the WP1 `GeoDatum` table.
- `oldLettering` applies when `datum.ellipsoid ∈ {Clarke1866, Clarke1880IGN, Bessel1841}`.

## 3. CalibrationSession (`@MainActor final class CalibrationSession: ObservableObject`)

### Published state
- `isCalibrating`
- `phase: CalibrationPhase` (`.placing`, `.entering(Pending)`, `.moving(UUID)`, `.datumSheet`, `.pointsSheet`, `.leaveDialog`, `.finishConfirm([ConfirmReason])`, `.resumePrompt(CalibrationDraft)`)
- `state: CalibrationState?`
- `report: FitReport`
- `selectedID`
- `display: CalibrationDisplay?`, holding `(georef, generation)`
- `draftUnsaved`
- `entryText`, `entryKind`, `entryParse`, `entryCheck`
- `canUndo`

### Private state
- `base: PdfGeoreference`, which is either:
  - provisional, embedded or the saved manual georef; or
  - `seed`, `undo: [CalibrationState]` (capped at 50), `startState`, `wasPreview: Bool`.
- The session holds identity strongly as `CalibrationTarget`. This replaces the weak `source` at :35.

### API
- `start(entry: ImportedMapEntry, base: PdfGeoreference, seed: ManualCalibration?, draft: CalibrationDraft?, resumeSilently: Bool)`
- `chooseDatum(_ id: String)`
- `beginAdd(capture: CalibrationCapture) -> AddResult`, returning `.ok`, `.offSheet` or `.maxPoints`. It also writes the draft with `pending`.
- `updateEntryText(_:)`, which parses live.
- `useGPS(_ location: CLLocation)`
- `moveEntryToCrosshair(capture:)`
- `commitEntry()`
- `cancelEntry()`
- `select(_:)`
- `beginMove(_:)`
- `confirmMove(capture:)`
- `cancelMove()`
- `beginEdit(_:)`, which opens the entry card prefilled and uses the leave-one-out predictor.
- `delete(_:)`
- `undo()`
- `finishTapped()`, which moves to `blocked`, `.finishConfirm` or `commit`.
- `commit() -> ManualCalibration?`
- `leaveTapped()`
- `leave(keepDraft: Bool)`
- `suspend()`
- `onDataKeyUnlocked()`, which retries the draft write.

### Mutation pipeline
Every mutation runs these steps:
1. `push(undo)`
2. `state = state.applying(edit)`
3. `report = CalibrationFitReport.evaluate`
4. `display = nextDisplay()`: bump `generation` only if the georef changed. The rule is `report.georef` when the fit is not blocked and n ≥ 3, else `base`.
5. `CalibrationDraftStore.save(...)` runs synchronously. On a thrown error, set `draftUnsaved = true`.

### Draft storage
- The sealed payload is about 12 KB at 50 points.
- A write takes 2-5 ms on the main thread.
- The typed text is never persisted.

## 4. Renderer plug-in

### `Map/Renderer/TileMapContainer.swift`

**Container parameter**
- Keep `calibration: CalibrationSession` (:71).

**`updateUIView` (:102-140)**
- Right after `syncSource`, call `context.coordinator.syncCalibrationDisplay(calibration.isCalibrating ? calibration.display : nil, view:)`. This replaces `syncCalibrationMarkers` at :131 and :416-424.
- Pass effective visibility into `updateOverlays` and the waypoint projection while calibrating:
  - `drawings: []`, `peers: [:]`, `decorations: .empty`, `handles: []`
  - `gridVisible: calibration.gridOn && !displayProvisional` (instead of `visibility.mgrsGridVisible`)
  - user location hidden while provisional
  - heatmap off
- Set `view.isRotationGestureEnabled = opsec.mapOrientationMode == .northUp || calibration.isCalibrating` (:107).

**`Coordinator.attach` (:218-275)**
- Add `CalibrationMarkersOverlayView`. Insert it above `grid` and below `drawings` in the :250 array, with `project` set.
- Sink the new `mapVM.zoomStepRequests`: set `camera.zoom` clamped to 2...22.
- Sink the new `mapVM.centreRequests`, which set the centre while keeping zoom and heading.
- Set `mapVM.calibrationCapture = { [weak self] in self?.captureCalibrationPoint() }`. It reads `view.camera` live, so there is no dependence on the `publish` debounce.

**`wireEditing` (:279-306)**
- Delete the `pdfScreenTapToPDFPoint` and `refreshCalibrationMarkers` closures (:296-305).
- Add `editing.calibrationMarkerHitTest = { [weak self] pt in self?.markersView?.pointID(at: pt, radius: 24) }`.

**`reprojectOverlays` (:310-332)**
- Add `markersView?.reproject()`.

**New `syncCalibrationDisplay(_:view:)`**
- If the generation differs from `installedGeneration`, start a transition (shared §7.2):

```swift
let target = CalibrationCameraAnchor.adjust(camera: view.camera, from: installedGeoref, to: new.georef) ?? view.camera
let source = GeorefAdapters.pdfTileSource(entryID:, georef: new.georef, generation:)
view.transition(to: source, camera: {
  CalibrationCameraAnchor.adjust(camera: view.camera, from: installedGeoref, to: new.georef) ?? view.camera
}, prefetchFor: target, deadline: .milliseconds(400)) { [weak self] in
  self?.installedGeoref = new.georef
  self?.installedGeneration = new.generation
  self?.markersView?.update(...)
}
```

- The camera is re-anchored against the *current* camera at swap time.
- On the first install (session start), there is no anchor: install directly.
- When the session ends:
  - set `installedGeoref = nil`;
  - WP2's normal `syncPDF` path takes over;
  - the display returns to the entry's effective georef or the restored source.

**New `captureCalibrationPoint() -> CalibrationCapture?`**
- `page = installedGeoref.toPage(view.camera.center)`
- `onSheet = pageBox.contains(page)`
- `screenPtPerPagePt`
- `generation`
- The session rejects a capture whose generation is not current (defence in depth).

**`syncPDF` (:377-406)**
- This is WP2's.
- Until WP2 lands, the fallback is to map `installedGeoref` into the existing `PDFImageOverlayView` placement: an affine least-squares fit through the georef's pageBox corners and centre, used as the display only.
- Capture and markers still use `toPage`/`toWGS84`, so recorded points stay exact.

### `Map/Renderer/TileMapView.swift` (coordinate with WP2)

Add the transition function:

```swift
func transition(to newSource: RasterTileSource,
                camera: @escaping () -> MapCamera,
                prefetchFor: MapCamera,
                deadline: DispatchTimeInterval,
                completion: @escaping () -> Void)
```

What it does:
1. Asks `newSource` for the tiles visible at `prefetchFor`, into a staging `NSCache`, via WP2's `prefetch` base-raster path.
2. On completion or deadline, on the main thread and in one turn:
   - bump `sourceGeneration`;
   - replace `cache` with the staging cache (no `removeAllObjects` of staged tiles);
   - set `source` without the `didSet` clear;
   - set `camera = camera()`;
   - call `setNeedsDisplay`;
   - call `completion`.
3. A newer call cancels the older one.

Other notes:
- Today `source.didSet` (:20-27) clears the cache, which is why this new path is needed.
- Flag for WP2: `cache.countLimit = 400` (:55) should become `totalCostLimit` (about 0.88 GB worst case at 3x).
- Pinch clamp (:162-163): WP2 removes the hard PDF clamp. Calibration relies on the global 2...22 range.

### `Map/Renderer/CalibrationMarkersOverlayView.swift` (new UIView)

**Model**
- `points: [(id, number, page, flagged, residualTarget: CLLocationCoordinate2D?)]`
- `pending: PagePoint?`
- `movingGhost: PagePoint?`
- `selectedID`
- The WGS84 position of each point is cached per generation. Per frame the only work is `camera.screenPoint`.

**Drawing**
- Draws the marker states from shared §7.5.
- `pointID(at:radius:)` does the hit test.
- Up to 50 markers take under 1 ms to redraw.

### `Map/Renderer/MapEditingController.swift`
- The calibration branch in `onTap` (:144-151) becomes:

```swift
if calibration?.isCalibrating == true {
    let id = calibrationMarkerHitTest?(pt)
    calibration?.select(id)
    return
}
```

- Delete the properties at :73-76 and add `calibrationMarkerHitTest`.
- The long-press gates at :232 and :279 stay as they are.

### `Calibration/CalibrationReticle.swift`
- Used by `ContentView` instead of `CrosshairOverlay` while calibrating (:818-823).

## 5. ContentView wiring (`App/ContentView.swift`)

### Session objects
- Keep `@StateObject calibration` (:485).
- Add `@StateObject importController = MapImportController(mapVM:)`.

### `startCalibration(entryID:)` (replaces :1914-1917)
Runs the shared §2.3 sequence:
1. `drawingSession.cancel()`
2. `measureSession.cancel()`
3. `drawingsPanelOpen = false`
4. `mapVM.selectedWaypointID = nil`
5. `mapVM.selectedDrawingID = nil`
6. `drawingControlsPreview = nil`
7. `mapPressPoint = nil`
8. `quickSymbolDraft = nil`
9. `mapVM.calibrationActive = true`, which gates the first-fix recentre and sets `isBrowsing = true`
10. Then:
    - if the entry has no effective georef: `mapVM.beginCalibrationPreview(entry)`;
    - else if it is not active: `mapVM.activateLibraryEntry(id)`;
11. then `calibration.start(...)`.

### Layers
- The `LayersSheet(onCalibrate:)` wiring at :998-1004 passes the `entryID`.

### Auto-resume
- After `restoreActiveBasemap()` (:945), if `CalibrationDraftStore.activeDraft()` finds an existing entry, call `startCalibration(entryID:, resumeSilently: true)`.

### Header coordinate and heading
- `headerCoordinate` (:596-600) and the heading receiver (:953-957): add `&& !calibration.isCalibrating` to the heading-up gate.
- `mapVM.userLocationDidUpdate` (`MapViewModel.swift:464-477`) returns early on the first fix while `calibrationActive`, but still records `lastUserCoordinate`.

### `hudOverlay` (:1314-1545) while calibrating
- **Header** (`MGRSHeaderView` call at :1316-1346): pass
  - `calibrationReadout: .notGeoreferenced` (provisional),
  - or `.preview(showTag:)`,
  - or `nil`,
  - and `basemapLabel = calibration_header_label` in amber (`basemapLabel` at :585-589).
  - `onDropPin` is nil while provisional.
- **Hidden elements**: guard each with `if !calibration.isCalibrating`:
  - `HamburgerMenu` (:1367-1461)
  - `TacMapChatShortcutButton` (:1463)
  - `QuickAddSymbolButton` (:1470-1477; already gated)
  - `UndoRedoButtons` (:1512-1519)
  - `LockButton` (:1520-1527)
  - `TacticalSymbolOverlay` (:787-795): `.opacity(0)` while calibrating
- **Bottom bar**: the calibration branch of `hudBottomBar` (:1552-1559) becomes `CalibrationPanel(session:, onFinish:, onLeave:, onZoom:)`. The ✕ button and the zoom buttons are overlays in the same ZStack.
- **Entry card**: `CalibrationEntryCard` is a top overlay whenever `phase == .entering`.

### Sheets and alerts
- Remove the `CalibrationInputSheet` `nightSheet` (:1006-1016).
- Add `nightSheet`s with detents `.medium`/`.large` for `CalibrationPointsSheet` and `CalibrationDatumSheet`.
- Alerts, bound to `calibration.phase`:
  - leave dialog: `confirmationDialog` with the three buttons
  - finish confirm
  - resume prompt
  - save-failed alert with Retry
- The persistence alert Retry at :1235-1239 no longer calls `calibration.cancel()`. Commit handles its own retry.

### `finishCalibration` (replaces :1919-1944)
```swift
guard let manual = calibration.commit() else { return }
if mapVM.commitCalibration(entryID: calibration.target.entryID, manual) {
    calibration.endAfterCommit()
    toast(done)
} else {
    calibration.phase = .saveFailed
}
```
- No framing.

### Source-change suspend
```swift
.onChange(of: mapVM.mapSource.id) { _ in
    if calibration.isCalibrating,
       (mapVM.mapSource as? PDFMapSource)?.entryID != calibration.target?.entryID {
        calibration.suspend()
    }
}
```

### Imports
- `handleImport` (:1946-1988) becomes `importController.importPDF(result)`.
- `handleMBTilesImport` (:1993-2027) becomes `importController.importMBTiles(result)`.
- `HamburgerMenu` gains `importsEnabled: importController.progress == nil`.
- Overlays:
  - `ImportProgressHUD(controller:)`
  - `nightSheet(item: $importController.pagePicker)` presenting `PDFPagePickerSheet`
  - the rejected-georef alert
  - the interrupted-import alert at launch

## 6. Library, selection and MapViewModel (`Map/MapViewModel.swift`)

### Dependencies
`MapSelectionDependencies` (:74-94) is replaced by `LibraryDependencies`:
- `load`
- `write`
- `reconcile(state, inFlight)`
- `migrate`
- `sourceFor(entry)`
- `verifyInBackground(entry)`
- `deleteFiles([URL])`
- `drafts: CalibrationDraftStoring`

### Transitions
`PendingMapSelectionTransition` (:96-101) is replaced by `LibraryTransition`:
- `selectOnline(BasemapStyle)`
- `activateEntry(UUID, reframe: Bool)`
- `addEntry(ImportedMapEntry, activate: Bool)`
- `commitCalibration(UUID, ManualCalibration)`
- `updateEntry(UUID, EntryUpdate)`
- `deleteEntry(UUID)`
- `restoreActive`

`executeMapSelectionTransition` (:307-376) becomes `execute(_:)`:
1. load the state (Loaded, else issue + Retry);
2. build the candidate state (pure `LibraryTransitionReducer`, which is unit-tested);
3. `write` (the single commit point);
4. publish;
5. for delete: `closeForDeletion`, draft delete and file unlink;
6. reconcile.

On failure, `reportMapSelectionIssue` reuses the existing alert and Retry.

`ActiveMapSelectionCommitCoordinator` (:26-72) is deleted. Its session-first ordering is no longer needed: one store, one write.

### Public API (replacing :232-301)
- `selectMapSource(_:)`, kept for online styles only
- `selectOnlineBasemap`
- `activateLibraryEntry(_:)`
- `addImportedEntry(_:activate:)`
- `commitCalibration(entryID:_:)`
- `updateLibraryEntry`
- `deleteLibraryEntry`
- `beginCalibrationPreview(_ entry:)`, which publishes a `PDFMapSource(entry:, georef: provisional)` without writing, and remembers `previewReturn = mapSource`
- `endCalibrationPreview()`, which publishes `previewReturn` with `reframe: false`
- `@Published private(set) var library: LibraryState?`, read by Layers
- Removed: `restoreRetainedMap`, `restoreRetainedMapSelection`, `deleteRetainedImportedMap`, `removeUnavailableRetainedMapEntry`

### Other changes
- `publishMapSource(_:reframe: Bool = true)` (:402-410): call `frameCamera` only when `reframe` is true.
- New properties:
  - `calibrationActive`
  - `calibrationCapture: (() -> CalibrationCapture?)?`
  - `zoomStepRequests = PassthroughSubject<Double, Never>()`
  - `centreRequests = PassthroughSubject<CLLocationCoordinate2D, Never>()`

### `restoreActiveMapSelection` (:254-273)
1. `migrate` if the library is Empty and legacy state is present.
2. `load`.
3. `sourceFor(active)`: size and mtime checked on the main thread, no hash.
4. Publish.
5. Reconcile.
6. Prune drafts.
7. Check the interrupted-import marker.
8. `verifyInBackground(activeEntry)`: SHA-256 on a `.utility` task. On mismatch, go back to the main thread, mark the entry unavailable and select online.

### `PDFMapSource`
- `init(entry:, fileURL:, georef:)`
- `entryID`
- `displayName` from the entry
- `kind` derived from `georef.origin`
- Coordinate this with WP1/WP2, which also touch this type.

### Legacy readers
- **`ActiveMapSelectionStore.swift`**:
  - keep `loadState`/`decodeState` (:354-392) and `source(for:)` (:394-415) as `LegacySelectionReader`, used only by the migration;
  - delete `save` (:111-138), `reconcileManagedImportedMapFiles` (:234-270) and `removeRetainedMap` (:276-337), after moving their tests.
- **`PDFSessionStore.swift`**:
  - `load` (:143), the library (:400-460) and `clear` (:261) are used only by the migration;
  - keep `contentKey(for:)` (:464-478);
  - `ManagedImportedMapFileLifecycle.reconcile` (:515) is reused unchanged, except that `isCrashResidueName` (~:604-620) skips any URL in `InFlightImportFiles`.

## 7. Import pipeline (§9 of the contract)

### Running `MapImportController.importPDF`
1. Check the library state:
   - Loaded; otherwise `map_import_unlock_first`
   - fewer than 100 entries
2. Start a `Task`. The HUD appears after 300 ms.
3. `MapImportPipeline.preparePDF` runs in `Task.detached(priority: .userInitiated)`:
   1. Coordinated, security-scoped read (existing).
   2. Checks: size limit; free space via `volumeAvailableCapacityForImportantUsageKey` ≥ size + 64 MiB.
   3. `ImportedMapStorage.copy` into `ImportedMaps/map-<uuid>.pdf.partial`:
      - the partial is registered in-flight;
      - SHA-256 is computed and progress reported per 1 MiB chunk;
      - `Task.checkCancellation` runs per chunk;
      - then rename to `.pdf`, `excludeFromBackup`, and cufua protection.
   4. Write the `.import-inspecting` marker.
   5. Run `PDFInspector.inspect` under a 30 s watchdog, implemented as a race of the inspector with `Task.sleep`. On timeout, throw `.tooComplex` and abandon the worker; the copy is deleted when the worker returns.
   6. Remove the marker.
4. Back on the main actor: `ImportDecision.decide`, then one of:
   - `mapVM.addImportedEntry(activate:)` and a toast;
   - `pagePicker = prepared`;
   - `rejectedPrompt`;
   - `startCalibration`.
5. Unregister the in-flight file after the commit.

**On any error or cancel**: remove the partial or copy and the marker, and set `importMessage` from `ImportLimits.errors`.

### Page picker
- `PDFPagePickerSheet` shows a `LazyVGrid` of thumbnails:
  - 120 pt wide at 2x;
  - `PDFPage.thumbnail(of:for:)` on one serial background actor;
  - `NSCache` `countLimit` 24;
  - about 0.33 MB each, so at most 8 MB.
- The fallback cut is a plain list of "Page n".

### MBTiles
- `prepareMBTiles` uses the same copy, hash and progress, then validates with `MBTilesStore(url:)`.

### Behaviour change from today
- Today's silent cancel of a running import (:1954) is removed. Import items are disabled instead.

## 8. Layers (`Drawing/LayersSheet.swift`)

### Replaced code
- `importedMapSection` (:170-270)
- The retained rows in the basemap section (~:293-311)
- `refreshRetainedMap` (:320-340)
- `deleteRetainedImportedMap` (:342-351) and its alert (:112-118)
- `restorableImportedMap`/`retainedMapError`/`confirmingImportedMapDeletion` state (:25-27)

### New section
- They are replaced by `ImportedMapsSection(library: mapVM.library, drafts:, onActivate:, onCalibrate:, onDelete:, onGenerateTiles:)`, which implements the rows, menu, delete confirm and footer from shared §10.
- The size comes from `byteCount`. There is no hashing in `onAppear` (D5-08).

### Moved and removed
- `generateTiles` (:144-166) hands off to WP2's bake API. The bake adds a derived entry and never selects-and-deletes.
- The `pdfOverlayVisible` toggle (:174) is removed, or kept only if WP2 honours it (D5-17).

## 9. Threading and memory

**Main actor**
- Session, parse (< 1 ms per keystroke), fit plus leave-one-out (< 1 ms at n ≤ 50).
- Draft write (sync, 2-5 ms).
- Library write: about 2 KB per entry, plus about 10 KB when an entry holds 50 manual points; 1-10 ms, on user actions only.
- Marker redraw (< 1 ms).
- One-time migration: the same two hashes that every launch pays today, then never again.

**Detached / utility**
- Copy and hash:
  - about 0.35 s of CPU per 512 MiB, overlapped with I/O;
  - the separate hash read is gone.
- Inspection.
- Thumbnails.
- The background integrity hash of the active entry.

**WP2 queue**
- Tile renders and the transition prefetch.
- The prefetch holds one extra visible tile set at 2x, 15-36 MB, released at the swap.

## 10. Test plan

### Unit tests (XCTest; loading shared fixtures via `TestFixtures`)

**`CoordinateInputParserTests`**
- Every case in `calibration_input.json`.
- `search_contract.json` still passes.

**`CalibrationFitReportTests`**
- Every entry in `cases`, `entryChecks` and `capture`.
- The D2-05, D2-03 and D2-10 vectors.

**`CalibrationCameraAnchorTests`**
- The `cameraAnchor` vectors, using the real `MapCamera`.
- After adjustment, `old.toPage(centre) == new.toPage(newCentre)` within 1e-6 pt.

**`CalibrationStateTests`**
- Stable numbering.
- Undo restores an equal state.
- The 50-point cap.

**`CalibrationSessionTests`** (fake draft store, fake capture)
- An off-sheet capture is rejected.
- The draft is written on every mutation.
- A draft-write failure sets `draftUnsaved`, and unlock retries it.
- A resume prompt versus a silent resume.
- The dirty-leave dialog.
- Suspend on a source change.
- Commit with a mismatched target writes nothing.
- A generation bump happens only on a georef change.

**`CalibrationDraftStoreTests`**
- The raw file contains no MGRS text or name.
- A draft for A is never returned for B.
- LRU eviction at 16.
- A locked key means no write.

**`ImportedMapLibraryTests`** (rewrite of the `ActiveMapSelectionStoreTests` cases that assume one retained map, including :288, :649 and :770)
- Import B keeps A and A's tiles.
- Delete removes the file, WAL/SHM, derived tiles and draft.
- Reconcile keeps in-flight files.
- Locked or corrupt state deletes nothing.
- Restore does no hashing on main: a spy counts `contentKey` calls.
- The background mismatch path marks the entry `unavailable`.

**`ImportedMapLibraryMigrationTests`**
- Frozen v1 and v2 legacy fixtures.
- An injected crash after the link, after the write and after the unlink; each case is idempotent.
- A camera-fallback entry becomes `needsCalibration` and triggers the one-time alert.

**`ImportedMapStorageTests`**
- Opaque names.
- `isExcludedFromBackup` on the directories and files after import, rename and bake publish.
- cufua protection.
- The streamed hash equals `PDFSessionStore.contentKey(for:)`.

**`MapImportPipelineTests`**
- Cancel at every stage leaves no files.
- Limits:
  - a CGPDFContext-generated encrypted PDF;
  - a 501-page PDF;
  - a 20,000 pt page;
  - 512 MiB + 1 (sparse file).
- Page 0 plain plus page 1 /VP auto-picks page 1.
- One document open (counter hook).
- `performedWorkOffMainThread`.
- A leftover marker produces the interrupted alert.

**`ImportDecisionTests`**
- The `import_limits.json` `decisions` vectors.

**`ImportLimitsContractTests`** and **`LocalizationTests`**
- The new keys and plural families.

### UI tests (XCUITest)
A DEBUG-only launch argument, `-TACMAP_UITEST_IMPORT <fixture>`, runs the pipeline on a bundled `testdata/geopdf/tacmap_grid_*_plain.pdf` or a GeoPDF.

1. A plain sheet shows the datum sheet, then "NOT GEOREFERENCED" in the header. Pan, pinch and the zoom buttons all move the map.
2. Add 5 points, one with a 1 km digit slip:
   - the entry-far warning appears;
   - [Save anyway] shows the flagged row;
   - delete it, then undo.
3. Terminate mid-session and relaunch: auto-resume brings back all points.
4. Leave with points shows the three-button dialog.
5. Finish at n = 3 shows the confirm.
6. Import a GeoPDF and a plain PDF: both are listed. Switch between them, then delete one with the confirm.
7. A multi-page plain PDF opens the page picker.
8. A German-language pass.

### On device (simulator and one older iPhone, preferably SE-size)
- Calibrate the WP1 synthetic plain sheets (1:25k and 1:50k, 45°N and 60°N, plus rotated 5°). Use `scripts/grid_alignment.py` (plan §6): ≤ 1 px and ≤ 1 m at 3 zooms.
- An ED50 sheet.
- Gloves: every action reachable without pinching.
- Night mode: every state is readable by glyph and word.
- On an SE-size screen, the keyboard, entry card and crosshair are all visible.
- A 400 MB scan: timing, peak memory and Cancel.
- Low storage.
- Kill during entry.
- The `com.apple.metadata:com_apple_backup_excludeItem` xattr on `ImportedMaps` and `offline_tiles`.
- A phone photo of a paper map: the grade and advice are sensible.

## 11. Phasing (one engineer, one session)

**A. Pure layer and fixtures, about 30%**
- Parser, point, state, fit report, camera anchor, limits and decision.
- `scripts/gen_calibration_fixtures.py` and the three fixtures.
- The Android engineer consumes the same fixtures.

**B. Storage and library, about 25%**
- Library, migration, reconcile and in-flight registry, delete cascade.
- MapViewModel transitions.
- `ImportedMapsSection`.
- `ImportedMapStorage`, covering backup exclusion, cufua and opaque names.

This phase fixes D5-03, D5-04, D5-08, D5-14 and D5-19.

**C. Calibration UX, about 30%**
- Session, panel, entry card, sheets, reticle, markers view.
- The TileMapContainer and editing wiring, the header, and hidden chrome.
- Draft store, finish, leave and resume.

**D. Import UX, about 15%**
- Pipeline, controller, HUD, page picker, messages, crash marker.

**Cut list if short** (each is P1):
- page-picker thumbnails (use a list instead)
- "Choose page…"
- "Use the PDF's own georeferencing"
- marker tap-to-select (the points sheet covers it)
- residual lines
- the next-corner hint text (keep the spread warning)
- the TileMapView prefetch transition (use a plain atomic swap)

## 12. iOS-specific risks

- **WP1 names and shapes are still in flux.**
  - Absorb changes in `GeorefAdapters.swift`.
  - `PDFMapSource` is touched by WP1, WP2 and WP5: agree the init shape before phase B.
- **The migration is the largest data-loss risk.**
  - It is fail-closed and link-based, with injected-crash tests.
  - A downgrade to an older TestFlight build shows its recovery message and deletes nothing.
- **Moving the integrity hash off the main thread.** A tampered file with an unchanged size and mtime renders once before the background check rejects it. The container is app-private. Document this in THREAT_MODEL.
- **CGPDF is uninterruptible.**
  - The watchdog only abandons the work.
  - Memory can spike until the call returns, and jetsam is possible.
  - The marker handles a next-launch recovery. Document this.
- **iOS 16.3 has no background-interactive sheets.** The entry card is inline at the top. In landscape on small phones, collapse the card to the field plus the Save row.
- **Hiding the hamburger removes Search, Sync and Settings until Leave.** This is intentional, to prevent mode and source switches.
- **`isExcludedFromBackup` coverage.** It must be re-applied after every rename and bake publish; the launch sweep is the backstop.
