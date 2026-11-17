package com.tacmap.map

import com.tacmap.models.LiveMapLocationState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunTipsGateTest {
    @Test
    fun tourWaitsWhileTheLocationPromptIsStillPending() {
        assertFalse(FirstRunTips.mayShow(LiveMapLocationState.NotRequested, graceOver = false))
    }

    @Test
    fun tourShowsOnceThePromptHasAnAnswerOfAnyKind() {
        for (state in LiveMapLocationState.entries.filter { it != LiveMapLocationState.NotRequested }) {
            assertTrue(state.name, FirstRunTips.mayShow(state, graceOver = false))
        }
    }

    @Test
    fun tourShowsAfterTheGraceEvenWithNoPrompt() {
        assertTrue(FirstRunTips.mayShow(LiveMapLocationState.NotRequested, graceOver = true))
    }
}
