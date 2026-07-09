import Foundation
import SQLite3

/// Read-only reader for an MBTiles file (SQLite DB of raster map tiles, OSGeo
/// spec). Serves tiles by XYZ coordinate (converting to TMS row scheme that
/// MBTiles uses) plus bounds + zoom metadata. Data layer behind offline raster
/// basemaps: user sideloads a .mbtiles generated from a GeoPDF/raster (e.g.
/// via gdal_translate + gdal2tiles) and app serves it with no network.
final class MBTilesStore {

    struct Metadata {
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
    /// MapKit calls tileData concurrently from multiple MKTileOverlay.loadTile
    /// threads. Single SQLite connection isn't thread-safe so just serialise
    /// every query behind this lock.
    private let lock = NSLock()

    init?(url: URL) {
        self.url = url
        guard sqlite3_open_v2(url.path, &db, SQLITE_OPEN_READONLY, nil) == SQLITE_OK else {
            sqlite3_close(db)
            db = nil
            return nil
        }
        // Reject files that arent actually MBTiles: sqlite3_open succeeds on any
        // path, so without this check a garbage/corrupt file would load as a
        // "valid" but blank basemap. Require the mandatory `tiles` table.
        guard hasTable("tiles") else {
            sqlite3_close(db)
            db = nil
            return nil
        }
        loadMetadata()
    }

    deinit { sqlite3_close(db) }

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

    private func loadMetadata() {
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, "SELECT name, value FROM metadata", -1, &stmt, nil) == SQLITE_OK
        else { return }
        while sqlite3_step(stmt) == SQLITE_ROW {
            guard let nameC = sqlite3_column_text(stmt, 0),
                  let valueC = sqlite3_column_text(stmt, 1) else { continue }
            let name = String(cString: nameC)
            let value = String(cString: valueC)
            switch name {
            case "name":    metadata.name = value
            case "format":  metadata.format = value
            case "minzoom": metadata.minZoom = Int(value)
            case "maxzoom": metadata.maxZoom = Int(value)
            case "bounds":
                let p = value.split(separator: ",")
                    .compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
                if p.count == 4 { metadata.bounds = (p[0], p[1], p[2], p[3]) }
            default: break
            }
        }
    }

    /// Raster tile bytes for an XYZ tile, or nil if the tile isn't present.
    /// MBTiles rows are TMS (y flipped vs XYZ): `tmsRow = (2^z - 1) - y`.
    func tileData(z: Int, x: Int, y: Int) -> Data? {
        guard z >= 0, z < 32 else { return nil }
        let tmsRow = (1 << z) - 1 - y
        lock.lock()
        defer { lock.unlock() }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(
            db,
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            -1, &stmt, nil) == SQLITE_OK
        else { return nil }
        sqlite3_bind_int(stmt, 1, Int32(z))
        sqlite3_bind_int(stmt, 2, Int32(x))
        sqlite3_bind_int(stmt, 3, Int32(tmsRow))
        guard sqlite3_step(stmt) == SQLITE_ROW,
              let blob = sqlite3_column_blob(stmt, 0) else { return nil }
        let count = Int(sqlite3_column_bytes(stmt, 0))
        return Data(bytes: blob, count: count)
    }
}
