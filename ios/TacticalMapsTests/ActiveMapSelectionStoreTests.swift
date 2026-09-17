import XCTest
import SQLite3
import CoreGraphics
import CoreLocation
@testable import TacticalMaps

final class ActiveMapSelectionStoreTests: XCTestCase {
    private let testKey = Data((0..<32).map { UInt8(255 - $0) })
    private var root: URL!
    private var originalApplicationSupportProvider: (() -> URL)!
    private var originalStorageProvider: (() -> URL)!
    private var originalImportedDirectoryProvider: (() throws -> URL)!
    private var originalPDFDefaultsProvider: (() -> UserDefaults)!
    private var originalPDFImportedDirectoryProvider: (() throws -> URL)!
    private var originalPDFLegacyDocumentsDirectoryProvider: (() -> URL?)!
    private var originalPDFRequiresSealedPolicy: ((String, Data) throws -> Bool)!
    private var originalPDFMarkSealedPolicy: ((String, Data) throws -> Void)!
    private var pdfDefaultsSuiteName: String!
    private var pdfFixtureURLs: [URL] = []
    private var legacyDocumentsDirectory: URL!

    override func setUp() {
        super.setUp()
        originalApplicationSupportProvider = ActiveMapSelectionStore.applicationSupportDirectoryProvider
        originalStorageProvider = ActiveMapSelectionStore.storageURLProvider
        originalImportedDirectoryProvider = ActiveMapSelectionStore.importedMapsDirectoryProvider
        originalPDFDefaultsProvider = PDFSessionStore.defaultsProvider
        originalPDFImportedDirectoryProvider = PDFSessionStore.importedMapsDirectoryProvider
        originalPDFLegacyDocumentsDirectoryProvider = PDFSessionStore.legacyDocumentsDirectoryProvider
        originalPDFRequiresSealedPolicy = PDFSessionStore.requiresSealedPolicy
        originalPDFMarkSealedPolicy = PDFSessionStore.markSealedPolicy
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("active-map-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let selection = root.appendingPathComponent("active-map-selection.json")
        let imported = root.appendingPathComponent("ImportedMaps", isDirectory: true)
        let legacyDocuments = root.appendingPathComponent("Documents", isDirectory: true)
        legacyDocumentsDirectory = legacyDocuments
        try? FileManager.default.createDirectory(at: imported, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(
            at: legacyDocuments,
            withIntermediateDirectories: true
        )
        let applicationSupport = root!
        ActiveMapSelectionStore.applicationSupportDirectoryProvider = { applicationSupport }
        ActiveMapSelectionStore.storageURLProvider = { selection }
        ActiveMapSelectionStore.importedMapsDirectoryProvider = { imported }
        pdfDefaultsSuiteName = "ActiveMapSelectionStoreTests.\(UUID().uuidString)"
        let pdfDefaults = UserDefaults(suiteName: pdfDefaultsSuiteName)!
        pdfDefaults.removePersistentDomain(forName: pdfDefaultsSuiteName)
        PDFSessionStore.defaultsProvider = { pdfDefaults }
        PDFSessionStore.importedMapsDirectoryProvider = { imported }
        PDFSessionStore.legacyDocumentsDirectoryProvider = { legacyDocuments }
        SafeStore.keyProvider = { [testKey] in testKey }
        SealedMigrationPolicy.resetForTests(key: testKey)
        PDFSessionStore.clear()
    }

    override func tearDown() {
        PDFSessionStore.clear()
        pdfFixtureURLs.forEach { try? FileManager.default.removeItem(at: $0) }
        pdfFixtureURLs = []
        PDFSessionStore.defaultsProvider().removePersistentDomain(forName: pdfDefaultsSuiteName)
        PDFSessionStore.defaultsProvider = originalPDFDefaultsProvider
        PDFSessionStore.importedMapsDirectoryProvider = originalPDFImportedDirectoryProvider
        PDFSessionStore.legacyDocumentsDirectoryProvider = originalPDFLegacyDocumentsDirectoryProvider
        PDFSessionStore.requiresSealedPolicy = originalPDFRequiresSealedPolicy
        PDFSessionStore.markSealedPolicy = originalPDFMarkSealedPolicy
        ActiveMapSelectionStore.applicationSupportDirectoryProvider = originalApplicationSupportProvider
        ActiveMapSelectionStore.storageURLProvider = originalStorageProvider
        ActiveMapSelectionStore.importedMapsDirectoryProvider = originalImportedDirectoryProvider
        SafeStore.keyProvider = { try DataKey.key() }
        SealedMigrationPolicy.resetForTests(key: testKey)
        try? FileManager.default.removeItem(at: root)
        super.tearDown()
    }

    func testOnlineStyleRoundTripsWithoutPlaintextPreference() throws {
        ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmTopo))

        let bytes = try Data(contentsOf: ActiveMapSelectionStore.storageURLProvider())
        XCTAssertTrue(SealedEnvelope.isSealedFile(bytes))
        XCTAssertFalse(String(decoding: bytes, as: UTF8.self).contains("osmTopo"))

        guard case .restored(let source) = ActiveMapSelectionStore.restore(),
              let online = source as? OnlineRasterBasemapSource else {
            return XCTFail("expected restored online basemap")
        }
        XCTAssertEqual(online.style, .osmTopo)
    }

    func testOfflineMBTilesRoundTripsAndMissingFileFallsBackSafely() throws {
        let directory = try ActiveMapSelectionStore.importedMapsDirectoryProvider()
        let file = directory.appendingPathComponent("training-area.mbtiles")
        try makeMinimalMBTiles(at: file)
        ActiveMapSelectionStore.save(try XCTUnwrap(OfflineTileMapSource(url: file)))
        do {
            guard case .restored(let source) = ActiveMapSelectionStore.restore(),
                  let offline = source as? OfflineTileMapSource else {
                return XCTFail("expected restored offline basemap")
            }
            XCTAssertEqual(offline.url.standardizedFileURL, file.standardizedFileURL)
        }

        try FileManager.default.removeItem(at: file)
        guard case .unavailable = ActiveMapSelectionStore.restore() else {
            return XCTFail("a vanished offline pack must not produce a broken map source")
        }
    }

    func testGeneratedOfflineTilesRoundTripFromApplicationSupport() throws {
        let directory = root.appendingPathComponent("offline_tiles", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent("generated-sheet.mbtiles")
        try makeMinimalMBTiles(at: file)

        ActiveMapSelectionStore.save(try XCTUnwrap(OfflineTileMapSource(url: file)))

        guard case .restored(let source) = ActiveMapSelectionStore.restore(),
              let offline = source as? OfflineTileMapSource else {
            return XCTFail("expected generated offline_tiles basemap to restore")
        }
        XCTAssertEqual(offline.url.standardizedFileURL, file.standardizedFileURL)
    }

    func testLegacyOfflineBasenameStillRestoresFromImportedMaps() throws {
        let directory = try ActiveMapSelectionStore.importedMapsDirectoryProvider()
        let file = directory.appendingPathComponent("legacy-sheet.mbtiles")
        try makeMinimalMBTiles(at: file)
        try writeOfflineSelection(value: file.lastPathComponent)

        guard case .restored(let source) = ActiveMapSelectionStore.restore(),
              let offline = source as? OfflineTileMapSource else {
            return XCTFail("expected legacy basename descriptor to restore")
        }
        XCTAssertEqual(offline.url.standardizedFileURL, file.standardizedFileURL)
    }

    func testTraversalAndSymlinkEscapeAreRejected() throws {
        try writeOfflineSelection(value: "../outside.mbtiles")
        guard case .unavailable = ActiveMapSelectionStore.restore() else {
            return XCTFail("relative traversal must be rejected")
        }

        let outside = FileManager.default.temporaryDirectory
            .appendingPathComponent("active-map-outside-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: outside) }
        try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
        try makeMinimalMBTiles(at: outside.appendingPathComponent("escaped.mbtiles"))
        let link = root.appendingPathComponent("offline_tiles", isDirectory: true)
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: outside)

        try writeOfflineSelection(value: "offline_tiles/escaped.mbtiles")
        guard case .unavailable = ActiveMapSelectionStore.restore() else {
            return XCTFail("an intermediate symlink must not escape Application Support")
        }
    }

    func testSaveRefusesOfflineFileOutsideManagedDirectories() throws {
        let outside = root.appendingPathComponent("Other", isDirectory: true)
        try FileManager.default.createDirectory(at: outside, withIntermediateDirectories: true)
        let file = outside.appendingPathComponent("unmanaged.mbtiles")
        try makeMinimalMBTiles(at: file)

        ActiveMapSelectionStore.save(try XCTUnwrap(OfflineTileMapSource(url: file)))

        guard case .noSelection = ActiveMapSelectionStore.restore() else {
            return XCTFail("unmanaged MBTiles must not replace the active selection")
        }
    }

    func testMissingSelectionIsDistinctFromUnavailableSelection() {
        guard case .noSelection = ActiveMapSelectionStore.restore() else {
            return XCTFail("fresh install should have no persisted map selection")
        }
    }

    func testPDFSessionPersistsBeforeActiveSelectionRoundTrips() throws {
        let source = try makePersistablePDFSource()

        XCTAssertTrue(PDFSessionStore.save(source))
        ActiveMapSelectionStore.save(source)

        guard case .restored(let restoredSource) = ActiveMapSelectionStore.restore(),
              let restored = restoredSource as? PDFMapSource else {
            return XCTFail("expected persisted PDF to restore as the active basemap")
        }
        let restoredBounds = try XCTUnwrap(restored.bounds)
        XCTAssertEqual(restored.url.standardizedFileURL, source.url.standardizedFileURL)
        XCTAssertEqual(restoredBounds.southWest.latitude, -34, accuracy: 1e-12)
        XCTAssertEqual(restoredBounds.northEast.longitude, 152, accuracy: 1e-12)
    }

    func testPDFRetainedAcrossOnlineSwitchAndColdRestoreKeepsActiveSeparate() throws {
        let source = try makePersistablePDFSource()
        XCTAssertTrue(PDFSessionStore.save(source))
        XCTAssertTrue(ActiveMapSelectionStore.save(source))

        XCTAssertTrue(ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmStreet)))

        guard case .restored(let active) = ActiveMapSelectionStore.restore(),
              let online = active as? OnlineRasterBasemapSource else {
            return XCTFail("the active selection must stay online after a cold restore")
        }
        XCTAssertEqual(online.style, .osmStreet)

        for _ in 0..<2 {
            guard case .restored(let retained) = ActiveMapSelectionStore.restoreRetained(),
                  let pdf = retained as? PDFMapSource else {
                return XCTFail("the independently retained PDF must remain returnable")
            }
            XCTAssertEqual(pdf.url.standardizedFileURL, source.url.standardizedFileURL)
            XCTAssertEqual(try XCTUnwrap(pdf.bounds).southWest.latitude, -34, accuracy: 1e-12)
        }

        let sealed = try Data(contentsOf: ActiveMapSelectionStore.storageURLProvider())
        XCTAssertTrue(SealedEnvelope.isSealedFile(sealed))
        XCTAssertFalse(String(decoding: sealed, as: UTF8.self).contains(source.url.lastPathComponent))
    }

    func testMBTilesRetainedAcrossOnlineSwitchAndColdRestoreKeepsActiveSeparate() throws {
        let directory = try ActiveMapSelectionStore.importedMapsDirectoryProvider()
        let file = directory.appendingPathComponent("returnable.mbtiles")
        try makeMinimalMBTiles(at: file)
        let source = try XCTUnwrap(OfflineTileMapSource(url: file))

        XCTAssertTrue(ActiveMapSelectionStore.save(source))
        XCTAssertTrue(ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmTopo)))

        guard case .restored(let active) = ActiveMapSelectionStore.restore(),
              let online = active as? OnlineRasterBasemapSource else {
            return XCTFail("the active selection must stay online after a cold restore")
        }
        XCTAssertEqual(online.style, .osmTopo)

        for _ in 0..<2 {
            guard case .restored(let retained) = ActiveMapSelectionStore.restoreRetained(),
                  let tiles = retained as? OfflineTileMapSource else {
                return XCTFail("the independently retained MBTiles map must remain returnable")
            }
            XCTAssertEqual(tiles.url.standardizedFileURL, file.standardizedFileURL)
        }
    }

    func testOnlineSelectionCanAtomicallyClearActiveAndRetainedSnapshot() throws {
        let directory = try ActiveMapSelectionStore.importedMapsDirectoryProvider()
        let file = directory.appendingPathComponent("atomic-clear.mbtiles")
        try makeMinimalMBTiles(at: file)
        XCTAssertTrue(ActiveMapSelectionStore.save(try XCTUnwrap(OfflineTileMapSource(url: file))))

        XCTAssertTrue(ActiveMapSelectionStore.save(
            OnlineRasterBasemapSource(.osmStreet),
            clearRetained: true
        ))

        guard case .restored(let active) = ActiveMapSelectionStore.restore(),
              (active as? OnlineRasterBasemapSource)?.style == .osmStreet else {
            return XCTFail("online active choice should commit with the clear")
        }
        guard case .noRetainedMap = ActiveMapSelectionStore.restoreRetained() else {
            return XCTFail("active and retained must come from the same v2 snapshot")
        }
    }

    func testMissingRetainedBackingFileIsActionableWhileOnlineActiveStillRestores() throws {
        let directory = try ActiveMapSelectionStore.importedMapsDirectoryProvider()
        let file = directory.appendingPathComponent("missing-retained.mbtiles")
        try makeMinimalMBTiles(at: file)
        XCTAssertTrue(ActiveMapSelectionStore.save(try XCTUnwrap(OfflineTileMapSource(url: file))))
        XCTAssertTrue(ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmTopo)))
        try FileManager.default.removeItem(at: file)

        guard case .restored(let active) = ActiveMapSelectionStore.restore(),
              active is OnlineRasterBasemapSource else {
            return XCTFail("a missing retained file must not break the separate active map")
        }
        guard case .unavailable(let message) = ActiveMapSelectionStore.restoreRetained() else {
            return XCTFail("a missing retained file needs an actionable unavailable state")
        }
        XCTAssertTrue(message.localizedCaseInsensitiveContains("missing"))

        XCTAssertNoThrow(try ActiveMapSelectionStore.removeRetainedMap(deleteBackingFile: false))
        guard case .noRetainedMap = ActiveMapSelectionStore.restoreRetained() else {
            return XCTFail("the stale retained entry should be removable")
        }
    }

    func testRetainedMapCannotBeDeletedWhileItIsActive() throws {
        let directory = try ActiveMapSelectionStore.importedMapsDirectoryProvider()
        let file = directory.appendingPathComponent("active-delete-guard.mbtiles")
        try makeMinimalMBTiles(at: file)
        XCTAssertTrue(ActiveMapSelectionStore.save(try XCTUnwrap(OfflineTileMapSource(url: file))))

        XCTAssertThrowsError(try ActiveMapSelectionStore.removeRetainedMap(deleteBackingFile: true))
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))

        XCTAssertTrue(ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmTopo)))
        XCTAssertNoThrow(try ActiveMapSelectionStore.removeRetainedMap(deleteBackingFile: true))
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
        guard case .noRetainedMap = ActiveMapSelectionStore.restoreRetained() else {
            return XCTFail("delete should clear the retained entry only after active separation")
        }
    }

    func testLegacyOnlineSelectionMigratesExistingPDFLibraryEntry() throws {
        let source = try makePersistablePDFSource()
        XCTAssertTrue(PDFSessionStore.save(source))
        let legacy = try JSONSerialization.data(
            withJSONObject: ["kind": "online", "value": "osmTopo"]
        )
        try SafeStore.write(
            legacy,
            to: ActiveMapSelectionStore.storageURLProvider(),
            label: "map_source/active_selection"
        )

        guard case .restored(let active) = ActiveMapSelectionStore.restore(),
              active is OnlineRasterBasemapSource else {
            return XCTFail("legacy active selection should remain online")
        }
        guard case .restored(let retained) = ActiveMapSelectionStore.restoreRetained(),
              let pdf = retained as? PDFMapSource else {
            return XCTFail("the already-sealed PDF session should migrate into retained state")
        }
        XCTAssertEqual(pdf.url.standardizedFileURL, source.url.standardizedFileURL)
    }

    func testLegacyMigrationDoesNotPublishUntilV2SnapshotIsDurable() throws {
        let legacyURL = ActiveMapSelectionStore.storageURLProvider()
        let legacy = try JSONSerialization.data(
            withJSONObject: ["kind": "online", "value": "osmTopo"]
        )
        try SafeStore.write(
            legacy,
            to: legacyURL,
            label: "map_source/active_selection"
        )
        let blocker = root.appendingPathComponent("not-a-directory")
        try Data([0x01]).write(to: blocker)
        var providerCalls = 0
        ActiveMapSelectionStore.storageURLProvider = {
            providerCalls += 1
            return providerCalls == 1
                ? legacyURL
                : blocker.appendingPathComponent("selection.json")
        }

        guard case .unavailable = ActiveMapSelectionStore.restore() else {
            return XCTFail("legacy state must not publish before the v2 write succeeds")
        }

        ActiveMapSelectionStore.storageURLProvider = { legacyURL }
        guard case .restored(let source) = ActiveMapSelectionStore.restore(),
              (source as? OnlineRasterBasemapSource)?.style == .osmTopo else {
            return XCTFail("the same legacy bytes should remain retryable")
        }
    }

    func testCorruptSelectionRemainsActionableAfterActiveRestoreQuarantinesIt() throws {
        let directory = try ActiveMapSelectionStore.importedMapsDirectoryProvider()
        let file = directory.appendingPathComponent("corrupt-state.mbtiles")
        try makeMinimalMBTiles(at: file)
        XCTAssertTrue(ActiveMapSelectionStore.save(try XCTUnwrap(OfflineTileMapSource(url: file))))
        XCTAssertTrue(ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmTopo)))
        var bytes = try Data(contentsOf: ActiveMapSelectionStore.storageURLProvider())
        bytes[bytes.count - 1] ^= 0x01
        try bytes.write(to: ActiveMapSelectionStore.storageURLProvider(), options: .atomic)

        guard case .unavailable = ActiveMapSelectionStore.restore() else {
            return XCTFail("corrupt active state must fall back safely")
        }
        guard case .unavailable(let message) = ActiveMapSelectionStore.restoreRetained() else {
            return XCTFail("the retained-map UI still needs an actionable recovery state")
        }
        XCTAssertTrue(message.localizedCaseInsensitiveContains("recovery"))

        XCTAssertNoThrow(try ActiveMapSelectionStore.removeRetainedMap(deleteBackingFile: false))
        guard case .noRetainedMap = ActiveMapSelectionStore.restoreRetained() else {
            return XCTFail("reset should dismiss the quarantined descriptor warning")
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path),
                      "resetting an unreadable descriptor must not guess which file to delete")
    }

    func testPDFSessionSaveReportsFailureWhenSealingIsUnavailable() throws {
        let source = try makePersistablePDFSource()
        let key = testKey
        SafeStore.keyProvider = { throw DataKey.LockedError() }

        let saved = PDFSessionStore.save(source)

        SafeStore.keyProvider = { key }
        XCTAssertFalse(saved)
        XCTAssertNil(PDFSessionStore.load())
    }

    func testCoordinatorPublishesOnlyAfterSelectorAndRetry() {
        var durable = "old"
        var shouldFail = true
        var publications: [String] = []
        let coordinator = ActiveMapSelectionCommitCoordinator<String>(
            persistSelection: { source, _ in
                guard !shouldFail else { return false }
                durable = source
                return true
            },
            publish: { source in
                XCTAssertEqual(durable, source, "publication must observe the durable selector")
                publications.append(source)
            }
        )

        XCTAssertEqual(coordinator.select("new"), .failed(.selector))
        XCTAssertEqual(durable, "old")
        XCTAssertTrue(publications.isEmpty)

        shouldFail = false
        XCTAssertEqual(coordinator.select("new"), .succeeded)
        XCTAssertEqual(publications, ["new"])
    }

    func testPDFCoordinatorBoundsCrashWindowAndRollsExactSessionBack() throws {
        let oldPDF = try makePersistablePDFSource()
        let newPDF = try makePersistablePDFSource()
        XCTAssertTrue(PDFSessionStore.save(oldPDF))
        let oldSnapshot = PDFSessionStore.snapshotActiveSession()
        var events: [String] = []
        var failSelector = true
        let coordinator = ActiveMapSelectionCommitCoordinator<PDFMapSource>(
            persistSelection: { _, _ in
                events.append("selector")
                return !failSelector
            },
            publish: { _ in events.append("publish") }
        )

        let first = coordinator.activatePDF(
            newPDF,
            persistSession: {
                events.append("session")
                return PDFSessionStore.save(newPDF)
            },
            rollbackSession: {
                events.append("rollback")
                return PDFSessionStore.restoreActiveSession(oldSnapshot)
            }
        )

        XCTAssertEqual(first, .failed(.selector))
        XCTAssertEqual(events, ["session", "selector", "rollback"])
        XCTAssertEqual(PDFSessionStore.load()?.url, oldPDF.url)
        XCTAssertFalse(events.contains("publish"),
                       "the session/selector crash window must not reach UI publication")

        events.removeAll()
        failSelector = false
        let retrySnapshot = PDFSessionStore.snapshotActiveSession()
        XCTAssertEqual(
            coordinator.activatePDF(
                newPDF,
                persistSession: { events.append("session"); return PDFSessionStore.save(newPDF) },
                rollbackSession: {
                    events.append("rollback")
                    return PDFSessionStore.restoreActiveSession(retrySnapshot)
                }
            ),
            .succeeded
        )
        XCTAssertEqual(events, ["session", "selector", "publish"])
        XCTAssertEqual(PDFSessionStore.load()?.url, newPDF.url)
    }

    func testMapViewModelDiskFullSelectorGatesUIAndRetry() {
        var failWrite = true
        let snapshot = PDFSessionStore.snapshotActiveSession()
        let dependencies = MapSelectionDependencies(
            persistSelection: { _, _ in !failWrite },
            restoreActive: { .noSelection },
            restoreRetained: { .noRetainedMap },
            removeRetained: { _ in },
            snapshotPDFSession: { snapshot },
            persistPDFSession: { _ in true },
            restorePDFSession: { _ in true }
        )
        let original = OnlineRasterBasemapSource(.osmTopo)
        let viewModel = MapViewModel(
            mapSelectionDependencies: dependencies,
            initialMapSource: original
        )

        XCTAssertFalse(viewModel.selectOnlineBasemap(.osmStreet))
        XCTAssertTrue(viewModel.mapSource === original)
        XCTAssertNotNil(viewModel.mapSelectionPersistenceIssue,
                        "the production UI binding must receive an actionable Retry issue")
        XCTAssertTrue(viewModel.mapSelectionPersistenceIssue?.message.contains("Retry") == true)

        failWrite = false
        XCTAssertTrue(viewModel.retryMapSelectionPersistence())
        XCTAssertEqual((viewModel.mapSource as? OnlineRasterBasemapSource)?.style, .osmStreet)
        XCTAssertNil(viewModel.mapSelectionPersistenceIssue)
    }

    func testMapViewModelLockedPDFSessionRollsBackAndRetries() throws {
        let pdf = try makePersistablePDFSource()
        let original = OnlineRasterBasemapSource(.osmTopo)
        let snapshot = PDFSessionStore.snapshotActiveSession()
        var sessionLocked = true
        var rollbackCalls = 0
        var selectorWrites = 0
        let dependencies = MapSelectionDependencies(
            persistSelection: { _, _ in selectorWrites += 1; return true },
            restoreActive: { .noSelection },
            restoreRetained: { .noRetainedMap },
            removeRetained: { _ in },
            snapshotPDFSession: { snapshot },
            persistPDFSession: { _ in !sessionLocked },
            restorePDFSession: { _ in rollbackCalls += 1; return true }
        )
        let viewModel = MapViewModel(
            mapSelectionDependencies: dependencies,
            initialMapSource: original
        )

        XCTAssertFalse(viewModel.selectMapSource(pdf))
        XCTAssertTrue(viewModel.mapSource === original)
        XCTAssertEqual(selectorWrites, 0)
        XCTAssertEqual(rollbackCalls, 1)
        XCTAssertNotNil(viewModel.mapSelectionPersistenceIssue)

        sessionLocked = false
        XCTAssertTrue(viewModel.retryMapSelectionPersistence())
        XCTAssertTrue(viewModel.mapSource === pdf)
        XCTAssertEqual(selectorWrites, 1)
        XCTAssertNil(viewModel.mapSelectionPersistenceIssue)
    }

    func testMapViewModelColdRestorePublishesOnlyAuthenticatedDescriptor() {
        let restored = OnlineRasterBasemapSource(.osmStreet)
        var restoreAvailable = false
        var writes = 0
        let snapshot = PDFSessionStore.snapshotActiveSession()
        let dependencies = MapSelectionDependencies(
            persistSelection: { _, _ in writes += 1; return true },
            restoreActive: { restoreAvailable ? .restored(restored) : .unavailable },
            restoreRetained: { .noRetainedMap },
            removeRetained: { _ in },
            snapshotPDFSession: { snapshot },
            persistPDFSession: { _ in true },
            restorePDFSession: { _ in true }
        )
        let original = OnlineRasterBasemapSource(.osmTopo)
        let viewModel = MapViewModel(
            mapSelectionDependencies: dependencies,
            initialMapSource: original
        )

        XCTAssertNil(viewModel.restoreActiveMapSelection())
        XCTAssertTrue(viewModel.mapSource === original)
        XCTAssertNotNil(viewModel.mapSelectionPersistenceIssue)

        restoreAvailable = true
        XCTAssertTrue(viewModel.retryMapSelectionPersistence())
        XCTAssertTrue(viewModel.mapSource === restored)
        XCTAssertEqual(writes, 0, "an authenticated cold descriptor is already durable")
        XCTAssertNil(viewModel.mapSelectionPersistenceIssue)
    }

    func testMapViewModelRejectsUnmanagedMBTilesWithoutPublishing() throws {
        let unmanagedDirectory = root.appendingPathComponent("Unmanaged", isDirectory: true)
        try FileManager.default.createDirectory(at: unmanagedDirectory, withIntermediateDirectories: true)
        let file = unmanagedDirectory.appendingPathComponent("outside.mbtiles")
        try makeMinimalMBTiles(at: file)
        let unmanaged = try XCTUnwrap(OfflineTileMapSource(url: file))
        let original = OnlineRasterBasemapSource(.osmTopo)
        let viewModel = MapViewModel(initialMapSource: original)

        XCTAssertFalse(viewModel.selectMapSource(unmanaged))
        XCTAssertTrue(viewModel.mapSource === original)
        XCTAssertNotNil(viewModel.mapSelectionPersistenceIssue)
        guard case .noSelection = ActiveMapSelectionStore.restore() else {
            return XCTFail("sandbox rejection must preserve the known-good selector")
        }
    }

    func testRetainedRestoreAndColdRestoreUseDurableProductionPath() throws {
        let pdf = try makePersistablePDFSource()
        XCTAssertTrue(PDFSessionStore.save(pdf))
        let viewModel = MapViewModel(initialMapSource: OnlineRasterBasemapSource(.osmTopo))

        XCTAssertTrue(viewModel.restoreRetainedMap(pdf))
        XCTAssertTrue(viewModel.mapSource === pdf)

        let relaunched = MapViewModel(initialMapSource: OnlineRasterBasemapSource(.osmStreet))
        let cold = try XCTUnwrap(relaunched.restoreActiveMapSelection() as? PDFMapSource)
        XCTAssertEqual(cold.url.standardizedFileURL, pdf.url.standardizedFileURL)
    }

    func testDeleteSwitchesOnlineBeforeCloseAndBackingRemoval() throws {
        let directory = try ActiveMapSelectionStore.importedMapsDirectoryProvider()
        let file = directory.appendingPathComponent("delete-through-view-model.mbtiles")
        try makeMinimalMBTiles(at: file)
        let source = try XCTUnwrap(OfflineTileMapSource(url: file))
        XCTAssertTrue(ActiveMapSelectionStore.save(source))
        let viewModel = MapViewModel(initialMapSource: source)

        XCTAssertTrue(viewModel.deleteRetainedImportedMap(source, returningTo: .osmTopo))
        XCTAssertEqual((viewModel.mapSource as? OnlineRasterBasemapSource)?.style, .osmTopo)
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
        guard case .noRetainedMap = ActiveMapSelectionStore.restoreRetained() else {
            return XCTFail("successful delete must clear the durable library entry")
        }
    }

    func testDeleteFailureKeepsOnlinePublicationDurableAndRetriesCleanup() throws {
        let directory = try ActiveMapSelectionStore.importedMapsDirectoryProvider()
        let file = directory.appendingPathComponent("delete-retry.mbtiles")
        try makeMinimalMBTiles(at: file)
        let source = try XCTUnwrap(OfflineTileMapSource(url: file))
        var durableSource: MapSource = source
        var removalAttempts = 0
        let snapshot = PDFSessionStore.snapshotActiveSession()
        let dependencies = MapSelectionDependencies(
            persistSelection: { candidate, _ in durableSource = candidate; return true },
            restoreActive: { .restored(durableSource) },
            restoreRetained: { .restored(source) },
            removeRetained: { _ in
                removalAttempts += 1
                if removalAttempts == 1 { throw CocoaError(.fileWriteOutOfSpace) }
            },
            snapshotPDFSession: { snapshot },
            persistPDFSession: { _ in true },
            restorePDFSession: { _ in true }
        )
        let viewModel = MapViewModel(
            mapSelectionDependencies: dependencies,
            initialMapSource: source
        )

        XCTAssertFalse(viewModel.deleteRetainedImportedMap(source, returningTo: .osmTopo))
        XCTAssertTrue(viewModel.mapSource === durableSource)
        XCTAssertTrue(durableSource is OnlineRasterBasemapSource)
        XCTAssertNotNil(viewModel.mapSelectionPersistenceIssue)

        XCTAssertTrue(viewModel.retryMapSelectionPersistence())
        XCTAssertEqual(removalAttempts, 2)
        XCTAssertNil(viewModel.mapSelectionPersistenceIssue)
    }

    func testSuccessfulReplacementDeletesSupersededPDFAndMBTilesOnlyAfterCommit() throws {
        let viewModel = MapViewModel(initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        let oldPDF = try makePersistablePDFSource()
        XCTAssertTrue(viewModel.selectMapSource(oldPDF))
        XCTAssertTrue(FileManager.default.fileExists(atPath: oldPDF.url.path))

        let imported = try ActiveMapSelectionStore.importedMapsDirectoryProvider()
        let staleImportedTiles = imported.appendingPathComponent("stale-import.mbtiles")
        try makeMinimalMBTiles(at: staleImportedTiles)
        let generated = root.appendingPathComponent("offline_tiles", isDirectory: true)
        let staleGeneratedTiles = generated.appendingPathComponent("stale-generated.mbtiles")
        try makeMinimalMBTiles(at: staleGeneratedTiles)
        let replacement = try makePersistablePDFSource()

        XCTAssertTrue(viewModel.selectMapSource(replacement))

        XCTAssertFalse(FileManager.default.fileExists(atPath: oldPDF.url.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: replacement.url.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: staleImportedTiles.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: staleGeneratedTiles.path))
    }

    func testFailedReplacementCommitNeverRunsManagedFileCleanup() throws {
        let oldPDF = try makePersistablePDFSource()
        let candidate = try makePersistablePDFSource()
        var reconciliationCalls = 0
        let snapshot = PDFSessionStore.snapshotActiveSession()
        let dependencies = MapSelectionDependencies(
            persistSelection: { _, _ in false },
            restoreActive: { .noSelection },
            restoreRetained: { .noRetainedMap },
            removeRetained: { _ in },
            snapshotPDFSession: { snapshot },
            persistPDFSession: { _ in true },
            restorePDFSession: { _ in true },
            reconcileManagedMapFiles: {
                reconciliationCalls += 1
                return true
            }
        )
        let viewModel = MapViewModel(
            mapSelectionDependencies: dependencies,
            initialMapSource: oldPDF
        )

        XCTAssertFalse(viewModel.selectMapSource(candidate))

        XCTAssertEqual(reconciliationCalls, 0)
        XCTAssertTrue(viewModel.mapSource === oldPDF)
        XCTAssertTrue(FileManager.default.fileExists(atPath: oldPDF.url.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: candidate.url.path))
    }

    func testColdReconciliationFailsClosedWithoutAuthenticatedCurrentSnapshot() throws {
        let orphan = try makePersistablePDFSource().url

        XCTAssertFalse(ActiveMapSelectionStore.reconcileManagedImportedMapFiles())
        XCTAssertTrue(FileManager.default.fileExists(atPath: orphan.path))
    }

    func testManagedFileReconciliationDoesNotFollowSymlinkOutsideRoot() throws {
        let imported = try ActiveMapSelectionStore.importedMapsDirectoryProvider()
        let keep = imported.appendingPathComponent("keep.pdf")
        let stale = imported.appendingPathComponent("stale.mbtiles")
        try Data("keep".utf8).write(to: keep)
        try Data("stale".utf8).write(to: stale)
        let outsideDirectory = root.appendingPathComponent("Outside", isDirectory: true)
        try FileManager.default.createDirectory(at: outsideDirectory, withIntermediateDirectories: true)
        let outside = outsideDirectory.appendingPathComponent("outside.pdf")
        try Data("outside".utf8).write(to: outside)
        let link = imported.appendingPathComponent("escape.pdf")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: outside)

        XCTAssertFalse(ManagedImportedMapFileLifecycle.reconcile(
            directories: [imported],
            keeping: [keep]
        ))

        XCTAssertTrue(FileManager.default.fileExists(atPath: keep.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: stale.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: link.path))
        XCTAssertEqual(try Data(contentsOf: outside), Data("outside".utf8))
    }

    func testManagedFileReconciliationCleansRecognizedCrashResidueOnly() throws {
        let imported = try ActiveMapSelectionStore.importedMapsDirectoryProvider()
        let generated = root.appendingPathComponent("offline_tiles", isDirectory: true)
        try FileManager.default.createDirectory(at: generated, withIntermediateDirectories: true)
        let keep = imported.appendingPathComponent("current.mbtiles")
        try Data("keep".utf8).write(to: keep)
        let residues = [
            imported.appendingPathComponent("copy.pdf.partial"),
            imported.appendingPathComponent("copy.mbtiles.partial"),
            imported.appendingPathComponent("copy.mbtiles.partial-wal"),
            imported.appendingPathComponent("copy.mbtiles.partial-shm"),
            imported.appendingPathComponent("copy.mbtiles.partial-journal"),
            generated.appendingPathComponent("bake.mbtiles-wal"),
            generated.appendingPathComponent("bake.mbtiles-shm"),
            generated.appendingPathComponent("bake.mbtiles-journal"),
        ]
        for residue in residues {
            try Data("operational-map-residue".utf8).write(to: residue)
        }
        let unrelated = imported.appendingPathComponent("notes.partial")
        let misleading = imported.appendingPathComponent("map.pdf.partial.backup")
        try Data("unrelated".utf8).write(to: unrelated)
        try Data("backup".utf8).write(to: misleading)

        XCTAssertTrue(ManagedImportedMapFileLifecycle.reconcile(
            directories: [imported, generated],
            keeping: [keep]
        ))

        XCTAssertTrue(FileManager.default.fileExists(atPath: keep.path))
        for residue in residues {
            XCTAssertFalse(FileManager.default.fileExists(atPath: residue.path))
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: unrelated.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: misleading.path))
    }

    func testMBTilesReplacementKeepsOnlyDurablyRetainedManagedFile() throws {
        let imported = try ActiveMapSelectionStore.importedMapsDirectoryProvider()
        let firstURL = imported.appendingPathComponent("first.mbtiles")
        try makeMinimalMBTiles(at: firstURL)
        let first = try XCTUnwrap(OfflineTileMapSource(url: firstURL))
        let viewModel = MapViewModel(initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        XCTAssertTrue(viewModel.selectMapSource(first))

        let secondURL = imported.appendingPathComponent("second.mbtiles")
        try makeMinimalMBTiles(at: secondURL)
        let second = try XCTUnwrap(OfflineTileMapSource(url: secondURL))
        XCTAssertTrue(viewModel.selectMapSource(second))

        XCTAssertFalse(FileManager.default.fileExists(atPath: firstURL.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: secondURL.path))
    }

    func testPDFCalibrationFollowsIdenticalBytesAcrossDifferentNames() throws {
        let directory = try PDFSessionStore.importedMapsDirectoryProvider()
        let originalURL = directory.appendingPathComponent("original.pdf")
        let renamedURL = directory.appendingPathComponent("renamed.pdf")
        let bytes = Data("identical-pdf-fixture".utf8)
        try bytes.write(to: originalURL)
        try bytes.write(to: renamedURL)
        pdfFixtureURLs.append(contentsOf: [originalURL, renamedURL])

        let original = makeCalibratedPDFSource(at: originalURL)
        XCTAssertTrue(PDFSessionStore.save(original))

        let reimported = makeUncalibratedPDFSource(at: renamedURL)
        PDFSessionStore.applyCalibrationIfKnown(to: reimported)

        guard case .fiduciaries(let fids, let transform)? = reimported.calibration else {
            return XCTFail("identical bytes should restore calibration after rename")
        }
        XCTAssertEqual(fids.count, 3)
        XCTAssertEqual(transform, expectedCalibrationTransform)
    }

    func testPDFCalibrationDoesNotFollowDifferentBytesWithSameName() throws {
        let directory = try PDFSessionStore.importedMapsDirectoryProvider()
        let url = directory.appendingPathComponent("colliding-name.pdf")
        try Data("sheet-a".utf8).write(to: url)
        pdfFixtureURLs.append(url)

        XCTAssertTrue(PDFSessionStore.save(makeCalibratedPDFSource(at: url)))
        try Data("sheet-b-is-different".utf8).write(to: url, options: .atomic)

        let replacement = makeUncalibratedPDFSource(at: url)
        PDFSessionStore.applyCalibrationIfKnown(to: replacement)
        XCTAssertNil(replacement.calibration)
        XCTAssertNil(PDFSessionStore.load(), "cold restore must reject changed bytes too")
    }

    func testPDFCalibrationColdRestoreAndReimportRemainStable() throws {
        let directory = try PDFSessionStore.importedMapsDirectoryProvider()
        let url = directory.appendingPathComponent("cold-restore.pdf")
        try Data("stable-sheet".utf8).write(to: url)
        pdfFixtureURLs.append(url)

        XCTAssertTrue(PDFSessionStore.save(makeCalibratedPDFSource(at: url)))
        guard let restored = PDFSessionStore.load() else {
            return XCTFail("expected content-bound cold restore")
        }
        guard case .fiduciaries(let restoredFids, let restoredTransform)? = restored.calibration else {
            return XCTFail("expected restored manual calibration")
        }
        XCTAssertEqual(restoredFids.count, 3)
        XCTAssertEqual(restoredTransform, expectedCalibrationTransform)

        let reimported = makeUncalibratedPDFSource(at: url)
        PDFSessionStore.applyCalibrationIfKnown(to: reimported)
        XCTAssertNotNil(reimported.calibration)
    }

    func testPDFSourceRejectsDegenerateManualCalibrationAndPersistsSafeFallback() throws {
        let directory = try PDFSessionStore.importedMapsDirectoryProvider()
        let url = directory.appendingPathComponent("degenerate-calibration.pdf")
        try Data("degenerate-sheet".utf8).write(to: url)
        pdfFixtureURLs.append(url)
        let source = makeUncalibratedPDFSource(at: url)
        source.applyCalibration(
            transform: AffineTransform2D(a: 1, b: 2, c: 150, d: 2, e: 4, f: -34),
            fiduciaries: [
                Fiduciary(pdfX: 0, pdfY: 0, mgrs: "a", latitude: -34, longitude: 150),
                Fiduciary(pdfX: 1, pdfY: 0, mgrs: "b", latitude: -33, longitude: 151),
                Fiduciary(pdfX: 0, pdfY: 1, mgrs: "c", latitude: -32, longitude: 152),
            ]
        )

        XCTAssertNil(source.calibration)
        XCTAssertTrue(PDFSessionStore.save(source))
        XCTAssertNil(PDFSessionStore.load()?.calibration)
    }

    func testPDFSessionRejectsCollinearControlPointsEvenWithInvertibleAffine() throws {
        let directory = try PDFSessionStore.importedMapsDirectoryProvider()
        let url = directory.appendingPathComponent("collinear-calibration.pdf")
        try Data("collinear-sheet".utf8).write(to: url)
        pdfFixtureURLs.append(url)
        let source = makeUncalibratedPDFSource(at: url)
        source.applyCalibration(
            transform: expectedCalibrationTransform,
            fiduciaries: [
                Fiduciary(pdfX: 0, pdfY: 0, mgrs: "a", latitude: -34, longitude: 150),
                Fiduciary(pdfX: 1, pdfY: 1, mgrs: "b", latitude: -33.99, longitude: 150.01),
                Fiduciary(pdfX: 2, pdfY: 2, mgrs: "c", latitude: -33.98, longitude: 150.02),
            ]
        )

        XCTAssertFalse(PDFSessionStore.save(source))
        XCTAssertNil(PDFSessionStore.load())
    }

    func testLegacyActivePDFCalibrationMigratesToContentIdentity() throws {
        let directory = try PDFSessionStore.importedMapsDirectoryProvider()
        let legacyURL = directory.appendingPathComponent("legacy-active.pdf")
        let historicalURL = legacyDocumentsDirectory.appendingPathComponent("legacy-active.pdf")
        let renamedURL = directory.appendingPathComponent("renamed-after-migration.pdf")
        let bytes = Data("legacy-active-sheet".utf8)
        try bytes.write(to: legacyURL)
        try bytes.write(to: historicalURL)
        try bytes.write(to: renamedURL)
        pdfFixtureURLs.append(contentsOf: [legacyURL, historicalURL, renamedURL])
        try storeLegacyActivePDFDescriptor(fileName: legacyURL.lastPathComponent)

        let restored = try XCTUnwrap(PDFSessionStore.load())
        XCTAssertNotNil(restored.calibration)
        let renamed = makeUncalibratedPDFSource(at: renamedURL)
        PDFSessionStore.applyCalibrationIfKnown(to: renamed)
        XCTAssertNotNil(renamed.calibration,
                        "trusted legacy active metadata should migrate to the byte hash")
    }

    func testSealedPDFSessionRequiresDurablePolicyBeforePublication() throws {
        enum PolicyFailure: Error { case forced }
        let directory = try PDFSessionStore.importedMapsDirectoryProvider()
        let url = directory.appendingPathComponent("policy-gated-active.pdf")
        let historicalURL = legacyDocumentsDirectory.appendingPathComponent(
            "policy-gated-active.pdf"
        )
        let bytes = Data("policy-gated-active-sheet".utf8)
        try bytes.write(to: url)
        try bytes.write(to: historicalURL)
        pdfFixtureURLs.append(contentsOf: [url, historicalURL])
        let plaintext = try storeLegacyActivePDFDescriptor(fileName: url.lastPathComponent)
        let defaults = PDFSessionStore.defaultsProvider()
        let sealed = try XCTUnwrap(defaults.data(forKey: "active_pdf_v1"))
        SealedMigrationPolicy.resetForTests(key: testKey)
        PDFSessionStore.markSealedPolicy = { _, _ in throw PolicyFailure.forced }

        XCTAssertNil(PDFSessionStore.load())
        XCTAssertEqual(defaults.data(forKey: "active_pdf_v1"), sealed)

        PDFSessionStore.markSealedPolicy = originalPDFMarkSealedPolicy
        XCTAssertNotNil(PDFSessionStore.load())
        defaults.set(plaintext, forKey: "active_pdf_v1")
        XCTAssertNil(
            PDFSessionStore.load(),
            "once the sealed policy is durable, a plaintext replacement must be rejected"
        )
        XCTAssertEqual(defaults.data(forKey: "active_pdf_v1"), plaintext)
    }

    func testSealedPDFCalibrationLibraryRequiresDurablePolicyBeforeUse() throws {
        enum PolicyFailure: Error { case forced }
        let directory = try PDFSessionStore.importedMapsDirectoryProvider()
        let originalURL = directory.appendingPathComponent("policy-library-original.pdf")
        let renamedURL = directory.appendingPathComponent("policy-library-renamed.pdf")
        let bytes = Data("policy-library-sheet".utf8)
        try bytes.write(to: originalURL)
        try bytes.write(to: renamedURL)
        pdfFixtureURLs.append(contentsOf: [originalURL, renamedURL])
        XCTAssertTrue(PDFSessionStore.save(makeCalibratedPDFSource(at: originalURL)))
        let defaults = PDFSessionStore.defaultsProvider()
        let sealed = try XCTUnwrap(defaults.data(forKey: "pdf_calibrations_v1"))
        let plaintext = try XCTUnwrap(SealedEnvelope.openFile(
            key: testKey,
            blob: sealed,
            label: "pdf_session/pdf_calibrations"
        ))
        SealedMigrationPolicy.resetForTests(key: testKey)
        PDFSessionStore.markSealedPolicy = { _, _ in throw PolicyFailure.forced }

        let unavailable = makeUncalibratedPDFSource(at: renamedURL)
        PDFSessionStore.applyCalibrationIfKnown(to: unavailable)
        XCTAssertNil(unavailable.calibration)
        XCTAssertEqual(defaults.data(forKey: "pdf_calibrations_v1"), sealed)

        PDFSessionStore.markSealedPolicy = originalPDFMarkSealedPolicy
        let available = makeUncalibratedPDFSource(at: renamedURL)
        PDFSessionStore.applyCalibrationIfKnown(to: available)
        XCTAssertNotNil(available.calibration)

        defaults.set(plaintext, forKey: "pdf_calibrations_v1")
        let downgraded = makeUncalibratedPDFSource(at: renamedURL)
        PDFSessionStore.applyCalibrationIfKnown(to: downgraded)
        XCTAssertNil(downgraded.calibration)
        XCTAssertEqual(defaults.data(forKey: "pdf_calibrations_v1"), plaintext)
    }

    func testLegacyFilenameCollisionNeverCrossBindsCalibrationToDifferentBytes() throws {
        let directory = try PDFSessionStore.importedMapsDirectoryProvider()
        let privateURL = directory.appendingPathComponent("sheet.pdf")
        let historicalURL = legacyDocumentsDirectory.appendingPathComponent("sheet.pdf")
        let originalBytes = Data("historical-original-sheet".utf8)
        let collidingBytes = Data("different-private-sheet".utf8)
        try collidingBytes.write(to: privateURL)
        try originalBytes.write(to: historicalURL)
        pdfFixtureURLs.append(contentsOf: [privateURL, historicalURL])
        try storeLegacyActivePDFDescriptor(fileName: "sheet.pdf")

        let restored = try XCTUnwrap(PDFSessionStore.load())

        XCTAssertNil(restored.calibration,
                     "filename provenance alone must never apply the legacy affine")
        XCTAssertEqual(try Data(contentsOf: restored.url), originalBytes,
                       "the historical map may be preserved, but only uncalibrated")
        XCTAssertEqual(try Data(contentsOf: privateURL), collidingBytes,
                       "the unrelated private collision must remain untouched")
        XCTAssertNotEqual(restored.url.standardizedFileURL, privateURL.standardizedFileURL)

        let coldRestored = try XCTUnwrap(PDFSessionStore.load())
        XCTAssertNil(coldRestored.calibration)
        XCTAssertEqual(coldRestored.contentKey, PDFSessionStore.contentKey(for: restored.url))
    }

    @discardableResult
    private func storeLegacyActivePDFDescriptor(fileName: String) throws -> Data {
        let fids: [[String: Any]] = [
            ["id": "00000000-0000-0000-0000-000000000001",
             "pdfX": 0.0, "pdfY": 0.0, "mgrs": "a", "latitude": -34.0, "longitude": 150.0],
            ["id": "00000000-0000-0000-0000-000000000002",
             "pdfX": 100.0, "pdfY": 0.0, "mgrs": "b", "latitude": -34.0, "longitude": 151.0],
            ["id": "00000000-0000-0000-0000-000000000003",
             "pdfX": 0.0, "pdfY": 100.0, "mgrs": "c", "latitude": -33.0, "longitude": 150.0],
        ]
        let transform: [String: Any] = [
            "a": 0.01, "b": 0.0, "c": 150.0,
            "d": 0.0, "e": 0.01, "f": -34.0,
        ]
        let descriptor: [String: Any] = [
            "fileName": fileName,
            "swLat": -34.0, "swLng": 150.0,
            "neLat": -33.0, "neLng": 151.0,
            "cropX": 0.0, "cropY": 0.0, "cropW": 100.0, "cropH": 100.0,
            "kind": MapSourceKind.calibratedPDF.rawValue,
            "calibration": ["fids": fids, "transform": transform],
        ]
        let plaintext = try JSONSerialization.data(withJSONObject: descriptor)
        let sealed = try SealedEnvelope.sealFile(
            key: testKey,
            plaintext: plaintext,
            label: "pdf_session/active_pdf"
        )
        PDFSessionStore.defaultsProvider().set(sealed, forKey: "active_pdf_v1")
        return plaintext
    }

    private func writeOfflineSelection(value: String) throws {
        let payload = try JSONSerialization.data(
            withJSONObject: ["kind": "offlineTiles", "value": value]
        )
        try SafeStore.write(
            payload,
            to: ActiveMapSelectionStore.storageURLProvider(),
            label: "map_source/active_selection"
        )
    }

    private func makeMinimalMBTiles(at url: URL) throws {
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        var db: OpaquePointer?
        guard sqlite3_open(url.path, &db) == SQLITE_OK else {
            throw CocoaError(.fileWriteUnknown)
        }
        defer { sqlite3_close(db) }
        let sql = """
        CREATE TABLE metadata (name TEXT, value TEXT);
        CREATE TABLE tiles (
          zoom_level INTEGER,
          tile_column INTEGER,
          tile_row INTEGER,
          tile_data BLOB
        );
        INSERT INTO metadata VALUES
          ('name','Training Area'),
          ('format','png'),
          ('bounds','150.0,-34.0,152.0,-32.0');
        INSERT INTO tiles VALUES (0, 0, 0, X'89504E47');
        """
        guard sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK else {
            throw CocoaError(.fileWriteUnknown)
        }
    }

    private func makePersistablePDFSource() throws -> PDFMapSource {
        let directory = try PDFSessionStore.importedMapsDirectoryProvider()
        let file = directory.appendingPathComponent(
            "active-map-\(UUID().uuidString).pdf",
            isDirectory: false
        )
        try Data().write(to: file, options: .atomic)
        pdfFixtureURLs.append(file)
        let bounds = GeoPDFReader.Bounds(
            southWest: CLLocationCoordinate2D(latitude: -34, longitude: 150),
            northEast: CLLocationCoordinate2D(latitude: -32, longitude: 152),
            pdfCropRect: CGRect(x: 0, y: 0, width: 200, height: 300)
        )
        return PDFMapSource(url: file, bounds: bounds, fromGeoPDF: false)
    }

    private var expectedCalibrationTransform: AffineTransform2D {
        AffineTransform2D(a: 0.01, b: 0, c: 150, d: 0, e: 0.01, f: -34)
    }

    private func makeUncalibratedPDFSource(at url: URL) -> PDFMapSource {
        PDFMapSource(
            url: url,
            bounds: GeoPDFReader.Bounds(
                southWest: CLLocationCoordinate2D(latitude: -34, longitude: 150),
                northEast: CLLocationCoordinate2D(latitude: -32, longitude: 152),
                pdfCropRect: CGRect(x: 0, y: 0, width: 200, height: 200)
            ),
            fromGeoPDF: false,
            preflightMediaBox: CGRect(x: 0, y: 0, width: 200, height: 200)
        )
    }

    private func makeCalibratedPDFSource(at url: URL) -> PDFMapSource {
        let source = makeUncalibratedPDFSource(at: url)
        source.applyCalibration(
            transform: expectedCalibrationTransform,
            fiduciaries: [
                Fiduciary(pdfX: 0, pdfY: 0, mgrs: "fixture-a", latitude: -34, longitude: 150),
                Fiduciary(pdfX: 100, pdfY: 0, mgrs: "fixture-b", latitude: -34, longitude: 151),
                Fiduciary(pdfX: 0, pdfY: 100, mgrs: "fixture-c", latitude: -33, longitude: 150),
            ]
        )
        return source
    }
}
