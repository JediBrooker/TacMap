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
    static let maximumMetadataRows = 64
    static let maximumKeyCharacters = 32
    static let utf8PrefixBytesPerCharacter = 4
    static let knownValueMaximumCharacters: [String: Int] = [
        "name": 128, "format": 32, "minzoom": 16, "maxzoom": 16, "bounds": 256
    ]
    static let consumedBakeExtensionMaximumCharacters = 128
    /// 3.0.2 SEC-1 caps, every connection: SQLITE_LIMIT_LENGTH (one value) and
    /// SQLITE_LIMIT_SQL_LENGTH (a longer schema statement fails the schema load)
    static let maximumValueBytes = maximumTileBytes + 64 * 1024
    static let maximumSchemaSQLBytes = MBTilesViewShape.maximumSQLBytes
    /// generated columns (and pragma_table_xinfo hidden 2/3) need 3.31
    static let generatedColumnsMinimumSQLite: Int32 = 3_031_000
    /// One deadline for the admission after the relation checks (metadata,
    /// tile aggregate), only when tiles or metadata is a view. A view's join
    /// isn't bounded by the file, two tables are one scan the file bounds.
    static let admissionBudgetMs = 30_000
    /// Per statement against a view after admission (tile + extension reads).
    static let viewQueryBudgetMs = 2_000

    /// MBTiles 1.3 lets tiles and metadata be either. Deduplicated packs from
    /// node-mbtiles, TileMill, mbutil and MapTiler make tiles a view.
    private enum Relation {
        case table
        case view
    }

    /// Boxed so the C progress handler can read it through its void pointer.
    private final class QueryDeadline {
        let uptimeNanoseconds: UInt64
        init(milliseconds: Int) {
            uptimeNanoseconds = DispatchTime.now().uptimeNanoseconds
                + UInt64(max(0, milliseconds)) * 1_000_000
        }
    }

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
    private var tilesRelation = Relation.table
    private var metadataRelation = Relation.table
#if DEBUG
    /// A regression-test seam proving an oversized length preflight never
    /// advances to the payload query that can make SQLite expose BLOB bytes.
    private(set) var payloadQueryCountForTesting = 0
#endif
    /// MapKit calls tileData concurrently from multiple MKTileOverlay.loadTile
    /// threads. Single SQLite connection isn't thread-safe so just serialise
    /// every query behind this lock.
    private let lock = NSLock()

#if DEBUG
    /// called at the top of every admission open, so tests can look at the
    /// crash markers and guard files right where a hostile pack would kill us
    static var admissionOpenHookForTesting: ((URL) -> Void)?
#endif

    /// admissionBudgetMs is only a test seam, callers use the default.
    init?(url: URL, admissionBudgetMs: Int = MBTilesStore.admissionBudgetMs) {
        self.url = url
#if DEBUG
        Self.admissionOpenHookForTesting?(url)
#endif
        guard openConnection() else { return nil }
        // Reject files that aren't actually MBTiles: sqlite3_open succeeds on any
        // path, so without this check a garbage/corrupt file would load as a
        // "valid" but blank basemap. Both relations are mandatory, and all
        // security-sensitive metadata is validated before publishing a source.
        // Relations, view shape and base tables first, they read sqlite_master
        // only, then the metadata record probe. The budget's for views, two
        // tables get none
        var validated: Metadata?
        if loadRelationTypes() {
            validated = tilesRelation == .view || metadataRelation == .view
                ? withQueryBudget(milliseconds: admissionBudgetMs) { loadMetadata() }
                : loadMetadata()
        }
        guard let validatedMetadata = validated else {
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

    private func openConnection() -> Bool {
        // s15.2 schema probe: sqlite loads and parses every schema row at the
        // first statement, before any check of ours, and below 3.45 it loads a
        // value whole before SQLITE_LIMIT_LENGTH gets a look. it reads every
        // ANALYZE row (sqlite_stat1/4) whole with it too (PROBE-RT-1). so a
        // schema or stat table too big to load gets refused off the file before
        // sqlite even opens it. every MBTiles open comes through here:
        // admission, the lazy prevalidated open, import, the library
        // rebuild/salvage and migration
        guard Self.probeAllows({ try MBTilesRecordProbe.schema(url) }) else { return false }
        guard sqlite3_open_v2(url.path, &db, SQLITE_OPEN_READONLY, nil) == SQLITE_OK else {
            sqlite3_close(db)
            db = nil
            return false
        }
        // caps before the schema's even parsed, so before the first statement
        // (that's when the schema and stat rows load): one value can't go past a tile
        // + 64 KiB, so randomblob / zeroblob / printf in the file hit TOOBIG
        // instead of allocating, and a giant schema statement fails the load.
        // automatic indexes off or one big image gets copied into a temp index
        // on every read. trusted_schema off before anything touches the schema
        sqlite3_limit(db, SQLITE_LIMIT_LENGTH, Int32(Self.maximumValueBytes))
        sqlite3_limit(db, SQLITE_LIMIT_SQL_LENGTH, Int32(Self.maximumSchemaSQLBytes))
        guard sqlite3_exec(db, "PRAGMA automatic_index=OFF", nil, nil, nil) == SQLITE_OK,
              sqlite3_exec(db, "PRAGMA trusted_schema=OFF", nil, nil, nil) == SQLITE_OK else {
            sqlite3_close(db)
            db = nil
            return false
        }
        return true
    }

#if DEBUG
    /// what the live connection really runs with (LENGTH, SQL_LENGTH,
    /// automatic_index, trusted_schema). Opens lazily like a read would
    func connectionHardeningForTesting() -> (length: Int32, sqlLength: Int32, automaticIndex: Int32,
                                             trustedSchema: Int32)? {
        lock.lock()
        defer { lock.unlock() }
        guard openDatabaseIfNeeded() else { return nil }
        func pragma(_ name: String) -> Int32 {
            var stmt: OpaquePointer?
            defer { sqlite3_finalize(stmt) }
            guard sqlite3_prepare_v2(db, "PRAGMA \(name)", -1, &stmt, nil) == SQLITE_OK,
                  sqlite3_step(stmt) == SQLITE_ROW else { return -1 }
            return sqlite3_column_int(stmt, 0)
        }
        return (sqlite3_limit(db, SQLITE_LIMIT_LENGTH, -1), sqlite3_limit(db, SQLITE_LIMIT_SQL_LENGTH, -1),
                pragma("automatic_index"), pragma("trusted_schema"))
    }
#endif

    /// Lazy open for the prevalidated path. It still has to learn whether
    /// each relation is a table or a view since the read paths differ.
    private func openDatabaseIfNeeded() -> Bool {
        guard !closedForDeletion else { return false }
        if db != nil { return true }
        guard openConnection() else { return false }
        guard loadRelationTypes() else {
            sqlite3_close(db)
            db = nil
            return false
        }
        return true
    }

    /// a probe that throws (file gone, short read) refuses like one that says no
    private static func probeAllows(_ probe: () throws -> MBTilesRecordProbe.Refusal?) -> Bool {
        do {
            return try probe() == nil
        } catch {
            return false
        }
    }

    /// 3.0.2 SEC-1: both relations, a view's shape and every table a read
    /// touches, all from sqlite_master before any statement names tiles or
    /// metadata. After this no expression stored in the file can run on a read.
    /// 3.0.3 U2: then the metadata probe, before the admission budget and
    /// before anything reads metadata. the lazy open comes through here too
    private func loadRelationTypes() -> Bool {
        var found: [Relation] = []
        var metadataRoots: [Int64] = []
        for name in ["tiles", "metadata"] {
            guard let row = relationType(name) else { return false }
            let relation = row.relation
            let bases: [String]
            if relation == .view {
                guard let sql = row.sql,
                      case .success(let tables) = MBTilesViewShape.baseTables(sql: sql, relation: name) else {
                    return false
                }
                bases = tables
            } else {
                bases = [name]
            }
            var roots: [Int64] = []
            for base in bases {
                guard let table = ordinaryTable(base) else { return false }
                roots.append(table.rootPage)
            }
            if name == "metadata" { metadataRoots = roots }
            found.append(relation)
        }
        // s15.2: sqlite loads a whole name or value before length() or substr()
        // gets to cut it, and iOS 16-18 ship sqlite older than 3.45, which only
        // checks SQLITE_LIMIT_LENGTH after that load. so each table the metadata
        // reads touch (metadata, or its view's base tables) gets its rows and
        // record sizes read off the file first
        for root in metadataRoots {
            guard Self.probeAllows({ try MBTilesRecordProbe.metadataTable(url, root: root) }) else {
                return false
            }
        }
        tilesRelation = found[0]
        metadataRelation = found[1]
        return true
    }

    /// Exactly one sqlite_master row by that name and it has to be a table
    /// or a view. Index, trigger, missing or anything odd fails closed.
    /// SEC-M1-SHADOW: NOCASE, cos thats how sqlite resolves FROM tiles. a
    /// case-exact lookup checked a dormant 'tiles' row while reads ran a live
    /// 'TILES' view, so a second row in any case fails closed now
    private func relationType(_ name: String) -> (relation: Relation, sql: String?)? {
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(
            db,
            "SELECT type, sql FROM sqlite_master WHERE name = ? COLLATE NOCASE LIMIT 2",
            -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_text(stmt, 1, name, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
        guard sqlite3_step(stmt) == SQLITE_ROW,
              sqlite3_column_type(stmt, 0) == SQLITE_TEXT,
              let typePointer = sqlite3_column_text(stmt, 0) else { return nil }
        let type = String(cString: typePointer)
        // schema text is capped by SQL_LENGTH already, so this copy is bounded
        let sql = sqlite3_column_type(stmt, 1) == SQLITE_TEXT
            ? sqlite3_column_text(stmt, 1).map { String(cString: $0) } : nil
        guard sqlite3_step(stmt) == SQLITE_DONE else { return nil }
        switch type {
        case "table": return (.table, sql)
        case "view": return (.view, sql)
        default: return nil
        }
    }

    private struct OrdinaryTable {
        /// that row's rootpage, for the metadata record probe
        let rootPage: Int64
    }

    /// a table a read touches: one sqlite_master row (NOCASE, like SQLite
    /// resolves it), a real table not CREATE VIRTUAL TABLE (module code on
    /// every read), and no generated column (an expression on every read).
    /// its sql has to declare that very name too, no IF NOT EXISTS, so the
    /// row we checked is the table sqlite loaded (SEC-M1-SHADOW). and its
    /// rootpage has to be an integer, tiles and a tiles view's tables too
    /// (U2-IOS-3, android always refused that). sqlite only writes integers
    /// there, anything else is a hand edit. nil if it isn't one
    private func ordinaryTable(_ name: String) -> OrdinaryTable? {
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        guard sqlite3_prepare_v2(db, "SELECT type, sql, rootpage FROM sqlite_master WHERE name = ? COLLATE NOCASE " +
                                 "LIMIT 2", -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_text(stmt, 1, name, -1, transient)
        guard sqlite3_step(stmt) == SQLITE_ROW,
              sqlite3_column_type(stmt, 0) == SQLITE_TEXT,
              let typePointer = sqlite3_column_text(stmt, 0),
              String(cString: typePointer) == "table",
              sqlite3_column_type(stmt, 1) == SQLITE_TEXT,
              let sqlPointer = sqlite3_column_text(stmt, 1),
              MBTilesViewShape.tableDeclares(sql: String(cString: sqlPointer), name: name),
              sqlite3_column_type(stmt, 2) == SQLITE_INTEGER else { return nil }
        let rootPage = sqlite3_column_int64(stmt, 2)
        guard sqlite3_step(stmt) == SQLITE_DONE else { return nil }
        // below 3.31 a generated column can't exist, the schema wouldnt parse
        guard sqlite3_libversion_number() >= Self.generatedColumnsMinimumSQLite else {
            return OrdinaryTable(rootPage: rootPage)
        }
        var info: OpaquePointer?
        defer { sqlite3_finalize(info) }
        guard sqlite3_prepare_v2(db, "SELECT hidden FROM pragma_table_xinfo(?)", -1, &info, nil) == SQLITE_OK else {
            return nil
        }
        sqlite3_bind_text(info, 1, name, -1, transient)
        while true {
            let step = sqlite3_step(info)
            if step == SQLITE_DONE { return OrdinaryTable(rootPage: rootPage) }
            guard step == SQLITE_ROW else { return nil }
            let hidden = sqlite3_column_int(info, 0)
            if hidden == 2 || hidden == 3 { return nil }
        }
    }

    /// Runs body with a progress handler that interrupts whatever statement
    /// is stepping once the deadline passes. Interrupted steps come back as
    /// SQLITE_INTERRUPT which every reader already treats as failure.
    private func withQueryBudget<T>(milliseconds: Int, _ body: () -> T) -> T {
        let deadline = QueryDeadline(milliseconds: milliseconds)
        sqlite3_progress_handler(db, 1000, { context in
            guard let context else { return 1 }
            let deadline = Unmanaged<QueryDeadline>.fromOpaque(context).takeUnretainedValue()
            return DispatchTime.now().uptimeNanoseconds >= deadline.uptimeNanoseconds ? 1 : 0
        }, Unmanaged.passUnretained(deadline).toOpaque())
        defer { sqlite3_progress_handler(db, 0, nil, nil) }
        return withExtendedLifetime(deadline) { body() }
    }

    /// Tables get no per-read budget, views get viewQueryBudgetMs per statement.
    private func budgetedRead<T>(on relation: Relation, _ body: () -> T) -> T {
        guard relation == .view else { return body() }
        return withQueryBudget(milliseconds: Self.viewQueryBudgetMs, body)
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

    private struct MetadataDescriptor {
        // table path reads by rowid, view path already has the name prefix
        let rowID: Int64?
        let nameType: String
        let valueType: String
        let namePrefix: Data
        let nameByteCount: Int
    }

    private func readMetadataDescriptors() -> [MetadataDescriptor]? {
        let isView = metadataRelation == .view
        let prefixBytes = Self.maximumKeyCharacters * Self.utf8PrefixBytesPerCharacter
        // a view has no rowid, so it gets the bounded name prefix up front
        let sql = isView
            ? "SELECT typeof(name), typeof(value), length(CAST(name AS BLOB)), " +
              "substr(CAST(name AS BLOB), 1, \(prefixBytes)) FROM metadata LIMIT \(Self.maximumMetadataRows + 1)"
            : "SELECT rowid, typeof(name), typeof(value) FROM metadata LIMIT \(Self.maximumMetadataRows + 1)"
        var rowStatement: OpaquePointer?
        defer { sqlite3_finalize(rowStatement) }
        guard sqlite3_prepare_v2(db, sql, -1, &rowStatement, nil) == SQLITE_OK else { return nil }
        let typeColumn: Int32 = isView ? 0 : 1
        var descriptors: [MetadataDescriptor] = []
        while true {
            let step = sqlite3_step(rowStatement)
            if step == SQLITE_DONE { break }
            guard step == SQLITE_ROW,
                  descriptors.count < Self.maximumMetadataRows,
                  isView || sqlite3_column_type(rowStatement, 0) == SQLITE_INTEGER,
                  sqlite3_column_type(rowStatement, typeColumn) == SQLITE_TEXT,
                  sqlite3_column_type(rowStatement, typeColumn + 1) == SQLITE_TEXT,
                  let nameTypePointer = sqlite3_column_text(rowStatement, typeColumn),
                  let valueTypePointer = sqlite3_column_text(rowStatement, typeColumn + 1) else {
                return nil
            }
            let nameType = String(cString: nameTypePointer)
            let valueType = String(cString: valueTypePointer)
            if isView {
                // non-text names fail below anyway, dont bother with their bytes
                var prefix = Data()
                var byteCount = 0
                if nameType == "text" {
                    guard sqlite3_column_type(rowStatement, 2) == SQLITE_INTEGER,
                          let column = Self.boundedPrefixColumn(rowStatement, 3) else { return nil }
                    byteCount = Int(sqlite3_column_int64(rowStatement, 2))
                    prefix = column
                }
                descriptors.append(MetadataDescriptor(
                    rowID: nil, nameType: nameType, valueType: valueType,
                    namePrefix: prefix, nameByteCount: byteCount))
            } else {
                descriptors.append(MetadataDescriptor(
                    rowID: sqlite3_column_int64(rowStatement, 0), nameType: nameType,
                    valueType: valueType, namePrefix: Data(), nameByteCount: 0))
            }
        }
        return descriptors
    }

    private func readKnownMetadataValues() -> [String: MetadataValue]? {
        guard let descriptors = readMetadataDescriptors() else { return nil }

        let truncatedKeys: Set<String> = ["name", "format"]
        var result: [String: MetadataValue] = [:]
        for descriptor in descriptors {
            guard descriptor.nameType == "text" else { return nil }
            // Known MBTiles keys are short. A bounded prefix is enough
            // to classify an arbitrarily long extension key as unknown
            // without rejecting the otherwise-valid map or reading it.
            let keyValue: MetadataValue
            if let rowID = descriptor.rowID {
                keyValue = readMetadataText(
                    rowID: rowID,
                    column: "name",
                    maximumCharacters: Self.maximumKeyCharacters,
                    truncateOversized: true
                )
            } else {
                keyValue = Self.boundedText(
                    prefix: descriptor.namePrefix,
                    encodedByteCount: descriptor.nameByteCount,
                    maximumCharacters: Self.maximumKeyCharacters,
                    truncateOversized: true
                )
            }
            guard case let .value(rawKey) = keyValue else { return nil }
            let key = rawKey.lowercased()
            guard let characters = Self.knownValueMaximumCharacters[key] else { continue }
            guard result[key] == nil,
                  descriptor.valueType == "text" else { return nil }
            let truncates = truncatedKeys.contains(key)
            let value: MetadataValue
            if let rowID = descriptor.rowID {
                value = readMetadataText(
                    rowID: rowID,
                    column: "value",
                    maximumCharacters: characters,
                    truncateOversized: truncates
                )
            } else {
                value = readMetadataValue(
                    forKey: key,
                    maximumCharacters: characters,
                    truncateOversized: truncates
                )
            }
            guard !value.isInvalid else { return nil }
            result[key] = value
        }
        return result
    }

    /// Key-addressed read for a metadata view. Exactly one TEXT row or it's
    /// invalid, and only a bounded prefix ever leaves SQLite.
    private func readMetadataValue(
        forKey key: String,
        maximumCharacters: Int,
        truncateOversized: Bool
    ) -> MetadataValue {
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(
            db,
            "SELECT typeof(value), length(CAST(value AS BLOB)), substr(CAST(value AS BLOB), 1, ?2) " +
            "FROM metadata WHERE lower(name) = ?1 LIMIT 2",
            -1, &stmt, nil
        ) == SQLITE_OK else { return .invalid }
        sqlite3_bind_text(stmt, 1, key, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
        sqlite3_bind_int64(stmt, 2, Int64(maximumCharacters * Self.utf8PrefixBytesPerCharacter))
        guard sqlite3_step(stmt) == SQLITE_ROW,
              let typePointer = sqlite3_column_text(stmt, 0),
              String(cString: typePointer) == "text",
              sqlite3_column_type(stmt, 1) == SQLITE_INTEGER,
              let prefix = Self.boundedPrefixColumn(stmt, 2) else { return .invalid }
        let byteCount = Int(sqlite3_column_int64(stmt, 1))
        guard sqlite3_step(stmt) == SQLITE_DONE else { return .invalid }
        return Self.boundedText(
            prefix: prefix,
            encodedByteCount: byteCount,
            maximumCharacters: maximumCharacters,
            truncateOversized: truncateOversized
        )
    }

    /// The substr() column is already capped by SQLite, so copying it is bounded.
    private static func boundedPrefixColumn(_ stmt: OpaquePointer?, _ column: Int32) -> Data? {
        let type = sqlite3_column_type(stmt, column)
        guard type == SQLITE_BLOB || type == SQLITE_NULL else { return nil }
        let count = Int(sqlite3_column_bytes(stmt, column))
        guard count > 0 else { return Data() }
        guard let bytes = sqlite3_column_blob(stmt, column) else { return nil }
        return Data(bytes: bytes, count: count)
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
        let maximumPrefixBytes = maximumCharacters * Self.utf8PrefixBytesPerCharacter
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
        guard readResult == SQLITE_OK else { return .invalid }
        return Self.boundedText(
            prefix: prefix,
            encodedByteCount: encodedByteCount,
            maximumCharacters: maximumCharacters,
            truncateOversized: truncateOversized
        )
    }

    /// Shared by the blob and substr paths: length bound, NUL, and UTF-8 with
    /// up to 3 bytes dropped when the prefix cut a scalar in half.
    private static func boundedText(
        prefix: Data,
        encodedByteCount: Int,
        maximumCharacters: Int,
        truncateOversized: Bool
    ) -> MetadataValue {
        let maximumPrefixBytes = maximumCharacters * utf8PrefixBytesPerCharacter
        guard encodedByteCount >= 0,
              truncateOversized || encodedByteCount <= maximumPrefixBytes else {
            return .invalid
        }
        if encodedByteCount == 0 { return .value("") }
        guard prefix.count == min(encodedByteCount, maximumPrefixBytes),
              !prefix.contains(0) else { return .invalid }

        let wasTruncated = prefix.count < encodedByteCount
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
        guard let length = budgetedRead(on: tilesRelation, {
            tilePayloadLength(z: z, x: x, tmsRow: tmsRow)
        }) else { return nil }
#if DEBUG
        payloadQueryCountForTesting += 1
#endif
        return budgetedRead(on: tilesRelation) {
            readTilePayload(z: z, x: x, tmsRow: tmsRow, expectedLength: length)
        }
    }

    /// Only the two values the bake reader consumes. Ordinary map admission
    /// ignores unused extensions; consumption independently fails closed.
    func extensionMetadata(
        _ key: String,
        maximumCharacters: Int = MBTilesStore.consumedBakeExtensionMaximumCharacters
    ) -> String? {
        guard ["tacmap_bake_key", "tacmap_tile_px"].contains(key),
              (1...Self.consumedBakeExtensionMaximumCharacters).contains(maximumCharacters) else { return nil }
        lock.lock()
        defer { lock.unlock() }
        guard openDatabaseIfNeeded() else { return nil }
        if metadataRelation == .view {
            let value = budgetedRead(on: .view) {
                readMetadataValue(forKey: key, maximumCharacters: maximumCharacters, truncateOversized: false)
            }
            guard case let .value(text) = value else { return nil }
            return text
        }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(
            db,
            "SELECT rowid, typeof(value) FROM metadata WHERE lower(name) = ?1 LIMIT 2",
            -1, &stmt, nil
        ) == SQLITE_OK else {
            sqlite3_finalize(stmt)
            return nil
        }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        sqlite3_bind_text(stmt, 1, key, -1, transient)
        guard sqlite3_step(stmt) == SQLITE_ROW,
              sqlite3_column_type(stmt, 0) == SQLITE_INTEGER,
              let type = sqlite3_column_text(stmt, 1),
              String(cString: type) == "text" else {
            sqlite3_finalize(stmt)
            return nil
        }
        let rowID = sqlite3_column_int64(stmt, 0)
        let unique = sqlite3_step(stmt) == SQLITE_DONE
        sqlite3_finalize(stmt)
        guard unique,
              case let .value(value) = readMetadataText(
                rowID: rowID, column: "value", maximumCharacters: maximumCharacters,
                truncateOversized: false
              ) else { return nil }
        return value
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

#if DEBUG
    /// retired, no connection left and none will open again
    var isClosedForTesting: Bool {
        lock.lock()
        defer { lock.unlock() }
        return closedForDeletion && db == nil
    }
#endif
}
