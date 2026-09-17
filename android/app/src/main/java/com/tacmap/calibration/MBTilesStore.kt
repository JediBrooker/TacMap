package com.tacmap.calibration

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read-only reader for MBTiles - a SQLite DB of raster map tiles (OSGeo
 * MBTiles spec). Mirrors iOS MBTilesStore: serves tiles by XYZ coordinate
 * (converting to TMS row scheme that MBTiles stores) plus bounds and zoom
 * metadata. The data layer behind offline raster basemaps.
 *
 * Note: backed by android.database.sqlite so the TMS/XYZ flip is covered
 * by iOS MBTilesStoreTests (shared logic) rather than a host JVM test.
 */
class MBTilesStore private constructor(private val db: SQLiteDatabase) : Closeable {

    data class Metadata(
        val name: String? = null,
        val format: String? = null,
        val minZoom: Int,
        val maxZoom: Int,
        val bounds: Wgs84Bounds? = null
    )

    val metadata: Metadata = loadMetadata()
    private val closed = AtomicBoolean(false)

    private fun loadMetadata(): Metadata {
        val rows = linkedMapOf<String, String>()
        db.rawQuery("SELECT name, value FROM metadata", null).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0) ?: continue
                val value = c.getString(1) ?: continue
                rows[key.lowercase(Locale.US)] = value
            }
        }
        val tileZoomRange = db.rawQuery("SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles", null).use { c ->
            require(c.moveToFirst() && !c.isNull(0) && !c.isNull(1)) { "MBTiles has no raster tiles." }
            strictZoom(c.getString(0)) to strictZoom(c.getString(1))
        }
        return validatedMBTilesMetadata(rows, tileZoomRange)
    }

    /** Tile bytes for an XYZ tile, or null if not found. MBTiles rows use
     *  TMS (y flipped vs XYZ): tmsRow = (2^z - 1) - y. */
    @Synchronized
    fun tileData(z: Int, x: Int, y: Int): ByteArray? {
        if (closed.get() || !db.isOpen || z !in metadata.minZoom..metadata.maxZoom) return null
        val width = 1 shl z
        if (x !in 0 until width || y !in 0 until width) return null
        val tmsRow = (1 shl z) - 1 - y
        val args = arrayOf(z.toString(), x.toString(), tmsRow.toString())
        return runCatching {
            val length = db.rawQuery(
                "SELECT length(tile_data) FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                args
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else return null }
            if (length <= 0L || length > MAX_TILE_BYTES) return null
            // Query the blob only after the length-only CursorWindow proves it is
            // bounded; selecting both columns could materialize an attacker-sized
            // blob before getLong() had a chance to reject it.
            db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                args
            ).use { c -> if (c.moveToFirst()) c.getBlob(0)?.takeIf { it.size <= MAX_TILE_BYTES } else null }
        }.getOrNull()
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true) && db.isOpen) db.close()
    }

    internal fun isClosedForTesting(): Boolean = closed.get() || !db.isOpen

    companion object {
        private const val MAX_TILE_BYTES = 4 * 1024 * 1024
        internal const val MAX_ZOOM = 30

        fun open(path: String): MBTilesStore? {
            val db = try {
                SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
            } catch (_: Throwable) {
                return null
            }
            return try {
                MBTilesStore(db)
            } catch (_: Throwable) {
                runCatching { db.close() }
                null
            }
        }
    }
}

private fun strictZoom(value: String): Int {
    require(value.length <= 16) { "Invalid MBTiles zoom metadata." }
    val trimmed = value.trim()
    require(trimmed.matches(Regex("(?:0|[1-9][0-9]?)"))) { "Invalid MBTiles zoom metadata." }
    return trimmed.toInt().also {
        require(it in 0..MBTilesStore.MAX_ZOOM) { "MBTiles zoom is outside the supported range." }
    }
}

internal fun validatedMBTilesMetadata(
    rows: Map<String, String>,
    tileZoomRange: Pair<Int, Int>,
): MBTilesStore.Metadata {
    val tileMin = tileZoomRange.first
    val tileMax = tileZoomRange.second
    require(tileMin in 0..MBTilesStore.MAX_ZOOM && tileMax in tileMin..MBTilesStore.MAX_ZOOM) {
        "Invalid MBTiles tile zoom range."
    }
    val minZoom = rows["minzoom"]?.let(::strictZoom) ?: tileMin
    val maxZoom = rows["maxzoom"]?.let(::strictZoom) ?: tileMax
    require(minZoom <= maxZoom) { "MBTiles minzoom must not exceed maxzoom." }

    val bounds = rows["bounds"]?.let { encoded ->
        require(encoded.length <= 256) { "Invalid MBTiles bounds metadata." }
        val values = encoded.split(',')
        require(values.size == 4) { "Invalid MBTiles bounds metadata." }
        val parsed = values.map { component ->
            component.trim().toDoubleOrNull()
                ?.takeIf(Double::isFinite)
                ?: throw IllegalArgumentException("Invalid MBTiles bounds metadata.")
        }
        val (minLon, minLat, maxLon, maxLat) = parsed
        require(minLon in -180.0..180.0 && maxLon in -180.0..180.0 &&
            minLat in -90.0..90.0 && maxLat in -90.0..90.0 &&
            minLon < maxLon && minLat < maxLat
        ) { "MBTiles bounds are non-finite, out of range, or out of order." }
        Wgs84Bounds(
            southwest = Wgs84Coordinate(minLat, minLon),
            northeast = Wgs84Coordinate(maxLat, maxLon),
        )
    }

    return MBTilesStore.Metadata(
        name = rows["name"]?.trim()?.takeIf(String::isNotEmpty)?.take(128),
        format = rows["format"]?.trim()?.lowercase(Locale.US)
            ?.takeIf(String::isNotEmpty)?.take(32),
        minZoom = minZoom,
        maxZoom = maxZoom,
        bounds = bounds,
    )
}
