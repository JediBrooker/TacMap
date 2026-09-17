package com.tacmap.map

import com.tacmap.localization.L10n

import com.tacmap.waypoints.Waypoint

internal val SYMBOL_CREATION_ERROR =
    L10n.text("The symbol could not be saved. Check available storage, then try again.")

internal sealed interface DurableSymbolCreation {
    data class Saved(val waypoint: Waypoint) : DurableSymbolCreation
    data class Failed(val waypoint: Waypoint, val message: String) : DurableSymbolCreation
}

/** Production decision seam shared by every local symbol-creation surface.
 * Callers may dismiss, select, or announce success only for [Saved]. */
internal fun persistNewSymbol(
    waypoint: Waypoint,
    persist: (Waypoint) -> Boolean,
): DurableSymbolCreation = if (persist(waypoint)) {
    DurableSymbolCreation.Saved(waypoint)
} else {
    DurableSymbolCreation.Failed(waypoint, SYMBOL_CREATION_ERROR)
}
