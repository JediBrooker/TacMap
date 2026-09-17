package com.tacmap.map

import com.tacmap.drawings.DrawingLayer
import com.tacmap.export.GeoJsonImporter
import java.io.InputStream
import java.io.ByteArrayOutputStream

/** User-facing outcome for the ACTION_OPEN_DOCUMENT GeoJSON path. Keeping the
 * result handling outside Compose makes the production picker path regression
 * testable without replacing Android's ContentResolver. */
internal data class GeoJsonImportFeedback(
    val succeeded: Boolean,
    val message: String,
)

internal fun parseGeoJsonDocument(
    input: InputStream?,
    existingLayers: List<DrawingLayer>,
    fallbackLayerId: String,
    density: Float = 1f,
): kotlin.Result<GeoJsonImporter.Result> = runCatching {
    val readable = input ?: throw IllegalStateException("Couldn't read the selected file")
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
                val skipped = if (parsed.invalidSkipped > 0) {
                    "; skipped ${parsed.invalidSkipped} invalid feature(s)"
                } else {
                    ""
                }
                GeoJsonImportFeedback(
                    succeeded = true,
                    message = "Imported ${parsed.waypoints.size} waypoint(s) and " +
                        "${parsed.drawings.size} drawing(s)$skipped",
                )
            },
            onFailure = { failure ->
                GeoJsonImportFeedback(false, "Import failed: ${failure.readableMessage()}")
            },
        )
    },
    onFailure = { failure ->
        GeoJsonImportFeedback(false, "Import failed: ${failure.readableMessage()}")
    },
)

private fun Throwable.readableMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "The selected file could not be imported"

internal fun readBoundedExternalImport(input: InputStream?): ByteArray {
    val readable = input ?: throw IllegalStateException("Couldn't read the selected file")
    return readable.use { stream ->
        val output = ByteArrayOutputStream(64 * 1024)
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_EXTERNAL_IMPORT_BYTES) { "Import exceeds 8 MiB" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}

private const val MAX_EXTERNAL_IMPORT_BYTES = 8 * 1024 * 1024
