package com.tacmap.sync

import com.tacmap.waypoints.SymbolAffiliation
import com.tacmap.waypoints.SymbolEchelon
import com.tacmap.waypoints.SymbolFunction

data class PresenceConfig(
    /** Human-readable local labels keyed by the derived opaque room ID. This
     * map is sealed at rest and deliberately absent from every wire payload. */
    val roomNamesById: Map<String, String> = emptyMap(),
    val callsign: String = "",
    val shareLocation: Boolean = false,
    val affiliation: SymbolAffiliation = SymbolAffiliation.FRIEND,
    val echelon: SymbolEchelon = SymbolEchelon.TEAM,
    val function: SymbolFunction = SymbolFunction.INFANTRY,
    val isHQ: Boolean = false
)

internal sealed interface PresenceConfigLoadDecision {
    data class UseSealed(val config: PresenceConfig) : PresenceConfigLoadDecision
    data class MigrateLegacy(val config: PresenceConfig) : PresenceConfigLoadDecision
    data object RejectInvalidSealed : PresenceConfigLoadDecision
}

/** Once a sealed record exists, an invalid copy must never fall back to stale
 * plaintext keys that could silently restore Share my location. */
internal fun resolvePresenceConfigLoad(
    sealedStored: Boolean,
    readSealed: () -> PresenceConfig?,
    readLegacy: () -> PresenceConfig,
): PresenceConfigLoadDecision {
    if (!sealedStored) return PresenceConfigLoadDecision.MigrateLegacy(readLegacy())
    return readSealed()?.let(PresenceConfigLoadDecision::UseSealed)
        ?: PresenceConfigLoadDecision.RejectInvalidSealed
}

internal const val MAX_ROOM_NAME_CODE_POINTS = 64
internal const val MAX_LOCAL_ROOM_NAMES = 64
internal const val MAX_LOCAL_ROOM_ID_LENGTH = 128

internal fun boundRoomName(value: String): String {
    val count = value.codePointCount(0, value.length)
    return if (count <= MAX_ROOM_NAME_CODE_POINTS) value
    else value.substring(0, value.offsetByCodePoints(0, MAX_ROOM_NAME_CODE_POINTS))
}

internal fun updatedRoomNamesById(
    current: Map<String, String>,
    roomId: String,
    roomName: String,
): Map<String, String> {
    if (roomId.isBlank() || roomId.length > MAX_LOCAL_ROOM_ID_LENGTH) return current
    val updated = LinkedHashMap(current)
    updated.remove(roomId)
    val bounded = boundRoomName(roomName).trim()
    if (bounded.isNotBlank()) updated[roomId] = bounded
    while (updated.size > MAX_LOCAL_ROOM_NAMES) updated.remove(updated.keys.first())
    return updated
}
