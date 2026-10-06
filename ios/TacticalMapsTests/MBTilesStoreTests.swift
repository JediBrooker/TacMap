import XCTest
import SQLite3
@testable import TacticalMaps

/// Tests MBTiles reader against a generated sample db: metadata parsing
/// and the TMS/XYZ row flip thats the easiest thing to get wrong.
final class MBTilesStoreTests: XCTestCase {

    private func execute(_ sql: String, on url: URL) throws {
        var db: OpaquePointer?
        guard sqlite3_open(url.path, &db) == SQLITE_OK else {
            throw CocoaError(.fileWriteUnknown)
        }
        defer { sqlite3_close(db) }
        guard sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK else {
            throw CocoaError(.fileWriteUnknown)
        }
    }

    private func sampleWithTileExpression(_ expression: String) throws -> URL {
        let url = try makeSampleMBTiles()
        try execute("UPDATE tiles SET tile_data=\(expression)", on: url)
        return url
    }

    private func assertMetadataRejected(
        _ update: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) throws {
        let url = try makeSampleMBTiles()
        defer { try? FileManager.default.removeItem(at: url) }
        try execute(update, on: url)
        XCTAssertNil(MBTilesStore(url: url), file: file, line: line)
    }

    private func makeSampleMBTiles(metadataValueType: String = "TEXT", tileZoomType: String = "INTEGER") throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("sample-\(UUID().uuidString).mbtiles")
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &db), SQLITE_OK)
        defer { sqlite3_close(db) }
        let ddl = """
        CREATE TABLE metadata (name TEXT, value \(metadataValueType));
        CREATE TABLE tiles (zoom_level \(tileZoomType), tile_column INTEGER, tile_row INTEGER, tile_data BLOB);
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

    func testCloseForDeletionPreventsLateReopenAndAllowsBackingRemoval() throws {
        let url = try makeSampleMBTiles()
        let store = try XCTUnwrap(MBTilesStore(url: url))

        store.closeForDeletion()
        XCTAssertNil(store.tileData(z: 0, x: 0, y: 0),
                     "late renderer callbacks must not reopen a retired database")
        XCTAssertNoThrow(try FileManager.default.removeItem(at: url))
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
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
        XCTAssertNil(store.tileData(z: 1, x: -1, y: 0))
        XCTAssertNil(store.tileData(z: 1, x: 0, y: 2))
        XCTAssertNil(store.tileData(z: 31, x: 0, y: 0))
    }

    func testExactlyFourMiBTileIsAccepted() throws {
        let url = try sampleWithTileExpression("zeroblob(\(MBTilesStore.maximumTileBytes))")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = try XCTUnwrap(MBTilesStore(url: url))

        let tile = try XCTUnwrap(store.tileData(z: 1, x: 0, y: 1))

        XCTAssertEqual(tile.count, MBTilesStore.maximumTileBytes)
        XCTAssertEqual(tile.first, 0)
        XCTAssertEqual(tile.last, 0)
#if DEBUG
        XCTAssertEqual(store.payloadQueryCountForTesting, 1)
#endif
    }

    func testFourMiBPlusOneIsRejectedBeforePayloadQuery() throws {
        let url = try sampleWithTileExpression(
            "zeroblob(\(MBTilesStore.maximumTileBytes + 1))"
        )
        defer { try? FileManager.default.removeItem(at: url) }
        let store = try XCTUnwrap(MBTilesStore(url: url))

        XCTAssertNil(store.tileData(z: 1, x: 0, y: 1))
#if DEBUG
        XCTAssertEqual(
            store.payloadQueryCountForTesting,
            0,
            "an oversized length preflight must not issue the tile_data query"
        )
#endif
    }

    func testSparseHugeZeroBlobIsRejectedWithoutPayloadMaterialization() throws {
        let hugeByteCount = MBTilesStore.maximumTileBytes * 16
        let url = try sampleWithTileExpression("zeroblob(\(hugeByteCount))")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = try XCTUnwrap(MBTilesStore(url: url))

        for _ in 0..<8 {
            XCTAssertNil(store.tileData(z: 1, x: 0, y: 1))
        }
#if DEBUG
        XCTAssertEqual(
            store.payloadQueryCountForTesting,
            0,
            "the zero-filled 64 MiB BLOB is inspected only through length(tile_data)"
        )
#endif
    }

    func testZeroNullAndNonBlobTilesFailClosed() throws {
        for expression in ["zeroblob(0)", "NULL", "'not-a-blob'"] {
            let url = try sampleWithTileExpression(expression)
            defer { try? FileManager.default.removeItem(at: url) }
            let store = try XCTUnwrap(MBTilesStore(url: url))

            XCTAssertNil(store.tileData(z: 1, x: 0, y: 1), expression)
        }
    }

    func testHugeMalformedTextTileIsRejectedBeforePayloadQuery() throws {
        let hugeByteCount = MBTilesStore.maximumTileBytes * 16
        let url = try sampleWithTileExpression(
            "CAST(zeroblob(\(hugeByteCount)) AS TEXT)"
        )
        defer { try? FileManager.default.removeItem(at: url) }
        let store = try XCTUnwrap(MBTilesStore(url: url))

        XCTAssertNil(store.tileData(z: 1, x: 0, y: 1))
#if DEBUG
        XCTAssertEqual(
            store.payloadQueryCountForTesting,
            0,
            "typeof(tile_data) must reject a huge TEXT value before length or payload access"
        )
#endif
    }

    func testRepeatedConcurrentReadsRemainBoundedAndStable() throws {
        let url = try makeSampleMBTiles()
        defer { try? FileManager.default.removeItem(at: url) }
        let store = try XCTUnwrap(MBTilesStore(url: url))
        let expected = Data([0xDE, 0xAD, 0xBE, 0xEF])

        DispatchQueue.concurrentPerform(iterations: 128) { _ in
            XCTAssertEqual(store.tileData(z: 1, x: 0, y: 1), expected)
        }
#if DEBUG
        XCTAssertEqual(store.payloadQueryCountForTesting, 128)
#endif
    }

    func testNormalColdRestoreReopensMetadataAndTiles() throws {
        let url = try makeSampleMBTiles()
        defer { try? FileManager.default.removeItem(at: url) }

        do {
            let firstStore = try XCTUnwrap(MBTilesStore(url: url))
            XCTAssertEqual(firstStore.tileData(z: 1, x: 0, y: 1), Data([0xDE, 0xAD, 0xBE, 0xEF]))
        }

        let restoredStore = try XCTUnwrap(MBTilesStore(url: url))
        XCTAssertEqual(restoredStore.metadata.name, "Sample")
        XCTAssertEqual(restoredStore.metadata.minZoom, 0)
        XCTAssertEqual(restoredStore.metadata.maxZoom, 1)
        XCTAssertEqual(restoredStore.tileData(z: 1, x: 0, y: 1), Data([0xDE, 0xAD, 0xBE, 0xEF]))
    }

    func testMetadataTextIsBoundedAndNormalizedLikeAndroid() throws {
        let url = try makeSampleMBTiles()
        defer { try? FileManager.default.removeItem(at: url) }
        let longName = String(repeating: "N", count: 10_000)
        let longFormat = String(repeating: "P", count: 10_000)
        try execute(
            "UPDATE metadata SET value='\(longName)' WHERE name='name'; " +
            "UPDATE metadata SET value='\(longFormat)' WHERE name='format'",
            on: url
        )

        let store = try XCTUnwrap(MBTilesStore(url: url))
        XCTAssertEqual(store.metadata.name, String(repeating: "N", count: 128))
        XCTAssertEqual(store.metadata.format, String(repeating: "p", count: 32))
    }

    func testMetadataRowAdmissionAccepts64AndRejects65() throws {
        let url = try makeSampleMBTiles()
        defer { try? FileManager.default.removeItem(at: url) }
        // Five standard rows plus 59 extension rows are exactly the existing
        // reader admission boundary; a 65th must fail before publication.
        try execute("""
            WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<59)
            INSERT INTO metadata SELECT 'extension_' || i, 'ignored' FROM n;
            """, on: url)
        let accepted = try XCTUnwrap(MBTilesStore(url: url))
        XCTAssertEqual(accepted.metadata.name, "Sample")
        XCTAssertEqual(accepted.tileData(z: 1, x: 0, y: 1), Data([0xDE, 0xAD, 0xBE, 0xEF]))
        accepted.closeForDeletion()
        try execute("INSERT INTO metadata VALUES ('extension_60','ignored')", on: url)
        XCTAssertNil(MBTilesStore(url: url))
    }

    func testUnknownHugeMetadataIsIgnoredWhileKnownValuesRemainBounded() throws {
        let url = try makeSampleMBTiles()
        defer { try? FileManager.default.removeItem(at: url) }
        // SQLite stores a large unknown value without constructing it in Swift.
        // The reader only classifies the key and never requests its payload.
        // 8,000,000 bytes, under the record probe's cap (s15.2)
        try execute("INSERT INTO metadata VALUES ('vendor_extension', CAST(zeroblob(8000000) AS TEXT))", on: url)
        let store = try XCTUnwrap(MBTilesStore(url: url))
        XCTAssertEqual(store.metadata.name, "Sample")
        XCTAssertEqual(store.metadata.minZoom, 0)
        XCTAssertEqual(store.metadata.maxZoom, 1)
        XCTAssertNotNil(store.tileData(z: 1, x: 0, y: 1))
        store.closeForDeletion()
        // 16 MiB was ignored the same way up to 3.0.2. 3.0.3 refuses a row over
        // 2 x maxValueBytes off its record size, before sqlite reads any of it
        try execute("UPDATE metadata SET value = CAST(zeroblob(16777216) AS TEXT) WHERE name = 'vendor_extension'",
                    on: url)
        XCTAssertNil(MBTilesStore(url: url))
    }

    private func sqliteScalarText(_ query: String, on url: URL) throws -> String {
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open_v2(url.path, &db, SQLITE_OPEN_READONLY, nil), SQLITE_OK)
        defer { sqlite3_close(db) }
        var statement: OpaquePointer?
        defer { sqlite3_finalize(statement) }
        XCTAssertEqual(sqlite3_prepare_v2(db, query, -1, &statement, nil), SQLITE_OK)
        XCTAssertEqual(sqlite3_step(statement), SQLITE_ROW)
        return String(cString: try XCTUnwrap(sqlite3_column_text(statement, 0)))
    }

    private func metadataAdmissionFixture() throws -> [String: Any] {
        let url = try XCTUnwrap(PDFTileRenderFixtureTests.testdataURL("import_limits.json"))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        return try XCTUnwrap(json["mbtilesMetadataAdmission"] as? [String: Any])
    }

    private func sqlText(_ bytes: Data) -> String {
        "CAST(X'" + bytes.map { String(format: "%02x", $0) }.joined() + "' AS TEXT)"
    }

    func testSharedMetadataAdmissionCasesUseActualSQLiteReader() throws {
        try runSharedMetadataAdmissionCases(variant: nil)
    }

    private func runSharedMetadataAdmissionCases(variant: [String: Any]?) throws {
        let fixture = try metadataAdmissionFixture()
        XCTAssertEqual(fixture["maxRows"] as? Int, 64)
        let cases = try XCTUnwrap(fixture["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 12)
        let variantSQL = variant?["sql"] as? [String] ?? []
        let suffix = (variant?["id"] as? String).map { "/" + $0 } ?? ""
        for vector in cases {
            let id = try XCTUnwrap(vector["id"] as? String) + suffix
            let url = try makeSampleMBTiles(metadataValueType: "")
            defer { try? FileManager.default.removeItem(at: url) }
            try execute("DELETE FROM metadata", on: url)
            if let count = vector["unknownRows"] as? Int {
                try execute("""
                    WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<\(count))
                    INSERT INTO metadata SELECT 'extension_' || i, 'ignored' FROM n;
                    """, on: url)
            }
            for row in try XCTUnwrap(vector["rows"] as? [[String: Any]]) {
                let key = try XCTUnwrap(row["key"] as? String)
                let value: String
                if let integer = row["integerValue"] as? Int { value = String(integer) }
                else if let hex = row["textBytesHex"] as? String { value = "CAST(X'" + hex + "' AS TEXT)" }
                else if let repeated = row["valueRepeat"] as? [Any] {
                    let char = try XCTUnwrap(repeated.first as? String)
                    let count = try XCTUnwrap(repeated.last as? Int)
                    value = sqlText(Data(String(repeating: char, count: count).utf8))
                } else { value = sqlText(Data(try XCTUnwrap(row["value"] as? String).utf8)) }
                try execute("INSERT INTO metadata VALUES (\(sqlText(Data(key.utf8))),\(value))", on: url)
                XCTAssertEqual(try sqliteScalarText("SELECT typeof(value) FROM metadata ORDER BY rowid DESC LIMIT 1", on: url),
                    row["integerValue"] == nil ? "text" : "integer", id)
            }
            for statement in variantSQL { try execute(statement, on: url) }
            let store = MBTilesStore(url: url)
            XCTAssertEqual(store != nil, try XCTUnwrap(vector["accepted"] as? Bool), id)
            if let name = vector["name"] as? String { XCTAssertEqual(store?.metadata.name, name, id) }
            if let format = vector["format"] as? String { XCTAssertEqual(store?.metadata.format, format, id) }
        }
    }

    func testSharedTileZoomStorageClassesUseActualSQLiteAggregate() throws {
        try runSharedTileZoomCases(variant: nil)
    }

    private func runSharedTileZoomCases(variant: [String: Any]?) throws {
        let fixture = try metadataAdmissionFixture()
        XCTAssertEqual(fixture["tileZoomMin"] as? Int, 0)
        XCTAssertEqual(fixture["tileZoomMax"] as? Int, MBTilesStore.maximumZoom)
        let cases = try XCTUnwrap(fixture["tileZoomCases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 7)
        let variantSQL = variant?["sql"] as? [String] ?? []
        let suffix = (variant?["id"] as? String).map { "/" + $0 } ?? ""
        for vector in cases {
            let id = try XCTUnwrap(vector["id"] as? String) + suffix
            let url = try makeSampleMBTiles(tileZoomType: "")
            defer { try? FileManager.default.removeItem(at: url) }
            let type = try XCTUnwrap(vector["storageType"] as? String)
            let expression: String
            if type == "integer" { expression = String(try XCTUnwrap(vector["value"] as? Int)) }
            else if type == "real" { expression = "CAST(0 AS REAL)" }
            else if let repeated = vector["valueRepeat"] as? [Any] {
                expression = sqlText(Data(String(repeating: try XCTUnwrap(repeated.first as? String),
                    count: try XCTUnwrap(repeated.last as? Int)).utf8))
            } else { expression = sqlText(Data(try XCTUnwrap(vector["value"] as? String).utf8)) }
            try execute("DELETE FROM metadata; UPDATE tiles SET zoom_level=\(expression),tile_column=0,tile_row=0", on: url)
            XCTAssertEqual(try sqliteScalarText("SELECT typeof(zoom_level) FROM tiles LIMIT 1", on: url), type, id)
            for statement in variantSQL { try execute(statement, on: url) }
            let store = MBTilesStore(url: url)
            let accepted = try XCTUnwrap(vector["accepted"] as? Bool)
            XCTAssertEqual(store != nil, accepted, id)
            if accepted {
                XCTAssertEqual(store?.metadata.minZoom, vector["value"] as? Int, id)
                XCTAssertEqual(store?.metadata.maxZoom, vector["value"] as? Int, id)
            }
        }
    }

    func testConsumedBakeExtensionsRejectNulTailAndDuplicatesWithoutRejectingMap() throws {
        let expectedKey = String(repeating: "a", count: 64)
        for hostile in ["nulTail", "duplicate"] {
            let url = try makeSampleMBTiles()
            defer { try? FileManager.default.removeItem(at: url) }
            try execute("INSERT INTO metadata VALUES ('tacmap_bake_key',\(sqlText(Data(expectedKey.utf8)))),('tacmap_tile_px','768')", on: url)
            if hostile == "nulTail" {
                let prefix = sqlText(Data((expectedKey + "\0").utf8))
                try execute("UPDATE metadata SET value=\(prefix) || replace(hex(zeroblob(1048576)),'00','j') WHERE name='tacmap_bake_key'", on: url)
            } else {
                try execute("INSERT INTO metadata VALUES ('tacmap_bake_key',\(sqlText(Data(expectedKey.utf8))))", on: url)
            }
            let ordinary = try XCTUnwrap(MBTilesStore(url: url), hostile)
            XCTAssertEqual(ordinary.metadata.name, "Sample", hostile)
            XCTAssertNil(ordinary.extensionMetadata("tacmap_bake_key"), hostile)
            XCTAssertNil(PDFBakeReader(url: url, expectedKey: expectedKey, tilePx: 768), hostile)
        }
    }

    func testSharedConsumedExtensionCasesUseActualLazyReader() throws {
        try runSharedConsumedExtensionCases(variant: nil)
    }

    private func runSharedConsumedExtensionCases(variant: [String: Any]?) throws {
        let fixture = try metadataAdmissionFixture()
        let cases = try XCTUnwrap(fixture["extensionCases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 9)
        let variantSQL = variant?["sql"] as? [String] ?? []
        let suffix = (variant?["id"] as? String).map { "/" + $0 } ?? ""
        for vector in cases {
            let id = try XCTUnwrap(vector["id"] as? String) + suffix
            let url = try makeSampleMBTiles(metadataValueType: "")
            defer { try? FileManager.default.removeItem(at: url) }
            try execute("DELETE FROM metadata", on: url)
            for row in try XCTUnwrap(vector["rows"] as? [[String: Any]]) {
                let key = try XCTUnwrap(row["key"] as? String)
                let value: String
                if let integer = row["integerValue"] as? Int { value = String(integer) }
                else if let hex = row["textBytesHex"] as? String { value = "CAST(X'" + hex + "' AS TEXT)" }
                else if let repeated = row["valueRepeat"] as? [Any] {
                    value = sqlText(Data(String(repeating: try XCTUnwrap(repeated.first as? String),
                        count: try XCTUnwrap(repeated.last as? Int)).utf8))
                } else { value = sqlText(Data(try XCTUnwrap(row["value"] as? String).utf8)) }
                try execute("INSERT INTO metadata VALUES (\(sqlText(Data(key.utf8))),\(value))", on: url)
                XCTAssertEqual(try sqliteScalarText("SELECT typeof(value) FROM metadata ORDER BY rowid DESC LIMIT 1", on: url),
                    row["integerValue"] == nil ? "text" : "integer", id)
            }
            for statement in variantSQL { try execute(statement, on: url) }
            let store = MBTilesStore(url: url)
            XCTAssertEqual(store != nil, try XCTUnwrap(vector["mapAccepted"] as? Bool), id)
            XCTAssertEqual(store?.extensionMetadata(try XCTUnwrap(vector["requestedKey"] as? String)),
                vector["expected"] as? String, id)
        }
    }

    // A4 (3.0.1): tiles and metadata can be views. These used to be refused
    // outright by the old table-only check.

    private func relationVariant(_ id: String) throws -> [String: Any] {
        let variants = try XCTUnwrap(try metadataAdmissionFixture()["relationVariants"] as? [[String: Any]])
        return try XCTUnwrap(variants.first { $0["id"] as? String == id }, id)
    }

    func testSharedRelationVariantsGiveTheTableVerdicts() throws {
        let fixture = try metadataAdmissionFixture()
        let ids = try XCTUnwrap(fixture["relationVariants"] as? [[String: Any]]).compactMap { $0["id"] as? String }
        XCTAssertEqual(ids, ["metadataView", "tilesView", "bothViews"])
        for id in ["metadataView", "bothViews"] {
            try runSharedMetadataAdmissionCases(variant: try relationVariant(id))
            try runSharedConsumedExtensionCases(variant: try relationVariant(id))
        }
        for id in ["tilesView", "bothViews"] {
            try runSharedTileZoomCases(variant: try relationVariant(id))
        }
    }

    /// sql[] in order on one ordinary connection, like the generator builds
    /// them. one connection so a leading PRAGMA page_size / auto_vacuum sticks
    private func makePack(_ statements: [String]) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("relation-\(UUID().uuidString).mbtiles")
        var db: OpaquePointer?
        guard sqlite3_open(url.path, &db) == SQLITE_OK else {
            sqlite3_close(db)
            throw CocoaError(.fileWriteUnknown)
        }
        defer { sqlite3_close(db) }
        for statement in statements {
            guard sqlite3_exec(db, statement, nil, nil, nil) == SQLITE_OK else {
                throw CocoaError(.fileWriteUnknown)
            }
        }
        return url
    }

    /// a relationCases row as a file. the ones that write sqlite_master come as
    /// packBase64, our sqlite has SQLITE_DBCONFIG_DEFENSIVE on so writable_schema
    /// is a no-op here and their sql[] can't be replayed
    private func makeRelationPack(_ vector: [String: Any], _ id: String) throws -> URL {
        guard let packed = vector["packBase64"] as? String else {
            return try makePack(try XCTUnwrap(vector["sql"] as? [String], id))
        }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("relation-\(UUID().uuidString).mbtiles")
        try XCTUnwrap(Data(base64Encoded: packed), id).write(to: url)
        return url
    }

    private func hex(_ data: Data?) -> String? {
        data.map { $0.map { String(format: "%02x", $0) }.joined() }
    }

    func testSharedRelationCasesUseActualSQLiteReader() throws {
        let fixture = try metadataAdmissionFixture()
        XCTAssertEqual(fixture["relationTypes"] as? [String], ["table", "view"])
        XCTAssertEqual(fixture["admissionBudgetMs"] as? Int, MBTilesStore.admissionBudgetMs)
        XCTAssertEqual(fixture["viewQueryBudgetMs"] as? Int, MBTilesStore.viewQueryBudgetMs)
        XCTAssertEqual(fixture["admissionBudgetAppliesTo"] as? String, "views")
        let cases = try XCTUnwrap(fixture["relationCases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 48)
        var seen: Set<String> = []
        for vector in cases {
            let id = try XCTUnwrap(vector["id"] as? String)
            // a row SQLite here can't even build (generated columns < 3.31)
            if let min = vector["minSqliteVersion"] as? String, sqlite3_libversion_number() < Self.versionNumber(min) {
                continue
            }
            seen.insert(id)
            let url = try makeRelationPack(vector, id)
            defer { try? FileManager.default.removeItem(at: url) }
            let expect = try XCTUnwrap(vector["expect"] as? [String: Any], id)
            let budget = vector["testBudgetMs"] as? Int ?? MBTilesStore.admissionBudgetMs

            let started = Date()
            let store = MBTilesStore(url: url, admissionBudgetMs: budget)
            // every refusal but the budget one is a schema read, no waiting on anything
            let limit: TimeInterval = vector["testBudgetMs"] != nil ? 10 : 5
            XCTAssertLessThan(Date().timeIntervalSince(started), limit, "\(id): admission has to come back quick")
            let accepted = try XCTUnwrap(expect["accepted"] as? Bool, id)
            XCTAssertEqual(store != nil, accepted, id)
            guard accepted, let store else { continue }

            XCTAssertEqual(store.metadata.minZoom, expect["minZoom"] as? Int, id)
            XCTAssertEqual(store.metadata.maxZoom, expect["maxZoom"] as? Int, id)
            XCTAssertEqual(store.metadata.name, expect["name"] as? String, id)
            XCTAssertEqual(store.metadata.format, expect["format"] as? String, id)
            // the prevalidated reader opens lazily and has to learn the
            // relation types itself, so probe both
            let lazy = MBTilesStore(prevalidatedURL: url, metadata: store.metadata)
            for probe in expect["tiles"] as? [[String: Any]] ?? [] {
                let z = try XCTUnwrap(probe["z"] as? Int), x = try XCTUnwrap(probe["x"] as? Int)
                let y = try XCTUnwrap(probe["y"] as? Int)
                let want = probe["hex"] as? String
                XCTAssertEqual(hex(store.tileData(z: z, x: x, y: y)), want, "\(id) \(z)/\(x)/\(y)")
                XCTAssertEqual(hex(lazy.tileData(z: z, x: x, y: y)), want, "\(id) lazy \(z)/\(x)/\(y)")
            }
            for (key, value) in expect["extensions"] as? [String: Any] ?? [:] {
                XCTAssertEqual(store.extensionMetadata(key), value as? String, "\(id) \(key)")
                XCTAssertEqual(lazy.extensionMetadata(key), value as? String, "\(id) lazy \(key)")
            }
            store.closeForDeletion()
            lazy.closeForDeletion()
        }
        XCTAssertTrue(seen.isSuperset(of: ["nodeMbtilesDedup", "bothViews", "tilesViewEndless", "tilesIsAnIndex",
                                           "viewDateTrigger", "martinNormalizedLeftJoin", "dedupNoIndexOversizedImage",
                                           "tableOversizedValues", "schemaStatementTooLong", "budgetUnindexedJoin",
                                           "tilesShadowedByCaseVariant", "metadataShadowedByCaseVariant",
                                           "tableRowKeepsIfNotExists", "baseRowNameLie",
                                           // SHADOW-PARITY-2, both opened in 3.0.1
                                           "gdal2mbtilesCommaJoin", "tippecanoeAndJoin",
                                           "commaJoinWhereCallsFunction",
                                           // 3.0.3 U2 record probe, 3.0.2 opened the three refused ones
                                           "metadataLargeRecordUnderProbeCap", "metadataRecordOverProbeCap",
                                           "metadataViewJoinBaseRows", "schemaTooManyObjects", "textTileData"]))
    }

    /// "3.31.0" -> 3031000, what sqlite3_libversion_number gives
    private static func versionNumber(_ s: String) -> Int32 {
        let p = s.split(separator: ".").compactMap { Int32($0) } + [0, 0, 0]
        return p[0] * 1_000_000 + p[1] * 1_000 + p[2]
    }

    // MARK: 3.0.2 SEC-1, no file SQL on a read

    private func relationCase(_ id: String) throws -> [String: Any] {
        let cases = try XCTUnwrap(try metadataAdmissionFixture()["relationCases"] as? [[String: Any]])
        return try XCTUnwrap(cases.first { $0["id"] as? String == id }, id)
    }

    func testConnectionCapsMatchTheSharedFixtureAndHoldOnEveryConnection() throws {
        let connection = try XCTUnwrap(try metadataAdmissionFixture()["connection"] as? [String: Any])
        XCTAssertEqual(connection["maxTileBytes"] as? Int, MBTilesStore.maximumTileBytes)
        XCTAssertEqual(connection["maxValueBytes"] as? Int, MBTilesStore.maximumValueBytes)
        XCTAssertEqual(connection["maxSchemaSqlBytes"] as? Int, MBTilesStore.maximumSchemaSQLBytes)
        let ios = try XCTUnwrap(connection["ios"] as? [String: Any])
        let limits = try XCTUnwrap(ios["sqlite3_limit"] as? [String: Int])
        XCTAssertEqual(limits, ["SQLITE_LIMIT_LENGTH": MBTilesStore.maximumValueBytes,
                                "SQLITE_LIMIT_SQL_LENGTH": MBTilesStore.maximumSchemaSQLBytes])
        XCTAssertTrue(ios["hardHeapLimit"] is NSNull, "no process wide heap limit on iOS")
        XCTAssertEqual(Self.versionNumber(try XCTUnwrap(connection["generatedColumnsMinSqlite"] as? String)),
                       MBTilesStore.generatedColumnsMinimumSQLite)
        let pragmas = try XCTUnwrap(connection["pragmas"] as? [String])
        XCTAssertTrue(pragmas.contains("automatic_index=OFF"))
        XCTAssertTrue(pragmas.contains { $0.hasPrefix("trusted_schema=OFF") })

        // the admission open and the lazy prevalidated open both run hardened
        let url = try makeSampleMBTiles()
        defer { try? FileManager.default.removeItem(at: url) }
        let admitted = try XCTUnwrap(MBTilesStore(url: url))
        let lazy = MBTilesStore(prevalidatedURL: url, metadata: admitted.metadata)
        for (label, store) in [("admission", admitted), ("lazy", lazy)] {
            let h = try XCTUnwrap(store.connectionHardeningForTesting(), label)
            XCTAssertEqual(Int(h.length), MBTilesStore.maximumValueBytes, label)
            XCTAssertEqual(Int(h.sqlLength), MBTilesStore.maximumSchemaSQLBytes, label)
            XCTAssertEqual(h.automaticIndex, 0, label)
            XCTAssertEqual(h.trustedSchema, 0, label)
        }
    }

    func testSharedViewShapeCasesUseThePortedParser() throws {
        let shape = try XCTUnwrap(try metadataAdmissionFixture()["viewShape"] as? [String: Any])
        XCTAssertEqual(shape["maxSqlBytes"] as? Int, MBTilesViewShape.maximumSQLBytes)
        XCTAssertEqual(shape["whitespace"] as? [String], [" ", "\t", "\r", "\n"])
        XCTAssertEqual(shape["punctuation"] as? [String], ["(", ")", ",", ".", "*", "="])
        XCTAssertEqual(shape["quotedIdentifiers"] as? [[String]], [["\"", "\""], ["[", "]"], ["`", "`"]])
        let reserved = try XCTUnwrap(shape["reservedWords"] as? [String])
        XCTAssertEqual(reserved.count, 149)
        XCTAssertEqual(Set(reserved), MBTilesViewShape.reservedWords)
        let cases = try XCTUnwrap(shape["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 82)
        var reasons: [String: Int] = [:]
        for c in cases {
            let id = try XCTUnwrap(c["id"] as? String)
            let sql = try XCTUnwrap(c["sql"] as? String, id)
            let relation = try XCTUnwrap(c["relation"] as? String, id)
            let expect = try XCTUnwrap(c["expect"] as? [String: Any], id)
            let got = MBTilesViewShape.baseTables(sql: sql, relation: relation)
            if try XCTUnwrap(expect["accepted"] as? Bool, id) {
                XCTAssertEqual(try? got.get(), try XCTUnwrap(expect["tables"] as? [String], id), id)
                reasons["accepted", default: 0] += 1
            } else {
                let reason = try XCTUnwrap(expect["reason"] as? String, id)
                guard case .failure(let r) = got else {
                    XCTFail("\(id): accepted \(String(describing: try? got.get())), wanted \(reason)")
                    continue
                }
                XCTAssertEqual(r.rawValue, reason, id)
                reasons[reason, default: 0] += 1
            }
        }
        // 3.0.2 SEC-M1-SHADOW moved ifNotExists from accepted to shape. SHADOW-PARITY-2
        // moved onWithAnd the other way and added the comma join / AND rows
        XCTAssertEqual(reasons, ["accepted": 23, "tooLong": 1, "token": 18, "shape": 40])
        for id in ["gdal2mbtiles", "tippecanoe", "onWithAnd"] {
            let c = try XCTUnwrap(cases.first { $0["id"] as? String == id }, id)
            XCTAssertEqual((c["expect"] as? [String: Any])?["accepted"] as? Bool, true, id)
        }
    }

    /// SEC-M1-SHADOW: a base table row has to declare its own name, the
    /// generator's table_declares() verdicts
    func testSharedBaseTableShapeCasesUseThePortedCheck() throws {
        let shape = try XCTUnwrap(try metadataAdmissionFixture()["baseTableShape"] as? [String: Any])
        let cases = try XCTUnwrap(shape["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 35)
        var verdicts: [Bool: Int] = [:]
        for c in cases {
            let id = try XCTUnwrap(c["id"] as? String)
            let name = try XCTUnwrap(c["name"] as? String, id)
            let want = try XCTUnwrap((c["expect"] as? [String: Any])?["accepted"] as? Bool, id)
            XCTAssertEqual(MBTilesViewShape.tableDeclares(sql: c["sql"] as? String, name: name), want, id)
            verdicts[want, default: 0] += 1
        }
        XCTAssertEqual(verdicts, [true: 12, false: 23])
    }

    // MARK: 3.0.3 U2, the record probe (s15.2)

    private func recordProbeFixture() throws -> [String: Any] {
        try XCTUnwrap(try metadataAdmissionFixture()["recordProbe"] as? [String: Any])
    }

    /// a recordProbe expect: nil when ok, else its reason
    private func probeReason(_ expect: Any?, _ id: String) throws -> String? {
        let e = try XCTUnwrap(expect as? [String: Any], id)
        return try XCTUnwrap(e["ok"] as? Bool, id) ? nil : try XCTUnwrap(e["reason"] as? String, id)
    }

    /// the root lookup the casesRule gives, the same row the base table check reads
    private func rootPage(of table: String, in url: URL, _ id: String) throws -> Int64 {
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open_v2(url.path, &db, SQLITE_OPEN_READONLY, nil), SQLITE_OK, id)
        defer { sqlite3_close(db) }
        var statement: OpaquePointer?
        defer { sqlite3_finalize(statement) }
        XCTAssertEqual(sqlite3_prepare_v2(db, "SELECT rootpage FROM sqlite_master WHERE type = 'table' AND name = ? " +
                                          "COLLATE NOCASE", -1, &statement, nil), SQLITE_OK, id)
        sqlite3_bind_text(statement, 1, table, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
        XCTAssertEqual(sqlite3_step(statement), SQLITE_ROW, "\(id) \(table)")
        XCTAssertEqual(sqlite3_column_type(statement, 0), SQLITE_INTEGER, "\(id) \(table)")
        return sqlite3_column_int64(statement, 0)
    }

    func testRecordProbeCapsMatchTheSharedFixture() throws {
        let fixture = try metadataAdmissionFixture()
        let probe = try recordProbeFixture()
        XCTAssertEqual(probe["maxSchemaRows"] as? Int, MBTilesRecordProbe.maximumSchemaRows)
        XCTAssertEqual((probe["maxSchemaBytes"] as? Int).map(UInt64.init), MBTilesRecordProbe.maximumSchemaBytes)
        XCTAssertEqual(probe["maxMetadataRows"] as? Int, MBTilesRecordProbe.maximumMetadataRows)
        XCTAssertEqual((probe["maxMetadataRecordBytes"] as? Int).map(UInt64.init),
                       MBTilesRecordProbe.maximumMetadataRecordBytes)
        XCTAssertEqual(probe["maxDepth"] as? Int, MBTilesRecordProbe.maximumDepth)
        XCTAssertEqual(probe["maxPages"] as? Int, MBTilesRecordProbe.maximumPages)
        // the admission's own 64 row cap, and a record that holds two values at the value cap
        XCTAssertEqual(fixture["maxRows"] as? Int, MBTilesRecordProbe.maximumMetadataRows)
        let connection = try XCTUnwrap(fixture["connection"] as? [String: Any])
        XCTAssertEqual((connection["maxValueBytes"] as? Int).map { 2 * UInt64($0) },
                       MBTilesRecordProbe.maximumMetadataRecordBytes)
    }

    /// recordProbe.cases: sql[] rows built by this sqlite with their table's
    /// root looked up like the reader does, packBase64 rows are the exact files
    func testSharedRecordProbeCasesGiveTheSharedVerdicts() throws {
        let cases = try XCTUnwrap(try recordProbeFixture()["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 34)
        XCTAssertEqual(cases.filter { $0["sql"] != nil }.count, 8)
        // the 9 byte varint is 2^64 - 1, a signed compare would let it through as -1
        XCTAssertTrue(cases.contains { $0["id"] as? String == "nineByteVarint" })
        var reasons: Set<String> = []
        for vector in cases {
            let id = try XCTUnwrap(vector["id"] as? String)
            let url: URL
            if let packed = vector["packBase64"] as? String {
                url = FileManager.default.temporaryDirectory.appendingPathComponent("probe-\(UUID().uuidString).mbtiles")
                try XCTUnwrap(Data(base64Encoded: packed), id).write(to: url)
            } else {
                url = try makePack(try XCTUnwrap(vector["sql"] as? [String], id))
            }
            defer { try? FileManager.default.removeItem(at: url) }
            let schema = try probeReason(vector["schema"], id)
            XCTAssertEqual(try MBTilesRecordProbe.schema(url)?.rawValue, schema, "\(id) schema")
            if let schema { reasons.insert(schema) }
            for table in try XCTUnwrap(vector["metadataTables"] as? [[String: Any]], id) {
                let root: Int64
                if let name = table["table"] as? String {
                    root = try rootPage(of: name, in: url, id)
                } else {
                    root = Int64(try XCTUnwrap(table["root"] as? Int, id))
                }
                let want = try probeReason(table["expect"], id)
                XCTAssertEqual(try MBTilesRecordProbe.metadataTable(url, root: root)?.rawValue, want, "\(id) root \(root)")
                if let want { reasons.insert(want) }
            }
        }
        XCTAssertEqual(reasons, ["header", "structure", "pageType", "rows", "recordBytes", "totalBytes"])
    }

    /// every door a pack comes through refuses what the probe refuses: the
    /// admission, the map source, the bake reader, the import and the lazy
    /// prevalidated open, which never reads metadata and gets probed anyway
    func testRecordProbeRefusalsHoldOnEveryOpen() async throws {
        // a 16 MB name value. 3.0.2 admitted it on iOS (the table path reads a
        // bounded blob prefix), an old android sqlite loaded all of it first
        let huge = try makePack([
            "CREATE TABLE metadata (name text, value text)",
            "INSERT INTO metadata VALUES ('name', replace(hex(zeroblob(8000000)), '0', 'n')), ('format', 'png')",
            "CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)",
            "INSERT INTO tiles VALUES (0, 0, 0, X'01')"
        ])
        defer { try? FileManager.default.removeItem(at: huge) }
        let size = try XCTUnwrap(try FileManager.default.attributesOfItem(atPath: huge.path)[.size] as? Int)
        XCTAssertGreaterThan(UInt64(size), MBTilesRecordProbe.maximumMetadataRecordBytes)
        XCTAssertNil(OfflineTileMapSource(url: huge), "map source")
        XCTAssertNil(PDFBakeReader(url: huge, expectedKey: String(repeating: "a", count: 64), tilePx: 256), "bake reader")

        var packs: [(String, URL)] = [("hugeName", huge)]
        for id in ["metadataRecordOverProbeCap", "metadataViewJoinBaseRows", "schemaTooManyObjects"] {
            let vector = try relationCase(id)
            XCTAssertEqual((vector["expect"] as? [String: Any])?["rejectedAt"] as? String, "probe", id)
            packs.append((id, try makeRelationPack(vector, id)))
        }
        defer { for (_, url) in packs.dropFirst() { try? FileManager.default.removeItem(at: url) } }
        for (id, url) in packs {
            XCTAssertNil(MBTilesStore(url: url), "\(id): admission")
            let lazy = MBTilesStore(prevalidatedURL: url, metadata: MBTilesStore.Metadata(
                name: "x", format: "png", minZoom: 0, maxZoom: 1, bounds: nil))
            XCTAssertNil(lazy.tileData(z: 0, x: 0, y: 0), "\(id) lazy tile")
            XCTAssertNil(lazy.extensionMetadata("tacmap_bake_key"), "\(id) lazy extension")
            XCTAssertNil(lazy.connectionHardeningForTesting(), "\(id): the lazy open refuses, not just the read")
            lazy.closeForDeletion()
        }

        // and the import's admission, refused and the copy cleaned up
        let original = ImportedMapStorage.applicationSupportProvider
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("probe-import-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        ImportedMapStorage.applicationSupportProvider = { root }
        defer {
            ImportedMapStorage.applicationSupportProvider = original
            try? FileManager.default.removeItem(at: root)
        }
        do {
            _ = try await MapImportPipeline.prepareMBTiles(url: huge, entryCount: 0, libraryLoaded: true,
                                                           isCancelled: { false }, progress: { _ in })
            XCTFail("the import admitted a 16 MB metadata row")
        } catch {
            XCTAssertEqual(error as? MapImportError, .invalidMbtiles)
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: huge.path), "refused, the source isn't touched")
    }

    /// the 100 byte file header, only what the probe reads filled in
    private func sqliteHeader(pageSize: Int) -> [UInt8] {
        var h = [UInt8](repeating: 0, count: 100)
        h.replaceSubrange(0..<16, with: Array("SQLite format 3\u{0}".utf8))
        h[16] = UInt8(pageSize >> 8 & 0xFF)
        h[17] = UInt8(pageSize & 0xFF)
        h[18] = 1
        h[19] = 1
        return h
    }

    /// a table leaf page, b-tree header at headerAt, one cell per record size
    /// packed at the end. offsets count from the page start
    private func leafPage(size: Int, records: [UInt64], headerAt: Int = 0) -> [UInt8] {
        var page = [UInt8](repeating: 0, count: size)
        page[headerAt] = 0x0D
        page[headerAt + 3] = UInt8(records.count >> 8 & 0xFF)
        page[headerAt + 4] = UInt8(records.count & 0xFF)
        var at = size
        for (i, record) in records.enumerated() {
            let cell = sqliteVarint(record) + sqliteVarint(UInt64(i + 1))
            at -= cell.count
            page.replaceSubrange(at..<at + cell.count, with: cell)
            page[headerAt + 8 + 2 * i] = UInt8(at >> 8 & 0xFF)
            page[headerAt + 9 + 2 * i] = UInt8(at & 0xFF)
        }
        return page
    }

    /// sqlite's varint for anything under 2^56
    private func sqliteVarint(_ value: UInt64) -> [UInt8] {
        var groups: [UInt8] = []
        var v = value
        repeat {
            groups.insert(UInt8(v & 0x7F), at: 0)
            v >>= 7
        } while v != 0
        return groups.enumerated().map { $0.offset < groups.count - 1 ? $0.element | 0x80 : $0.element }
    }

    func testAHugeMetadataRecordIsJudgedOffItsSizeWithoutReadingIt() throws {
        // the OOM pack's shape: a sane schema and one metadata row claiming 300 MB.
        // the file really is that long (sparse, nothing written past page 2) and
        // the probe only ever reads two pages of it
        let size = 4096
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("probe-huge-\(UUID().uuidString).mbtiles")
        defer { try? FileManager.default.removeItem(at: url) }
        var first = leafPage(size: size, records: [60], headerAt: 100)
        first.replaceSubrange(0..<100, with: sqliteHeader(pageSize: size))
        func write(_ record: UInt64) throws {
            try Data(first + leafPage(size: size, records: [record])).write(to: url)
            let handle = try FileHandle(forWritingTo: url)
            try handle.truncate(atOffset: UInt64(2 * size) + 300_000_000)
            try handle.close()
        }
        try write(300_000_000)
        XCTAssertNil(try MBTilesRecordProbe.schema(url))
        XCTAssertEqual(try MBTilesRecordProbe.metadataTable(url, root: 2), .recordBytes)
        // its size alone decides: right at the cap is fine, a byte over isn't
        try write(MBTilesRecordProbe.maximumMetadataRecordBytes)
        XCTAssertNil(try MBTilesRecordProbe.metadataTable(url, root: 2))
        try write(MBTilesRecordProbe.maximumMetadataRecordBytes + 1)
        XCTAssertEqual(try MBTilesRecordProbe.metadataTable(url, root: 2), .recordBytes)
    }

    func testAFileThatCantBeReadThrowsSoTheOpenRefusesIt() {
        let gone = FileManager.default.temporaryDirectory.appendingPathComponent("gone-\(UUID().uuidString).mbtiles")
        XCTAssertThrowsError(try MBTilesRecordProbe.schema(gone))
        XCTAssertThrowsError(try MBTilesRecordProbe.metadataTable(gone, root: 2))
        XCTAssertNil(MBTilesStore(url: gone))
    }

    /// seeded so a failure replays
    private struct SplitMix64 {
        var state: UInt64
        mutating func next() -> UInt64 {
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return z ^ (z >> 31)
        }
    }

    /// a hostile file gets no say in whether we crash: bytes flipped all over a
    /// real multi level pack (page headers and cell pointers mostly), tails cut
    /// off, silly roots. every walk has to come back with a verdict, quick
    func testMangledPacksNeverTrapTheProbe() throws {
        let cases = try XCTUnwrap(try recordProbeFixture()["cases"] as? [[String: Any]])
        let vector = try XCTUnwrap(cases.first { $0["id"] as? String == "smallPagesManyLevels" })
        let url = try makePack(try XCTUnwrap(vector["sql"] as? [String]))
        defer { try? FileManager.default.removeItem(at: url) }
        let original = [UInt8](try Data(contentsOf: url))
        let pageSize = 512
        XCTAssertEqual(Int(original[16]) << 8 | Int(original[17]), pageSize)
        let metadataRoot = try rootPage(of: "metadata", in: url, "fuzz")
        var rng = SplitMix64(state: 0x7AC_3A9_0303)
        var verdicts: Set<String> = []
        let started = Date()
        for round in 0..<600 {
            var bytes = original
            for _ in 0..<(1 + Int(rng.next() % 6)) {
                let page = Int(rng.next() % UInt64(bytes.count / pageSize))
                let base = page * pageSize + (page == 0 ? 100 : 0)
                let at: Int
                switch rng.next() % 8 {
                case 0: at = 16 + Int(rng.next() % 6) // page size and reserved bytes
                case 1, 2: at = Int(rng.next() % UInt64(bytes.count))
                default: at = base + Int(rng.next() % 40) // b-tree header and pointer array
                }
                bytes[at] = UInt8(truncatingIfNeeded: rng.next())
            }
            // cut the tail off now and then, sometimes down to nearly nothing
            if round % 9 == 0 {
                let keep = round % 27 == 0 ? rng.next() % 120 : rng.next() % UInt64(bytes.count)
                bytes = Array(bytes.prefix(Int(keep)))
            }
            try Data(bytes).write(to: url)
            verdicts.insert(try MBTilesRecordProbe.schema(url)?.rawValue ?? "ok")
            for root in [metadataRoot, 1, -1, 0, Int64(bytes.count / pageSize), Int64(bytes.count / pageSize) + 1,
                         Int64(UInt32.max), Int64.max, Int64.min] {
                verdicts.insert(try MBTilesRecordProbe.metadataTable(url, root: root)?.rawValue ?? "ok")
            }
        }
        // the mangling has to reach past the header check or this proves little
        XCTAssertTrue(verdicts.isSuperset(of: ["ok", "structure", "pageType", "header"]), "\(verdicts)")
        XCTAssertLessThan(Date().timeIntervalSince(started), 30)
    }

    /// the widest interior page there is (64 KiB, ~32k children) all pointing at
    /// one page, and a chain of interior pages that goes one level too deep.
    /// both are refused as structure without walking forever
    func testWideAndDeepInteriorPagesAreStructure() throws {
        let size = 65_536
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("probe-wide-\(UUID().uuidString).mbtiles")
        defer { try? FileManager.default.removeItem(at: url) }
        var first = leafPage(size: size, records: [], headerAt: 100)
        // the header stores 65536 as 1
        first.replaceSubrange(0..<100, with: sqliteHeader(pageSize: 1))
        // page 2: interior, every pointer at one cell whose child is page 3, a leaf
        var wide = [UInt8](repeating: 0, count: size)
        let n = (size - 12 - 4) / 2
        wide[0] = 0x05
        wide[3] = UInt8(n >> 8 & 0xFF)
        wide[4] = UInt8(n & 0xFF)
        let cell = 12 + 2 * n
        for i in 0..<n {
            wide[12 + 2 * i] = UInt8(cell >> 8 & 0xFF)
            wide[13 + 2 * i] = UInt8(cell & 0xFF)
        }
        wide[cell + 3] = 3
        wide[11] = 3
        let leaf = leafPage(size: size, records: [10])
        try Data(first + wide + leaf).write(to: url)
        XCTAssertNil(try MBTilesRecordProbe.schema(url))
        let started = Date()
        XCTAssertEqual(try MBTilesRecordProbe.metadataTable(url, root: 2), .structure, "page 3 a second time")
        XCTAssertNil(try MBTilesRecordProbe.metadataTable(url, root: 3))
        XCTAssertLessThan(Date().timeIntervalSince(started), 5)

        // interior pages 2...21 each with only a right most child, the next one,
        // and page 22 a leaf: depth 20 is one too many, from page 3 it's fine
        let small = 512
        var chain = sqliteHeader(pageSize: small)
        chain += leafPage(size: small, records: [], headerAt: 100).dropFirst(100)
        for p in 2...21 {
            var page = [UInt8](repeating: 0, count: small)
            page[0] = 0x05
            page[8] = UInt8((p + 1) >> 24 & 0xFF)
            page[9] = UInt8((p + 1) >> 16 & 0xFF)
            page[10] = UInt8((p + 1) >> 8 & 0xFF)
            page[11] = UInt8((p + 1) & 0xFF)
            chain += page
        }
        chain += leafPage(size: small, records: [1])
        try Data(chain).write(to: url)
        XCTAssertEqual(try MBTilesRecordProbe.metadataTable(url, root: 2), .structure)
        XCTAssertNil(try MBTilesRecordProbe.metadataTable(url, root: 3))
    }

    /// the process's resident high water mark. SQLite fills what it allocates
    /// (random bytes, hex digits, printf output) so a big value shows up here
    private func residentHighWater() -> Int {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size / MemoryLayout<natural_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
            }
        }
        return kr == KERN_SUCCESS ? Int(info.resident_size_max) : 0
    }

    /// SEC-1: built-ins that allocate inside one uninterruptible step. 3.0.1
    /// admitted all of these and ran them on the first tile or metadata read
    func testHostilePacksFailClosedWithoutALargeAllocation() throws {
        let base = [
            "CREATE TABLE metadata (name text, value text)",
            "INSERT INTO metadata VALUES ('name', 'Sample'), ('format', 'png')",
            "CREATE TABLE raw (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)",
            "INSERT INTO raw VALUES (0, 0, 0, X'01'), (1, 0, 1, X'0203')"
        ]
        let bombs: [(String, [String])] = [
            ("randomblob", base + ["CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row, " +
                                   "randomblob(900000000) AS tile_data FROM raw"]),
            ("hexZeroblob", base + ["CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row, " +
                                    "hex(zeroblob(400000000)) AS tile_data FROM raw"]),
            ("printf", [
                "CREATE TABLE meta_base (name text, value text)",
                "INSERT INTO meta_base VALUES ('name', 'Sample'), ('format', 'png'), ('tacmap_bake_key', 'k')",
                "CREATE VIEW metadata AS SELECT name, CASE WHEN name = 'tacmap_bake_key' " +
                    "THEN printf('%.*c', 900000000, 'x') ELSE value END AS value FROM meta_base",
                "CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)",
                "INSERT INTO tiles VALUES (0, 0, 0, X'01')"
            ]),
            // each one fits under the value cap, all live at once in max()'s registers
            ("registersTimesCap", base + ["CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row, max(" +
                Array(repeating: "randomblob(4000000)", count: 64).joined(separator: ", ") + ") AS tile_data FROM raw"]),
            // the 3.0.0 deterministic bomb, in an ordinary looking table
            ("generatedColumn", [
                "CREATE TABLE metadata (name text, value text)",
                "INSERT INTO metadata VALUES ('name', 'Sample'), ('format', 'png')",
                "CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, " +
                    "tile_data blob GENERATED ALWAYS AS (hex(zeroblob(400000000))) VIRTUAL)",
                "INSERT INTO tiles (zoom_level, tile_column, tile_row) VALUES (0, 0, 0), (1, 0, 1)"
            ])
        ]
        for (id, sql) in bombs {
            let url = try makePack(sql)
            defer { try? FileManager.default.removeItem(at: url) }
            let before = residentHighWater()
            XCTAssertNil(MBTilesStore(url: url), "\(id): refused at admission")
            // the prevalidated path never ran admission, it still mustnt run the file's SQL
            let lazy = MBTilesStore(prevalidatedURL: url, metadata: MBTilesStore.Metadata(
                name: "x", format: "png", minZoom: 0, maxZoom: 1, bounds: nil))
            XCTAssertNil(lazy.tileData(z: 0, x: 0, y: 0), id)
            XCTAssertNil(lazy.tileData(z: 1, x: 0, y: 0), id)
            XCTAssertNil(lazy.extensionMetadata("tacmap_bake_key"), id)
            XCTAssertNil(lazy.connectionHardeningForTesting(), "\(id): the lazy open refuses too")
            lazy.closeForDeletion()
            XCTAssertLessThan(residentHighWater() - before, 64 * 1024 * 1024, "\(id): nothing big got allocated")
        }
    }

    /// viewDateTrigger: a view whose value turns on later. Refused by both the
    /// admission and the lazy prevalidated open, the latter serves nothing
    func testHostileViewIsRefusedOnTheAdmissionAndTheLazyPath() throws {
        for id in ["viewDateTrigger", "viewCallsFunction", "viewKeywordValue", "viewWhereClause", "viewOverView",
                   "viewOverVirtualTable", "tilesVirtualTable", "tilesTableGeneratedColumn", "viewOverGeneratedColumn",
                   "schemaStatementTooLong", "tilesViewEndless", "metadataViewLargeUnknownValue",
                   // SEC-M1-SHADOW: the row the checks read has to be the object sqlite runs
                   "tilesShadowedByCaseVariant", "metadataShadowedByCaseVariant", "tableRowKeepsIfNotExists",
                   "viewRowNameLieBehindIfNotExists", "baseRowNameLie", "baseRowNameLieBehindIfNotExists"] {
            let vector = try relationCase(id)
            if let min = vector["minSqliteVersion"] as? String, sqlite3_libversion_number() < Self.versionNumber(min) {
                continue
            }
            let url = try makeRelationPack(vector, id)
            defer { try? FileManager.default.removeItem(at: url) }
            XCTAssertNil(MBTilesStore(url: url), id)
            let lazy = MBTilesStore(prevalidatedURL: url, metadata: MBTilesStore.Metadata(
                name: "x", format: "png", minZoom: 0, maxZoom: 1, bounds: nil))
            let started = Date()
            XCTAssertNil(lazy.tileData(z: 0, x: 0, y: 0), "\(id) lazy")
            XCTAssertNil(lazy.extensionMetadata("tacmap_bake_key"), "\(id) lazy")
            XCTAssertLessThan(Date().timeIntervalSince(started), 1, "\(id): refused at once, not cut off by a budget")
            lazy.closeForDeletion()
        }
    }

    /// F3 / 3.0.2: two tables get no admission budget, their aggregate is one
    /// scan the file bounds. 3.0.1 interrupted this one at the first check
    func testATablePackIsAdmittedWithoutTheBudget() throws {
        let url = try makePack([
            "CREATE TABLE metadata (name text, value text)",
            "INSERT INTO metadata VALUES ('name', 'Big'), ('format', 'png')",
            "CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)",
            "WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < 19999) " +
                "INSERT INTO tiles SELECT 15, i, 0, X'01' FROM n"
        ])
        defer { try? FileManager.default.removeItem(at: url) }
        let store = MBTilesStore(url: url, admissionBudgetMs: 0)
        XCTAssertNotNil(store)
        XCTAssertEqual(store?.metadata.minZoom, 15)
        // the same rows behind a view do get the budget
        let viewURL = try makePack([
            "CREATE TABLE metadata (name text, value text)",
            "INSERT INTO metadata VALUES ('name', 'Big'), ('format', 'png')",
            "CREATE TABLE raw (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)",
            "WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < 19999) " +
                "INSERT INTO raw SELECT 15, i, 0, X'01' FROM n",
            "CREATE VIEW tiles AS SELECT * FROM raw"
        ])
        defer { try? FileManager.default.removeItem(at: viewURL) }
        XCTAssertNil(MBTilesStore(url: viewURL, admissionBudgetMs: 0))
        XCTAssertNotNil(MBTilesStore(url: viewURL))
    }

    func testDeduplicatedViewPackPassesImportAdmissionAndDrawsTiles() async throws {
        let fixture = try metadataAdmissionFixture()
        let cases = try XCTUnwrap(fixture["relationCases"] as? [[String: Any]])
        let dedup = try XCTUnwrap(cases.first { $0["id"] as? String == "nodeMbtilesDedup" })
        let source = try makePack(try XCTUnwrap(dedup["sql"] as? [String]))
        defer { try? FileManager.default.removeItem(at: source) }

        let original = ImportedMapStorage.applicationSupportProvider
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("mbtiles-view-import-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        ImportedMapStorage.applicationSupportProvider = { root }
        defer {
            ImportedMapStorage.applicationSupportProvider = original
            try? FileManager.default.removeItem(at: root)
        }
        let payload = try await MapImportPipeline.prepareMBTiles(url: source, entryCount: 0, libraryLoaded: true,
                                                                 isCancelled: { false }, progress: { _ in })
        XCTAssertEqual(payload.metadata.name, "Sample")
        XCTAssertEqual(payload.metadata.minZoom, 0)
        XCTAssertEqual(payload.metadata.maxZoom, 1)
        XCTAssertEqual(payload.entry()?.kind, .mbtiles)

        let sourceMap = OfflineTileMapSource(prevalidatedURL: payload.copy.url, metadata: payload.metadata)
        XCTAssertEqual(sourceMap.store.tileData(z: 0, x: 0, y: 0), Data([0x01]))
        XCTAssertEqual(sourceMap.store.tileData(z: 1, x: 0, y: 0), Data([0x02, 0x03]))
        XCTAssertNil(sourceMap.store.tileData(z: 1, x: 1, y: 1))
    }

    /// SHADOW-PARITY-2: gdal2mbtiles, a raster PNG producer, stores tiles as a
    /// comma join (FROM map, images WHERE map.tile_id = images.tile_id). 3.0.1
    /// opened those packs and the first 3.0.2 grammar refused them, so an
    /// upgrade lost the offline basemap. through the real import and the
    /// prevalidated source the map draws from
    func testGdal2mbtilesCommaJoinPackImportsAndDrawsTiles() async throws {
        let vector = try relationCase("gdal2mbtilesCommaJoin")
        let expect = try XCTUnwrap(vector["expect"] as? [String: Any])
        XCTAssertEqual(expect["accepted"] as? Bool, true)
        let source = try makeRelationPack(vector, "gdal2mbtilesCommaJoin")
        defer { try? FileManager.default.removeItem(at: source) }

        let original = ImportedMapStorage.applicationSupportProvider
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("mbtiles-gdal-import-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        ImportedMapStorage.applicationSupportProvider = { root }
        defer {
            ImportedMapStorage.applicationSupportProvider = original
            try? FileManager.default.removeItem(at: root)
        }
        let payload = try await MapImportPipeline.prepareMBTiles(url: source, entryCount: 0, libraryLoaded: true,
                                                                 isCancelled: { false }, progress: { _ in })
        XCTAssertEqual(payload.metadata.name, expect["name"] as? String)
        XCTAssertEqual(payload.metadata.format, expect["format"] as? String)
        XCTAssertEqual(payload.metadata.minZoom, expect["minZoom"] as? Int)
        XCTAssertEqual(payload.metadata.maxZoom, expect["maxZoom"] as? Int)
        XCTAssertEqual(payload.entry()?.kind, .mbtiles)

        let sourceMap = OfflineTileMapSource(prevalidatedURL: payload.copy.url, metadata: payload.metadata)
        let probes = try XCTUnwrap(expect["tiles"] as? [[String: Any]])
        // a hit, a deduplicated hit and a miss, so the join really ran
        XCTAssertEqual(probes.compactMap { $0["hex"] as? String }.count, 3)
        for probe in probes {
            let z = try XCTUnwrap(probe["z"] as? Int), x = try XCTUnwrap(probe["x"] as? Int)
            let y = try XCTUnwrap(probe["y"] as? Int)
            XCTAssertEqual(hex(sourceMap.store.tileData(z: z, x: x, y: y)), probe["hex"] as? String, "\(z)/\(x)/\(y)")
        }
    }

    func testViewReadsAfterAdmissionAreBudgetedPerStatement() throws {
        // The prevalidated path skips admission, so a pack swapped in later
        // whose plain join has no index (automatic indexes are off, so that's a
        // 20,000 x 20,000 nested loop) must still come back as a missing
        // tile/extension instead of wedging the renderer under the store lock.
        // CROSS JOIN pins the left table outside, every key matches, and the
        // column the read filters on lives in the inner table, so each read
        // really walks all 400M pairs (no Bloom filter shortcut).
        // 3.0.3 U2: the record probe walks a metadata view's base tables now,
        // 64 rows each at most, so a slow metadata view never gets this far
        // (refused at the lazy open, below). its reads stay budgeted, the
        // tiles view isn't walked and is what's left to be slow
        let rows = "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 19999)"
        let small = "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 64)"
        func metadataView(_ fill: String) -> [String] {
            ["CREATE TABLE names (k text, value text)",
             "CREATE TABLE vals (k text, name text)",
             "\(fill) INSERT INTO names SELECT 'a', 'v' FROM n",
             "\(fill) INSERT INTO vals SELECT 'a', 'extension_' || i FROM n",
             "CREATE VIEW metadata AS SELECT vals.name AS name, names.value AS value FROM names CROSS JOIN vals " +
                "ON vals.k = names.k"]
        }
        let tilesView = [
            "CREATE TABLE map (zoom_level integer, tile_column integer, k text)",
            "CREATE TABLE images (tile_row integer, tile_data blob, k text)",
            // the z0 row and its image come first, every z1 row only meets row 0 images
            "INSERT INTO map VALUES (0, 0, 'hit')",
            "INSERT INTO images VALUES (0, X'01', 'hit')",
            "\(rows) INSERT INTO map SELECT 1, 0, 'a' FROM n",
            "\(rows) INSERT INTO images SELECT 0, X'02', 'a' FROM n",
            "CREATE VIEW tiles AS SELECT map.zoom_level AS zoom_level, map.tile_column AS tile_column, " +
                "images.tile_row AS tile_row, images.tile_data AS tile_data FROM map CROSS JOIN images ON images.k = map.k"
        ]
        let url = try makePack(metadataView(small) + tilesView)
        defer { try? FileManager.default.removeItem(at: url) }
        let store = MBTilesStore(prevalidatedURL: url, metadata: MBTilesStore.Metadata(
            name: "Unindexed", format: "png", minZoom: 0, maxZoom: 1, bounds: nil))

        // lower bound proves the scan really ran until the budget cut it
        let budget = Double(MBTilesStore.viewQueryBudgetMs) / 1000
        var started = Date()
        XCTAssertNil(store.tileData(z: 1, x: 0, y: 0))
        var elapsed = Date().timeIntervalSince(started)
        XCTAssertGreaterThanOrEqual(elapsed, budget * 0.9)
        XCTAssertLessThan(elapsed, 10)
        // 64 x 64 pairs, the budgeted view read answers well inside it
        started = Date()
        XCTAssertNil(store.extensionMetadata("tacmap_bake_key"))
        XCTAssertLessThan(Date().timeIntervalSince(started), budget / 2)
        // the matching row comes first so this one still answers
        XCTAssertEqual(store.tileData(z: 0, x: 0, y: 0), Data([0x01]))

        // the 3.0.2 slow metadata view (20,000 x 20,000) is refused by the lazy
        // open's metadata probe before a single read, tiles included
        let slowMetadata = try makePack(metadataView(rows) + tilesView)
        defer { try? FileManager.default.removeItem(at: slowMetadata) }
        let probed = MBTilesStore(prevalidatedURL: slowMetadata, metadata: MBTilesStore.Metadata(
            name: "Unindexed", format: "png", minZoom: 0, maxZoom: 1, bounds: nil))
        started = Date()
        XCTAssertNil(probed.extensionMetadata("tacmap_bake_key"))
        XCTAssertNil(probed.tileData(z: 0, x: 0, y: 0))
        XCTAssertNil(probed.connectionHardeningForTesting(), "the lazy open refused it")
        XCTAssertLessThan(Date().timeIntervalSince(started), 1, "refused at once")

        // the 3.0.1 endless recursive views aren't budgeted any more, the lazy
        // open refuses them before a single read
        let endless = try makePack([
            "CREATE VIEW metadata AS WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n) " +
                "SELECT 'extension_' || i AS name, 'v' AS value FROM n",
            "CREATE VIEW tiles AS WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n) " +
                "SELECT i * 0 AS zoom_level, i * 0 AS tile_column, i * 0 AS tile_row, X'01' AS tile_data FROM n"
        ])
        defer { try? FileManager.default.removeItem(at: endless) }
        let refused = MBTilesStore(prevalidatedURL: endless, metadata: MBTilesStore.Metadata(
            name: "Endless", format: "png", minZoom: 0, maxZoom: 1, bounds: nil))
        started = Date()
        XCTAssertNil(refused.tileData(z: 0, x: 0, y: 0))
        XCTAssertNil(refused.tileData(z: 1, x: 0, y: 0))
        XCTAssertNil(refused.extensionMetadata("tacmap_bake_key"))
        XCTAssertLessThan(Date().timeIntervalSince(started), 1, "refused at once")
        XCTAssertNil(MBTilesStore(url: endless))
    }

    func testAdmissionConstantsMatchTheSharedFixture() throws {
        // test-integrity-4: these used to be inline literals nobody checked
        let fixture = try metadataAdmissionFixture()
        XCTAssertEqual(fixture["maxRows"] as? Int, MBTilesStore.maximumMetadataRows)
        XCTAssertEqual(fixture["maxKeyCharacters"] as? Int, MBTilesStore.maximumKeyCharacters)
        XCTAssertEqual(fixture["utf8PrefixBytesPerCharacter"] as? Int, MBTilesStore.utf8PrefixBytesPerCharacter)
        XCTAssertEqual(fixture["knownValueMaxCharacters"] as? [String: Int], MBTilesStore.knownValueMaximumCharacters)
        XCTAssertEqual(fixture["consumedBakeExtensionMaxCharacters"] as? Int,
                       MBTilesStore.consumedBakeExtensionMaximumCharacters)
        XCTAssertEqual(fixture["tileZoomMax"] as? Int, MBTilesStore.maximumZoom)
    }

    func testAbsentZoomMetadataUsesValidatedTileRange() throws {
        let url = try makeSampleMBTiles()
        defer { try? FileManager.default.removeItem(at: url) }
        try execute("DELETE FROM metadata WHERE name IN ('minzoom','maxzoom')", on: url)

        let store = try XCTUnwrap(MBTilesStore(url: url))
        XCTAssertEqual(store.metadata.minZoom, 1)
        XCTAssertEqual(store.metadata.maxZoom, 1)
        XCTAssertEqual(store.tileData(z: 1, x: 0, y: 1), Data([0xDE, 0xAD, 0xBE, 0xEF]))
    }

    func testMalformedZoomBoundsAndMetadataRowsAreRejected() throws {
        try assertMetadataRejected("UPDATE metadata SET value='01' WHERE name='minzoom'")
        try assertMetadataRejected("UPDATE metadata SET value='31' WHERE name='maxzoom'")
        try assertMetadataRejected(
            "UPDATE metadata SET value='2' WHERE name='minzoom'; " +
            "UPDATE metadata SET value='1' WHERE name='maxzoom'"
        )
        try assertMetadataRejected(
            "UPDATE metadata SET value='12345678901234567' WHERE name='minzoom'"
        )
        try assertMetadataRejected("UPDATE metadata SET value='nan,-2,3,4' WHERE name='bounds'")
        try assertMetadataRejected("UPDATE metadata SET value='3,-2,-1,4' WHERE name='bounds'")
        try assertMetadataRejected("UPDATE metadata SET value='-1,-91,3,4' WHERE name='bounds'")
        try assertMetadataRejected(
            "UPDATE metadata SET value='\(String(repeating: "1", count: 257))' WHERE name='bounds'"
        )
        try assertMetadataRejected(
            "UPDATE metadata SET value=CAST(X'2D312C2D322C332C34002C35' AS TEXT) WHERE name='bounds'"
        )
        try assertMetadataRejected("INSERT INTO metadata VALUES ('bounds','-1,-2,3,4')")
        try assertMetadataRejected("UPDATE tiles SET zoom_level=31")
        try assertMetadataRejected("UPDATE tiles SET tile_column=-1")
        try assertMetadataRejected("UPDATE tiles SET tile_row=2")
        try assertMetadataRejected("UPDATE tiles SET tile_column=CAST(zeroblob(64) AS TEXT)")
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
        // D5-14: opaque names, the sheet name never reaches the file system
        XCTAssertTrue(firstDest.lastPathComponent.hasPrefix("map-"))
        XCTAssertTrue(secondDest.lastPathComponent.hasPrefix("map-"))
        XCTAssertFalse(firstDest.lastPathComponent.contains("training"))
        XCTAssertEqual(firstDest.pathExtension, "mbtiles")
        XCTAssertEqual(try Data(contentsOf: firstDest), Data("first".utf8))
        XCTAssertEqual(try Data(contentsOf: secondDest), Data("second".utf8))
    }

    func testImportedMapFileCopierCanonicalisesManagedExtension() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("import-extension-\(UUID().uuidString)", isDirectory: true)
        let destination = root.appendingPathComponent("dest", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("operational-map.payload")
        try Data("map bytes".utf8).write(to: source)

        let copied = try ImportedMapFileCopier.copy(
            source,
            into: destination,
            preferredExtension: "mbtiles"
        )

        XCTAssertEqual(copied.pathExtension, "mbtiles")
        XCTAssertFalse(copied.lastPathComponent.contains("operational"), "opaque name (D5-14)")
        XCTAssertEqual(try Data(contentsOf: copied), Data("map bytes".utf8))
    }

    func testImportedMapFileCopierRejectsOversizedInputWithoutResidue() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("import-limit-\(UUID().uuidString)", isDirectory: true)
        let destination = root.appendingPathComponent("dest", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("oversized.mbtiles")
        try Data("12345".utf8).write(to: source)

        XCTAssertThrowsError(try ImportedMapFileCopier.copy(
            source, into: destination, maximumBytes: 4
        ))
        XCTAssertFalse(FileManager.default.fileExists(atPath: destination.path))
    }

    func testImportedMapFileCopierCancellationRemovesPartialCopy() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("import-cancel-\(UUID().uuidString)", isDirectory: true)
        let destination = root.appendingPathComponent("dest", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("large.mbtiles")
        try Data(repeating: 0xA5, count: 32).write(to: source)
        var chunks = 0

        XCTAssertThrowsError(try ImportedMapFileCopier.copy(
            source,
            into: destination,
            maximumBytes: 64,
            chunkSize: 8,
            afterChunk: {
                chunks += 1
                if chunks == 1 { throw CancellationError() }
            }
        )) { error in
            XCTAssertTrue(error is CancellationError)
        }
        XCTAssertEqual(chunks, 1)
        XCTAssertTrue(FileManager.default.fileExists(atPath: destination.path))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: destination.path), [])
    }

    func testImportedMapFileCopierConcurrentCreateLoserDoesNotDeleteWinner() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("import-race-\(UUID().uuidString)", isDirectory: true)
        let destination = root.appendingPathComponent("dest", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("training.mbtiles")
        try Data("loser source".utf8).write(to: source)
        let winnerBytes = Data("winner copy".utf8)
        var winnerURL: URL?

        XCTAssertThrowsError(try ImportedMapFileCopier.copy(
            source,
            into: destination,
            beforeCreate: { candidate in
                // Deterministically model another invocation creating the
                // selected path after this invocation resolved its filename.
                try winnerBytes.write(to: candidate, options: .withoutOverwriting)
                winnerURL = candidate
            }
        ))

        let createdByWinner = try XCTUnwrap(winnerURL)
        XCTAssertTrue(FileManager.default.fileExists(atPath: createdByWinner.path))
        XCTAssertEqual(try Data(contentsOf: createdByWinner), winnerBytes,
                       "the losing invocation must not remove or alter the winner's file")
    }

    func testMBTilesWorkerCopiesAndValidatesOffMainThread() async throws {
        let source = try makeSampleMBTiles()
        defer { try? FileManager.default.removeItem(at: source) }

        // WP5 pipeline: copy + hash + validate off main into a temp App Support
        let original = ImportedMapStorage.applicationSupportProvider
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("mbtiles-import-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        ImportedMapStorage.applicationSupportProvider = { root }
        defer {
            ImportedMapStorage.applicationSupportProvider = original
            try? FileManager.default.removeItem(at: root)
        }
        let payload = try await MapImportPipeline.prepareMBTiles(url: source, entryCount: 0, libraryLoaded: true,
                                                                 isCancelled: { false }, progress: { _ in })
        XCTAssertTrue(payload.performedWorkOffMainThread)
        XCTAssertTrue(FileManager.default.fileExists(atPath: payload.copy.url.path))
        XCTAssertTrue(payload.copy.url.lastPathComponent.hasPrefix("map-"), "opaque name")
        XCTAssertEqual(payload.copy.contentKey, PDFSessionStore.contentKey(for: payload.copy.url))
        XCTAssertEqual(payload.metadata.name, "Sample")
        XCTAssertEqual(payload.metadata.format, "png")
        XCTAssertEqual(payload.metadata.minZoom, 0)
        XCTAssertEqual(payload.metadata.maxZoom, 1)
        XCTAssertEqual(payload.entry()?.kind, .mbtiles)

        let sourceMap = OfflineTileMapSource(
            prevalidatedURL: payload.copy.url,
            metadata: payload.metadata
        )
        XCTAssertEqual(sourceMap.displayName, "Sample")
        XCTAssertNotNil(sourceMap.coverage)
        XCTAssertEqual(sourceMap.store.tileData(z: 1, x: 0, y: 1),
                       Data([0xDE, 0xAD, 0xBE, 0xEF]))
    }

    func testPrevalidatedMapSourceInitializerDoesNotOpenItsFile() {
        let absent = FileManager.default.temporaryDirectory
            .appendingPathComponent("absent-\(UUID().uuidString).mbtiles")
        let metadata = MBTilesStore.Metadata(
            name: "Already validated",
            format: "png",
            minZoom: 2,
            maxZoom: 8,
            bounds: (minLon: 149, minLat: -36, maxLon: 152, maxLat: -33)
        )

        let source = OfflineTileMapSource(prevalidatedURL: absent, metadata: metadata)

        XCTAssertFalse(FileManager.default.fileExists(atPath: absent.path))
        XCTAssertEqual(source.displayName, "Already validated")
        XCTAssertNotNil(source.coverage,
                        "construction uses the worker's value metadata without querying SQLite")
    }
}
