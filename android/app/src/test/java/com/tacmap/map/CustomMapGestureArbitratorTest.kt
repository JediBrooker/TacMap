package com.tacmap.map

import androidx.compose.ui.geometry.Offset
import com.tacmap.map.render.MapCamera
import com.tacmap.map.render.orientationInvariantGridBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CustomMapGestureArbitratorTest {

    @Test
    fun secondPointerPromotesWaypointStreamToMapTransform() {
        val arbitrator = CustomMapGestureArbitrator(startedOnItem = true)

        assertEquals(CustomMapGestureMode.ITEM_PENDING, arbitrator.mode)
        assertEquals(
            CustomMapGestureMode.ITEM_PENDING,
            arbitrator.update(pointerCount = 1, movedBeyondSlop = false)
        )
        assertEquals(
            CustomMapGestureMode.MAP_TRANSFORM,
            arbitrator.update(pointerCount = 2, movedBeyondSlop = false)
        )
        assertEquals(
            "lifting finger two must not hand the stream back to item dragging",
            CustomMapGestureMode.MAP_TRANSFORM,
            arbitrator.update(pointerCount = 1, movedBeyondSlop = true)
        )
    }

    @Test
    fun oneFingerStillDragsItemAfterTouchSlop() {
        val arbitrator = CustomMapGestureArbitrator(startedOnItem = true)

        assertEquals(
            CustomMapGestureMode.ITEM_DRAG,
            arbitrator.update(pointerCount = 1, movedBeyondSlop = true)
        )
        assertEquals(
            "a second finger must cancel even an item drag already in progress",
            CustomMapGestureMode.MAP_TRANSFORM,
            arbitrator.update(pointerCount = 2, movedBeyondSlop = true)
        )
    }

    @Test
    fun promotedStreamAppliesRotationToCameraHeading() {
        val camera = MapCamera(
            centerLat = -33.8688,
            centerLon = 151.2093,
            zoom = 12.0,
            headingDegrees = 5.0,
            viewportWidth = 400.0,
            viewportHeight = 800.0
        )

        val rotated = applyCustomMapTransform(
            camera = camera,
            centroidPx = Offset(200f, 400f),
            panPx = Offset.Zero,
            zoomChange = 1f,
            rotationDegrees = 15f,
            density = 1f,
            minZoom = 2.0,
            maxZoom = 22.0
        )

        assertEquals(350.0, rotated.headingDegrees, 1e-9)
    }

    @Test
    fun headingUpIgnoresManualRotationButKeepsOtherTransformsAvailable() {
        val camera = MapCamera(
            centerLat = -33.8688,
            centerLon = 151.2093,
            zoom = 12.0,
            headingDegrees = 20.0,
            viewportWidth = 400.0,
            viewportHeight = 800.0,
        )

        val transformed = applyCustomMapTransform(
            camera = camera,
            centroidPx = Offset(200f, 400f),
            panPx = Offset.Zero,
            zoomChange = 2f,
            rotationDegrees = 15f,
            density = 1f,
            minZoom = 2.0,
            maxZoom = 22.0,
            rotationEnabled = false,
            headingOverrideDegrees = 30.0,
        )

        assertEquals(30.0, transformed.headingDegrees, 1e-9)
        assertEquals(13.0, transformed.zoom, 1e-9)
    }

    @Test
    fun heatmapCoverageIsInvariantAcrossMapHeading() {
        val camera = MapCamera(
            centerLat = -33.8688,
            centerLon = 151.2093,
            zoom = 12.0,
            headingDegrees = 0.0,
            viewportWidth = 400.0,
            viewportHeight = 800.0,
        )

        assertEquals(
            orientationInvariantHeatmapBounds(camera),
            orientationInvariantHeatmapBounds(camera.copy(headingDegrees = 90.0)),
        )
    }

    @Test
    fun heatmapCoverageChangesWhenViewportDiagonalChanges() {
        val camera = MapCamera(
            centerLat = -33.8688,
            centerLon = 151.2093,
            zoom = 12.0,
            headingDegrees = 0.0,
            viewportWidth = 400.0,
            viewportHeight = 800.0,
        )

        assertNotEquals(
            orientationInvariantHeatmapBounds(camera),
            orientationInvariantHeatmapBounds(
                camera.copy(viewportWidth = 800.0, viewportHeight = 800.0),
            ),
        )
    }

    @Test
    fun gridCoverageIgnoresHeadingButRespondsToViewportDiagonal() {
        val camera = MapCamera(
            centerLat = -33.8688,
            centerLon = 151.2093,
            zoom = 12.0,
            headingDegrees = 0.0,
            viewportWidth = 400.0,
            viewportHeight = 800.0,
        )
        val coverage = orientationInvariantGridBounds(camera)

        assertEquals(
            coverage,
            orientationInvariantGridBounds(camera.copy(headingDegrees = 90.0)),
        )
        assertNotEquals(
            coverage,
            orientationInvariantGridBounds(
                camera.copy(viewportWidth = 800.0, viewportHeight = 800.0),
            ),
        )
    }
}
