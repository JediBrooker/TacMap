package com.tacmap.map

import com.tacmap.localization.DisplayFormat

import com.tacmap.localization.Messages

import com.tacmap.localization.LocalizedMessage
import com.tacmap.localization.displayMessage
import com.tacmap.export.importSummaryMessage
import com.tacmap.localization.L10n

import com.tacmap.drawings.DrawingLayer
import com.tacmap.export.GeoJsonImporter
import java.io.InputStream
import java.io.ByteArrayOutputStream

/** User-facing outcome for the ACTION_OPEN_DOCUMENT GeoJSON path. Keeping the
 * result handling outside Compose makes the production picker path regression
 * testable without replacing Android's ContentResolver. */
internal data class GeoJsonImportFeedback(
    val succeeded: Boolean,
    val pendingMessage: LocalizedMessage,
) { val message: String get() = pendingMessage.text }

internal fun parseGeoJsonDocument(
    input: InputStream?,
    existingLayers: List<DrawingLayer>,
    fallbackLayerId: String,
    density: Float = 1f,
): kotlin.Result<GeoJsonImporter.Result> = runCatching {
    val readable = input ?: throw IllegalStateException(L10n.text("Couldn't read the selected file"))
    readable.use {
        GeoJsonImporter.parseStream(
            input = it,
            existingLayers = existingLayers,
            fallbackLayerId = fallbackLayerId,
            density = density,
        )
    }
}

internal fun applyGeoJsonImportResult(
    result: kotlin.Result<GeoJsonImporter.Result>,
    apply: (GeoJsonImporter.Result) -> Unit,
): GeoJsonImportFeedback = result.fold(
    onSuccess = { parsed ->
        runCatching { apply(parsed) }.fold(
            onSuccess = {
                GeoJsonImportFeedback(
                    succeeded = true,
                    pendingMessage = importSummaryMessage(parsed.waypoints.size, parsed.drawings.size, parsed.invalidSkipped),
                )
            },
            onFailure = { failure ->
                GeoJsonImportFeedback(false, Messages.importFailedMessage("").withArgument(0, failure.readableMessage()))
            },
        )
    },
    onFailure = { failure ->
        GeoJsonImportFeedback(false, Messages.importFailedMessage("").withArgument(0, failure.readableMessage()))
    },
)

private fun Throwable.readableMessage(): LocalizedMessage =
    if (message.isNullOrBlank()) Messages.importUnknownFailureMessage() else displayMessage

internal fun readBoundedExternalImport(input: InputStream?): ByteArray {
    val readable = input ?: throw IllegalStateException(L10n.text("Couldn't read the selected file"))
    return readable.use { stream ->
        val output = ByteArrayOutputStream(64 * 1024)
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_EXTERNAL_IMPORT_BYTES) { L10n.text("Import exceeds 8 MiB") }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}

private const val MAX_EXTERNAL_IMPORT_BYTES = 8 * 1024 * 1024
