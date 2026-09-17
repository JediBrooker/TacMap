package com.tacmap.waypoints

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WaypointAmplifierTest {
    @Test
    fun fieldLimitsCountUnicodeCodePointsAndNormalizationTrims() {
        val value = "  " + "😀".repeat(HIGHER_FORMATION_MAX_CODE_POINTS + 4) + "  "
        val normalized = normalizedUnitAmplifier(value, HIGHER_FORMATION_MAX_CODE_POINTS)!!

        assertEquals(
            HIGHER_FORMATION_MAX_CODE_POINTS,
            normalized.codePointCount(0, normalized.length),
        )
        assertNull(normalizedUnitAmplifier("   ", UNIQUE_IDENTIFIER_MAX_CODE_POINTS))
    }

    @Test
    fun fieldFUsesParenthesizedDoctrineConstructs() {
        assertEquals("", ReinforcementStatus.NONE.amplifier)
        assertEquals("(+)", ReinforcementStatus.REINFORCED.amplifier)
        assertEquals("(-)", ReinforcementStatus.REDUCED.amplifier)
    }

    @Test
    fun categoryChangeCannotLeaveHiddenAmplifierDataOnNonUnitGraphic() {
        val normalized = normalizedUnitAmplifiersForKind(
            kind = WaypointKind.ControlMeasure(),
            higherFormation = "BG-Waratah",
            uniqueIdentifier = "I11",
            reinforcementStatus = ReinforcementStatus.REINFORCED,
        )

        assertNull(normalized.higherFormation)
        assertNull(normalized.uniqueIdentifier)
        assertEquals(ReinforcementStatus.NONE, normalized.reinforcementStatus)
    }
}
