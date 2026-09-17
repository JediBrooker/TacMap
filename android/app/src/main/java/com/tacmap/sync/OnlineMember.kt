package com.tacmap.sync

/** Signature-verified v3 sessions reported live by the relay. This is
 * deliberately separate from
 * [PresencePeer], which only represents a peer that elected to share a map
 * location. Identity metadata is optional until a signed presence payload is
 * received; a hello-only member remains visible without requiring location.
 * Relay-attested liveness is not itself cryptographic proof of connectivity. */
data class OnlineMember(
    val clientId: String,
    val displayName: String,
    val callsign: String? = null,
    val affiliation: String? = null,
    val echelon: String? = null,
    val function: String? = null,
    val isHQ: Boolean? = null,
    val joinedAt: Long,
    val lastSeenAt: Long,
    val metadataUpdatedAt: Long? = null,
)

/** Pure lifecycle reducer for authenticated member sessions. SyncManager owns
 * transport/authentication and only calls this after a v3 hello or payload has
 * been verified. Keeping it pure makes non-location membership testable. */
internal class OnlineMemberTracker(
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
) {
    private data class Tracked(val sessionDomain: String, val member: OnlineMember)

    private val tracked = LinkedHashMap<String, Tracked>()

    init {
        require(staleAfterMs > 0)
    }

    fun authenticatedHello(clientId: String, sessionDomain: String, nowMs: Long): Map<String, OnlineMember> {
        if (clientId.isBlank() || sessionDomain.isBlank()) return snapshot()
        val prior = tracked[clientId]
        val member = if (prior?.sessionDomain == sessionDomain) {
            prior.member.copy(lastSeenAt = nowMs)
        } else {
            OnlineMember(
                clientId = clientId,
                displayName = anonymousMemberName(clientId),
                joinedAt = nowMs,
                lastSeenAt = nowMs,
            )
        }
        tracked[clientId] = Tracked(sessionDomain, member)
        return snapshot()
    }

    fun authenticatedActivity(clientId: String, sessionDomain: String, nowMs: Long): Map<String, OnlineMember> {
        val current = tracked[clientId]
        return if (current?.sessionDomain == sessionDomain) {
            tracked[clientId] = current.copy(member = current.member.copy(lastSeenAt = nowMs))
            snapshot()
        } else {
            authenticatedHello(clientId, sessionDomain, nowMs)
        }
    }

    fun updatePresenceMetadata(
        clientId: String,
        sessionDomain: String,
        callsign: String,
        affiliation: String,
        echelon: String,
        function: String,
        isHQ: Boolean,
        nowMs: Long,
    ): Map<String, OnlineMember> {
        val current = tracked[clientId]?.takeIf { it.sessionDomain == sessionDomain }
            ?: return snapshot()
        val safeCallsign = safeDisplayText(callsign, MAX_CALLSIGN_CODE_POINTS)
        val member = current.member.copy(
            displayName = safeCallsign ?: anonymousMemberName(clientId),
            callsign = safeCallsign,
            affiliation = safeDisplayText(affiliation, MAX_METADATA_CODE_POINTS),
            echelon = safeDisplayText(echelon, MAX_METADATA_CODE_POINTS),
            function = safeDisplayText(function, MAX_METADATA_CODE_POINTS),
            isHQ = isHQ,
            lastSeenAt = nowMs,
            metadataUpdatedAt = nowMs,
        )
        tracked[clientId] = current.copy(member = member)
        return snapshot()
    }

    fun remove(clientId: String): Map<String, OnlineMember> {
        tracked.remove(clientId)
        return snapshot()
    }

    fun remove(clientId: String, sessionDomain: String): Map<String, OnlineMember> {
        if (tracked[clientId]?.sessionDomain == sessionDomain) tracked.remove(clientId)
        return snapshot()
    }

    /** Location-derived display metadata may age out, but an authenticated
     * active hello/session remains a member until relay leave or transport
     * teardown. Expiring the member itself would make non-sharing clients
     * disappear merely because they chose not to publish a location. */
    fun expireStaleMetadata(nowMs: Long): Map<String, OnlineMember> {
        tracked.replaceAll { clientId, value ->
            val updatedAt = value.member.metadataUpdatedAt
            if (updatedAt != null && nowMs >= updatedAt && nowMs - updatedAt > staleAfterMs) {
                value.copy(
                    member = value.member.copy(
                        displayName = anonymousMemberName(clientId),
                        callsign = null,
                        affiliation = null,
                        echelon = null,
                        function = null,
                        isHQ = null,
                        metadataUpdatedAt = null,
                    )
                )
            } else {
                value
            }
        }
        return snapshot()
    }

    fun clear(): Map<String, OnlineMember> {
        tracked.clear()
        return emptyMap()
    }

    fun snapshot(): Map<String, OnlineMember> = tracked.mapValues { it.value.member }

    companion object {
        // Signed location metadata is not proof that the member is still
        // sharing it forever. Membership itself is session/relay driven.
        const val DEFAULT_STALE_AFTER_MS = 5 * 60 * 1000L
        private const val MAX_CALLSIGN_CODE_POINTS = 64
        private const val MAX_METADATA_CODE_POINTS = 64

        private fun anonymousMemberName(clientId: String): String {
            val suffix = clientId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                .takeLast(8)
                .ifBlank { "unknown" }
            return "Member $suffix"
        }

        private fun safeDisplayText(value: String, maxCodePoints: Int): String? {
            val cleaned = value.filterNot {
                it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
            }.trim()
            if (cleaned.isBlank()) return null
            val count = cleaned.codePointCount(0, cleaned.length)
            return if (count <= maxCodePoints) cleaned
            else cleaned.substring(0, cleaned.offsetByCodePoints(0, maxCodePoints))
        }
    }
}
