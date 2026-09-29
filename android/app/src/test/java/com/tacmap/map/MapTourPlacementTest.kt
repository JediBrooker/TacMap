package com.tacmap.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tour card always ends up fully on screen, beside the control when it fits. */
class MapTourPlacementTest {

    private fun place(spotTop: Float, spotBottom: Float, cardHeight: Float, screenHeight: Float = 1000f) =
        calloutPlacement(
            spotTop = spotTop,
            spotBottom = spotBottom,
            cardHeight = cardHeight,
            screenHeight = screenHeight,
            topInset = 40f,
            bottomInset = 30f,
            gap = 8f,
            arrowHeight = 11f,
        )

    @Test
    fun controlNearTheTopGetsTheCardBelowIt() {
        val placement = place(spotTop = 100f, spotBottom = 150f, cardHeight = 300f)
        assertEquals(CalloutArrow.UP, placement.arrow)
        assertEquals(150f + 8f + 11f, placement.cardY)
        assertEquals(158f, placement.arrowY)
    }

    @Test
    fun controlNearTheBottomGetsTheCardAboveIt() {
        val placement = place(spotTop = 850f, spotBottom = 900f, cardHeight = 300f)
        assertEquals(CalloutArrow.DOWN, placement.arrow)
        assertEquals(850f - 8f - 11f - 300f, placement.cardY)
        assertEquals(850f - 8f - 11f, placement.arrowY)
    }

    @Test
    fun whenBothSidesFitTheRoomierOneWins() {
        // 560 above against 330 below; a 300 card fits either way.
        val placement = place(spotTop = 600f, spotBottom = 640f, cardHeight = 300f)
        assertEquals(CalloutArrow.DOWN, placement.arrow)
        assertEquals(600f - 8f - 11f - 300f, placement.cardY)
    }

    @Test
    fun cardThatFitsNeitherSideStaysOnScreenWithoutAnArrow() {
        // A small screen with a control in the middle: 262 below, 256 above.
        val placement = place(spotTop = 296f, spotBottom = 330f, cardHeight = 280f, screenHeight = 640f)
        assertEquals(CalloutArrow.NONE, placement.arrow)
        assertTrue("Card starts above the top inset", placement.cardY >= 40f)
        assertTrue("Card runs past the bottom inset", placement.cardY + 280f <= 640f - 30f)
    }
}
