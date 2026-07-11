package com.tacmap.map.render

import androidx.compose.ui.geometry.Offset

/**
 * A [MapCamera] wrapped for overlays. The camera projects in dp; overlays place
 * and hit-test in the device px that Compose pointer events use, so this scales
 * by [density] at the boundary. The SDK-free stand-in for the Google Maps
 * `Projection` (toScreenLocation / fromScreenLocation).
 */
class MapProjection(val camera: MapCamera, val density: Float) {

    /** WGS84 -> screen px. */
    fun toScreen(lat: Double, lon: Double): Offset {
        val p = camera.screenPoint(lat, lon)
        return Offset((p.x * density).toFloat(), (p.y * density).toFloat())
    }

    /** Screen px -> (lat, lon). Inverse of [toScreen]. */
    fun fromScreen(x: Float, y: Float): Pair<Double, Double> =
        camera.coordinate(x.toDouble() / density, y.toDouble() / density)

    val zoom: Double get() = camera.zoom
    val headingDegrees: Double get() = camera.headingDegrees
    /** Ground metres per device px at the current centre/zoom. */
    val metresPerPx: Double get() = camera.metresPerPoint / density
}
