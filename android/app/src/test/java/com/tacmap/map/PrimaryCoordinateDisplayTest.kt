package com.tacmap.map

import com.tacmap.settings.CoordinateDisplayType
import org.junit.Assert.assertEquals
import org.junit.Test

class PrimaryCoordinateDisplayTest {
    private val mgrs = "56H LH 33456 62518"
    private val wgs84 = "33.86880° S, 151.20930° E"
    private val utm = "56S 334369mE 6250947mN"

    @Test fun resolvesEachAvailablePreference() {
        assertEquals(
            PrimaryCoordinateDisplay(CoordinateDisplayType.MGRS, mgrs),
            resolvePrimaryCoordinateDisplay(CoordinateDisplayType.MGRS, mgrs, wgs84, utm)
        )
        assertEquals(
            PrimaryCoordinateDisplay(CoordinateDisplayType.WGS84, wgs84),
            resolvePrimaryCoordinateDisplay(CoordinateDisplayType.WGS84, mgrs, wgs84, utm)
        )
        assertEquals(
            PrimaryCoordinateDisplay(CoordinateDisplayType.UTM, utm),
            resolvePrimaryCoordinateDisplay(CoordinateDisplayType.UTM, mgrs, wgs84, utm)
        )
    }

    @Test fun unavailableUtmFallsBackToWgs84() {
        listOf(null, "", "N/A (>84°N)", "  n/a (<80°S)").forEach { unavailable ->
            assertEquals(
                PrimaryCoordinateDisplay(CoordinateDisplayType.WGS84, wgs84),
                resolvePrimaryCoordinateDisplay(
                    CoordinateDisplayType.UTM,
                    mgrs,
                    wgs84,
                    unavailable
                )
            )
        }
    }
}
