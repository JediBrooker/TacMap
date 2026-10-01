import XCTest
import CoreLocation
import MapKit
import SQLite3
@testable import TacticalMaps

/// WP5 library (contract s8.2): one sealed authority for the active map and
/// every imported map. Replaces the single-retained-map tests in
/// ActiveMapSelectionStoreTests: importing B keeps A (D5-03), delete cascades
/// (D5-19), nothing is hashed on main at restore (D5-08), backups and names (D5-14).
final class ImportedMapLibraryTests: XCTestCase {
    private let testKey = Data((0..<32).map { UInt8(truncatingIfNeeded: 17 &* $0 &+ 3) })
    private var root: URL!
    private var originalSupport: (() -> URL)!
    private var originalLibraryURL: (() -> URL)!

    override func setUp() {
        super.setUp()
        originalSupport = ImportedMapStorage.applicationSupportProvider
        originalLibraryURL = ImportedMapLibrary.storageURLProvider
        root = FileManager.default.temporaryDirectory.appendingPathComponent("library-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let r = root!
        ImportedMapStorage.applicationSupportProvider = { r }
        ImportedMapLibrary.storageURLProvider = { r.appendingPathComponent("imported-map-library.json") }
        SafeStore.keyProvider = { [testKey] in testKey }
        SealedMigrationPolicy.resetForTests(key: testKey)
    }

    override func tearDown() {
        ImportedMapStorage.applicationSupportProvider = originalSupport
        ImportedMapLibrary.storageURLProvider = originalLibraryURL
        SafeStore.keyProvider = { try DataKey.key() }
        SealedMigrationPolicy.resetForTests(key: testKey)
        try? FileManager.default.removeItem(at: root)
        super.tearDown()
    }

    // MARK: - fixtures

    private func fixturePDF(_ name: String) throws -> URL {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let c = dir.appendingPathComponent("testdata/geopdf").appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: c.path) { return c }
            dir = dir.deletingLastPathComponent()
        }
        throw XCTSkip("testdata/geopdf/\(name) missing")
    }

    private func importPDF(_ name: String) async throws -> ImportedMapEntry {
        let prepared = try await MapImportPipeline.preparePDF(url: try fixturePDF(name), entryCount: 0, libraryLoaded: true,
                                                              isCancelled: { false }, progress: { _ in })
        InFlightImportFiles.unregister(prepared.copy.url)
        return try XCTUnwrap(prepared.entry(pageIndex: 0))
    }

    private func makeMBTiles(at url: URL) throws {
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &db), SQLITE_OK)
        defer { sqlite3_close(db) }
        let sql = """
        CREATE TABLE metadata (name TEXT, value TEXT);
        CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);
        INSERT INTO metadata VALUES ('name','Tiles'),('format','png'),('minzoom','0'),('maxzoom','1'),('bounds','149,-36,152,-33');
        INSERT INTO tiles VALUES (0,0,0,x'89504E47');
        """
        XCTAssertEqual(sqlite3_exec(db, sql, nil, nil, nil), SQLITE_OK)
    }

    /// live storage, injectable hash
    private func deps(drafts: CalibrationDraftStoring = InMemoryCalibrationDraftStore(),
                      verify: @escaping (URL, String, @escaping (Bool) -> Void) -> Void = { _, _, done in done(true) },
                      write: ((LibraryState) throws -> Void)? = nil) -> LibraryDependencies {
        var d = LibraryDependencies.live
        d.drafts = drafts
        d.verify = verify
        d.legacyPresent = { false }
        d.sweepBackup = {}
        d.recoverInterruptedImport = { false }
        if let write { d.write = write }
        return d
    }

    // MARK: - D5-03: importing B never deletes A

    func testImportingASecondMapKeepsTheFirstAndItsFile() async throws {
        let vm = MapViewModel(libraryDependencies: deps(), initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        XCTAssertEqual(vm.restoreActiveMapSelection(), .nothing)
        let a = try await importPDF("tacmap_grid_sf_iso.pdf")
        XCTAssertTrue(vm.addImportedEntry(a, activate: true))
        let b = try await importPDF("tacmap_grid_rot5_iso.pdf")
        XCTAssertTrue(vm.addImportedEntry(b, activate: true))
        XCTAssertEqual(vm.library?.entries.map(\.id), [a.id, b.id])
        XCTAssertTrue(FileManager.default.fileExists(atPath: try XCTUnwrap(ImportedMapLibrary.fileURL(a)).path))
        XCTAssertEqual(vm.activeEntryID, b.id)
        // switch back to A: one write, no re-import
        XCTAssertTrue(vm.activateLibraryEntry(a.id))
        XCTAssertEqual(vm.activeEntryID, a.id)
        // and it all survives a relaunch
        let again = MapViewModel(libraryDependencies: deps(), initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        XCTAssertEqual(again.restoreActiveMapSelection(), .restored)
        XCTAssertEqual(again.library?.entries.count, 2)
        XCTAssertEqual(again.activeEntryID, a.id)
    }

    func testUncalibratedPDFIsNeverTheDurableActiveMap() async throws {
        let vm = MapViewModel(libraryDependencies: deps(), initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        vm.restoreActiveMapSelection()
        let plain = try await importPDF("tacmap_grid_rot5_plain.pdf")
        XCTAssertTrue(vm.addImportedEntry(plain, activate: true))
        XCTAssertNil(vm.activeEntryID, "no georef, no durable active")
        if case .entry? = vm.library?.active { XCTFail("the library must not point at an uncalibrated PDF") }
        XCTAssertFalse(vm.activateLibraryEntry(plain.id))
        // the preview is non durable: shown, but the library still says online
        var g = try XCTUnwrap(PdfGeoreference.provisional(pageBox: CGRect(x: 0, y: 0, width: 900, height: 1100), rotation: 0,
                                                          centredOn: CLLocationCoordinate2D(latitude: 37.7, longitude: -122.4)))
        g.crop = try XCTUnwrap(plain.pdf?.pageBox)
        XCTAssertTrue(vm.beginCalibrationDisplay(entry: plain, georef: g, reframe: true))
        XCTAssertEqual(vm.activeEntryID, plain.id)
        if case .entry? = try XCTUnwrap(vm.library).active { XCTFail("preview must not be written") }
        vm.endCalibrationDisplay()
        XCTAssertNil(vm.activeEntryID)
    }

    /// E1 preview frames the whole page. The provisional page is centred on the
    /// camera, so "frame the user if they're inside it" always won before and
    /// left you 1.5 km in. The first fix while calibrating is used up too.
    func testCalibrationPreviewFramesThePageAndTheFirstFixNeverYanks() async throws {
        let vm = MapViewModel(libraryDependencies: deps(), initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        vm.restoreActiveMapSelection()
        let plain = try await importPDF("tacmap_grid_rot5_plain.pdf")
        XCTAssertTrue(vm.addImportedEntry(plain, activate: false))
        var regions: [MKCoordinateRegion] = []
        let sink = vm.cameraRequests.sink { regions.append($0) }
        defer { sink.cancel() }
        let user = CLLocationCoordinate2D(latitude: 37.7, longitude: -122.4)
        vm.calibrationActive = true
        vm.userLocationDidUpdate(CLLocation(latitude: user.latitude, longitude: user.longitude))
        XCTAssertTrue(regions.isEmpty, "first fix while calibrating doesn't move the camera")
        var g = try XCTUnwrap(PdfGeoreference.provisional(pageBox: CGRect(x: 0, y: 0, width: 900.7839644, height: 1106.9282314),
                                                          rotation: 0, centredOn: user))
        g.crop = try XCTUnwrap(plain.pdf?.pageBox)
        XCTAssertTrue(vm.beginCalibrationDisplay(entry: plain, georef: g, reframe: true))
        XCTAssertEqual(regions.count, 1)
        // 1107 pt at 1:50k is ~19.5 km tall, the old user box was 1.5 km
        XCTAssertGreaterThan(try XCTUnwrap(regions.first).span.latitudeDelta * 111_320, 15_000)
        vm.endCalibrationDisplay()
        vm.calibrationActive = false
        vm.userLocationDidUpdate(CLLocation(latitude: user.latitude, longitude: user.longitude))
        XCTAssertEqual(regions.count, 1, "the fix after the session isn't a late first-fix recentre")
    }

    // MARK: - D5-19: delete removes files, tiles, sidecars and drafts

    func testDeleteCascadesToDerivedTilesSidecarsAndDrafts() async throws {
        let drafts = InMemoryCalibrationDraftStore()
        let vm = MapViewModel(libraryDependencies: deps(drafts: drafts), initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        vm.restoreActiveMapSelection()
        let pdf = try await importPDF("tacmap_grid_sf_iso.pdf")
        XCTAssertTrue(vm.addImportedEntry(pdf, activate: true))
        // a bake derived from it, with SQLite sidecars
        let tilesDir = try ImportedMapStorage.offlineTilesDirectory()
        let tiles = tilesDir.appendingPathComponent("tacmap-\(UUID().uuidString).mbtiles")
        try makeMBTiles(at: tiles)
        try Data("wal".utf8).write(to: URL(fileURLWithPath: tiles.path + "-wal"))
        let derived = ImportedMapEntry(id: UUID(), kind: .mbtiles, fileName: try XCTUnwrap(ImportedMapStorage.relativePath(for: tiles)),
                                       displayName: pdf.displayName, contentKey: nil,
                                       byteCount: Int64((try tiles.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0),
                                       fileModifiedAtMs: ImportedMapStorage.modifiedAtMs(tiles), importedAtMs: 0,
                                       derivedFromId: pdf.id, pdf: nil)
        // a pre WP2 tiler bake comes in through the migration as a derived MBTiles
        // entry (a WP2 bake is a record on the PDF entry instead)
        var withDerived = try XCTUnwrap(vm.library)
        withDerived.entries.append(derived)
        withDerived.active = .entry(derived.id)
        try ImportedMapLibrary.write(withDerived)
        vm.restoreActiveMapSelection()
        XCTAssertEqual(vm.activeEntryID, derived.id)
        let key = try XCTUnwrap(pdf.contentKey)
        drafts.drafts[CalibrationDraft.key(contentKey: key, pageIndex: 0)] =
            CalibrationDraft(contentKey: key, pageIndex: 0, entryId: pdf.id, datumId: nil, points: [], nextNumber: 1,
                             pending: nil, active: false, updatedAtMs: 0)

        XCTAssertTrue(vm.deleteLibraryEntry(pdf.id))
        XCTAssertEqual(vm.library?.entries.count, 0, "the derived tiles go with their PDF")
        XCTAssertTrue(vm.mapSource is OnlineRasterBasemapSource)
        XCTAssertFalse(FileManager.default.fileExists(atPath: try XCTUnwrap(ImportedMapLibrary.fileURL(pdf)).path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: tiles.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: tiles.path + "-wal"))
        XCTAssertTrue(drafts.drafts.isEmpty)
    }

    func testFailedWriteChangesNothingAndRetryCompletes() async throws {
        var failing = true
        let base = deps()
        let vm = MapViewModel(libraryDependencies: deps(write: { s in
            if failing { throw CocoaError(.fileWriteOutOfSpace) }
            try base.write(s)
        }), initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        vm.restoreActiveMapSelection()
        XCTAssertFalse(vm.selectOnlineBasemap(.osmStreet))
        XCTAssertNotNil(vm.mapSelectionPersistenceIssue)
        XCTAssertEqual((vm.mapSource as? OnlineRasterBasemapSource)?.style, .osmTopo, "nothing published")
        failing = false
        XCTAssertTrue(vm.retryMapSelectionPersistence())
        XCTAssertEqual((vm.mapSource as? OnlineRasterBasemapSource)?.style, .osmStreet)
        XCTAssertNil(vm.mapSelectionPersistenceIssue)
    }

    // MARK: - restore (D5-08)

    func testRestoreChecksSizeOnlyThenHashesOffMainAndMarksATamperedFileUnavailable() async throws {
        let setup = MapViewModel(libraryDependencies: deps(), initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        setup.restoreActiveMapSelection()
        let a = try await importPDF("tacmap_grid_sf_iso.pdf")
        XCTAssertTrue(setup.addImportedEntry(a, activate: true))

        var verifyCalls = 0
        var pending: ((Bool) -> Void)?
        let vm = MapViewModel(libraryDependencies: deps(verify: { _, _, done in
            verifyCalls += 1
            pending = done
        }), initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        XCTAssertEqual(vm.restoreActiveMapSelection(), .restored)
        XCTAssertEqual(vm.activeEntryID, a.id, "published straight from size + mtime")
        XCTAssertEqual(verifyCalls, 1, "the one hash runs through the background seam")
        // the background hash says the bytes changed under an unchanged size/mtime.
        // WP2 OD-F4 (merge): a PDF keeps its selection and fails as cannotOpen
        // instead of going online, nothing ever draws the changed bytes
        pending?(false)
        let shown = try XCTUnwrap(vm.mapSource as? PDFMapSource)
        XCTAssertEqual(shown.entryID, a.id)
        XCTAssertTrue(shown.storedFileUnavailable)
        XCTAssertEqual(vm.fileStatus(a), .sizeOrMtimeMismatch)
        XCTAssertEqual(ImportedMapStates.present(a, file: vm.fileStatus(a), draftPoints: nil, parentName: nil).subtitle.key,
                       "map_state_unavailable")
    }

    func testLockedOrCorruptLibraryDeletesNothing() async throws {
        let setup = MapViewModel(libraryDependencies: deps(), initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        setup.restoreActiveMapSelection()
        let a = try await importPDF("tacmap_grid_sf_iso.pdf")
        XCTAssertTrue(setup.addImportedEntry(a, activate: false))
        let file = try XCTUnwrap(ImportedMapLibrary.fileURL(a))
        let orphan = try ImportedMapStorage.importedMapsDirectory().appendingPathComponent("map-orphan.pdf")
        try Data("x".utf8).write(to: orphan)

        SafeStore.keyProvider = { throw DataKey.LockedError() }
        let locked = MapViewModel(libraryDependencies: deps(), initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        XCTAssertEqual(locked.restoreActiveMapSelection(), .locked)
        XCTAssertFalse(locked.selectOnlineBasemap(.osmStreet), "no writes while locked")
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: orphan.path), "no reconcile without a loaded library")
        SafeStore.keyProvider = { [testKey] in testKey }

        // a loaded library reconciles the orphan away and keeps the entry
        let loaded = MapViewModel(libraryDependencies: deps(), initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        XCTAssertEqual(loaded.restoreActiveMapSelection(), .restored)
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: orphan.path))
    }

    func testCorruptLibraryStaysCorruptAcrossRetryAndRelaunch() async throws {
        let entry = try await importPDF("tacmap_grid_rot5_plain.pdf")
        try ImportedMapLibrary.write(LibraryState(entries: [entry]))
        let libraryURL = ImportedMapLibrary.storageURLProvider()
        try Data("broken library".utf8).write(to: libraryURL)
        let file = try XCTUnwrap(ImportedMapLibrary.fileURL(entry))
        let bake = try ImportedMapStorage.offlineTilesDirectory().appendingPathComponent("tacmap-bake-recovery.mbtiles")
        try Data("bake".utf8).write(to: bake)
        var cleanupCalls = 0
        var d = deps()
        d.reconcile = { _ in cleanupCalls += 1; return true }
        d.sweepBakes = { _ in cleanupCalls += 1; return true }
        for _ in 0..<3 {
            let vm = MapViewModel(libraryDependencies: d, initialMapSource: OnlineRasterBasemapSource(.osmTopo))
            XCTAssertEqual(vm.restoreActiveMapSelection(), .corrupt)
            XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
            XCTAssertTrue(FileManager.default.fileExists(atPath: bake.path))
        }
        XCTAssertEqual(cleanupCalls, 0)
        XCTAssertTrue(SafeStore.quarantineSiblingExists(libraryURL))
    }

    func testPreviouslySealedMissingLibraryNeverLoadsAsEmpty() throws {
        try ImportedMapLibrary.write(LibraryState())
        try FileManager.default.removeItem(at: ImportedMapLibrary.storageURLProvider())
        guard case .corrupt = ImportedMapLibrary.load() else { return XCTFail("vanished sealed library must stay corrupt") }
        SafeStore.keyProvider = { throw DataKey.LockedError() }
        guard case .locked = ImportedMapLibrary.load() else { return XCTFail("unknown provenance while locked must stay locked") }
    }

    func testCorruptRebuildAndNextLaunchPreserveOrphanBakesAndDrafts() async throws {
        let entry = try await importPDF("tacmap_grid_rot5_plain.pdf")
        try ImportedMapLibrary.write(LibraryState(entries: [entry]))
        try Data("broken".utf8).write(to: ImportedMapLibrary.storageURLProvider())
        let bake = try ImportedMapStorage.offlineTilesDirectory().appendingPathComponent("tacmap-bake-orphan.mbtiles")
        try Data("offline tiles".utf8).write(to: bake)
        let drafts = InMemoryCalibrationDraftStore()
        let draft = CalibrationDraft(contentKey: "sha256:" + String(repeating: "9", count: 64),
                                     pageIndex: 0, entryId: UUID(), datumId: "WGS84", points: [],
                                     nextNumber: 1, pending: nil, active: false, updatedAtMs: 1)
        try drafts.save(draft)
        var d = deps(drafts: drafts)
        d.background = { $0() }
        d.foreground = { $0() }
        let vm = MapViewModel(libraryDependencies: d, initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        XCTAssertEqual(vm.restoreActiveMapSelection(), .corrupt)
        var rebuilt = false
        vm.rebuildCorruptLibrary { rebuilt = $0 }
        XCTAssertTrue(rebuilt)
        for _ in 0..<2 {
            let next = MapViewModel(libraryDependencies: d, initialMapSource: OnlineRasterBasemapSource(.osmTopo))
            XCTAssertEqual(next.restoreActiveMapSelection(), .restored)
            XCTAssertTrue(FileManager.default.fileExists(atPath: bake.path))
            XCTAssertEqual(drafts.drafts.count, 1)
            XCTAssertTrue(next.selectOnlineBasemap(.osmStreet))
            XCTAssertTrue(FileManager.default.fileExists(atPath: bake.path))
        }
    }

    func testPreviewCrashOffersRecoveryAgainAfterNotNowAndColdLaunch() async throws {
        let entry = try await importPDF("tacmap_grid_rot5_plain.pdf")
        try ImportedMapLibrary.write(LibraryState(entries: [entry]))
        let drafts = InMemoryCalibrationDraftStore()
        try drafts.save(CalibrationDraft(contentKey: try XCTUnwrap(entry.contentKey), pageIndex: 0,
                                         entryId: entry.id, datumId: "WGS84", points: [], nextNumber: 1,
                                         pending: nil, active: true, updatedAtMs: 1))
        let guardURL = root.appendingPathComponent("preview-guard.json")
        let firstGuard = PDFRenderGuard(url: guardURL)
        XCTAssertTrue(firstGuard.arm(kind: .base, token: entry.renderGuardToken))
        for launch in 0..<2 {
            let vm = MapViewModel(libraryDependencies: deps(drafts: drafts), initialMapSource: OnlineRasterBasemapSource(.osmTopo))
            vm.pdfRenderGuard = PDFRenderGuard(url: guardURL)
            XCTAssertEqual(vm.restoreActiveMapSelection(), .restored)
            XCTAssertEqual(vm.pdfCrashSuspect?.entryID, entry.id)
            XCTAssertTrue(vm.crashSuspectPending)
            XCTAssertNil(vm.activeEntryID)
            XCTAssertEqual(drafts.activeDraft()?.entryId, entry.id)
            if launch == 0 {
                vm.dismissCrashSuspect()
                XCTAssertEqual(vm.pdfRenderGuard.suspect, entry.renderGuardToken)
            } else {
                var resumed: UUID?
                vm.resumeCalibrationRequested = { resumed = $0 }
                vm.openCrashSuspectAnyway()
                XCTAssertEqual(resumed, entry.id)
                XCTAssertFalse(vm.crashSuspectPending)
                XCTAssertEqual(vm.library?.activeEntryID, nil)
            }
        }
    }

    func testRecoveryAdoptsExistingMapsBeyondTheNewImportLimit() throws {
        let directory = try ImportedMapStorage.importedMapsDirectory()
        for i in 0...ImportLimits.maxLibraryEntries {
            try Data("unreadable PDF \(i)".utf8).write(to: directory.appendingPathComponent("map-\(UUID().uuidString).pdf"))
        }
        let recovered = ImportedMapLibraryRecovery.rebuild(inspect: { _ in nil })
        XCTAssertEqual(recovered.entries.count, ImportLimits.maxLibraryEntries + 1)
        XCTAssertFalse(recovered.permitsCleanup)
        XCTAssertTrue(ImportedMapLibrary.reconcile(recovered))
        XCTAssertEqual(ImportedMapLibraryRecovery.candidates(inFlight: []).count, ImportLimits.maxLibraryEntries + 1)
    }

    func testRecoveredLibraryRemovesKnownSupersededBakeAndKeepsUnknownBake() async throws {
        var entry = try await importPDF("tacmap_grid_sf_iso.pdf")
        let directory = try ImportedMapStorage.offlineTilesDirectory()
        let known = "tacmap-bake-known.mbtiles"
        let unknown = directory.appendingPathComponent("tacmap-bake-unknown.mbtiles")
        let knownURL = directory.appendingPathComponent(known)
        try Data("known".utf8).write(to: knownURL)
        try Data("unknown".utf8).write(to: unknown)
        entry.pdf?.pageCount = 2
        entry.pdf?.bake = PDFBakeRecord(fileName: known, bakeKey: String(repeating: "a", count: 64),
                                        minZoom: 0, maxZoom: 14, tilePx: 512, bytes: 5)
        try ImportedMapLibrary.write(LibraryState(entries: [entry], recoveryPreservesOrphans: true))
        let vm = MapViewModel(libraryDependencies: deps(), initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        XCTAssertEqual(vm.restoreActiveMapSelection(), .restored)
        XCTAssertTrue(FileManager.default.fileExists(atPath: knownURL.path))
        XCTAssertTrue(vm.changePage(entry.id, pageIndex: 1, rotate: 0, pageBox: try XCTUnwrap(entry.pdf?.pageBox),
                                    embedded: nil, embeddedIssue: nil))
        XCTAssertFalse(FileManager.default.fileExists(atPath: knownURL.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: unknown.path))
        XCTAssertFalse(try XCTUnwrap(vm.library).permitsCleanup)
    }

    func testCorruptRetryWithoutCompletionPublishesTheRebuiltLibrary() async throws {
        let entry = try await importPDF("tacmap_grid_sf_iso.pdf")
        try ImportedMapLibrary.write(LibraryState(entries: [entry]))
        try Data("corrupt".utf8).write(to: ImportedMapLibrary.storageURLProvider())
        var dependencies = deps()
        dependencies.background = { $0() }
        dependencies.foreground = { $0() }
        let vm = MapViewModel(libraryDependencies: dependencies, initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        XCTAssertEqual(vm.restoreActiveMapSelection(), .corrupt)
        XCTAssertTrue(vm.retryMapSelectionPersistence())
        XCTAssertEqual(vm.libraryStatus, .loaded)
        XCTAssertEqual(vm.library?.entries.count, 1)
        XCTAssertNil(vm.mapSelectionPersistenceIssue)
        XCTAssertFalse(try XCTUnwrap(vm.library).permitsCleanup)
    }

    func testReconcileLeavesInFlightImportsAlone() throws {
        let dir = try ImportedMapStorage.importedMapsDirectory()
        let partial = dir.appendingPathComponent("map-busy.pdf.partial")
        let pickerCopy = dir.appendingPathComponent("map-picker.pdf")
        let stale = dir.appendingPathComponent("map-stale.pdf.partial")
        for u in [partial, pickerCopy, stale] { try Data("x".utf8).write(to: u) }
        XCTAssertTrue(ImportedMapLibrary.reconcile(LibraryState(), inFlight: [partial, pickerCopy]))
        XCTAssertTrue(FileManager.default.fileExists(atPath: partial.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: pickerCopy.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: stale.path))
    }

    // MARK: - storage hygiene (D5-14)

    func testCopyIsOpaqueHashedExcludedFromBackupAndCancellable() throws {
        let src = try fixturePDF("tacmap_grid_sf_iso.pdf")
        let dest = try ImportedMapStorage.opaqueDestination(id: UUID(), ext: "pdf")
        XCTAssertTrue(dest.lastPathComponent.hasPrefix("map-"))
        var ticks = 0
        let copy = try ImportedMapStorage.copyHashing(src, to: dest, maximumBytes: ImportLimits.pdfMaxBytes, chunkSize: 1024,
                                                      progress: { _, _ in ticks += 1 })
        defer { InFlightImportFiles.unregister(copy.url) }
        XCTAssertGreaterThan(ticks, 2)
        XCTAssertEqual(copy.contentKey, PDFSessionStore.contentKey(for: copy.url), "one pass gives the same key")
        XCTAssertEqual(try copy.url.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
        let dir = try ImportedMapStorage.importedMapsDirectory()
        XCTAssertEqual(try dir.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)

        // cancel mid copy leaves nothing behind
        let dest2 = try ImportedMapStorage.opaqueDestination(id: UUID(), ext: "pdf")
        var chunks = 0
        XCTAssertThrowsError(try ImportedMapStorage.copyHashing(src, to: dest2, maximumBytes: ImportLimits.pdfMaxBytes, chunkSize: 512,
                                                                isCancelled: { chunks > 1 }, progress: { _, _ in chunks += 1 }))
        XCTAssertFalse(FileManager.default.fileExists(atPath: dest2.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: dest2.path + ".partial"))
        // over the limit never starts
        XCTAssertThrowsError(try ImportedMapStorage.copyHashing(src, to: dest2, maximumBytes: 10)) {
            XCTAssertEqual($0 as? MapImportError, .tooLarge(limit: 10))
        }
    }

    func testInterruptedImportMarkerRemovesTheCopyOnce() throws {
        let dir = try ImportedMapStorage.importedMapsDirectory()
        let copy = dir.appendingPathComponent("map-1234.pdf")
        try Data("x".utf8).write(to: copy)
        MapImportPipeline.writeMarker(copy)
        XCTAssertTrue(MapImportPipeline.recoverInterruptedImport())
        XCTAssertFalse(FileManager.default.fileExists(atPath: copy.path))
        XCTAssertFalse(MapImportPipeline.recoverInterruptedImport(), "said once")
    }

    // MARK: - drafts (s8.1)

    func testDraftStoreIsSealedKeyedAndBounded() throws {
        let store = CalibrationDraftStore(url: { [root] in root!.appendingPathComponent("calibration-drafts.json") })
        let key = "sha256:" + String(repeating: "c", count: 64)
        let p = CalibrationPoint(number: 1, page: PdfPagePoint(x: 1, y: 2), input: "56HLH 34900 52288",
                                 reference: .grid(zone: 56, south: true, easting: 334900, northing: 6252288, cellSizeM: 1, source: .mgrs),
                                 label: "Hut")
        try store.save(CalibrationDraft(contentKey: key, pageIndex: 0, entryId: UUID(), datumId: "GDA94", points: [p],
                                        nextNumber: 2, pending: nil, active: true, updatedAtMs: 5))
        let raw = try Data(contentsOf: root.appendingPathComponent("calibration-drafts.json"))
        XCTAssertNil(raw.range(of: Data("56HLH".utf8)), "sealed, the typed text isn't in the clear")
        XCTAssertNil(raw.range(of: Data("Hut".utf8)))
        XCTAssertEqual(try store.draft(contentKey: key, pageIndex: 0)?.points.count, 1)
        XCTAssertNil(try store.draft(contentKey: key, pageIndex: 1), "another page is another draft")
        XCTAssertNil(try store.draft(contentKey: "sha256:" + String(repeating: "d", count: 64), pageIndex: 0))
        XCTAssertEqual(store.activeDraft()?.contentKey, key)

        for i in 0..<20 {
            try store.save(CalibrationDraft(contentKey: "sha256:\(i)", pageIndex: 0, entryId: UUID(), datumId: nil, points: [],
                                            nextNumber: 1, pending: nil, active: false, updatedAtMs: Int64(100 + i)))
        }
        XCTAssertEqual(store.summaries().count, CalibrationLimits.maxDrafts, "LRU by updatedAt")
        XCTAssertNil(try store.draft(contentKey: key, pageIndex: 0), "the oldest went first")

        SafeStore.keyProvider = { throw DataKey.LockedError() }
        XCTAssertThrowsError(try store.save(CalibrationDraft(contentKey: key, pageIndex: 0, entryId: UUID(), datumId: nil, points: [],
                                                             nextNumber: 1, pending: nil, active: true, updatedAtMs: 1)))
        SafeStore.keyProvider = { [testKey] in testKey }
    }
}

/// The one-time move from ActiveMapSelectionStore + PDFSessionStore (s8.2 Migration).
final class ImportedMapLibraryMigrationTests: XCTestCase {
    private let testKey = Data((0..<32).map { UInt8(90 &+ $0) })
    private var root: URL!
    private var suite: String!
    private var saved: (support: () -> URL, library: () -> URL, sel: () -> URL, selSupport: () -> URL,
                        selImported: () throws -> URL, pdfDefaults: () -> UserDefaults, pdfImported: () throws -> URL,
                        legacyDocs: () -> URL?)!

    override func setUp() {
        super.setUp()
        saved = (ImportedMapStorage.applicationSupportProvider, ImportedMapLibrary.storageURLProvider,
                 ActiveMapSelectionStore.storageURLProvider, ActiveMapSelectionStore.applicationSupportDirectoryProvider,
                 ActiveMapSelectionStore.importedMapsDirectoryProvider, PDFSessionStore.defaultsProvider,
                 PDFSessionStore.importedMapsDirectoryProvider, PDFSessionStore.legacyDocumentsDirectoryProvider)
        root = FileManager.default.temporaryDirectory.appendingPathComponent("migration-\(UUID().uuidString)")
        let r = root!
        let imported = r.appendingPathComponent("ImportedMaps")
        try? FileManager.default.createDirectory(at: imported, withIntermediateDirectories: true)
        ImportedMapStorage.applicationSupportProvider = { r }
        ImportedMapLibrary.storageURLProvider = { r.appendingPathComponent("imported-map-library.json") }
        ActiveMapSelectionStore.storageURLProvider = { r.appendingPathComponent("active-map-selection.json") }
        ActiveMapSelectionStore.applicationSupportDirectoryProvider = { r }
        ActiveMapSelectionStore.importedMapsDirectoryProvider = { imported }
        suite = "ImportedMapLibraryMigrationTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        PDFSessionStore.defaultsProvider = { defaults }
        PDFSessionStore.importedMapsDirectoryProvider = { imported }
        PDFSessionStore.legacyDocumentsDirectoryProvider = { nil }
        SafeStore.keyProvider = { [testKey] in testKey }
        SealedMigrationPolicy.resetForTests(key: testKey)
    }

    override func tearDown() {
        PDFSessionStore.clear()
        PDFSessionStore.defaultsProvider().removePersistentDomain(forName: suite)
        ImportedMapStorage.applicationSupportProvider = saved.support
        ImportedMapLibrary.storageURLProvider = saved.library
        ActiveMapSelectionStore.storageURLProvider = saved.sel
        ActiveMapSelectionStore.applicationSupportDirectoryProvider = saved.selSupport
        ActiveMapSelectionStore.importedMapsDirectoryProvider = saved.selImported
        PDFSessionStore.defaultsProvider = saved.pdfDefaults
        PDFSessionStore.importedMapsDirectoryProvider = saved.pdfImported
        PDFSessionStore.legacyDocumentsDirectoryProvider = saved.legacyDocs
        SafeStore.keyProvider = { try DataKey.key() }
        SealedMigrationPolicy.resetForTests(key: testKey)
        try? FileManager.default.removeItem(at: root)
        super.tearDown()
    }

    private func legacyCopy(_ name: String, as legacyName: String) throws -> URL {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let c = dir.appendingPathComponent("testdata/geopdf").appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: c.path) {
                let dest = root.appendingPathComponent("ImportedMaps").appendingPathComponent(legacyName)
                try FileManager.default.copyItem(at: c, to: dest)
                return dest
            }
            dir = dir.deletingLastPathComponent()
        }
        throw XCTSkip("testdata/geopdf/\(name) missing")
    }

    func testGeoPDFSessionWithOnlineActiveBecomesAnEntryAndTheOldNameGoes() throws {
        let old = try legacyCopy("tacmap_grid_sf_iso.pdf", as: "Presidio 1-24k.pdf")
        let g = try XCTUnwrap(GeoPDFReader.read(url: old)?.georef)
        let source = PDFMapSource(url: old, georef: g, contentKey: PDFSessionStore.contentKey(for: old))
        XCTAssertTrue(PDFSessionStore.save(source))
        XCTAssertTrue(ActiveMapSelectionStore.save(source))
        XCTAssertTrue(ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmTopo)))

        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .migrated(uncalibratedName: nil))
        guard case .loaded(let lib) = ImportedMapLibrary.load() else { return XCTFail("library written") }
        XCTAssertEqual(lib.active, .online(.osmTopo))
        let e = try XCTUnwrap(lib.entries.first)
        XCTAssertEqual(e.displayName, "Presidio 1-24k")
        XCTAssertEqual(e.pdf?.embedded, g)
        XCTAssertTrue(e.fileName.hasPrefix("ImportedMaps/map-"), "opaque now: \(e.fileName)")
        XCTAssertEqual(ImportedMapLibrary.fileStatus(e), .ok)
        XCTAssertFalse(FileManager.default.fileExists(atPath: old.path), "old name unlinked after the write")
        XCTAssertFalse(ImportedMapLibraryMigration.legacyPresent, "legacy stores cleared")
        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .notNeeded, "idempotent")
    }

    func testCalibratedSessionBecomesManualAndStaysActive() throws {
        let old = try legacyCopy("tacmap_grid_sf_plain.pdf", as: "Hut map.pdf")
        let truth: [(Double, Double, Double, Double)] = [
            (185.3858268, 185.3858268, 547_000, 4_177_000), (638.9291339, 185.3858268, 551_000, 4_177_000),
            (638.9291339, 865.7007874, 551_000, 4_183_000), (185.3858268, 865.7007874, 547_000, 4_183_000),
        ]
        let fids = try truth.map { x, y, e, n -> Fiduciary in
            let ll = try XCTUnwrap(GeoCrs.utm(zone: 10, south: false).inverse(x: e, y: n, ellipsoid: .wgs84))
            return Fiduciary(pdfX: x, pdfY: y, mgrs: "10SEG \(Int(e) % 100_000) \(Int(n) % 100_000)", latitude: ll.lat, longitude: ll.lon)
        }
        let readout = try XCTUnwrap(GeoPDFReader.read(url: old))
        let prov = try XCTUnwrap(PdfGeoreference.provisional(pageBox: readout.page.cropBox, rotation: 0,
                                                              centredOn: CLLocationCoordinate2D(latitude: 37.7, longitude: -122.4)))
        let source = PDFMapSource(url: old, georef: prov, contentKey: PDFSessionStore.contentKey(for: old))
        let fit = try XCTUnwrap(FiduciaryFitter.georeference(fromWGS84: fids, crop: readout.page.cropBox))
        source.adoptCalibration(georef: fit, fiduciaries: fids, transform: try XCTUnwrap(fit.bestFitLatLonAffine()))
        XCTAssertTrue(PDFSessionStore.save(source))
        XCTAssertTrue(ActiveMapSelectionStore.save(source))

        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .migrated(uncalibratedName: nil))
        guard case .loaded(let lib) = ImportedMapLibrary.load() else { return XCTFail("library written") }
        let e = try XCTUnwrap(lib.entries.first)
        XCTAssertEqual(lib.active, .entry(e.id))
        let manual = try XCTUnwrap(e.pdf?.manual)
        XCTAssertEqual(manual.datumId, "WGS84")
        XCTAssertEqual(manual.points.count, 4)
        XCTAssertTrue(manual.points.allSatisfy { $0.datumOverride == "WGS84" })
        XCTAssertLessThan(try XCTUnwrap(manual.rmsM), 0.05)
        XCTAssertEqual(ImportedMapStates.state(e, file: .ok), .calibrated)
    }

    func testCameraFallbackSessionComesOverUncalibratedAndNotActive() throws {
        let old = try legacyCopy("tacmap_grid_rot5_plain.pdf", as: "Scan.pdf")
        let readout = try XCTUnwrap(GeoPDFReader.read(url: old))
        let prov = try XCTUnwrap(PdfGeoreference.provisional(pageBox: readout.page.cropBox, rotation: 0,
                                                              centredOn: CLLocationCoordinate2D(latitude: 37.7, longitude: -122.4)))
        let source = PDFMapSource(url: old, georef: prov, contentKey: PDFSessionStore.contentKey(for: old))
        XCTAssertTrue(PDFSessionStore.save(source))
        XCTAssertTrue(ActiveMapSelectionStore.save(source))

        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .migrated(uncalibratedName: "Scan"))
        guard case .loaded(let lib) = ImportedMapLibrary.load() else { return XCTFail("library written") }
        let e = try XCTUnwrap(lib.entries.first)
        XCTAssertEqual(ImportedMapStates.state(e, file: .ok), .needsCalibration)
        if case .entry? = lib.active { XCTFail("an uncalibrated PDF is never the active basemap") }
    }

    func testLockedKeyMigratesNothingAndDeletesNothing() throws {
        let old = try legacyCopy("tacmap_grid_sf_iso.pdf", as: "Keep me.pdf")
        let g = try XCTUnwrap(GeoPDFReader.read(url: old)?.georef)
        let source = PDFMapSource(url: old, georef: g, contentKey: PDFSessionStore.contentKey(for: old))
        XCTAssertTrue(PDFSessionStore.save(source))
        XCTAssertTrue(ActiveMapSelectionStore.save(source))
        SafeStore.keyProvider = { throw DataKey.LockedError() }
        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .blocked)
        SafeStore.keyProvider = { [testKey] in testKey }
        XCTAssertTrue(FileManager.default.fileExists(atPath: old.path))
        XCTAssertTrue(ImportedMapLibraryMigration.legacyPresent)
        XCTAssertFalse(ImportedMapLibrary.exists())
        // and a library restore refuses to start fresh on top of unmigrated maps
        var d = LibraryDependencies.live
        d.legacyPresent = { ImportedMapLibraryMigration.legacyPresent }
        d.sweepBackup = {}
        d.recoverInterruptedImport = { false }
        let vm = MapViewModel(libraryDependencies: d, initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        XCTAssertEqual(vm.restoreActiveMapSelection(), .locked)
    }

    func testCrashAfterTheLinkJustRedoesTheMigration() throws {
        let old = try legacyCopy("tacmap_grid_sf_iso.pdf", as: "Twice.pdf")
        let g = try XCTUnwrap(GeoPDFReader.read(url: old)?.georef)
        let source = PDFMapSource(url: old, georef: g, contentKey: PDFSessionStore.contentKey(for: old))
        XCTAssertTrue(PDFSessionStore.save(source))
        XCTAssertTrue(ActiveMapSelectionStore.save(source))
        // a previous attempt died after linking: an orphan opaque copy exists, no library
        let orphan = try XCTUnwrap(ImportedMapLibraryMigration.linkOpaque(old, ext: "pdf"))
        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .migrated(uncalibratedName: nil))
        guard case .loaded(let lib) = ImportedMapLibrary.load() else { return XCTFail("library written") }
        XCTAssertEqual(lib.entries.count, 1)
        XCTAssertEqual(lib.active, .entry(try XCTUnwrap(lib.entries.first).id))
        // the next loaded-library reconcile drops the orphan, keeps the entry
        XCTAssertTrue(ImportedMapLibrary.reconcile(lib, inFlight: []))
        XCTAssertFalse(FileManager.default.fileExists(atPath: orphan.path))
        XCTAssertEqual(ImportedMapLibrary.fileStatus(try XCTUnwrap(lib.entries.first)), .ok)
    }

    func testMalformedLegacySessionBlocksMigrationWithoutClearingItsBytes() throws {
        let old = try legacyCopy("tacmap_grid_sf_iso.pdf", as: "Keep malformed session.pdf")
        XCTAssertTrue(ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmTopo)))
        let bytes = Data("unreadable legacy session".utf8)
        PDFSessionStore.defaultsProvider().set(bytes, forKey: "active_pdf_v1")
        let selector = try Data(contentsOf: ActiveMapSelectionStore.storageURLProvider())

        for _ in 0..<2 {
            XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .blocked)
            XCTAssertFalse(ImportedMapLibrary.exists())
            XCTAssertEqual(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"), bytes)
            XCTAssertEqual(try Data(contentsOf: ActiveMapSelectionStore.storageURLProvider()), selector)
            XCTAssertTrue(FileManager.default.fileExists(atPath: old.path))
        }
    }

    func testAuthenticatedSessionWithNonPDFBytesCannotBeSilentlyDropped() throws {
        let fixture = try legacyCopy("tacmap_grid_sf_iso.pdf", as: "Original.pdf")
        let georef = try XCTUnwrap(GeoPDFReader.read(url: fixture)?.georef)
        let old = root.appendingPathComponent("ImportedMaps/Unreadable.pdf")
        let contents = Data("not a PDF document".utf8)
        try contents.write(to: old)
        let source = PDFMapSource(url: old, georef: georef, contentKey: PDFSessionStore.contentKey(for: old))
        XCTAssertTrue(PDFSessionStore.save(source))
        XCTAssertTrue(ActiveMapSelectionStore.save(source))
        let session = try XCTUnwrap(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"))
        let selector = try Data(contentsOf: ActiveMapSelectionStore.storageURLProvider())

        for _ in 0..<2 {
            XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .blocked)
            XCTAssertFalse(ImportedMapLibrary.exists())
            XCTAssertEqual(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"), session)
            XCTAssertEqual(try Data(contentsOf: ActiveMapSelectionStore.storageURLProvider()), selector)
            XCTAssertEqual(try Data(contentsOf: old), contents)
        }
        let maps = try FileManager.default.contentsOfDirectory(at: old.deletingLastPathComponent(), includingPropertiesForKeys: nil)
        XCTAssertEqual(Set(maps.map(\.lastPathComponent)), ["Original.pdf", "Unreadable.pdf"], "blocked conversion removes only its temporary link")
    }

    func testAuthenticatedSessionWithMissingPageCannotBeSilentlyDropped() throws {
        let old = try legacyCopy("tacmap_grid_sf_iso.pdf", as: "Missing page.pdf")
        var georef = try XCTUnwrap(GeoPDFReader.read(url: old)?.georef)
        georef.page = 99
        let source = PDFMapSource(url: old, georef: georef, contentKey: PDFSessionStore.contentKey(for: old))
        XCTAssertTrue(PDFSessionStore.save(source))
        XCTAssertTrue(ActiveMapSelectionStore.save(source))
        let session = try XCTUnwrap(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"))

        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .blocked)
        XCTAssertFalse(ImportedMapLibrary.exists())
        XCTAssertEqual(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"), session)
        XCTAssertTrue(ActiveMapSelectionStore.legacyStoreExists)
        XCTAssertTrue(FileManager.default.fileExists(atPath: old.path))
    }

    func testLegacyPDFHashMismatchBlocksMigrationWithoutClearingItsCalibration() throws {
        let old = try legacyCopy("tacmap_grid_sf_iso.pdf", as: "Changed.pdf")
        let georef = try XCTUnwrap(GeoPDFReader.read(url: old)?.georef)
        let source = PDFMapSource(url: old, georef: georef, contentKey: PDFSessionStore.contentKey(for: old))
        XCTAssertTrue(PDFSessionStore.save(source))
        XCTAssertTrue(ActiveMapSelectionStore.save(source))
        let session = try XCTUnwrap(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"))
        let contents = Data("changed map bytes".utf8)
        try contents.write(to: old)

        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .blocked)
        XCTAssertFalse(ImportedMapLibrary.exists())
        XCTAssertEqual(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"), session)
        XCTAssertTrue(ActiveMapSelectionStore.legacyStoreExists)
        XCTAssertEqual(try Data(contentsOf: old), contents)
    }

    func testQuarantinedLegacySelectorBlocksEveryRestoreWithoutDeletingFilesOrDrafts() throws {
        let old = try legacyCopy("tacmap_grid_sf_iso.pdf", as: "Keep after quarantine.pdf")
        XCTAssertTrue(ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmTopo)))
        try Data("corrupt selector".utf8).write(to: ActiveMapSelectionStore.storageURLProvider())
        let bake = try ImportedMapStorage.offlineTilesDirectory().appendingPathComponent("tacmap-bake-legacy.mbtiles")
        try Data("tiles".utf8).write(to: bake)
        let drafts = InMemoryCalibrationDraftStore()
        let draft = CalibrationDraft(contentKey: "sha256:" + String(repeating: "f", count: 64), pageIndex: 0,
                                     entryId: UUID(), datumId: "WGS84", points: [], nextNumber: 1,
                                     pending: nil, active: false, updatedAtMs: 1)
        try drafts.save(draft)
        var dependencies = LibraryDependencies.live
        dependencies.drafts = drafts
        dependencies.sweepBackup = {}
        dependencies.recoverInterruptedImport = { false }
        for _ in 0..<3 {
            XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .blocked)
            let vm = MapViewModel(libraryDependencies: dependencies, initialMapSource: OnlineRasterBasemapSource(.osmTopo))
            XCTAssertEqual(vm.restoreActiveMapSelection(), .locked)
            XCTAssertFalse(ImportedMapLibrary.exists())
            XCTAssertTrue(FileManager.default.fileExists(atPath: old.path))
            XCTAssertTrue(FileManager.default.fileExists(atPath: bake.path))
            XCTAssertEqual(drafts.drafts.count, 1)
        }
        XCTAssertTrue(SafeStore.quarantineSiblingExists(ActiveMapSelectionStore.storageURLProvider()))
    }

    func testSharedLibraryLoadRowsUseTheRealStoresAndRestoreCleanup() throws {
        let fixture = try XCTUnwrap(PDFTileRenderFixtureTests.testdataURL("import_limits.json"))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: fixture)) as? [String: Any])
        let table = try XCTUnwrap(json["libraryLoad"] as? [String: Any])
        let rows = try XCTUnwrap(table["rows"] as? [[String: Any]])
        XCTAssertEqual(rows.count, 14)
        let inputPDF = try XCTUnwrap(PDFTileRenderFixtureTests.testdataURL("geopdf/tacmap_grid_sf_iso.pdf"))
        let georef = try XCTUnwrap(GeoPDFReader.read(url: inputPDF)?.georef)
        for row in rows {
            let id = try XCTUnwrap(row["id"] as? String)
            let given = try XCTUnwrap(row["given"] as? [String: Any])
            let expected = try XCTUnwrap(row["expect"] as? [String: Any])
            let directory = root.appendingPathComponent(id)
            let imported = directory.appendingPathComponent("ImportedMaps")
            try FileManager.default.createDirectory(at: imported, withIntermediateDirectories: true)
            ImportedMapStorage.applicationSupportProvider = { directory }
            ImportedMapLibrary.storageURLProvider = { directory.appendingPathComponent("imported-map-library.json") }
            ActiveMapSelectionStore.storageURLProvider = { directory.appendingPathComponent("active-map-selection.json") }
            ActiveMapSelectionStore.applicationSupportDirectoryProvider = { directory }
            ActiveMapSelectionStore.importedMapsDirectoryProvider = { imported }
            PDFSessionStore.importedMapsDirectoryProvider = { imported }
            PDFSessionStore.defaultsProvider().removePersistentDomain(forName: suite)
            SafeStore.keyProvider = { [testKey] in testKey }
            SealedMigrationPolicy.resetForTests(key: testKey)

            let libraryURL = ImportedMapLibrary.storageURLProvider()
            switch given["libraryFile"] as? String {
            case "ok":
                var state = LibraryState()
                state.recoveryPreservesOrphans = given["recoveryPreservesOrphans"] as? Bool ?? false
                try ImportedMapLibrary.write(state)
            case "unreadable": try Data("corrupt library".utf8).write(to: libraryURL)
            case "newerSchema":
                var state = LibraryState()
                state.schemaVersion = LibraryState.currentSchema + 1
                try SafeStore.write(JSONEncoder().encode(state), to: libraryURL, label: ImportedMapLibrary.label)
            default:
                if given["writtenBefore"] as? Bool == true {
                    try ImportedMapLibrary.write(LibraryState())
                    try FileManager.default.removeItem(at: libraryURL)
                }
                if given["corruptSibling"] as? Bool == true {
                    try Data("quarantine".utf8).write(to: URL(fileURLWithPath: libraryURL.path + ".corrupt-1"))
                }
            }
            let legacy = given["legacy"] as? String ?? "none"
            if legacy != "none" {
                XCTAssertTrue(ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmTopo)), id)
                if legacy == "corrupt" {
                    try Data("corrupt selector".utf8).write(to: ActiveMapSelectionStore.storageURLProvider())
                } else if legacy == "pdfUnconvertible" {
                    let url = imported.appendingPathComponent("Unreadable.pdf")
                    try Data("unconvertible document".utf8).write(to: url)
                    let source = PDFMapSource(url: url, georef: georef, contentKey: PDFSessionStore.contentKey(for: url))
                    XCTAssertTrue(PDFSessionStore.save(source), id)
                    XCTAssertTrue(ActiveMapSelectionStore.save(source), id)
                }
            }
            let migrationAction = expected["migration"] as? String ?? "none"
            if migrationAction != "none" {
                if legacy == "locked" { SafeStore.keyProvider = { throw DataKey.LockedError() } }
                let migrated = ImportedMapLibraryMigration.migrateIfNeeded()
                if migrationAction == "blocked" { XCTAssertEqual(migrated, .blocked, id) }
                else { XCTAssertEqual(migrated, .migrated(uncalibratedName: nil), id) }
                SafeStore.keyProvider = { [testKey] in testKey }
            }
            if given["missionKey"] as? String == "locked" { SafeStore.keyProvider = { throw DataKey.LockedError() } }
            let drafts = InMemoryCalibrationDraftStore()
            let draft = CalibrationDraft(contentKey: "sha256:" + String(repeating: "a", count: 64), pageIndex: 0,
                                         entryId: UUID(), datumId: nil, points: [], nextNumber: 1,
                                         pending: nil, active: false, updatedAtMs: 0)
            try drafts.save(draft)
            var reconciled = false, swept = false, cleared = false
            var dependencies = LibraryDependencies.live
            dependencies.drafts = drafts
            dependencies.reconcile = { _ in reconciled = true; return true }
            dependencies.sweepBakes = { _ in swept = true; return true }
            dependencies.clearLegacy = { cleared = true; ImportedMapLibraryMigration.clearLegacy() }
            dependencies.sweepBackup = {}
            dependencies.recoverInterruptedImport = { false }
            let vm = MapViewModel(libraryDependencies: dependencies, initialMapSource: OnlineRasterBasemapSource(.osmTopo))
            vm.pdfRenderGuard = PDFRenderGuard(url: directory.appendingPathComponent("guard.json"))
            let outcome = vm.restoreActiveMapSelection()
            let status: String
            switch outcome {
            case .restored: status = "loaded"
            case .nothing: status = "empty"
            case .corrupt: status = "corrupt"
            case .locked: status = legacy == "none" ? "locked" : "migrationPending"
            }
            XCTAssertEqual(status, expected["status"] as? String, id)
            XCTAssertEqual(reconciled, expected["reconcile"] as? Bool, id)
            XCTAssertEqual(swept, expected["bakeSweep"] as? Bool, id)
            XCTAssertEqual(drafts.drafts.isEmpty, expected["draftPrune"] as? Bool, id)
            XCTAssertEqual(cleared || migrationAction == "run" || migrationAction == "writeEmptyAndClear",
                           expected["clearLegacy"] as? Bool, id)
            XCTAssertEqual(vm.mapSelectionPersistenceIssue != nil, !(expected["issue"] is NSNull), id)
        }
        SafeStore.keyProvider = { [testKey] in testKey }
    }
}
