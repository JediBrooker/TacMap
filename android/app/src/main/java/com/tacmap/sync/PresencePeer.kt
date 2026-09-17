package com.tacmap.sync

import com.tacmap.localization.L10n

data class PresencePeer(
    val clientId: String,
    val callsign: String,
    val affiliation: String,
    val echelon: String,
    val function: String,
    val isHQ: Boolean,
    val lat: Double,
    val lon: Double,
    val heading: Double,
    val speed: Double,
    val ts: Long,
    val receivedAt: Long = System.currentTimeMillis(),
    /** v3 signed session domain; null for legacy v2 location presence. */
    val sessionDomain: String? = null,
    /** Sender-signed last-known lifetime; legacy/foreground senders use 45s. */
    val retentionWindowMs: Long = PresenceExpiryPolicy.LIVE_UPDATE_WINDOW_MS,
    /** Signed horizontal accuracy when advertised by an updated v3 sender. */
    val horizontalAccuracyMetres: Double? = null,
    /** Process-local monotonic receipt time; wall-clock changes cannot extend expiry. */
    val receivedAtUptimeMs: Long = System.nanoTime() / 1_000_000L,
    /** Monotonic instant when the coordinate aged beyond the live-update window. */
    val staleSinceUptimeMs: Long? = null,
    /** Start of the current transport-loss/session-rotation grace. Kept
     * separate from fix age so repeated retry failures cannot extend it. */
    val reconnectGraceStartedAtUptimeMs: Long? = null,
) {
    val isStale: Boolean get() =
        staleSinceUptimeMs != null || reconnectGraceStartedAtUptimeMs != null
}

internal data class PresenceMarkerPresentation(
    val visibleLabel: String,
    val accessibilityLabel: String,
)

/** Explicit map truth for stale coordinates. Opacity remains a secondary cue;
 * both sighted and assistive-tech users receive Last known semantics and age. */
internal fun presenceMarkerPresentation(
    peer: PresencePeer,
    nowUptimeMs: Long,
): PresenceMarkerPresentation {
    val unitName = peer.callsign.ifBlank { L10n.text("Unit") }
    if (!peer.isStale) return PresenceMarkerPresentation(peer.callsign, unitName)
    val ageMs = (nowUptimeMs - peer.receivedAtUptimeMs).coerceAtLeast(0L)
    val shortAge: String
    val spokenAge: String
    when {
        ageMs < 60_000L -> {
            shortAge = "<1m"
            spokenAge = L10n.text("less than 1 minute ago")
        }
        ageMs < 60L * 60L * 1_000L -> {
            val minutes = ageMs / 60_000L
            shortAge = "${minutes}m"
            spokenAge = L10n.quantity("minute_ago", minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        else -> {
            val hours = ageMs / (60L * 60L * 1_000L)
            shortAge = "${hours}h"
            spokenAge = L10n.quantity("hour_ago", hours.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }
    val visible = if (peer.callsign.isBlank()) {
        L10n.text("Last known · %1\$s", shortAge)
    } else {
        L10n.text("%1\$s · Last known %2\$s", peer.callsign, shortAge)
    }
    return PresenceMarkerPresentation(
        visibleLabel = visible,
        accessibilityLabel = L10n.text("%1\$s. Last known %2\$s.", unitName, spokenAge),
    )
}

/** Keeps foreground/legacy positions short-lived while allowing a bounded,
 * sender-signed v3 lifetime to bridge TacMap's iOS background cadence only
 * when the exact authenticated relay session is still active. */
internal object PresenceExpiryPolicy {
    const val LIVE_UPDATE_WINDOW_MS = 45_000L
    const val ACTIVE_SESSION_LAST_KNOWN_WINDOW_MS = 65L * 60L * 1_000L
    const val RECONNECT_LAST_KNOWN_WINDOW_MS = 2L * 60L * 1_000L

    fun markedStale(peer: PresencePeer, nowUptimeMs: Long): PresencePeer =
        if (peer.reconnectGraceStartedAtUptimeMs != null) peer else {
            peer.copy(reconnectGraceStartedAtUptimeMs = nowUptimeMs)
        }

    /** A verified hello for the same signed session restores transport
     * liveness without making an old coordinate look fresh. */
    fun transportRestored(peer: PresencePeer, nowUptimeMs: Long): PresencePeer =
        withFreshness(peer.copy(reconnectGraceStartedAtUptimeMs = null), nowUptimeMs)

    fun withFreshness(peer: PresencePeer, nowUptimeMs: Long): PresencePeer {
        if (peer.staleSinceUptimeMs != null || nowUptimeMs < peer.receivedAtUptimeMs) return peer
        return if (nowUptimeMs - peer.receivedAtUptimeMs > LIVE_UPDATE_WINDOW_MS) {
            peer.copy(staleSinceUptimeMs = peer.receivedAtUptimeMs + LIVE_UPDATE_WINDOW_MS)
        } else peer
    }

    fun shouldRetain(
        peer: PresencePeer,
        activeSessionDomain: String?,
        nowUptimeMs: Long = System.nanoTime() / 1_000_000L,
    ): Boolean {
        if (nowUptimeMs < peer.receivedAtUptimeMs) return false
        val ageMs = nowUptimeMs - peer.receivedAtUptimeMs
        if (ageMs <= LIVE_UPDATE_WINDOW_MS) return true
        val reconnectGrace = peer.reconnectGraceStartedAtUptimeMs?.let { graceStarted ->
            nowUptimeMs >= graceStarted &&
                nowUptimeMs - graceStarted <= RECONNECT_LAST_KNOWN_WINDOW_MS
        } == true
        val requestedWindow = peer.retentionWindowMs.coerceAtMost(
            ACTIVE_SESSION_LAST_KNOWN_WINDOW_MS
        )
        val activeRetention = requestedWindow > LIVE_UPDATE_WINDOW_MS &&
            ageMs <= requestedWindow &&
            peer.sessionDomain != null &&
            peer.sessionDomain == activeSessionDomain
        return reconnectGrace || activeRetention
    }
}
