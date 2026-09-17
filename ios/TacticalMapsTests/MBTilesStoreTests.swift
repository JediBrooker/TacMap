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
        XCTAssertEqual(firstDest.lastPathComponent, "training.mbtiles")
        XCTAssertEqual(secondDest.lastPathComponent, "training-1.mbtiles")
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

        XCTAssertEqual(copied.lastPathComponent, "operational-map.mbtiles")
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

        let payload = try await ImportedMapWorker.prepareMBTiles(url: source)
        defer { try? FileManager.default.removeItem(at: payload.destination) }
        XCTAssertTrue(payload.performedWorkOffMainThread)
        XCTAssertTrue(FileManager.default.fileExists(atPath: payload.destination.path))
        XCTAssertEqual(payload.metadata.name, "Sample")
        XCTAssertEqual(payload.metadata.format, "png")
        XCTAssertEqual(payload.metadata.minZoom, 0)
        XCTAssertEqual(payload.metadata.maxZoom, 1)

        let sourceMap = OfflineTileMapSource(
            prevalidatedURL: payload.destination,
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
