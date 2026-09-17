package com.tacmap.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class LgiProjectionTest {
    @Test
    fun utmAndEquivalentTransverseMercatorResolveSydney() {
        // EPSG:32756 coordinates for Sydney CBD.
        val easting = 334_368.6336
        val northing = 6_250_945.575
        val datum = requireNotNull(LgiDatum.fromCode("WE"))
        val utm = requireNotNull(LgiProjectionFactory.utm(56, true, datum.ellipsoid))
        val tc = requireNotNull(
            LgiProjectionFactory.transverseMercator(
                centralMeridian = 153.0,
                originLatitude = 0.0,
                falseEasting = 500_000.0,
                falseNorthing = 10_000_000.0,
                scaleFactor = 0.9996,
                ellipsoid = datum.ellipsoid,
            )
        )

        val fromUtm = requireNotNull(LgiCoordinateConverter(utm, datum).toWgs84(easting, northing))
        val fromTc = requireNotNull(LgiCoordinateConverter(tc, datum).toWgs84(easting, northing))
        // Cross-checked with NGA UTM 2.1.3's independent inverse.
        assertEquals(-33.8688251, fromUtm.first, 1e-6)
        assertEquals(151.2092995, fromUtm.second, 1e-6)
        assertEquals(fromUtm.first, fromTc.first, 1e-10)
        assertEquals(fromUtm.second, fromTc.second, 1e-10)
    }

    @Test
    fun legacyTokyoDatumIsShiftedOntoWgs84LikeIos() {
        val sourceLatitude = 35.68
        val sourceLongitude = 139.77
        val shifted = requireNotNull(
            requireNotNull(LgiDatum.fromCode("TC")).toWgs84(sourceLatitude, sourceLongitude)
        )

        assertEquals(464.0, distanceMetres(sourceLatitude, sourceLongitude, shifted.first, shifted.second), 50.0)
        assertTrue(shifted.first in -90.0..90.0)
        assertTrue(shifted.second in -180.0..180.0)
    }

    @Test
    fun unsupportedAndIncompleteProjectionDefinitionsFailClosed() {
        assertNull(LgiDatum.fromCode("UNKNOWN"))
        assertNull(LgiDatum.inline(6_378_137.0, 0.0, 0.0, 0.0, 0.0))
        assertNull(LgiProjectionFactory.utm(0, false, LgiEllipsoid.WGS84))
        assertNull(LgiProjectionFactory.utm(61, false, LgiEllipsoid.WGS84))
        assertNull(
            LgiProjectionFactory.transverseMercator(
                centralMeridian = Double.NaN,
                originLatitude = 0.0,
                falseEasting = 0.0,
                falseNorthing = 0.0,
                scaleFactor = 1.0,
                ellipsoid = LgiEllipsoid.WGS84,
            )
        )
        assertNull(LgiCoordinateConverter(LgiProjection.LongLat, LgiDatum.WGS84).toWgs84(500_000.0, 6_000_000.0))
        assertNotNull(LgiDatum.fromCode("GD"))
    }

    private fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = lat1 * PI / 180.0
        val phi2 = lat2 * PI / 180.0
        val deltaPhi = (lat2 - lat1) * PI / 180.0
        val deltaLambda = (lon2 - lon1) * PI / 180.0
        val a = sin(deltaPhi / 2.0) * sin(deltaPhi / 2.0) +
            cos(phi1) * cos(phi2) * sin(deltaLambda / 2.0) * sin(deltaLambda / 2.0)
        return 6_371_000.0 * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }
}
