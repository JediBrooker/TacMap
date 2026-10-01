
# Android design for WP4 + WP5

Line references are to HEAD `77504db`. Paths are under `android/app/src/main/java/com/tacmap/`. The shared contract fixes every number, rule, key and fixture. This design covers placement, and the Android-specific lifecycle facts that shape it.

## 0. Two Android facts that drive the design (verified in code)

**Fact 1: MapScreen is torn down on every pause.**
- `MainActivity.onPause` (:320-336) calls `DataKey.lock()` and sets `missionKeyReady = false`.
- The composition at :224-227 then removes `MapScreen` entirely.
- As a result, anything held in `remember{}` is lost whenever the user switches apps. That includes the calibration state at `MapScreen.kt:401-405`.
- Consequence for the design:
  - The calibration session lives in `MapViewModel`. That is `viewModel()` at `MapScreen.kt:177`, which is Activity-scoped and survives both pause and configuration changes.
  - Draft writes are **synchronous at the moment of mutation**. A deferred or conflated IO write could run after `DataKey.lock()`. It would then fail in auth-bound mode, or silently re-unwrap the key in device-bound mode, which the existing code deliberately prevents.

**Fact 2: the picker result arrives while the key is locked.**
- The document picker pauses `MainActivity`, so the key is locked when `receiveDocumentImportResult` (:417-432) runs.
- A sealed pending-URI store would therefore fail in auth-bound mode.
- D5-18 is fixed by keeping the pending import in memory and `savedInstanceState` only (§7).
- The import job itself stays composition-scoped, as today (`MapScreen.kt:595-613`). It is cancelled on pause and resumed through the idempotent copy journal. Progress is published through a `MapViewModel` StateFlow, so the progress card survives recomposition.

## 1. File plan

### New pure Kotlin files (JVM unit tests)

**`mgrs/CoordinateInputParser.kt`**
- `object CoordinateInputParser { fun parse(raw: String, ctx: CoordinateParseContext): ParseOutcome }`
- `ParseOutcome = Ok(ParsedReference) | Err(code: CoordinateParseError, args: Map<String,String>)`
- Reuses the helpers in `map/MgrsCoordinateResolver.kt`:
  - `FULL_SUPPORTED_MGRS`, `extractMgrsPrefix` (:64-68, :107-110)
  - `centreOfMgrsSquare` (:70-97)
- Reuses `parseDecimalCoordinate` from `SearchDialog.kt:588-603`. It is made `internal`; Search behaviour does not change.
- Adds the zone column-set check (ports iOS `isSafeUTMMGRS`).
- The same check also goes into `MgrsFormatter.parse`/`looksLikeMgrs` (`mgrs/MgrsFormatter.kt:50-70`), and `56HAH3490052288` is added to the invalid list in `mgrs_samples.json` (D2-11 #3).

**`calibration/fiducial/CalibrationPoint.kt`**
- `@Serializable` types:
  - `PagePoint` (unless WP1 provides one)
  - `CalibrationPointKind`
  - `sealed CalibrationReference`, with `Grid(zone, south, easting, northing, cellSizeM, source)` and `Geographic(lat, lon)`
  - `CalibrationPoint(id, number, page, input, reference, kind, datumOverride, label)`
- `fun resolved(sheetDatum)` does the reference resolution from shared §5.

**`calibration/fiducial/CalibrationState.kt`**
- `CalibrationTarget(entryId, contentKey, pageIndex, pageBox, rotate)`
- `CalibrationState(target, datumId, points, nextNumber) { fun applying(e: CalibrationEdit): CalibrationState }`
- `sealed CalibrationEdit`: `Add | Move | EditReference | Delete | SetDatum`

**`calibration/fiducial/CalibrationFitReport.kt`**
- `object CalibrationFitReport { fun evaluate(s, fitter: FiduciaryFitting): FitReport; fun entryCheck(...): EntryCheck? }`
- `FitReport`, `Finishability`, `BlockReason` and `ConfirmReason` mirror iOS field-for-field.

**`calibration/fiducial/CalibrationCameraAnchor.kt`**
- `fun adjust(camera: MapCamera, from: PdfGeoreference, to: PdfGeoreference, minZ: Double = 2.0, maxZ: Double = 22.0): MapCamera?`
- `fun screenPointsPerPagePoint(g, page, zoom): Double?`

**`calibration/ImportLimits.kt`**
- The constants.
- `object ImportDecision { fun decide(inspection, duplicate): ImportOutcome }`

**`calibration/GeorefAdapters.kt`**
- The WP1/WP2 seam, including `FiduciaryFitting`.

### New storage files

**`calibration/CalibrationDraftStore.kt`**
- `class CalibrationDraftStore(filesDir)` using `SafeStore.readOrQuarantine` and `writeAtomically`.
- File: `calibration_drafts.json`.
- `load(key)`, `save(draft): Boolean` (synchronous), `delete(key)`, `prune(keep)`, `activeDraft()`.

**`calibration/ImportedMapLibraryStore.kt`**
- `class ImportedMapLibraryStore(filesDir)`, file `imported_map_library.json`, using the same `LibraryState`/`ImportedMapEntry` shape as the contract.
- `load(): LibraryLoad` (`Loaded | Empty | Locked | Corrupt`), `write(state): Boolean`, `managedFiles(state): Set<File>`.

**`calibration/InFlightImportFiles.kt`**
- A synchronized `Set<File>`.

**`calibration/ImportedMapLibraryMigration.kt`**
- `fun migrateIfNeeded(legacySelection: ActiveMapSelectionStore, legacySession: PdfSessionStore, library): MigrationResult`.

**`calibration/PdfInspector.kt`**
- `fun inspect(file, cancel: () -> Boolean): PdfInspection`.
- Loads PDFBox once, via `PDDocument.load(file, "", MemoryUsageSetting.setupMixed(16 MiB, 64 MiB).setTempDir(cacheDir/pdfbox))`.
- Then:
  - `InvalidPasswordException` maps to `PASSWORD`;
  - checks page count, boxes and rotation;
  - runs WP1 `GeoPdfParser.georeferenceFor(doc, i)` for i < 50, plus the catalog.
- Afterwards, one pdfium `PdfPageRenderer` open of the chosen page. `SecurityException` maps to `PASSWORD`.
- Only this file catches `OutOfMemoryError`/`StackOverflowError`, mapping them to `TOO_COMPLEX`.

### New UI and app files

**`map/CalibrationController.kt`**
- VM-owned; `vm.calibration`.
- `StateFlow<CalibrationUiState?>`, where `CalibrationUiState` holds:
  - `phase`, `state`, `report`, `display: CalibrationDisplay(georef, generation)`
  - `selectedId`, `entry: EntryUi(text, kind, parse, check)`
  - `draftUnsaved`, `canUndo`, `gridOn`
- Its API matches iOS `CalibrationSession` exactly:
  - `start`, `chooseDatum`, `beginAdd`, `updateEntryText`, `useGps`, `moveEntryToCrosshair`, `commitEntry`, `cancelEntry`
  - `select`, `beginMove`, `confirmMove`, `cancelMove`, `beginEdit`, `delete`, `undo`
  - `finishTapped`, `commit`, `leaveTapped`, `leave`, `suspend`, `onActiveSourceChanged`
- Mutations run on the main thread: refit is under 1 ms at n ≤ 10, and about 3-10 ms at n = 50 on low-end phones.
- `val bridge = CalibrationMapBridge()`

**`map/CalibrationMapBridge.kt`**
- `class CalibrationMapBridge { var capture: (() -> CalibrationCapture?)? }`
- Main-thread only. Set by `CustomMapScreen`.

**Other UI files**
- `map/CalibrationUi.kt`: `CalibrationPanel`, `CalibrationEntryCard`, `CalibrationPointsSheet`, `CalibrationDatumSheet` and the dialogs. It uses `com.tacmap.ui.AlertDialog` (night-aware).
- `map/render/CalibrationMarkersLayer.kt`: replaces `CalibrationFiduciariesLayer` (`CustomMapOverlays.kt:392-432`).
- `map/CalibrationReticle.kt`
- `map/ImportedMapsSection.kt`
- `map/ImportProgressCard.kt`
- `map/PdfPagePickerDialog.kt`
- `map/MapImportPipeline.kt`: replaces `importPdfMapSource`/`importMBTilesMapSource` (`MapScreenHelpers.kt:441-540`).

### Deleted
- `MapScreenChrome.kt:440-578`: `CalibrationBar` and `CalibrationInputDialog`.
- `MapScreenHelpers.kt:60-80`: `PendingCalibrationTap` and `pdfPointFor`, the bounding-box ratio bug (D2-02).
- `MapScreenHelpers.kt:424-439`: `preflightPdfImport`. The rotation rejection goes too, once WP1/WP2 support `/Rotate`.
- Calibration state and handlers in `MapScreen.kt`:
  - `MapScreen.kt:401-405`: state.
  - `:838-867`: `startPdfCalibration`, `finishPdfCalibration`, `cancelPdfCalibration`.
  - `:926-933`: `onCalibrationTap`.
  - `:2136-2163`: the dialog.

## 2. Gestures (D2-01 and the verifier note)

**`CustomMapScreen.kt:294`**
- The gate becomes `if (!drawingInputEnabled && !freeDrawActive)`.
- While `calibrationActive`, `MapItemTouchOverlayCustom` is composed with:
  - `waypoints = emptyList()`, `drawings = emptyList()`, `peers = emptyMap()`
  - `locked = true`
  - `onEmptyLongPress = null`
  - `rotationEnabled = true`
  - `onEmptyTap = { pos -> onCalibrationMapTap(pos) }`
- `VertexHandlesOverlayCustom` gets `feature = null`.
- Pan, pinch and rotate then come from the existing arbitrator (`CustomMapTouch.kt:210-330`).

**`CustomMapTouch.kt`**
- Remove the `calibrationInputEnabled` parameter (:113) and the early return (:131).
- `onEmptyTap: () -> Unit` becomes `(Offset) -> Unit`, and :373 calls `cOnEmpty.value(start)`.
- The existing caller in `CustomMapScreen` (:314) wraps it as `{ onMapTap() }`.

**`MapInputOverlay` (`CustomMapTouch.kt:571-624`)**
- Remove the calibration parameters and branch.
- Both `pointerInput(Unit)` calls (:593, :616) become `pointerInput(drawingInputEnabled, freeDrawActive)`.
- Read the booleans through `rememberUpdatedState`. This removes the stale-capture bug the verifier found.

**`MapScreen.beginCalibration`** (replaces `startPdfCalibration` at :838-848). It performs the shared §2.3 steps:
- `stopDrawing()`, `measureSession.cancel()`
- `isFreeDrawMode = false`, `activeDrawTool = null`, draft cleared
- `vm.selectWaypoint(null)`, `selectedDrawingId = null`
- close the point menu and quick-add
- then `vm.calibration.start(...)`

**Wiring into `CustomMapScreen` (:870)**
- `headingUpEnabled` = `mapOrientationMode == HEADING_UP && !calibrating` (:907).
- `drawingInputEnabled` (:884) is unchanged.
- The calibration parameters (:892-893) and `onCalibrationTap` (:926) are replaced by the new parameters in §3.

**Back handling**
- `BackHandler(enabled = calibrating) { vm.calibration.leaveTapped() }`

## 3. Renderer plug-in (`map/CustomMapScreen.kt`)

### New parameters
These replace `calibrationInputEnabled`/`calibrationFiduciaries` (:75-76) and `onCalibrationTap` (:104):
- `calibrationActive: Boolean`
- `calibrationDisplay: CalibrationDisplay?`
- `calibrationMarkers: CalibrationMarkerModel?`
- `calibrationBridge: CalibrationMapBridge?`
- `onCalibrationMapTap: (Offset) -> Unit`
- `zoomStepRequests: Flow<Int>?`
- `centreRequests: Flow<Pair<Double, Double>>?`
- `overlaysHidden: Boolean`
- `userLocationHidden: Boolean`
- `gridOverride: Boolean?`

### Atomic generation install (shared §7.2)
The camera is owned here (`var camera` at :119), so the install happens here:

```kotlin
var shown by remember { mutableStateOf(calibrationDisplay) }
LaunchedEffect(calibrationDisplay?.generation) {
    val next = calibrationDisplay
    val prev = shown
    if (next == null || prev == null || next.generation == prev.generation) { shown = next; return@LaunchedEffect }
    val target = CalibrationCameraAnchor.adjust(camera, prev.georef, next.georef) ?: camera
    val nextSource = GeorefAdapters.pdfTileSource(next)            // WP2, keyed pdf:<entry>:<gen>
    withTimeoutOrNull(400) { nextSource.prefetch(TileMath.visibleTiles(target)) }
    Snapshot.withMutableSnapshot {                                 // one frame, both or neither
        camera = CalibrationCameraAnchor.adjust(camera, prev.georef, next.georef) ?: camera
        shown = next; shownSource = nextSource
    }
}
```

- Every consumer reads `shown`, never the incoming parameter:
  - the PDF tile layer (WP2)
  - the markers
  - the capture
- Because `shown` and the camera change in one snapshot, no frame mixes the old georef with the new camera (or the reverse). The Android implementation-risk lens's plan used `vm.flyTo` through `pendingTarget`; that path renders at least one mismatched frame, so it is not used.

### Capture bridge
```kotlin
val cCamera = rememberUpdatedState(camera); val cShown = rememberUpdatedState(shown)
DisposableEffect(calibrationBridge) {
    calibrationBridge?.capture = { cShown.value?.let { s -> captureOf(s, cCamera.value) } }
    onDispose { calibrationBridge?.capture = null }
}
```
- "Add point" and "Set here" call `vm.calibration.beginAdd(bridge.capture())`. This reads the live camera. It does not use `vm.cameraLat`, which is published through a `LaunchedEffect`.

### Zoom, centre, grid, reticle and overlays
- **Zoom**: `LaunchedEffect(zoomStepRequests) { collect { camera = camera.copy(zoom = (camera.zoom + it).coerceIn(2.0, 22.0)) } }`
- **Centre**: `centreRequests` sets the centre and keeps zoom and heading.
- **Grid** (:252): `if ((gridOverride ?: mgrsGridVisible) && !freeDrawActive)`
- **Reticle**: `CrosshairOverlay()` (:265) is replaced by `CalibrationReticle()` while `calibrationActive`.
- **Hidden overlays** when `overlaysHidden`:
  - `WaypointSymbolsLayer`
  - `PresenceLayer`
  - the label layers
  - `DrawingsCanvas`
  - the heatmap
  - `UserLocationCanvas`, only when `userLocationHidden`
- **Markers**: `CalibrationMarkersLayer(calibrationMarkers, shown, camera, density)` replaces :271.
  - WGS84 positions are cached per generation with `remember(shown.generation, markers)`.
  - It draws the shared §7.5 states, residual lines and badges.
  - `onCalibrationMapTap` hit-tests within 24 dp and calls `vm.calibration.select(id)`.

### PDF display
- `PdfGroundLayer` (`CustomMapOverlays.kt:809-874`) belongs to WP2.
- Until WP2 lands, a stop-gap maps `shown.georef` into its `polyToPoly` quad. That is display only; capture and markers stay exact.

## 4. MapScreen wiring (`map/MapScreen.kt`)

### State and effects
- `val calibrationUi by vm.calibration.state.collectAsState()`
- `calibrating = calibrationUi != null`
- The `LaunchedEffect(mapSource.id)` reset (:709-715) is removed. Instead, the VM publish lambda (`MapViewModel.kt:180-196`) calls `calibration.onActiveSourceChanged(source)`. This implements suspend (D5-10).

### Header (:1060-1093)
- `MgrsHeader` (`MgrsHeader.kt:55-74`) gains a new parameter, `calibrationReadout: CalibrationReadout?`, which is one of:
  - `NotGeoreferenced`
  - `Preview(showTag)`
  - `null`
- `basemapLabel` (:230-238) becomes `calibration_header_label` in amber while calibrating.
- `onDropPin` is null while provisional.
- `vm.headerCoordinate` uses the camera, because `beginCalibration` sets browsing.

### Hidden chrome (all hidden while `calibrating`)
- Hamburger box (:1138-1303); this also removes Measure (:1202-1210) and Import/Export (:1221-1228).
- Quick-add.
- `UndoRedoButtons` (:1377-1394).
- `LockButton` (:1395-1400).
- Chat HUD.
- Centre pills.
- The online-tiles warning slot (:1099-1111).

### Bottom bar (:1404-1415)
- Replaced by `CalibrationPanel(ui, onAction = vm.calibration::…)`.
- Also shown:
  - the ✕ button at top-left;
  - zoom buttons on the right edge;
  - `CalibrationEntryCard` at the top whenever `phase is Entering`;
  - the sheets and dialogs from `CalibrationUi.kt`.

### Finish
```kotlin
val manual = vm.calibration.commit() ?: return
if (vm.commitCalibration(entryId, manual)) vm.calibration.endAfterCommit()
else vm.calibration.showSaveFailed()
```
The toast strings are the shared keys.

### Layers (:2000-2049)
- The `LayersSheet` parameters `retainedImportedMapName … onUnloadOfflineTiles` (`LayersSheet.kt:84-92`) are replaced by:
  - `library: LibraryUi`, `activeEntryId`, `drafts`
  - `onActivate(id)`, `onCalibrate(id)`, `onGenerateTiles(id)` (WP2), `onDelete(id)`
- The section at `LayersSheet.kt:152-201` is replaced by `ImportedMapsSection`. Unload is gone (D5-04). Delete needs confirmation.

### Import/Export sheet
- `ImportExportSheet` (`ImportExportSheet.kt:59-72`) gains `importsEnabled: Boolean = vm.importProgress.value == null`. When false, the PDF and Offline Tiles rows are disabled.

### Auto-resume and launch alerts
- After the VM restore, if `calibrationUi` was produced by an auto-resume (the VM calls `calibration.start(..., resumeSilently = true)` at init), show the snackbar `calibration_resumed`.
- The migration and interrupted-import alerts come from a VM `StateFlow<LaunchAlert?>`.

## 5. Library, selection and MapViewModel (`map/MapViewModel.kt`)

### Store fields
- `activeMapSelectionStore` and `pdfSessionStore` (:137-148) remain only as legacy readers used by the migration.
- They are joined by:
  - `private val library = ImportedMapLibraryStore(app.filesDir)`
  - `private val drafts = CalibrationDraftStore(app.filesDir)`
  - `val calibration = CalibrationController(drafts, fitter, clock)`
- `val libraryState: StateFlow<LibraryState?>`
- `_retainedImportedMapSource` (:158-162) is removed.

### Transitions
- `PendingMapSelectionTransition` (:53-82) is replaced by a `LibraryTransition` sealed interface with the same cases as iOS:
  - `SelectOnline`, `ActivateEntry`, `AddEntry`, `CommitCalibration`, `UpdateEntry`, `DeleteEntry`, `RestoreActive`
- The pure reducer `LibraryTransitionReducer` (`calibration/`) is JVM-tested.
- `executeMapSelectionTransition` (:391-475) becomes `execute(t)`:
  1. load;
  2. build the candidate;
  3. `library.write` — the single commit point;
  4. publish;
  5. for delete: close MBTiles, delete the draft, unlink files;
  6. reconcile;
  7. on failure, `reportMapSelectionIssue` (:484) with Retry.
- `ActiveMapSelectionCommitCoordinator` (`map/ActiveMapSelectionCommitCoordinator.kt`) is replaced. Its `frameCamera` behaviour becomes a per-transition flag: `frameCameraFor` (:223-232) moves out of the publish lambda (:195) and is called only when `reframe == true`.

### Public API
Replaces `selectBaseMap`/`restoreOnlineBasemap`/`setMapSource`/`unloadPdfMap`/`unloadOfflineTiles`/`restoreRetainedImportedMap` (:203-271):
- `selectBaseMap(style)` (kept)
- `activateImportedMap(id)`
- `addImportedEntry(entry, activate)`
- `commitCalibration(entryId, manual)`
- `updateImportedMap(id, update)`
- `deleteImportedMap(id)`
- `beginCalibrationPreview(entry)` / `endCalibrationPreview()`: a non-durable publish that remembers `previewReturn`.
- `zoomStepRequests: SharedFlow<Int>`, `centreRequests`
- `importProgress: StateFlow<ImportProgress?>`, `pagePicker: StateFlow<PreparedPdfImport?>`, `rejectedPrompt`, `launchAlert`

### `init` (:291-301)
1. If the library is Empty and legacy state is present, run `migrateIfNeeded()`. It runs synchronously once; see the risks section.
2. Load the library.
3. `restoreActive`: size and mtime only, then publish.
4. `reconcile`, but only when Loaded (this replaces `canReconcileManagedMapsAtColdStart`).
5. `drafts.prune`.
6. Interrupted-import check.
7. `verifyActiveInBackground()` on `Dispatchers.IO`. On mismatch, go back to Main, mark the entry unavailable and select online.
8. Auto-resume the active draft.

This removes the main-thread hash of `PdfSessionStore.save` (`PdfSessionStore.kt:94-98`) from every path.

### Reconcile
- `reconcileManagedImportedMapFiles` (:476-482) becomes `ManagedImportedMapFileLifecycle.reconcile(managedParent = filesDir, directories = [pdf_maps, mbtiles, offline_tiles], keeping = library.managedFiles(state) ∪ InFlightImportFiles.snapshot())`.
- `ManagedImportedMapFileLifecycle.isCrashResidueName` (:116-125) exempts in-flight files.
- `ActiveMapSelectionStore.reconcileManagedImportedMapFiles` (:273-313) and its tests are retired.

### Sources
- `PdfMapSource` (coordinated with WP1/WP2) gains `entryId`, `pageIndex`, `contentKey` and `georef`.
- `calibrated()` returning `this` on failure (`PdfMapSource.kt:22-27`) is no longer reachable. Commit goes through `CommitCalibration`, which writes the WP1 fit or fails loudly (D2-09).

## 6. Import pipeline (`map/MapImportPipeline.kt`; contract §9)

### Entry point
```kotlin
internal class MapImportPipeline(ctx, journal: DocumentImportCopyStateStore, limits: ImportLimits, inspector: PdfInspector) {
  suspend fun runPdf(uri: Uri, operationKey: String, progress: (ImportProgress) -> Unit): PreparedOutcome
  suspend fun runMbtiles(uri, operationKey, progress): PreparedOutcome
}
```
- `PreparedOutcome = Prepared(PdfInspection|MbtilesInfo, file, contentKey, displayName) | Failed(ImportError) | Cancelled`

### Stages
1. **Pre-check.** Read the size via `OpenableColumns.SIZE`, then check:
   - the size limit;
   - free space: `StatFs(filesDir).availableBytes` ≥ size + 64 MiB;
   - library Loaded;
   - fewer than 100 entries.
2. **Copy.** `IdempotentDocumentCopy.execute` (`DocumentImportCopy.kt:90-152`) gains `onBytes(done, total)` and a `DigestOutputStream`.
   - The contentKey is stored in `DocumentImportCopyState` as a new nullable field `contentKey`.
   - The copy loop (:154-164) calls `coroutineContext.ensureActive()` every 1 MiB and emits progress.
   - The copy is registered in-flight before the `.partial` file is created.
   - File names stay `import-<16hex>` (already opaque).
3. **Journal marker.** Add `inspectStartedAtEpochMs: Long? = null` to `DocumentImportCopyState` (:21-26).
   - It is a nullable field, not a new enum value, so older builds still decode it.
   - It is persisted before `PdfInspector` runs.
   - On relaunch, a leftover marker gives `INTERRUPTED`: the file is deleted, the import is not retried, and the user sees `launchAlert = interrupted`.
4. **Inspect.** `withContext(inspectorDispatcher) { withTimeoutOrNull(30_000) { runInterruptible { inspector.inspect(file) } } }`.
   - `inspectorDispatcher` is a dedicated single thread.
   - On timeout the result is `TOO_COMPLEX`, and the orphaned PDFBox thread is abandoned.
5. **Decide.** Back on Main, `ImportDecision.decide` then one of:
   - `vm.addImportedEntry` + toast;
   - `vm.pagePicker`;
   - `vm.rejectedPrompt`;
   - `beginCalibration`.

### Composition-scoped job
The token `LaunchedEffect` at `MapScreen.kt:595-613` stays, and calls `vm.runImport(pending)`, which suspends:
- **Cancel button**: sets `vm.importCancelRequested = token` and cancels. The effect treats this as terminal: `map_import_cancelled`, then `onCompleteDocumentImport`.
- **Lifecycle cancellation** (onPause teardown): stays as today, `abandon` then retry on resume.
- **Page picker and rejected prompt**: these complete the token at hand-off. The prepared file stays in-flight in the VM until the user chooses. Cancel, or process death, leaves it for reconcile.
- The `runCatching` at `MapScreen.kt:446-459` becomes an explicit `catch (e: Exception)`. `pdfImportUserMessage` (`MapScreenHelpers.kt:416-422`) maps typed `ImportError` values to the shared keys.

### Load counts
- PDFBox loads per import drop from 3 to 1 (D5-09), and PdfRenderer opens from 2 to 1.
- PDFBox heap is at most 16 MiB, plus at most 64 MiB of scratch in `cacheDir/pdfbox`. Exceeding the scratch cap gives an `IOException`, which maps to `TOO_COMPLEX`.

### Page picker
- `PdfPagePickerDialog` renders 120 dp-wide thumbnails.
- `RGB_565`, about 0.37 MB each at 3x, with LRU 24 (about 9 MB).
- Rendered serially on one pdfium renderer.
- Cut fallback: a plain list.

### MBTiles
- The same copy, hash and progress, then validate with `MBTilesStore.open`, then add the entry.
- The limit stays 4 GiB.

## 7. Pending import URI (D5-18; `app/MainActivity.kt`)

- Remove `persistPendingDocumentImport` (:489-502) and the prefs constants (:699-703).
- Keep the pending record in `pendingImportCoordinator` (memory) plus `onSaveInstanceState` (:338-346, unchanged).
- `restorePendingDocumentImport` (:466-487):
  1. Read `savedInstanceState` first.
  2. Then read the legacy `pending_document_import_v1` prefs **once**, adopt the record into memory, and immediately `edit().clear().commit()`.
- `completeDocumentImport` (:434-449) no longer needs the durable-clear step: completion clears memory, then releases the grant.
- At startup, when no pending import is held, release every `contentResolver.persistedUriPermissions` entry. These can only be leaked import grants.
- Side benefit: a crash no longer replays the import from prefs on the next launch (part of D5-07).

## 8. Threading and memory

| Where | Work | Cost |
|---|---|---|
| Main | parse | < 0.5 ms per keystroke |
| Main | fit + leave-one-out | < 1 ms at n ≤ 10; about 3-10 ms at n = 50 |
| Main | draft write (sync, fsync) | 5-20 ms, on discrete taps only; the same pattern as the existing synchronous `WaypointStore.update` |
| Main | library write | ≤ 100 KB typical, on user actions only |
| Main | markers | per-frame affine only, with WGS84 cached per generation |
| IO / dedicated thread | copy + hash | about 0.35-0.7 s of CPU per 512 MiB; overlapped |
| IO / dedicated thread | inspector, thumbnails, background integrity hash | — |
| WP2 | tile renders and the refit prefetch | one visible set, about 8-12 tiles at 3x, released at the swap; the 400 ms deadline bounds the stall |

## 9. Test plan

### JVM unit tests (loading shared fixtures from `testdata/`)
- **`CoordinateInputParserTest`**: every case in `calibration_input.json`. `SearchDialogTest` and `search_contract.json` are unchanged and still green. `sharedMgrsVectors` includes `56HAH…`, which must be invalid.
- **`CalibrationFitReportTest`**: `cases`, `entryChecks` and `capture`.
- **`CalibrationCameraAnchorTest`**: the `cameraAnchor` cases.
- **`CalibrationStateTest`**: numbering, undo and the 50-point cap.
- **`CalibrationControllerTest`**, using a fake draft store and a fake bridge:
  - an off-sheet capture is rejected;
  - the draft is written synchronously on each mutation, and a locked store sets `draftUnsaved`;
  - the resume prompt versus the silent path;
  - leave-dirty;
  - suspend on a source change;
  - a mismatched commit writes nothing;
  - the VM survives a simulated MapScreen disposal: the state is intact.
- **`CalibrationDraftStoreTest`**: round trip, LRU of 16, corrupt quarantined, key mismatch ignored.
- **`ImportedMapLibraryStoreTest` / `LibraryTransitionReducerTest`**:
  - import A then B keeps both (this rewrites the delete-others assertions in `ActiveMapSelectionStoreTest` around :285-450);
  - delete cascades to the file, sidecars, derived entries and draft;
  - Locked or Corrupt state deletes nothing;
  - in-flight files survive reconcile;
  - restore does not hash on the main thread (a spy on `PdfCalibrationIdentity.contentKey`).
- **`ImportedMapLibraryMigrationTest`**: frozen legacy selector, retained file and prefs; injected failures; camera fallback becomes `needsCalibration` plus the alert; v1 fiducials become `manual` with a WGS84 override.
- **`DocumentImportCopyTest`**:
  - the streamed hash equals `contentKey`;
  - cancel leaves no `.partial`;
  - the progress cadence;
  - an old journal JSON without the new fields decodes.
- **`MapImportPipelineTest`**, using a fake inspector:
  - one PDFBox load;
  - OOM becomes `TOO_COMPLEX`;
  - the marker becomes `INTERRUPTED`;
  - dedupe;
  - the decision vectors.
- **`ImportLimitsContractTest`**: the Kotlin constants equal the JSON.
- **`PendingDocumentImportTest`**: after a pick, the app's `shared_prefs` directory contains no URI or file name; legacy prefs are migrated and cleared.
- **`LocalizationTest`** (instrumented): the new German keys and plural families.

### Instrumented and Compose tests
- **Gestures (D2-01)**: while calibrating, a swipe pans, a pinch zooms, and neither opens the entry card. A tap on a marker selects it.
- **Stale booleans (verifier case)**: compose with draw active, switch to calibration, then to measure. Each tap reaches the correct handler.
- **Atomic swap**: record frames across a refit. No frame has `shown.generation` ≠ the generation the camera was anchored for.
- **`PdfInspectorTest`**: georef on page 2 picks page 2; a 3-page plain PDF gives the picker; a locked PDF gives `PASSWORD`; a Flate bomb gives `TOO_COMPLEX` within 30 s with heap growth under 64 MB.
- **Delete**: the confirm dialog appears, and the file exists until confirmed (D5-04).

### On device (emulator plus a low-end phone, with gloves)
- Calibrate the synthetic plain sheets at 1:25k and 1:50k using only pan and the ± buttons. Place a 1 km slip on purpose: it gets the warning, then the flag; fix it through the list and Undo. Run `scripts/grid_alignment.py` for ≤ 1 px and ≤ 1 m.
- Switch apps mid-calibration and mid-import: calibration persists in the VM, and the import restarts cleanly.
- `am kill` mid-entry: auto-resume on relaunch.
- A dark-mode or locale change during calibration.
- A second import keeps the first. Delete it with confirm, then check with `run-as ls` that the files, tiles and draft are gone.
- Hostile inputs: a 600 MB file, a locked PDF, a bomb, a 501-page PDF. Each shows the same message as iOS.
- A German small-phone pass (360 dp), large font, night mode, and TalkBack labels on every button.

## 10. Phasing (one engineer, one session)

| Phase | Share | Contents | Findings fixed |
|---|---|---|---|
| A | ~30% | shared fixtures (consumed or co-authored with iOS), parser, state, fit report, camera anchor, limits/decision | — |
| B | ~25% | library store, migration, reconcile + in-flight, VM transitions, delete, `ImportedMapsSection`, pending-URI change | D5-03, D5-04, D5-18, D5-19 |
| C | ~30% | gestures, controller, UI, markers, atomic install in `CustomMapScreen`, drafts, header and hidden chrome | D2-01, D2-02, D2-07..D2-13, D5-10, D5-11 |
| D | ~15% | pipeline, inspector, progress card, page picker, messages, crash marker | D5-09, D5-12, D5-13, D5-15, D5-20 |

**Cut list (P1):**
- picker thumbnails (use a list)
- "Choose page…"
- "Use the PDF's own georeferencing"
- marker tap-select
- residual lines
- next-corner text
- the prefetch (use a plain atomic swap)

## 11. Android-specific risks

- **Migration on the main thread in VM `init`.** It hashes the legacy PDF once, up to 512 MiB (about 0.3-1.5 s, one time only). This is the same cost as today's `PdfSessionStore.save`, but at launch. If it proves janky, move it to `viewModelScope` on IO, with restore awaiting it, and keep publishing the default online source until it completes.
- **Synchronous fsync'd draft writes on Main.** Each costs 5-20 ms per tap on slow flash. This is accepted, because deferring the write across `onPause` would break the key-lock policy. Measure it on a low-end phone.
- **PDFBox limits are soft.** `setupMixed` does not bound object-stream inflation, and the timed-out thread keeps running. pdfium runs in-process and can crash natively. The journal marker is the crash-loop breaker. Document this in THREAT_MODEL.
- **Raising the PDF limit from 256 to 512 MiB.** A USB-OTG copy may take 17-50 s, which is why progress and Cancel are mandatory in the same change. If low-RAM devices misbehave, both platforms drop to 256 MiB together.
- **The pending import is no longer on disk.** If the process dies before `onSaveInstanceState` and the task is not restored, the pick is lost. The user re-picks, and any grant leaked this way is released at startup.
- **Replacing the tap-only overlay in calibration with the transform overlay.** A missed call site that passes real waypoints would re-enable drag and select during calibration. The Compose test guards against this.
