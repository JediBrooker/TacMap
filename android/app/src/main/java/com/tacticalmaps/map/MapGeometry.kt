package com.tacticalmaps.map

import androidx.compose.ui.geometry.Offset

/**
 * Pure screen-space hit-test geometry extracted from GoogleMapScreen.kt so it
 * can be unit-tested without Compose/Maps. Mirrors the iOS MapGeometry helpers
 * (point-to-segment distance, polyline distance, point-in-polygon).
 */

internal fun pointToPolylineDistance(p: Offset, line: List<Offset>): Float {
    var best = Float.MAX_VALUE
    for (i in 0 until line.size - 1) {
        val d = pointToSegmentDistance(p, line[i], line[i + 1])
        if (d < best) best = d
    }
    return best
}

internal fun pointToSegmentDistance(p: Offset, a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    if (len2 == 0f) return kotlin.math.hypot(p.x - a.x, p.y - a.y)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
    val cx = a.x + t * dx
    val cy = a.y + t * dy
    return kotlin.math.hypot(p.x - cx, p.y - cy)
}

internal fun pointInPolygon(p: Offset, poly: List<Offset>): Boolean {
    var inside = false
    var j = poly.size - 1
    for (i in poly.indices) {
        val xi = poly[i].x; val yi = poly[i].y
        val xj = poly[j].x; val yj = poly[j].y
        val intersect = ((yi > p.y) != (yj > p.y)) &&
            (p.x < (xj - xi) * (p.y - yi) / ((yj - yi).takeIf { it != 0f } ?: 1e-9f) + xi)
        if (intersect) inside = !inside
        j = i
    }
    return inside
}
