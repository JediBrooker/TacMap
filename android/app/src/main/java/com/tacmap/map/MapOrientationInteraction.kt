package com.tacmap.map

import com.tacmap.settings.MapOrientationMode
import kotlin.math.abs

internal enum class CompassTapAction {
    RESET_NORTH,
    ENABLE_HEADING_UP,
    DISABLE_HEADING_UP,
    HEADING_UNAVAILABLE,
}

internal fun normalizedHeadingDegrees(degrees: Double): Double {
    if (!degrees.isFinite()) return 0.0
    val wrapped = degrees % 360.0
    return if (wrapped < 0) wrapped + 360.0 else wrapped
}

/** Smallest signed turn from [start] to [end], in [-180, 180). */
internal fun shortestHeadingDelta(start: Double, end: Double): Double {
    var delta = normalizedHeadingDegrees(end) - normalizedHeadingDegrees(start)
    if (delta >= 180.0) delta -= 360.0
    if (delta < -180.0) delta += 360.0
    return delta
}

/** Circular low-pass filter that crosses 359/0 by the short path. */
internal fun smoothedHeadingDegrees(
    previous: Double?,
    measured: Double,
    factor: Double = 0.25,
): Double {
    val normalized = normalizedHeadingDegrees(measured)
    if (previous == null || !previous.isFinite()) return normalized
    val clampedFactor = factor.coerceIn(0.0, 1.0)
    return normalizedHeadingDegrees(
        previous + shortestHeadingDelta(previous, normalized) * clampedFactor
    )
}

internal fun compassTapAction(
    mode: MapOrientationMode,
    currentHeading: Double,
    headingAvailable: Boolean,
    northToleranceDegrees: Double = 1.0,
): CompassTapAction {
    if (mode == MapOrientationMode.HEADING_UP) return CompassTapAction.DISABLE_HEADING_UP
    if (abs(shortestHeadingDelta(currentHeading, 0.0)) > northToleranceDegrees) {
        return CompassTapAction.RESET_NORTH
    }
    return if (headingAvailable) {
        CompassTapAction.ENABLE_HEADING_UP
    } else {
        CompassTapAction.HEADING_UNAVAILABLE
    }
}
