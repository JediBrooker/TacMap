import Foundation
import SQLite3

/// Writes an MBTiles file (OSGeo spec): SQLite DB with a metadata key/value
/// table and a tiles table of raster blobs. Write-side companion to MBTilesStore;
/// PDFBakeWorker uses it to bake a calibrated PDF into an offline tile pyramid
/// on-device, no desktop GDAL step needed. Single thread, whoever owns it.
final class MBTilesWriter {

    private var db: OpaquePointer?
    private var insert: OpaquePointer?
    // SQLite wants to copy the bound blob/text before the statement is reset.
    private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    /// Set true the moment any SQL step/exec returns an error (e.g. disk full).
    /// Callers MUST check this before treating the bake as successful, otherwise
    /// a half-written file gets mistaken for a complete offline basemap.
    private(set) var hadError = false
    /// FIRST sqlite result code that went wrong (R6), SQLITE_FULL means no
    /// space. a later BEGIN/COMMIT failing after a full disk never hides it
    private(set) var lastErrorCode: Int32 = SQLITE_OK
    /// what PRAGMA journal_mode actually answered, "off" or we failed
    private(set) var journalMode: String?

    init?(path: String) {
        let fm = FileManager.default
        try? fm.removeItem(atPath: path)
        // make the file ourselves first so it has the right protection class
        // before sqlite opens it. .complete (the old default here) made bakes
        // die ~10 s after the phone locked during a background track (D3-10)
        guard fm.createFile(atPath: path, contents: nil,
                            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]) else {
            return nil
        }
        guard sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE, nil) == SQLITE_OK else {
            sqlite3_close(db); db = nil; return nil
        }
        // a .partial is throwaway until published, no journal needed. the
        // pragma answers with the mode it ended up in, check it, before any
        // transaction. OD-F5: the system SQLite runs in defensive mode, which
        // quietly ignores OFF (it answered "delete", hence the -journal mid
        // bake). Swift cant reach the variadic sqlite3_db_config to turn that
        // off, so MEMORY it is: still nothing on disk next to the .partial
        journalMode = queryText("PRAGMA journal_mode=OFF;")?.lowercased()
        if journalMode != "off" { journalMode = queryText("PRAGMA journal_mode=MEMORY;")?.lowercased() }
        if journalMode != "off", journalMode != "memory" { fail(SQLITE_ERROR) }
        exec("PRAGMA synchronous=OFF;")
        exec("CREATE TABLE metadata (name TEXT, value TEXT);")
        exec("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);")
        exec("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row);")
        if sqlite3_prepare_v2(db,
            "INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?,?,?,?)",
            -1, &insert, nil) != SQLITE_OK { fail(sqlite3_errcode(db)) }
        if hadError { close(); return nil }
    }

    deinit { close() }

    func writeMetadata(name: String, format: String = "png",
                       minZoom: Int, maxZoom: Int,
                       minLon: Double, minLat: Double, maxLon: Double, maxLat: Double,
                       extra: [String: String] = [:]) {
        put("name", name)
        put("format", format)
        put("type", "baselayer")
        put("version", "1.0")
        put("minzoom", String(minZoom))
        put("maxzoom", String(maxZoom))
        put("bounds", "\(minLon),\(minLat),\(maxLon),\(maxLat)")
        for (k, v) in extra.sorted(by: { $0.key < $1.key }) { put(k, v) }
    }

    /// Store one XYZ tile (converted to MBTiles' TMS row scheme).
    func putTile(z: Int, x: Int, y: Int, data: Data) {
        guard let insert else { hadError = true; return }
        let tmsRow = (1 << z) - 1 - y
        sqlite3_reset(insert)
        sqlite3_clear_bindings(insert)
        sqlite3_bind_int(insert, 1, Int32(z))
        sqlite3_bind_int(insert, 2, Int32(x))
        sqlite3_bind_int(insert, 3, Int32(tmsRow))
        _ = data.withUnsafeBytes { raw in
            sqlite3_bind_blob(insert, 4, raw.baseAddress, Int32(data.count), SQLITE_TRANSIENT)
        }
        let rc = sqlite3_step(insert)
        if rc != SQLITE_DONE { fail(rc) }
    }

    func begin()  { exec("BEGIN TRANSACTION;") }
    func commit() { exec("COMMIT;") }

    func close() {
        if insert != nil { sqlite3_finalize(insert); insert = nil }
        if db != nil { sqlite3_close(db); db = nil }
    }

    private func put(_ key: String, _ value: String) {
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, "INSERT INTO metadata (name, value) VALUES (?,?)", -1, &stmt, nil) == SQLITE_OK
        else { fail(sqlite3_errcode(db)); return }
        sqlite3_bind_text(stmt, 1, key, -1, SQLITE_TRANSIENT)
        sqlite3_bind_text(stmt, 2, value, -1, SQLITE_TRANSIENT)
        let rc = sqlite3_step(stmt)
        if rc != SQLITE_DONE { fail(rc) }
    }

    func exec(_ sql: String) {
        let rc = sqlite3_exec(db, sql, nil, nil, nil)
        if rc != SQLITE_OK { fail(rc) }
    }

    /// test hook: a tiny page budget makes the next writes hit SQLITE_FULL
    func limitPagesForTesting(_ pages: Int) {
        exec("PRAGMA max_page_count=\(pages);")
    }

    /// first column of the first row, for pragmas that answer
    private func queryText(_ sql: String) -> String? {
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { fail(sqlite3_errcode(db)); return nil }
        let rc = sqlite3_step(stmt)
        guard rc == SQLITE_ROW, let c = sqlite3_column_text(stmt, 0) else {
            if rc != SQLITE_DONE { fail(rc) }
            return nil
        }
        return String(cString: c)
    }

    private func fail(_ rc: Int32) {
        // keep the first one, thats the real cause
        if !hadError { lastErrorCode = rc & 0xFF }
        hadError = true
    }
}
