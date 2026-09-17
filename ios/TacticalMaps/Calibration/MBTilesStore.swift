import Foundation
import SQLite3

/// Read-only reader for an MBTiles file (SQLite DB of raster map tiles, OSGeo
/// spec). Serves tiles by XYZ coordinate (converting to TMS row scheme that
/// MBTiles uses) plus bounds + zoom metadata. Data layer behind offline raster
/// basemaps: user sideloads a .mbtiles generated from a GeoPDF/raster (e.g.
/// via gdal_translate + gdal2tiles) and app serves it with no network.
final class MBTilesStore: @unchecked Sendable {

    static let maximumTileBytes = 4 * 1024 * 1024
    static let maximumZoom = 30

    private enum MetadataValue {
        case missing
        case value(String)
        case invalid

        var isInvalid: Bool {
            if case .invalid = self { return true }
            return false
        }
    }

    struct Metadata: Sendable {
        var name: String?
        var format: String?          // "png", "jpg", etc
        var minZoom: Int?
        var maxZoom: Int?
        /// WGS84 extent from the `bounds` metadata: minLon, minLat, maxLon, maxLat.
        var bounds: (minLon: Double, minLat: Double, maxLon: Double, maxLat: Double)?
    }

    let url: URL
    private(set) var metadata = Metadata()
    private var db: OpaquePointer?
    private var closedForDeletion = false
#if DEBUG
    /// A regression-test seam proving an oversized length preflight never
    /// advances to the payload query that can make SQLite expose BLOB bytes.
    private(set) var payloadQueryCountForTesting = 0
#endif
    /// MapKit calls tileData concurrently from multiple MKTileOverlay.loadTile
    /// threads. Single SQLite connection isn't thread-safe so just serialise
    /// every query behind this lock.
    private let lock = NSLock()

    init?(url: URL) {
        self.url = url
        guard openDatabaseIfNeeded() else { return nil }
        // Reject files that aren't actually MBTiles: sqlite3_open succeeds on any
        // path, so without this check a garbage/corrupt file would load as a
        // "valid" but blank basemap. Both tables are mandatory, and all
        // security-sensitive metadata is validated before publishing a source.
        guard hasTable("tiles"),
              hasTable("metadata"),
              let validatedMetadata = loadMetadata() else {
            sqlite3_close(db)
            db = nil
            return nil
        }
        metadata = validatedMetadata
    }

    /// The import worker has already opened and validated this app-private
    /// copy off-main. Retain only its value metadata here; the SQLite handle is
    /// opened lazily by the first tile request instead of on the MainActor.
    init(prevalidatedURL url: URL, metadata: Metadata) {
        self.url = url
        self.metadata = metadata
    }

    deinit { sqlite3_close(db) }

    private func openDatabaseIfNeeded() -> Bool {
        guard !closedForDeletion else { return false }
        if db != nil { return true }
        guard sqlite3_open_v2(url.path, &db, SQLITE_OPEN_READONLY, nil) == SQLITE_OK else {
            sqlite3_close(db)
            db = nil
            return false
        }
        return true
    }

    private func hasTable(_ name: String) -> Bool {
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(
            db,
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            -1, &stmt, nil) == SQLITE_OK else { return false }
        sqlite3_bind_text(stmt, 1, name, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
        return sqlite3_step(stmt) == SQLITE_ROW
    }

    private func loadMetadata() -> Metadata? {
        guard databaseUsesUTF8Text(),
              let tileZoomRange = loadTileZoomRange(),
              let metadataValues = readKnownMetadataValues() else { return nil }

        return validatedMetadata(
            nameValue: metadataValues["name"] ?? .missing,
            formatValue: metadataValues["format"] ?? .missing,
            minimumZoomValue: metadataValues["minzoom"] ?? .missing,
            maximumZoomValue: metadataValues["maxzoom"] ?? .missing,
            boundsValue: metadataValues["bounds"] ?? .missing,
            tileZoomRange: tileZoomRange
        )
    }

    private func databaseUsesUTF8Text() -> Bool {
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, "PRAGMA encoding", -1, &stmt, nil) == SQLITE_OK,
              sqlite3_step(stmt) == SQLITE_ROW,
              let encodingPointer = sqlite3_column_text(stmt, 0) else { return false }
        return String(cString: encodingPointer).caseInsensitiveCompare("UTF-8") == .orderedSame
    }

    private func loadTileZoomRange() -> (minimum: Int, maximum: Int)? {
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        let sql = """
        SELECT MIN(CASE WHEN typeof(zoom_level)='integer' THEN zoom_level END),
               MAX(CASE WHEN typeof(zoom_level)='integer' THEN zoom_level END),
               COUNT(*),
               SUM(CASE WHEN typeof(zoom_level)='integer'
                                  AND typeof(tile_column)='integer'
                                  AND typeof(tile_row)='integer'
                        THEN CASE WHEN zoom_level BETWEEN 0 AND \(Self.maximumZoom)
                                           AND tile_column BETWEEN 0 AND ((1 << zoom_level) - 1)
                                           AND tile_row BETWEEN 0 AND ((1 << zoom_level) - 1)
                                  THEN 1 ELSE 0 END
                        ELSE 0 END)
        FROM tiles
        """
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK,
              sqlite3_step(stmt) == SQLITE_ROW,
              sqlite3_column_type(stmt, 0) == SQLITE_INTEGER,
              sqlite3_column_type(stmt, 1) == SQLITE_INTEGER,
              sqlite3_column_type(stmt, 2) == SQLITE_INTEGER,
              sqlite3_column_type(stmt, 3) == SQLITE_INTEGER else { return nil }

        let rowCount = sqlite3_column_int64(stmt, 2)
        let validZoomCount = sqlite3_column_int64(stmt, 3)
        let minimum = sqlite3_column_int64(stmt, 0)
        let maximum = sqlite3_column_int64(stmt, 1)
        guard rowCount > 0,
              rowCount == validZoomCount,
              minimum >= 0,
              maximum >= minimum,
              maximum <= Int64(Self.maximumZoom) else { return nil }
        return (Int(minimum), Int(maximum))
    }

    private func readKnownMetadataValues() -> [String: MetadataValue]? {
        struct RowDescriptor {
            let rowID: Int64
            let nameType: String
            let valueType: String
        }

        var descriptors: [RowDescriptor] = []
        var rowStatement: OpaquePointer?
        guard sqlite3_prepare_v2(
            db,
            "SELECT rowid, typeof(name), typeof(value) FROM metadata LIMIT 65",
            -1,
            &rowStatement,
            nil
        ) == SQLITE_OK else {
            sqlite3_finalize(rowStatement)
            return nil
        }
        while true {
            let step = sqlite3_step(rowStatement)
            if step == SQLITE_DONE { break }
            guard step == SQLITE_ROW else {
                sqlite3_finalize(rowStatement)
                return nil
            }
            guard descriptors.count < 64,
                  sqlite3_column_type(rowStatement, 0) == SQLITE_INTEGER,
                  sqlite3_column_type(rowStatement, 1) == SQLITE_TEXT,
                  sqlite3_column_type(rowStatement, 2) == SQLITE_TEXT,
                  let nameTypePointer = sqlite3_column_text(rowStatement, 1),
                  let valueTypePointer = sqlite3_column_text(rowStatement, 2) else {
                sqlite3_finalize(rowStatement)
                return nil
            }
            descriptors.append(RowDescriptor(
                rowID: sqlite3_column_int64(rowStatement, 0),
                nameType: String(cString: nameTypePointer),
                valueType: String(cString: valueTypePointer)
            ))
        }
        sqlite3_finalize(rowStatement)

        let limits: [String: (characters: Int, truncates: Bool)] = [
            "name": (128, true),
            "format": (32, true),
            "minzoom": (16, false),
            "maxzoom": (16, false),
            "bounds": (256, false)
        ]
        var result: [String: MetadataValue] = [:]
        for descriptor in descriptors {
            guard descriptor.nameType == "text",
                  case let .value(rawKey) = readMetadataText(
                    rowID: descriptor.rowID,
                    column: "name",
                    maximumCharacters: 32,
                    // Known MBTiles keys are short. A bounded prefix is enough
                    // to classify an arbitrarily long extension key as unknown
                    // without rejecting the otherwise-valid map or reading it.
                    truncateOversized: true
                  ) else { return nil }
            let key = rawKey.lowercased()
            guard let limit = limits[key] else { continue }
            guard result[key] == nil,
                  descriptor.valueType == "text" else { return nil }
            let value = readMetadataText(
                rowID: descriptor.rowID,
                column: "value",
                maximumCharacters: limit.characters,
                truncateOversized: limit.truncates
            )
            guard !value.isInvalid else { return nil }
            result[key] = value
        }
        return result
    }

    private func readMetadataText(
        rowID: Int64,
        column: String,
        maximumCharacters: Int,
        truncateOversized: Bool
    ) -> MetadataValue {
        // Incremental BLOB access gives us the encoded byte count from SQLite's
        // record header and lets us copy only a small prefix. Hostile metadata
        // keys and values therefore cannot make Foundation materialize their
        // full contents merely so the app can identify or truncate them.
        var blob: OpaquePointer?
        guard sqlite3_blob_open(
            db,
            "main",
            "metadata",
            column,
            rowID,
            0,
            &blob
        ) == SQLITE_OK else {
            if blob != nil { sqlite3_blob_close(blob) }
            return .invalid
        }
        defer { sqlite3_blob_close(blob) }

        let encodedByteCount = Int(sqlite3_blob_bytes(blob))
        let maximumPrefixBytes = maximumCharacters * 4
        guard encodedByteCount >= 0,
              truncateOversized || encodedByteCount <= maximumPrefixBytes else {
            return .invalid
        }
        if encodedByteCount == 0 { return .value("") }

        let bytesToRead = min(encodedByteCount, maximumPrefixBytes)
        var prefix = Data(count: bytesToRead)
        let readResult = prefix.withUnsafeMutableBytes { bytes in
            sqlite3_blob_read(blob, bytes.baseAddress, Int32(bytesToRead), 0)
        }
        guard readResult == SQLITE_OK, !prefix.contains(0) else { return .invalid }

        let wasTruncated = bytesToRead < encodedByteCount
        let drops = wasTruncated ? 0...min(3, prefix.count) : 0...0
        for droppedByteCount in drops {
            let candidate = prefix.dropLast(droppedByteCount)
            guard let decoded = String(data: candidate, encoding: .utf8) else { continue }
            guard truncateOversized || decoded.count <= maximumCharacters else {
                return .invalid
            }
            return .value(String(decoded.prefix(maximumCharacters)))
        }
        return .invalid
    }

    private func validatedMetadata(
        nameValue: MetadataValue,
        formatValue: MetadataValue,
        minimumZoomValue: MetadataValue,
        maximumZoomValue: MetadataValue,
        boundsValue: MetadataValue,
        tileZoomRange: (minimum: Int, maximum: Int)
    ) -> Metadata? {
        func optionalText(
            _ value: MetadataValue,
            maximumCharacters: Int,
            lowercased: Bool = false
        ) -> String? {
            guard case let .value(raw) = value else { return nil }
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return nil }
            let normalized = lowercased ? trimmed.lowercased() : trimmed
            return String(normalized.prefix(maximumCharacters))
        }

        func zoom(_ value: MetadataValue, fallback: Int) -> Int? {
            guard case let .value(raw) = value else { return fallback }
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty,
                  trimmed.utf8.count <= 2,
                  trimmed.utf8.allSatisfy({ $0 >= 48 && $0 <= 57 }),
                  trimmed.count == 1 || trimmed.first != "0",
                  let parsed = Int(trimmed),
                  parsed >= 0,
                  parsed <= Self.maximumZoom else { return nil }
            return parsed
        }

        guard let minimumZoom = zoom(minimumZoomValue, fallback: tileZoomRange.minimum),
              let maximumZoom = zoom(maximumZoomValue, fallback: tileZoomRange.maximum),
              minimumZoom <= maximumZoom else { return nil }

        var bounds: (minLon: Double, minLat: Double, maxLon: Double, maxLat: Double)?
        if case let .value(rawBounds) = boundsValue {
            let components = rawBounds.components(separatedBy: ",")
            guard components.count == 4 else { return nil }
            let parsed = components.compactMap {
                Double($0.trimmingCharacters(in: .whitespacesAndNewlines))
            }
            guard parsed.count == 4,
                  parsed.allSatisfy({ $0.isFinite }),
                  (-180.0...180.0).contains(parsed[0]),
                  (-90.0...90.0).contains(parsed[1]),
                  (-180.0...180.0).contains(parsed[2]),
                  (-90.0...90.0).contains(parsed[3]),
                  parsed[0] < parsed[2],
                  parsed[1] < parsed[3] else { return nil }
            bounds = (parsed[0], parsed[1], parsed[2], parsed[3])
        }

        return Metadata(
            name: optionalText(nameValue, maximumCharacters: 128),
            format: optionalText(
                formatValue,
                maximumCharacters: 32,
                lowercased: true
            ),
            minZoom: minimumZoom,
            maxZoom: maximumZoom,
            bounds: bounds
        )
    }

    private func bindTileCoordinates(
        _ statement: OpaquePointer?,
        z: Int,
        x: Int,
        tmsRow: Int
    ) {
        sqlite3_bind_int(statement, 1, Int32(z))
        sqlite3_bind_int(statement, 2, Int32(x))
        sqlite3_bind_int(statement, 3, Int32(tmsRow))
    }

    private func tilePayloadLength(z: Int, x: Int, tmsRow: Int) -> Int? {
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(
            db,
            "SELECT CASE WHEN typeof(tile_data)='blob' THEN length(tile_data) END " +
            "FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
            -1,
            &stmt,
            nil
        ) == SQLITE_OK else { return nil }
        bindTileCoordinates(stmt, z: z, x: x, tmsRow: tmsRow)
        guard sqlite3_step(stmt) == SQLITE_ROW,
              sqlite3_column_type(stmt, 0) == SQLITE_INTEGER else { return nil }
        let length = sqlite3_column_int64(stmt, 0)
        guard length > 0, length <= Int64(Self.maximumTileBytes) else { return nil }
        return Int(length)
    }

    private func readTilePayload(
        z: Int,
        x: Int,
        tmsRow: Int,
        expectedLength: Int
    ) -> Data? {
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(
            db,
            "SELECT CASE WHEN typeof(tile_data)='blob' " +
            "THEN CASE WHEN length(tile_data)=?4 THEN tile_data END END " +
            "FROM tiles WHERE zoom_level=?1 AND tile_column=?2 AND tile_row=?3 LIMIT 1",
            -1,
            &stmt,
            nil
        ) == SQLITE_OK else { return nil }
        bindTileCoordinates(stmt, z: z, x: x, tmsRow: tmsRow)
        sqlite3_bind_int64(stmt, 4, Int64(expectedLength))
        guard sqlite3_step(stmt) == SQLITE_ROW,
              sqlite3_column_type(stmt, 0) == SQLITE_BLOB else { return nil }

        // Recheck SQLite's column byte count before asking it for a BLOB pointer
        // or allocating Data. This also fails closed if the file changed between
        // the length-only query and this payload query.
        let byteCount = Int(sqlite3_column_bytes(stmt, 0))
        guard byteCount == expectedLength,
              byteCount > 0,
              byteCount <= Self.maximumTileBytes,
              let bytes = sqlite3_column_blob(stmt, 0) else { return nil }
        return Data(bytes: bytes, count: byteCount)
    }

    /// Raster tile bytes for an XYZ tile, or nil if the tile isn't present.
    /// MBTiles rows are TMS (y flipped vs XYZ): `tmsRow = (2^z - 1) - y`.
    func tileData(z: Int, x: Int, y: Int) -> Data? {
        guard z >= 0, z <= Self.maximumZoom else { return nil }
        if let minimumZoom = metadata.minZoom, z < minimumZoom { return nil }
        if let maximumZoom = metadata.maxZoom, z > maximumZoom { return nil }
        let width = 1 << z
        guard x >= 0, x < width, y >= 0, y < width else { return nil }
        let tmsRow = width - 1 - y
        lock.lock()
        defer { lock.unlock() }
        guard openDatabaseIfNeeded() else { return nil }
        guard let length = tilePayloadLength(z: z, x: x, tmsRow: tmsRow) else { return nil }
#if DEBUG
        payloadQueryCountForTesting += 1
#endif
        return readTilePayload(z: z, x: x, tmsRow: tmsRow, expectedLength: length)
    }

    /// Permanently retires this reader before its app-managed backing file is
    /// deleted. The same lock used by tile queries guarantees SQLite is never
    /// unlinked underneath an in-flight read, and late renderer callbacks fail
    /// closed instead of reopening the database.
    func closeForDeletion() {
        lock.lock()
        defer { lock.unlock() }
        closedForDeletion = true
        sqlite3_close(db)
        db = nil
    }
}
