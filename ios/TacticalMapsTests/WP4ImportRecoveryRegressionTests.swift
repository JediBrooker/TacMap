import XCTest
import Combine
@testable import TacticalMaps

final class WP4ImportRecoveryRegressionTests: XCTestCase {
    private let key = Data((0..<32).map { UInt8(truncatingIfNeeded: $0 &* 11 &+ 5) })
    private var root: URL!
    private var savedSupport: (() -> URL)!
    private var savedLibraryURL: (() -> URL)!
    private var savedDocumentOpened: (() -> Void)?
    private var failWrites = false
    private var writeAttempts = 0

    override func setUp() {
        super.setUp()
        savedSupport = ImportedMapStorage.applicationSupportProvider
        savedLibraryURL = ImportedMapLibrary.storageURLProvider
        savedDocumentOpened = PDFInspector.documentOpened
        root = FileManager.default.temporaryDirectory.appendingPathComponent("wp4-import-\(UUID().uuidString)")
        let directory = root!
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        ImportedMapStorage.applicationSupportProvider = { directory }
        ImportedMapLibrary.storageURLProvider = { directory.appendingPathComponent("imported-map-library.json") }
        SafeStore.keyProvider = { [key] in key }
        SealedMigrationPolicy.resetForTests(key: key)
        failWrites = false
        writeAttempts = 0
    }

    override func tearDown() {
        for url in ownedCopies { InFlightImportFiles.unregister(url) }
        ImportedMapStorage.applicationSupportProvider = savedSupport
        ImportedMapLibrary.storageURLProvider = savedLibraryURL
        PDFInspector.documentOpened = savedDocumentOpened
        SafeStore.keyProvider = { try DataKey.key() }
        SealedMigrationPolicy.resetForTests(key: key)
        try? FileManager.default.removeItem(at: root)
        super.tearDown()
    }

    private var ownedCopies: Set<URL> {
        Set(InFlightImportFiles.snapshot.filter { $0.path.hasPrefix(root.path + "/") })
    }

    private func viewModel() -> MapViewModel {
        var dependencies = LibraryDependencies.live
        dependencies.drafts = InMemoryCalibrationDraftStore()
        dependencies.legacyPresent = { false }
        dependencies.clearLegacy = {}
        dependencies.verify = { _, _, completion in completion(true) }
        dependencies.sweepBackup = {}
        dependencies.recoverInterruptedImport = { false }
        let writer = dependencies.write
        dependencies.write = { [unowned self] state in
            self.writeAttempts += 1
            if self.failWrites { throw CocoaError(.fileWriteOutOfSpace) }
            try writer(state)
        }
        let vm = MapViewModel(libraryDependencies: dependencies, initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        vm.pdfRenderGuard = PDFRenderGuard(url: root.appendingPathComponent("runtime-guard.json"))
        vm.restoreActiveMapSelection()
        return vm
    }

    private func fixture(_ name: String = "tacmap_grid_sf_iso.pdf") throws -> URL {
        try XCTUnwrap(PDFTileRenderFixtureTests.testdataURL("geopdf/" + name))
    }

    private func importedFiles() throws -> [URL] {
        try FileManager.default.contentsOfDirectory(at: ImportedMapStorage.importedMapsDirectory(), includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "pdf" }
    }

    private func installRealProbe(on controller: MapImportController) {
        let guardURL = root.appendingPathComponent("probe-guard.json")
        controller.probe = { url, key, index, geometry, georef, token in
            try MapImportPipeline.probe(url: url, contentKey: key, pageIndex: index, page: geometry,
                                        georef: georef, token: token, guardStore: PDFRenderGuard(url: guardURL))
        }
    }

    func testDebugCalibrationCameraPointRejectsMalformedInput() {
        #if DEBUG
        let point = DebugHooks.parseCalibrationPoint("254.1299411,194.8365854")
        XCTAssertEqual(point?.x, 254.1299411)
        XCTAssertEqual(point?.y, 194.8365854)
        for raw in ["", "1", "1,2,3", "nan,2", "1,inf", "junk,2"] {
            XCTAssertNil(DebugHooks.parseCalibrationPoint(raw))
        }
        #endif
    }

    @MainActor
    func testCancelAfterInspectionAndSuccessfulProbePreventsTheLibraryCommit() async throws {
        let vm = viewModel()
        let controller = MapImportController()
        controller.mapVM = vm
        let probed = expectation(description: "real probe completed")
        let completed = expectation(description: "cancelled import completed")
        let release = DispatchSemaphore(value: 0)
        defer { release.signal() }
        let guardURL = root.appendingPathComponent("probe-guard.json")
        controller.probe = { url, key, index, geometry, georef, token in
            let raster = try MapImportPipeline.probe(url: url, contentKey: key, pageIndex: index, page: geometry,
                                                    georef: georef, token: token, guardStore: PDFRenderGuard(url: guardURL))
            probed.fulfill()
            guard release.wait(timeout: .now() + 15) == .success else { throw MapImportError.failed(detail: "probe gate timed out") }
            return raster
        }
        var toasts: [String] = []
        let subscription = controller.toasts.sink { toasts.append($0) }
        defer { subscription.cancel() }
        let before = writeAttempts
        controller.importPDF(url: try fixture()) { completed.fulfill() }
        await fulfillment(of: [probed], timeout: 15)
        XCTAssertTrue(controller.canCancel)
        XCTAssertEqual(ownedCopies.count, 1)
        controller.cancel()
        release.signal()
        await fulfillment(of: [completed], timeout: 15)

        XCTAssertEqual(writeAttempts, before, "Cancel after the worker result still wins before the durable write")
        XCTAssertEqual(vm.library?.entries.count, 0)
        XCTAssertNil(controller.error)
        XCTAssertEqual(toasts, [Messages.mapImportCancelled()])
        XCTAssertTrue(ownedCopies.isEmpty)
        XCTAssertTrue(try importedFiles().isEmpty)
    }

    @MainActor
    func testPagePickerCancelAfterProbeDoesNotCommitOrStartCalibration() async throws {
        let vm = viewModel()
        let controller = MapImportController()
        controller.mapVM = vm
        let prepared = try await MapImportPipeline.preparePDF(url: try fixture("tacmap_grid_rot5_plain.pdf"),
                                                              entryCount: 0, libraryLoaded: true,
                                                              isCancelled: { false }, progress: { _ in })
        controller.pagePicker = PagePickerRequest(prepared: prepared, badges: [])
        let probed = expectation(description: "picked page probe completed")
        let cancelled = expectation(description: "picked page cancelled")
        let release = DispatchSemaphore(value: 0)
        defer { release.signal() }
        let guardURL = root.appendingPathComponent("picker-probe-guard.json")
        controller.probe = { url, key, index, geometry, georef, token in
            let raster = try MapImportPipeline.probe(url: url, contentKey: key, pageIndex: index, page: geometry,
                                                    georef: georef, token: token, guardStore: PDFRenderGuard(url: guardURL))
            probed.fulfill()
            guard release.wait(timeout: .now() + 15) == .success else { throw MapImportError.failed(detail: "probe gate timed out") }
            return raster
        }
        var calibrationStarted = false
        controller.startCalibration = { _ in calibrationStarted = true }
        let subscription = controller.toasts.sink { message in
            if message == Messages.mapImportCancelled() { cancelled.fulfill() }
        }
        defer { subscription.cancel() }
        let before = writeAttempts
        controller.choosePage(0)
        await fulfillment(of: [probed], timeout: 15)
        XCTAssertTrue(controller.canCancel)
        controller.cancel()
        release.signal()
        await fulfillment(of: [cancelled], timeout: 15)
        XCTAssertFalse(calibrationStarted)
        XCTAssertEqual(writeAttempts, before)
        XCTAssertEqual(vm.library?.entries.count, 0)
        XCTAssertNil(controller.error)
        XCTAssertFalse(FileManager.default.fileExists(atPath: prepared.copy.url.path))
        XCTAssertFalse(ownedCopies.contains(prepared.copy.url))
    }

    @MainActor
    func testFailedImportWriteKeepsItsCopyThroughFailedRetryThenReleasesItAfterCommit() async throws {
        let vm = viewModel()
        let controller = MapImportController()
        controller.mapVM = vm
        installRealProbe(on: controller)
        failWrites = true
        let completed = expectation(description: "failed import completed")
        controller.importPDF(url: try fixture()) { completed.fulfill() }
        await fulfillment(of: [completed], timeout: 15)
        XCTAssertNotNil(vm.mapSelectionPersistenceIssue)
        XCTAssertEqual(vm.library?.entries.count, 0)
        let copy = try XCTUnwrap(ownedCopies.first)
        XCTAssertEqual(ownedCopies.count, 1)
        XCTAssertTrue(FileManager.default.fileExists(atPath: copy.path))
        XCTAssertTrue(ImportedMapLibrary.reconcile(try XCTUnwrap(vm.library)))
        XCTAssertTrue(FileManager.default.fileExists(atPath: copy.path), "a failed commit still owns its Retry copy")
        XCTAssertFalse(vm.retryMapSelectionPersistence())
        XCTAssertTrue(ownedCopies.contains(copy))
        XCTAssertTrue(FileManager.default.fileExists(atPath: copy.path))

        failWrites = false
        XCTAssertTrue(vm.retryMapSelectionPersistence())
        let entry = try XCTUnwrap(vm.library?.entries.first)
        XCTAssertEqual(ImportedMapLibrary.fileURL(entry), copy)
        XCTAssertEqual(vm.activeEntryID, entry.id)
        XCTAssertNil(vm.mapSelectionPersistenceIssue)
        XCTAssertFalse(ownedCopies.contains(copy))
        XCTAssertTrue(FileManager.default.fileExists(atPath: copy.path))
        guard case .loaded(let durable) = ImportedMapLibrary.load() else { return XCTFail("Retry must durably commit the entry") }
        XCTAssertEqual(durable.entries.map(\.id), [entry.id])
    }

    /// the relock gate DataKey runs, over the test key, counting Keychain reads
    private final class GatedKey {
        let cache = DataKeyCache()
        private let key: Data
        private(set) var reads = 0
        init(_ key: Data) { self.key = key }
        private func read() throws -> Data { reads += 1; return key }
        /// unlocked and wired to SafeStore like production
        func install() throws {
            try cache.unlock(read)
            SafeStore.keyProvider = { [self] in try cache.get(read) }
        }
        /// RootGate when the scene leaves active (auth-bound key)
        func relock() { cache.lock() }
        /// the Unlock button
        func unlock() throws { try cache.unlock(read) }
    }

    /// 3.0.3 DL-4: auth-bound key, the user left the app mid import so the key
    /// relocked, and the import's commit hit the gate. The unlock's own restore
    /// cleared its Retry, so the map never landed and nothing said so. Now it
    /// goes in right after that restore, and nothing read the key before it
    @MainActor
    func testAnImportCommitBehindTheRelockLandsAfterTheUnlock() async throws {
        let gate = GatedKey(key)
        try gate.install()
        try ImportedMapLibrary.write(LibraryState())
        let vm = viewModel()
        XCTAssertEqual(vm.libraryStatus, .loaded)
        let controller = MapImportController()
        controller.mapVM = vm
        installRealProbe(on: controller)
        let reads = gate.reads

        gate.relock()
        let completed = expectation(description: "import finished behind the lock")
        controller.importPDF(url: try fixture()) { completed.fulfill() }
        await fulfillment(of: [completed], timeout: 15)
        XCTAssertEqual(vm.library?.entries.count, 0, "the commit hit the gate")
        let copy = try XCTUnwrap(ownedCopies.first)
        XCTAssertTrue(FileManager.default.fileExists(atPath: copy.path))
        // a restore that still can't read the library leaves it waiting
        XCTAssertEqual(vm.restoreActiveMapSelection(), .locked)
        XCTAssertTrue(ownedCopies.contains(copy))
        XCTAssertEqual(gate.reads, reads, "nothing behind the lock read the key")

        // Unlock: DataKey.unlock(), then ContentView's restore
        try gate.unlock()
        XCTAssertEqual(vm.restoreActiveMapSelection(), .restored)
        let entry = try XCTUnwrap(vm.library?.entries.first, "the import landed after the unlock")
        XCTAssertEqual(ImportedMapLibrary.fileURL(entry), copy)
        XCTAssertEqual(vm.activeEntryID, entry.id)
        XCTAssertNil(vm.mapSelectionPersistenceIssue)
        XCTAssertFalse(ownedCopies.contains(copy), "the library owns the copy now")
        XCTAssertTrue(FileManager.default.fileExists(atPath: copy.path))
        guard case .loaded(let durable) = ImportedMapLibrary.load() else { return XCTFail("the commit must be durable") }
        XCTAssertEqual(durable.entries.map(\.id), [entry.id])
        // and only the once
        XCTAssertEqual(vm.restoreActiveMapSelection(), .restored)
        XCTAssertEqual(vm.library?.entries.map(\.id), [entry.id])
    }

    /// Not Now while a relocked commit waits (say the restore after the unlock
    /// came back locked): the copy goes and the commit with it, so no entry ever
    /// points at the deleted file
    @MainActor
    func testNotNowDropsAnImportCommitParkedBehindTheRelock() async throws {
        let gate = GatedKey(key)
        try gate.install()
        try ImportedMapLibrary.write(LibraryState())
        let vm = viewModel()
        let controller = MapImportController()
        controller.mapVM = vm
        installRealProbe(on: controller)
        gate.relock()
        let completed = expectation(description: "import finished behind the lock")
        controller.importPDF(url: try fixture()) { completed.fulfill() }
        await fulfillment(of: [completed], timeout: 15)
        let copy = try XCTUnwrap(ownedCopies.first)

        vm.dismissMapSelectionPersistenceIssue()
        XCTAssertFalse(FileManager.default.fileExists(atPath: copy.path))
        XCTAssertFalse(ownedCopies.contains(copy))
        try gate.unlock()
        let before = writeAttempts
        XCTAssertEqual(vm.restoreActiveMapSelection(), .restored)
        XCTAssertEqual(writeAttempts, before, "nothing left to commit")
        XCTAssertEqual(vm.library?.entries.count, 0)
    }

    @MainActor
    func testDismissingFailedImportUnlinksItsCopyAndInvalidatesRetry() async throws {
        let vm = viewModel()
        let controller = MapImportController()
        controller.mapVM = vm
        installRealProbe(on: controller)
        failWrites = true
        let completed = expectation(description: "failed import completed")
        controller.importPDF(url: try fixture()) { completed.fulfill() }
        await fulfillment(of: [completed], timeout: 15)
        XCTAssertNotNil(vm.mapSelectionPersistenceIssue)
        let copy = try XCTUnwrap(ownedCopies.first)
        XCTAssertTrue(FileManager.default.fileExists(atPath: copy.path))

        vm.dismissMapSelectionPersistenceIssue()
        XCTAssertNil(vm.mapSelectionPersistenceIssue)
        XCTAssertFalse(FileManager.default.fileExists(atPath: copy.path))
        XCTAssertFalse(ownedCopies.contains(copy))
        failWrites = false
        let before = writeAttempts
        XCTAssertFalse(vm.retryMapSelectionPersistence(), "dismissal must invalidate a Retry that would resurrect the deleted copy")
        XCTAssertEqual(writeAttempts, before)
        XCTAssertEqual(vm.library?.entries.count, 0)
    }
}
