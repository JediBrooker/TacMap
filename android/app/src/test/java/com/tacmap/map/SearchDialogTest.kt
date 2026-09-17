package com.tacmap.map

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.drawings.DrawingPoint
import com.tacmap.mgrs.MgrsFormatter
import com.tacmap.waypoints.MarkerSet
import com.tacmap.waypoints.MarkerSymbol
import com.tacmap.waypoints.MilitarySymbolSpec
import com.tacmap.waypoints.SymbolFunction
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs

class SearchDialogTest {

    @Test
    fun productionPlaceCoordinatorNeverInvokesProviderForCoordinateShapedInput() = runBlocking {
        val queries = Json.parseToJsonElement(fixtureText("search_contract.json"))
            .jsonObject.getValue("coordinateEgress").jsonObject
            .getValue("mustStayOffline").jsonArray.map { it.jsonPrimitive.content }
        var providerCalls = 0

        queries.forEach { query ->
            val offline = searchOffline(
                rawQuery = query,
                waypoints = emptyList(),
                drawings = emptyList(),
                layers = emptyList(),
                cameraLat = -33.8568,
                cameraLng = 151.2153,
            )
            assertTrue(query, offline.recognizedCoordinateInput)
            val outcome = performOnlinePlaceLookup(query, offline, onlineLookups = true) {
                providerCalls += 1
                emptyList()
            }
            assertTrue(query, outcome.results.isEmpty())
        }

        assertEquals("coordinate input must never reach Geocoder", 0, providerCalls)
    }

    @Test
    fun productionPlaceCoordinatorCallsProviderForMissionProseOnlyWhenEnabled() = runBlocking {
        val proseQueries = Json.parseToJsonElement(fixtureText("search_contract.json"))
            .jsonObject.getValue("coordinateEgress").jsonObject
            .getValue("eligiblePlaceProse").jsonArray.map { it.jsonPrimitive.content }
        var providerCalls = 0

        proseQueries.forEach { prose ->
            val offline = searchOffline(
                rawQuery = prose,
                waypoints = emptyList(),
                drawings = emptyList(),
                layers = emptyList(),
            )
            assertFalse(prose, offline.recognizedCoordinateInput)
            performOnlinePlaceLookup(prose, offline, onlineLookups = true) {
                providerCalls += 1
                emptyList()
            }
        }
        val disabledProse = proseQueries.first()
        val disabledOffline = searchOffline(
            rawQuery = disabledProse,
            waypoints = emptyList(),
            drawings = emptyList(),
            layers = emptyList(),
        )
        val disabled = performOnlinePlaceLookup(disabledProse, disabledOffline, onlineLookups = false) {
            providerCalls += 1
            emptyList()
        }

        assertEquals(proseQueries.size, providerCalls)
        assertTrue(disabled.disabled)
    }

    @Test
    fun blankRecentsUseNewestThenCanonicalIdTieBreak() {
        val tiedAt = 123L
        val response = searchOffline(
            rawQuery = "",
            waypoints = listOf(
                Waypoint(id = "zulu", name = "Zulu", latitude = 0.0, longitude = 0.0, createdAt = tiedAt),
                Waypoint(id = "alpha", name = "Alpha", latitude = 0.0, longitude = 0.0, createdAt = tiedAt),
                Waypoint(id = "older", name = "Older", latitude = 0.0, longitude = 0.0, createdAt = tiedAt - 1),
            ),
            drawings = emptyList(),
            layers = emptyList(),
        )

        assertEquals(
            listOf("waypoint:alpha", "waypoint:zulu", "waypoint:older"),
            response.results.map { it.id },
        )
    }

    @Test
    fun resolvesPartialGridAgainstCameraPrefix() {
        val cameraLat = -34.0522
        val cameraLng = 150.9550
        val cameraMgrs = MgrsFormatter.format(cameraLat, cameraLng, spaced = false)
        val prefix = Regex("""^(\d{1,2}[A-Z][A-Z]{2})""")
            .find(cameraMgrs)!!
            .groupValues[1]

        val result = partialGridResult("1885", cameraLat, cameraLng)

        assertNotNull(result)
        assertTrue(result!!.title.startsWith(prefix))
        assertTrue(result.subtitle.contains("1 km"))
        assertTrue(result.latitude in -90.0..90.0)
        assertTrue(result.longitude in -180.0..180.0)
    }

    @Test
    fun resolvesPartialGridAtLegitimateZeroZeroCameraCoordinate() {
        assertNotNull(partialGridResult("1660", 0.0, 0.0))
    }

    @Test
    fun fullReducedPrecisionMgrsSearchUsesSquareCentre() {
        val full = "56HLH1885"
        val expected = resolveMgrsCoordinate(full, null, null)!!

        val response = searchOffline(
            rawQuery = full,
            waypoints = emptyList(),
            drawings = emptyList(),
            layers = emptyList(),
        )

        assertEquals(1, response.results.size)
        assertEquals(expected.latitude, response.results.single().latitude, 1e-12)
        assertEquals(expected.longitude, response.results.single().longitude, 1e-12)
        assertTrue(response.results.single().subtitle.contains("Centre of 1 km"))
    }

    @Test
    fun buildSearchResultsIncludesPartialGridResult() {
        val results = buildSearchResults(
            rawQuery = "1885",
            waypoints = emptyList(),
            drawings = emptyList(),
            cameraLat = -34.0522,
            cameraLng = 150.9550
        )

        assertTrue(results.any { it.id == "coordinate:partial-mgrs" })
    }

    @Test
    fun missionNamesContainingDigitsAreNotParsedAsPartialGridReferences() {
        assertNull(partialGridResult("Route 1885", -34.0522, 150.9550))
        assertNull(partialGridResult("18-85", -34.0522, 150.9550))
        assertNull(partialGridResult("1 885", -34.0522, 150.9550))

        val waypoint = Waypoint(
            id = "route-1885",
            name = "Route 1885",
            latitude = -34.0,
            longitude = 151.0,
        )
        val response = searchOffline(
            rawQuery = "Route 1885",
            waypoints = listOf(waypoint),
            drawings = emptyList(),
            layers = emptyList(),
            cameraLat = -34.0522,
            cameraLng = 150.9550,
        )

        assertEquals(listOf("waypoint:route-1885"), response.results.map { it.id })
        assertEquals(false, response.recognizedCoordinateInput)
    }

    @Test
    fun placeStatusCopyMatchesTheSharedContractExactly() {
        val places = Json.parseToJsonElement(fixtureText("search_contract.json"))
            .jsonObject.getValue("places").jsonObject

        assertEquals(places.getValue("disabledStatus").jsonPrimitive.content, PLACES_DISABLED_STATUS)
        assertEquals(places.getValue("offlineStatus").jsonPrimitive.content, PLACES_OFFLINE_STATUS)
    }

    @Test
    fun sharedOfflineSearchContractMatchesResultKindsOrderAndCoordinates() {
        val root = Json.parseToJsonElement(fixtureText("search_contract.json")).jsonObject
        val anchor = root.getValue("anchor").jsonObject
        val layers = root.getValue("layers").jsonArray.map { element ->
            val layer = element.jsonObject
            DrawingLayer(
                id = layer.getValue("id").jsonPrimitive.content,
                name = layer.getValue("name").jsonPrimitive.content,
            )
        }
        val waypointInputs = root.getValue("waypoints").jsonArray.map { it.jsonObject }
        val waypoints = waypointInputs.mapIndexed { index, waypoint ->
            val kind = if (index == 0) {
                WaypointKind.Military(MilitarySymbolSpec(function = SymbolFunction.MEDICAL))
            } else {
                WaypointKind.Marker(MarkerSymbol(MarkerSet.POI, "checkpoint", "#3BC85A"))
            }
            Waypoint(
                id = waypoint.getValue("id").jsonPrimitive.content,
                name = waypoint.getValue("name").jsonPrimitive.content,
                notes = waypoint.getValue("notes").jsonPrimitive.content,
                kind = kind,
                layerId = waypoint.getValue("layerId").jsonPrimitive.content,
                latitude = waypoint.getValue("latitude").jsonPrimitive.double,
                longitude = waypoint.getValue("longitude").jsonPrimitive.double,
                createdAt = waypoint.getValue("createdOrder").jsonPrimitive.int.toLong(),
            )
        }
        val drawings = root.getValue("drawings").jsonArray.map { element ->
            val drawing = element.jsonObject
            val geometry = DrawingGeometry.entries.first {
                it.displayName == drawing.getValue("geometryDisplayName").jsonPrimitive.content
            }
            val centre = DrawingPoint(
                drawing.getValue("centreLatitude").jsonPrimitive.double,
                drawing.getValue("centreLongitude").jsonPrimitive.double,
            )
            DrawingFeature(
                id = drawing.getValue("id").jsonPrimitive.content,
                name = drawing.getValue("name").jsonPrimitive.content,
                notes = drawing.getValue("notes").jsonPrimitive.content,
                geometry = geometry,
                points = when (geometry) {
                    DrawingGeometry.POINT -> listOf(centre)
                    DrawingGeometry.LINE -> listOf(
                        centre.copy(longitude = centre.longitude - 0.0001),
                        centre.copy(longitude = centre.longitude + 0.0001),
                    )
                    DrawingGeometry.POLYGON -> listOf(
                        centre.copy(latitude = centre.latitude - 0.0001),
                        centre.copy(longitude = centre.longitude + 0.0001),
                        centre.copy(latitude = centre.latitude + 0.0001),
                    )
                },
                layerId = drawing.getValue("layerId").jsonPrimitive.content,
                createdAt = drawing.getValue("createdOrder").jsonPrimitive.int.toLong(),
            )
        }
        val anchorLat = anchor.getValue("latitude").jsonPrimitive.double
        val anchorLng = anchor.getValue("longitude").jsonPrimitive.double

        root.getValue("queries").jsonArray.forEach { element ->
            val case = element.jsonObject
            val response = searchOffline(
                rawQuery = case.getValue("query").jsonPrimitive.content,
                waypoints = waypoints,
                drawings = drawings,
                layers = layers,
                cameraLat = anchorLat,
                cameraLng = anchorLng,
            )
            val expectedIds = case.getValue("expectedResultIds").jsonArray.map {
                it.jsonPrimitive.content
            }
            assertEquals(case.getValue("caseKey").jsonPrimitive.content, expectedIds, response.results.map { it.id })
            case["expectedStatus"]?.jsonPrimitive?.content?.let { expected ->
                assertEquals(expected, response.status)
            }
            case["expectedCoordinate"]?.jsonObject?.let { expected ->
                val result = response.results.single()
                val tolerance = expected.getValue("toleranceDegrees").jsonPrimitive.double
                assertTrue(abs(result.latitude - expected.getValue("latitude").jsonPrimitive.double) <= tolerance)
                assertTrue(abs(result.longitude - expected.getValue("longitude").jsonPrimitive.double) <= tolerance)
            }
            assertEquals(0, case.getValue("networkRequests").jsonPrimitive.int)
        }
    }

    private fun fixtureText(name: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val fixture = File(dir, "testdata/$name")
            if (fixture.exists()) return fixture.readText()
            dir = dir?.parentFile
        }
        error("Could not locate testdata/$name")
    }
}
