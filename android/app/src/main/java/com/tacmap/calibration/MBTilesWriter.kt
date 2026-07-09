package com.tacmap.calibration

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.Locale

/**
 * Writes an MBTiles file (OSGeo spec): SQLite DB with `metadata` key/value
 * table and `tiles` table of raster blobs. Write-side companion to
 * [MBTilesStore]; used by [PdfTiler] to bake a calibrated PDF into an
 * offline tile pyramid on-device without needing desktop GDAL.
 */
class MBTilesWriter private constructor(private val db: SQLiteDatabase) {

    /** True once any insert failed (e.g. disk full). Callers MUST check
     *  this before treating the bake as done - otherwise a half-written
     *  file gets mistaken for a finished basemap and the source PDF gets
     *  discarded. */
    var hadError = false
        private set

    fun writeMetadata(
        name: String,
        format: String = "png",
        minZoom: Int,
        maxZoom: Int,
        bounds: Wgs84Bounds
    ) {
        fun put(key: String, value: String) {
            if (db.insert("metadata", null, ContentValues().apply {
                    put("name", key); put("value", value)
                }) == -1L) hadError = true
        }
        put("name", name)
        put("format", format)
        put("type", "baselayer")
        put("version", "1.0")
        put("minzoom", minZoom.toString())
        put("maxzoom", maxZoom.toString())
        // MBTiles bounds: "minLon,minLat,maxLon,maxLat". Force Locale.US so
        // a comma-decimal locale doesn't corrupt the comma-separated field.
        put(
            "bounds",
            "%f,%f,%f,%f".format(
                Locale.US,
                bounds.southwest.longitude, bounds.southwest.latitude,
                bounds.northeast.longitude, bounds.northeast.latitude
            )
        )
    }

    /** Store one XYZ tile (converts to MBTiles TMS row scheme). */
    fun putTile(z: Int, x: Int, y: Int, data: ByteArray) {
        val tmsRow = (1 shl z) - 1 - y
        val rowId = db.insertWithOnConflict("tiles", null, ContentValues().apply {
            put("zoom_level", z)
            put("tile_column", x)
            put("tile_row", tmsRow)
            put("tile_data", data)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (rowId == -1L) hadError = true
    }

    fun beginBatch() = db.beginTransaction()
    fun commitBatch() { db.setTransactionSuccessful(); db.endTransaction() }

    fun close() = db.close()

    companion object {
        /** Create (clobbers existing) an MBTiles file at [path]. */
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
