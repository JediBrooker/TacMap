package com.tacmap.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tacmap.calibration.MBTilesStore
import com.tacmap.map.render.pdf.PdfBakeReader
import com.tacmap.calibration.OfflineTileMapSourceAndroid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*

@RunWith(AndroidJUnit4::class)
class MBTilesLifecycleInstrumentedTest {
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
    fun sixtyFourRowsAndBoundedUtf8PrefixAreAcceptedButViewsAreRejected() {
        val file = makeMBTiles("row-boundary", (0..62).associate { "extension_$it" to "value" } +
            ("name" to ("a".repeat(511) + "😀" + "suffix")))
        requireNotNull(MBTilesStore.open(file.path)).use { assertEquals("a".repeat(128), it.metadata.name) }
        val view = makeMBTiles("metadata-view")
        SQLiteDatabase.openDatabase(view.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("ALTER TABLE metadata RENAME TO metadata_base")
            it.execSQL("CREATE VIEW metadata AS SELECT name,value FROM metadata_base")
        }
        assertNull(MBTilesStore.open(view.path))
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
        fixture["cases"]!!.jsonArray.forEach { item ->
            val case = item.jsonObject
            val id = case["id"]!!.jsonPrimitive.content
            val file = makeMBTiles("generated-$id")
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
        fixture["tileZoomCases"]!!.jsonArray.forEach { element ->
            val case = element.jsonObject; val id = case["id"]!!.jsonPrimitive.content
            val file = makeMBTiles("generated-zoom-$id")
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
        fixture["extensionCases"]!!.jsonArray.forEach { element ->
            val case = element.jsonObject; val id = case["id"]!!.jsonPrimitive.content
            val file = makeMBTiles("generated-extension-$id")
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
            }
            val store = MBTilesStore.open(file.path)
            assertEquals(id, case["mapAccepted"]!!.jsonPrimitive.boolean, store != null)
            requireNotNull(store).use {
                assertEquals(id, case["expected"]!!.jsonPrimitive.contentOrNull,
                    it.rawMetadata(case["requestedKey"]!!.jsonPrimitive.content))
            }
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
