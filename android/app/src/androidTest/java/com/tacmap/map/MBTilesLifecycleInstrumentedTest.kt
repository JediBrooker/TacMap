package com.tacmap.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tacmap.calibration.ImportError
import com.tacmap.calibration.InFlightImportFiles
import com.tacmap.calibration.MBTilesStore
import com.tacmap.map.render.TileIndex
import com.tacmap.map.render.pdf.PdfBakeReader
import com.tacmap.calibration.OfflineTileMapSourceAndroid
import com.tacmap.util.MissionKeyUnlockRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*

@RunWith(AndroidJUnit4::class)
class MBTilesLifecycleInstrumentedTest {
    @get:Rule val missionKey = MissionKeyUnlockRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun validatedMetadataDrivesSourceZoomBoundsAndCloseIsIdempotent() {
        val file = makeMBTiles(
            "valid",
            mapOf(
                "name" to "Training map",
                "minzoom" to "6",
                "maxzoom" to "14",
                "bounds" to "150,-34,151,-33",
            ),
            tileZoom = 8,
        )
        val source = requireNotNull(OfflineTileMapSourceAndroid.open(file.path))

        assertEquals(6, source.minZoom)
        assertEquals(14, source.maxZoom)
        assertEquals(-34.0, source.coverage!!.southwest.latitude, 0.0)
        assertFalse(source.isClosedForTesting())
        source.close()
        source.close()
        assertTrue(source.isClosedForTesting())
    }

    @Test
    fun replacingAReferencedSourceClosesOnlyTheSupersededDatabase() {
        val first = requireNotNull(OfflineTileMapSourceAndroid.open(makeMBTiles("first").path))
        val second = requireNotNull(OfflineTileMapSourceAndroid.open(makeMBTiles("second").path))

        closeSupersededOfflineSources(
            candidates = listOf(first, first, second),
            stillReferenced = listOf(second),
        )

        assertTrue(first.isClosedForTesting())
        assertFalse(second.isClosedForTesting())
        second.close()
    }

    @Test
    fun malformedMetadataRejectsTheSourceAndFailedOpenReleasesDatabaseOwnership() {
        listOf(
            mapOf("bounds" to "NaN,-34,151,-33"),
            mapOf("bounds" to "151,-34,150,-33"),
            mapOf("minzoom" to "12", "maxzoom" to "4"),
            mapOf("maxzoom" to "31"),
        ).forEachIndexed { index, metadata ->
            val file = makeMBTiles("invalid-$index", metadata)
            assertNull(MBTilesStore.open(file.path))
            // A second exclusive writer can open immediately only if the failed
            // reader constructor closed the SQLite handle it had acquired.
            val writer = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
            assertTrue(writer.isOpen)
            writer.close()
        }
    }

    @Test
    fun closedStoreRejectsFurtherTileReadsWithoutThrowing() {
        val store = requireNotNull(MBTilesStore.open(makeMBTiles("closed").path))
        store.close()

        assertNull(store.tileData(8, 0, 0))
        assertTrue(store.isClosedForTesting())
    }

    @Test
    fun metadataRowBudgetRejectsSixtyFiveActualSQLiteRows() {
        val file = makeMBTiles("row-budget", (0..64).associate { "extension_$it" to "value" })
        assertNull(MBTilesStore.open(file.path))
    }

    @Test
    fun sixtyFourRowsAndBoundedUtf8PrefixAreAcceptedAndSoAreViews() {
        val file = makeMBTiles("row-boundary", (0..62).associate { "extension_$it" to "value" } +
            ("name" to ("a".repeat(511) + "😀" + "suffix")))
        requireNotNull(MBTilesStore.open(file.path)).use { assertEquals("a".repeat(128), it.metadata.name) }
        // 3.0.0 refused this, MBTiles 1.3 allows views and 2.x opened them (contract s13.2)
        val view = makeMBTiles("metadata-view", (0..62).associate { "extension_$it" to "value" } +
            ("name" to ("a".repeat(511) + "😀" + "suffix")))
        SQLiteDatabase.openDatabase(view.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("ALTER TABLE metadata RENAME TO metadata_base")
            it.execSQL("CREATE VIEW metadata AS SELECT name,value FROM metadata_base")
            it.execSQL("ALTER TABLE tiles RENAME TO tiles_base")
            it.execSQL("CREATE VIEW tiles AS SELECT * FROM tiles_base")
        }
        requireNotNull(MBTilesStore.open(view.path)).use {
            assertEquals("a".repeat(128), it.metadata.name)
            assertEquals("010203", it.tileData(8, 0, 255)?.hex())
        }
        // and the 65 row cap still holds on a view
        SQLiteDatabase.openDatabase(view.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO metadata_base VALUES ('extension_63', 'value')")
        }
        assertNull(MBTilesStore.open(view.path))
    }

    @Test
    fun deduplicatedNodeMbtilesPackImportsOpensAndDraws() {
        // the node-mbtiles / TileMill / MapTiler schema, tiles is a view over map + images
        val png = ByteArrayOutputStream().use { out ->
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
        val file = File(context.cacheDir, "${System.nanoTime()}-dedup.mbtiles")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE metadata (name text, value text)")
            db.execSQL("INSERT INTO metadata VALUES ('name', 'Dedup pack'), ('format', 'png'), " +
                "('minzoom', '0'), ('maxzoom', '1'), ('bounds', '-180,-85,180,85'), ('tacmap_tile_px', '256')")
            db.execSQL("CREATE TABLE map (zoom_level integer, tile_column integer, tile_row integer, tile_id text, grid_id text)")
            db.execSQL("CREATE UNIQUE INDEX map_index ON map (zoom_level, tile_column, tile_row)")
            db.execSQL("CREATE TABLE images (tile_data blob, tile_id text)")
            db.execSQL("CREATE UNIQUE INDEX images_id ON images (tile_id)")
            // five tiles share one image, which is the whole point of the dedup schema
            db.execSQL("INSERT INTO map VALUES (0,0,0,'red',NULL), (1,0,0,'red',NULL), (1,1,0,'red',NULL), " +
                "(1,0,1,'red',NULL), (1,1,1,'red',NULL)")
            db.execSQL("INSERT INTO images VALUES (?, 'red')", arrayOf(png))
            db.execSQL("CREATE VIEW tiles AS SELECT map.zoom_level AS zoom_level, map.tile_column AS tile_column, " +
                "map.tile_row AS tile_row, images.tile_data AS tile_data FROM map JOIN images ON images.tile_id = map.tile_id")
        }

        requireNotNull(MBTilesStore.open(file.path)).use { store ->
            assertEquals("Dedup pack", store.metadata.name)
            assertEquals("png", store.metadata.format)
            assertEquals(0, store.metadata.minZoom)
            assertEquals(1, store.metadata.maxZoom)
            assertEquals("256", store.rawMetadata("tacmap_tile_px"))
        }
        val source = requireNotNull(OfflineTileMapSourceAndroid.open(file.path))
        try {
            assertEquals("Dedup pack", source.displayName)
            val tile = runBlocking { source.renderTileSource().loadTile(TileIndex(1, 1, 0)) }
            assertNotNull("dedup tile must draw", tile)
            assertEquals(Color.RED, tile!!.getPixel(128, 128))
            tile.recycle()
        } finally {
            source.close()
        }

        // import admission goes through the same open, so a re-import is accepted too
        val journalDir = File(context.cacheDir, "mbtiles-import-journal-${System.nanoTime()}").apply { mkdirs() }
        val pipeline = MapImportPipeline(context, DocumentImportCopyJournal.forTests(journalDir))
        val snapshot = LibrarySnapshot(loaded = true, entryCount = 0, byContentKey = { null })
        val outcome = runBlocking { pipeline.runMbtiles(Uri.fromFile(file), "dedup-${System.nanoTime()}", snapshot) { } }
        assertTrue("import refused: $outcome", outcome is PreparedOutcome.Mbtiles)
        val prepared = (outcome as PreparedOutcome.Mbtiles).prepared
        InFlightImportFiles.release(prepared.file)
        prepared.file.delete()
    }

    @Test
    fun anMbtilesImportIsAdmittedUnderTheInterruptedImportMarker() {
        // s14.1 r7: a pack that kills its own admission is swept at the next launch like a PDF,
        // and the replayed pick doesn't go round again
        val file = makeMBTiles("import-marker")
        val journal = DocumentImportCopyJournal.forTests(File(context.cacheDir, "marker-journal-${System.nanoTime()}").apply { mkdirs() })
        val op = "mbtiles:marker-${System.nanoTime()}"
        var during: DocumentImportCopyState? = null
        val pipeline = MapImportPipeline(context, journal, validateMbtiles = { f ->
            during = journal.state(op)
            MBTilesStore.open(f.path)?.use { it.metadata }
        })
        val snapshot = LibrarySnapshot(loaded = true, entryCount = 0, byContentKey = { null })
        val outcome = runBlocking { pipeline.runMbtiles(Uri.fromFile(file), op, snapshot) { } }
        val prepared = (outcome as PreparedOutcome.Mbtiles).prepared
        assertNotNull("no marker while the pack was admitted", during?.inspectStartedAtEpochMs)
        assertNull(journal.state(op)!!.inspectStartedAtEpochMs)

        // the process died in there: what the journal held at that point is what the next launch finds
        journal.persist(during!!)
        InFlightImportFiles.release(prepared.file)
        assertTrue(MapImportPipeline.sweepInterrupted(journal))
        assertFalse("the copy goes", prepared.file.exists())
        var admitted = false
        val replay = MapImportPipeline(context, journal, validateMbtiles = { admitted = true; null })
        val again = runBlocking { replay.runMbtiles(Uri.fromFile(file), op, snapshot) { } }
        assertEquals(ImportError.INTERRUPTED, (again as PreparedOutcome.Failed).failure.error)
        assertFalse("the replay opened it again", admitted)
    }

    @Test
    fun aSlowTileReadOnAViewIsCutOffAsAMissingTile() {
        // 3.0.2: the old endless subquery view is refused before any read now (viewShape), and so is
        // anything else that computes. what's left to be slow is a plain join with no index (automatic
        // indexes are off): every map row on one tile, a nested loop over images for each. the
        // admission gets a big budget through the seam, the read only has its own 2 s
        val budget = admissionFixture()["viewQueryBudgetMs"]!!.jsonPrimitive.long
        var rows = 8_000
        while (true) {
            val file = File(context.cacheDir, "${System.nanoTime()}-slow-join.mbtiles")
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE metadata (name text, value text)")
                db.execSQL("INSERT INTO metadata VALUES ('name', 'Slow join')")
                db.execSQL("CREATE TABLE map (zoom_level integer, tile_column integer, tile_row integer, tile_id text)")
                db.execSQL("CREATE TABLE images (tile_data blob, tile_id text)")
                db.execSQL("WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < ${rows - 1}) " +
                    "INSERT INTO map SELECT 8, 0, 0, 't' || i FROM n")
                db.execSQL("WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < ${rows - 1}) " +
                    "INSERT INTO images SELECT X'01', 't' || i FROM n")
                db.execSQL("CREATE VIEW tiles AS SELECT map.zoom_level AS zoom_level, map.tile_column AS tile_column, " +
                    "map.tile_row AS tile_row, images.tile_data AS tile_data FROM map JOIN images ON images.tile_id = map.tile_id")
            }
            val opened = System.nanoTime()
            val store = requireNotNull(MBTilesStore.open(file.path, 600_000L)) { "a plain join is admitted" }
            val admissionMs = (System.nanoTime() - opened) / 1_000_000
            // the read walks the same join the aggregate did, so it's only a test once that's well past the budget
            if (admissionMs < budget + 1_500 && rows < 64_000) {
                store.close()
                file.delete()
                rows = rows * 3 / 2
                continue
            }
            store.use {
                val started = System.nanoTime()
                assertNull(it.tileData(8, 0, 255))
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertTrue("read took $elapsedMs ms, admission $admissionMs ms", elapsedMs in (budget - 10)..(budget + 5_000))
            }
            file.delete()
            return
        }
    }

    @Test
    fun everyConnectionIsHardenedBeforeItsFirstRead() {
        val version = sqliteVersion()
        requireNotNull(MBTilesStore.open(makeMBTiles("hardened").path)).use { store ->
            assertEquals("0", store.pragmaForTesting("automatic_index"))
            if (version >= com.tacmap.calibration.SqliteVersion(3, 31, 0)) {
                assertEquals("0", store.pragmaForTesting("trusted_schema"))
                // process wide, set by the first open
                assertEquals(MBTilesStore.HARD_HEAP_LIMIT_BYTES.toString(), store.pragmaForTesting("hard_heap_limit"))
            }
        }
    }

    @Test
    fun aValueOverTheCapOnAViewFailsClosedButATableStillTruncates() {
        // no sqlite3_limit on android, so the view path checks the byte length itself (s14.1)
        val cap = MBTilesStore.MAX_VALUE_BYTES
        fun pack(name: String, rows: String, metadataView: Boolean): File {
            val file = File(context.cacheDir, "${System.nanoTime()}-$name.mbtiles")
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE meta_base (name text, value text)")
                db.execSQL("INSERT INTO meta_base VALUES $rows")
                if (metadataView) db.execSQL("CREATE VIEW metadata AS SELECT name, value FROM meta_base")
                else db.execSQL("ALTER TABLE meta_base RENAME TO metadata")
                db.execSQL("CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)")
                db.execSQL("INSERT INTO tiles VALUES (8, 0, 0, X'010203')")
            }
            return file
        }
        val overFormat = "('name', 'Pack'), ('format', replace(hex(zeroblob(${cap / 2 + 1})), '0', 'p'))"
        val overKey = "('name', 'Pack'), (replace(hex(zeroblob(${cap / 2 + 1})), '0', 'k'), 'v')"
        val atCap = "('name', replace(hex(zeroblob(${cap / 2})), '0', 'n'))"
        assertNull("known value over the cap", MBTilesStore.open(pack("view-over-format", overFormat, true).path))
        assertNull("key over the cap", MBTilesStore.open(pack("view-over-key", overKey, true).path))
        requireNotNull(MBTilesStore.open(pack("view-at-cap", atCap, true).path)).use { assertEquals("n".repeat(128), it.metadata.name) }
        // tables never hit the cap: the bounded prefix by rowid, as before
        requireNotNull(MBTilesStore.open(pack("table-over-format", overFormat, false).path)).use {
            assertEquals("p".repeat(32), it.metadata.format)
        }
        requireNotNull(MBTilesStore.open(pack("table-over-key", overKey, false).path)).use { assertEquals("Pack", it.metadata.name) }
    }

    @Test
    fun twoTablesGetNoAdmissionBudget() {
        // s14.2: a table pack's aggregate is one scan the file bounds, a spent budget doesn't refuse it.
        // a view still gets one
        val file = makeMBTiles("table-no-budget")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 60000) " +
                "INSERT INTO tiles SELECT 16, i, 0, X'01' FROM n")
        }
        requireNotNull(MBTilesStore.open(file.path, 1L)) { "two tables were cut off by the admission budget" }.close()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("ALTER TABLE tiles RENAME TO tiles_base")
            it.execSQL("CREATE VIEW tiles AS SELECT * FROM tiles_base")
        }
        assertNull(MBTilesStore.open(file.path, 1L))
        requireNotNull(MBTilesStore.open(file.path)).close()
    }

    @Test
    fun aPrevalidatedPackOpensOnItsFirstReadAndStillRefusesAHostileSchema() {
        // s14.2 r2: the import worker's metadata puts a pack up with no second admission, the first
        // tile read opens it (hardening, relation, shape and base checks, no aggregate)
        val file = makeMBTiles("prevalidated")
        val admitted = requireNotNull(MBTilesStore.open(file.path)).use { it.metadata }
        val source = OfflineTileMapSourceAndroid.prevalidated(file.path, "Prevalidated", admitted)
        assertEquals(admitted.maxZoom, source.maxZoom)
        assertFalse("opened before any read", source.refusedForTesting())
        assertEquals("010203", source.tileData(8, 0, 255)?.hex())
        source.close()
        assertTrue(source.isClosedForTesting())
        assertNull("closed serves nothing", source.tileData(8, 0, 255))

        // the same metadata claimed for bytes whose tiles view runs SQL: refused on the lazy open, no tile ever
        val hostile = makeMBTiles("prevalidated-hostile")
        SQLiteDatabase.openDatabase(hostile.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("ALTER TABLE tiles RENAME TO tiles_base")
            it.execSQL("CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row, " +
                "CASE WHEN date('now') > '2000-01-01' THEN tile_data END AS tile_data FROM tiles_base")
        }
        val lazy = OfflineTileMapSourceAndroid.prevalidated(hostile.path, "Hostile", admitted)
        assertNull(lazy.tileData(8, 0, 255))
        assertTrue("lazy open didn't refuse it", lazy.refusedForTesting())
        assertNull(lazy.tileData(8, 0, 255))
        lazy.close()

        // a close while nothing's been read yet never opens it
        val untouched = OfflineTileMapSourceAndroid.prevalidated(file.path, "Untouched", admitted)
        untouched.close()
        assertNull(untouched.tileData(8, 0, 255))
        assertFalse(untouched.refusedForTesting())
    }

    @Test
    fun aHostileViewIsRefusedOnEveryOpenEvenOnceTheTriggerFires() {
        // SEC-1: date('now') in a view used to pass admission and switch on later. now the text alone refuses it
        val file = makeMBTiles("date-trigger")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("ALTER TABLE tiles RENAME TO tiles_base")
            it.execSQL("CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row, " +
                "CASE WHEN date('now') > '2000-01-01' THEN tile_data END AS tile_data FROM tiles_base")
        }
        assertNull(MBTilesStore.open(file.path))
        assertNull(OfflineTileMapSourceAndroid.open(file.path))
        // and a generated column in an ordinary table, the 3.0.0 bomb
        if (sqliteVersion() >= com.tacmap.calibration.SqliteVersion(3, 31, 0)) {
            val generated = File(context.cacheDir, "${System.nanoTime()}-generated.mbtiles")
            SQLiteDatabase.openOrCreateDatabase(generated, null).use { db ->
                db.execSQL("CREATE TABLE metadata (name text, value text)")
                db.execSQL("CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, raw blob, " +
                    "tile_data blob GENERATED ALWAYS AS (hex(zeroblob(64))) VIRTUAL)")
                db.execSQL("INSERT INTO tiles (zoom_level, tile_column, tile_row, raw) VALUES (8, 0, 0, X'01')")
            }
            assertNull(MBTilesStore.open(generated.path))
        }
    }

    @Test
    fun generatedRelationCasesAdmitTablesAndViewsTheSameWay() {
        val fixture = admissionFixture()
        val cases = fixture["relationCases"]!!.jsonArray.map { it.jsonObject }
        // a generator change that drops rows shouldn't pass by testing less
        assertTrue("only ${cases.size} relation cases", cases.size >= 40)
        assertTrue(cases.any { it["id"]!!.jsonPrimitive.content == "nodeMbtilesDedup" })
        // SEC-M1-SHADOW: case variant and name lie rows, they come as packBase64 (sql[] needs writable_schema)
        for (id in listOf("tilesShadowedByCaseVariant", "metadataShadowedByCaseVariant", "tableRowKeepsIfNotExists",
                "baseRowNameLie")) assertTrue(id, cases.any { it["id"]!!.jsonPrimitive.content == id })
        assertTrue(cases.any { it["id"]!!.jsonPrimitive.content == "tilesViewEndless" })
        assertTrue(cases.any { it["id"]!!.jsonPrimitive.content == "metadataViewOversizedName" })
        val version = sqliteVersion()
        cases.forEach { case ->
            val id = case["id"]!!.jsonPrimitive.content
            // can't even be built on an older sqlite (a generated column)
            val needs = case["minSqliteVersion"]?.jsonPrimitive?.content?.let { com.tacmap.calibration.SqliteVersion.parse(it)!! }
            if (needs != null && version < needs) return@forEach
            val file = File(context.cacheDir, "${System.nanoTime()}-relation-$id.mbtiles")
            val packed = case["packBase64"]?.jsonPrimitive?.content
            if (packed != null) {
                // the exact file the reference judged, iOS can't replay writable_schema sql
                file.writeBytes(java.util.Base64.getDecoder().decode(packed))
            } else {
                SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                    case["sql"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                }
            }
            val testBudget = case["testBudgetMs"]?.jsonPrimitive?.long
            val started = System.nanoTime()
            val store = MBTilesStore.open(file.path, testBudget ?: fixture["admissionBudgetMs"]!!.jsonPrimitive.long)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val expect = case["expect"]!!.jsonObject
            assertEquals(id, expect["accepted"]!!.jsonPrimitive.boolean, store != null)
            // the budget is what stopped it, not some other limit after a long hang
            if (testBudget != null) assertTrue("$id took $elapsedMs ms", elapsedMs in (testBudget - 10)..(testBudget + 5_000))
            store?.use { s ->
                assertEquals(id, expect["minZoom"]!!.jsonPrimitive.int, s.metadata.minZoom)
                assertEquals(id, expect["maxZoom"]!!.jsonPrimitive.int, s.metadata.maxZoom)
                assertEquals(id, expect["name"]?.jsonPrimitive?.contentOrNull, s.metadata.name)
                assertEquals(id, expect["format"]?.jsonPrimitive?.contentOrNull, s.metadata.format)
                expect["tiles"]?.jsonArray?.forEach { probe ->
                    val p = probe.jsonObject
                    val z = p["z"]!!.jsonPrimitive.int; val x = p["x"]!!.jsonPrimitive.int; val y = p["y"]!!.jsonPrimitive.int
                    assertEquals("$id $z/$x/$y", p["hex"]!!.jsonPrimitive.contentOrNull, s.tileData(z, x, y)?.hex())
                }
                expect["extensions"]?.jsonObject?.forEach { (key, value) ->
                    assertEquals("$id $key", value.jsonPrimitive.contentOrNull, s.rawMetadata(key))
                }
            }
        }
    }

    @Test
    fun hostileUnknownMetadataNeverEntersRetainedRows() {
        val longKey = "x".repeat(64 * 1024)
        val file = makeMBTiles("bounded-extensions", mapOf(
            "extension" to "v".repeat(256 * 1024), longKey to "v".repeat(256 * 1024),
            "name" to "Training map", "format" to "webp",
        ))
        requireNotNull(MBTilesStore.open(file.path)).use { store ->
            assertEquals("Training map", store.metadata.name)
            assertEquals("webp", store.metadata.format)
            assertTrue("unknown large value is not retained", store.rawMetadata("extension") == null)
            assertTrue("unknown large key is not retained", store.rawMetadata(longKey) == null)
        }
    }

    @Test
    fun metadataDuplicatesTypesNulAndInvalidUtf8FailClosed() {
        val attacks = listOf(
            "INSERT INTO metadata VALUES ('NAME', 'conflicting')" to mapOf("name" to "first"),
            "INSERT INTO metadata VALUES (CAST(X'6E616D65' AS BLOB), 'map')" to emptyMap(),
            "INSERT INTO metadata VALUES ('minzoom', X'38')" to emptyMap(),
            "INSERT INTO metadata VALUES ('name', 'safe' || char(0) || 'hidden')" to emptyMap(),
            "INSERT INTO metadata VALUES ('name', CAST(X'C328' AS TEXT))" to emptyMap(),
            "INSERT INTO metadata VALUES ('name' || char(0), 'map')" to emptyMap(),
            "INSERT INTO metadata VALUES ('bounds', '150,-34,151,-33' || char(0))" to emptyMap(),
        )
        attacks.forEachIndexed { index, (sql, initial) ->
            val file = makeMBTiles("metadata-attack-$index", initial)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL(sql) }
            assertNull("attack $index must reject", MBTilesStore.open(file.path))
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { assertTrue(it.isOpen) }
        }
    }

    @Test
    fun boundedDescriptionAndStrictBakeMetadataRemainCompatible() {
        val file = makeMBTiles("bounded-valid", mapOf(
            "NAME" to "é".repeat(256), "format" to "webp",
            "minzoom" to "8", "maxzoom" to "8", "bounds" to "150,-34,151,-33",
            "tacmap_bake_key" to "a".repeat(64), "tacmap_tile_px" to "768",
        ))
        requireNotNull(MBTilesStore.open(file.path)).use { store ->
            assertEquals("é".repeat(128), store.metadata.name)
            assertEquals("a".repeat(64), store.rawMetadata("tacmap_bake_key"))
            assertEquals("768", store.rawMetadata("tacmap_tile_px"))
        }
        requireNotNull(PdfBakeReader.open(file, "a".repeat(64), 768)).close()
        listOf("minzoom" to "8".repeat(17), "bounds" to "0".repeat(257)).forEachIndexed { index, pair ->
            assertNull(MBTilesStore.open(makeMBTiles("strict-overflow-$index", mapOf(pair)).path))
        }
    }

    @Test
    fun tileCoordinatesAndZoomMustBeTypedBeforeAggregateProjection() {
        val numericText = makeMBTiles("text-zoom")
        SQLiteDatabase.openDatabase(numericText.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE tiles")
            it.execSQL("CREATE TABLE tiles(zoom_level, tile_column, tile_row, tile_data)")
            it.execSQL("INSERT INTO tiles VALUES ('8',0,0,X'010203')")
        }
        assertNull("numeric TEXT must reject before aggregate", MBTilesStore.open(numericText.path))
        val attacks = listOf("zoom_level=X'38'", "zoom_level='" + "8".repeat(256 * 1024) + "'",
            "tile_column=-1", "tile_row=256")
        attacks.forEachIndexed { index, update ->
            val file = makeMBTiles("tile-aggregate-$index")
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("UPDATE tiles SET $update")
            }
            assertNull("typed tile admission $index", MBTilesStore.open(file.path))
        }
    }

    @Test
    fun generatedMetadataAdmissionVectorsUseRealSQLiteTypesAndBounds() {
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("import_limits.json")
            .use { Json.parseToJsonElement(it.reader().readText()).jsonObject["mbtilesMetadataAdmission"]!!.jsonObject }
        assertEquals(MBTilesStore.MAX_METADATA_ROWS, fixture["maxRows"]!!.jsonPrimitive.int)
        assertEquals(MBTilesStore.MAX_METADATA_KEY_CHARACTERS, fixture["maxKeyCharacters"]!!.jsonPrimitive.int)
        assertEquals(MBTilesStore.UTF8_BYTES_PER_CHARACTER, fixture["utf8PrefixBytesPerCharacter"]!!.jsonPrimitive.int)
        assertEquals(MBTilesStore.MAX_BAKE_EXTENSION_CHARACTERS, fixture["consumedBakeExtensionMaxCharacters"]!!.jsonPrimitive.int)
        fixture["knownValueMaxCharacters"]!!.jsonObject.forEach { (key, bound) ->
            assertEquals(bound.jsonPrimitive.int, MBTilesStore.METADATA_VALUE_LIMITS[key]!!.first)
        }
        for (variant in listOf(null, "metadataView", "bothViews")) fixture["cases"]!!.jsonArray.forEach { item ->
            val case = item.jsonObject
            val id = case["id"]!!.jsonPrimitive.content + (variant?.let { " ($it)" } ?: "")
            val file = makeMBTiles("generated-${case["id"]!!.jsonPrimitive.content}")
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TABLE metadata")
                db.execSQL("CREATE TABLE metadata(name,value)")
                repeat(case["unknownRows"]?.jsonPrimitive?.int ?: 0) { row ->
                    db.execSQL("INSERT INTO metadata VALUES (?,?)", arrayOf("extension_$row", "value"))
                }
                case["rows"]!!.jsonArray.forEach { element ->
                    val row = element.jsonObject; val key = row["key"]!!.jsonPrimitive.content
                    val hex = row["textBytesHex"]?.jsonPrimitive?.content
                    if (hex != null) {
                        check(hex.matches(Regex("[0-9a-fA-F]+")))
                        db.execSQL("INSERT INTO metadata VALUES (?,CAST(X'$hex' AS TEXT))", arrayOf(key))
                    } else {
                        val repeated = row["valueRepeat"]?.jsonArray
                        val value: Any = row["integerValue"]?.jsonPrimitive?.int
                            ?: repeated?.let { it[0].jsonPrimitive.content.repeat(it[1].jsonPrimitive.int) }
                            ?: row["value"]!!.jsonPrimitive.content
                        db.execSQL("INSERT INTO metadata VALUES (?,?)", arrayOf(key, value))
                    }
                }
                variantSql(fixture, variant).forEach(db::execSQL)
            }
            val store = MBTilesStore.open(file.path)
            assertEquals(id, case["accepted"]!!.jsonPrimitive.boolean, store != null)
            store?.use {
                case["name"]?.let { name -> assertEquals(id, name.jsonPrimitive.content, it.metadata.name) }
                case["format"]?.let { format -> assertEquals(id, format.jsonPrimitive.content, it.metadata.format) }
            }
        }
    }

    @Test
    fun generatedTileZoomStorageAdmissionNeverProjectsHostileText() {
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("import_limits.json")
            .use { Json.parseToJsonElement(it.reader().readText()).jsonObject["mbtilesMetadataAdmission"]!!.jsonObject }
        assertEquals(0, fixture["tileZoomMin"]!!.jsonPrimitive.int)
        assertEquals(MBTilesStore.MAX_ZOOM, fixture["tileZoomMax"]!!.jsonPrimitive.int)
        for (variant in listOf(null, "tilesView", "bothViews")) fixture["tileZoomCases"]!!.jsonArray.forEach { element ->
            val case = element.jsonObject
            val id = case["id"]!!.jsonPrimitive.content + (variant?.let { " ($it)" } ?: "")
            val file = makeMBTiles("generated-zoom-${case["id"]!!.jsonPrimitive.content}")
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TABLE tiles")
                db.execSQL("CREATE TABLE tiles(zoom_level,tile_column,tile_row,tile_data)")
                val repeated = case["valueRepeat"]?.jsonArray
                val value: Any = when (case["storageType"]!!.jsonPrimitive.content) {
                    "integer" -> case["value"]!!.jsonPrimitive.int
                    "real" -> case["value"]!!.jsonPrimitive.double
                    "text" -> repeated?.let { it[0].jsonPrimitive.content.repeat(it[1].jsonPrimitive.int) }
                        ?: case["value"]!!.jsonPrimitive.content
                    else -> error("unsupported generated type")
                }
                db.execSQL("INSERT INTO tiles VALUES (?,0,0,X'010203')", arrayOf(value))
                variantSql(fixture, variant).forEach(db::execSQL)
            }
            val store = MBTilesStore.open(file.path)
            assertEquals(id, case["accepted"]!!.jsonPrimitive.boolean, store != null)
            store?.close()
        }
    }

    @Test
    fun malformedBakeExtensionsMismatchWithoutRejectingOrdinaryRasterPack() {
        val cases = listOf("'" + "a".repeat(129) + "'", "'a' || char(0) || 'hidden'", "X'61'", "CAST(X'FF' AS TEXT)")
        cases.forEachIndexed { index, value ->
            val file = makeMBTiles("bad-extension-$index", mapOf("tacmap_tile_px" to "768"))
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("INSERT INTO metadata VALUES ('tacmap_bake_key',$value)")
            }
            requireNotNull(MBTilesStore.open(file.path)).use { assertNull(it.rawMetadata("tacmap_bake_key")) }
            assertNull(PdfBakeReader.open(file, "a", 768))
        }
        val duplicate = makeMBTiles("duplicate-extension", mapOf("tacmap_bake_key" to "first", "tacmap_tile_px" to "768"))
        SQLiteDatabase.openDatabase(duplicate.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO metadata VALUES ('TACMAP_BAKE_KEY','second')")
        }
        requireNotNull(MBTilesStore.open(duplicate.path)).use { assertNull(it.rawMetadata("tacmap_bake_key")) }
        assertNull(PdfBakeReader.open(duplicate, "second", 768))
    }

    @Test
    fun generatedLazyBakeExtensionAdmissionIsBoundedAndKeepsOrdinaryPacks() {
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("import_limits.json")
            .use { Json.parseToJsonElement(it.reader().readText()).jsonObject["mbtilesMetadataAdmission"]!!.jsonObject }
        for (variant in listOf(null, "metadataView", "bothViews")) fixture["extensionCases"]!!.jsonArray.forEach { element ->
            val case = element.jsonObject
            val id = case["id"]!!.jsonPrimitive.content + (variant?.let { " ($it)" } ?: "")
            val file = makeMBTiles("generated-extension-${case["id"]!!.jsonPrimitive.content}")
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TABLE metadata")
                db.execSQL("CREATE TABLE metadata(name,value)")
                case["rows"]!!.jsonArray.forEach { rowElement ->
                    val row = rowElement.jsonObject; val key = row["key"]!!.jsonPrimitive.content
                    val hex = row["textBytesHex"]?.jsonPrimitive?.content
                    if (hex != null) {
                        check(hex.matches(Regex("[0-9a-fA-F]+")))
                        db.execSQL("INSERT INTO metadata VALUES (?,CAST(X'$hex' AS TEXT))", arrayOf(key))
                    } else {
                        val repeated = row["valueRepeat"]?.jsonArray
                        val value: Any = row["integerValue"]?.jsonPrimitive?.int
                            ?: repeated?.let { it[0].jsonPrimitive.content.repeat(it[1].jsonPrimitive.int) }
                            ?: row["value"]!!.jsonPrimitive.content
                        db.execSQL("INSERT INTO metadata VALUES (?,?)", arrayOf(key, value))
                    }
                }
                variantSql(fixture, variant).forEach(db::execSQL)
            }
            val store = MBTilesStore.open(file.path)
            assertEquals(id, case["mapAccepted"]!!.jsonPrimitive.boolean, store != null)
            requireNotNull(store).use {
                assertEquals(id, case["expected"]!!.jsonPrimitive.contentOrNull,
                    it.rawMetadata(case["requestedKey"]!!.jsonPrimitive.content))
            }
        }
    }

    private fun admissionFixture(): JsonObject =
        InstrumentationRegistry.getInstrumentation().context.assets.open("import_limits.json")
            .use { Json.parseToJsonElement(it.reader().readText()).jsonObject["mbtilesMetadataAdmission"]!!.jsonObject }

    /** variantRule: null is the plain table, otherwise the fixture's SQL turns relations into views */
    private fun variantSql(fixture: JsonObject, variant: String?): List<String> =
        if (variant == null) emptyList() else fixture["relationVariants"]!!.jsonArray.map { it.jsonObject }
            .single { it["id"]!!.jsonPrimitive.content == variant }["sql"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    /** the framework's own sqlite, what the reader's gates go by */
    private fun sqliteVersion(): com.tacmap.calibration.SqliteVersion =
        SQLiteDatabase.create(null).use { db ->
            db.rawQuery("SELECT sqlite_version()", null).use { c ->
                c.moveToFirst()
                requireNotNull(com.tacmap.calibration.SqliteVersion.parse(c.getString(0)))
            }
        }

    private fun makeMBTiles(
        name: String,
        metadata: Map<String, String> = emptyMap(),
        tileZoom: Int = 8,
    ): File {
        val file = File(context.cacheDir, "${System.nanoTime()}-$name.mbtiles")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
            db.execSQL(
                "CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)"
            )
            metadata.forEach { (key, value) ->
                db.execSQL("INSERT INTO metadata(name, value) VALUES (?, ?)", arrayOf(key, value))
            }
            db.execSQL(
                "INSERT INTO tiles(zoom_level, tile_column, tile_row, tile_data) VALUES (?, 0, 0, ?)",
                arrayOf(tileZoom, byteArrayOf(1, 2, 3)),
            )
        } finally {
            db.close()
        }
        return file
    }
}
