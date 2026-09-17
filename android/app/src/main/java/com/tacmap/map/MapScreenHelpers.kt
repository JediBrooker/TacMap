package com.tacmap.map

import com.tacmap.localization.L10n

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.tacmap.calibration.AffineFitter
import com.tacmap.calibration.GeoPdfParser
import com.tacmap.calibration.OfflineTileMapSourceAndroid
import com.tacmap.calibration.MBTilesStore
import com.tacmap.calibration.PdfMapSource
import com.tacmap.calibration.PdfPageRenderer
import com.tacmap.calibration.PdfSessionStore
import com.tacmap.calibration.Wgs84Coordinate
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingPoint
import com.tacmap.drawings.DrawingStrokeStyle
import com.tacmap.export.EXPORT_ARTIFACT_RETENTION_MS
import com.tacmap.export.ExportArtifact
import com.tacmap.export.ExportArtifactWorkspace
import com.tacmap.export.ExportPipelineDriver
import com.tacmap.export.GeoJsonExporter
import com.tacmap.export.MissionObjectExport
import com.tacmap.export.executeExportPipeline
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import android.os.Handler
import android.os.Looper

// Non-composable helpers extracted from MapScreen.kt: angle normalisation,
// drawing defaults/naming, PDF import + georeferencing, GeoJSON sharing.
// Widened to internal so MapScreen.kt can still reach them.

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
    /** Portable width shared with iOS and interchange formats. */
    const val STROKE_WIDTH_DP: Float = 3f

    fun rendererStrokeWidth(density: Float): Float =
        STROKE_WIDTH_DP * density.coerceAtLeast(0f)
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

/**
 * Single production creation path for map-authored drawings. Android's
 * renderer stores pixels, while the cross-platform authoring contract is 3dp.
 * Importers have their own wire conversion path; every locally created draft
 * and committed feature goes through this factory.
 */
internal fun newMapDrawingFeature(
    name: String,
    geometry: DrawingGeometry,
    points: List<DrawingPoint>,
    layerId: String,
    strokeColor: Int,
    fillColor: Int,
    strokeStyle: DrawingStrokeStyle,
    density: Float,
): DrawingFeature = DrawingFeature(
    name = name,
    geometry = geometry,
    points = points,
    layerId = layerId,
    strokeColor = strokeColor,
    fillColor = fillColor,
    strokeWidth = DrawingDefaults.rendererStrokeWidth(density),
    strokeStyle = strokeStyle,
)

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
        DrawingGeometry.POINT -> L10n.text("Point %1\$s", next)
        DrawingGeometry.LINE -> L10n.text("Line %1\$s", next)
        DrawingGeometry.POLYGON -> L10n.text("Area %1\$s", next)
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

internal suspend fun shareGeoJson(
    context: Context,
    waypoints: List<com.tacmap.waypoints.Waypoint>,
    drawings: List<DrawingFeature>,
    layers: List<com.tacmap.drawings.DrawingLayer>
) {
    shareTextExport(
        context = context,
        exportLabel = "GeoJSON",
        fileName = "TacMap.geojson",
        mimeType = "application/geo+json",
        chooserTitle = L10n.text("Export GeoJSON"),
    ) {
        GeoJsonExporter.export(
            waypoints,
            drawings,
            layers,
            density = context.resources.displayMetrics.density,
        )
    }
}

internal suspend fun shareGpx(
    context: Context,
    points: List<com.tacmap.models.TrackPoint>
) {
    if (points.isEmpty()) {
        Toast.makeText(context, L10n.text("No track recorded yet."), Toast.LENGTH_SHORT).show()
        return
    }
    shareTextExport(
        context = context,
        exportLabel = L10n.text("GPX track"),
        fileName = "TacMap-track.gpx",
        mimeType = "application/gpx+xml",
        chooserTitle = L10n.text("Export GPX"),
    ) { com.tacmap.export.GpxExporter.export(points) }
}

/** Export all mission objects + their layer metadata as GeoJSON. Tracks stay in GPX. */
internal suspend fun exportAllMissionObjects(
    context: Context,
    waypoints: List<com.tacmap.waypoints.Waypoint>,
    drawings: List<DrawingFeature>,
    layers: List<com.tacmap.drawings.DrawingLayer>
) {
    if (!MissionObjectExport.hasExportableContent(waypoints, drawings, layers)) {
        Toast.makeText(context, L10n.text("Nothing to export."), Toast.LENGTH_SHORT).show()
        return
    }
    shareTextExport(
        context = context,
        exportLabel = L10n.text("mission-object GeoJSON"),
        fileName = MissionObjectExport.FILE_NAME,
        mimeType = "application/geo+json",
        chooserTitle = MissionObjectExport.SHARE_TITLE,
    ) {
        MissionObjectExport.geoJson(
            waypoints = waypoints,
            drawings = drawings,
            layers = layers,
            density = context.resources.displayMetrics.density,
        )
    }
}

internal fun cleanupExportArtifacts(context: Context) {
    ExportArtifactWorkspace(File(context.cacheDir, "exports")).cleanupStaleArtifacts()
}

private suspend fun shareTextExport(
    context: Context,
    exportLabel: String,
    fileName: String,
    mimeType: String,
    chooserTitle: String,
    generate: () -> String,
) {
    val outcome = executeExportPipeline(
        exportLabel = exportLabel,
        driver = AndroidTextExportDriver(
            context = context,
            fileName = fileName,
            mimeType = mimeType,
            chooserTitle = chooserTitle,
            generate = generate,
        ),
    )
    if (!outcome.succeeded) {
        Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
    }
}

private class AndroidTextExportDriver(
    private val context: Context,
    private val fileName: String,
    private val mimeType: String,
    private val chooserTitle: String,
    private val generate: () -> String,
) : ExportPipelineDriver<Uri> {
    private val appContext = context.applicationContext
    private val workspace = ExportArtifactWorkspace(File(appContext.cacheDir, "exports"))
    private var grantedUri: Uri? = null

    override fun cleanupStaleArtifacts() = workspace.cleanupStaleArtifacts()

    override fun generateContent(): String = generate()

    override fun prepareArtifact(): ExportArtifact = workspace.prepareArtifact(fileName)

    override fun writeArtifact(artifact: ExportArtifact, content: String) {
        FileOutputStream(artifact.partialFile).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                artifact.partialFile.toPath(),
                artifact.finalFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                artifact.partialFile.toPath(),
                artifact.finalFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    override fun createShareToken(artifact: ExportArtifact): Uri =
        FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            artifact.finalFile,
        ).also { grantedUri = it }

    override fun scheduleCleanup(artifact: ExportArtifact) {
        // Only immutable locals and applicationContext cross the delay; never
        // retain the Activity for the 15-minute cleanup window.
        val cleanupContext = appContext
        val cleanupWorkspace = workspace
        val generationArtifact = artifact
        val uriToRevoke = grantedUri
        check(
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { cleanupWorkspace.cleanupArtifact(generationArtifact) }
                uriToRevoke?.let { uri ->
                    runCatching {
                        cleanupContext.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            }, EXPORT_ARTIFACT_RETENTION_MS)
        ) { "Could not schedule temporary-file cleanup" }
    }

    override fun launchShare(artifact: ExportArtifact, token: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_SUBJECT, chooserTitle)
            putExtra(Intent.EXTRA_TITLE, chooserTitle)
            putExtra(Intent.EXTRA_STREAM, token)
            clipData = ClipData.newUri(context.contentResolver, artifact.finalFile.name, token)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }

    override fun cleanupFailedArtifact(artifact: ExportArtifact?) {
        artifact?.let(workspace::cleanupArtifact)
        grantedUri?.let { uri ->
            appContext.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

internal class PdfImportRejectedException(message: String) : Exception(message)

internal fun pdfRotationRejectionMessage(rotationDegrees: Int): String =
    L10n.text("This PDF's first page is rotated %1\$s°. TacMap cannot safely ", rotationDegrees) +
        L10n.text("georeference rotated pages yet. Flatten the page rotation in a PDF editor or ") +
        L10n.text("print it to a new PDF, then import that copy.")

internal fun pdfImportUserMessage(failure: Throwable): String = when {
    failure is PdfImportRejectedException -> failure.message ?: L10n.text("Unable to import PDF map.")
    generateSequence(failure as Throwable?) { it.cause }
        .any { it.message == L10n.text("Import exceeds the supported size limit") } ->
        L10n.text("This PDF is larger than TacMap's 256 MB import limit.")
    else -> L10n.text("TacMap could not read the first page. The PDF may be invalid or password-protected.")
}

internal fun preflightPdfImport(context: Context, file: File): com.tacmap.calibration.PdfPageInfo {
    val fileUri = Uri.fromFile(file)
    val pageInfo = try {
        PdfPageRenderer.firstPageInfo(context.applicationContext, fileUri)
    } catch (_: Exception) {
        throw PdfImportRejectedException(
            L10n.text("TacMap could not read the first page. The PDF may be invalid or password-protected.")
        )
    }
    val rotation = GeoPdfParser.pageRotation(context.applicationContext, fileUri)
        ?: throw PdfImportRejectedException(
            L10n.text("TacMap could not safely inspect this PDF's page rotation. Flatten or print it to a new PDF, then import that copy.")
        )
    if (rotation != 0) throw PdfImportRejectedException(pdfRotationRejectionMessage(rotation))
    return pageInfo
}

internal fun importPdfMapSource(
    context: Context,
    sourceUri: Uri,
    cameraLat: Double,
    cameraLng: Double,
    operationKey: String,
    copyJournal: DocumentImportCopyStateStore,
): PdfMapSource {
    val appContext = context.applicationContext
    val displayName = context.displayNameFor(sourceUri)
    val pdfDir = File(appContext.filesDir, "pdf_maps")
    val dest = IdempotentDocumentCopy(
        destinationDir = pdfDir,
        extension = "pdf",
        maxBytes = MAX_PDF_IMPORT_BYTES,
        stateStore = copyJournal,
        openSource = {
            requireNotNull(appContext.contentResolver.openInputStream(sourceUri)) {
                L10n.text("Unable to open selected PDF")
            }
        },
        validate = { file ->
            preflightPdfImport(appContext, file)
            true
        },
    ).execute(operationKey)

    val fileUri = Uri.fromFile(dest)
    val pageInfo = preflightPdfImport(appContext, dest)
    val baseName = displayName.removeSuffix(".pdf").removeSuffix(".PDF")
    val base = PdfMapSource.imported(
        uri = fileUri,
        name = baseName,
        center = Wgs84Coordinate(cameraLat, cameraLng),
        pageInfo = pageInfo
    )

    // Manual calibration (user-dropped fiduciaries with real MGRS strings)
    // wins over auto-parsing. Auto-parsed calibrations are NOT honored here
    // b/c they're reproducible from the PDF, so short-circuiting the re-parse
    // would pin a stale result that a parser fix can never correct on re-import.
    // That's exactly what stranded the sheet at wrong longitude after the
    // GeoPDF viewport fix. Auto correspondences leave MGRS blank, manual
    // ones don't - thats how we tell them apart.
    PdfSessionStore(appContext).calibration(dest)
        ?.takeIf { saved -> saved.fids.any { it.mgrs.isNotBlank() } }
        ?.let { saved -> return base.calibrated(saved.transform, saved.fids) }

    /// Try to pull georeferencing straight from the PDF (OGC GeoPDF /
    /// Adobe LGIDict). If we get >=3 correspondences, fit an affine
    /// and return a calibrated source - PDF lands in the right spot
    /// with correct rotation+scale, no user calibration needed. If
    /// no georef found, leave it uncalibrated and user can drop
    /// fiduciaries manually.
    val geo = GeoPdfParser.parse(appContext, fileUri) ?: return base
    val fiducials = geo.correspondences.map { it.toFiduciary() }
    val fit = runCatching { AffineFitter.fit(fiducials) }.getOrNull() ?: return base
    Log.i("GeoPdfImport", "auto-parsed ${fiducials.size} correspondences")
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

/** Copy picked .mbtiles into files dir (SQLite needs a real path, not a
 *  content Uri) and open as an offline-tile basemap. */
internal fun importMBTilesMapSource(
    context: Context,
    sourceUri: Uri,
    operationKey: String,
    copyJournal: DocumentImportCopyStateStore,
): OfflineTileMapSourceAndroid? {
    val appContext = context.applicationContext
    val dir = File(appContext.filesDir, "mbtiles")
    val dest = IdempotentDocumentCopy(
        destinationDir = dir,
        extension = "mbtiles",
        maxBytes = MAX_MBTILES_IMPORT_BYTES,
        stateStore = copyJournal,
        openSource = {
            requireNotNull(appContext.contentResolver.openInputStream(sourceUri)) {
                L10n.text("Unable to open selected MBTiles")
            }
        },
        validate = { file ->
            MBTilesStore.open(file.path)?.let { store ->
                store.close()
                true
            } ?: false
        },
    ).execute(operationKey)
    return OfflineTileMapSourceAndroid.open(dest.path)
}

private const val MAX_PDF_IMPORT_BYTES = 256L * 1024 * 1024
private const val MAX_MBTILES_IMPORT_BYTES = 4L * 1024 * 1024 * 1024
