package com.tacmap.export

import com.tacmap.localization.DisplayFormat

import com.tacmap.localization.Messages

import com.tacmap.localization.LocalizedMessage
import com.tacmap.localization.L10n

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingLayer
import com.tacmap.waypoints.Waypoint

internal data class ExternalImportCommitResult(
    val succeeded: Boolean,
    val partialCommit: Boolean,
    val pendingMessage: LocalizedMessage,
) { val message: String get() = pendingMessage.text }

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
            pendingMessage = Messages.importWaypointsSaveFailedMessage(),
        )
    }
    val drawingCommitted = commitDrawings(imported.newLayers, imported.drawings)
    if (!drawingCommitted.succeeded) {
        return ExternalImportCommitResult(
            succeeded = false,
            partialCommit = waypointCommitted.insertedCount > 0,
            pendingMessage = if (waypointCommitted.insertedCount > 0) {
                Messages.importDrawingsPartiallySavedMessage()
            } else {
                Messages.importDrawingsSaveFailedMessage()
            },
        )
    }
    return ExternalImportCommitResult(
        succeeded = true,
        partialCommit = false,
        pendingMessage = importSummaryMessage(waypointCommitted.insertedCount, drawingCommitted.insertedCount, imported.invalidSkipped),
    )
}

/** Display-only summary; re-resolving it never repeats either durable commit. */
internal fun importSummaryMessage(waypoints: Int, drawings: Int, invalid: Int): LocalizedMessage {
    val summary = Messages.importCompleteSummaryMessage("", "")
        .withArgument(0, Messages.waypointCountMessage(waypoints))
        .withArgument(1, Messages.drawingCountMessage(drawings))
    return if (invalid > 0) Messages.importInvalidSummaryMessage("", DisplayFormat.number(invalid.toDouble(), 0))
        .withArgument(0, summary) else summary
}
