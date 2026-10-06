import XCTest
import CoreLocation
import MapKit
import SQLite3
import CryptoKit
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

    // on main like the app: since M2 the restored pack's open lands on main, and
    // racing it from a background executor could put it back up after the delete
    @MainActor
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
    private var savedPageTransform: ((URL) -> CGAffineTransform?)!

    override func setUp() {
        super.setUp()
        savedPageTransform = PDFSessionStore.legacyPageTransform
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
        PDFSessionStore.legacyPageTransform = savedPageTransform
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

    // MARK: - 3.0.1 salvage (s13.1 L3-L12): an uncertain legacy read ends, it never blocks forever

    /// the bytes of every map file in ImportedMaps, whatever it's called now
    private func mapHashes(_ dir: URL) throws -> Set<String> {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        var out = Set<String>()
        for name in names where name.lowercased().hasSuffix(".pdf") || name.lowercased().hasSuffix(".mbtiles") {
            let data = try Data(contentsOf: dir.appendingPathComponent(name))
            out.insert(SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined())
        }
        return out
    }

    private func importedNames() throws -> Set<String> {
        Set(try FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent("ImportedMaps").path)
            .filter { !$0.hasPrefix(".") })
    }

    private func loadedLibrary() throws -> LibraryState {
        guard case .loaded(let lib) = ImportedMapLibrary.load() else {
            XCTFail("library not written")
            throw CocoaError(.fileReadNoSuchFile)
        }
        return lib
    }

    private func liveDependencies(_ drafts: CalibrationDraftStoring = InMemoryCalibrationDraftStore()) -> LibraryDependencies {
        var d = LibraryDependencies.live
        d.drafts = drafts
        d.sweepBackup = {}
        d.recoverInterruptedImport = { false }
        d.verify = { _, _, done in done(true) }
        return d
    }

    func testMalformedLegacySessionIsSalvagedWithoutClearingItsBytes() throws {
        let old = try legacyCopy("tacmap_grid_sf_iso.pdf", as: "Keep malformed session.pdf")
        let fileBytes = try Data(contentsOf: old)
        XCTAssertTrue(ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmTopo)))
        let bytes = Data("unreadable legacy session".utf8)
        PDFSessionStore.defaultsProvider().set(bytes, forKey: "active_pdf_v1")
        let selector = try Data(contentsOf: ActiveMapSelectionStore.storageURLProvider())

        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .salvaged)
        let lib = try XCTUnwrap(try? loadedLibrary())
        XCTAssertEqual(lib.recoveryPreservesOrphans, true)
        XCTAssertEqual(lib.active, .online(.osmTopo))
        let e = try XCTUnwrap(lib.entries.first)
        XCTAssertEqual(lib.entries.count, 1)
        XCTAssertEqual(e.displayName, "Keep malformed session", "a 2.x name keeps its stem")
        XCTAssertTrue(e.fileName.hasPrefix("ImportedMaps/map-"), "linked to an opaque name: \(e.fileName)")
        XCTAssertEqual(try Data(contentsOf: try XCTUnwrap(ImportedMapLibrary.fileURL(e))), fileBytes)
        XCTAssertEqual(ImportedMapStates.state(e, file: ImportedMapLibrary.fileStatus(e)), .geoPDF, "re-inspected, first valid page")
        // the old stores stay frozen, byte for byte
        XCTAssertEqual(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"), bytes)
        XCTAssertEqual(try Data(contentsOf: ActiveMapSelectionStore.storageURLProvider()), selector)
        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .notNeeded, "ends: the library exists now")
        XCTAssertEqual(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"), bytes)
    }

    func testAuthenticatedSessionWithNonPDFBytesIsSalvagedNotDropped() throws {
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

        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .salvaged)
        let lib = try XCTUnwrap(try? loadedLibrary())
        XCTAssertEqual(lib.recoveryPreservesOrphans, true)
        XCTAssertEqual(Set(lib.entries.map(\.displayName)), ["Original", "Unreadable"])
        let bad = try XCTUnwrap(lib.entries.first { $0.displayName == "Unreadable" })
        XCTAssertEqual(bad.pdf?.pageCount, 0)
        XCTAssertEqual(ImportedMapStates.state(bad, file: ImportedMapLibrary.fileStatus(bad)), .unavailable, "listed, Delete only")
        XCTAssertEqual(try Data(contentsOf: try XCTUnwrap(ImportedMapLibrary.fileURL(bad))), contents)
        XCTAssertEqual(lib.active, .online(OnlineRasterBasemapSource.defaultStyle), "the active PDF didn't convert")
        XCTAssertEqual(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"), session)
        XCTAssertEqual(try Data(contentsOf: ActiveMapSelectionStore.storageURLProvider()), selector)
        let names = try importedNames()
        XCTAssertTrue(names.allSatisfy { ImportedMapLibraryRecovery.isOpaqueImportName($0) }, "both linked to opaque names: \(names)")
    }

    func testAuthenticatedSessionWithMissingPageIsSalvagedNotDropped() throws {
        let old = try legacyCopy("tacmap_grid_sf_iso.pdf", as: "Missing page.pdf")
        let fileBytes = try Data(contentsOf: old)
        var georef = try XCTUnwrap(GeoPDFReader.read(url: old)?.georef)
        georef.page = 99
        let source = PDFMapSource(url: old, georef: georef, contentKey: PDFSessionStore.contentKey(for: old))
        XCTAssertTrue(PDFSessionStore.save(source))
        XCTAssertTrue(ActiveMapSelectionStore.save(source))
        let session = try XCTUnwrap(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"))

        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .salvaged)
        let lib = try XCTUnwrap(try? loadedLibrary())
        let e = try XCTUnwrap(lib.entries.first)
        XCTAssertEqual(e.displayName, "Missing page")
        XCTAssertEqual(e.pdf?.pageIndex, 0, "s9.6 first valid page, not the session's page 99")
        XCTAssertEqual(try Data(contentsOf: try XCTUnwrap(ImportedMapLibrary.fileURL(e))), fileBytes)
        XCTAssertEqual(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"), session)
        XCTAssertTrue(ActiveMapSelectionStore.legacyStoreExists)
    }

    func testLegacyPDFHashMismatchIsSalvagedAndItsCalibrationStaysFrozen() throws {
        let old = try legacyCopy("tacmap_grid_sf_iso.pdf", as: "Changed.pdf")
        let georef = try XCTUnwrap(GeoPDFReader.read(url: old)?.georef)
        let source = PDFMapSource(url: old, georef: georef, contentKey: PDFSessionStore.contentKey(for: old))
        XCTAssertTrue(PDFSessionStore.save(source))
        XCTAssertTrue(ActiveMapSelectionStore.save(source))
        let session = try XCTUnwrap(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"))
        let contents = Data("changed map bytes".utf8)
        try contents.write(to: old)

        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .salvaged)
        let lib = try XCTUnwrap(try? loadedLibrary())
        let e = try XCTUnwrap(lib.entries.first)
        XCTAssertEqual(lib.entries.count, 1)
        XCTAssertNil(e.pdf?.embedded, "the old georef was for other bytes")
        XCTAssertNil(e.pdf?.manual)
        XCTAssertEqual(try Data(contentsOf: try XCTUnwrap(ImportedMapLibrary.fileURL(e))), contents)
        XCTAssertEqual(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"), session)
        XCTAssertTrue(ActiveMapSelectionStore.legacyStoreExists)
    }

    /// wp4-ios-1: this used to block every launch forever (locked, no imports, no
    /// basemap change). Now the first pass salvages and every later one is Loaded
    func testQuarantinedLegacySelectorIsSalvagedAndNeverDeletesFilesOrDrafts() throws {
        let old = try legacyCopy("tacmap_grid_sf_iso.pdf", as: "Keep after quarantine.pdf")
        let fileBytes = try Data(contentsOf: old)
        XCTAssertTrue(ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmTopo)))
        try Data("corrupt selector".utf8).write(to: ActiveMapSelectionStore.storageURLProvider())
        let bake = try ImportedMapStorage.offlineTilesDirectory().appendingPathComponent("tacmap-bake-legacy.mbtiles")
        try Data("tiles".utf8).write(to: bake)
        let drafts = InMemoryCalibrationDraftStore()
        let draft = CalibrationDraft(contentKey: "sha256:" + String(repeating: "f", count: 64), pageIndex: 0,
                                     entryId: UUID(), datumId: "WGS84", points: [], nextNumber: 1,
                                     pending: nil, active: false, updatedAtMs: 1)
        try drafts.save(draft)
        let dependencies = liveDependencies(drafts)
        for pass in 0..<3 {
            XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(drafts: drafts), pass == 0 ? .salvaged : .notNeeded)
            let vm = MapViewModel(libraryDependencies: dependencies, initialMapSource: OnlineRasterBasemapSource(.osmTopo))
            vm.pdfRenderGuard = PDFRenderGuard(url: root.appendingPathComponent("guard-\(pass).json"))
            XCTAssertEqual(vm.restoreActiveMapSelection(), .restored, "pass \(pass)")
            XCTAssertEqual(vm.libraryStatus, .loaded, "imports pass the s9.2 pre-check")
            XCTAssertNil(vm.mapSelectionPersistenceIssue, "no 'unlock mission data' any more")
            XCTAssertEqual(vm.library?.recoveryPreservesOrphans, true)
            let e = try XCTUnwrap(vm.library?.entries.first { $0.displayName == "Keep after quarantine" })
            XCTAssertEqual(try Data(contentsOf: try XCTUnwrap(ImportedMapLibrary.fileURL(e))), fileBytes)
            XCTAssertTrue(FileManager.default.fileExists(atPath: bake.path), "no bake sweep on a salvaged library")
            XCTAssertEqual(drafts.drafts.count, 1, "no draft prune either")
            XCTAssertTrue(SafeStore.quarantineSiblingExists(ActiveMapSelectionStore.storageURLProvider()))
            // and the basemap can change again, keeping the flag
            XCTAssertTrue(vm.selectOnlineBasemap(pass % 2 == 0 ? .osmStreet : .osmTopo), "pass \(pass)")
            XCTAssertEqual(try loadedLibrary().recoveryPreservesOrphans, true)
        }
    }

    /// what a 2.x build sealed before georefs: box, crop, kind and display-space points
    private func storeV1Session(file: URL, calibration: [String: Any]) throws {
        let d: [String: Any] = [
            "fileName": file.lastPathComponent,
            "contentKey": try XCTUnwrap(PDFSessionStore.contentKey(for: file)),
            "swLat": 37.72, "swLng": -122.48, "neLat": 37.81, "neLng": -122.40,
            "cropX": 0.0, "cropY": 0.0, "cropW": 824.315, "cropH": 1051.087,
            "kind": MapSourceKind.calibratedPDF.rawValue,
            "calibration": calibration,
        ]
        let sealed = try SealedEnvelope.sealFile(key: testKey, plaintext: JSONSerialization.data(withJSONObject: d),
                                                 label: "pdf_session/active_pdf")
        PDFSessionStore.defaultsProvider().set(sealed, forKey: "active_pdf_v1")
    }

    /// the ring targets of tacmap_grid_sf_plain.pdf, page 0 has no box offset or
    /// rotate so the old display space is the raw one
    private func v1Calibration() throws -> [String: Any] {
        let targets: [(Double, Double, Double, Double)] = [
            (185.3858268, 185.3858268, 547_000, 4_177_000), (638.9291339, 185.3858268, 551_000, 4_177_000),
            (638.9291339, 865.7007874, 551_000, 4_183_000), (185.3858268, 865.7007874, 547_000, 4_183_000),
        ]
        var fids: [[String: Any]] = []
        var plain: [Fiduciary] = []
        for (i, t) in targets.enumerated() {
            let ll = try XCTUnwrap(GeoCrs.utm(zone: 10, south: false).inverse(x: t.2, y: t.3, ellipsoid: .wgs84))
            fids.append(["id": "00000000-0000-0000-0000-00000000000\(i + 1)", "pdfX": t.0, "pdfY": t.1,
                         "mgrs": "f\(i)", "latitude": ll.lat, "longitude": ll.lon])
            plain.append(Fiduciary(pdfX: t.0, pdfY: t.1, mgrs: "", latitude: ll.lat, longitude: ll.lon))
        }
        let t = try AffineFitter.fit(plain).transform
        return ["fids": fids, "transform": ["a": t.a, "b": t.b, "c": t.c, "d": t.d, "e": t.e, "f": t.f]]
    }

    /// wp4-ios-8: PDFKit can't rebuild the v1 display space (CoreGraphics still
    /// opens the page). The points used to be parked in pdf_calibrations_v1 and
    /// then wiped by clearLegacy with a clean-looking migration
    func testV1PointsWhosePageSpaceCantBeRebuiltStayParkedAndTheMapStillConverts() throws {
        let old = try legacyCopy("tacmap_grid_sf_plain.pdf", as: "Hut map.pdf")
        try storeV1Session(file: old, calibration: try v1Calibration())
        XCTAssertTrue(ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmTopo)))
        PDFSessionStore.legacyPageTransform = { _ in nil }

        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .salvaged)
        let lib = try XCTUnwrap(try? loadedLibrary())
        XCTAssertEqual(lib.recoveryPreservesOrphans, true)
        let e = try XCTUnwrap(lib.entries.first)
        XCTAssertEqual(lib.entries.count, 1, "converted once, not adopted again")
        XCTAssertEqual(e.displayName, "Hut map")
        XCTAssertNil(e.pdf?.manual, "converted without a calibration")
        XCTAssertEqual(ImportedMapStates.state(e, file: .ok), .needsCalibration)
        XCTAssertNotNil(PDFSessionStore.defaultsProvider().data(forKey: "pdf_calibrations_v1"), "parked points kept")
        XCTAssertNotNil(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"), "session frozen, not cleared")

        // the parked points are the real ones: with PDFKit back they calibrate the same bytes
        PDFSessionStore.legacyPageTransform = savedPageTransform
        let url = try XCTUnwrap(ImportedMapLibrary.fileURL(e))
        let readout = try XCTUnwrap(GeoPDFReader.read(url: url))
        let prov = try XCTUnwrap(PdfGeoreference.provisional(pageBox: readout.page.cropBox, rotation: 0,
                                                              centredOn: CLLocationCoordinate2D(latitude: 37.7, longitude: -122.4)))
        let again = PDFMapSource(url: url, georef: prov, contentKey: e.contentKey)
        PDFSessionStore.applyCalibrationIfKnown(to: again)
        XCTAssertEqual((again.fiduciaries ?? again.pendingFiduciaries).count, 4)
    }

    /// what build 1.2.2 wrote: the bare selection, no schemaVersion, no retained
    private func writeBareSelector() throws {
        try SafeStore.write(Data(#"{"kind":"online","value":"osmTopo"}"#.utf8),
                            to: ActiveMapSelectionStore.storageURLProvider(), label: "map_source/active_selection")
    }

    /// 1.2.2 straight to 3.0: decoding the bare selector called PDFSessionStore.load(),
    /// which parked the unrebuildable v1 points and rewrote the session as a clean
    /// v2 before migrationRead looked. The plain run then cleared the parked points
    func testBareSelectorDoesNotLetTheSessionLoadLoseV1Points() throws {
        let old = try legacyCopy("tacmap_grid_sf_plain.pdf", as: "Hut map.pdf")
        try storeV1Session(file: old, calibration: try v1Calibration())
        try writeBareSelector()
        PDFSessionStore.legacyPageTransform = { _ in nil }

        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .salvaged)
        let lib = try XCTUnwrap(try? loadedLibrary())
        XCTAssertEqual(lib.recoveryPreservesOrphans, true)
        XCTAssertEqual(lib.active, .online(.osmTopo))
        XCTAssertEqual(lib.entries.map(\.displayName), ["Hut map"])
        XCTAssertNotNil(PDFSessionStore.defaultsProvider().data(forKey: "pdf_calibrations_v1"), "parked points kept")
        XCTAssertNotNil(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"), "session frozen")
    }

    /// same path, PDF changed since: load() removed the session there and then, so
    /// the migration saw no PDF at all, wrote an unflagged library and the restore's
    /// reconcile deleted the 2.x file. Now the hash mismatch salvages it
    func testBareSelectorWithAChangedPDFAdoptsTheFileInsteadOfReconcilingIt() throws {
        let old = try legacyCopy("tacmap_grid_sf_iso.pdf", as: "Changed.pdf")
        let georef = try XCTUnwrap(GeoPDFReader.read(url: old)?.georef)
        XCTAssertTrue(PDFSessionStore.save(PDFMapSource(url: old, georef: georef, contentKey: PDFSessionStore.contentKey(for: old))))
        try writeBareSelector()
        let session = try XCTUnwrap(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"))
        let contents = Data("changed map bytes".utf8)
        try contents.write(to: old)

        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .salvaged)
        XCTAssertEqual(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"), session)
        let vm = MapViewModel(libraryDependencies: liveDependencies(), initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        vm.pdfRenderGuard = PDFRenderGuard(url: root.appendingPathComponent("guard.json"))
        XCTAssertEqual(vm.restoreActiveMapSelection(), .restored)
        XCTAssertEqual(vm.library?.recoveryPreservesOrphans, true)
        let e = try XCTUnwrap(vm.library?.entries.first)
        XCTAssertEqual(vm.library?.entries.count, 1)
        XCTAssertEqual(e.displayName, "Changed")
        XCTAssertEqual(try Data(contentsOf: try XCTUnwrap(ImportedMapLibrary.fileURL(e))), contents, "adopted, not reconciled")
    }

    /// L6 / wp4-android-8 on iOS: a draft that won't save writes no library and
    /// clears nothing; the links this attempt made go again
    func testMigrationDraftSaveFailureWritesNothingAndClearsNothing() throws {
        let old = try legacyCopy("tacmap_grid_sf_iso.pdf", as: "Two points.pdf")
        let georef = try XCTUnwrap(GeoPDFReader.read(url: old)?.georef)
        let source = PDFMapSource(url: old, georef: georef, contentKey: PDFSessionStore.contentKey(for: old))
        source.keepPendingFiduciaries([Fiduciary(pdfX: 200, pdfY: 200, mgrs: "a", latitude: 37.75, longitude: -122.45),
                                       Fiduciary(pdfX: 600, pdfY: 800, mgrs: "b", latitude: 37.79, longitude: -122.42)])
        XCTAssertTrue(PDFSessionStore.save(source))
        XCTAssertTrue(ActiveMapSelectionStore.save(source))
        let session = try XCTUnwrap(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"))
        let drafts = InMemoryCalibrationDraftStore()
        drafts.locked = true

        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(drafts: drafts), .blocked)
        XCTAssertFalse(ImportedMapLibrary.exists())
        XCTAssertEqual(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"), session)
        XCTAssertTrue(ActiveMapSelectionStore.legacyStoreExists)
        XCTAssertEqual(try importedNames(), ["Two points.pdf"], "the attempt's link is gone, the file isn't")

        drafts.locked = false
        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(drafts: drafts), .migrated(uncalibratedName: nil))
        let lib = try XCTUnwrap(try? loadedLibrary())
        let e = try XCTUnwrap(lib.entries.first)
        let saved = try XCTUnwrap(drafts.drafts.values.first)
        XCTAssertEqual(saved.entryId, e.id, "the retry's draft points at the entry that got written")
        XCTAssertEqual(saved.points.count, 2)
        XCTAssertFalse(ImportedMapLibraryMigration.legacyPresent)
    }

    /// what SafeStore.write really does when the keychain sealed-only record
    /// fails: the sealed bytes are already down, then it throws
    private let landsThenThrows: (LibraryState) throws -> Void = { s in
        try ImportedMapLibrary.write(s)
        throw CocoaError(.fileWriteNoPermission)
    }

    /// F1: a library write that throws after landing used to unlink the links the
    /// landed library points at. The restore right after loads it (plain run, so
    /// cleanup is on) and its reconcile ate the 2.x name, the only copy left
    func testMigrationWriteThatThrowsAfterTheLibraryLandedKeepsTheMap() throws {
        let old = try legacyCopy("tacmap_grid_sf_iso.pdf", as: "Foo.pdf")
        let fileBytes = try Data(contentsOf: old)
        let g = try XCTUnwrap(GeoPDFReader.read(url: old)?.georef)
        let source = PDFMapSource(url: old, georef: g, contentKey: PDFSessionStore.contentKey(for: old))
        XCTAssertTrue(PDFSessionStore.save(source))
        XCTAssertTrue(ActiveMapSelectionStore.save(source))

        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(write: landsThenThrows), .blocked)
        let lib = try XCTUnwrap(try? loadedLibrary())
        let e = try XCTUnwrap(lib.entries.first)
        XCTAssertEqual(lib.active, .entry(e.id))
        XCTAssertEqual(ImportedMapLibrary.fileStatus(e), .ok, "the file the landed library names is still there")
        XCTAssertTrue(FileManager.default.fileExists(atPath: old.path), "nothing unlinked")
        XCTAssertTrue(ImportedMapLibraryMigration.legacyPresent, "nothing cleared")

        // same launch: ContentView restores straight after a blocked hop. Loaded and
        // unflagged, so D8 and the reconcile run for real
        let vm = MapViewModel(libraryDependencies: liveDependencies(), initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        vm.pdfRenderGuard = PDFRenderGuard(url: root.appendingPathComponent("guard.json"))
        XCTAssertEqual(vm.restoreActiveMapSelection(), .restored)
        XCTAssertEqual(vm.activeEntryID, e.id)
        let url = try XCTUnwrap(ImportedMapLibrary.fileURL(e))
        XCTAssertEqual(try? Data(contentsOf: url), fileBytes, "map bytes survive the reconcile")
        XCTAssertFalse(ImportedMapLibraryMigration.legacyPresent)
        XCTAssertEqual(try importedNames(), [url.lastPathComponent], "only the 2.x name went, its bytes live on under the opaque one")
    }

    /// F1 for a salvage: the flagged library that landed has to find its files
    /// under the names it lists, not under 2.x names nothing lists any more
    func testSalvageWriteThatThrowsAfterTheLibraryLandedKeepsWhatItLists() throws {
        let old = try legacyCopy("tacmap_grid_sf_iso.pdf", as: "Malformed.pdf")
        let fileBytes = try Data(contentsOf: old)
        XCTAssertTrue(ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmTopo)))
        let session = Data("unreadable legacy session".utf8)
        PDFSessionStore.defaultsProvider().set(session, forKey: "active_pdf_v1")

        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(write: landsThenThrows), .blocked)
        let lib = try XCTUnwrap(try? loadedLibrary())
        XCTAssertEqual(lib.recoveryPreservesOrphans, true)
        let e = try XCTUnwrap(lib.entries.first)
        XCTAssertEqual(lib.entries.count, 1)
        XCTAssertEqual(try? Data(contentsOf: try XCTUnwrap(ImportedMapLibrary.fileURL(e))), fileBytes)
        XCTAssertEqual(ImportedMapStates.state(e, file: ImportedMapLibrary.fileStatus(e)), .geoPDF)
        XCTAssertTrue(FileManager.default.fileExists(atPath: old.path), "the old name stays, nothing deleted")
        XCTAssertEqual(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"), session)
    }

    /// the other side of F1: a write that throws before anything lands still
    /// takes this attempt's links back, like a failed draft save
    func testMigrationWriteThatThrowsBeforeLandingStillUnlinksTheAttempt() throws {
        let old = try legacyCopy("tacmap_grid_sf_iso.pdf", as: "Bar.pdf")
        let g = try XCTUnwrap(GeoPDFReader.read(url: old)?.georef)
        let source = PDFMapSource(url: old, georef: g, contentKey: PDFSessionStore.contentKey(for: old))
        XCTAssertTrue(PDFSessionStore.save(source))
        XCTAssertTrue(ActiveMapSelectionStore.save(source))

        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(write: { _ in throw CocoaError(.fileWriteOutOfSpace) }), .blocked)
        XCTAssertFalse(ImportedMapLibrary.exists())
        XCTAssertEqual(try importedNames(), ["Bar.pdf"])
        XCTAssertTrue(ImportedMapLibraryMigration.legacyPresent)
    }

    private func makeMBTiles(_ url: URL, name: String) throws {
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &db), SQLITE_OK)
        defer { sqlite3_close(db) }
        XCTAssertEqual(sqlite3_exec(db, """
        CREATE TABLE metadata (name TEXT, value TEXT);
        CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);
        INSERT INTO metadata VALUES ('name','\(name)'),('format','png'),('bounds','-1.0,-2.0,3.0,4.0');
        INSERT INTO tiles VALUES (0,0,0,X'01');
        """, nil, nil, nil), SQLITE_OK)
    }

    /// 3.0.2 s14.1 rule 8 through the real salvage: last launch died while adopt
    /// had an orphan pack open. The 2.x pack's name read runs before adopt and
    /// used to overwrite that marker, so adopt opened the orphan again, and the
    /// app died at every launch
    func testSalvageNeverReopensAnOrphanPackTheLastAdoptionDiedOn() throws {
        let imported = root.appendingPathComponent("ImportedMaps")
        let legacy = imported.appendingPathComponent("Ridge.mbtiles")
        try makeMBTiles(legacy, name: "Ridge pack")
        let orphan = imported.appendingPathComponent("map-\(UUID().uuidString.lowercased()).mbtiles")
        try makeMBTiles(orphan, name: "Orphan pack")
        XCTAssertTrue(ActiveMapSelectionStore.save(try XCTUnwrap(OfflineTileMapSource(url: legacy))))
        // any uncertain read salvages, an unreadable session will do
        PDFSessionStore.defaultsProvider().set(Data("unreadable legacy session".utf8), forKey: "active_pdf_v1")
        let marker = try XCTUnwrap(ImportedMapLibraryRecovery.markerURL)
        try Data(orphan.lastPathComponent.utf8).write(to: marker)
        var opened: [String] = []
        MBTilesStore.admissionOpenHookForTesting = { opened.append($0.lastPathComponent) }
        defer { MBTilesStore.admissionOpenHookForTesting = nil }

        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .salvaged)
        XCTAssertFalse(opened.contains(orphan.lastPathComponent), "reopened the pack it died on: \(opened)")
        let lib = try loadedLibrary()
        XCTAssertEqual(lib.entries.count, 2)
        let adopted = try XCTUnwrap(lib.entries.first { $0.fileName.hasSuffix(orphan.lastPathComponent) })
        XCTAssertEqual(adopted.fileModifiedAtMs, -1, "adopted unavailable, never opened")
        let converted = try XCTUnwrap(lib.entries.first { $0.id != adopted.id })
        XCTAssertEqual(converted.displayName, "Ridge", "a marker down means no name read, the stem stands in")
        XCTAssertEqual(lib.active, .entry(converted.id))
        XCTAssertTrue(FileManager.default.fileExists(atPath: orphan.path), "never deletes")
        XCTAssertFalse(FileManager.default.fileExists(atPath: marker.path), "adopt took it off")
    }

    /// L7: map files with no library and no legacy store at all
    func testOrphanMapFilesWithNoLibraryAreAdoptedNotReconciled() throws {
        let orphan = root.appendingPathComponent("ImportedMaps/map-\(UUID().uuidString.lowercased()).pdf")
        try FileManager.default.copyItem(at: try XCTUnwrap(PDFTileRenderFixtureTests.testdataURL("geopdf/tacmap_grid_sf_iso.pdf")),
                                         to: orphan)
        let bake = try ImportedMapStorage.offlineTilesDirectory().appendingPathComponent("tacmap-bake-orphan.mbtiles")
        try Data("tiles".utf8).write(to: bake)
        XCTAssertTrue(ImportedMapLibraryMigration.pending)
        var swept = false
        var d = liveDependencies()
        d.sweepBakes = { _ in swept = true; return true }
        // before the hop ran (or after its write failed): pending, not a first launch
        let early = MapViewModel(libraryDependencies: d, initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        XCTAssertEqual(early.restoreActiveMapSelection(), .locked)
        XCTAssertFalse(swept)
        XCTAssertTrue(FileManager.default.fileExists(atPath: orphan.path))

        XCTAssertEqual(ImportedMapLibraryMigration.migrateIfNeeded(), .adoptedOrphans)
        let lib = try XCTUnwrap(try? loadedLibrary())
        XCTAssertEqual(lib.recoveryPreservesOrphans, true)
        XCTAssertEqual(lib.active, .online(OnlineRasterBasemapSource.defaultStyle))
        let e = try XCTUnwrap(lib.entries.first)
        XCTAssertEqual(lib.entries.count, 1, "the bake isn't a map entry")
        XCTAssertEqual(e.displayName, Messages.mapRecoveredName(DisplayFormat.number(1, decimals: 0)))
        XCTAssertEqual(ImportedMapLibrary.fileURL(e)?.lastPathComponent, orphan.lastPathComponent, "opaque, adopted where it is")
        let vm = MapViewModel(libraryDependencies: d, initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        XCTAssertEqual(vm.restoreActiveMapSelection(), .restored)
        XCTAssertFalse(swept)
        XCTAssertTrue(FileManager.default.fileExists(atPath: bake.path))
    }

    /// s9.8 owns the copy a pending marker names: never adopted, and not enough
    /// on its own to call an Empty library pending
    func testInterruptedImportCopyIsLeftToTheCrashBreaker() throws {
        let token = "map-\(UUID().uuidString.lowercased())"
        let copy = root.appendingPathComponent("ImportedMaps/\(token).pdf")
        try Data("half parsed".utf8).write(to: copy)
        try Data(token.utf8).write(to: try XCTUnwrap(MapImportPipeline.markerURL))
        XCTAssertFalse(ImportedMapLibraryRecovery.managedFilesPresent(inFlight: []))
        XCTAssertTrue(ImportedMapLibraryRecovery.adopt(excluding: [], inFlight: []).entries.isEmpty)
        XCTAssertTrue(MapImportPipeline.recoverInterruptedImport())
        XCTAssertFalse(FileManager.default.fileExists(atPath: copy.path))
    }

    // MARK: - shared libraryLoad rows, legacy states built from real stores (s13.4)

    private struct Pass {
        var status = "", migration = "none"
        var notice = false, reconciled = false, swept = false, cleared = false, pruned = false
        var migrationDrafts = 0
        var vm: MapViewModel?
    }

    /// two pending points on a GeoPDF: they become a migration draft, so a
    /// failing draft store has something to fail on
    private func pendingPoints() -> [Fiduciary] {
        [Fiduciary(pdfX: 200, pdfY: 200, mgrs: "a", latitude: 37.75, longitude: -122.45),
         Fiduciary(pdfX: 600, pdfY: 800, mgrs: "b", latitude: 37.79, longitude: -122.42)]
    }

    /// libraryLoad.legacyCodes, on disk the way 2.x / an earlier pass left them
    private func buildLegacy(_ code: String, imported: URL, georef: PdfGeoreference, geoPDF: URL, plainPDF: URL) throws {
        let selector = ActiveMapSelectionStore.storageURLProvider()
        func copy(_ src: URL, _ name: String) throws -> URL {
            let url = imported.appendingPathComponent(name)
            try FileManager.default.copyItem(at: src, to: url)
            return url
        }
        func session(_ url: URL) -> PDFMapSource {
            PDFMapSource(url: url, georef: georef, contentKey: PDFSessionStore.contentKey(for: url))
        }
        switch code {
        case "none":
            break
        case "readable", "locked":
            let source = session(try copy(geoPDF, "Readable.pdf"))
            source.keepPendingFiduciaries(pendingPoints())
            XCTAssertTrue(PDFSessionStore.save(source), code)
            XCTAssertTrue(ActiveMapSelectionStore.save(source), code)
        case "namesNothing":
            let url = try copy(geoPDF, "Gone.pdf")
            XCTAssertTrue(PDFSessionStore.save(session(url)), code)
            XCTAssertTrue(ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmTopo)), code)
            try FileManager.default.removeItem(at: url)
        case "corrupt":
            XCTAssertTrue(ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmTopo)), code)
            try Data("corrupt selector".utf8).write(to: selector)
            _ = try copy(geoPDF, "Keep.pdf")
        case "quarantinedOnly":
            try Data("quarantined selector".utf8).write(to: URL(fileURLWithPath: selector.path + ".corrupt-1"))
        case "sessionInvalid":
            XCTAssertTrue(ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmTopo)), code)
            _ = try copy(geoPDF, "Session.pdf")
            PDFSessionStore.defaultsProvider().set(Data("unreadable legacy session".utf8), forKey: "active_pdf_v1")
        case "pdfHashMismatch":
            let url = try copy(geoPDF, "Changed.pdf")
            let source = session(url)
            XCTAssertTrue(PDFSessionStore.save(source), code)
            XCTAssertTrue(ActiveMapSelectionStore.save(source), code)
            try Data("changed map bytes".utf8).write(to: url)
        case "pdfUnconvertible":
            let url = imported.appendingPathComponent("Unreadable.pdf")
            try Data("unconvertible document".utf8).write(to: url)
            let source = session(url)
            XCTAssertTrue(PDFSessionStore.save(source), code)
            XCTAssertTrue(ActiveMapSelectionStore.save(source), code)
        case "v1PointsUnrebuildable":
            try storeV1Session(file: try copy(plainPDF, "Hut map.pdf"), calibration: try v1Calibration())
            XCTAssertTrue(ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmTopo)), code)
            PDFSessionStore.legacyPageTransform = { _ in nil }
        default:
            XCTFail("no on-disk builder for legacy code \(code)")
        }
    }

    /// one launch: ContentView's migration hop (same gate), then a fresh view
    /// model's restore. Retry and relaunch both go through exactly this
    private func restorePass(given: [String: Any], legacy: String, directory: URL, n: Int) throws -> Pass {
        var p = Pass()
        let writes = given["writes"] as? String ?? "ok"
        let migrationDrafts = InMemoryCalibrationDraftStore()
        migrationDrafts.locked = writes == "draftFails"
        let write: (LibraryState) throws -> Void = writes == "libraryFails"
            ? { _ in throw CocoaError(.fileWriteOutOfSpace) } : ImportedMapLibrary.write
        let keyLocked = given["missionKey"] as? String == "locked"
        if keyLocked || legacy == "locked" { SafeStore.keyProvider = { throw DataKey.LockedError() } }
        if !ImportedMapLibrary.exists() && ImportedMapLibraryMigration.pending {
            switch ImportedMapLibraryMigration.migrateIfNeeded(drafts: migrationDrafts, write: write) {
            case .notNeeded: p.migration = "none"
            case .blocked: p.migration = "blocked"
            case .migrated:
                guard case .loaded(let s) = ImportedMapLibrary.load() else { XCTFail("migrated, no library"); break }
                p.migration = s.entries.isEmpty ? "writeEmptyAndClear" : "run"
            case .salvaged: p.migration = "salvage"; p.notice = true
            case .adoptedOrphans: p.migration = "adoptOrphans"; p.notice = true
            }
        }
        p.migrationDrafts = migrationDrafts.drafts.count
        if !keyLocked { SafeStore.keyProvider = { [testKey] in testKey } }
        let drafts = InMemoryCalibrationDraftStore()
        try drafts.save(CalibrationDraft(contentKey: "sha256:" + String(repeating: "a", count: 64), pageIndex: 0,
                                         entryId: UUID(), datumId: nil, points: [], nextNumber: 1,
                                         pending: nil, active: false, updatedAtMs: 0))
        var dependencies = liveDependencies(drafts)
        var reconciled = false, swept = false, cleared = false
        dependencies.reconcile = { _ in reconciled = true; return true }
        dependencies.sweepBakes = { _ in swept = true; return true }
        dependencies.clearLegacy = { cleared = true; ImportedMapLibraryMigration.clearLegacy() }
        let vm = MapViewModel(libraryDependencies: dependencies, initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        vm.pdfRenderGuard = PDFRenderGuard(url: directory.appendingPathComponent("guard-\(n).json"))
        switch vm.restoreActiveMapSelection() {
        case .restored: p.status = "loaded"
        case .nothing: p.status = "empty"
        case .corrupt: p.status = "corrupt"
        case .locked: p.status = keyLocked ? "locked" : "migrationPending"
        }
        SafeStore.keyProvider = { [testKey] in testKey }
        p.reconciled = reconciled
        p.swept = swept
        p.cleared = cleared || p.migration == "run" || p.migration == "writeEmptyAndClear"
        p.pruned = drafts.drafts.isEmpty
        p.vm = vm
        return p
    }

    func testSharedLibraryLoadRowsUseTheRealStoresAndRestoreCleanup() throws {
        let fixture = try XCTUnwrap(PDFTileRenderFixtureTests.testdataURL("import_limits.json"))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: fixture)) as? [String: Any])
        let table = try XCTUnwrap(json["libraryLoad"] as? [String: Any])
        let allRows = try XCTUnwrap(table["rows"] as? [[String: Any]])
        XCTAssertEqual(allRows.count, 32)
        let rows = allRows.filter { ($0["platforms"] as? [String])?.contains("ios") == true }
        XCTAssertEqual(rows.count, 27, "the retained-selector and ledger-only rows are Android only")
        let codes = try XCTUnwrap(table["legacyCodes"] as? [String: Any])
        let geoPDF = try XCTUnwrap(PDFTileRenderFixtureTests.testdataURL("geopdf/tacmap_grid_sf_iso.pdf"))
        let plainPDF = try XCTUnwrap(PDFTileRenderFixtureTests.testdataURL("geopdf/tacmap_grid_sf_plain.pdf"))
        let georef = try XCTUnwrap(GeoPDFReader.read(url: geoPDF)?.georef)
        for row in rows {
            let id = try XCTUnwrap(row["id"] as? String)
            let given = try XCTUnwrap(row["given"] as? [String: Any])
            let expected = try XCTUnwrap(row["expect"] as? [String: Any])
            let next = try XCTUnwrap(expected["nextRestore"] as? [String: Any])
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
            PDFSessionStore.legacyPageTransform = savedPageTransform
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
            XCTAssertNotNil(codes[legacy], id)
            try buildLegacy(legacy, imported: imported, georef: georef, geoPDF: geoPDF, plainPDF: plainPDF)
            if given["managedFiles"] as? Bool == true {
                // an opaque map file no store names, what a lost library or an earlier pass leaves
                try FileManager.default.copyItem(at: plainPDF, to: imported.appendingPathComponent("map-\(UUID().uuidString.lowercased()).pdf"))
            }
            XCTAssertEqual(ImportedMapLibraryRecovery.managedFilesPresent(inFlight: []),
                           try !mapHashes(imported).isEmpty, "\(id) managedFiles")
            let mapsBefore = try mapHashes(imported)
            let selector = ActiveMapSelectionStore.storageURLProvider()
            let quarantined = legacy == "corrupt" || legacy == "quarantinedOnly"
            let sessionBefore = PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1")
            let parkedBefore = PDFSessionStore.defaultsProvider().data(forKey: "pdf_calibrations_v1")

            var everCleared = false
            for (n, e) in [expected, next].enumerated() {
                let ctx = "\(id) pass \(n + 1)"
                let p = try restorePass(given: given, legacy: legacy, directory: directory, n: n)
                let vm = try XCTUnwrap(p.vm)
                XCTAssertEqual(p.status, e["status"] as? String, ctx)
                XCTAssertEqual(p.migration, e["migration"] as? String, ctx)
                XCTAssertEqual(p.reconciled, e["reconcile"] as? Bool, ctx)
                XCTAssertEqual(p.cleared, e["clearLegacy"] as? Bool, ctx)
                XCTAssertEqual(vm.mapSelectionPersistenceIssue != nil, !(e["issue"] is NSNull), ctx)
                XCTAssertEqual(p.notice, e["notice"] as? String == "recovered", ctx)
                XCTAssertEqual(vm.libraryStatus == .loaded, e["importAllowed"] as? Bool, ctx)
                if e["recoveryPreservesOrphans"] is NSNull {
                    XCTAssertNil(vm.library, ctx)
                } else {
                    XCTAssertEqual(vm.library?.recoveryPreservesOrphans ?? false, e["recoveryPreservesOrphans"] as? Bool, ctx)
                }
                if n == 0 {
                    XCTAssertEqual(p.swept, e["bakeSweep"] as? Bool, ctx)
                    XCTAssertEqual(p.pruned, e["draftPrune"] as? Bool, ctx)
                    XCTAssertEqual(vm.libraryStatus == .loaded, e["basemapChangeAllowed"] as? Bool, ctx)
                    // L6: the migration's draft was saved before the library write, failing or not.
                    // a write already on record means corrupt, no migration runs so no draft either
                    if legacy == "readable" && given["libraryFile"] as? String == "absent"
                        && given["writtenBefore"] as? Bool != true && given["writes"] as? String != "draftFails" {
                        XCTAssertEqual(p.migrationDrafts, 1, ctx)
                    }
                }
                everCleared = everCleared || p.cleared
                // L9: no map bytes, quarantine copy or frozen store goes anywhere
                XCTAssertTrue(mapsBefore.isSubset(of: try mapHashes(imported)), ctx)
                if quarantined { XCTAssertTrue(SafeStore.quarantineSiblingExists(selector), ctx) }
                if !everCleared {
                    XCTAssertEqual(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"), sessionBefore, ctx)
                    // the v1 row parks its points during the read, every other row leaves it alone
                    if legacy != "v1PointsUnrebuildable" {
                        XCTAssertEqual(PDFSessionStore.defaultsProvider().data(forKey: "pdf_calibrations_v1"), parkedBefore, ctx)
                    }
                }
                // L11: a usable restore takes a basemap change and the write keeps the flag
                if n == 1 {
                    let wasFlagged = vm.library?.recoveryPreservesOrphans == true
                    XCTAssertEqual(vm.selectOnlineBasemap(.osmStreet), e["importAllowed"] as? Bool, ctx)
                    if e["importAllowed"] as? Bool == true, case .loaded(let s) = ImportedMapLibrary.load() {
                        XCTAssertEqual(s.recoveryPreservesOrphans == true, wasFlagged, ctx)
                    }
                }
            }
            if legacy == "v1PointsUnrebuildable" {
                XCTAssertNotNil(PDFSessionStore.defaultsProvider().data(forKey: "pdf_calibrations_v1"), id)
            }
        }
        SafeStore.keyProvider = { [testKey] in testKey }
    }
}
