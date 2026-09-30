package com.tacmap.models

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
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

/** Undo only ever moves your own edits. Unit Sync writes from peers are folded into the saved
 * snapshots instead of becoming steps (they used to, and Undo deleted peers' work). */
class SyncedEditUndoTest {
    private val dir: File = Files.createTempDirectory("synced-undo").toFile()
    private val waypoints = WaypointStore.forTests(dir, NoOpPersistence)
    private val drawings = DrawingStore.forTests(dir, NoOpPersistence)
    private val follower = RangeRingFollower(drawings).also { waypoints.committedChangeListener = it::onWaypointsCommitted }
    private val history = MissionUndoHistory().also { history ->
        waypoints.undoStepListener = { history.recorded(UndoTarget.SYMBOLS) }
        drawings.undoStepListener = { history.recorded(UndoTarget.DRAWINGS) }
    }

    private fun undo() = history.undo { target ->
        when (target) {
            UndoTarget.DRAWINGS -> drawings.undo().also { if (it) follower.realign(waypoints.committedWaypoints.value) }
            UndoTarget.SYMBOLS -> waypoints.undo()
        }
    }

    private fun redo() = history.redo { target ->
        when (target) {
            UndoTarget.DRAWINGS -> drawings.redo().also { if (it) follower.realign(waypoints.committedWaypoints.value) }
            UndoTarget.SYMBOLS -> waypoints.redo()
        }
    }

    private val remote = ModelMutationOrigin.REMOTE_SYNC

    @Test
    fun undoRemovesYourRingsNotTheUnitAPeerPlacedAfterThem() {
        // what two emulators hit: rings from the symbol card, peer drops a marker, Undo
        val op = Waypoint(name = "OP", latitude = -33.8688, longitude = 151.2093)
        assertTrue(waypoints.add(op))
        val rings = RangeRings.features(op, listOf(500.0, 1000.0), layerColor = null, density = 1f)
        assertTrue(drawings.addFeatures(rings))
        val peerMarker = Waypoint(name = "B COY", latitude = -33.86, longitude = 151.20)
        assertTrue(waypoints.add(peerMarker, remote))

        assertTrue(undo())
        assertTrue(drawings.document.value.features.none { it.anchorId == op.id })
        assertEquals(listOf(op, peerMarker), waypoints.waypoints.value)

        assertTrue(undo())
        assertEquals("peer marker survives undoing our own symbol", listOf(peerMarker), waypoints.waypoints.value)
        assertFalse(history.canUndo.value)
    }

    @Test
    fun peerWritesAloneNeverEnableUndo() {
        assertTrue(waypoints.add(Waypoint(name = "peer", latitude = 1.0, longitude = 2.0), remote))
        val line = line("peer PL")
        assertTrue(drawings.addFeature(line, remote))
        assertTrue(drawings.updateFeatureNoUndo(line.copy(name = "peer PL 2"), remote))
        assertTrue(drawings.removeFeature(line.id, remote))
        assertFalse(history.canUndo.value)
        assertFalse(waypoints.canUndo.value)
        assertFalse(drawings.canUndo.value)
    }

    @Test
    fun undoingYourEditKeepsAPeerUpdateAndDeleteThatLandedLater() {
        val shared = line("shared")
        val doomed = line("doomed")
        assertTrue(drawings.addFeature(shared, remote))
        assertTrue(drawings.addFeature(doomed, remote))
        val mine = line("mine")
        assertTrue(drawings.addFeature(mine))

        val peerEdit = shared.copy(name = "shared v2")
        assertTrue(drawings.updateFeatureNoUndo(peerEdit, remote))
        assertTrue(drawings.removeFeature(doomed.id, remote))

        assertTrue(undo())
        assertEquals(listOf(peerEdit), drawings.document.value.features)
        assertTrue(redo())
        assertEquals(listOf(peerEdit, mine), drawings.document.value.features)
    }

    @Test
    fun peerWritesAreCarriedIntoRedoSnapshotsToo() {
        val mine = Waypoint(name = "mine", latitude = 0.0, longitude = 0.0)
        assertTrue(waypoints.add(mine))
        assertTrue(undo())
        val peer = Waypoint(name = "peer", latitude = 1.0, longitude = 1.0)
        assertTrue(waypoints.add(peer, remote))

        assertTrue(redo())
        assertEquals(listOf(peer, mine).map { it.id }.toSet(), waypoints.waypoints.value.map { it.id }.toSet())
    }

    @Test
    fun peerEditToSomethingYouCreatedDoesNotStopUndoingTheCreate() {
        val mine = line("mine")
        assertTrue(drawings.addFeature(mine))
        assertTrue(drawings.updateFeatureNoUndo(mine.copy(name = "peer renamed it"), remote))

        assertTrue(undo())
        assertTrue(drawings.document.value.features.isEmpty())
    }

    @Test
    fun peerWriteLandingMidGestureStillIsntAnUndoStep() {
        val wp = Waypoint(name = "peer", latitude = 1.0, longitude = 1.0)
        assertTrue(waypoints.add(wp, remote))
        assertTrue(waypoints.previewUpdate(wp.copy(latitude = 1.5)))
        // update() routes through commitPreview while a preview is open for the same id
        assertTrue(waypoints.update(wp.copy(latitude = 2.0), remote))
        assertFalse(waypoints.canUndo.value)
        assertFalse(history.canUndo.value)
    }

    @Test
    fun peerLayerSurvivesUndo() {
        val mine = line("mine")
        assertTrue(drawings.addFeature(mine))
        val layer = DrawingLayer(name = "Peer layer", color = 0xFF00FF00.toInt())
        assertTrue(drawings.addLayerVerbatim(layer, remote))

        assertTrue(undo())
        assertTrue(drawings.document.value.layers.any { it.id == layer.id })
        assertTrue(drawings.document.value.features.isEmpty())
    }

    @Test
    fun foldKeepsOrderAndOnlyAppendsObjectsThePeerCreated() {
        data class Item(val id: String, val v: Int)
        val before = listOf(Item("a", 1), Item("b", 1), Item("c", 1))
        val after = listOf(Item("a", 2), Item("c", 1), Item("d", 1))
        val fold = RemoteChangeFold(before, after, Item::id)

        assertEquals(
            listOf(Item("x", 0), Item("a", 2), Item("d", 1)),
            fold.applyTo(listOf(Item("x", 0), Item("a", 1), Item("b", 1))),
        )
        assertEquals("update to an object the snapshot predates stays out", listOf(Item("d", 1)), fold.applyTo(emptyList()))
        assertTrue(RemoteChangeFold(before, before, Item::id).isEmpty)
    }

    private fun line(name: String) = DrawingFeature(
        name = name,
        geometry = DrawingGeometry.LINE,
        points = listOf(DrawingPoint(-33.9, 151.1), DrawingPoint(-33.8, 151.3)),
    )

    private object NoOpPersistence : MissionStorePersistence {
        override fun write(file: File, label: String, text: String) = Unit
    }
}
