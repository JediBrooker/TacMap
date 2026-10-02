# WP2 -> WP4 merge: shared behaviour spec (both platforms must match)

Context: WP4 made the sealed ImportedMapLibrary (iOS ImportedMapLibrary/LibraryState/LibraryReducer,
Android ImportedMapLibrary/LibraryState/LibraryReducer) THE authority for the active map and every
imported map; ActiveMapSelectionStore and PDFSessionStore/PdfSessionStore are legacy readers used
only by the one-time migration. WP2 kept its bake record + crash-guard token in the legacy sealed PDF
session and its delete/rollback/sweep logic on the legacy stores. Merge = WP4's library model with
WP2's guarantees ported onto it. WP2's PDFOverlay/PDFTiler/PdfTiler/PDFRasteriser/image overlay stay
DELETED.

M1 Bake record lives on the PDF library entry: `pdf.bake` (iOS PDFBakeRecord, Android PersistedPdfBake,
   same fields/validation as WP2's validBake / isValidBakeRecord). Sealed with the library. One bake
   per PDF entry. WP4's `addDerived` transition / addDerivedTiles / "bake = derived MBTiles entry"
   is dropped (it used the deleted tiler). `derivedFromId` + state `derived` stay for legacy data
   (fixture entryStates still pins them; delete still cascades to derived entries).

M2 Crash-guard token: `pdf.renderGuardToken` (random UUID string) on the PDF entry, minted when the
   entry is created (import commit, migration: legacy session token if it is a valid UUID, else new).
   An entry without one (pre-merge dev library) uses entry.id as its token (random UUID, sealed,
   never derived from file or name). No lazy write needed.

M3 attachBake = a library transition (one sealed write): refuses with sourceChanged unless the entry
   exists, is a PDF, contentKey == the bake's contentKey, token == the bake's token, and
   bakeKey(entry effective georef, tilePx) == record.bakeKey. Invalid record -> writeFailed. Library
   not Loaded or write failure -> writeFailed. Never re-saves the stale in-memory object. The active
   map never changes; the runtime attaches the reader only if it is showing that entry.

M4 Georef change drops the bake: reducer commitCalibration, revertToEmbedded, changePage set
   `pdf.bake = nil`. The file is then unreferenced and the reconcile (after every library write) and
   the bake-only sweep reap it. A calibration preview/display never uses a bake (different georef ->
   different bakeKey; source built with bake = nil).

M5 Reconcile keep set = every entry's file (+ sqlite sidecars) + each PDF entry's VALID bake file in
   offline_tiles (+ sidecars) + the in-flight registry. Runs under the managed-files lock that the
   bake publish (move + record) and Remove take.

M6 Remove Offline Tiles = one library write clearing that entry's `pdf.bake`; if it fails nothing is
   deleted (usual persistence issue + Retry). Then delete the named file + -journal/-wal/-shm directly
   (only `tacmap-bake-<x>.mbtiles`, plain files directly inside offline_tiles, under the lock), then
   the bake-only sweep.

M7 Bake-only sweep (WP2 R3-2) keeps the valid bake names of ALL PDF entries of a Loaded library, read
   under the managed-files lock. Runs at the start of every restore (launch, unlock, Retry) and after
   Remove. Authoritative read only: library Loaded, or library Empty with no legacy stores present
   (names nothing). Locked / Corrupt / Empty-with-legacy-present (migration pending) -> skip, delete
   nothing. Migration drops any legacy session bake record (re-creatable; the sweep reaps the file).

M8 OD-F4 kept: if the ACTIVE entry is a PDF whose file is missing or size/mtime-mismatched at restore,
   the durable selection is kept and the PDF source is published flagged storedFileUnavailable ->
   G1 sticky cannotOpen (failure alert: Try Again / Use Online Map / Not Now; Layers failed row).
   Try Again re-checks the bytes against contentKey off main and recovers. WP4's background hash
   check (restore step 4) on a mismatch for the active PDF marks it tampered (row shows
   map_state_unavailable) and flags the live source unavailable -> failed cannotOpen (instead of going
   online). A Try Again that passes clears the tampered mark. MBTiles entries keep WP4's behaviour
   (online + unavailable). Non-active unavailable rows: WP4 behaviour (Delete only).

M9 H1 preferred online style = the library's preferredOnlineStyle. Crash-guard suppress publishes it
   in memory only; the failure alert's "Use Online Map" is a durable selectOnline(preferred).

M10 Crash guard: launch decision once per launch on the first restore that knows the active entry,
   with that entry's token. Open Anyway publishes the entry; Not Now keeps suspect; Delete Map... ->
   WP4's delete flow (its map_delete confirmation, then the one-write delete) then resolve suspect.

M11 Delete (WP4 one-write delete) + WP2 extras: first cancel any estimate/bake for that PDF (F7),
   publish online if it was showing so the renderer lets go, after the write unlink the entry file
   (+ sidecars) AND its bake file (+ sidecars), then reconcile + sweep. WP2's multi-store rollback is
   moot (one write is the commit point): a failed write deletes nothing, shows the persistence issue
   with Retry; Retry finishes the delete.

M12 Import: WP2's import probe (arm the render guard `import` kind, render the base raster + blank check
   in the import worker, complete the guard) runs inside WP4's pipeline on the page that is about to be
   committed (georeferenced, rejected-with-calibrate or needsCalibration; after the page picker pick),
   before the library write. A probe failure aborts the import with the WP2 render-failure copy
   (cannotDraw(reason)) and commits nothing. WP4's own parse-phase crash marker (9.8) stays around the
   inspection; the WP2 import guard is only armed around the probe, so one crash yields one notice.
   The probe's base raster is adopted for the first paint as WP2 did.

M13 Calibration display through the WP2 tile source: the session's displayedGeoref (crop = pageBox
   while calibrating) is drawn by a PDF tile source for (entry, generation), bake = nil. A new
   generation installs the new tile source AND the anchored camera in ONE main-thread step (iOS:
   both inside the same deferred runloop turn WP2 uses to avoid publishing during a view update).
   WP2 exposes no prefetch, so WP4 s7.2's "without WP2" fallback applies (brief dark placeholder).
   Stale tiles are not kept across a swap (a new source clears the cache). The document render service
   (base raster) is shared across generations. Show Imported Map is forced on while calibrating.

M14 Markers: WP4's calibration markers (iOS CalibrationMarkersOverlayView, Android
   CalibrationMarkersLayer) replace WP2's PDFPageMarkersView / old fiduciary-marker hooks; WP4 never
   places a point by tapping, so WP2's tap -> page conversion goes.

M15 Layers: WP4's Imported maps section (rows, radio, subtitle, overflow menu, delete) PLUS WP2's
   active-PDF block: Show Imported Map toggle (on + disabled while calibrating), failed row ("Map
   couldn't be drawn" + reason + Try Again), bake block (progress / "Offline tiles: zoom a-b · size" +
   Remove Offline Tiles; Generate hidden once a bake exists; disabled while failed / uncalibrated /
   bytes unverified). The row menu "Generate offline tiles…" (fixture menu id generateOfflineTiles)
   starts the same WP2 estimate -> confirm -> run flow for that entry; in the UI it is hidden for an
   entry that already has a bake (the pure presentation function still matches the fixture).
