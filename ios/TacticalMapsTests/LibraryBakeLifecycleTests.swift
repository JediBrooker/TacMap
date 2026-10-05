import XCTest
import SQLite3
import CoreLocation
@testable import TacticalMaps

/// WP2's bake, crash guard and OD-F4 guarantees on the WP4 library. The merge
/// moved the bake record and the guard token off the legacy sealed PDF session
/// onto the library entry, so these are the WP2 ActiveMapSelectionStoreTests
/// (OD-F4, bake reconcile, Delete Map, R3-2 sweep, Try Again) and
/// PDFBakePublishTests (S1) asserting the same things against the library.
final class LibraryBakeLifecycleTests: XCTestCase {
    typealias F = PDFTileRenderFixtureTests
    private let testKey = Data((0..<32).map { UInt8(truncatingIfNeeded: 41 &* $0 &+ 7) })
    private var root: URL!
    private var originalSupport: (() -> URL)!
    private var originalLibraryURL: (() -> URL)!
    private var failWrites = false
    private var legacyPresent = false

    override func setUp() {
        super.setUp()
        originalSupport = ImportedMapStorage.applicationSupportProvider
        originalLibraryURL = ImportedMapLibrary.storageURLProvider
        root = FileManager.default.temporaryDirectory.appendingPathComponent("library-bake-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let r = root!
        ImportedMapStorage.applicationSupportProvider = { r }
        ImportedMapLibrary.storageURLProvider = { r.appendingPathComponent("imported-map-library.json") }
        SafeStore.keyProvider = { [testKey] in testKey }
        SealedMigrationPolicy.resetForTests(key: testKey)
        failWrites = false
        legacyPresent = false
    }

    override func tearDown() {
        ImportedMapStorage.applicationSupportProvider = originalSupport
        ImportedMapLibrary.storageURLProvider = originalLibraryURL
        SafeStore.keyProvider = { try DataKey.key() }
        SealedMigrationPolicy.resetForTests(key: testKey)
        try? FileManager.default.removeItem(at: root)
        super.tearDown()
    }

    // MARK: - helpers

    /// offline_tiles, made on first use so plain Data writes into it work
    private var tilesDir: URL {
        let d = ImportedMapLibrary.offlineTilesDirectory
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    private func deps() -> LibraryDependencies {
        var d = LibraryDependencies.live
        d.drafts = InMemoryCalibrationDraftStore()
        d.verify = { _, _, done in done(true) }
        d.legacyPresent = { [unowned self] in self.legacyPresent }
        d.sweepBackup = {}
        d.recoverInterruptedImport = { false }
        let write = d.write
        d.write = { [unowned self] s in
            if self.failWrites { throw CocoaError(.fileWriteOutOfSpace) }
            try write(s)
        }
        return d
    }

    private func viewModel() -> MapViewModel {
        let vm = MapViewModel(libraryDependencies: deps(), initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        vm.pdfRenderGuard = PDFRenderGuard(url: root.appendingPathComponent("guard-\(UUID().uuidString).json"))
        return vm
    }

    /// a fixture copied into ImportedMaps under an opaque name, as an import does
    private func importedEntry(_ fixture: String = "geopdf/tacmap_grid_sf_iso.pdf") throws -> ImportedMapEntry {
        let src = try XCTUnwrap(F.testdataURL(fixture))
        let url = try ImportedMapStorage.importedMapsDirectory().appendingPathComponent("map-\(UUID().uuidString).pdf")
        try FileManager.default.copyItem(at: src, to: url)
        // in flight till it's in the library, same as a real import (a reconcile
        // on the way would otherwise eat it)
        InFlightImportFiles.register(url)
        let g = try XCTUnwrap(GeoPDFReader.read(url: url)?.georef)
        let size = Int64(try XCTUnwrap(url.resourceValues(forKeys: [.fileSizeKey]).fileSize))
        let info = ImportedMapEntry.PDFInfo(pageCount: 1, pageIndex: 0, rotate: 0, pageBox: g.crop, embedded: g,
                                            embeddedIssue: nil, manual: nil, firstRenderPending: nil,
                                            renderGuardToken: UUID().uuidString)
        return ImportedMapEntry(id: UUID(), kind: .pdf, fileName: try XCTUnwrap(ImportedMapStorage.relativePath(for: url)),
                                displayName: "Sheet", contentKey: PDFSessionStore.contentKey(for: url), byteCount: size,
                                fileModifiedAtMs: ImportedMapStorage.modifiedAtMs(url), importedAtMs: 0,
                                derivedFromId: nil, pdf: info)
    }

    /// restore an empty library, add the entry as the active map, the source on screen
    private func showing(_ entry: ImportedMapEntry, in vm: MapViewModel,
                         file: StaticString = #filePath, line: UInt = #line) throws -> PDFMapSource {
        vm.restoreActiveMapSelection()
        XCTAssertTrue(vm.addImportedEntry(entry, activate: true), file: file, line: line)
        if let url = vm.fileURL(entry) { InFlightImportFiles.unregister(url) }
        return try XCTUnwrap(vm.mapSource as? PDFMapSource, file: file, line: line)
    }

    private func record(for entry: ImportedMapEntry, name: String, tilePx: Int = 512) throws -> PDFBakeRecord {
        let g = try XCTUnwrap(entry.pdf?.effectiveGeoref)
        return PDFBakeRecord(fileName: name, bakeKey: PDFRenderContext.bakeKey(georef: g, tilePx: tilePx),
                             minZoom: 0, maxZoom: 14, tilePx: tilePx, bytes: 1)
    }

    private func bakeName() -> String { "tacmap-bake-\(UUID().uuidString).mbtiles" }

    private func files(in dir: URL) -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).filter { !$0.hasPrefix(".") }.sorted()
    }

    private func makeMinimalMBTiles(at url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        var db: OpaquePointer?
        guard sqlite3_open(url.path, &db) == SQLITE_OK else { throw CocoaError(.fileWriteUnknown) }
        defer { sqlite3_close(db) }
        let sql = """
        CREATE TABLE metadata (name TEXT, value TEXT);
        CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);
        INSERT INTO metadata VALUES ('name','TacMap offline tiles'),('format','png');
        INSERT INTO tiles VALUES (0, 0, 0, X'89504E47');
        """
        guard sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK else { throw CocoaError(.fileWriteUnknown) }
    }

    private func waitUntil(_ what: String, timeout: Double = 20, _ cond: () -> Bool) {
        let end = Date().addingTimeInterval(timeout)
        while !cond(), Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
        XCTAssertTrue(cond(), "timed out waiting for \(what)")
    }

    private func storedEntry(_ id: UUID) -> ImportedMapEntry? {
        guard case .loaded(let s) = ImportedMapLibrary.load() else { return nil }
        return s.entry(id)
    }

    private func assertNothingLeft(_ what: String, file: StaticString = #filePath, line: UInt = #line) throws {
        XCTAssertEqual(files(in: try ImportedMapStorage.importedMapsDirectory()), [], "\(what): ImportedMaps",
                       file: file, line: line)
        XCTAssertEqual(files(in: tilesDir), [], "\(what): offline_tiles", file: file, line: line)
        guard case .loaded(let s) = ImportedMapLibrary.load() else {
            return XCTFail("\(what): the library should still read", file: file, line: line)
        }
        XCTAssertEqual(s.entries, [], "\(what): a library entry is still there", file: file, line: line)
    }

    // MARK: - OD-F4

    /// a truncated, swapped or missing stored PDF keeps the selection. The
    /// restore publishes it flagged, the renderer fails it as cannotOpen (G1,
    /// Try Again), never the "locked or unreadable" store error, and once the
    /// exact bytes are back Try Again draws it and the row stops saying unavailable
    func testUnreadableStoredPDFKeepsTheSelectionAndRecovers() throws {
        let entry = try importedEntry()
        _ = try showing(entry, in: viewModel())
        let file = try XCTUnwrap(ImportedMapLibrary.fileURL(entry))
        let original = try Data(contentsOf: file)

        for damage in ["truncated", "swapped", "missing"] {
            switch damage {
            case "truncated": try original.prefix(original.count / 3).write(to: file)
            case "swapped":
                try Data(contentsOf: try XCTUnwrap(F.testdataURL("geopdf/tacmap_render_blank.pdf"))).write(to: file)
            default: try FileManager.default.removeItem(at: file)
            }
            let vm = viewModel()
            XCTAssertEqual(vm.restoreActiveMapSelection(), .restored, damage)
            let shown = try XCTUnwrap(vm.mapSource as? PDFMapSource, "\(damage): the PDF stays the active map")
            XCTAssertTrue(shown.storedFileUnavailable, damage)
            XCTAssertEqual(shown.entryID, entry.id, damage)
            XCTAssertEqual(shown.renderGuardToken, entry.renderGuardToken, damage)
            XCTAssertEqual(shown.contentKey, entry.contentKey, damage)
            XCTAssertEqual(shown.georef, entry.pdf?.embedded, damage)
            XCTAssertNil(vm.mapSelectionPersistenceIssue, "\(damage): not a store failure")
            XCTAssertEqual(vm.library?.activeEntryID, entry.id, "\(damage): the selection is untouched")

            // G1 failed state, then the file comes back and Try Again draws it
            let runtime = vm.pdfRuntime
            _ = runtime.tileSource(for: shown, screenScale: 2)
            waitUntil("\(damage) cannotOpen") { runtime.status.failure != nil }
            XCTAssertEqual(runtime.status, .failed(.cannotOpen), damage)
            runtime.retry()
            waitUntil("\(damage) still cannotOpen") { runtime.status.failure != nil }
            XCTAssertEqual(runtime.status, .failed(.cannotOpen), "\(damage): retry before the file is back")
            try original.write(to: file)
            runtime.retry()
            waitUntil("\(damage) ready") { runtime.status == .ready }
            XCTAssertFalse(shown.storedFileUnavailable, damage)
            // the bytes checked out: the entry takes the new size + mtime, the row is fine again
            waitUntil("\(damage) row") { vm.library?.entry(entry.id).map { vm.fileStatus($0) == .ok } == true }
            runtime.reset()
        }
    }

    /// WP4 restore step 4 meets OD-F4: a file swapped in place with the same
    /// size + mtime is drawn until the background hash says otherwise, then the
    /// PDF fails as cannotOpen (the selection stays), it doesnt go online
    func testBackgroundHashMismatchFailsTheLiveSheetInsteadOfGoingOnline() throws {
        let entry = try importedEntry()
        _ = try showing(entry, in: viewModel())
        var d = deps()
        var verdict: ((Bool) -> Void)?
        d.verify = { _, _, done in verdict = done }
        let vm = MapViewModel(libraryDependencies: d, initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        vm.pdfRenderGuard = PDFRenderGuard(url: root.appendingPathComponent("guard-bg.json"))
        vm.restoreActiveMapSelection()
        let shown = try XCTUnwrap(vm.mapSource as? PDFMapSource)
        _ = vm.pdfRuntime.tileSource(for: shown, screenScale: 2)
        waitUntil("ready") { vm.pdfRuntime.status == .ready }
        // swap the bytes, keep the stamp
        let file = try XCTUnwrap(ImportedMapLibrary.fileURL(entry))
        let stamp = try FileManager.default.attributesOfItem(atPath: file.path)[.modificationDate]
        var swapped = try Data(contentsOf: file)
        swapped[swapped.count / 2] ^= 0xFF
        try swapped.write(to: file, options: .atomic)
        if let stamp { try FileManager.default.setAttributes([.modificationDate: stamp], ofItemAtPath: file.path) }
        try XCTUnwrap(verdict)(false)
        XCTAssertTrue(vm.mapSource is PDFMapSource, "the selection stays")
        XCTAssertEqual(vm.library?.activeEntryID, entry.id)
        XCTAssertEqual(vm.fileStatus(entry), .sizeOrMtimeMismatch, "the row says unavailable")
        waitUntil("cannotOpen") { vm.pdfRuntime.status.failure != nil }
        XCTAssertEqual(vm.pdfRuntime.status, .failed(.cannotOpen))
        vm.pdfRuntime.reset()
    }

    // MARK: - the bake rides with its PDF entry

    func testReconcileKeepsThePDFsBakeAndDeleteReapsBoth() throws {
        let entry = try importedEntry()
        let vm = viewModel()
        let pdf = try showing(entry, in: vm)
        let bakeFile = tilesDir.appendingPathComponent(bakeName())
        let orphan = tilesDir.appendingPathComponent(bakeName())
        try makeMinimalMBTiles(at: bakeFile)
        try makeMinimalMBTiles(at: orphan)
        let rec = try record(for: entry, name: bakeFile.lastPathComponent)
        XCTAssertEqual(vm.attachBake(rec, for: pdf), .attached)
        XCTAssertTrue(FileManager.default.fileExists(atPath: bakeFile.path), "the PDF's bake is kept")
        XCTAssertFalse(FileManager.default.fileExists(atPath: orphan.path), "unreferenced bakes are reaped")

        // still kept with the PDF parked behind an online map
        XCTAssertTrue(vm.selectOnlineBasemap(.osmTopo))
        XCTAssertTrue(FileManager.default.fileExists(atPath: bakeFile.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: pdf.url.path))

        // the record survives the sealed round trip, token too
        let stored = try XCTUnwrap(storedEntry(entry.id))
        XCTAssertEqual(stored.pdf?.bake, rec)
        XCTAssertEqual(stored.renderGuardToken, entry.renderGuardToken)
        let again = viewModel()
        again.restoreActiveMapSelection()
        XCTAssertEqual((again.source(for: stored) as? PDFMapSource)?.bake, rec, "a restored source draws from it")

        XCTAssertTrue(vm.deleteLibraryEntry(entry.id))
        XCTAssertFalse(FileManager.default.fileExists(atPath: pdf.url.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: bakeFile.path), "deleting the map deletes its bake")
    }

    /// Remove clears the record (one library write) and the reconcile after
    /// that write reaps the file, even when Remove itself looked elsewhere
    func testDroppingTheBakeRecordLetsReconcileReapTheFile() throws {
        let entry = try importedEntry()
        let vm = viewModel()
        let pdf = try showing(entry, in: vm)
        let bakeFile = tilesDir.appendingPathComponent(bakeName())
        try makeMinimalMBTiles(at: bakeFile)
        let rec = try record(for: entry, name: bakeFile.lastPathComponent)
        XCTAssertEqual(vm.attachBake(rec, for: pdf), .attached)
        pdf.bake = rec
        let controller = PDFBakeController()
        vm.bindBakeController(controller)
        // Remove (and its R3-2 sweep) works on an empty scratch dir, so only the
        // reconcile can be what reaps the file here
        let scratch = root.appendingPathComponent("remove-scratch", isDirectory: true)
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
        controller.finalDirectory = scratch
        XCTAssertTrue(controller.removeBake(from: pdf))
        XCTAssertNil(pdf.bake)
        XCTAssertNil(storedEntry(entry.id)?.pdf?.bake, "the sealed record is gone")
        XCTAssertFalse(FileManager.default.fileExists(atPath: bakeFile.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: pdf.url.path))
    }

    /// a georef change drops the bake (WP2 J), the reconcile then reaps the file
    func testRecalibrationDropsTheBakeAndReapsItsFile() throws {
        let entry = try importedEntry()
        let vm = viewModel()
        let pdf = try showing(entry, in: vm)
        let bakeFile = tilesDir.appendingPathComponent(bakeName())
        try makeMinimalMBTiles(at: bakeFile)
        XCTAssertEqual(vm.attachBake(try record(for: entry, name: bakeFile.lastPathComponent), for: pdf), .attached)
        XCTAssertTrue(vm.commitCalibration(entryID: entry.id, manual: try shiftedManual(entry),
                                           contentKey: try XCTUnwrap(entry.contentKey), pageIndex: 0))
        XCTAssertNil(storedEntry(entry.id)?.pdf?.bake)
        XCTAssertNil((vm.mapSource as? PDFMapSource)?.bake)
        XCTAssertFalse(FileManager.default.fileExists(atPath: bakeFile.path))
    }

    func testBakeRecordValidation() throws {
        let ok = PDFBakeRecord(fileName: "tacmap-bake-1.mbtiles", bakeKey: String(repeating: "f", count: 64),
                               minZoom: 0, maxZoom: 16, tilePx: 768, bytes: 1)
        XCTAssertTrue(PDFSessionStore.validBake(ok))
        XCTAssertTrue(ok.isValidRecord)
        var bad = ok; bad.fileName = "../escape.mbtiles"
        XCTAssertFalse(bad.isValidRecord)
        bad = ok; bad.fileName = "x.pdf"
        XCTAssertFalse(bad.isValidRecord)
        bad = ok; bad.bakeKey = "zz"
        XCTAssertFalse(bad.isValidRecord)
        bad = ok; bad.maxZoom = 40
        XCTAssertFalse(bad.isValidRecord)

        // the reducer only attaches what we'd have written, under our prefix
        let entry = try importedEntry()
        var s = LibraryState()
        s.entries = [entry]
        var foreign = try record(for: entry, name: "training-area.mbtiles")
        XCTAssertThrowsError(try LibraryReducer.apply(.attachBake(entry.id, foreign, contentKey: entry.contentKey,
                                                                  renderGuardToken: entry.renderGuardToken), to: s)) {
            XCTAssertEqual($0 as? LibraryTransitionError, .invalidBake)
        }
        foreign.fileName = "tacmap-bake-x.mbtiles"
        foreign.bytes = -1
        XCTAssertThrowsError(try LibraryReducer.apply(.attachBake(entry.id, foreign, contentKey: entry.contentKey,
                                                                  renderGuardToken: entry.renderGuardToken), to: s)) {
            XCTAssertEqual($0 as? LibraryTransitionError, .invalidBake)
        }
    }

    // MARK: - S1: attach only onto the map the bake was made from

    func testAttachRefusesAnyOtherMap() throws {
        let entry = try importedEntry()
        var s = LibraryState()
        s.entries = [entry]
        let rec = try record(for: entry, name: bakeName())
        func attach(_ id: UUID, key: String?, token: String, _ r: PDFBakeRecord, to state: LibraryState) throws -> LibraryState {
            try LibraryReducer.apply(.attachBake(id, r, contentKey: key, renderGuardToken: token), to: state).state
        }
        func refused(_ what: String, _ body: () throws -> LibraryState) {
            XCTAssertThrowsError(try body(), what) { XCTAssertEqual($0 as? LibraryTransitionError, .bakeSourceChanged, what) }
        }
        let ok = try attach(entry.id, key: entry.contentKey, token: entry.renderGuardToken, rec, to: s)
        XCTAssertEqual(ok.entry(entry.id)?.pdf?.bake, rec)
        XCTAssertEqual(ok.active, s.active, "the active map never changes")
        refused("unknown entry") { try attach(UUID(), key: entry.contentKey, token: entry.renderGuardToken, rec, to: s) }
        refused("other bytes") { try attach(entry.id, key: "sha256:" + String(repeating: "0", count: 64),
                                            token: entry.renderGuardToken, rec, to: s) }
        refused("other token") { try attach(entry.id, key: entry.contentKey, token: UUID().uuidString, rec, to: s) }
        refused("tile size the key wasnt made for") {
            var r = rec
            r.tilePx = 768
            return try attach(entry.id, key: entry.contentKey, token: entry.renderGuardToken, r, to: s)
        }
        var recal = s
        recal.entries[0].pdf?.manual = try shiftedManual(entry)
        refused("recalibrated meanwhile") { try attach(entry.id, key: entry.contentKey, token: entry.renderGuardToken, rec, to: recal) }

        // the view model: the same bytes deleted and imported again is a new map
        let vm = viewModel()
        let pdf = try showing(entry, in: vm)
        XCTAssertTrue(vm.deleteLibraryEntry(entry.id))
        let fresh = try importedEntry()
        XCTAssertEqual(fresh.contentKey, entry.contentKey)
        XCTAssertTrue(vm.addImportedEntry(fresh, activate: true))
        XCTAssertEqual(vm.attachBake(rec, for: pdf), .sourceChanged)
        XCTAssertNil(vm.library?.entry(fresh.id)?.pdf?.bake)
        // and a library that cant be written is a write problem, not another map
        failWrites = true
        let shown = try XCTUnwrap(vm.mapSource as? PDFMapSource)
        XCTAssertEqual(vm.attachBake(try record(for: fresh, name: bakeName()), for: shown), .writeFailed)
    }

    // MARK: - Delete Map leaves nothing, failures are loud

    func testDeleteMapLeavesNothingBehindForThatMap() throws {
        let entry = try importedEntry()
        let vm = viewModel()
        let pdf = try showing(entry, in: vm)
        let name = bakeName()
        try makeMinimalMBTiles(at: tilesDir.appendingPathComponent(name))
        try Data([1]).write(to: tilesDir.appendingPathComponent(name + "-wal"))
        XCTAssertEqual(vm.attachBake(try record(for: entry, name: name), for: pdf), .attached)

        XCTAssertTrue(vm.deleteLibraryEntry(entry.id))
        XCTAssertTrue(vm.mapSource is OnlineRasterBasemapSource)
        XCTAssertNil(vm.mapSelectionPersistenceIssue)
        try assertNothingLeft("no bake running")
        // and a relaunch has nothing to bring back
        let relaunched = viewModel()
        relaunched.restoreActiveMapSelection()
        XCTAssertFalse(relaunched.mapSource is PDFMapSource)
        try assertNothingLeft("after relaunch")
    }

    /// a real bake mid run: it is stopped (F7), its partial goes, nothing is published
    func testDeleteMapDuringABakeLeavesNothingBehind() throws {
        let entry = try importedEntry()
        let vm = viewModel()
        let pdf = try showing(entry, in: vm)
        let bake = PDFBakeController()
        bake.finalDirectory = tilesDir
        bake.guardStore = PDFRenderGuard(url: root.appendingPathComponent("bake-guard.json"))
        vm.bakeController = bake
        vm.bindBakeController(bake)
        defer { bake.cancel() }
        bake.prepare(pdf: pdf, runtime: nil)
        waitUntil("estimate", timeout: 60) { bake.state != .estimating }
        guard case .confirming(let p) = bake.state, let top = p.options.last else {
            return XCTFail("estimate ended in \(bake.state)")
        }
        let partialsBefore = Set(files(in: PDFBakeWorker.workDirectory))
        bake.start(maxZoom: top.maxZoom)
        XCTAssertTrue(bake.isRunning)
        waitUntil("partial", timeout: 30) { Set(self.files(in: PDFBakeWorker.workDirectory)) != partialsBefore }

        XCTAssertTrue(vm.deleteLibraryEntry(entry.id))
        XCTAssertFalse(bake.isRunning)
        XCTAssertNil(vm.mapSelectionPersistenceIssue)
        waitUntil("partial gone", timeout: 30) {
            Set(self.files(in: PDFBakeWorker.workDirectory)).isSubset(of: partialsBefore)
        }
        RunLoop.main.run(until: Date().addingTimeInterval(0.5))
        try assertNothingLeft("bake running")
        XCTAssertNil(pdf.bake)
    }

    /// WP2's multi store rollback is one library write now: a failed write
    /// deletes nothing and says so (Retry), Retry finishes the job
    func testFailedDeleteChangesNothingAndRetryFinishesIt() throws {
        let entry = try importedEntry()
        let vm = viewModel()
        let pdf = try showing(entry, in: vm)
        let name = bakeName()
        try makeMinimalMBTiles(at: tilesDir.appendingPathComponent(name))
        XCTAssertEqual(vm.attachBake(try record(for: entry, name: name), for: pdf), .attached)
        XCTAssertTrue(vm.selectOnlineBasemap(.osmTopo))

        failWrites = true
        XCTAssertFalse(vm.deleteLibraryEntry(entry.id))
        XCTAssertNotNil(vm.mapSelectionPersistenceIssue, "a failed delete is reported")
        XCTAssertTrue(FileManager.default.fileExists(atPath: pdf.url.path), "nothing deleted on the way out")
        XCTAssertTrue(FileManager.default.fileExists(atPath: tilesDir.appendingPathComponent(name).path))
        XCTAssertNotNil(storedEntry(entry.id), "the entry is kept for Retry")
        XCTAssertNotNil(vm.library?.entry(entry.id))

        // still failing: Retry fails the same way, again loudly
        XCTAssertFalse(vm.retryMapSelectionPersistence())
        XCTAssertNotNil(vm.mapSelectionPersistenceIssue)
        XCTAssertTrue(FileManager.default.fileExists(atPath: pdf.url.path))

        failWrites = false
        XCTAssertTrue(vm.retryMapSelectionPersistence())
        XCTAssertNil(vm.mapSelectionPersistenceIssue)
        try assertNothingLeft("after retry")
    }

    // MARK: - R3-2 bake only sweep

    /// the restore sweeps bakes nobody names, even with the PDF missing.
    /// Locked library: no sweep
    func testLaunchSweepsUnreferencedBakesOnlyOnAnAuthoritativeLibrary() throws {
        let entry = try importedEntry()
        let vm0 = viewModel()
        let pdf = try showing(entry, in: vm0)
        let named = bakeName(), orphan = bakeName()
        for n in [named, orphan, orphan + "-shm"] { try Data([1]).write(to: tilesDir.appendingPathComponent(n)) }
        XCTAssertEqual(vm0.attachBake(try record(for: entry, name: named), for: pdf), .attached)
        // the attach's reconcile only reaps .mbtiles it knows the shape of, put the orphan back to be sure
        for n in [orphan, orphan + "-shm"] where !FileManager.default.fileExists(atPath: tilesDir.appendingPathComponent(n).path) {
            try Data([1]).write(to: tilesDir.appendingPathComponent(n))
        }
        try FileManager.default.removeItem(at: pdf.url)

        SafeStore.keyProvider = { throw CocoaError(.fileReadNoPermission) }
        let locked = viewModel()
        XCTAssertEqual(locked.restoreActiveMapSelection(), .locked)
        XCTAssertEqual(files(in: tilesDir), [named, orphan, orphan + "-shm"].sorted(), "locked = untouched")

        SafeStore.keyProvider = { [testKey] in testKey }
        let vm = viewModel()
        XCTAssertEqual(vm.restoreActiveMapSelection(), .restored)
        let restored = try XCTUnwrap(vm.mapSource as? PDFMapSource)
        XCTAssertTrue(restored.storedFileUnavailable)
        XCTAssertEqual(files(in: tilesDir), [named], "only the orphan and its sidecar go")
    }

    /// what the sweep trusts: a Loaded library's names, an empty library with no
    /// legacy stores (names nothing). Never a locked or corrupt one, never while
    /// the migration still has legacy maps to move
    func testSweepSkipsWhileTheLibraryCantBeTrusted() throws {
        let names: Set<String> = ["tacmap-bake-a.mbtiles"]
        var s = LibraryState()
        s.entries = []
        XCTAssertEqual(ImportedMapLibrary.bakeAuthority(.loaded(s), legacyPresent: false), .read([]))
        XCTAssertEqual(ImportedMapLibrary.bakeAuthority(.empty, legacyPresent: false), .read([]))
        XCTAssertEqual(ImportedMapLibrary.bakeAuthority(.empty, legacyPresent: true), .unreadable)
        XCTAssertEqual(ImportedMapLibrary.bakeAuthority(.empty, legacyPresent: false, managedFiles: true), .unreadable)
        XCTAssertEqual(ImportedMapLibrary.bakeAuthority(.locked(CocoaError(.fileReadNoPermission)), legacyPresent: false),
                       .unreadable)
        XCTAssertEqual(ImportedMapLibrary.bakeAuthority(.corrupt(quarantinedTo: nil, error: CocoaError(.fileReadCorruptFile)),
                                                        legacyPresent: false), .unreadable)
        XCTAssertFalse(names.isEmpty)

        try FileManager.default.createDirectory(at: tilesDir, withIntermediateDirectories: true)
        let orphan = tilesDir.appendingPathComponent(bakeName())
        try Data([1]).write(to: orphan)
        // migration pending: nothing touched
        legacyPresent = true
        XCTAssertEqual(viewModel().restoreActiveMapSelection(), .locked)
        XCTAssertTrue(FileManager.default.fileExists(atPath: orphan.path))
        // corrupt library: nothing touched
        legacyPresent = false
        try Data("not sealed".utf8).write(to: ImportedMapLibrary.storageURLProvider())
        XCTAssertEqual(viewModel().restoreActiveMapSelection(), .corrupt)
        XCTAssertTrue(FileManager.default.fileExists(atPath: orphan.path))
        // S1: the second load after that quarantine is still corrupt, never empty.
        // (this used to read empty and sweep, the very bug)
        XCTAssertEqual(viewModel().restoreActiveMapSelection(), .corrupt)
        XCTAssertTrue(FileManager.default.fileExists(atPath: orphan.path))
        // never written but a bake is sitting there: 3.0.1 L7, that's a pending
        // adoption (the launch hop writes a flagged library), not a first launch
        let dir = ImportedMapLibrary.storageURLProvider().deletingLastPathComponent()
        for name in try FileManager.default.contentsOfDirectory(atPath: dir.path)
        where name.hasPrefix(ImportedMapLibrary.storageURLProvider().lastPathComponent) {
            try FileManager.default.removeItem(at: dir.appendingPathComponent(name))
        }
        XCTAssertEqual(viewModel().restoreActiveMapSelection(), .locked)
        XCTAssertTrue(FileManager.default.fileExists(atPath: orphan.path))
        // nothing anywhere: names nothing and has nothing to delete, a real first launch
        try FileManager.default.removeItem(at: orphan)
        XCTAssertEqual(viewModel().restoreActiveMapSelection(), .nothing)
    }

    /// the reconcile keep set and the sweep agree on an invalid record: neither
    /// keeps the file it names, and the source never draws from it
    func testInvalidBakeRecordIsIgnoredByRestoreReconcileAndSweep() throws {
        var entry = try importedEntry()
        let name = bakeName()
        let file = tilesDir.appendingPathComponent(name)
        try makeMinimalMBTiles(at: file)
        let bad = PDFBakeRecord(fileName: name, bakeKey: "zz", minZoom: 0, maxZoom: 14, tilePx: 512, bytes: 1)
        XCTAssertFalse(bad.isValidRecord)
        entry.pdf?.bake = bad
        var s = LibraryState()
        s.entries = [entry]
        s.active = .entry(entry.id)
        try ImportedMapLibrary.write(s)
        XCTAssertEqual(ImportedMapLibrary.bakeFileNames(s), [], "the sweep names nothing")
        let vm = viewModel()
        XCTAssertEqual(vm.restoreActiveMapSelection(), .restored)
        XCTAssertNil((vm.mapSource as? PDFMapSource)?.bake, "restore drops it")
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path), "the keep set didnt keep it either")
        XCTAssertTrue(FileManager.default.fileExists(atPath: try XCTUnwrap(ImportedMapLibrary.fileURL(entry)).path))
    }

    // MARK: - Try Again

    /// F5: two Try Agains overlap and the older one's check lands last. it must
    /// not overwrite what the newer (on screen) one found
    func testOverlappingTryAgainsKeepTheNewestFileVerdict() throws {
        let entry = try importedEntry()
        let vm = viewModel()
        let pdf = try showing(entry, in: vm)
        let runtime = PDFMapRuntime()
        _ = runtime.tileSource(for: pdf, screenScale: 2)
        waitUntil("ready") { runtime.status == .ready }

        let firstMayFinish = DispatchSemaphore(value: 0)
        let lock = NSLock()
        var calls = 0
        var firstDone = false
        runtime.fileCheck = { _ in
            lock.lock(); calls += 1; let n = calls; lock.unlock()
            guard n == 1 else { return true }
            // the first Try Again finds the file gone, but only after the second finished
            firstMayFinish.wait()
            lock.lock(); firstDone = true; lock.unlock()
            return false
        }
        runtime.retry()
        runtime.retry()
        waitUntil("second ready") { runtime.status == .ready && calls == 2 }
        XCTAssertFalse(pdf.storedFileUnavailable)
        firstMayFinish.signal()
        waitUntil("first check done") { lock.lock(); defer { lock.unlock() }; return firstDone }
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
        XCTAssertFalse(pdf.storedFileUnavailable, "the stale verdict was dropped")
        XCTAssertEqual(runtime.status, .ready)
        runtime.reset()
    }

    /// R3-3: Try Again re-checks the bytes even when nothing flagged the source
    func testTryAgainAlwaysRechecksTheStoredBytes() throws {
        let entry = try importedEntry()
        let vm = viewModel()
        let pdf = try showing(entry, in: vm)
        let original = try Data(contentsOf: pdf.url)
        XCTAssertFalse(pdf.storedFileUnavailable)
        let runtime = PDFMapRuntime()
        _ = runtime.tileSource(for: pdf, screenScale: 2)
        waitUntil("ready") { runtime.status == .ready }

        let blank = try XCTUnwrap(F.testdataURL("geopdf/tacmap_render_blank.pdf"))
        try Data(contentsOf: blank).write(to: pdf.url)
        runtime.retry()
        waitUntil("cannotOpen") { runtime.status.failure != nil }
        XCTAssertEqual(runtime.status, .failed(.cannotOpen), "never draws bytes that dont match the key")
        XCTAssertTrue(pdf.storedFileUnavailable, "flagged like a restore would")

        try original.write(to: pdf.url)
        runtime.retry()
        waitUntil("ready again") { runtime.status == .ready }
        XCTAssertFalse(pdf.storedFileUnavailable)
        runtime.reset()
    }

    /// F1: the alert host decisions
    func testMapIssueAlertGate() {
        typealias Gate = MapSelectionIssueAlertGate
        let a = MapSelectionPersistenceIssue(id: UUID(), pendingMessage: Messages.displayThePdfMapCouldNotBeSavedForRelaunchMessage())
        let b = MapSelectionPersistenceIssue(id: UUID(), pendingMessage: Messages.displayThePdfMapCouldNotBeSavedForRelaunchMessage())
        XCTAssertEqual(Gate.issueToShow(for: .init(issueID: a.id, isActive: true), current: a), a)
        XCTAssertNil(Gate.issueToShow(for: .init(issueID: a.id, isActive: false), current: a))
        XCTAssertNil(Gate.issueToShow(for: .init(issueID: a.id, isActive: true), current: b), "replaced meanwhile")
        XCTAssertNil(Gate.issueToShow(for: .init(issueID: a.id, isActive: true), current: nil), "dismissed meanwhile")
        XCTAssertNotEqual(Gate.Key(issueID: a.id, isActive: true), Gate.Key(issueID: a.id, isActive: false))
        XCTAssertNotEqual(Gate.Key(issueID: a.id, isActive: true), Gate.Key(issueID: b.id, isActive: true))
        XCTAssertTrue(Gate.rootHostIsActive(presentationsOnTop: [false, false]))
        XCTAssertFalse(Gate.rootHostIsActive(presentationsOnTop: [false, true, false]))
        XCTAssertFalse(Gate.clearsIssue(.dismissedBySystem))
        XCTAssertTrue(Gate.clearsIssue(.notNow))
    }

    // MARK: - M12: the WP2 import probe runs before the library write

    func testImportProbeFailureCommitsNothing() throws {
        let vm = viewModel()
        vm.restoreActiveMapSelection()
        let controller = MapImportController()
        controller.mapVM = vm
        var probedToken: String?
        controller.probe = { _, _, _, _, _, token in
            probedToken = token
            throw MapImportError.cannotDraw(.blank)
        }
        var done = false
        controller.importPDF(url: try XCTUnwrap(F.testdataURL("geopdf/tacmap_grid_sf_iso.pdf"))) { done = true }
        waitUntil("import", timeout: 60) { done }
        XCTAssertNotNil(probedToken, "the probe ran")
        XCTAssertEqual(controller.error, .cannotDraw(.blank))
        XCTAssertEqual(vm.library?.entries.count, 0, "nothing committed")
        XCTAssertEqual(files(in: try ImportedMapStorage.importedMapsDirectory()), [], "the copy is gone")

        // and a probe that passes commits the entry with the token it armed
        controller.error = nil
        controller.probe = { url, key, page, geometry, georef, token in
            probedToken = token
            return try MapImportPipeline.probe(url: url, contentKey: key, pageIndex: page, page: geometry, georef: georef,
                                               token: token, guardStore: PDFRenderGuard(url: self.root.appendingPathComponent("probe.json")))
        }
        done = false
        controller.importPDF(url: try XCTUnwrap(F.testdataURL("geopdf/tacmap_grid_sf_iso.pdf"))) { done = true }
        waitUntil("import 2", timeout: 60) { done }
        XCTAssertNil(controller.error)
        let entry = try XCTUnwrap(vm.library?.entries.first)
        XCTAssertEqual(entry.renderGuardToken, probedToken.flatMap { UUID(uuidString: $0)?.uuidString })
        XCTAssertEqual(vm.activeEntryID, entry.id)
    }

    // MARK: - S1 publish through the real library (was PDFBakePublishTests)

    private func boundController(_ vm: MapViewModel) -> PDFBakeController {
        let c = PDFBakeController()
        c.finalDirectory = tilesDir
        c.guardStore = PDFRenderGuard(url: root.appendingPathComponent("bake-guard-\(UUID().uuidString).json"))
        vm.bakeController = c
        vm.bindBakeController(c)
        return c
    }

    private func confirm(_ c: PDFBakeController, _ pdf: PDFMapSource) throws -> PDFBakeProposal {
        c.prepare(pdf: pdf, runtime: nil)
        waitUntil("estimate", timeout: 60) { c.state != .estimating }
        guard case .confirming(let p) = c.state else {
            XCTFail("estimate ended in \(c.state)")
            throw PDFBakeTests.EstimateDidntConfirm()
        }
        return p
    }

    /// a hand calibration whose georef isn't the embedded one
    private func shiftedManual(_ entry: ImportedMapEntry) throws -> ManualCalibration {
        var g = try XCTUnwrap(entry.pdf?.embedded)
        let a = g.affine
        g.affine = PlaneAffine(a: a.a, b: a.b, c: a.c + 25, d: a.d, e: a.e, f: a.f - 25)
        g.origin = .fiduciaries
        return ManualCalibration(datumId: "WGS84", points: [], nextNumber: 1, georef: g, n: 4, rmsM: 1,
                                 grade: .good, savedAtMs: 0)
    }

    /// R3-6: Remove with the stored PDF missing clears the record in the sealed
    /// library itself, the files go, and the library still reads (so the sweep could run)
    func testRemoveWithThePdfMissingClearsTheLibrarysBake() throws {
        let entry = try importedEntry()
        let vm0 = viewModel()
        let pdf0 = try showing(entry, in: vm0)
        let name = bakeName(), orphan = bakeName()
        for n in [name, name + "-wal"] { try Data([1]).write(to: tilesDir.appendingPathComponent(n)) }
        XCTAssertEqual(vm0.attachBake(try record(for: entry, name: name), for: pdf0), .attached)
        try Data([1]).write(to: tilesDir.appendingPathComponent(orphan))
        try FileManager.default.removeItem(at: pdf0.url)

        let vm = viewModel()
        let c = boundController(vm)
        vm.restoreActiveMapSelection()
        let restored = try XCTUnwrap(vm.mapSource as? PDFMapSource)
        XCTAssertTrue(restored.storedFileUnavailable)
        XCTAssertEqual(restored.bake?.fileName, name)

        XCTAssertTrue(c.removeBake(from: restored))
        XCTAssertNil(restored.bake)
        let after = try XCTUnwrap(storedEntry(entry.id), "the entry itself is kept (OD-F4)")
        XCTAssertNil(after.pdf?.bake, "the sealed record has no bake any more")
        XCTAssertEqual(after.renderGuardToken, entry.renderGuardToken)
        XCTAssertEqual(vm.bakeNamesForSweep(), [])
        XCTAssertEqual(files(in: tilesDir), [], "bake, sidecar and the unnamed orphan all gone")
    }

    func testBakeLandsOnTheLibraryEntryAndOnlyTouchesTheBakeField() throws {
        let entry = try importedEntry()
        let vm = viewModel()
        let pdf = try showing(entry, in: vm)
        let c = boundController(vm)
        let p = try confirm(c, pdf)
        c.start(maxZoom: try XCTUnwrap(p.options.first).maxZoom)
        waitUntil("bake", timeout: 120) { !c.isRunning }
        XCTAssertEqual(c.state, .idle)
        let rec = try XCTUnwrap(pdf.bake)
        XCTAssertTrue(ManagedImportedMapFileLifecycle.isGeneratedBakeName(rec.fileName))
        let stored = try XCTUnwrap(storedEntry(entry.id))
        XCTAssertEqual(stored.pdf?.bake, rec)
        XCTAssertEqual(stored.pdf?.embedded, entry.pdf?.embedded)
        XCTAssertNil(stored.pdf?.manual)
        XCTAssertEqual(stored.renderGuardToken, entry.renderGuardToken)
        XCTAssertEqual(stored.byteCount, entry.byteCount)
        XCTAssertEqual(vm.library?.activeEntryID, entry.id, "the active map never changes")
        XCTAssertTrue(FileManager.default.fileExists(atPath: tilesDir.appendingPathComponent(rec.fileName).path))
    }

    func testRecalibrationDuringTheBakeIsNotReverted() throws {
        let entry = try importedEntry()
        let vm = viewModel()
        let pdf = try showing(entry, in: vm)
        let c = boundController(vm)
        let p = try confirm(c, pdf)
        c.start(maxZoom: try XCTUnwrap(p.options.first).maxZoom)
        XCTAssertTrue(c.isRunning)
        // the user finishes a calibration mid bake
        let manual = try shiftedManual(entry)
        XCTAssertTrue(vm.commitCalibration(entryID: entry.id, manual: manual,
                                           contentKey: try XCTUnwrap(entry.contentKey), pageIndex: 0))
        waitUntil("bake", timeout: 120) { !c.isRunning }
        XCTAssertEqual(c.state, .failed(.sourceChanged))
        let stored = try XCTUnwrap(storedEntry(entry.id))
        XCTAssertEqual(stored.pdf?.manual, manual, "the newer calibration survives")
        XCTAssertNil(stored.pdf?.bake)
        XCTAssertEqual(files(in: tilesDir), [], "nothing published")
    }

    /// R2-S4: a flagged source estimates once the file at its stored path
    /// hashes to the stored key again, and never off swapped bytes
    func testFlaggedSourceEstimatesOnlyOffTheStoredBytes() throws {
        let entry = try importedEntry()
        let vm = viewModel()
        let pdf = try showing(entry, in: vm)
        let c = boundController(vm)
        pdf.storedFileUnavailable = true
        _ = try confirm(c, pdf)
        c.dismiss()
        let other = try XCTUnwrap(F.testdataURL("geopdf/tacmap_grid_sf_plain.pdf"))
        try FileManager.default.removeItem(at: pdf.url)
        try FileManager.default.copyItem(at: other, to: pdf.url)
        c.prepare(pdf: pdf, runtime: nil)
        waitUntil("estimate", timeout: 60) { c.state != .estimating }
        XCTAssertEqual(c.state, .failed(.renderFailed))
        c.dismiss()
        try FileManager.default.removeItem(at: pdf.url)
        c.prepare(pdf: pdf, runtime: nil)
        waitUntil("estimate", timeout: 60) { c.state != .estimating }
        XCTAssertEqual(c.state, .failed(.renderFailed))
    }
}
