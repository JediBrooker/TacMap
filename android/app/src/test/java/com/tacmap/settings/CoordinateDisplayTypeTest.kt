package com.tacmap.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateDisplayTypeTest {
    @Test fun absentOrUnknownPreferenceDefaultsToMgrs() {
        assertEquals(CoordinateDisplayType.MGRS, CoordinateDisplayType.fromPersisted(null))
        assertEquals(CoordinateDisplayType.MGRS, CoordinateDisplayType.fromPersisted(""))
        assertEquals(CoordinateDisplayType.MGRS, CoordinateDisplayType.fromPersisted("unknown"))
    }

    @Test fun persistedEnumNamesRoundTrip() {
        CoordinateDisplayType.entries.forEach { type ->
            assertEquals(type, CoordinateDisplayType.fromPersisted(type.name))
        }
    }

    @Test fun nextCyclesThroughAllThreeAndBackToMgrs() {
        assertEquals(CoordinateDisplayType.WGS84, CoordinateDisplayType.MGRS.next)
        assertEquals(CoordinateDisplayType.UTM, CoordinateDisplayType.WGS84.next)
        assertEquals(CoordinateDisplayType.MGRS, CoordinateDisplayType.UTM.next)
        // three taps from anywhere lands you back where you started
        CoordinateDisplayType.entries.forEach { type ->
            assertEquals(type, type.next.next.next)
        }
        assertEquals(CoordinateDisplayType.entries.toSet(), CoordinateDisplayType.entries.map { it.next }.toSet())
    }

    @Test fun mapOrientationDefaultsNorthUpAndRoundTrips() {
        assertEquals(MapOrientationMode.NORTH_UP, MapOrientationMode.fromPersisted(null))
        assertEquals(MapOrientationMode.NORTH_UP, MapOrientationMode.fromPersisted("unknown"))
        MapOrientationMode.entries.forEach { mode ->
            assertEquals(mode, MapOrientationMode.fromPersisted(mode.name))
        }
    }
}
