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
    private var pdfDefaultsSuiteName: String!
    private var pdfFixtureURLs: [URL] = []

    override func setUp() {
        super.setUp()
        originalApplicationSupportProvider = ActiveMapSelectionStore.applicationSupportDirectoryProvider
        originalStorageProvider = ActiveMapSelectionStore.storageURLProvider
        originalImportedDirectoryProvider = ActiveMapSelectionStore.importedMapsDirectoryProvider
        originalPDFDefaultsProvider = PDFSessionStore.defaultsProvider
        originalPDFImportedDirectoryProvider = PDFSessionStore.importedMapsDirectoryProvider
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("active-map-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let selection = root.appendingPathComponent("active-map-selection.json")
        let imported = root.appendingPathComponent("ImportedMaps", isDirectory: true)
        try? FileManager.default.createDirectory(at: imported, withIntermediateDirectories: true)
        let applicationSupport = root!
        ActiveMapSelectionStore.applicationSupportDirectoryProvider = { applicationSupport }
        ActiveMapSelectionStore.storageURLProvider = { selection }
        ActiveMapSelectionStore.importedMapsDirectoryProvider = { imported }
        pdfDefaultsSuiteName = "ActiveMapSelectionStoreTests.\(UUID().uuidString)"
        let pdfDefaults = UserDefaults(suiteName: pdfDefaultsSuiteName)!
        pdfDefaults.removePersistentDomain(forName: pdfDefaultsSuiteName)
        PDFSessionStore.defaultsProvider = { pdfDefaults }
        PDFSessionStore.importedMapsDirectoryProvider = { imported }
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
            return XCTFail("fresh install should keep the usable online default")
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

    func testPDFSessionSaveReportsFailureWhenSealingIsUnavailable() throws {
        let source = try makePersistablePDFSource()
        let key = testKey
        SafeStore.keyProvider = { throw DataKey.LockedError() }

        let saved = PDFSessionStore.save(source)

        SafeStore.keyProvider = { key }
        XCTAssertFalse(saved)
        XCTAssertNil(PDFSessionStore.load())
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
}
