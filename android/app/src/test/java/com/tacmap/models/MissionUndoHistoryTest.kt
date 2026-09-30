package com.tacmap.models

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingPoint
import com.tacmap.drawings.DrawingStore
import com.tacmap.drawings.RangeRingFollower
import com.tacmap.drawings.RangeRings
import com.tacmap.util.MissionStorePersistence
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MissionUndoHistoryTest {
    @Test
    fun undoAndRedoFollowTheOrderStepsWereRecorded() {
        val history = MissionUndoHistory()
        history.recorded(UndoTarget.SYMBOLS)
        history.recorded(UndoTarget.DRAWINGS)
        history.recorded(UndoTarget.SYMBOLS)

        val performed = mutableListOf<String>()
        repeat(3) { history.undo { target -> performed += "undo $target"; true } }
        assertFalse(history.undo { error("nothing left to undo") })
        repeat(3) { history.redo { target -> performed += "redo $target"; true } }

        assertEquals(
            listOf(
                "undo SYMBOLS", "undo DRAWINGS", "undo SYMBOLS",
                "redo SYMBOLS", "redo DRAWINGS", "redo SYMBOLS",
            ),
            performed,
        )
        assertTrue(history.canUndo.value)
        assertFalse(history.canRedo.value)
    }

    @Test
    fun aNewStepInEitherStoreClearsEveryRedoStep() {
        val history = MissionUndoHistory()
        history.recorded(UndoTarget.DRAWINGS)
        history.recorded(UndoTarget.SYMBOLS)
        assertTrue(history.undo { true })
        assertTrue(history.undo { true })
        assertTrue(history.canRedo.value)

        history.recorded(UndoTarget.SYMBOLS)

        assertFalse(history.canRedo.value)
        assertFalse(history.redo { error("redo was cleared") })
        val undone = mutableListOf<UndoTarget>()
        history.undo { undone += it; true }
        assertEquals(listOf(UndoTarget.SYMBOLS), undone)
        assertFalse(history.canUndo.value)
    }

    @Test
    fun aFailedStoreWriteLeavesTheStepInPlaceForRetry() {
        val history = MissionUndoHistory()
        history.recorded(UndoTarget.SYMBOLS)
        history.recorded(UndoTarget.DRAWINGS)

        assertFalse(history.undo { false })
        assertTrue(history.canUndo.value)
        assertFalse(history.canRedo.value)

        val undone = mutableListOf<UndoTarget>()
        assertTrue(history.undo { undone += it; true })
        assertEquals(listOf(UndoTarget.DRAWINGS), undone)
        assertFalse(history.redo { false })
        assertTrue(history.canRedo.value)
    }

    @Test
    fun dropsAStoresOldestStepWhenThatStoreDropsItsOldestSnapshot() {
        val history = MissionUndoHistory(storeLimit = 2)
        history.recorded(UndoTarget.DRAWINGS)
        history.recorded(UndoTarget.SYMBOLS)
        history.recorded(UndoTarget.DRAWINGS)
        history.recorded(UndoTarget.DRAWINGS) // the store has now forgotten the first

        val undone = mutableListOf<UndoTarget>()
        while (history.undo { undone += it; true }) Unit
        assertEquals(listOf(UndoTarget.DRAWINGS, UndoTarget.DRAWINGS, UndoTarget.SYMBOLS), undone)
    }

    @Test
    fun storesUndoInTrueChronologicalOrderIncludingRangeRings() {
        val dir = Files.createTempDirectory("mission-undo").toFile()
        val waypoints = WaypointStore.forTests(dir, NoOpPersistence)
        val drawings = DrawingStore.forTests(dir, NoOpPersistence)
        val follower = RangeRingFollower(drawings)
        waypoints.committedChangeListener = follower::onWaypointsCommitted
        val history = MissionUndoHistory()
        waypoints.undoStepListener = { history.recorded(UndoTarget.SYMBOLS) }
        drawings.undoStepListener = { history.recorded(UndoTarget.DRAWINGS) }
        fun undo() = history.undo { target ->
            when (target) {
                UndoTarget.DRAWINGS -> drawings.undo().also {
                    if (it) follower.realign(waypoints.committedWaypoints.value)
                }
                UndoTarget.SYMBOLS -> waypoints.undo()
            }
        }
        fun redo() = history.redo { target ->
            when (target) {
                UndoTarget.DRAWINGS -> drawings.redo().also {
                    if (it) follower.realign(waypoints.committedWaypoints.value)
                }
                UndoTarget.SYMBOLS -> waypoints.redo()
            }
        }
        fun ringIds() = drawings.document.value.features.filter { it.anchorId != null }.map { it.id }

        // Symbol, then a line, then rings (one step), then a symbol move the
        // rings follow without a step of their own.
        val op = Waypoint(name = "OP", latitude = -33.8688, longitude = 151.2093)
        assertTrue(waypoints.add(op))
        val line = DrawingFeature(
            name = "PL", geometry = DrawingGeometry.LINE,
            points = listOf(DrawingPoint(-33.9, 151.1), DrawingPoint(-33.8, 151.3)),
        )
        assertTrue(drawings.addFeature(line))
        val rings = RangeRings.features(op, listOf(500.0, 1000.0), layerColor = null, density = 1f)
        assertTrue(drawings.addFeatures(rings))
        val moved = op.copy(latitude = -33.85)
        assertTrue(waypoints.update(moved))

        assertTrue(undo())
        assertEquals(op, waypoints.waypoints.value.single())
        assertEquals("rings follow the symbol back",
            RangeRings.ring(op.latitude, op.longitude, 500.0),
            drawings.document.value.features.first { it.id == rings[0].id }.points)

        assertTrue(undo())
        assertTrue("all rings go in one step", ringIds().isEmpty())
        assertEquals(listOf(line.id), drawings.document.value.features.map { it.id })

        assertTrue(undo())
        assertTrue(drawings.document.value.features.isEmpty())
        assertEquals(listOf(op), waypoints.waypoints.value)

        assertTrue(undo())
        assertTrue(waypoints.waypoints.value.isEmpty())
        assertFalse(history.canUndo.value)

        repeat(4) { assertTrue(redo()) }
        assertEquals(listOf(moved), waypoints.waypoints.value)
        assertEquals(rings.map { it.id }, ringIds())
        assertEquals(
            RangeRings.ring(moved.latitude, moved.longitude, 500.0),
            drawings.document.value.features.first { it.id == rings[0].id }.points,
        )
        assertFalse(history.canRedo.value)
    }

    private object NoOpPersistence : MissionStorePersistence {
        override fun write(file: File, label: String, text: String) = Unit
    }
}
