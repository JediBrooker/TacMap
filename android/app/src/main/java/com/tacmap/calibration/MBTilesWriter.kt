package com.tacmap.calibration

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Writes an MBTiles file (OSGeo spec): a SQLite DB with a `metadata` key/value
 * table and a `tiles` table of raster blobs. The write-side companion to
 * [MBTilesStore]; used by [PdfTiler] to bake a calibrated PDF into an offline
 * tile pyramid on-device (no desktop GDAL step).
 */
class MBTilesWriter private constructor(private val db: SQLiteDatabase) {

    fun writeMetadata(
        name: String,
        format: String = "png",
        minZoom: Int,
        maxZoom: Int,
        bounds: Wgs84Bounds
    ) {
        fun put(key: String, value: String) {
            db.insert("metadata", null, ContentValues().apply {
                put("name", key); put("value", value)
            })
        }
        put("name", name)
        put("format", format)
        put("type", "baselayer")
        put("version", "1.0")
        put("minzoom", minZoom.toString())
        put("maxzoom", maxZoom.toString())
        // MBTiles bounds metadata: "minLon,minLat,maxLon,maxLat".
        put(
            "bounds",
            "%f,%f,%f,%f".format(
                bounds.southwest.longitude, bounds.southwest.latitude,
                bounds.northeast.longitude, bounds.northeast.latitude
            )
        )
    }

    /** Store one XYZ tile (converted to MBTiles' TMS row scheme). */
    fun putTile(z: Int, x: Int, y: Int, data: ByteArray) {
        val tmsRow = (1 shl z) - 1 - y
        db.insertWithOnConflict("tiles", null, ContentValues().apply {
            put("zoom_level", z)
            put("tile_column", x)
            put("tile_row", tmsRow)
            put("tile_data", data)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun beginBatch() = db.beginTransaction()
    fun commitBatch() { db.setTransactionSuccessful(); db.endTransaction() }

    fun close() = db.close()

    companion object {
        /** Create (overwriting) an MBTiles file at [path]. */
        fun create(path: String): MBTilesWriter? = try {
            File(path).delete()
            val db = SQLiteDatabase.openOrCreateDatabase(path, null)
            db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
            db.execSQL(
                "CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, " +
                    "tile_row INTEGER, tile_data BLOB)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX tile_index ON tiles " +
                    "(zoom_level, tile_column, tile_row)"
            )
            MBTilesWriter(db)
        } catch (_: Throwable) {
            null
        }
    }
}
