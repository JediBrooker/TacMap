import Foundation
import SQLite3

/// Writes an MBTiles file (OSGeo spec): a SQLite DB with a `metadata` key/value
/// table and a `tiles` table of raster blobs. Write-side companion to
/// `MBTilesStore`; used by `PDFTiler` to bake a calibrated PDF into an offline
/// tile pyramid on-device (no desktop GDAL step).
final class MBTilesWriter {

    private var db: OpaquePointer?
    // SQLite wants to copy the bound blob/text before the statement is reset.
    private let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    /// Set true the moment any SQL step/exec returns an error (e.g. disk full).
    /// Callers MUST check this before treating the bake as successful — otherwise
    /// a half-written file is mistaken for a complete offline basemap and the
    /// source PDF gets discarded.
    private(set) var hadError = false

    init?(path: String) {
        try? FileManager.default.removeItem(atPath: path)
        guard sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, nil) == SQLITE_OK else {
            sqlite3_close(db); db = nil; return nil
        }
        exec("CREATE TABLE metadata (name TEXT, value TEXT);")
        exec("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);")
        exec("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row);")
    }

    deinit { close() }

    func writeMetadata(name: String, format: String = "png",
                       minZoom: Int, maxZoom: Int,
                       minLon: Double, minLat: Double, maxLon: Double, maxLat: Double) {
        put("name", name)
        put("format", format)
        put("type", "baselayer")
        put("version", "1.0")
        put("minzoom", String(minZoom))
        put("maxzoom", String(maxZoom))
        put("bounds", "\(minLon),\(minLat),\(maxLon),\(maxLat)")
    }

    /// Store one XYZ tile (converted to MBTiles' TMS row scheme).
    func putTile(z: Int, x: Int, y: Int, data: Data) {
        let tmsRow = (1 << z) - 1 - y
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db,
            "INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?,?,?,?)",
            -1, &stmt, nil) == SQLITE_OK else { hadError = true; return }
        sqlite3_bind_int(stmt, 1, Int32(z))
        sqlite3_bind_int(stmt, 2, Int32(x))
        sqlite3_bind_int(stmt, 3, Int32(tmsRow))
        data.withUnsafeBytes { raw in
            sqlite3_bind_blob(stmt, 4, raw.baseAddress, Int32(data.count), SQLITE_TRANSIENT)
        }
        if sqlite3_step(stmt) != SQLITE_DONE { hadError = true }
    }

    func begin()  { exec("BEGIN TRANSACTION;") }
    func commit() { exec("COMMIT;") }

    func close() {
        if db != nil { sqlite3_close(db); db = nil }
    }

    private func put(_ key: String, _ value: String) {
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, "INSERT INTO metadata (name, value) VALUES (?,?)", -1, &stmt, nil) == SQLITE_OK
        else { hadError = true; return }
        sqlite3_bind_text(stmt, 1, key, -1, SQLITE_TRANSIENT)
        sqlite3_bind_text(stmt, 2, value, -1, SQLITE_TRANSIENT)
        if sqlite3_step(stmt) != SQLITE_DONE { hadError = true }
    }

    private func exec(_ sql: String) {
        if sqlite3_exec(db, sql, nil, nil, nil) != SQLITE_OK { hadError = true }
    }
}
