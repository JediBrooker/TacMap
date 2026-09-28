package com.tacmap.drawings

import com.tacmap.util.MissionStorePersistence
import com.tacmap.waypoints.MilitarySymbolSpec
import com.tacmap.waypoints.SymbolAffiliation
import com.tacmap.waypoints.SymbolEchelon
import com.tacmap.waypoints.TaskColor
import com.tacmap.waypoints.TacticalControlMeasure
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/** Pins range-ring geometry to `testdata/range_rings.json` (shared with iOS)
 * and checks that rings commit as ordinary drawings in one undo step. */
class RangeRingsTest {
    private val fixture: JsonObject by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val file = File(dir, "testdata/range_rings.json")
            if (file.exists()) return@lazy Json.parseToJsonElement(file.readText()).jsonObject
            dir = dir?.parentFile
        }
        error("Could not locate testdata/range_rings.json")
    }

    private fun JsonObject.d(key: String): Double = this[key]!!.jsonPrimitive.double
    private fun JsonObject.i(key: String): Int = this[key]!!.jsonPrimitive.int

    @Test
    fun ringGeometryMatchesSharedFixture() {
        assertEquals(fixture.i("segments"), RangeRings.SEGMENTS)
        assertEquals(fixture.i("maxRings"), RangeRings.MAX_RINGS)
        assertEquals(fixture.d("maxRadiusMetres"), RangeRings.MAX_RADIUS_METRES, 0.0)
        val tolerance = fixture.d("toleranceDegrees")
        for (element in fixture["cases"]!!.jsonArray) {
            val case = element.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            val center = case["center"]!!.jsonObject
            val ring = RangeRings.ring(center.d("lat"), center.d("lon"), case.d("radiusMetres"))
            assertEquals(name, fixture.i("pointsPerRing"), ring.size)
            assertEquals("$name must close", ring.first(), ring.last())
            for (sampleElement in case["samples"]!!.jsonArray) {
                val sample = sampleElement.jsonObject
                val point = ring[sample.i("index")]
                assertEquals(name, sample.d("lat"), point.latitude, tolerance)
                val lonError = abs(((point.longitude - sample.d("lon") + 540.0) % 360.0) - 180.0)
                assertTrue("$name longitude error $lonError", lonError < tolerance)
                assertTrue(name, point.longitude >= -180.0 && point.longitude < 180.0)
            }
        }
    }

    @Test
    fun radiiContract() {
        for (element in fixture["radii"]!!.jsonArray) {
            val valid = element.jsonObject
            assertEquals(
                valid["expected"]!!.jsonArray.map { it.jsonPrimitive.double },
                RangeRings.radii(valid.d("intervalMetres"), valid.i("count")),
            )
        }
        for (element in fixture["invalid"]!!.jsonArray) {
            val invalid = element.jsonObject
            assertNull(RangeRings.radii(invalid.d("intervalMetres"), invalid.i("count")))
        }
        assertNull(RangeRings.radii(Double.NaN, 1))
        assertNull(RangeRings.radii(Double.POSITIVE_INFINITY, 1))
    }

    @Test
    fun featuresAreDashedLinesOnTheSymbolLayerInItsAffiliationColour() {
        val colors = fixture["strokeColors"]!!.jsonObject
        fun argb(key: String): Int = (0xFF000000 or colors[key]!!.jsonPrimitive.content.removePrefix("#").toLong(16)).toInt()
        val hostile = Waypoint(
            name = "Enemy MG",
            latitude = -33.8688,
            longitude = 151.2093,
            kind = WaypointKind.Military(MilitarySymbolSpec(SymbolAffiliation.HOSTILE, SymbolEchelon.TEAM)),
            layerId = "hostile",
        )
        val features = RangeRings.features(hostile, listOf(300.0, 600.0), layerColor = 0xFF123456.toInt(), density = 2.5f)
        assertEquals(2, features.size)
        for (feature in features) {
            assertEquals(DrawingGeometry.LINE, feature.geometry)
            assertEquals("hostile", feature.layerId)
            assertEquals(argb("hostile"), feature.strokeColor)
            assertEquals(DrawingStrokeStyle.DASHED, feature.strokeStyle)
            assertEquals(RangeRings.STROKE_WIDTH_DP * 2.5f, feature.strokeWidth, 0.0001f)
            assertEquals(RangeRings.SEGMENTS + 1, feature.points.size)
            assertEquals(feature.points.first(), feature.points.last())
        }
        assertNotEquals(features[0].id, features[1].id)

        val friendlyTask = Waypoint(
            name = "OBJ",
            latitude = 0.0,
            longitude = 0.0,
            kind = WaypointKind.ControlMeasure(TacticalControlMeasure.SEIZE),
            taskColor = TaskColor.BLUE,
        )
        assertEquals(argb("friend"), RangeRings.strokeColor(friendlyTask, layerColor = null))
        val marker = Waypoint(name = "RV", latitude = 0.0, longitude = 0.0)
        assertEquals(0xFF123456.toInt(), RangeRings.strokeColor(marker, layerColor = 0xFF123456.toInt()))
    }

    @Test
    fun ringBatchIsOneDurableWriteAndOneUndoStep() {
        val persistence = CountingPersistence()
        val store = DrawingStore.forTests(Files.createTempDirectory("range-rings").toFile(), persistence)
        persistence.writes = 0
        val before = store.document.value.features
        val waypoint = Waypoint(name = "OP", latitude = -33.8688, longitude = 151.2093)
        val rings = RangeRings.features(waypoint, listOf(500.0, 1000.0, 1500.0), layerColor = null, density = 1f)

        assertTrue(store.addFeatures(rings))
        assertEquals("one ring batch must produce one durable write", 1, persistence.writes)
        assertEquals(before.map { it.id } + rings.map { it.id }, store.document.value.features.map { it.id })
        assertTrue("a retried batch is a no-op", store.addFeatures(rings))
        assertEquals(1, persistence.writes)

        assertTrue(store.undo())
        assertEquals(before, store.document.value.features)
        assertFalse(store.canUndo.value)
        assertTrue(store.redo())
        assertEquals(before.map { it.id } + rings.map { it.id }, store.document.value.features.map { it.id })
    }

    @Test
    fun failedRingBatchPublishesNothing() {
        val persistence = CountingPersistence()
        val store = DrawingStore.forTests(Files.createTempDirectory("range-rings").toFile(), persistence)
        val before = store.document.value
        persistence.fail = true
        val waypoint = Waypoint(name = "OP", latitude = 1.0, longitude = 2.0)

        assertFalse(store.addFeatures(RangeRings.features(waypoint, listOf(100.0), layerColor = null, density = 1f)))
        assertEquals(before, store.document.value)
        assertFalse(store.canUndo.value)
    }

    @Test
    fun ringsFollowLocalMovesAndUndoButNotRemoteSync() {
        val dir = Files.createTempDirectory("range-rings").toFile()
        val drawings = DrawingStore.forTests(dir, CountingPersistence())
        val waypoints = com.tacmap.waypoints.WaypointStore.forTests(dir, CountingPersistence())
        val follower = RangeRingFollower(drawings)
        waypoints.committedChangeListener = follower::onWaypointsCommitted

        val op = Waypoint(name = "OP", latitude = -33.8688, longitude = 151.2093)
        val other = Waypoint(name = "HQ", latitude = -33.9, longitude = 151.1)
        assertTrue(waypoints.add(op))
        assertTrue(waypoints.add(other))
        val rings = RangeRings.features(op, listOf(500.0, 1000.0), layerColor = null, density = 1f)
        val otherRing = RangeRings.features(other, listOf(250.0), layerColor = null, density = 1f)
        assertTrue(drawings.addFeatures(rings + otherRing))
        assertEquals(op.id, rings[0].anchorId)
        assertEquals(1000.0, rings[1].ringRadiusMetres!!, 0.0)
        fun ring(id: String) = drawings.document.value.features.first { it.id == id }

        val moved = op.copy(latitude = -33.85, longitude = 151.25)
        assertTrue(waypoints.update(moved))
        assertEquals(RangeRings.ring(moved.latitude, moved.longitude, 500.0), ring(rings[0].id).points)
        assertEquals(RangeRings.ring(moved.latitude, moved.longitude, 1000.0), ring(rings[1].id).points)
        assertEquals("rings of other symbols stay put", otherRing[0], ring(otherRing[0].id))

        assertTrue(waypoints.undo())
        assertEquals(rings[0].points, ring(rings[0].id).points)
        assertTrue(waypoints.redo())
        assertEquals(RangeRings.ring(moved.latitude, moved.longitude, 500.0), ring(rings[0].id).points)

        val remote = moved.copy(latitude = -33.8, longitude = 151.3)
        assertTrue(waypoints.update(remote, com.tacmap.models.ModelMutationOrigin.REMOTE_SYNC))
        assertEquals("the moving peer sends its own ring updates",
            RangeRings.ring(moved.latitude, moved.longitude, 500.0), ring(rings[0].id).points)

        // Following adds no undo entry: one drawing undo removes the rings.
        assertTrue(drawings.undo())
        assertTrue(drawings.document.value.features.none { it.anchorId != null })
        assertFalse(drawings.canUndo.value)
    }

    @Test
    fun realignFixesRingsRestoredByDrawingUndo() {
        val dir = Files.createTempDirectory("range-rings").toFile()
        val drawings = DrawingStore.forTests(dir, CountingPersistence())
        val waypoints = com.tacmap.waypoints.WaypointStore.forTests(dir, CountingPersistence())
        val follower = RangeRingFollower(drawings)
        waypoints.committedChangeListener = follower::onWaypointsCommitted
        val op = Waypoint(name = "OP", latitude = 10.0, longitude = 20.0)
        assertTrue(waypoints.add(op))
        val ring = RangeRings.features(op, listOf(300.0), layerColor = null, density = 1f).single()
        assertTrue(drawings.addFeatures(listOf(ring)))
        val line = DrawingFeature(
            name = "PL", geometry = DrawingGeometry.LINE,
            points = listOf(DrawingPoint(0.0, 0.0), DrawingPoint(1.0, 1.0)),
        )
        assertTrue(drawings.addFeature(line))
        val moved = op.copy(latitude = 11.0)
        assertTrue(waypoints.update(moved))

        // Undoing the line restores a snapshot taken before the symbol moved.
        assertTrue(drawings.undo())
        assertEquals(ring.points, drawings.document.value.features.first { it.id == ring.id }.points)
        follower.realign(waypoints.committedWaypoints.value)
        assertEquals(RangeRings.ring(11.0, 20.0, 300.0), drawings.document.value.features.first { it.id == ring.id }.points)
    }

    @Test
    fun followedSkipsUnanchoredAndUnmovedRings() {
        val op = Waypoint(name = "OP", latitude = 10.0, longitude = 20.0)
        val ring = RangeRings.features(op, listOf(300.0), layerColor = null, density = 1f).single()
        assertNull("already centred", RangeRings.followed(ring, op))
        val moved = op.copy(latitude = 11.0)
        assertNull("not a ring of this symbol", RangeRings.followed(ring.copy(anchorId = null), moved))
        assertEquals(0.0, RangeRings.followed(ring.copy(rotationDegrees = 45.0), op)!!.rotationDegrees, 0.0)
        assertEquals(RangeRings.ring(11.0, 20.0, 300.0), RangeRings.followed(ring, moved)!!.points)
    }

    @Test
    fun ringAnchorTravelsWithUnitSyncButNotFileImport() {
        val op = Waypoint(name = "OP", latitude = 10.0, longitude = 20.0)
        val ring = RangeRings.features(op, listOf(300.0), layerColor = null, density = 1f).single()
        val json = com.tacmap.export.GeoJsonExporter.export(waypoints = emptyList(), drawings = listOf(ring))
        assertTrue(json.contains("tacticalmaps:anchor_id"))

        val synced = com.tacmap.export.GeoJsonImporter.parse(
            json, existingLayers = emptyList(), fallbackLayerId = "default", keepRingAnchors = true,
        ).drawings.single()
        assertEquals(op.id, synced.anchorId)
        assertEquals(300.0, synced.ringRadiusMetres!!, 0.0)

        val imported = com.tacmap.export.GeoJsonImporter.parse(
            json, existingLayers = emptyList(), fallbackLayerId = "default",
        ).drawings.single()
        assertNull(imported.anchorId)
        assertNull(imported.ringRadiusMetres)

        val plain = ring.copy(anchorId = null, ringRadiusMetres = null)
        assertFalse(com.tacmap.export.GeoJsonExporter.export(emptyList(), listOf(plain)).contains("anchor_id"))
    }

    private class CountingPersistence(var writes: Int = 0, var fail: Boolean = false) : MissionStorePersistence {
        override fun write(file: File, label: String, text: String) {
            writes++
            if (fail) error("simulated persistence failure")
        }
    }
}
