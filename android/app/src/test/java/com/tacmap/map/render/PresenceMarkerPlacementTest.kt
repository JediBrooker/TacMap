package com.tacmap.map.render

import androidx.compose.ui.geometry.Offset
import com.tacmap.sync.PresencePeer
import com.tacmap.sync.presenceMarkerPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceMarkerPlacementTest {
    private val visibleLeft = 11f
    private val visibleTop = 7f
    private val visibleRight = 91f
    private val visibleBottom = 167f

    @Test
    fun hqSymbolVisibleBoundsAndCallsignShareOneProjectedAnchor() {
        val projected = Offset(640f, 980f)
        val placement = placement(projected, isHeadquarters = true, density = 3f)

        assertEquals(projected.x, placement.iconLeftPx + (visibleLeft + visibleRight) / 2f, 0.001f)
        assertEquals(projected.y, placement.iconTopPx + (visibleTop + visibleBottom) / 2f, 0.001f)
        assertEquals(projected.x, placement.labelCentreXPx, 0.001f)

        val visibleHeight = visibleBottom - visibleTop
        val expectedFrameBottom = projected.y - visibleHeight / 2f + visibleHeight * 0.62f
        assertEquals(expectedFrameBottom + 9f, placement.labelTopPx, 0.001f)
        assertTrue(
            "HQ callsign must sit below the frame, not below the long staff",
            placement.labelTopPx < projected.y + visibleHeight / 2f,
        )
    }

    @Test
    fun ordinaryUnitCallsignSitsImmediatelyBelowVisibleSymbol() {
        val projected = Offset(240f, 360f)
        val placement = placement(projected, isHeadquarters = false, density = 2f)

        val visibleBottomOnScreen = projected.y + (visibleBottom - visibleTop) / 2f
        assertEquals(visibleBottomOnScreen + 6f, placement.labelTopPx, 0.001f)
        assertEquals(projected.x, placement.labelCentreXPx, 0.001f)
    }

    @Test
    fun cameraZoomRotationAndPeerLocationMoveIconAndLabelTogether() {
        val density = 2.75f
        val baseCamera = MapCamera(
            centerLat = -35.29286,
            centerLon = 149.12687,
            zoom = 14.0,
            headingDegrees = 0.0,
            viewportWidth = 393.0,
            viewportHeight = 852.0,
        )
        val changedCamera = baseCamera.copy(
            centerLat = -35.2919,
            centerLon = 149.1282,
            zoom = 17.5,
            headingDegrees = 137.0,
        )
        val locations = listOf(
            -35.29286 to 149.12687,
            -35.29342 to 149.12761,
        )

        listOf(baseCamera, changedCamera).forEach { camera ->
            val projection = MapProjection(camera, density)
            val firstScreen = projection.toScreen(locations[0].first, locations[0].second)
            val secondScreen = projection.toScreen(locations[1].first, locations[1].second)
            val first = placement(firstScreen, isHeadquarters = true, density = density)
            val second = placement(secondScreen, isHeadquarters = true, density = density)
            assertMovesTogether(firstScreen, secondScreen, first, second)
        }

        val firstLocation = locations.first()
        val baseScreen = MapProjection(baseCamera, density)
            .toScreen(firstLocation.first, firstLocation.second)
        val changedScreen = MapProjection(changedCamera, density)
            .toScreen(firstLocation.first, firstLocation.second)
        assertMovesTogether(
            baseScreen,
            changedScreen,
            placement(baseScreen, isHeadquarters = true, density = density),
            placement(changedScreen, isHeadquarters = true, density = density),
        )
    }

    @Test
    fun staleMarkerExplicitlyShowsLastKnownAgeAndAccessibleMeaning() {
        val stale = PresencePeer(
            clientId = "peer", callsign = "11A", affiliation = "friend",
            echelon = "team", function = "infantry", isHQ = false,
            lat = -35.0, lon = 149.0, heading = 0.0, speed = 0.0, ts = 1,
            receivedAtUptimeMs = 1_000L,
            reconnectGraceStartedAtUptimeMs = 2_000L,
        )
        val presentation = presenceMarkerPresentation(stale, nowUptimeMs = 121_000L)
        assertEquals("11A · Last known 2m", presentation.visibleLabel)
        assertTrue(presentation.accessibilityLabel.contains("Last known 2 minutes ago"))

        val fresh = presenceMarkerPresentation(
            stale.copy(reconnectGraceStartedAtUptimeMs = null),
            nowUptimeMs = 10_000L,
        )
        assertEquals("11A", fresh.visibleLabel)
        assertEquals("11A", fresh.accessibilityLabel)
    }

    private fun assertMovesTogether(
        firstScreen: Offset,
        secondScreen: Offset,
        first: PresenceMarkerPlacement,
        second: PresenceMarkerPlacement,
    ) {
        val expectedDx = secondScreen.x - firstScreen.x
        val expectedDy = secondScreen.y - firstScreen.y
        assertEquals(expectedDx, second.iconLeftPx - first.iconLeftPx, 0.001f)
        assertEquals(expectedDy, second.iconTopPx - first.iconTopPx, 0.001f)
        assertEquals(expectedDx, second.labelCentreXPx - first.labelCentreXPx, 0.001f)
        assertEquals(expectedDy, second.labelTopPx - first.labelTopPx, 0.001f)
    }

    private fun placement(
        projected: Offset,
        isHeadquarters: Boolean,
        density: Float,
    ): PresenceMarkerPlacement = presenceMarkerPlacement(
        projectedX = projected.x,
        projectedY = projected.y,
        visibleLeftPx = visibleLeft,
        visibleTopPx = visibleTop,
        visibleRightPx = visibleRight,
        visibleBottomPx = visibleBottom,
        isHeadquarters = isHeadquarters,
        density = density,
    )
}
