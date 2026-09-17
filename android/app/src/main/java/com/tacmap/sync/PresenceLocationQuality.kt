package com.tacmap.sync

import android.location.Location
import android.os.Build
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class PresenceLocationFix(
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMetres: Double,
    val monotonicTimeMs: Long,
)

internal data class PresenceCandidateCluster(
    val latest: PresenceLocationFix,
    val consistentFixCount: Int,
)

internal data class PresenceJumpEvaluation(
    val accepted: Boolean,
    val nextCluster: PresenceCandidateCluster?,
    val recoveredFromOutlier: Boolean = false,
)

/** Cross-platform presence quality constants and accuracy-aware jump policy. */
internal object PresenceLocationQuality {
    const val MAX_HORIZONTAL_ACCURACY_METRES = 1_000.0
    const val MAX_REPORTED_SPEED_METRES_PER_SECOND = 1_000.0
    const val MAX_PLAUSIBLE_TRAVEL_SPEED_METRES_PER_SECOND = 400.0
    const val BASE_JUMP_ALLOWANCE_METRES = 200.0
    const val ACCURACY_ALLOWANCE_MULTIPLIER = 3.0
    const val MAX_LEGACY_FUTURE_SKEW_MS = 30_000L

    fun isUsableAccuracy(horizontalAccuracyMetres: Double): Boolean =
        horizontalAccuracyMetres.isFinite() &&
            horizontalAccuracyMetres > 0.0 &&
            horizontalAccuracyMetres <= MAX_HORIZONTAL_ACCURACY_METRES

    fun hasValidWireValues(
        latitude: Double,
        longitude: Double,
        heading: Double,
        speed: Double,
    ): Boolean = latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0 &&
        heading.isFinite() && heading >= 0.0 && heading < 360.0 &&
        speed.isFinite() && speed >= 0.0 && speed <= MAX_REPORTED_SPEED_METRES_PER_SECOND

    fun isPlausibleLegacyTimestamp(timestampMs: Long, nowMs: Long): Boolean {
        if (timestampMs < 0L || nowMs < 0L) return false
        val latest = if (nowMs > Long.MAX_VALUE - MAX_LEGACY_FUTURE_SKEW_MS) {
            Long.MAX_VALUE
        } else nowMs + MAX_LEGACY_FUTURE_SKEW_MS
        return timestampMs <= latest
    }

    fun acceptsJump(
        previous: PresenceLocationFix?,
        candidate: PresenceLocationFix,
        allowSimulatorTeleport: Boolean,
    ): Boolean {
        if (!isUsableAccuracy(candidate.horizontalAccuracyMetres)) return false
        val old = previous ?: return true
        if (candidate.monotonicTimeMs < old.monotonicTimeMs) return false
        if (allowSimulatorTeleport) return true
        val elapsedSeconds = (candidate.monotonicTimeMs - old.monotonicTimeMs) / 1_000.0
        val accuracyAllowance = ACCURACY_ALLOWANCE_MULTIPLIER *
            (old.horizontalAccuracyMetres + candidate.horizontalAccuracyMetres)
        val allowedDistance = BASE_JUMP_ALLOWANCE_METRES + accuracyAllowance +
            MAX_PLAUSIBLE_TRAVEL_SPEED_METRES_PER_SECOND * elapsedSeconds
        return distanceMetres(
            old.latitude, old.longitude, candidate.latitude, candidate.longitude
        ) <= allowedDistance
    }

    /** A single plausible-but-wrong first fix must not poison the session
     * forever. Three mutually consistent usable fixes can establish a new
     * cluster while the previously accepted map marker remains untouched. */
    fun evaluateJump(
        previous: PresenceLocationFix?,
        candidate: PresenceLocationFix,
        existingCluster: PresenceCandidateCluster?,
        allowSimulatorTeleport: Boolean,
    ): PresenceJumpEvaluation {
        if (!isUsableAccuracy(candidate.horizontalAccuracyMetres)) {
            return PresenceJumpEvaluation(false, existingCluster)
        }
        if (acceptsJump(previous, candidate, allowSimulatorTeleport)) {
            return PresenceJumpEvaluation(true, null)
        }
        if (previous == null || candidate.monotonicTimeMs < previous.monotonicTimeMs) {
            return PresenceJumpEvaluation(false, existingCluster)
        }
        val next = if (existingCluster != null &&
            acceptsJump(existingCluster.latest, candidate, allowSimulatorTeleport = false)
        ) {
            PresenceCandidateCluster(candidate, existingCluster.consistentFixCount + 1)
        } else {
            PresenceCandidateCluster(candidate, 1)
        }
        return if (next.consistentFixCount >= 3) {
            PresenceJumpEvaluation(true, null, recoveredFromOutlier = true)
        } else {
            PresenceJumpEvaluation(false, next)
        }
    }

    fun fromLocation(location: Location): PresenceLocationFix? {
        if (!location.hasAccuracy()) return null
        val accuracy = location.accuracy.toDouble()
        val timeMs = location.elapsedRealtimeNanos / 1_000_000L
        if (timeMs <= 0 || !isUsableAccuracy(accuracy)) return null
        return PresenceLocationFix(location.latitude, location.longitude, accuracy, timeMs)
    }

    /**
     * Teleport tolerance is compiled/runtime-scoped to a known emulator image.
     * A provider's mock flag is deliberately insufficient, so production
     * devices cannot bypass jump rejection through Developer Options.
     */
    fun allowsSimulatorTeleport(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        return fingerprint.startsWith("generic") || fingerprint.contains("emulator") ||
            model.contains("emulator") || model.contains("android sdk built for") ||
            product.contains("sdk_gphone")
    }

    private fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLat = lat2Rad - lat1Rad
        val deltaLon = Math.toRadians(lon2 - lon1)
        val a = sin(deltaLat / 2.0) * sin(deltaLat / 2.0) +
            cos(lat1Rad) * cos(lat2Rad) * sin(deltaLon / 2.0) * sin(deltaLon / 2.0)
        return 6_371_000.0 * 2.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
