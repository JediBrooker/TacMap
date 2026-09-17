package com.tacmap.export

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingLayer
import com.tacmap.waypoints.Waypoint

internal data class ExternalImportCommitResult(
    val succeeded: Boolean,
    val partialCommit: Boolean,
    val message: String,
)

internal data class ExternalImportStoreCommit(
    val succeeded: Boolean,
    val insertedCount: Int,
)

/** Makes the unavoidable two-file partial-commit boundary explicit and retry-safe. */
internal fun commitExternalImport(
    imported: GeoJsonImporter.Result,
    commitWaypoints: (List<Waypoint>) -> ExternalImportStoreCommit,
    commitDrawings: (List<DrawingLayer>, List<DrawingFeature>) -> ExternalImportStoreCommit,
): ExternalImportCommitResult {
    val waypointCommitted = commitWaypoints(imported.waypoints)
    if (!waypointCommitted.succeeded) {
        return ExternalImportCommitResult(
            succeeded = false,
            partialCommit = false,
            message = "Import could not save waypoints. Nothing else was changed; retry the same file.",
        )
    }
    val drawingCommitted = commitDrawings(imported.newLayers, imported.drawings)
    if (!drawingCommitted.succeeded) {
        return ExternalImportCommitResult(
            succeeded = false,
            partialCommit = waypointCommitted.insertedCount > 0,
            message = if (waypointCommitted.insertedCount > 0) {
                "Waypoints were saved, but drawings could not be saved. Retry the same file; existing objects will be skipped and the same IDs reused."
            } else {
                "Import could not save drawings. Retry the same file; the same IDs will be reused."
            },
        )
    }
    val skipped = if (imported.invalidSkipped > 0) {
        "; skipped ${imported.invalidSkipped} invalid feature(s)"
    } else {
        ""
    }
    return ExternalImportCommitResult(
        succeeded = true,
        partialCommit = false,
        message = "Imported ${waypointCommitted.insertedCount} waypoint(s) and " +
            "${drawingCommitted.insertedCount} drawing(s)$skipped",
    )
}
