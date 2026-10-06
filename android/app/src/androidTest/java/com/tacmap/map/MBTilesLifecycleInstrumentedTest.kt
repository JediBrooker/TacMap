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
import com.tacmap.calibration.MBTilesRecordProbe
import com.tacmap.calibration.MBTilesStore
import com.tacmap.map.render.TileIndex
import com.tacmap.map.render.pdf.PdfBakeReader
import com.tacmap.calibration.OfflineTileMapSourceAndroid
import com.tacmap.util.MissionKeyUnlockRule
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
    fun gdal2mbtilesCommaJoinPackImportsOpensAndDraws() {
        // SHADOW-PARITY-2: gdal2mbtiles (a raster producer) stores tiles as FROM map, images WHERE map.tile_id =
        // images.tile_id. 3.0.1 opened it, the first 3.0.2 grammar didn't, so an upgrade lost the basemap.
        // the generator's pack as is, just real png pixels in its images so there's something to decode
        val case = admissionFixture()["relationCases"]!!.jsonArray.map { it.jsonObject }
            .single { it["id"]!!.jsonPrimitive.content == "gdal2mbtilesCommaJoin" }
        val expect = case["expect"]!!.jsonObject
        assertTrue(expect["accepted"]!!.jsonPrimitive.boolean)
        val png = ByteArrayOutputStream().use { out ->
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
        val file = File(context.cacheDir, "${System.nanoTime()}-gdal2mbtiles.mbtiles")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            case["sql"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.execSQL("UPDATE images SET tile_data = ?", arrayOf(png))
        }

        requireNotNull(MBTilesStore.open(file.path)).use { store ->
            assertEquals(expect["name"]!!.jsonPrimitive.content, store.metadata.name)
            assertEquals(expect["format"]!!.jsonPrimitive.content, store.metadata.format)
            assertEquals(expect["minZoom"]!!.jsonPrimitive.int, store.metadata.minZoom)
            assertEquals(expect["maxZoom"]!!.jsonPrimitive.int, store.metadata.maxZoom)
        }
        val source = requireNotNull(OfflineTileMapSourceAndroid.open(file.path))
        try {
            // 1/1/1 is the deduplicated tile (map row points at image 1), 1/0/1 has no map row
            val tile = runBlocking { source.renderTileSource().loadTile(TileIndex(1, 1, 1)) }
            assertNotNull("comma join tile must draw", tile)
            assertEquals(Color.BLUE, tile!!.getPixel(128, 128))
            tile.recycle()
            assertNull(runBlocking { source.renderTileSource().loadTile(TileIndex(1, 0, 1)) })
        } finally {
            source.close()
        }

        val journalDir = File(context.cacheDir, "mbtiles-import-journal-${System.nanoTime()}").apply { mkdirs() }
        val pipeline = MapImportPipeline(context, DocumentImportCopyJournal.forTests(journalDir))
        val snapshot = LibrarySnapshot(loaded = true, entryCount = 0, byContentKey = { null })
        val outcome = runBlocking { pipeline.runMbtiles(Uri.fromFile(file), "gdal-${System.nanoTime()}", snapshot) { } }
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

        // the process died in there: what the journal held at that point is what the next launch
        // finds, and the new process has no memory of setting it
        journal.persist(during!!)
        InspectionMarks.forgetForTesting()
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
    fun anImportPausedDuringItsAdmissionIsRetriedNotRefusedAsInterrupted() {
        // DL-1: Home locks the key while the pack's admitted, the screen's job goes. the admission
        // can't be stopped, comes back behind the lock and can't clear its marker. that's not a
        // crash, so the replay on resume imports it instead of saying it was interrupted
        val file = makeMBTiles("paused")
        val dir = File(context.cacheDir, "paused-journal-${System.nanoTime()}").apply { mkdirs() }
        val op = "mbtiles:paused-${System.nanoTime()}"
        val snapshot = LibrarySnapshot(loaded = true, entryCount = 0, byContentKey = { null })
        val admitting = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        val first = MapImportPipeline(context, DocumentImportCopyJournal.forTests(dir), validateMbtiles = { f ->
            admitting.countDown()
            gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
            MBTilesStore.open(f.path)?.use { it.metadata }
        })
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job())
        val job = scope.launch {
            // the screen's import job catches whatever the dead run throws, so does this
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { first.runMbtiles(Uri.fromFile(file), op, snapshot) { } }
            }
        }
        assertTrue(admitting.await(10, java.util.concurrent.TimeUnit.SECONDS))
        try {
            com.tacmap.util.DataKey.lock()
            job.cancel()
            gate.countDown()
            runBlocking { job.join() }
        } finally {
            com.tacmap.util.DataKey.unlock()
        }
        // what the rebuilt screen reads: the marker's still on disk
        val replayJournal = DocumentImportCopyJournal.forTests(dir)
        assertNotNull("the clear landed after all", replayJournal.state(op)?.inspectStartedAtEpochMs)
        // the view model's sweep on the way back doesn't call it a crash either
        assertFalse("swept as interrupted", MapImportPipeline.sweepInterrupted(DocumentImportCopyJournal.forTests(dir)))

        val again = runBlocking { MapImportPipeline(context, replayJournal).runMbtiles(Uri.fromFile(file), op, snapshot) { } }
        assertTrue("not retried: $again", again is PreparedOutcome.Mbtiles)
        val prepared = (again as PreparedOutcome.Mbtiles).prepared
        assertNotNull("admitted without metadata", prepared.metadata)
        assertNull(DocumentImportCopyJournal.forTests(dir).state(op)!!.inspectStartedAtEpochMs)
        InFlightImportFiles.release(prepared.file)
        prepared.file.delete()
    }

    @Test
    fun aReplayWhileThePausedAdmissionStillRunsWaitsForItAndLeavesItsCopyAlone() {
        // DL-1 too: back before the admission's done. the replay used to read the live marker as a
        // crash, delete the copy under the admission and say interrupted
        val file = makeMBTiles("paused-live")
        val dir = File(context.cacheDir, "paused-live-journal-${System.nanoTime()}").apply { mkdirs() }
        val op = "mbtiles:paused-live-${System.nanoTime()}"
        val snapshot = LibrarySnapshot(loaded = true, entryCount = 0, byContentKey = { null })
        val admitting = java.util.concurrent.CountDownLatch(1)
        val gate = java.util.concurrent.CountDownLatch(1)
        var copy: File? = null
        val first = MapImportPipeline(context, DocumentImportCopyJournal.forTests(dir), validateMbtiles = { f ->
            copy = f
            admitting.countDown()
            gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
            MBTilesStore.open(f.path)?.use { it.metadata }
        })
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job())
        val job = scope.launch {
            // the screen's import job catches whatever the dead run throws, so does this
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { first.runMbtiles(Uri.fromFile(file), op, snapshot) { } }
            }
        }
        assertTrue(admitting.await(10, java.util.concurrent.TimeUnit.SECONDS))
        job.cancel()
        val replay = scope.async(kotlinx.coroutines.Dispatchers.IO) {
            MapImportPipeline(context, DocumentImportCopyJournal.forTests(dir)).runMbtiles(Uri.fromFile(file), op, snapshot) { }
        }
        Thread.sleep(300)
        assertFalse("replay didn't wait", replay.isCompleted)
        assertTrue("copy deleted under the admission", copy!!.isFile)
        gate.countDown()
        val again = runBlocking { replay.await() }
        assertTrue("not retried: $again", again is PreparedOutcome.Mbtiles)
        val prepared = (again as PreparedOutcome.Mbtiles).prepared
        assertTrue(prepared.file.isFile)
        InFlightImportFiles.release(prepared.file)
        prepared.file.delete()
    }

    @Test
    fun aSlowTileReadOnAViewIsCutOffAndAReadStopsAtItsFirstRow() {
        // 3.0.2: the old endless subquery view is refused before any read now (viewShape), and so is
        // anything else that computes. what's left to be slow is a plain join with no index (automatic
        // indexes are off), a nested loop over images for each map row. the admission gets a big budget
        // through the seam, a read only has its own 2 s. two keys on one pack: 8/0/0 joins on its first
        // map row and then grinds through rows that match nothing, 8/1/0 only joins on its very last.
        // 3.0.3: a read takes the first row and stops (LIMIT 1, iOS steps once too), so a second row on
        // the same key is never loaded and 8/0/0 comes back at once. 8/1/0 still runs out of budget
        val budget = admissionFixture()["viewQueryBudgetMs"]!!.jsonPrimitive.long
        var rows = 8_000
        while (true) {
            val file = File(context.cacheDir, "${System.nanoTime()}-slow-join.mbtiles")
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                val n = "WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < ${rows - 1})"
                db.execSQL("CREATE TABLE metadata (name text, value text)")
                db.execSQL("INSERT INTO metadata VALUES ('name', 'Slow join')")
                db.execSQL("CREATE TABLE map (zoom_level integer, tile_column integer, tile_row integer, tile_id text)")
                db.execSQL("CREATE TABLE images (tile_data blob, tile_id text)")
                // first and last sit at the ends of both tables, so either loop order the planner picks agrees
                db.execSQL("INSERT INTO map VALUES (8, 0, 0, 'first')")
                db.execSQL("$n INSERT INTO map SELECT 8, 0, 0, 'a' || i FROM n")
                db.execSQL("$n INSERT INTO map SELECT 8, 1, 0, 'b' || i FROM n")
                db.execSQL("INSERT INTO map VALUES (8, 1, 0, 'last')")
                db.execSQL("INSERT INTO images VALUES (X'01', 'first')")
                db.execSQL("$n INSERT INTO images SELECT X'02', 'i' || i FROM n")
                db.execSQL("INSERT INTO images VALUES (X'03', 'last')")
                db.execSQL("CREATE VIEW tiles AS SELECT map.zoom_level AS zoom_level, map.tile_column AS tile_column, " +
                    "map.tile_row AS tile_row, images.tile_data AS tile_data FROM map JOIN images ON images.tile_id = map.tile_id")
            }
            val opened = System.nanoTime()
            val store = requireNotNull(MBTilesStore.open(file.path, 600_000L)) { "a plain join is admitted" }
            val admissionMs = (System.nanoTime() - opened) / 1_000_000
            // each key's rows are about half the join the aggregate walked, so it's only a test once that
            // half is well past the budget
            if (admissionMs < 2 * budget + 3_000 && rows < 64_000) {
                store.close()
                file.delete()
                rows = rows * 3 / 2
                continue
            }
            store.use {
                var started = System.nanoTime()
                assertEquals("8/0/0 is its first row", "01", it.tileData(8, 0, 255)?.hex())
                val firstMs = (System.nanoTime() - started) / 1_000_000
                assertTrue("the read went on past its first row: $firstMs ms, admission $admissionMs ms", firstMs < budget)
                started = System.nanoTime()
                assertNull(it.tileData(8, 1, 255))
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
    fun theHeapCapIsOnBeforeAPacksFirstStatement() {
        // PROBE-RT-1: harden() put hard_heap_limit on after its SELECT sqlite_version(), and newer sqlite
        // (3.51 yes, this emulator's 3.44 not yet) loads the schema and the ANALYZE tables with it on that
        // very statement, so the first pack a process opened got that load uncapped. now it goes on before
        // the pack's opened at all: even a file the record probe turns away has it seen to. sqlite can't
        // take the cap off again, so in a process an earlier test already capped only the flag shows it
        if (sqliteVersion() < com.tacmap.calibration.SqliteVersion(3, 31, 0)) return
        MBTilesStore.forgetHeapLimitForTesting()
        val notSqlite = File(context.cacheDir, "${System.nanoTime()}-not-sqlite.mbtiles").apply { writeBytes(ByteArray(4096)) }
        assertNull(MBTilesStore.open(notSqlite.path))
        assertTrue("cap not seen to before the pack's opened", MBTilesStore.heapLimitCheckedForTesting())
        assertEquals(MBTilesStore.HARD_HEAP_LIMIT_BYTES.toString(), heapLimit())
        notSqlite.delete()
        // and a schema sqlite can't even parse, so the open dies on the first statement that reads it
        MBTilesStore.forgetHeapLimitForTesting()
        val file = makeMBTiles("cap-first")
        val bytes = file.readBytes()
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("CREATE TABLE metadata")
        assertTrue(at > 0)
        // CREATE TABLX, a syntax error once sqlite parses its schema. the record probe only reads sizes
        bytes[at + 11] = 'X'.code.toByte()
        file.writeBytes(bytes)
        assertNull(MBTilesStore.open(file.path))
        assertTrue("cap not seen to before the pack's first statement", MBTilesStore.heapLimitCheckedForTesting())
        assertEquals(MBTilesStore.HARD_HEAP_LIMIT_BYTES.toString(), heapLimit())
        file.delete()
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
    fun aHugeMetadataValueIsRefusedBeforeSqliteLoadsIt() {
        // SEC-M1-ANDROID-OLDSQLITE-OOM: sqlite loads all of a TEXT value before substr(CAST(... AS BLOB))
        // cuts it, and below android 12 there's no length limit or heap cap, so a pack with a few hundred
        // MB name got its import (or the restore at every launch) OOM killed. 16 MB sits under the 12+
        // heap cap, so 3.0.2 admitted this on every API level, after loading all of it. 3.0.3 reads the
        // record size off the file and every door refuses it before sqlite reads one metadata row (s15.2)
        val cap = admissionFixture()["recordProbe"]!!.jsonObject["maxMetadataRecordBytes"]!!.jsonPrimitive.long
        val file = File(context.cacheDir, "${System.nanoTime()}-huge-name.mbtiles")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE metadata (name text, value text)")
            db.execSQL("INSERT INTO metadata VALUES ('name', replace(hex(zeroblob(8000000)), '0', 'n')), ('format', 'png')")
            db.execSQL("CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)")
            db.execSQL("INSERT INTO tiles VALUES (8, 0, 0, X'010203')")
        }
        assertTrue("the name has to be over the probe's cap", file.length() > cap)
        assertNull("admission", MBTilesStore.open(file.path))
        assertNull("source open", OfflineTileMapSourceAndroid.open(file.path))
        assertNull("bake reader", PdfBakeReader.open(file, "a".repeat(64), 256))
        // the lazy prevalidated open (import commit, admitted cache) never reads metadata, it's probed anyway
        val lazy = OfflineTileMapSourceAndroid.prevalidated(file.path, "Huge", MBTilesStore.Metadata(minZoom = 8, maxZoom = 8))
        assertNull(lazy.tileData(8, 0, 255))
        assertTrue("lazy open didn't refuse it", lazy.refusedForTesting())
        lazy.close()
        // and the import's admission
        val journalDir = File(context.cacheDir, "huge-journal-${System.nanoTime()}").apply { mkdirs() }
        val pipeline = MapImportPipeline(context, DocumentImportCopyJournal.forTests(journalDir))
        val snapshot = LibrarySnapshot(loaded = true, entryCount = 0, byContentKey = { null })
        val outcome = runBlocking { pipeline.runMbtiles(Uri.fromFile(file), "huge-${System.nanoTime()}", snapshot) { } }
        assertEquals("import: $outcome", ImportError.INVALID_MBTILES, (outcome as? PreparedOutcome.Failed)?.failure?.error)
        // refused, not touched: the pack's still there for the user to delete
        assertTrue(file.exists())
        file.delete()
    }

    @Test
    fun aHugeAnalyzeRowIsRefusedBeforeSqliteLoadsIt() {
        // PROBE-RT-1: the schema load at the first statement reads every sqlite_stat1 row whole, and the
        // first record probe only walked sqlite_master, so the red team's 150 MB stat row went straight
        // through to an OOM below android 12. 40 MB here sits under the 12+ heap cap, so before this fix
        // it was admitted on every API level after loading all of it. a zeroblob, so building it is cheap
        val file = File(context.cacheDir, "${System.nanoTime()}-huge-stat.mbtiles")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE metadata (name text, value text)")
            db.execSQL("INSERT INTO metadata VALUES ('name', 'Analyzed'), ('format', 'png')")
            db.execSQL("CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)")
            db.execSQL("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)")
            db.execSQL("INSERT INTO tiles VALUES (8, 0, 0, X'010203')")
            db.execSQL("ANALYZE")
        }
        // mbutil runs ANALYZE on everything it writes, that much has to keep opening
        requireNotNull(MBTilesStore.open(file.path)) { "an ANALYZE'd pack was refused" }.close()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE sqlite_stat1 SET stat = zeroblob(40000000) WHERE idx = 'tile_index'")
        }
        assertTrue(file.length() > 40_000_000)
        assertEquals("recordBytes", MBTilesRecordProbe.schema(file))
        assertNull("admission", MBTilesStore.open(file.path))
        assertNull("source open", OfflineTileMapSourceAndroid.open(file.path))
        assertNull("bake reader", PdfBakeReader.open(file, "a".repeat(64), 256))
        val lazy = OfflineTileMapSourceAndroid.prevalidated(file.path, "Analyzed", MBTilesStore.Metadata(minZoom = 8, maxZoom = 8))
        assertNull(lazy.tileData(8, 0, 255))
        assertTrue("lazy open didn't refuse it", lazy.refusedForTesting())
        lazy.close()
        val journalDir = File(context.cacheDir, "stat-journal-${System.nanoTime()}").apply { mkdirs() }
        val pipeline = MapImportPipeline(context, DocumentImportCopyJournal.forTests(journalDir))
        val snapshot = LibrarySnapshot(loaded = true, entryCount = 0, byContentKey = { null })
        val outcome = runBlocking { pipeline.runMbtiles(Uri.fromFile(file), "stat-${System.nanoTime()}", snapshot) { } }
        assertEquals("import: $outcome", ImportError.INVALID_MBTILES, (outcome as? PreparedOutcome.Failed)?.failure?.error)
        assertTrue(file.exists())
        file.delete()
    }

    @Test
    fun aHugeIndexKeyIsRefusedBeforeSqliteComparesIt() {
        // s16.1: a seek mallocs every overflowing index key it compares whole, no length limit applies there
        // (seen at 200 MB on 3.51), and 3.0.3's probe never walked an index. one key just over the cap, well
        // under the 12+ heap cap, so only the probe stands in its way. a zeroblob, cheap to build
        val cap = admissionFixture()["recordProbe"]!!.jsonObject["maxIndexKeyBytes"]!!.jsonPrimitive.long
        val file = File(context.cacheDir, "${System.nanoTime()}-huge-key.mbtiles")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE metadata (name text, value text)")
            db.execSQL("INSERT INTO metadata VALUES ('name', 'Indexed'), ('format', 'png')")
            db.execSQL("CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob, note blob)")
            db.execSQL("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)")
            db.execSQL("CREATE INDEX tiles_note ON tiles (zoom_level, tile_column, tile_row, note)")
            db.execSQL("INSERT INTO tiles VALUES (8, 0, 0, X'010203', X'00')")
        }
        // small keys are what every real pack has, that keeps opening
        requireNotNull(MBTilesStore.open(file.path)) { "an indexed pack was refused" }.close()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE tiles SET note = zeroblob(${cap + 500_000})")
        }
        assertTrue(file.length() > cap)
        assertEquals("keyBytes", MBTilesRecordProbe.schema(file))
        assertNull("admission", MBTilesStore.open(file.path))
        assertNull("source open", OfflineTileMapSourceAndroid.open(file.path))
        assertNull("bake reader", PdfBakeReader.open(file, "a".repeat(64), 256))
        // the lazy open runs no aggregate, its first tile read is a seek on one of those indexes
        val lazy = OfflineTileMapSourceAndroid.prevalidated(file.path, "Indexed", MBTilesStore.Metadata(minZoom = 8, maxZoom = 8))
        assertNull(lazy.tileData(8, 0, 255))
        assertTrue("lazy open didn't refuse it", lazy.refusedForTesting())
        lazy.close()
        val journalDir = File(context.cacheDir, "key-journal-${System.nanoTime()}").apply { mkdirs() }
        val pipeline = MapImportPipeline(context, DocumentImportCopyJournal.forTests(journalDir))
        val snapshot = LibrarySnapshot(loaded = true, entryCount = 0, byContentKey = { null })
        val outcome = runBlocking { pipeline.runMbtiles(Uri.fromFile(file), "key-${System.nanoTime()}", snapshot) { } }
        assertEquals("import: $outcome", ImportError.INVALID_MBTILES, (outcome as? PreparedOutcome.Failed)?.failure?.error)
        assertTrue(file.exists())
        file.delete()
    }

    @Test
    fun aPackSqliteFindsCorruptIsNeverDeleted() {
        // DL-A1: the 3 arg openDatabase got every pack the framework's DefaultDatabaseErrorHandler, and on
        // SQLITE_CORRUPT that deletes the file, read only or not. so one bad page took the user's pack with
        // it, at the restore's admission or later on the tile read that hit it, while U1's notice said nothing
        // was deleted. the record probe never walks tiles or tile_index so all three of these reach sqlite

        // a bad tile_index leaf: the admission aggregate scans that index, so it's refused
        val index = pagedPack("index-leaf", tileBytes = 200, side = 45)
        smash(index) { bytes, pageSize, roots -> (leafPages(bytes, pageSize, roots.getValue("tile_index")).first() - 1) * pageSize }
        assertNull("admission", MBTilesStore.open(index.path))
        assertTrue("admission deleted the pack", index.exists())
        assertNull("source open", OfflineTileMapSourceAndroid.open(index.path))
        assertTrue("restore open deleted the pack", index.exists())

        // a bad tiles leaf: the aggregate never reads table leaves, so it's admitted and the first tile on
        // that leaf, rowid 1 = (8, 0, tms 0), is where sqlite trips. no tile, and the pack stays
        val leaf = pagedPack("tiles-leaf", tileBytes = 200, side = 45)
        smash(leaf) { bytes, pageSize, roots -> (leafPages(bytes, pageSize, roots.getValue("tiles")).first() - 1) * pageSize }
        requireNotNull(OfflineTileMapSourceAndroid.open(leaf.path)) { "a bad tiles leaf got refused, the test needs a new page" }.use {
            noTileWhereSqliteSeesTheDamage(it.tileData(8, 0, 255))
        }
        assertTrue("a tile read deleted the pack", leaf.exists())
        val lazy = OfflineTileMapSourceAndroid.prevalidated(leaf.path, "Damaged", MBTilesStore.Metadata(minZoom = 8, maxZoom = 8))
        noTileWhereSqliteSeesTheDamage(lazy.tileData(8, 0, 255))
        lazy.close()
        assertTrue("a lazy tile read deleted the pack", leaf.exists())

        // a tile whose overflow chain points past the end of the file, only read when that tile's drawn
        val overflow = pagedPack("overflow", tileBytes = 9_000, side = 10)
        smash(overflow) { bytes, pageSize, roots ->
            val page = firstOverflowPage(bytes, pageSize, leafPages(bytes, pageSize, roots.getValue("tiles")).first())
            val at = (page - 1) * pageSize
            val past = bytes.size / pageSize + 1_000
            for (i in 0 until 4) bytes[at + i] = (past ushr (24 - 8 * i)).toByte()
            -1
        }
        requireNotNull(OfflineTileMapSourceAndroid.open(overflow.path)) { "a bad overflow pointer got refused" }.use {
            noTileWhereSqliteSeesTheDamage(it.tileData(8, 0, 255))
            assertNotNull("the rest still draws", it.tileData(8, 1, 255))
        }
        assertTrue("a tile read deleted the pack", overflow.exists())
        listOf(index, leaf, overflow).forEach { it.delete() }
    }

    // android 8's sqlite (3.18) doesnt check pages as hard as 3.32+, so on old api levels a smashed leaf or
    // overflow pointer can come back as garbage bytes instead of SQLITE_CORRUPT. thats fine, the decoder
    // drops it. what DL-A1 is about is the file never getting deleted, and thats asserted on every api level
    private fun noTileWhereSqliteSeesTheDamage(tile: ByteArray?) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) assertNull(tile)
    }

    /** an mbutil style pack with a real tile_index, big enough that tiles and the index span several pages */
    private fun pagedPack(name: String, tileBytes: Int, side: Int): File {
        val file = File(context.cacheDir, "${System.nanoTime()}-$name.mbtiles")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            // everything in the main file, the smash below edits it directly
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            db.execSQL("CREATE TABLE metadata (name text, value text)")
            db.execSQL("INSERT INTO metadata VALUES ('name', 'Damaged'), ('format', 'png'), ('minzoom', '8'), ('maxzoom', '8')")
            db.execSQL("CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)")
            db.execSQL("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row)")
            db.execSQL("WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < ${side * side - 1}) " +
                "INSERT INTO tiles SELECT 8, i / $side, i % $side, randomblob($tileBytes) FROM n")
        }
        return file
    }

    /** [at] gives the byte to zero (the page type when it's a page start), or -1 when it edited the bytes itself */
    private fun smash(file: File, at: (ByteArray, Int, Map<String, Int>) -> Int) {
        val roots = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name, rootpage FROM sqlite_master", null).use { c ->
                buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
            }
        }
        val bytes = file.readBytes()
        val pageSize = u16(bytes, 16).let { if (it == 1) 65_536 else it }
        assertEquals("no reserved bytes", 0, bytes[20].toInt())
        val offset = at(bytes, pageSize, roots)
        if (offset >= 0) bytes[offset] = 0
        file.writeBytes(bytes)
    }

    /** leaf pages under [root], left to right */
    private fun leafPages(bytes: ByteArray, pageSize: Int, root: Int): List<Int> {
        val leaves = mutableListOf<Int>()
        fun walk(page: Int) {
            val base = (page - 1) * pageSize
            val header = base + if (page == 1) 100 else 0
            when (bytes[header].toInt() and 0xff) {
                0x0d, 0x0a -> leaves += page
                0x05, 0x02 -> {
                    for (k in 0 until u16(bytes, header + 3)) walk(u32(bytes, base + u16(bytes, header + 12 + 2 * k)))
                    walk(u32(bytes, header + 8))
                }
                else -> error("page $page isn't a b-tree page")
            }
        }
        walk(root)
        assertTrue("the b-tree at $root is one page, nothing to pick", leaves.size > 1)
        return leaves
    }

    /** the first overflow page of the first cell on a table leaf, sqlite's local payload rule */
    private fun firstOverflowPage(bytes: ByteArray, pageSize: Int, leaf: Int): Int {
        val base = (leaf - 1) * pageSize
        var i = base + u16(bytes, base + 8)
        fun varint(): Long {
            var v = 0L
            repeat(8) {
                val b = bytes[i++].toInt() and 0xff
                v = (v shl 7) or (b and 0x7f).toLong()
                if (b < 0x80) return v
            }
            return (v shl 8) or (bytes[i++].toLong() and 0xff)
        }
        val payload = varint()
        varint() // rowid
        val usable = pageSize
        val maxLocal = usable - 35
        assertTrue("the tile has to overflow", payload > maxLocal)
        val minLocal = (usable - 12) * 32 / 255 - 23
        val k = minLocal + ((payload - minLocal) % (usable - 4)).toInt()
        return u32(bytes, i + if (k <= maxLocal) k else minLocal)
    }

    private fun u16(bytes: ByteArray, at: Int): Int = ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)

    private fun u32(bytes: ByteArray, at: Int): Int = (u16(bytes, at) shl 16) or u16(bytes, at + 2)

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
        assertTrue("only ${cases.size} relation cases", cases.size >= 57)
        assertTrue(cases.any { it["id"]!!.jsonPrimitive.content == "nodeMbtilesDedup" })
        // 3.0.3 U2: the record probe's rows (3.0.2 opened the three refused ones) and the TEXT tile that's no tile
        for (id in listOf("metadataLargeRecordUnderProbeCap", "metadataRecordOverProbeCap", "metadataViewJoinBaseRows",
                "schemaTooManyObjects", "textTileData")) assertTrue(id, cases.any { it["id"]!!.jsonPrimitive.content == id })
        // PROBE-RT-1: an ANALYZE'd pack opens, one with a stat row over the schema caps doesn't
        for (id in listOf("analyzedPack", "statisticsOverProbeCap"))
            assertTrue(id, cases.any { it["id"]!!.jsonPrimitive.content == id })
        // s16.1 index keys (3.0.3 opened the four refused ones), s16.2 names merely like a statistics table
        for (id in listOf("tilesIndexKeyUnderProbeCap", "tilesIndexKeyOverProbeCap", "metadataExpressionIndexOverProbeCap",
                "dedupOrphanImageKeyOverProbeCap", "planetilerShallowRowOverProbeCap", "tilesColumnLikeStatistics",
                "indexNamedLikeStatistics")) assertTrue(id, cases.any { it["id"]!!.jsonPrimitive.content == id })
        // SEC-M1-SHADOW: case variant and name lie rows, they come as packBase64 (sql[] needs writable_schema)
        for (id in listOf("tilesShadowedByCaseVariant", "metadataShadowedByCaseVariant", "tableRowKeepsIfNotExists",
                "baseRowNameLie")) assertTrue(id, cases.any { it["id"]!!.jsonPrimitive.content == id })
        // SHADOW-PARITY-2: gdal2mbtiles' comma join and tippecanoe's ON ... and ..., both opened in 3.0.1
        for (id in listOf("gdal2mbtilesCommaJoin", "tippecanoeAndJoin", "commaJoinWhereCallsFunction"))
            assertTrue(id, cases.any { it["id"]!!.jsonPrimitive.content == id })
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
    fun recordProbeCasesGetTheSharedVerdictsOnThisSqlite() {
        // s15.2 recordProbe.cases: the sql[] rows get built by this device's sqlite and their table's root
        // looked up like the reader does, the hand made page images are the exact files (the JVM runs those too)
        val cases = admissionFixture()["recordProbe"]!!.jsonObject["cases"]!!.jsonArray.map { it.jsonObject }
        assertTrue("only ${cases.size} cases", cases.size >= 92)
        assertTrue(cases.count { it["sql"] != null } >= 22)
        cases.forEach { case ->
            val id = case["id"]!!.jsonPrimitive.content
            val file = File(context.cacheDir, "${System.nanoTime()}-probe-$id.mbtiles")
            val packed = case["packBase64"]?.jsonPrimitive?.content
            if (packed != null) {
                file.writeBytes(java.util.Base64.getDecoder().decode(packed))
            } else {
                SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                    case["sql"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                }
            }
            assertEquals("$id schema", case["schema"]!!.jsonObject.probeReason(), MBTilesRecordProbe.schema(file))
            case["metadataTables"]!!.jsonArray.map { it.jsonObject }.forEach { table ->
                val name = table["table"]?.jsonPrimitive?.content
                val root = if (name == null) table["root"]!!.jsonPrimitive.long else
                    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                        db.rawQuery("SELECT rootpage FROM sqlite_master WHERE type = 'table' AND name = ? COLLATE NOCASE",
                            arrayOf(name)).use { c -> assertTrue("$id $name", c.moveToFirst()); c.getLong(0) }
                    }
                assertEquals("$id ${name ?: root}", table["expect"]!!.jsonObject.probeReason(),
                    MBTilesRecordProbe.metadataTable(file, root))
            }
            file.delete()
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

    /** a recordProbe expect: null when ok, else its reason */
    private fun JsonObject.probeReason(): String? =
        if (this["ok"]!!.jsonPrimitive.boolean) null else this["reason"]!!.jsonPrimitive.content

    /** the process wide cap as a fresh connection sees it */
    private fun heapLimit(): String =
        SQLiteDatabase.create(null).use { db ->
            db.rawQuery("PRAGMA hard_heap_limit", null).use { c -> c.moveToFirst(); c.getString(0) }
        }

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
