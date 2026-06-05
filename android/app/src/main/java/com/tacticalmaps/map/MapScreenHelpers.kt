package com.tacticalmaps.map

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.tacticalmaps.calibration.AffineFitter
import com.tacticalmaps.calibration.GeoPdfParser
import com.tacticalmaps.calibration.OfflineTileMapSourceAndroid
import com.tacticalmaps.calibration.PdfMapSource
import com.tacticalmaps.calibration.PdfPageRenderer
import com.tacticalmaps.calibration.PdfSessionStore
import com.tacticalmaps.calibration.Wgs84Coordinate
import com.tacticalmaps.drawings.DrawingFeature
import com.tacticalmaps.drawings.DrawingGeometry
import com.tacticalmaps.drawings.DrawingPoint
import com.tacticalmaps.export.GeoJsonExporter
import java.io.File

// Non-composable helpers extracted verbatim from MapScreen.kt: angle
// normalisation, drawing defaults / naming, PDF import + georeferencing, and
// GeoJSON sharing. Visibility widened private -> internal so MapScreen.kt (same
// package) can still reach them.

internal fun normalizedDegrees(degrees: Double): Double =
    ((degrees % 360.0) + 360.0) % 360.0

internal data class PendingCalibrationTap(
    val pdfX: Double,
    val pdfY: Double
)

internal fun PdfMapSource.pdfPointFor(latitude: Double, longitude: Double): PendingCalibrationTap? {
    val bounds = coverage ?: return null
    val info = pageInfo ?: return null
    val latSpan = bounds.latitudeSpan
    val lonSpan = bounds.longitudeSpan
    if (kotlin.math.abs(latSpan) < 1e-12 || kotlin.math.abs(lonSpan) < 1e-12) return null

    val yRatio = (latitude - bounds.southwest.latitude) / latSpan
    val xRatio = (longitude - bounds.southwest.longitude) / lonSpan
    if (xRatio !in -0.05..1.05 || yRatio !in -0.05..1.05) return null

    return PendingCalibrationTap(
        pdfX = xRatio.coerceIn(0.0, 1.0) * info.pageWidth,
        pdfY = yRatio.coerceIn(0.0, 1.0) * info.pageHeight
    )
}

internal object DrawingDefaults {
    val DEFAULT_COLOR: Int = 0xFFFFA000.toInt()
    const val STROKE_WIDTH: Float = 8f
    val COLORS = listOf(
        DEFAULT_COLOR,
        0xFFE53935.toInt(),
        0xFFFB8C00.toInt(),
        0xFFFDD835.toInt(),
        0xFF1E88E5.toInt(),
        0xFF00ACC1.toInt(),
        0xFF43A047.toInt(),
        0xFF3949AB.toInt(),
        0xFF8E24AA.toInt(),
        0xFFD81B60.toInt(),
        0xFF111111.toInt(),
        0xFFFFFFFF.toInt()
    )
}

internal fun Int.withAlpha(alpha: Int): Int =
    (this and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

internal val DrawingGeometry.minimumVertices: Int
    get() = when (this) {
        DrawingGeometry.POINT -> 1
        DrawingGeometry.LINE -> 2
        DrawingGeometry.POLYGON -> 3
    }

internal fun defaultDrawingName(geometry: DrawingGeometry, existing: List<DrawingFeature>): String {
    val next = existing.count { it.geometry == geometry } + 1
    return when (geometry) {
        DrawingGeometry.POINT -> "Point $next"
        DrawingGeometry.LINE -> "Line $next"
        DrawingGeometry.POLYGON -> "Area $next"
    }
}

internal fun drawingNameOrDefault(
    proposedName: String,
    geometry: DrawingGeometry,
    existing: List<DrawingFeature>
): String = proposedName.trim().ifEmpty { defaultDrawingName(geometry, existing) }

internal fun List<DrawingPoint>.dedupeTrailingPoints(): List<DrawingPoint> {
    if (size < 2) return this
    return if (this[size - 1].isSameLocation(this[size - 2])) dropLast(1) else this
}

internal fun DrawingPoint.isSameLocation(other: DrawingPoint): Boolean =
    kotlin.math.abs(latitude - other.latitude) < 0.0000001 &&
        kotlin.math.abs(longitude - other.longitude) < 0.0000001

internal fun shareGeoJson(
    context: Context,
    waypoints: List<com.tacticalmaps.waypoints.Waypoint>,
    drawings: List<DrawingFeature>,
    layers: List<com.tacticalmaps.drawings.DrawingLayer>
) {
    val geoJson = GeoJsonExporter.export(waypoints, drawings, layers)
    val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
    val exportFile = File(exportDir, "TacticalMaps-${System.currentTimeMillis()}.geojson")
    exportFile.writeText(geoJson)
    val exportUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        exportFile
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/geo+json"
        putExtra(Intent.EXTRA_SUBJECT, exportFile.name)
        putExtra(Intent.EXTRA_TITLE, exportFile.name)
        putExtra(Intent.EXTRA_STREAM, exportUri)
        clipData = ClipData.newUri(context.contentResolver, exportFile.name, exportUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Export GeoJSON"))
    }.onFailure {
        Toast.makeText(context, "No app available to export GeoJSON.", Toast.LENGTH_SHORT).show()
    }
}

internal fun importPdfMapSource(
    context: Context,
    sourceUri: Uri,
    cameraLat: Double,
    cameraLng: Double
): PdfMapSource {
    val displayName = context.displayNameFor(sourceUri)
    val pdfDir = File(context.filesDir, "pdf_maps").apply { mkdirs() }
    val dest = File(pdfDir, uniquePdfFileName(displayName))

    context.contentResolver.openInputStream(sourceUri).use { input ->
        requireNotNull(input) { "Unable to open selected PDF" }
        dest.outputStream().use { output -> input.copyTo(output) }
    }

    val fileUri = Uri.fromFile(dest)
    val pageInfo = PdfPageRenderer.firstPageInfo(context, fileUri)
    val baseName = displayName.removeSuffix(".pdf").removeSuffix(".PDF")
    val base = PdfMapSource.imported(
        uri = fileUri,
        name = baseName,
        center = Wgs84Coordinate(cameraLat, cameraLng),
        pageInfo = pageInfo
    )

    // A previously-saved MANUAL calibration (user-dropped fiduciaries, which
    // carry real MGRS strings) wins over auto-parsing. Auto-parsed calibrations
    // are deliberately NOT honored here: they're reproducible from the PDF, so
    // short-circuiting the re-parse would pin a stale result that a parser fix
    // can never correct on re-import — exactly what stranded the sheet at the
    // wrong longitude after the GeoPDF viewport fix. Auto correspondences leave
    // the MGRS field blank; manual ones don't — that's how we tell them apart.
    PdfSessionStore(context).calibration(baseName)
        ?.takeIf { saved -> saved.fids.any { it.mgrs.isNotBlank() } }
        ?.let { saved -> return base.calibrated(saved.transform, saved.fids) }

    /// Try to lift georeferencing straight out of the PDF (OGC
    /// GeoPDF / Adobe LGIDict). If we find ≥3 correspondences we
    /// fit the same affine the manual-fiduciary flow would and
    /// return a calibrated source — the PDF lands in its real
    /// geographic position with the right rotation and scale, no
    /// user calibration step required. If the PDF has no
    /// recognisable georeferencing we leave the base (uncalibrated)
    /// source in place and the user can drop fiduciaries by hand.
    val geo = GeoPdfParser.parse(context, fileUri) ?: return base
    val fiducials = geo.correspondences.map { it.toFiduciary() }
    val fit = runCatching { AffineFitter.fit(fiducials) }.getOrNull() ?: return base
    Log.i(
        "GeoPdfImport",
        "auto-parsed ${fiducials.size} correspondences; centre≈" +
            "%.4f,%.4f".format(
                fiducials.map { it.latitude }.average(),
                fiducials.map { it.longitude }.average()
            )
    )
    return base.calibrated(fit.transform, fiducials)
}

internal fun Context.displayNameFor(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) {
            return cursor.getString(idx)
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "Imported Map.pdf"
}

internal fun uniquePdfFileName(displayName: String): String {
    val base = displayName.substringBeforeLast('.', displayName)
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "Imported_Map" }
    return "${System.currentTimeMillis()}_$base.pdf"
}

/** Copy a picked .mbtiles into the app's files dir (SQLite needs a real path,
 *  not a content Uri) and open it as an offline-tile basemap source. */
internal fun importMBTilesMapSource(context: Context, sourceUri: Uri): OfflineTileMapSourceAndroid? {
    val displayName = context.displayNameFor(sourceUri)
    val dir = File(context.filesDir, "mbtiles").apply { mkdirs() }
    val base = displayName.substringBeforeLast('.', displayName)
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "Offline_Tiles" }
    val dest = File(dir, "${System.currentTimeMillis()}_$base.mbtiles")
    context.contentResolver.openInputStream(sourceUri).use { input ->
        requireNotNull(input) { "Unable to open selected MBTiles" }
        dest.outputStream().use { output -> input.copyTo(output) }
    }
    return OfflineTileMapSourceAndroid.open(dest.path)
}
