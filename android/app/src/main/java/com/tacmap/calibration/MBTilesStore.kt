package com.tacmap.calibration

import com.tacmap.localization.L10n

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.VisibleForTesting
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
class MBTilesStore private constructor(
    private val db: SQLiteDatabase,
    admissionBudgetMs: Long,
) : Closeable {

    data class Metadata(
        val name: String? = null,
        val format: String? = null,
        val minZoom: Int,
        val maxZoom: Int,
        val bounds: Wgs84Bounds? = null
    )

    // one deadline for every admission statement, a view's row count isn't bounded by the file
    private val admissionDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(admissionBudgetMs)
    private val metadataIsView: Boolean
    private val tilesIsView: Boolean

    init {
        // stops a hostile view calling non-innocuous functions. API 31+ only, older SQLite
        // doesn't know the pragma and ignores it
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { db.rawQuery("PRAGMA trusted_schema=OFF", null).use { it.moveToFirst() } }
        }
        // MBTiles 1.3 lets either one be a view (node-mbtiles/TileMill/MapTiler dedup packs)
        val types = listOf("metadata", "tiles").map { relation ->
            admit("SELECT type FROM sqlite_master WHERE name=?", arrayOf(relation)) { c ->
                require(c.moveToFirst())
                val type = c.getString(0)
                require(type in RELATION_TYPES && !c.moveToNext())
                type
            }
        }
        metadataIsView = types[0] == "view"
        tilesIsView = types[1] == "view"
    }

    private val rows = linkedMapOf<String, String>()
    val metadata: Metadata = loadMetadata()
    private val closed = AtomicBoolean(false)

    /** Only the two provenance fields consumed by our bake reader are read.
     * Malformed extensions mismatch a bake; they do not reject an ordinary pack. */
    @Synchronized
    fun rawMetadata(key: String): String? {
        val normalized = key.lowercase(Locale.US)
        if (closed.get() || !db.isOpen || normalized !in BAKE_EXTENSION_KEYS) return null
        if (metadataIsView) return runCatching {
            readValueByKey(normalized, MAX_BAKE_EXTENSION_CHARACTERS, false, VIEW_QUERY_BUDGET_MS)
        }.getOrNull()
        return runCatching {
            val rowId = db.rawQuery(
                "SELECT rowid, typeof(value) FROM metadata WHERE lower(name)=? LIMIT 2",
                arrayOf(normalized),
            ).use { c ->
                if (!c.moveToFirst() || c.getType(0) != android.database.Cursor.FIELD_TYPE_INTEGER ||
                    c.getString(1) != "text") return@runCatching null
                val first = c.getLong(0)
                if (c.moveToNext()) return@runCatching null
                first
            }
            readMetadataPrefix(rowId, "value", MAX_BAKE_EXTENSION_CHARACTERS, false, null)
        }.getOrNull()
    }

    private fun loadMetadata(): Metadata {
        admit("PRAGMA encoding", null) { c ->
            require(c.moveToFirst() && c.getString(0).equals("UTF-8", ignoreCase = true))
        }
        if (metadataIsView) loadViewMetadata() else loadTableMetadata()
        // Aggregate only validated INTEGER scalars. MIN/MAX of arbitrary TEXT
        // would otherwise copy a hostile zoom string into CursorWindow.
        val tileZoomRange = admit(
            """
            SELECT MIN(CASE WHEN typeof(zoom_level)='integer' THEN zoom_level END),
                   MAX(CASE WHEN typeof(zoom_level)='integer' THEN zoom_level END),
                   COUNT(*),
                   SUM(CASE WHEN typeof(zoom_level)='integer'
                                      AND typeof(tile_column)='integer'
                                      AND typeof(tile_row)='integer'
                            THEN CASE WHEN zoom_level BETWEEN 0 AND $MAX_ZOOM
                                               AND tile_column BETWEEN 0 AND ((1 << zoom_level) - 1)
                                               AND tile_row BETWEEN 0 AND ((1 << zoom_level) - 1)
                                      THEN 1 ELSE 0 END ELSE 0 END)
            FROM tiles
            """.trimIndent(), null,
        ) { c ->
            require(c.moveToFirst() && (0..3).all { c.getType(it) == Cursor.FIELD_TYPE_INTEGER })
            val minimum = c.getLong(0); val maximum = c.getLong(1)
            require(c.getLong(2) > 0 && c.getLong(2) == c.getLong(3) &&
                minimum in 0..MAX_ZOOM.toLong() && maximum in minimum..MAX_ZOOM.toLong())
            minimum.toInt() to maximum.toInt()
        }
        return validatedMBTilesMetadata(rows, tileZoomRange)
    }

    private fun loadTableMetadata() {
        // Descriptor admission never selects attacker-sized strings/blobs into
        // CursorWindow. Match the existing iOS 64-row metadata policy.
        data class Descriptor(val rowId: Long, val nameType: String, val valueType: String)
        val descriptors = mutableListOf<Descriptor>()
        admit("SELECT rowid, typeof(name), typeof(value) FROM metadata LIMIT 65", null) { c ->
            while (c.moveToNext()) {
                require(descriptors.size < MAX_METADATA_ROWS && c.getType(0) == Cursor.FIELD_TYPE_INTEGER)
                descriptors += Descriptor(c.getLong(0), c.getString(1), c.getString(2))
            }
        }
        for (descriptor in descriptors) {
            require(descriptor.nameType == "text")
            val key = readMetadataPrefix(descriptor.rowId, "name", MAX_METADATA_KEY_CHARACTERS, true, admissionLeftMs())
                .lowercase(Locale.US)
            val (characters, truncates) = METADATA_VALUE_LIMITS[key] ?: continue // never select unknown extension values
            require(descriptor.valueType == "text" && key !in rows)
            rows[key] = readMetadataPrefix(descriptor.rowId, "value", characters, truncates, admissionLeftMs())
        }
    }

    // a view has no rowid, so the key comes back as a bounded prefix with its descriptor
    // and known values are looked up by key. same bounds and fail-closed rules as the table
    private fun loadViewMetadata() {
        class Descriptor(val nameType: String, val valueType: String, val nameLength: Long?, val namePrefix: ByteArray?)
        val descriptors = mutableListOf<Descriptor>()
        val keyBytes = MAX_METADATA_KEY_CHARACTERS * UTF8_BYTES_PER_CHARACTER
        admit(
            "SELECT typeof(name), typeof(value), length(CAST(name AS BLOB)), " +
                "substr(CAST(name AS BLOB), 1, $keyBytes) FROM metadata LIMIT 65",
            null,
        ) { c ->
            while (c.moveToNext()) {
                require(descriptors.size < MAX_METADATA_ROWS)
                descriptors += Descriptor(
                    c.getString(0), c.getString(1),
                    if (c.isNull(2)) null else c.getLong(2),
                    if (c.isNull(3)) null else c.getBlob(3),
                )
            }
        }
        for (descriptor in descriptors) {
            require(descriptor.nameType == "text")
            val key = decodeBoundedPrefix(
                requireNotNull(descriptor.nameLength), requireNotNull(descriptor.namePrefix),
                MAX_METADATA_KEY_CHARACTERS, true,
            ).lowercase(Locale.US)
            val (characters, truncates) = METADATA_VALUE_LIMITS[key] ?: continue // never select unknown extension values
            require(descriptor.valueType == "text" && key !in rows)
            rows[key] = requireNotNull(readValueByKey(key, characters, truncates, admissionLeftMs()))
        }
    }

    /** exactly one TEXT row for [key] or null, only the bounded prefix crosses into Java */
    private fun readValueByKey(key: String, characters: Int, truncates: Boolean, budgetMs: Long): String? {
        val maxBytes = characters * UTF8_BYTES_PER_CHARACTER
        return query(
            "SELECT typeof(value), length(CAST(value AS BLOB)), substr(CAST(value AS BLOB), 1, ?) " +
                "FROM metadata WHERE lower(name) = ? LIMIT 2",
            arrayOf(maxBytes.toString(), key), budgetMs,
        ) { c ->
            if (!c.moveToFirst() || c.getString(0) != "text" || c.isNull(1) || c.isNull(2)) return@query null
            val length = c.getLong(1)
            val prefix = c.getBlob(2)
            if (c.moveToNext()) return@query null
            decodeBoundedPrefix(length, prefix, characters, truncates)
        }
    }

    // admission reads pass the shared deadline, the lazy bake extension read on a table passes null
    private fun readMetadataPrefix(rowId: Long, column: String, characters: Int, truncates: Boolean, budgetMs: Long?): String {
        check(column == "name" || column == "value")
        val maxBytes = characters * UTF8_BYTES_PER_CHARACTER
        // SQLite may inspect its own record internally; only this bounded prefix
        // crosses into CursorWindow/Java. Do not select the unbounded TEXT first.
        val sql = "SELECT length(CAST($column AS BLOB)), substr(CAST($column AS BLOB), 1, ?) " +
            "FROM metadata WHERE rowid=?"
        return query(sql, arrayOf(maxBytes.toString(), rowId.toString()), budgetMs) { c ->
            require(c.moveToFirst() && !c.isNull(0) && !c.isNull(1))
            decodeBoundedPrefix(c.getLong(0), c.getBlob(1), characters, truncates)
        }
    }

    private fun decodeBoundedPrefix(length: Long, prefix: ByteArray, characters: Int, truncates: Boolean): String {
        val maxBytes = characters * UTF8_BYTES_PER_CHARACTER
        require(length >= 0 && (truncates || length <= maxBytes))
        require(prefix.size <= maxBytes && prefix.size.toLong() == minOf(length, maxBytes.toLong()))
        require(prefix.none { it == 0.toByte() })
        val truncated = length > prefix.size
        var decoded: String? = null
        for (dropped in 0..if (truncated) minOf(3, prefix.size) else 0) {
            decoded = runCatching {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(prefix, 0, prefix.size - dropped)).toString()
            }.getOrNull()
            if (decoded != null) break
        }
        val text = requireNotNull(decoded)
        val count = text.codePointCount(0, text.length)
        require(truncates || count <= characters)
        return if (count > characters) text.substring(0, text.offsetByCodePoints(0, characters)) else text
    }

    private fun admissionLeftMs(): Long {
        val left = TimeUnit.NANOSECONDS.toMillis(admissionDeadlineNanos - System.nanoTime())
        require(left > 0) { "MBTiles admission budget spent" }
        return left
    }

    private inline fun <T> admit(sql: String, args: Array<String>?, read: (Cursor) -> T): T =
        query(sql, args, admissionLeftMs(), read)

    /** null budget = plain read. otherwise a scheduled cancel interrupts the running statement */
    private inline fun <T> query(sql: String, args: Array<String>?, budgetMs: Long?, read: (Cursor) -> T): T {
        if (budgetMs == null) return db.rawQuery(sql, args).use(read)
        val signal = CancellationSignal()
        val timer = WATCHDOG.schedule({ signal.cancel() }, budgetMs, TimeUnit.MILLISECONDS)
        try {
            return db.rawQuery(sql, args, signal).use(read)
        } finally {
            timer.cancel(false)
        }
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
        // a view gets a per-statement budget, an interrupted read is just a missing tile
        val budget = if (tilesIsView) VIEW_QUERY_BUDGET_MS else null
        return runCatching {
            val length = query(
                "SELECT length(tile_data) FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                args, budget,
            ) { c -> if (c.moveToFirst()) c.getLong(0) else return null }
            if (length <= 0L || length > MAX_TILE_BYTES) return null
            // Query the blob only after the length-only CursorWindow proves it is
            // bounded; selecting both columns could materialize an attacker-sized
            // blob before getLong() had a chance to reject it.
            query(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                args, budget,
            ) { c -> if (c.moveToFirst()) c.getBlob(0)?.takeIf { it.size <= MAX_TILE_BYTES } else null }
        }.getOrNull()
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true) && db.isOpen) db.close()
    }

    internal fun isClosedForTesting(): Boolean = closed.get() || !db.isOpen

    companion object {
        internal const val MAX_METADATA_ROWS = 64
        internal const val MAX_METADATA_KEY_CHARACTERS = 32
        internal const val UTF8_BYTES_PER_CHARACTER = 4
        internal val METADATA_VALUE_LIMITS = mapOf(
            "name" to (128 to true), "format" to (32 to true),
            "minzoom" to (16 to false), "maxzoom" to (16 to false),
            "bounds" to (256 to false),
        )
        internal const val MAX_BAKE_EXTENSION_CHARACTERS = 128
        private val BAKE_EXTENSION_KEYS = setOf("tacmap_bake_key", "tacmap_tile_px")
        private const val MAX_TILE_BYTES = 4 * 1024 * 1024
        internal const val MAX_ZOOM = 30
        internal val RELATION_TYPES = setOf("table", "view")
        internal const val ADMISSION_BUDGET_MS = 30_000L
        internal const val VIEW_QUERY_BUDGET_MS = 2_000L

        // one daemon thread fires the budget cancels, cancelled timers drop out right away
        private val WATCHDOG by lazy {
            ScheduledThreadPoolExecutor(1) { r -> Thread(r, "mbtiles-budget").apply { isDaemon = true } }
                .apply { removeOnCancelPolicy = true }
        }

        fun open(path: String): MBTilesStore? = open(path, ADMISSION_BUDGET_MS)

        /** the budget seam, tests shorten it so an endless view gives up quickly */
        @VisibleForTesting
        internal fun open(path: String, admissionBudgetMs: Long): MBTilesStore? {
            val db = try {
                SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
            } catch (_: Throwable) {
                return null
            }
            return try {
                MBTilesStore(db, admissionBudgetMs)
            } catch (_: Throwable) {
                runCatching { db.close() }
                null
            }
        }
    }
}

private fun strictZoom(value: String): Int {
    require(value.length <= 16) { L10n.text("Invalid MBTiles zoom metadata.") }
    val trimmed = value.trim()
    require(trimmed.matches(Regex("(?:0|[1-9][0-9]?)"))) { L10n.text("Invalid MBTiles zoom metadata.") }
    return trimmed.toInt().also {
        require(it in 0..MBTilesStore.MAX_ZOOM) { L10n.text("MBTiles zoom is outside the supported range.") }
    }
}

internal fun validatedMBTilesMetadata(
    rows: Map<String, String>,
    tileZoomRange: Pair<Int, Int>,
): MBTilesStore.Metadata {
    val tileMin = tileZoomRange.first
    val tileMax = tileZoomRange.second
    require(tileMin in 0..MBTilesStore.MAX_ZOOM && tileMax in tileMin..MBTilesStore.MAX_ZOOM) {
        L10n.text("Invalid MBTiles tile zoom range.")
    }
    val minZoom = rows["minzoom"]?.let(::strictZoom) ?: tileMin
    val maxZoom = rows["maxzoom"]?.let(::strictZoom) ?: tileMax
    require(minZoom <= maxZoom) { L10n.text("MBTiles minzoom must not exceed maxzoom.") }

    val bounds = rows["bounds"]?.let { encoded ->
        require(encoded.length <= 256) { L10n.text("Invalid MBTiles bounds metadata.") }
        val values = encoded.split(',')
        require(values.size == 4) { L10n.text("Invalid MBTiles bounds metadata.") }
        val parsed = values.map { component ->
            component.trim().toDoubleOrNull()
                ?.takeIf(Double::isFinite)
                ?: throw IllegalArgumentException(L10n.text("Invalid MBTiles bounds metadata."))
        }
        val (minLon, minLat, maxLon, maxLat) = parsed
        require(minLon in -180.0..180.0 && maxLon in -180.0..180.0 &&
            minLat in -90.0..90.0 && maxLat in -90.0..90.0 &&
            minLon < maxLon && minLat < maxLat
        ) { L10n.text("MBTiles bounds are non-finite, out of range, or out of order.") }
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
