package com.tacmap.map

import androidx.compose.ui.geometry.Offset
import com.tacmap.map.render.MapCamera
import org.junit.Assert.assertEquals
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
}
