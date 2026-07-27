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
}
