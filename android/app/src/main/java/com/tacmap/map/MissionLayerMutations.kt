package com.tacmap.map

import com.tacmap.drawings.DrawingDocument
import com.tacmap.drawings.DrawingStore
import com.tacmap.waypoints.WaypointStore

internal data class LayerDeleteOutcome(
    val succeeded: Boolean,
    val fallbackLayerId: String?,
)

/** Cross-store layer deletion policy shared by both Android layer surfaces.
 * Waypoints move first; if the drawing document then fails to persist, the
 * layer remains and no object is orphaned. */
internal fun deleteMissionLayer(
    layerId: String,
    waypointStore: WaypointStore,
    drawingStore: DrawingStore,
): LayerDeleteOutcome {
    val document = drawingStore.committedDocument.value
    if (layerId in DrawingDocument.DEFAULT_LAYER_IDS) return LayerDeleteOutcome(false, null)
    val fallback = document.layers.firstOrNull { it.id == DrawingDocument.DEFAULT_LAYER_ID }
        ?: document.layers.firstOrNull { it.id != layerId }
        ?: return LayerDeleteOutcome(false, null)
    if (!waypointStore.reassignLayer(layerId, fallback.id)) {
        return LayerDeleteOutcome(false, fallback.id)
    }
    return LayerDeleteOutcome(
        succeeded = drawingStore.deleteLayerReassigningFeatures(layerId, fallback.id),
        fallbackLayerId = fallback.id,
    )
}
