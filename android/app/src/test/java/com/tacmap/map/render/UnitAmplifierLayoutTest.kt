package com.tacmap.map.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitAmplifierLayoutTest {
    @Test
    fun fieldsUseRequestedCornersAndHqStaffDoesNotPullBottomFieldsDown() {
        val unit = unitAmplifierAnchors(100f, 200f, 80f, 100f, false, 1f)
        val headquarters = unitAmplifierAnchors(100f, 200f, 80f, 100f, true, 1f)

        assertEquals(unit.fieldMX, unit.fieldFX, 0f)
        assertTrue(unit.fieldTX < 60f)
        assertTrue(unit.fieldMX > 140f)
        assertTrue(unit.fieldFY < unit.fieldMY)
        assertTrue(headquarters.fieldMY < unit.fieldMY)
        assertEquals(headquarters.fieldMY, headquarters.fieldTY, 0f)
    }
}
