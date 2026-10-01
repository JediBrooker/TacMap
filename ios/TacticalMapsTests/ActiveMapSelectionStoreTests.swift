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

        XCTAssertTrue(ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmTopo)))

        guard case .restored(let active) = ActiveMapSelectionStore.restore(),
              let online = active as? OnlineRasterBasemapSource else {
            return XCTFail("the active selection must stay online after a cold restore")
        }
        XCTAssertEqual(online.style, .osmTopo)

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
            OnlineRasterBasemapSource(.osmTopo),
            clearRetained: true
        ))

        guard case .restored(let active) = ActiveMapSelectionStore.restore(),
              (active as? OnlineRasterBasemapSource)?.style == .osmTopo else {
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
        XCTAssertTrue(message.text.localizedCaseInsensitiveContains("missing"))

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
        XCTAssertTrue(message.text.localizedCaseInsensitiveContains("recovery"))

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
        // OD-F4 replaced "cold restore returns nil": the selection is kept, but
        // flagged, and the renderer refuses the changed bytes (cannotOpen). the
        // old calibration never gets drawn over sheet b
        let restored = try XCTUnwrap(PDFSessionStore.load())
        XCTAssertTrue(restored.storedFileUnavailable, "cold restore must reject changed bytes too")
        XCTAssertFalse(PDFSessionStore.storedFileMatches(restored))
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

        // the UTM refit refuses collinear points up front (plan 02 s1
        // degeneracy gate), so nothing calibrated exists to persist at all;
        // the map stays uncalibrated instead of the save failing later
        XCTAssertNil(source.calibration)
        XCTAssertTrue(source.isUncalibrated)
        XCTAssertTrue(PDFSessionStore.save(source))
        XCTAssertNil(PDFSessionStore.load()?.calibration)
    }

    func testLegacyActivePDFCalibrationMigratesToContentIdentity() throws {
        let directory = try PDFSessionStore.importedMapsDirectoryProvider()
        let legacyURL = directory.appendingPathComponent("legacy-active.pdf")
        let historicalURL = legacyDocumentsDirectory.appendingPathComponent("legacy-active.pdf")
        let renamedURL = directory.appendingPathComponent("renamed-after-migration.pdf")
        // a real (origin 0, unrotated) page now: v1 fiduciaries get moved out of
        // PDFKit's display space on the way in, which needs the page to open
        let bytes = minimalPDF(media: "0 0 100 100")
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
    func testLegacyActivePDFCalibrationOnAnUnreadablePageStaysPendingNotRefit() throws {
        let directory = try PDFSessionStore.importedMapsDirectoryProvider()
        let legacyURL = directory.appendingPathComponent("legacy-unreadable.pdf")
        let historicalURL = legacyDocumentsDirectory.appendingPathComponent("legacy-unreadable.pdf")
        let bytes = Data("legacy-active-sheet".utf8)
        try bytes.write(to: legacyURL)
        try bytes.write(to: historicalURL)
        pdfFixtureURLs.append(contentsOf: [legacyURL, historicalURL])
        try storeLegacyActivePDFDescriptor(fileName: legacyURL.lastPathComponent)

        // PDFKit can't open it, so the old display space can't be undone: never
        // refit those points as if they were raw, leave the map uncalibrated
        let restored = try XCTUnwrap(PDFSessionStore.load())
        XCTAssertNil(restored.calibration)
        XCTAssertTrue(restored.isUncalibrated)
        // ...but the points aren't thrown away, they sit in the library still
        // flagged as display space under the byte hash
        let sealed = try XCTUnwrap(PDFSessionStore.defaultsProvider().data(forKey: "pdf_calibrations_v1"))
        let plain = try XCTUnwrap(SealedEnvelope.openFile(key: testKey, blob: sealed, label: "pdf_session/pdf_calibrations"))
        let library = try XCTUnwrap(JSONSerialization.jsonObject(with: plain) as? [String: Any])
        let key = try XCTUnwrap(PDFSessionStore.contentKey(for: legacyURL))
        let pending = try XCTUnwrap((library["byContentHash"] as? [String: Any])?[key] as? [String: Any])
        XCTAssertEqual((pending["fids"] as? [[String: Any]])?.map { $0["pdfX"] as? Double }, [0, 100, 0])
        XCTAssertNil(pending["rawPageSpace"])
        XCTAssertNil(pending["georef"])
    }

    /// one empty page, enough for PDFKit to hand back its box transform
    private func minimalPDF(media: String) -> Data {
        let objects = ["<< /Type /Catalog /Pages 2 0 R >>",
                       "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                       "<< /Type /Page /Parent 2 0 R /MediaBox [\(media)] /Resources << >> >>"]
        var data = Data()
        func append(_ s: String) { data.append(s.data(using: .isoLatin1)!) }
        append("%PDF-1.7\n")
        var offsets: [Int] = []
        for (i, o) in objects.enumerated() { offsets.append(data.count); append("\(i + 1) 0 obj\n\(o)\nendobj\n") }
        let xref = data.count
        append("xref\n0 \(objects.count + 1)\n0000000000 65535 f \n")
        offsets.forEach { append(String(format: "%010d 00000 n \n", $0)) }
        append("trailer\n<< /Size \(objects.count + 1) /Root 1 0 R >>\nstartxref\n\(xref)\n%%EOF\n")
        return data
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

    /// OD-F4: a truncated, swapped or missing stored PDF keeps the selection.
    /// The restore hands back the session flagged, the renderer fails it as
    /// cannotOpen (G1, Try Again), never the "locked or unreadable" store
    /// error, and once the exact bytes are back Try Again (or a relaunch) draws it
    func testUnreadableStoredPDFKeepsTheSelectionAndRecovers() throws {
        let fixture = try XCTUnwrap(PDFTileRenderFixtureTests.testdataURL("geopdf/tacmap_grid_sf_iso.pdf"))
        let original = try Data(contentsOf: fixture)
        let directory = try PDFSessionStore.importedMapsDirectoryProvider()
        let file = directory.appendingPathComponent("import-\(UUID().uuidString).pdf")
        try original.write(to: file)
        pdfFixtureURLs.append(file)
        let g = try XCTUnwrap(GeoPDFReader.read(url: file)?.georef)
        let source = PDFMapSource(url: file, georef: g, contentKey: PDFSessionStore.contentKey(for: file))
        XCTAssertTrue(PDFSessionStore.save(source))
        XCTAssertTrue(ActiveMapSelectionStore.save(source))

        for damage in ["truncated", "swapped", "missing"] {
            switch damage {
            case "truncated": try original.prefix(original.count / 3).write(to: file)
            case "swapped":
                try Data(contentsOf: try XCTUnwrap(PDFTileRenderFixtureTests.testdataURL("geopdf/tacmap_render_blank.pdf")))
                    .write(to: file)
            default: try FileManager.default.removeItem(at: file)
            }
            guard case .restored(let restored) = ActiveMapSelectionStore.restore(),
                  let pdf = restored as? PDFMapSource else {
                return XCTFail("\(damage): the PDF selection is kept, not unavailable")
            }
            XCTAssertTrue(pdf.storedFileUnavailable, damage)
            XCTAssertEqual(pdf.renderGuardToken, source.renderGuardToken, damage)
            XCTAssertEqual(pdf.contentKey, source.contentKey, damage)
            XCTAssertEqual(pdf.georef, source.georef, damage)
            XCTAssertEqual(pdf.url.lastPathComponent, file.lastPathComponent, damage)
            // the stored session wasnt cleared or rewritten
            XCTAssertEqual(PDFSessionStore.activeContentKey(), source.contentKey, damage)

            // the view model publishes it, no "Map change not saved"
            let snapshot = PDFSessionStore.snapshotActiveSession()
            let deps = MapSelectionDependencies(
                persistSelection: { _, _ in true }, restoreActive: { ActiveMapSelectionStore.restore() },
                restoreRetained: { .noRetainedMap }, removeRetained: { _ in }, snapshotPDFSession: { snapshot },
                persistPDFSession: { _ in true }, restorePDFSession: { _ in true })
            let vm = MapViewModel(mapSelectionDependencies: deps, initialMapSource: OnlineRasterBasemapSource(.osmTopo))
            vm.pdfRenderGuard = PDFRenderGuard(url: root.appendingPathComponent("guard-\(damage).json"))
            let shown = try XCTUnwrap(vm.restoreActiveMapSelection() as? PDFMapSource)
            XCTAssertTrue(vm.mapSource === shown, "\(damage): the PDF stays the active map")
            XCTAssertNil(vm.mapSelectionPersistenceIssue, "\(damage): not a store failure")

            // G1 failed state, then the file comes back and Try Again draws it
            let runtime = PDFMapRuntime()
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
            // and a relaunch is a plain restore again
            let again = try XCTUnwrap(PDFSessionStore.load())
            XCTAssertFalse(again.storedFileUnavailable, damage)
            runtime.reset()
        }
    }

    private func waitUntil(_ what: String, timeout: Double = 20, _ cond: () -> Bool) {
        let end = Date().addingTimeInterval(timeout)
        while !cond(), Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
        XCTAssertTrue(cond(), "timed out waiting for \(what)")
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
        return PDFMapSource(url: file, bounds: bounds)
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

// MARK: - WP2: a PDF's baked tiles ride along with it

extension ActiveMapSelectionStoreTests {
    func testReconcileKeepsTheRetainedPDFsBakeAndDeleteReapsBoth() throws {
        let viewModel = MapViewModel(initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        let pdf = try makePersistablePDFSource()
        let generated = root.appendingPathComponent("offline_tiles", isDirectory: true)
        try FileManager.default.createDirectory(at: generated, withIntermediateDirectories: true)
        let bakeFile = generated.appendingPathComponent("tacmap-bake-keep.mbtiles")
        let orphan = generated.appendingPathComponent("tacmap-bake-orphan.mbtiles")
        try makeMinimalMBTiles(at: bakeFile)
        try makeMinimalMBTiles(at: orphan)
        pdf.bake = PDFBakeRecord(fileName: bakeFile.lastPathComponent, bakeKey: String(repeating: "a", count: 64),
                                 minZoom: 0, maxZoom: 14, tilePx: 512, bytes: 1024)

        XCTAssertTrue(viewModel.selectMapSource(pdf))
        XCTAssertTrue(FileManager.default.fileExists(atPath: pdf.url.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: bakeFile.path), "the PDF's bake is kept")
        XCTAssertFalse(FileManager.default.fileExists(atPath: orphan.path), "unreferenced bakes are reaped")

        // still kept with the PDF parked behind an online map
        XCTAssertTrue(viewModel.selectOnlineBasemap(.osmTopo))
        XCTAssertTrue(FileManager.default.fileExists(atPath: bakeFile.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: pdf.url.path))

        // the record survives the sealed session round trip
        let restored = try XCTUnwrap(PDFSessionStore.load())
        XCTAssertEqual(restored.bake, pdf.bake)
        XCTAssertEqual(restored.renderGuardToken, pdf.renderGuardToken)

        XCTAssertTrue(viewModel.deleteRetainedImportedMap(restored))
        XCTAssertFalse(FileManager.default.fileExists(atPath: pdf.url.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: bakeFile.path), "deleting the map deletes its bake")
    }

    func testDroppingTheBakeRecordLetsReconcileReapTheFile() throws {
        let viewModel = MapViewModel(initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        let pdf = try makePersistablePDFSource()
        let generated = root.appendingPathComponent("offline_tiles", isDirectory: true)
        try FileManager.default.createDirectory(at: generated, withIntermediateDirectories: true)
        let bakeFile = generated.appendingPathComponent("tacmap-bake-drop.mbtiles")
        try makeMinimalMBTiles(at: bakeFile)
        pdf.bake = PDFBakeRecord(fileName: bakeFile.lastPathComponent, bakeKey: String(repeating: "b", count: 64),
                                 minZoom: 0, maxZoom: 14, tilePx: 512, bytes: 1024)
        XCTAssertTrue(viewModel.selectMapSource(pdf))
        XCTAssertTrue(FileManager.default.fileExists(atPath: bakeFile.path))
        let controller = PDFBakeController()
        // Remove (and its R3-2 sweep) works on an empty scratch dir, so only the
        // reconcile can be what reaps the file here
        let scratch = root.appendingPathComponent("remove-scratch", isDirectory: true)
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
        controller.finalDirectory = scratch
        XCTAssertTrue(controller.removeBake(from: pdf))
        XCTAssertNil(pdf.bake)
        XCTAssertTrue(FileManager.default.fileExists(atPath: bakeFile.path), "Remove didnt touch it")
        XCTAssertTrue(ActiveMapSelectionStore.reconcileManagedImportedMapFiles())
        XCTAssertFalse(FileManager.default.fileExists(atPath: bakeFile.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: pdf.url.path))
    }

    func testBakeRecordValidation() {
        let ok = PDFBakeRecord(fileName: "tacmap-bake-1.mbtiles", bakeKey: String(repeating: "f", count: 64),
                               minZoom: 0, maxZoom: 16, tilePx: 768, bytes: 1)
        XCTAssertTrue(PDFSessionStore.validBake(ok))
        var bad = ok; bad.fileName = "../escape.mbtiles"
        XCTAssertFalse(PDFSessionStore.validBake(bad))
        bad = ok; bad.fileName = "x.pdf"
        XCTAssertFalse(PDFSessionStore.validBake(bad))
        bad = ok; bad.bakeKey = "zz"
        XCTAssertFalse(PDFSessionStore.validBake(bad))
        bad = ok; bad.maxZoom = 40
        XCTAssertFalse(PDFSessionStore.validBake(bad))
    }
}

// MARK: - WP2 round 4: Delete Map leaves nothing, failures are loud, sweep, Try Again

/// reads fall through to a stale value once ours is gone, like the sims device
/// wide prefs copy of com.tacticalmaps.app did (OD3-R3-1)
private final class ShadowedDefaults: UserDefaults {
    var shadow: [String: Data] = [:]
    override func data(forKey defaultName: String) -> Data? {
        super.data(forKey: defaultName) ?? shadow[defaultName]
    }
}

extension ActiveMapSelectionStoreTests {
    private var generatedDir: URL { root.appendingPathComponent("offline_tiles", isDirectory: true) }

    private func importedGeoPDF() throws -> PDFMapSource {
        let fixture = try XCTUnwrap(PDFTileRenderFixtureTests.testdataURL("geopdf/tacmap_grid_sf_iso.pdf"))
        let file = try PDFSessionStore.importedMapsDirectoryProvider()
            .appendingPathComponent("usgs-\(UUID().uuidString).pdf")
        try FileManager.default.copyItem(at: fixture, to: file)
        pdfFixtureURLs.append(file)
        let g = try XCTUnwrap(GeoPDFReader.read(url: file)?.georef)
        return PDFMapSource(url: file, georef: g, contentKey: PDFSessionStore.contentKey(for: file))
    }

    private func files(in dir: URL) -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []).sorted()
    }

    private func assertNothingLeft(for pdf: PDFMapSource, _ what: String,
                                   file: StaticString = #filePath, line: UInt = #line) throws {
        XCTAssertEqual(files(in: try PDFSessionStore.importedMapsDirectoryProvider()), [], "\(what): ImportedMaps",
                       file: file, line: line)
        XCTAssertEqual(files(in: generatedDir), [], "\(what): offline_tiles", file: file, line: line)
        XCTAssertNil(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"), "\(what): session record",
                     file: file, line: line)
        XCTAssertNil(PDFSessionStore.load(), "\(what): session", file: file, line: line)
        guard case .noRetainedMap = ActiveMapSelectionStore.restoreRetained() else {
            return XCTFail("\(what): a saved entry is still there", file: file, line: line)
        }
    }

    /// OD3-R3-1: Delete PDF Map on the visible PDF (its bake attached) leaves
    /// no PDF, no tiles, no session record and no saved entry
    func testDeleteMapLeavesNothingBehindForThatMap() throws {
        let pdf = try importedGeoPDF()
        try FileManager.default.createDirectory(at: generatedDir, withIntermediateDirectories: true)
        let bake = "tacmap-bake-\(UUID().uuidString).mbtiles"
        try makeMinimalMBTiles(at: generatedDir.appendingPathComponent(bake))
        try Data([1]).write(to: generatedDir.appendingPathComponent(bake + "-wal"))
        pdf.bake = PDFBakeRecord(fileName: bake, bakeKey: String(repeating: "d", count: 64),
                                 minZoom: 0, maxZoom: 14, tilePx: 512, bytes: 1024)
        let viewModel = MapViewModel(initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        XCTAssertTrue(viewModel.selectMapSource(pdf))
        XCTAssertTrue(viewModel.mapSource === pdf)
        XCTAssertNotNil(PDFSessionStore.load()?.bake)

        XCTAssertTrue(viewModel.deleteRetainedImportedMap(pdf))
        XCTAssertTrue(viewModel.mapSource is OnlineRasterBasemapSource)
        XCTAssertNil(viewModel.mapSelectionPersistenceIssue)
        try assertNothingLeft(for: pdf, "no bake running")
        // and a relaunch has nothing to bring back
        let relaunched = MapViewModel(initialMapSource: OnlineRasterBasemapSource(.osmStreet))
        XCTAssertFalse(relaunched.restoreActiveMapSelection() is PDFMapSource)
        try assertNothingLeft(for: pdf, "after relaunch")
    }

    /// same with a real bake mid run: it is stopped, its partial goes, nothing is published
    func testDeleteMapDuringABakeLeavesNothingBehind() throws {
        let pdf = try importedGeoPDF()
        let bake = PDFBakeController.shared
        let oldFinal = bake.finalDirectory, oldGuard = bake.guardStore
        bake.finalDirectory = generatedDir
        bake.guardStore = PDFRenderGuard(url: root.appendingPathComponent("bake-guard.json"))
        defer {
            bake.cancel()
            bake.finalDirectory = oldFinal
            bake.guardStore = oldGuard
        }
        let viewModel = MapViewModel(initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        XCTAssertTrue(viewModel.selectMapSource(pdf))
        bake.prepare(pdf: pdf, runtime: nil)
        waitUntil("estimate", timeout: 60) { bake.state != .estimating }
        guard case .confirming(let p) = bake.state, let top = p.options.last else {
            return XCTFail("estimate ended in \(bake.state)")
        }
        let partialsBefore = Set(files(in: PDFBakeWorker.workDirectory))
        bake.start(maxZoom: top.maxZoom)
        XCTAssertTrue(bake.isRunning)
        // let it get going so theres a partial on disk
        waitUntil("partial", timeout: 30) { Set(self.files(in: PDFBakeWorker.workDirectory)) != partialsBefore }

        XCTAssertTrue(viewModel.deleteRetainedImportedMap(pdf))
        XCTAssertFalse(bake.isRunning)
        XCTAssertNil(viewModel.mapSelectionPersistenceIssue)
        // the bake thread notices the cancel and drops its partial
        waitUntil("partial gone", timeout: 30) {
            Set(self.files(in: PDFBakeWorker.workDirectory)).isSubset(of: partialsBefore)
        }
        RunLoop.main.run(until: Date().addingTimeInterval(0.5))
        try assertNothingLeft(for: pdf, "bake running")
        XCTAssertNil(pdf.bake)
    }

    /// OD3-R3-1 root cause: clear() dropped our session and still read a stale
    /// one back. The delete has to fail loudly (issue kept for Retry) and roll
    /// BOTH stores back, so the library points at the real file and not the
    /// stale session. Once the stale copy is gone Retry finishes the job
    func testFailedDeleteRollsTheSessionBackAndRetryFinishesIt() throws {
        let suite = "ActiveMapSelectionStoreTests.shadow.\(UUID().uuidString)"
        let shadowed = ShadowedDefaults(suiteName: suite)!
        defer { shadowed.removePersistentDomain(forName: suite) }
        PDFSessionStore.defaultsProvider = { shadowed }
        // the stale copy: a sealed session for some other file thats long gone
        let stale = try importedGeoPDF()
        XCTAssertTrue(PDFSessionStore.save(stale))
        let staleBytes = try XCTUnwrap(shadowed.data(forKey: "active_pdf_v1"))
        try FileManager.default.removeItem(at: stale.url)
        XCTAssertTrue(PDFSessionStore.clear())
        shadowed.shadow["active_pdf_v1"] = staleBytes

        let pdf = try importedGeoPDF()
        let viewModel = MapViewModel(initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        XCTAssertTrue(viewModel.selectMapSource(pdf))
        XCTAssertTrue(viewModel.selectOnlineBasemap(.osmTopo))

        XCTAssertFalse(viewModel.deleteRetainedImportedMap(pdf))
        XCTAssertNotNil(viewModel.mapSelectionPersistenceIssue, "a failed delete is reported")
        XCTAssertTrue(FileManager.default.fileExists(atPath: pdf.url.path), "nothing deleted on the way out")
        let kept = try XCTUnwrap(PDFSessionStore.load())
        XCTAssertEqual(kept.url.lastPathComponent, pdf.url.lastPathComponent, "the real session is back")
        XCTAssertFalse(kept.storedFileUnavailable)
        guard case .restored(let entry as PDFMapSource) = ActiveMapSelectionStore.restoreRetained() else {
            return XCTFail("the saved entry is kept for Retry")
        }
        XCTAssertEqual(entry.url.lastPathComponent, pdf.url.lastPathComponent)

        // still stale: Retry fails the same way, again loudly
        XCTAssertFalse(viewModel.retryMapSelectionPersistence())
        XCTAssertNotNil(viewModel.mapSelectionPersistenceIssue)
        XCTAssertTrue(FileManager.default.fileExists(atPath: pdf.url.path))

        shadowed.shadow = [:]
        XCTAssertTrue(viewModel.retryMapSelectionPersistence())
        XCTAssertNil(viewModel.mapSelectionPersistenceIssue)
        try assertNothingLeft(for: pdf, "after retry")
    }

    /// R3-2: the launch restore sweeps bakes nobody names, even with the PDF
    /// missing (the full reconcile refuses then). Locked session: no sweep
    func testLaunchSweepsUnreferencedBakesOnlyWhenTheSessionReads() throws {
        let pdf = try importedGeoPDF()
        try FileManager.default.createDirectory(at: generatedDir, withIntermediateDirectories: true)
        let named = "tacmap-bake-\(UUID().uuidString).mbtiles"
        let orphan = "tacmap-bake-\(UUID().uuidString).mbtiles"
        for n in [named, orphan, orphan + "-shm"] { try Data([1]).write(to: generatedDir.appendingPathComponent(n)) }
        pdf.bake = PDFBakeRecord(fileName: named, bakeKey: String(repeating: "e", count: 64),
                                 minZoom: 0, maxZoom: 14, tilePx: 512, bytes: 1)
        XCTAssertTrue(PDFSessionStore.save(pdf))
        XCTAssertTrue(ActiveMapSelectionStore.save(pdf))
        try FileManager.default.removeItem(at: pdf.url)

        // locked: the restore cant read the session, nothing is touched
        SafeStore.keyProvider = { throw CocoaError(.fileReadNoPermission) }
        let locked = MapViewModel(initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        _ = locked.restoreActiveMapSelection()
        XCTAssertEqual(files(in: generatedDir), [named, orphan, orphan + "-shm"].sorted())

        SafeStore.keyProvider = { [testKey] in testKey }
        let viewModel = MapViewModel(initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        let restored = try XCTUnwrap(viewModel.restoreActiveMapSelection() as? PDFMapSource)
        XCTAssertTrue(restored.storedFileUnavailable)
        XCTAssertEqual(files(in: generatedDir), [named], "only the orphan and its sidecar go")
    }

    /// F6 (Android parity): no session record while the selector has a PDF
    /// active or retained, or cant be read, is not "names nothing", the sweep
    /// stays off. Only an online-only selector with no record lets it run
    func testSweepTreatsAMissingSessionForAPossiblePDFAsUnreadable() throws {
        let pdf = try importedGeoPDF()
        try FileManager.default.createDirectory(at: generatedDir, withIntermediateDirectories: true)
        let orphan = generatedDir.appendingPathComponent("tacmap-bake-\(UUID().uuidString).mbtiles")
        try Data([1]).write(to: orphan)
        let noRecord = { PDFSessionStore.defaultsProvider().removeObject(forKey: "active_pdf_v1") }
        func assertSkipped(_ what: String, line: UInt = #line) {
            noRecord()
            XCTAssertEqual(PDFSessionStore.storedBakeForSweep(), .unreadable, what, line: line)
            XCTAssertFalse(PDFBakeController.sweepUnreferencedBakes(in: generatedDir), what, line: line)
            XCTAssertTrue(FileManager.default.fileExists(atPath: orphan.path), what, line: line)
        }

        // active PDF (and retained)
        XCTAssertTrue(PDFSessionStore.save(pdf))
        XCTAssertTrue(ActiveMapSelectionStore.save(pdf))
        XCTAssertEqual(ActiveMapSelectionStore.mayHavePDF(), true)
        assertSkipped("active PDF")

        // retained only, an online map in front
        XCTAssertTrue(ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmTopo)))
        XCTAssertEqual(ActiveMapSelectionStore.mayHavePDF(), true)
        assertSkipped("retained-only PDF")

        // selector unavailable (locked): might be a PDF
        SafeStore.keyProvider = { throw CocoaError(.fileReadNoPermission) }
        XCTAssertNil(ActiveMapSelectionStore.mayHavePDF())
        assertSkipped("selector locked")
        SafeStore.keyProvider = { [testKey] in testKey }

        // selector unavailable (corrupt)
        let selection = ActiveMapSelectionStore.storageURLProvider()
        let good = try Data(contentsOf: selection)
        try Data("not sealed".utf8).write(to: selection)
        XCTAssertNil(ActiveMapSelectionStore.mayHavePDF())
        // SafeStore quarantined it on that read, the next one finds no file. still unknown
        assertSkipped("selector corrupt")
        XCTAssertNil(ActiveMapSelectionStore.mayHavePDF(), "quarantined this launch")
        try good.write(to: selection)

        // online only, no record: names nothing, the sweep runs
        XCTAssertTrue(ActiveMapSelectionStore.save(OnlineRasterBasemapSource(.osmTopo), clearRetained: true))
        XCTAssertEqual(ActiveMapSelectionStore.mayHavePDF(), false)
        noRecord()
        XCTAssertEqual(PDFSessionStore.storedBakeForSweep(), .read(fileName: nil))
        XCTAssertTrue(PDFBakeController.sweepUnreferencedBakes(in: generatedDir))
        XCTAssertFalse(FileManager.default.fileExists(atPath: orphan.path))
    }

    /// the reconcile keep set and the sweep agree on an invalid bake record:
    /// neither keeps the file it names
    func testInvalidBakeRecordIsIgnoredByRestoreReconcileAndSweep() throws {
        let pdf = try importedGeoPDF()
        try FileManager.default.createDirectory(at: generatedDir, withIntermediateDirectories: true)
        let name = "tacmap-bake-\(UUID().uuidString).mbtiles"
        let file = generatedDir.appendingPathComponent(name)
        try makeMinimalMBTiles(at: file)
        // a key no bake ever has, validBake says no
        let bad = PDFBakeRecord(fileName: name, bakeKey: "zz", minZoom: 0, maxZoom: 14, tilePx: 512, bytes: 1)
        XCTAssertFalse(PDFSessionStore.validBake(bad))
        pdf.bake = bad
        XCTAssertTrue(PDFSessionStore.save(pdf), "save drops an invalid bake rather than refusing")
        XCTAssertTrue(ActiveMapSelectionStore.save(pdf))
        XCTAssertNil(PDFSessionStore.load()?.bake, "restore drops it")
        XCTAssertEqual(PDFSessionStore.storedBakeForSweep(), .read(fileName: nil), "the sweep names nothing")
        XCTAssertTrue(ActiveMapSelectionStore.reconcileManagedImportedMapFiles())
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path), "the keep set didnt keep it either")
        XCTAssertTrue(FileManager.default.fileExists(atPath: pdf.url.path))
    }

    /// F5: two Try Agains overlap and the older one's check lands last. it must
    /// not overwrite what the newer (on screen) one found
    func testOverlappingTryAgainsKeepTheNewestFileVerdict() throws {
        let pdf = try importedGeoPDF()
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
        // let its main hop land
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
        XCTAssertFalse(pdf.storedFileUnavailable, "the stale verdict was dropped")
        XCTAssertEqual(runtime.status, .ready)
        runtime.reset()
    }

    /// F1: the alert host decisions
    func testMapIssueAlertGate() {
        typealias Gate = MapSelectionIssueAlertGate
        let a = MapSelectionPersistenceIssue(id: UUID(), pendingMessage: Messages.displayThePdfMapCouldNotBeSavedForRelaunchMessage())
        let b = MapSelectionPersistenceIssue(id: UUID(), pendingMessage: Messages.displayThePdfMapCouldNotBeSavedForRelaunchMessage())
        // shows only the issue the key was made for, only while on top
        XCTAssertEqual(Gate.issueToShow(for: .init(issueID: a.id, isActive: true), current: a), a)
        XCTAssertNil(Gate.issueToShow(for: .init(issueID: a.id, isActive: false), current: a))
        XCTAssertNil(Gate.issueToShow(for: .init(issueID: a.id, isActive: true), current: b), "replaced meanwhile")
        XCTAssertNil(Gate.issueToShow(for: .init(issueID: a.id, isActive: true), current: nil), "dismissed meanwhile")
        // either half changing is a new key, so the pending show is cancelled
        XCTAssertNotEqual(Gate.Key(issueID: a.id, isActive: true), Gate.Key(issueID: a.id, isActive: false))
        XCTAssertNotEqual(Gate.Key(issueID: a.id, isActive: true), Gate.Key(issueID: b.id, isActive: true))
        // root stands down for any presentation on top
        XCTAssertTrue(Gate.rootHostIsActive(presentationsOnTop: [false, false]))
        XCTAssertFalse(Gate.rootHostIsActive(presentationsOnTop: [false, true, false]))
        // a dropped presentation never loses the error, only Not Now does
        XCTAssertFalse(Gate.clearsIssue(.dismissedBySystem))
        XCTAssertTrue(Gate.clearsIssue(.notNow))
    }

    /// R3-3: Try Again re-checks the bytes even when nothing flagged the source
    func testTryAgainAlwaysRechecksTheStoredBytes() throws {
        let pdf = try importedGeoPDF()
        let original = try Data(contentsOf: pdf.url)
        XCTAssertFalse(pdf.storedFileUnavailable)
        let runtime = PDFMapRuntime()
        _ = runtime.tileSource(for: pdf, screenScale: 2)
        waitUntil("ready") { runtime.status == .ready }

        // swapped under the live map, then Try Again
        let blank = try XCTUnwrap(PDFTileRenderFixtureTests.testdataURL("geopdf/tacmap_render_blank.pdf"))
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
}
