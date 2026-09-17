package com.tacmap.map

import com.tacmap.map.render.MapCamera

/** Camera state retained by [MapViewModel] while MapScreen leaves composition. */
data class MapViewportState(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val bearingDegrees: Double,
) {
    fun isUsable(): Boolean =
        latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0 &&
            zoom.isFinite() && zoom > 0.0 &&
            bearingDegrees.isFinite()

    fun toCamera(): MapCamera = MapCamera(
        centerLat = latitude,
        centerLon = longitude,
        zoom = zoom,
        headingDegrees = normalizedHeadingDegrees(bearingDegrees),
        viewportWidth = 0.0,
        viewportHeight = 0.0,
    )

    companion object {
        fun from(camera: MapCamera): MapViewportState = MapViewportState(
            latitude = camera.centerLat,
            longitude = camera.centerLon,
            zoom = camera.zoom,
            bearingDegrees = normalizedHeadingDegrees(camera.headingDegrees),
        )
    }
}

internal data class MapCameraEntryState(
    val camera: MapCamera,
    val publicationReady: Boolean,
)

/**
 * Keeps the renderer's 0,0 construction placeholder from becoming application
 * state. A real pending target takes precedence, but is not publishable until
 * the composable has applied and consumed it.
 */
internal object MapCameraLifecyclePolicy {
    private val placeholder = MapCamera(
        centerLat = 0.0,
        centerLon = 0.0,
        zoom = 2.0,
        headingDegrees = 0.0,
        viewportWidth = 0.0,
        viewportHeight = 0.0,
    )

    fun enter(
        retained: MapViewportState?,
        pendingTarget: Triple<Double, Double, Float>?,
    ): MapCameraEntryState {
        val retainedCamera = retained?.takeIf(MapViewportState::isUsable)?.toCamera()
        val targetCamera = pendingTarget?.let {
            cameraForTarget(
                current = retainedCamera ?: placeholder,
                target = it,
            )
        }
        return when {
            targetCamera != null -> MapCameraEntryState(targetCamera, publicationReady = false)
            retainedCamera != null -> MapCameraEntryState(retainedCamera, publicationReady = true)
            else -> MapCameraEntryState(placeholder, publicationReady = false)
        }
    }

    fun cameraForTarget(
        current: MapCamera,
        target: Triple<Double, Double, Float>,
    ): MapCamera? {
        val (latitude, longitude, zoom) = target
        val candidate = MapViewportState(
            latitude = latitude,
            longitude = longitude,
            zoom = zoom.toDouble(),
            bearingDegrees = current.headingDegrees,
        )
        return candidate.takeIf(MapViewportState::isUsable)?.toCamera()?.copy(
            viewportWidth = current.viewportWidth,
            viewportHeight = current.viewportHeight,
        )
    }

    fun canPublish(initialized: Boolean, camera: MapCamera): Boolean =
        initialized && MapViewportState.from(camera).isUsable()
}
