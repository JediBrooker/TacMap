package com.tacmap.map

import com.tacmap.calibration.Wgs84Bounds
import com.tacmap.calibration.Wgs84Coordinate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineLookupCancellationTest {

    @Test
    fun coordinateLookupTransportRejectsEveryRedirect() {
        assertFalse(coordinateLookupHttpClient.followRedirects)
        assertFalse(coordinateLookupHttpClient.followSslRedirects)
    }

    @Test
    fun terrainStopsBeforeASecondBatchWhenGateTurnsOff() = runBlocking {
        var enabled = true
        var requests = 0
        val response = "{\"elevation\":[${List(100) { "42.0" }.joinToString(",")}] }"
        val service = TerrainHeatmapService(
            onlineLookupsEnabled = { enabled },
            fetchBody = { _, _ ->
                requests += 1
                enabled = false
                response
            },
        )

        val result = service.generate(
            Wgs84Bounds(
                southwest = Wgs84Coordinate(-34.1, 150.8),
                northeast = Wgs84Coordinate(-33.9, 151.0),
            ),
            grid = 11, // 121 samples would require two requests without re-gating.
        )

        assertNull(result)
        assertEquals(1, requests)
    }

    @Test
    fun elevationNeverCachesOrPublishesResponseCompletedAfterGateTurnsOff() = runBlocking {
        var enabled = true
        var requests = 0
        val service = ElevationService(
            onlineLookupsEnabled = { enabled },
            fetchBody = { _, _ ->
                requests += 1
                if (requests == 1) {
                    enabled = false
                    "{\"elevation\":[123.0]}"
                } else {
                    null
                }
            },
        )

        assertNull(service.reading(-33.8568, 151.2153))

        // If the first result had entered the cache, this second offline network
        // miss would incorrectly return it as either exact or nearby stale data.
        enabled = true
        assertNull(service.reading(-33.8568, 151.2153))
        assertEquals(2, requests)
    }
}
