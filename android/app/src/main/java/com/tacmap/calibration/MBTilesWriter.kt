package com.tacmap.calibration

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.Locale

internal fun deleteMBTilesSidecars(file: File) {
    listOf("-journal", "-wal", "-shm").forEach { suffix ->
        runCatching { File(file.path + suffix).delete() }
    }
}

/**
 * journal_mode for a .partial (R2-S3): OFF, or MEMORY if this sqlite won't do OFF. [set] runs
 * the PRAGMA and hands back what sqlite says it is now. Whatever else comes back the writer
 * can't promise there's no -journal next to the file, so it throws and the bake is writeFailed
 */
internal fun applyNoDiskJournal(set: (String) -> String?): String {
    for (mode in listOf("off", "memory")) {
        val answer = set(mode)?.lowercase(Locale.US)
        if (answer == mode) return mode
    }
    throw IllegalStateException("sqlite kept an on-disk journal")
}

internal fun deleteMBTilesArtifacts(file: File) {
    runCatching { file.delete() }
    deleteMBTilesSidecars(file)
}

/**
 * Writes an MBTiles file (OSGeo spec): SQLite DB with `metadata` key/value
 * table and `tiles` table of raster blobs. Write-side companion to
 * [MBTilesStore]; used by [PdfBaker] to pre-render a calibrated PDF into an
 * offline tile pyramid on-device without needing desktop GDAL.
 */
class MBTilesWriter private constructor(private val db: SQLiteDatabase) {
    // one compiled insert for the whole bake instead of a ContentValues per tile
    private val insertTile = db.compileStatement(
        "INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)"
    )

    /** True once any insert failed (e.g. disk full). Callers MUST check
     *  this before treating the bake as done - otherwise a half-written
     *  file gets mistaken for a finished basemap and the source PDF gets
     *  discarded. */
    var hadError = false
        private set

    /** the first thing that went wrong, so a full disk can be told apart from the rest (J2) */
    var firstError: Throwable? = null
        private set

    private fun failed(t: Throwable?) {
        hadError = true
        if (firstError == null) firstError = t
    }

    fun writeMetadata(
        name: String,
        format: String = "webp",
        minZoom: Int,
        maxZoom: Int,
        bounds: Wgs84Bounds,
        /** tacmap_bake_key, tacmap_tile_px, tacmap_renderer for a PDF bake */
        extras: Map<String, String> = emptyMap(),
    ) {
        fun put(key: String, value: String) {
            // insertOrThrow, plain insert swallows the SQLiteFullException we need to see
            try {
                db.insertOrThrow("metadata", null, ContentValues().apply {
                    this.put("name", key)
                    this.put("value", value)
                })
            } catch (e: android.database.SQLException) {
                failed(e)
            }
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
        extras.forEach { (k, v) -> put(k, v) }
    }

    /** Store one XYZ tile (converts to MBTiles TMS row scheme). */
    fun putTile(z: Int, x: Int, y: Int, data: ByteArray) {
        val tmsRow = (1 shl z) - 1 - y
        try {
            insertTile.clearBindings()
            insertTile.bindLong(1, z.toLong())
            insertTile.bindLong(2, x.toLong())
            insertTile.bindLong(3, tmsRow.toLong())
            insertTile.bindBlob(4, data)
            if (insertTile.executeInsert() == -1L) failed(null)
        } catch (e: android.database.SQLException) {
            failed(e)
        }
    }

    fun beginBatch() = db.beginTransaction()
    fun commitBatch() { db.setTransactionSuccessful(); db.endTransaction() }

    /** give up on a half written file: drop any open batch and close. same thread as the writes */
    fun abort() {
        runCatching { if (db.inTransaction()) db.endTransaction() }
        runCatching { close() }
    }

    fun close() {
        runCatching { insertTile.close() }
        db.close()
    }

    companion object {
        /** Create (clobbers existing) an MBTiles file at [path]. [onError] sees why when it returns null */
        fun create(path: String, onError: (Throwable) -> Unit = {}): MBTilesWriter? {
            val file = File(path)
            file.delete()
            var db: SQLiteDatabase? = null
            return try {
                val opened = SQLiteDatabase.openOrCreateDatabase(path, null)
                db = opened
                // a .partial that only ever gets published whole: no journal, no fsync per commit.
                // rawQuery because PRAGMA journal_mode returns a row and execSQL refuses those.
                // read the answer back, sqlite just ignores a mode it won't do (R2-S3)
                applyNoDiskJournal { mode ->
                    opened.rawQuery("PRAGMA journal_mode=$mode", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }
                opened.rawQuery("PRAGMA synchronous=OFF", null).use { it.moveToFirst() }
                opened.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
                opened.execSQL(
                    "CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, " +
                        "tile_row INTEGER, tile_data BLOB)"
                )
                opened.execSQL(
                    "CREATE UNIQUE INDEX tile_index ON tiles " +
                        "(zoom_level, tile_column, tile_row)"
                )
                MBTilesWriter(opened)
            } catch (t: Throwable) {
                onError(t)
                runCatching { db?.close() }
                deleteMBTilesArtifacts(file)
                null
            }
        }
    }
}
