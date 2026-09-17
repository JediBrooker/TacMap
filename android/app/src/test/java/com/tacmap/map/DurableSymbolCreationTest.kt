package com.tacmap.map

import com.tacmap.waypoints.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableSymbolCreationTest {
    @Test
    fun failedCreationKeepsTheDraftAndReturnsAnActionableRetryError() {
        val waypoint = Waypoint(name = "Alpha", latitude = -33.0, longitude = 151.0)
        var attempts = 0

        val first = persistNewSymbol(waypoint) {
            attempts += 1
            false
        }
        assertTrue(first is DurableSymbolCreation.Failed)
        first as DurableSymbolCreation.Failed
        assertSame(waypoint, first.waypoint)
        assertEquals(SYMBOL_CREATION_ERROR, first.message)

        val retry = persistNewSymbol(first.waypoint) {
            attempts += 1
            true
        }
        assertTrue(retry is DurableSymbolCreation.Saved)
        assertEquals(2, attempts)
    }

    @Test
    fun successReturnsTheExactDurablyStoredSymbolForSelection() {
        val waypoint = Waypoint(name = "Bravo", latitude = -34.0, longitude = 150.0)
        var persisted: Waypoint? = null

        val result = persistNewSymbol(waypoint) {
            persisted = it
            true
        }

        assertSame(waypoint, persisted)
        assertSame(waypoint, (result as DurableSymbolCreation.Saved).waypoint)
    }
}
