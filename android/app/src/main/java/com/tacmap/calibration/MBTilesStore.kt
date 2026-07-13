package com.tacmap.calibration

import android.database.sqlite.SQLiteDatabase

/**
 * Read-only reader for MBTiles - a SQLite DB of raster map tiles (OSGeo
 * MBTiles spec). Mirrors iOS MBTilesStore: serves tiles by XYZ coordinate
 * (converting to TMS row scheme that MBTiles stores) plus bounds and zoom
 * metadata. The data layer behind offline raster basemaps.
 *
 * Note: backed by android.database.sqlite so the TMS/XYZ flip is covered
 * by iOS MBTilesStoreTests (shared logic) rather than a host JVM test.
 */
class MBTilesStore private constructor(private val db: SQLiteDatabase) {

    data class Metadata(
        val name: String? = null,
        val format: String? = null,
        val minZoom: Int? = null,
        val maxZoom: Int? = null,
        val bounds: Wgs84Bounds? = null
    )

    val metadata: Metadata = loadMetadata()

    private fun loadMetadata(): Metadata {
        var name: String? = null
        var format: String? = null
        var minZoom: Int? = null
        var maxZoom: Int? = null
        var bounds: Wgs84Bounds? = null
        db.rawQuery("SELECT name, value FROM metadata", null).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0) ?: continue
                val value = c.getString(1) ?: continue
                when (key) {
                    "name" -> name = value
                    "format" -> format = value
                    "minzoom" -> minZoom = value.toIntOrNull()
                    "maxzoom" -> maxZoom = value.toIntOrNull()
                    "bounds" -> {
                        // MBTiles bounds: "minLon,minLat,maxLon,maxLat"
                        val p = value.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                        if (p.size == 4) {
                            bounds = Wgs84Bounds(
                                southwest = Wgs84Coordinate(p[1], p[0]),
                                northeast = Wgs84Coordinate(p[3], p[2])
                            )
                        }
                    }
                }
            }
        }
        return Metadata(name, format, minZoom, maxZoom, bounds)
    }

    /** Tile bytes for an XYZ tile, or null if not found. MBTiles rows use
     *  TMS (y flipped vs XYZ): tmsRow = (2^z - 1) - y. */
    fun tileData(z: Int, x: Int, y: Int): ByteArray? {
        if (z < 0 || z >= 32) return null
        val tmsRow = (1 shl z) - 1 - y
        val args = arrayOf(z.toString(), x.toString(), tmsRow.toString())
        val length = db.rawQuery(
            "SELECT length(tile_data) FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            args
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else return null }
        if (length <= 0L || length > MAX_TILE_BYTES) return null
        // Query the blob only after the length-only CursorWindow proves it is
        // bounded; selecting both columns could materialize an attacker-sized
        // blob before getLong() had a chance to reject it.
        return db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            args
        ).use { c -> if (c.moveToFirst()) c.getBlob(0)?.takeIf { it.size <= MAX_TILE_BYTES } else null }
    }

    fun close() = db.close()

    companion object {
        private const val MAX_TILE_BYTES = 4 * 1024 * 1024
        fun open(path: String): MBTilesStore? = try {
            MBTilesStore(SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY))
        } catch (_: Throwable) {
            null
        }
    }
}
