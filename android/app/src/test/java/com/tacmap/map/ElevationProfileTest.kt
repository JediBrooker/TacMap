package com.tacmap.map

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pins profile sampling, statistics, line of sight and dead ground to
 * `testdata/elevation_profile.json` (shared with iOS). */
class ElevationProfileTest {
    private val fixture: JsonObject by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val file = File(dir, "testdata/elevation_profile.json")
            if (file.exists()) return@lazy Json.parseToJsonElement(file.readText()).jsonObject
            dir = dir?.parentFile
        }
        error("Could not locate testdata/elevation_profile.json")
    }

    private fun JsonObject.d(key: String): Double = this[key]!!.jsonPrimitive.double
    private fun JsonObject.i(key: String): Int = this[key]!!.jsonPrimitive.int

    private fun path(value: JsonObject, key: String = "path") = value[key]!!.jsonArray.map {
        ElevationProfile.Coordinate(it.jsonObject.d("lat"), it.jsonObject.d("lon"))
    }

    @Test
    fun constantsMatchSharedFixture() {
        assertEquals(fixture.d("earthRadiusMetres"), ElevationProfile.EARTH_RADIUS_METRES, 0.0)
        assertEquals(fixture.d("refractionCoefficient"), ElevationProfile.REFRACTION_COEFFICIENT, 0.0)
        assertEquals(fixture.i("maxSamples"), ElevationProfile.MAX_SAMPLES)
        assertEquals(fixture.d("targetSpacingMetres"), ElevationProfile.TARGET_SPACING_METRES, 0.0)
        assertEquals(fixture.i("requestBatchSize"), ElevationProfileService.BATCH_SIZE)
    }

    @Test
    fun samplingMatchesSharedFixture() {
        val metres = fixture.d("toleranceMetres")
        val degrees = fixture.d("toleranceDegrees")
        for (element in fixture["sampling"]!!.jsonArray) {
            val case = element.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            val samples = ElevationProfile.samples(path(case))
            assertEquals(name, case.i("count"), samples.size)
            assertEquals(name, case.d("lengthMetres"), samples.lastOrNull()?.distance ?: 0.0, metres)
            for (expected in case["samples"]!!.jsonArray) {
                val e = expected.jsonObject
                val sample = samples[e.i("index")]
                assertEquals(name, e.d("distance"), sample.distance, metres)
                assertEquals(name, e.d("lat"), sample.latitude, degrees)
                assertEquals(name, e.d("lon"), sample.longitude, degrees)
            }
        }
    }

    @Test
    fun statsAndLineOfSightMatchSharedFixture() {
        val metres = fixture.d("toleranceMetres")
        for (element in fixture["analysis"]!!.jsonArray) {
            val case = element.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            val distances = case["distances"]!!.jsonArray.map { it.jsonPrimitive.double }
            val elevations = case["elevations"]!!.jsonArray.map { it.jsonPrimitive.double }

            val stats = ElevationProfile.stats(elevations)!!
            val expectedStats = case["stats"]!!.jsonObject
            assertEquals(name, expectedStats.d("min"), stats.minimum, metres)
            assertEquals(name, expectedStats.d("max"), stats.maximum, metres)
            assertEquals(name, expectedStats.d("ascent"), stats.ascent, metres)
            assertEquals(name, expectedStats.d("descent"), stats.descent, metres)

            val sight = ElevationProfile.lineOfSight(
                distances, elevations, case.d("observerHeight"), case.d("targetHeight"),
            )
            assertNotNull(name, sight)
            sight!!
            assertEquals(name, case["blocked"]!!.jsonPrimitive.boolean, sight.blocked)
            val worstIndex = case["worstIndex"]
            if (worstIndex is JsonNull) {
                assertNull(name, sight.worstIndex)
                assertNull(name, sight.worstMargin)
            } else {
                assertEquals(name, worstIndex!!.jsonPrimitive.int, sight.worstIndex)
                assertEquals(name, case.d("worstMargin"), sight.worstMargin!!, metres)
            }
            assertEquals(name, case["visible"]!!.jsonArray.map { it.jsonPrimitive.boolean }, sight.visible)
            val heights = case["sightLine"]!!.jsonArray.map { it.jsonPrimitive.double }
            assertEquals(name, heights.size, sight.heights.size)
            heights.zip(sight.heights).forEach { (expected, actual) -> assertEquals(name, expected, actual, metres) }
        }
    }

    @Test
    fun chartRangeKeepsTheTerrainReadableOnLongLines() {
        assertNull(ElevationProfile.chartRange(emptyList(), emptyList()))
        // No sight line: just the terrain.
        assertEquals(80.0..150.0, ElevationProfile.chartRange(listOf(120.0, 80.0, 150.0), emptyList()))
        // A 100 m mast and a target above the terrain are always shown.
        assertEquals(100.0..200.0, ElevationProfile.chartRange(listOf(100.0, 130.0, 110.0), listOf(200.0, 160.0, 115.0)))
        // Curvature drops a long sight line far below: clipped one terrain
        // span (70 m) under the lowest ground.
        assertEquals(110.0..250.0, ElevationProfile.chartRange(listOf(180.0, 250.0, 206.0), listOf(182.0, -40_000.0, 208.0)))
        // Flat ground still leaves at least 50 m.
        assertEquals(-40.0..12.0, ElevationProfile.chartRange(listOf(10.0, 10.0, 10.0), listOf(12.0, -900.0, 12.0)))
    }

    @Test
    fun requestsGoOutInBatchesRoundedToFourDecimals() {
        for (element in fixture["requests"]!!.jsonArray) {
            val case = element.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            val urls = ElevationProfileService.requestUrls(ElevationProfile.samples(path(case)))
            val batches = case["batches"]!!.jsonArray
            assertEquals(name, batches.size, urls.size)
            urls.zip(batches).forEach { (url, batchElement) ->
                val batch = batchElement.jsonObject
                assertTrue(name, url.startsWith("https://api.open-meteo.com/v1/elevation?"))
                val query = url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
                val latitudes = query.getValue("latitude").split(',')
                val longitudes = query.getValue("longitude").split(',')
                assertEquals(name, batch.i("count"), latitudes.size)
                assertEquals(name, batch.i("count"), longitudes.size)
                assertEquals(name, batch["latitudeFirst"]!!.jsonPrimitive.content, latitudes.first())
                assertEquals(name, batch["longitudeFirst"]!!.jsonPrimitive.content, longitudes.first())
                assertEquals(name, batch["latitudeLast"]!!.jsonPrimitive.content, latitudes.last())
                assertEquals(name, batch["longitudeLast"]!!.jsonPrimitive.content, longitudes.last())
            }
        }
    }

    private val shortLine = ElevationProfile.samples(
        listOf(ElevationProfile.Coordinate(51.5, -0.12), ElevationProfile.Coordinate(51.51, -0.12))
    )

    @Test
    fun fetchRefusesWhileOnlineLookupsAreOff() = runBlocking {
        var called = false
        val service = ElevationProfileService(onlineLookupsEnabled = { false }) { _, _ ->
            called = true
            null
        }
        assertEquals(ElevationProfileService.Result.LookupsOff, service.elevations(shortLine))
        assertFalse("No request may leave the device while online lookups are off", called)
    }

    @Test
    fun fetchJoinsBatchesInOrder() = runBlocking {
        val samples = ElevationProfile.samples(
            listOf(ElevationProfile.Coordinate(51.5, -0.12), ElevationProfile.Coordinate(51.5405, -0.12))
        )
        assertEquals(151, samples.size)
        var requests = 0
        val service = ElevationProfileService(onlineLookupsEnabled = { true }) { url, _ ->
            val count = url.substringAfter("latitude=").substringBefore('&').split(',').size
            val offset = if (requests++ == 0) 0 else 100
            """{"elevation":[${(0 until count).joinToString(",") { (offset + it).toString() }}]}"""
        }
        val result = service.elevations(samples)
        assertEquals(2, requests)
        assertEquals(ElevationProfileService.Result.Heights((0 until 151).map { it.toDouble() }), result)
    }

    @Test
    fun fetchFailsOnShortMissingOrBrokenReplies() = runBlocking {
        for (reply in listOf("""{"elevation":[1.0]}""", """{"elevation":[null]}""", "not json", null)) {
            val service = ElevationProfileService(onlineLookupsEnabled = { true }) { _, _ -> reply }
            assertEquals(reply.toString(), ElevationProfileService.Result.NetworkFailed, service.elevations(shortLine))
        }
    }
}
