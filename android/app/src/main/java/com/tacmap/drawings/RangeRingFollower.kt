package com.tacmap.drawings

import com.tacmap.models.ModelMutationOrigin
import com.tacmap.waypoints.Waypoint

/**
 * Keeps range rings centred on their symbol. Local moves, and their undo and
 * redo, regenerate the symbol's rings in the same step without an extra undo
 * entry. Remote Unit Sync writes are skipped: the peer that moved the symbol
 * sends its own ring updates. iOS mirrors this in `RangeRingFollower.swift`.
 */
class RangeRingFollower(private val drawingStore: DrawingStore) {

    fun onWaypointsCommitted(before: List<Waypoint>, after: List<Waypoint>, origin: ModelMutationOrigin) {
        if (origin != ModelMutationOrigin.LOCAL) return
        val previous = before.associateBy { it.id }
        val moved = after.filter { waypoint ->
            val old = previous[waypoint.id] ?: return@filter false
            old.latitude != waypoint.latitude || old.longitude != waypoint.longitude
        }
        follow(moved)
    }

    /** Re-centres every anchored ring on its symbol. Drawing undo and redo
     * restore whole-document snapshots, which can hold a ring's position from
     * before its symbol last moved. */
    fun realign(waypoints: List<Waypoint>) = follow(waypoints)

    private fun follow(waypoints: List<Waypoint>) {
        if (waypoints.isEmpty()) return
        val byId = waypoints.associateBy { it.id }
        val edits = drawingStore.committedDocument.value.features.mapNotNull { feature ->
            val waypoint = feature.anchorId?.let(byId::get) ?: return@mapNotNull null
            RangeRings.followed(feature, waypoint)
        }
        if (edits.isNotEmpty()) drawingStore.updateFeaturesNoUndo(edits)
    }
}
