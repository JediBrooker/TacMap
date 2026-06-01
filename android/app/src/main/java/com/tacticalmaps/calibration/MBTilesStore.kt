package com.tacticalmaps.calibration

import android.database.sqlite.SQLiteDatabase

/**
 * Read-only reader for an MBTiles file — a SQLite database of raster map tiles
 * (the OSGeo MBTiles spec). Mirrors the iOS MBTilesStore: serves tiles by XYZ
 * coordinate (converting to the TMS row scheme MBTiles stores) plus the bounds
 * and zoom metadata. The data layer behind an offline raster basemap.
 *
 * Note: backed by android.database.sqlite, so the TMS↔XYZ flip is covered by
 * the iOS MBTilesStoreTests (shared logic) rather than a host JVM unit test.
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
                        // MBTiles bounds metadata is "minLon,minLat,maxLon,maxLat".
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

    /** Raster tile bytes for an XYZ tile, or null if absent. MBTiles rows are
     *  TMS (y flipped vs XYZ): `tmsRow = (2^z - 1) - y`. */
    fun tileData(z: Int, x: Int, y: Int): ByteArray? {
        if (z < 0 || z >= 32) return null
        val tmsRow = (1 shl z) - 1 - y
        db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(z.toString(), x.toString(), tmsRow.toString())
        ).use { c ->
            return if (c.moveToFirst()) c.getBlob(0) else null
        }
    }

    fun close() = db.close()

    companion object {
        fun open(path: String): MBTilesStore? = try {
            MBTilesStore(SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY))
        } catch (_: Throwable) {
            null
        }
    }
}
