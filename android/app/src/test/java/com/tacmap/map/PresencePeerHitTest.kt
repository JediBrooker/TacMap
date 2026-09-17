package com.tacmap.map

import androidx.compose.ui.geometry.Offset
import com.tacmap.sync.PresencePeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresencePeerHitTest {
    @Test
    fun nearestPeerInsideTapRadiusWins() {
        val far = peer("far")
        val near = peer("near")
        val hit = closestPresencePeer(
            point = Offset(100f, 100f),
            projectedPeers = listOf(
                far to Offset(120f, 100f),
                near to Offset(104f, 103f),
            ),
            radiusPx = 32f,
        )
        assertEquals("near", hit?.clientId)
    }

    @Test
    fun movementStartingAwayFromPeerHasNoPeerHit() {
        assertNull(
            closestPresencePeer(
                Offset(0f, 0f),
                listOf(peer("peer") to Offset(80f, 80f)),
                32f,
            )
        )
    }

    private fun peer(id: String) = PresencePeer(
        clientId = id,
        callsign = id,
        affiliation = "FRIEND",
        echelon = "TEAM",
        function = "INFANTRY",
        isHQ = false,
        lat = -33.8,
        lon = 151.2,
        heading = 0.0,
        speed = 0.0,
        ts = 1L,
    )
}
