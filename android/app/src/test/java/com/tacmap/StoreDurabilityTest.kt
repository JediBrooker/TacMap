package com.tacmap

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingPoint
import com.tacmap.drawings.DrawingStore
import com.tacmap.util.MissionStorePersistence
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointStore
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StoreDurabilityTest {
    @Test
    fun waypointWriteFailurePublishesNoStateUndoOrMutation() = runBlocking {
        val persistence = RecordingPersistence(fail = true)
        lateinit var store: WaypointStore
        persistence.beforeWrite = { assertTrue(store.waypoints.value.isEmpty()) }
        store = WaypointStore.forTests(tempDir(), persistence)
        val events = store.mutations.produceIn(this)
        yield()

        assertFalse(store.add(waypoint("w1")))

        assertTrue(store.waypoints.value.isEmpty())
        assertFalse(store.canUndo.value)
        assertNull(events.tryReceive().getOrNull())
        assertTrue(store.loadError.value.orEmpty().contains("reverted"))
        events.cancel()
    }

    @Test
    fun failedWaypointUpdateDoesNotCreateAnUndoEntry() = runBlocking {
        val persistence = RecordingPersistence()
        val store = WaypointStore.forTests(tempDir(), persistence)
        val original = waypoint("w1")
        assertTrue(store.add(original))
        val events = store.mutations.produceIn(this)
        yield()
        events.tryReceive()
        persistence.fail = true

        assertFalse(store.update(original.copy(name = "unsaved")))
        assertEquals(original, store.waypoints.value.single())
        assertNull(events.tryReceive().getOrNull())

        persistence.fail = false
        assertTrue(store.undo())
        assertTrue("only the successful add should be undoable", store.waypoints.value.isEmpty())
        events.cancel()
    }

    @Test
    fun failedWaypointDeleteKeepsTheCommittedSymbolVisible() = runBlocking {
        val persistence = RecordingPersistence()
        val store = WaypointStore.forTests(tempDir(), persistence)
        val original = waypoint("w1")
        assertTrue(store.add(original))
        val events = store.mutations.produceIn(this)
        yield()
        events.tryReceive()
        persistence.fail = true

        assertFalse(store.remove(original))

        assertEquals(listOf(original), store.committedWaypoints.value)
        assertEquals(listOf(original), store.waypoints.value)
        assertNull(events.tryReceive().getOrNull())
        events.cancel()
    }

    @Test
    fun waypointPreviewWritesZeroTimesAndFailedCommitReverts() = runBlocking {
        val persistence = RecordingPersistence()
        val store = WaypointStore.forTests(tempDir(), persistence)
        val original = waypoint("w1")
        assertTrue(store.add(original))
        persistence.writes = 0
        val events = store.mutations.produceIn(this)
        yield()
        events.tryReceive() // discard the successful setup add

        assertTrue(store.previewUpdate(original.copy(rotation = 10.0)))
        assertTrue(store.previewUpdate(original.copy(rotation = 25.0)))
        assertEquals(25.0, store.waypoints.value.single().rotation, 0.0)
        assertEquals(original, store.committedWaypoints.value.single())
        assertEquals(0, persistence.writes)
        assertNull(events.tryReceive().getOrNull())

        persistence.fail = true
        assertFalse(store.commitPreview(original.copy(rotation = 25.0)))
        assertEquals(original, store.waypoints.value.single())
        assertEquals(1, persistence.writes)
        assertNull(events.tryReceive().getOrNull())
        events.cancel()
    }

    @Test
    fun drawingBatchFailurePublishesNoDocumentUndoOrMutation() = runBlocking {
        val persistence = RecordingPersistence(fail = true)
        lateinit var store: DrawingStore
        persistence.beforeWrite = { assertTrue(store.document.value.features.isEmpty()) }
        store = DrawingStore.forTests(tempDir(), persistence)
        val before = store.document.value
        val events = store.mutations.produceIn(this)
        yield()

        assertFalse(store.addImported(emptyList(), listOf(drawing("d1"))))

        assertEquals(before, store.document.value)
        assertFalse(store.canUndo.value)
        assertNull(events.tryReceive().getOrNull())
        assertTrue(store.loadError.value.orEmpty().contains("reverted"))
        events.cancel()
    }

    @Test
    fun drawingPreviewCommitsOnceAndProducesOneUndoAndMutation() = runBlocking {
        val persistence = RecordingPersistence()
        val store = DrawingStore.forTests(tempDir(), persistence)
        val original = drawing("d1")
        assertTrue(store.addFeature(original))
        persistence.writes = 0
        val events = store.mutations.produceIn(this)
        yield()
        events.tryReceive() // discard the successful setup add

        assertTrue(store.previewFeature(original.copy(rotationDegrees = 15.0)))
        assertTrue(store.previewFeature(original.copy(rotationDegrees = 30.0)))
        assertEquals(original, store.committedDocument.value.features.single())
        assertEquals(0, persistence.writes)
        assertNull(events.tryReceive().getOrNull())
        assertTrue(store.commitPreview(original.copy(rotationDegrees = 30.0)))
        assertEquals(1, persistence.writes)
        assertEquals(setOf("d1"), events.tryReceive().getOrNull()?.localIds)

        assertTrue(store.undo())
        assertEquals(original, store.document.value.features.single())
        events.cancel()
    }

    @Test
    fun slowDragNeverPublishesPreviewSnapshotsToSyncFacingFlows() = runBlocking {
        val waypointStore = WaypointStore.forTests(tempDir(), RecordingPersistence())
        val drawingStore = DrawingStore.forTests(tempDir(), RecordingPersistence())
        val wp = waypoint("w1")
        val feature = drawing("d1")
        assertTrue(waypointStore.add(wp))
        assertTrue(drawingStore.addFeature(feature))

        repeat(20) { tick ->
            assertTrue(waypointStore.previewUpdate(wp.copy(rotation = tick.toDouble())))
            assertTrue(drawingStore.previewFeature(feature.copy(rotationDegrees = tick.toDouble())))
            yield() // Model a collector getting CPU between every slow-drag tick.
            assertEquals(wp, waypointStore.committedWaypoints.value.single())
            assertEquals(feature, drawingStore.committedDocument.value.features.single())
        }

        assertTrue(waypointStore.commitPreview(wp.copy(rotation = 19.0)))
        assertTrue(drawingStore.commitPreview(feature.copy(rotationDegrees = 19.0)))
        assertEquals(19.0, waypointStore.committedWaypoints.value.single().rotation, 0.0)
        assertEquals(19.0, drawingStore.committedDocument.value.features.single().rotationDegrees, 0.0)
    }

    private fun waypoint(id: String) = Waypoint(
        id = id,
        name = "Waypoint $id",
        latitude = -33.8,
        longitude = 151.2,
    )

    private fun drawing(id: String) = DrawingFeature(
        id = id,
        name = "Drawing $id",
        geometry = DrawingGeometry.LINE,
        points = listOf(
            DrawingPoint(-33.8, 151.2),
            DrawingPoint(-33.9, 151.3),
        ),
    )

    private fun tempDir(): File = Files.createTempDirectory("mission-store").toFile()

    private class RecordingPersistence(
        var fail: Boolean = false,
        var writes: Int = 0,
        var beforeWrite: (() -> Unit)? = null,
    ) : MissionStorePersistence {
        override fun write(file: File, label: String, text: String) {
            writes++
            beforeWrite?.invoke()
            if (fail) error("simulated persistence failure")
        }
    }
}
