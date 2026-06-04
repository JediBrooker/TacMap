package com.tacticalmaps.map

import com.tacticalmaps.mgrs.MgrsFormatter
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchDialogTest {

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
    fun buildSearchResultsIncludesPartialGridResult() {
        val results = buildSearchResults(
            rawQuery = "1885",
            waypoints = emptyList(),
            drawings = emptyList(),
            cameraLat = -34.0522,
            cameraLng = 150.9550
        )

        assertTrue(results.any { it.id.startsWith("partial-mgrs:") })
    }
}
