import XCTest
import SQLite3
@testable import TacticalMaps

/// Tests the MBTiles reader against a generated sample database: metadata
/// parsing and the TMS↔XYZ row flip that's the easiest thing to get wrong.
final class MBTilesStoreTests: XCTestCase {

    private func makeSampleMBTiles() throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("sample-\(UUID().uuidString).mbtiles")
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &db), SQLITE_OK)
        defer { sqlite3_close(db) }
        let ddl = """
        CREATE TABLE metadata (name TEXT, value TEXT);
        CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);
        INSERT INTO metadata VALUES ('name','Sample'),('format','png'),('minzoom','0'),('maxzoom','1'),('bounds','-1.0,-2.0,3.0,4.0');
        """
        XCTAssertEqual(sqlite3_exec(db, ddl, nil, nil, nil), SQLITE_OK)
        // One tile at z=1, column=0, tms_row=0 with bytes DE AD BE EF.
        var stmt: OpaquePointer?
        XCTAssertEqual(sqlite3_prepare_v2(db, "INSERT INTO tiles VALUES (1,0,0,?)", -1, &stmt, nil), SQLITE_OK)
        let bytes: [UInt8] = [0xDE, 0xAD, 0xBE, 0xEF]
        bytes.withUnsafeBytes { raw in
            sqlite3_bind_blob(stmt, 1, raw.baseAddress, Int32(raw.count), nil)
            XCTAssertEqual(sqlite3_step(stmt), SQLITE_DONE)
        }
        sqlite3_finalize(stmt)
        return url
    }

    func testReadsMetadataAndTile() throws {
        let url = try makeSampleMBTiles()
        let store = try XCTUnwrap(MBTilesStore(url: url))
        XCTAssertEqual(store.metadata.name, "Sample")
        XCTAssertEqual(store.metadata.format, "png")
        XCTAssertEqual(store.metadata.minZoom, 0)
        XCTAssertEqual(store.metadata.maxZoom, 1)
        let b = try XCTUnwrap(store.metadata.bounds)
        XCTAssertEqual(b.minLon, -1.0, accuracy: 1e-9)
        XCTAssertEqual(b.minLat, -2.0, accuracy: 1e-9)
        XCTAssertEqual(b.maxLon, 3.0, accuracy: 1e-9)
        XCTAssertEqual(b.maxLat, 4.0, accuracy: 1e-9)

        // Stored tms_row 0 at z=1 maps to XYZ y = (2^1 - 1) - 0 = 1.
        XCTAssertEqual(store.tileData(z: 1, x: 0, y: 1), Data([0xDE, 0xAD, 0xBE, 0xEF]))
        // XYZ y=0 would be tms_row 1, which we didn't insert.
        XCTAssertNil(store.tileData(z: 1, x: 0, y: 0))
        XCTAssertNil(store.tileData(z: 9, x: 9, y: 9))
    }

    func testImportedMapFileCopierCopiesSameNamedFilesToUniqueDestinations() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("import-copy-\(UUID().uuidString)", isDirectory: true)
        let sourceA = root.appendingPathComponent("a", isDirectory: true)
        let sourceB = root.appendingPathComponent("b", isDirectory: true)
        let docs = root.appendingPathComponent("Documents", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }

        try FileManager.default.createDirectory(at: sourceA, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: sourceB, withIntermediateDirectories: true)
        let first = sourceA.appendingPathComponent("training.mbtiles")
        let second = sourceB.appendingPathComponent("training.mbtiles")
        try Data("first".utf8).write(to: first)
        try Data("second".utf8).write(to: second)

        let firstDest = try ImportedMapFileCopier.copy(first, into: docs)
        let secondDest = try ImportedMapFileCopier.copy(second, into: docs)

        XCTAssertNotEqual(firstDest, secondDest)
        XCTAssertEqual(firstDest.lastPathComponent, "training.mbtiles")
        XCTAssertEqual(secondDest.lastPathComponent, "training-1.mbtiles")
        XCTAssertEqual(try Data(contentsOf: firstDest), Data("first".utf8))
        XCTAssertEqual(try Data(contentsOf: secondDest), Data("second".utf8))
    }
}
