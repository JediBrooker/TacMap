package com.tacmap.map

import com.tacmap.drawings.DrawingDocument
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.drawings.DrawingPoint
import com.tacmap.drawings.DrawingStore
import com.tacmap.util.MissionStorePersistence
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MissionLayerMutationsTest {
    @Test
    fun addAndCombinedEditEachPublishOneDurableCandidate() {
        val persistence = RecordingPersistence()
        val store = DrawingStore.forTests(tempDir(), persistence)

        assertTrue(store.addLayer("  Support  "))
        val custom = store.committedDocument.value.layers.single {
            it.id !in DrawingDocument.DEFAULT_LAYER_IDS
        }
        assertEquals(1, persistence.writes)

        persistence.writes = 0
        assertTrue(store.updateLayer(custom.id, "  Logistics  ", 0x00123456))

        val edited = store.committedDocument.value.layers.single { it.id == custom.id }
        assertEquals("Logistics", edited.name)
        assertEquals(0xFF123456.toInt(), edited.color)
        assertEquals(1, persistence.writes)
    }

    @Test
    fun failedCombinedEditRetainsPriorLayerAndProtectedDefaultsCannotChange() {
        val persistence = RecordingPersistence()
        val store = DrawingStore.forTests(tempDir(), persistence)
        assertTrue(store.addLayerVerbatim(DrawingLayer(id = "custom", name = "Custom", color = 0xFF112233.toInt())))
        val before = store.committedDocument.value

        persistence.fail = true
        assertFalse(store.updateLayer("custom", "Changed", 0xFF445566.toInt()))
        assertEquals(before, store.committedDocument.value)

        persistence.fail = false
        persistence.writes = 0
        val default = store.committedDocument.value.layers.first {
            it.id in DrawingDocument.DEFAULT_LAYER_IDS
        }
        assertFalse(store.updateLayer(default.id, "Changed default", 0xFF445566.toInt()))
        assertEquals(0, persistence.writes)
        assertEquals(default, store.committedDocument.value.layers.first { it.id == default.id })
    }

    @Test
    fun deletingCustomLayerReassignsWaypointsAndDrawingsBeforeRemoval() {
        val waypointPersistence = RecordingPersistence()
        val drawingPersistence = RecordingPersistence()
        val waypointStore = WaypointStore.forTests(tempDir(), waypointPersistence)
        val drawingStore = DrawingStore.forTests(tempDir(), drawingPersistence)
        assertTrue(drawingStore.addLayerVerbatim(DrawingLayer(id = "custom", name = "Custom")))
        assertTrue(waypointStore.add(waypoint("custom")))
        assertTrue(drawingStore.addFeature(drawing("custom")))
        waypointPersistence.writes = 0
        drawingPersistence.writes = 0

        val outcome = deleteMissionLayer("custom", waypointStore, drawingStore)

        assertTrue(outcome.succeeded)
        assertEquals(DrawingDocument.DEFAULT_LAYER_ID, outcome.fallbackLayerId)
        assertEquals(DrawingDocument.DEFAULT_LAYER_ID, waypointStore.committedWaypoints.value.single().layerId)
        assertEquals(DrawingDocument.DEFAULT_LAYER_ID, drawingStore.committedDocument.value.features.single().layerId)
        assertFalse(drawingStore.committedDocument.value.layers.any { it.id == "custom" })
        assertEquals(1, waypointPersistence.writes)
        assertEquals(1, drawingPersistence.writes)
    }

    @Test
    fun drawingFailureLeavesLayerPresentAndNoObjectOrphaned() {
        val waypointStore = WaypointStore.forTests(tempDir(), RecordingPersistence())
        val drawingPersistence = RecordingPersistence()
        val drawingStore = DrawingStore.forTests(tempDir(), drawingPersistence)
        assertTrue(drawingStore.addLayerVerbatim(DrawingLayer(id = "custom", name = "Custom")))
        assertTrue(waypointStore.add(waypoint("custom")))
        assertTrue(drawingStore.addFeature(drawing("custom")))
        drawingPersistence.fail = true

        val outcome = deleteMissionLayer("custom", waypointStore, drawingStore)

        assertFalse(outcome.succeeded)
        assertEquals(DrawingDocument.DEFAULT_LAYER_ID, waypointStore.committedWaypoints.value.single().layerId)
        assertEquals("custom", drawingStore.committedDocument.value.features.single().layerId)
        assertTrue(drawingStore.committedDocument.value.layers.any { it.id == "custom" })
        val validLayerIds = drawingStore.committedDocument.value.layers.mapTo(HashSet()) { it.id }
        assertTrue(waypointStore.committedWaypoints.value.all { it.layerId in validLayerIds })
        assertTrue(drawingStore.committedDocument.value.features.all { it.layerId in validLayerIds })
    }

    private fun waypoint(layerId: String) = Waypoint(
        id = "waypoint",
        name = "Waypoint",
        latitude = -33.8,
        longitude = 151.2,
        layerId = layerId,
    )

    private fun drawing(layerId: String) = DrawingFeature(
        id = "drawing",
        name = "Drawing",
        geometry = DrawingGeometry.LINE,
        points = listOf(DrawingPoint(-33.8, 151.2), DrawingPoint(-33.9, 151.3)),
        layerId = layerId,
    )

    private fun tempDir(): File = Files.createTempDirectory("mission-layer").toFile()

    private class RecordingPersistence(
        var fail: Boolean = false,
        var writes: Int = 0,
    ) : MissionStorePersistence {
        override fun write(file: File, label: String, text: String) {
            writes++
            if (fail) error("simulated persistence failure")
        }
    }
}
