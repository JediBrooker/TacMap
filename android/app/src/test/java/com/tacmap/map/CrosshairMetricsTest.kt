package com.tacmap.map

import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrosshairMetricsTest {

    @Test
    fun oneDegreeAtEquatorIsAbout111Point2Km() {
        assertEquals(
            111_195.0,
            requireNotNull(crosshairDistanceMetres(0.0, 0.0, 0.0, 1.0)),
            20.0
        )
    }

    @Test
    fun sameCoordinateIsZero() {
        assertEquals(
            0.0,
            requireNotNull(
                crosshairDistanceMetres(-33.8688, 151.2093, -33.8688, 151.2093)
            ),
            0.001
        )
    }

    @Test
    fun invalidCoordinatesHideDistance() {
        assertNull(crosshairDistanceMetres(Double.NaN, 151.0, -34.0, 151.0))
        assertNull(crosshairDistanceMetres(-34.0, Double.POSITIVE_INFINITY, -34.0, 151.0))
        assertNull(crosshairDistanceMetres(90.0001, 151.0, -34.0, 151.0))
        assertNull(crosshairDistanceMetres(-34.0, -180.0001, -34.0, 151.0))
        assertNull(crosshairDistanceMetres(-34.0, 151.0, -90.0001, 151.0))
        assertNull(crosshairDistanceMetres(-34.0, 151.0, -34.0, 180.0001))
    }

    @Test
    fun movingWaypointChangesOnlyItsCoordinate() {
        val original = Waypoint(
            id = "symbol-1",
            name = "Bravo",
            latitude = -33.0,
            longitude = 150.0,
            kind = WaypointKind.Military()
        )

        val moved = moveWaypointToCrosshair(original, -34.0, 151.0)

        assertEquals(-34.0, moved.latitude, 0.0)
        assertEquals(151.0, moved.longitude, 0.0)
        assertEquals(original.copy(latitude = -34.0, longitude = 151.0), moved)
    }
}
