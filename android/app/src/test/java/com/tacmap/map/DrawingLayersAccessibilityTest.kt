package com.tacmap.map

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawingLayersAccessibilityTest {
    @Test
    fun layerColourTalkBackLabelUsesStableRgbValue() {
        assertEquals("Layer colour #1E88E5", layerColorAccessibilityLabel(0xFF1E88E5.toInt()))
        assertEquals("Layer colour #00A010", layerColorAccessibilityLabel(0x4400A010))
    }
}
