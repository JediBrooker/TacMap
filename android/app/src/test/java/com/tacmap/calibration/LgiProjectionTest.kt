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

/**
 * LGIDict projection + datum decoding, now on the shared GeoCrs / GeoDatums the
 * GeoPDF and fiduciary paths use too (the old LgiProjection/LgiDatum are gone).
 * Same three contracts as before.
 */
class LgiProjectionTest {
    @Test
    fun utmAndEquivalentTransverseMercatorResolveSydney() {
        // EPSG:32756 coordinates for Sydney CBD.
        val easting = 334_368.6336
        val northing = 6_250_945.575
        val datum = requireNotNull(GeoDatums.forLgiCode("WE"))
        val utm = GeoCrs.utm(56, true)
        val tc = GeoCrs.TransverseMercator(
            lat0 = 0.0,
            lon0 = 153.0,
            k0 = 0.9996,
            fe = 500_000.0,
            fn = 10_000_000.0,
        )

        val fromUtm = requireNotNull(utm.inverse(easting, northing, datum.ellipsoid))
        val fromTc = requireNotNull(tc.inverse(easting, northing, datum.ellipsoid))
        // Cross-checked with NGA UTM 2.1.3's independent inverse.
        assertEquals(-33.8688251, fromUtm.latitude, 1e-6)
        assertEquals(151.2092995, fromUtm.longitude, 1e-6)
        assertEquals(fromUtm.latitude, fromTc.latitude, 1e-10)
        assertEquals(fromUtm.longitude, fromTc.longitude, 1e-10)
        assertEquals(56, utm.utmZone)
        assertEquals('S', utm.hemisphere)
    }

    @Test
    fun legacyTokyoDatumIsShiftedOntoWgs84LikeIos() {
        val sourceLatitude = 35.68
        val sourceLongitude = 139.77
        val shifted = requireNotNull(
            requireNotNull(GeoDatums.forLgiCode("TC")).toWGS84(sourceLatitude, sourceLongitude)
        )

        assertEquals(464.0, distanceMetres(sourceLatitude, sourceLongitude, shifted.latitude, shifted.longitude), 50.0)
        assertTrue(shifted.latitude in -90.0..90.0)
        assertTrue(shifted.longitude in -180.0..180.0)
    }

    @Test
    fun unsupportedAndIncompleteProjectionDefinitionsFailClosed() {
        assertNull(GeoDatums.forLgiCode("UNKNOWN"))
        assertNull(GeoDatum.custom(6_378_137.0, 0.0))
        assertNull(GeoCrs.TransverseMercator(0.0, Double.NaN, 1.0, 0.0, 0.0).projector(GeoEllipsoid.WGS84))
        // lon/lat can't hold metres
        assertNull(GeoCrs.Geographic.inverse(500_000.0, 6_000_000.0, GeoEllipsoid.WGS84))
        assertNotNull(GeoDatums.forLgiCode("GD"))
        // the real file codes the old table rejected (D1-06)
        assertEquals("WGS84", GeoDatums.forLgiCode("WGE")?.id)
        assertEquals("NAD83", GeoDatums.forLgiCode("NAR-C")?.id)
        assertEquals("NAD27", GeoDatums.forLgiCode("NAS-C")?.id)
        assertEquals("NAD27_CANADA", GeoDatums.forLgiCode("NAS-E")?.id)
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
