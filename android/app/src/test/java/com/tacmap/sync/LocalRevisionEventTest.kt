package com.tacmap.sync

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingPoint
import com.tacmap.drawings.DrawingStore
import com.tacmap.models.ModelMutationOrigin
import com.tacmap.util.DataKey
import com.tacmap.util.SafeStore
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class LocalRevisionEventTest {
    private val sealedLabels = mutableSetOf<String>()

    @Before fun installStoreKey() {
        SafeStore.keyProvider = SafeStore.KeyProvider { ByteArray(32) { (it + 7).toByte() } }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = label in sealedLabels
            override fun markSealedOnly(label: String) { sealedLabels += label }
        }
    }

    @After fun restoreStoreKey() {
        SafeStore.keyProvider = SafeStore.KeyProvider { DataKey.key() }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = DataKey.isStoreSealedOnly(label)
            override fun markSealedOnly(label: String) = DataKey.markStoreSealedOnly(label)
        }
    }

    @Test fun actualStoresEmitEveryRapidInverseMutationAndSuppressOnlyRemote() = runBlocking {
        val dir = Files.createTempDirectory("revision-events").toFile()
        val waypointStore = WaypointStore.forTests(dir)
        val drawingStore = DrawingStore.forTests(dir)
        val journal = LocalModelRevisionJournal(dir)
        assertTrue(journal.load())
        val processor = LocalRevisionEventProcessor(journal) { error("unexpected persistence failure") }

        // Store construction/load establishes state without a synthetic mutation.
        assertNull(withTimeoutOrNull(50) { waypointStore.mutations.first() })
        assertNull(withTimeoutOrNull(50) { drawingStore.mutations.first() })

        val waypoint = Waypoint(name = "A", latitude = -33.0, longitude = 151.0)
        waypointStore.add(waypoint)
        waypointStore.updateNoUndo(waypoint.copy(name = "B"))
        waypointStore.updateNoUndo(waypoint) // exact revert, must not conflate
        waypointStore.remove(waypoint)       // create -> delete, also distinct
        waypointStore.mutations.take(4).toList().forEach { assertTrue(processor.process(it)) }
        assertEquals(4, journal.generation(waypoint.id))

        val drawing = DrawingFeature(
            name = "line", geometry = DrawingGeometry.LINE,
            points = listOf(DrawingPoint(-33.0, 151.0), DrawingPoint(-33.1, 151.1)))
        drawingStore.addFeature(drawing)
        drawingStore.updateFeatureNoUndo(drawing.copy(name = "changed"))
        drawingStore.updateFeatureNoUndo(drawing)
        drawingStore.removeFeature(drawing.id)
        drawingStore.mutations.take(4).toList().forEach { assertTrue(processor.process(it)) }
        assertEquals(4, journal.generation(drawing.id))

        val remote = waypoint.copy(id = "3d9650f4-d728-4273-a646-87f64d363d9f")
        waypointStore.add(remote, ModelMutationOrigin.REMOTE_SYNC)
        assertTrue(processor.process(waypointStore.mutations.first()))
        assertEquals(0, journal.generation(remote.id))
    }

    @Test fun sealedJournalReloadsAcrossLeaveAndFailureGatesOutboundProcessor() {
        val dir = Files.createTempDirectory("revision-reload").toFile()
        val id = "71d0f3d2-7d33-4af4-a593-d4cb70fb808d"
        val first = LocalModelRevisionJournal(dir)
        assertTrue(first.load())
        var saved = first.bump(id); assertTrue(first.lastPersistenceError?.toString(), saved)
        saved = first.bump(id); assertTrue(first.lastPersistenceError?.toString(), saved)

        // New instance models process restart after Leave; sealed count survives.
        val restarted = LocalModelRevisionJournal(dir)
        assertTrue(restarted.load())
        assertEquals(2, restarted.generation(id))

        var failedClosed = false
        val failing = LocalModelRevisionJournal(dir, persistOverride = { false })
        val processor = LocalRevisionEventProcessor(failing) { failedClosed = true }
        assertFalse(processor.process(com.tacmap.models.ModelMutationEvent(setOf(id))))
        assertTrue(failedClosed)
        assertEquals(0, failing.generation(id))
    }
}
