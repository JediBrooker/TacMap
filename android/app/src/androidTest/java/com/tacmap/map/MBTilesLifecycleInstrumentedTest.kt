package com.tacmap.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tacmap.calibration.MBTilesStore
import com.tacmap.calibration.OfflineTileMapSourceAndroid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
