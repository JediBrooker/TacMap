package com.tacmap.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCameraLifecycleTest {
    private val sydney = MapViewportState(
        latitude = -35.291960,
        longitude = 149.126868,
        zoom = 15.25,
        bearingDegrees = 37.0,
    )

    @Test
    fun uninitializedConstructionSentinelIsNeverPublished() {
        val entry = MapCameraLifecyclePolicy.enter(retained = null, pendingTarget = null)

        assertEquals(0.0, entry.camera.centerLat, 0.0)
        assertEquals(0.0, entry.camera.centerLon, 0.0)
        assertFalse(entry.publicationReady)
        assertFalse(MapCameraLifecyclePolicy.canPublish(entry.publicationReady, entry.camera))
    }

    @Test
    fun permissionPromptRemovalAndReentryRestoresExactViewport() {
        val beforePrompt = MapCameraLifecyclePolicy.enter(sydney, pendingTarget = null)
        val afterPrompt = MapCameraLifecyclePolicy.enter(
            retained = MapViewportState.from(beforePrompt.camera),
            pendingTarget = null,
        )

        assertEquals(beforePrompt.camera, afterPrompt.camera)
        assertTrue(afterPrompt.publicationReady)
        assertTrue(MapCameraLifecyclePolicy.canPublish(afterPrompt.publicationReady, afterPrompt.camera))
    }

    @Test
    fun appLockRemovalAndReentryKeepsZoomAndBearing() {
        val restored = MapCameraLifecyclePolicy.enter(sydney, pendingTarget = null)

        assertEquals(sydney.zoom, restored.camera.zoom, 0.0)
        assertEquals(sydney.bearingDegrees, restored.camera.headingDegrees, 0.0)
        assertEquals(sydney.latitude, restored.camera.centerLat, 0.0)
        assertEquals(sydney.longitude, restored.camera.centerLon, 0.0)
    }

    @Test
    fun pendingTargetSuppressesPublicationUntilApplicationThenSurvivesReentry() {
        val target = Triple(-33.8688, 151.2093, 13f)
        val entering = MapCameraLifecyclePolicy.enter(sydney, target)

        assertFalse(entering.publicationReady)
        assertFalse(MapCameraLifecyclePolicy.canPublish(entering.publicationReady, entering.camera))

        val applied = MapCameraLifecyclePolicy.cameraForTarget(entering.camera, target)!!
        assertTrue(MapCameraLifecyclePolicy.canPublish(initialized = true, applied))

        val retainedAfterConsumption = MapViewportState.from(applied)
        val reentered = MapCameraLifecyclePolicy.enter(retainedAfterConsumption, pendingTarget = null)
        assertEquals(applied.centerLat, reentered.camera.centerLat, 0.0)
        assertEquals(applied.centerLon, reentered.camera.centerLon, 0.0)
        assertEquals(applied.zoom, reentered.camera.zoom, 0.0)
        assertEquals(applied.headingDegrees, reentered.camera.headingDegrees, 0.0)
        assertTrue(reentered.publicationReady)
    }

    @Test
    fun explicitOriginViewportIsNotConfusedWithConstructionSentinel() {
        val explicitOrigin = sydney.copy(latitude = 0.0, longitude = 0.0)
        val restored = MapCameraLifecyclePolicy.enter(explicitOrigin, pendingTarget = null)

        assertTrue(restored.publicationReady)
        assertTrue(MapCameraLifecyclePolicy.canPublish(restored.publicationReady, restored.camera))
    }
}
