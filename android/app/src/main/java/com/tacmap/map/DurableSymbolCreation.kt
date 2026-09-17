package com.tacmap.map

import com.tacmap.localization.L10n
import com.tacmap.localization.Messages
import com.tacmap.localization.LocalizedMessage

import com.tacmap.waypoints.Waypoint

internal val SYMBOL_CREATION_MESSAGE: LocalizedMessage get() =
    Messages.displayTheSymbolCouldNotBeSavedCheckAvailableStorageMessage()

internal sealed interface DurableSymbolCreation {
    data class Saved(val waypoint: Waypoint) : DurableSymbolCreation
    data class Failed(val waypoint: Waypoint, val pendingMessage: LocalizedMessage) : DurableSymbolCreation {
        val message: String get() = pendingMessage.text
    }
}

/** Production decision seam shared by every local symbol-creation surface.
 * Callers may dismiss, select, or announce success only for [Saved]. */
internal fun persistNewSymbol(
    waypoint: Waypoint,
    persist: (Waypoint) -> Boolean,
): DurableSymbolCreation = if (persist(waypoint)) {
    DurableSymbolCreation.Saved(waypoint)
} else {
    DurableSymbolCreation.Failed(waypoint, SYMBOL_CREATION_MESSAGE)
}

internal val SYMBOL_CREATION_ERROR: String get() = SYMBOL_CREATION_MESSAGE.text
