package com.tacmap.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MBTilesMetadataTest {
    @Test
    fun validMetadataHonoursZoomsAndBounds() {
        val metadata = validatedMBTilesMetadata(
            rows = mapOf(
                "name" to " Field map ",
                "format" to "PNG",
                "minzoom" to "4",
                "maxzoom" to "16",
                "bounds" to "150.0,-34.0,151.0,-33.0",
            ),
            tileZoomRange = 6 to 12,
        )

        assertEquals("Field map", metadata.name)
        assertEquals("png", metadata.format)
        assertEquals(4, metadata.minZoom)
        assertEquals(16, metadata.maxZoom)
        assertEquals(-34.0, metadata.bounds!!.southwest.latitude, 0.0)
        assertEquals(151.0, metadata.bounds!!.northeast.longitude, 0.0)
    }

    @Test
    fun absentOptionalMetadataUsesActualTileZoomRange() {
        val metadata = validatedMBTilesMetadata(emptyMap(), 7 to 11)

        assertEquals(7, metadata.minZoom)
        assertEquals(11, metadata.maxZoom)
        assertNull(metadata.bounds)
    }

    @Test
    fun untrustedNameIsBounded() {
        val metadata = validatedMBTilesMetadata(mapOf("name" to "x".repeat(10_000)), 0 to 0)
        assertEquals(128, metadata.name!!.length)
    }

    @Test
    fun malformedNonFiniteAndOutOfOrderBoundsAreRejected() {
        listOf(
            "150,-34,151",
            "NaN,-34,151,-33",
            "150,-34,Infinity,-33",
            "151,-34,150,-33",
            "150,-33,151,-34",
            "181,-34,182,-33",
            "150,-91,151,-33",
            "1".repeat(10_000),
        ).forEach { encoded ->
            val result = runCatching {
                validatedMBTilesMetadata(mapOf("bounds" to encoded), 0 to 12)
            }
            assertTrue("Expected rejection for $encoded", result.isFailure)
        }
    }

    @Test
    fun malformedOrReversedZoomsAreRejected() {
        listOf(
            mapOf("minzoom" to "NaN"),
            mapOf("maxzoom" to "1.5"),
            mapOf("minzoom" to "9", "maxzoom" to "8"),
            mapOf("maxzoom" to "31"),
            mapOf("minzoom" to "-1"),
        ).forEach { rows ->
            assertTrue(runCatching { validatedMBTilesMetadata(rows, 0 to 12) }.isFailure)
        }
    }
}
