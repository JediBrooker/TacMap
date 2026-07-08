package com.tacmap.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Mirror of the iOS DatumTests: WGS84/GDA2020 are coincident, GDA94 differs by
 * ~1.8 m. Same Helmert constants as iOS, so the two platforms agree.
 */
class DatumTest {

    private val sydneyLat = -33.8568
    private val sydneyLng = 151.2153

    @Test
    fun wgs84AndGda2020AreIdentity() {
        assertEquals(sydneyLat, Datum.WGS84.toWgs84(sydneyLat, sydneyLng).first, 1e-12)
        assertEquals(sydneyLng, Datum.WGS84.toWgs84(sydneyLat, sydneyLng).second, 1e-12)
        assertEquals(sydneyLat, Datum.GDA2020.toWgs84(sydneyLat, sydneyLng).first, 1e-12)
        assertEquals(sydneyLng, Datum.GDA2020.toWgs84(sydneyLat, sydneyLng).second, 1e-12)
    }

    @Test
    fun gda94ShiftHasExpectedMagnitudeAndDirection() {
        val (lat, lng) = Datum.GDA94.toWgs84(sydneyLat, sydneyLng)
        val dLatM = (lat - sydneyLat) * 111_320.0
        val dLngM = (lng - sydneyLng) * 111_320.0 * cos(sydneyLat * Math.PI / 180)
        val metres = sqrt(dLatM * dLatM + dLngM * dLngM)
        assertTrue("shift $metres m out of expected ~1.8 m band", metres in 1.0..2.5)
        // GDA2020 (≈WGS84) coordinates sit ~1.8 m to the NORTH-EAST of GDA94
        // (Geoscience Australia). Asserting the sign of each component — not just
        // the magnitude — so a transposed/sign-flipped Helmert matrix is caught.
        assertTrue("expected a northward shift, got ${dLatM} m", dLatM > 0.0)
        assertTrue("expected an eastward shift, got ${dLngM} m", dLngM > 0.0)
    }
}
