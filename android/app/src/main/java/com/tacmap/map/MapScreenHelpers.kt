package com.tacmap.map

import com.tacmap.localization.Messages

import com.tacmap.localization.L10n

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.tacmap.calibration.Calibration
import com.tacmap.calibration.FiduciaryFitter
import com.tacmap.calibration.GeoPdfGeorefResult
import com.tacmap.calibration.GeorefOrigin
import com.tacmap.calibration.GeorefRejectReason
import com.tacmap.calibration.MapSourceKind
import com.tacmap.calibration.OfflineTileMapSourceAndroid
import com.tacmap.calibration.PdfDocumentInspector
import com.tacmap.calibration.PdfGeorefIssue
import com.tacmap.calibration.PdfSessionMigration
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
import com.tacmap.export.KmlExporter
import com.tacmap.export.KmzExporter
import com.tacmap.export.KmzSymbolImage
import com.tacmap.export.MissionObjectExport
import com.tacmap.export.executeExportPipeline
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import android.os.Handler
import android.os.Looper
import kotlin.math.roundToInt

// Non-composable helpers extracted from MapScreen.kt: angle normalisation,
// drawing defaults/naming, PDF import + georeferencing, GeoJSON sharing.
// Widened to internal so MapScreen.kt can still reach them.

internal fun normalizedDegrees(degrees: Double): Double =
    ((degrees % 360.0) + 360.0) % 360.0

internal data class PendingCalibrationTap(
    val pdfX: Double,
    val pdfY: Double
)

/**
 * Map tap -> raw PDF user space point under the finger, through georef.toPage (the
 * tile renderer's own mapping), calibrated or provisional. WP4 replaces the tap with
 * the crosshair flow.
 */
internal fun PdfMapSource.pdfPointFor(latitude: Double, longitude: Double): PendingCalibrationTap? {
    // the same georef the tiles are drawn through, so the point is exactly what's under the finger
    val p = placement?.toPage(latitude, longitude) ?: return null
    val x = p.x
    val y = p.y
    if (!x.isFinite() || !y.isFinite()) return null
    val box = geometry.visibleBox
    val marginX = box.width * 0.05
    val marginY = box.height * 0.05
    if (x !in (box.llx - marginX)..(box.urx + marginX) || y !in (box.lly - marginY)..(box.ury + marginY)) return null
    return PendingCalibrationTap(
        pdfX = x.coerceIn(box.llx, box.urx),
        pdfY = y.coerceIn(box.lly, box.ury),
    )
}

internal object DrawingDefaults {
    val DEFAULT_COLOR: Int = 0xFFFFA000.toInt()
    /** Portable width shared with iOS and interchange formats. */
    const val STROKE_WIDTH_DP: Float = 3f
    /** Stroke width slider range and step in portable points, as on iOS. */
    const val MIN_STROKE_WIDTH_DP: Float = 0.5f
    const val MAX_STROKE_WIDTH_DP: Float = 16f
    const val STROKE_WIDTH_STEP_DP: Float = 0.5f
    /** Polygon fill opacity for new drawings and the reset button (20%). */
    const val DEFAULT_FILL_ALPHA: Int = 0x33
    /** Fill opacity presets in percent, matching the iOS fill menus. */
    val FILL_OPACITY_PRESETS: List<Int> = listOf(0, 10, 20, 40, 60, 80, 100)

    fun rendererStrokeWidth(density: Float): Float =
        STROKE_WIDTH_DP * density.coerceAtLeast(0f)

    /** Android stores renderer pixels; the slider and iOS use portable points
     *  (`testdata/drawing_style.json`). */
    fun portableStrokeWidth(storedPixels: Float, density: Float): Float =
        if (density > 0f) storedPixels / density else storedPixels

    fun storedStrokeWidth(portableWidth: Float, density: Float): Float =
        portableWidth.coerceIn(MIN_STROKE_WIDTH_DP, MAX_STROKE_WIDTH_DP) * density.coerceAtLeast(0f)

    fun fillAlpha(percent: Int): Int = (percent.coerceIn(0, 100) * 255f / 100f).roundToInt()

    fun fillPercent(alpha: Int): Int = (alpha.coerceIn(0, 255) * 100f / 255f).roundToInt()

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

/** The GeoJSON export file's text; [GeoJsonExportPreviewDialog] previews it before sharing. */
internal fun geoJsonExportText(
    context: Context,
    waypoints: List<com.tacmap.waypoints.Waypoint>,
    drawings: List<DrawingFeature>,
    layers: List<com.tacmap.drawings.DrawingLayer>
): String = GeoJsonExporter.export(
    waypoints,
    drawings,
    layers,
    density = context.resources.displayMetrics.density,
)

/** Shares [content], the previewed GeoJSON export, as a file. */
internal suspend fun shareGeoJson(context: Context, content: String) {
    shareTextExport(
        context = context,
        exportLabel = GEOJSON_EXPORT_LABEL,
        fileName = "TacMap.geojson",
        mimeType = "application/geo+json",
        chooserTitle = L10n.text("Export GeoJSON"),
    ) { content }
}

/** Shares [content], the previewed GPX track export, as a file. */
internal suspend fun shareGpx(context: Context, content: String) {
    shareTextExport(
        context = context,
        exportLabel = L10n.text("GPX track"),
        fileName = "TacMap-track.gpx",
        mimeType = "application/gpx+xml",
        chooserTitle = L10n.text("Export GPX"),
    ) { content }
}

/** File-format name used in export status messages; not translated. */
internal const val GEOJSON_EXPORT_LABEL = "GeoJSON"

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

/** Export mission objects as KML, or as KMZ with each symbol's rendered image. */
internal suspend fun exportMissionKml(
    context: Context,
    waypoints: List<com.tacmap.waypoints.Waypoint>,
    drawings: List<DrawingFeature>,
    layers: List<com.tacmap.drawings.DrawingLayer>,
    withSymbols: Boolean,
) {
    if (waypoints.isEmpty() && drawings.isEmpty()) {
        Toast.makeText(context, L10n.text("Nothing to export."), Toast.LENGTH_SHORT).show()
        return
    }
    val density = context.resources.displayMetrics.density
    if (!withSymbols) {
        shareTextExport(
            context = context,
            exportLabel = "KML",
            fileName = KmzExporter.KML_FILE_NAME,
            mimeType = "application/vnd.google-earth.kml+xml",
            chooserTitle = Messages.exportKmlTitle(),
        ) { KmlExporter.export(waypoints, drawings, layers, density = density) }
        return
    }
    shareExport(
        context = context,
        exportLabel = "KMZ",
        fileName = KmzExporter.FILE_NAME,
        mimeType = "application/vnd.google-earth.kmz",
        chooserTitle = Messages.exportKmzTitle(),
        // SymbolIconFactory's bitmap cache belongs to the UI thread.
        generationDispatcher = Dispatchers.Main.immediate,
    ) {
        KmzExporter.export(waypoints, drawings, layers, density) { kmzSymbolImage(context, it) }
    }
}

/** The map's own marker image for [waypoint] as PNG, anchored where the map anchors it. */
private fun kmzSymbolImage(context: Context, waypoint: com.tacmap.waypoints.Waypoint): KmzSymbolImage? {
    val drawable = SymbolIconFactory.drawableFor(context, waypoint)
    val width = drawable.intrinsicWidth
    val height = drawable.intrinsicHeight
    if (width <= 0 || height <= 0) return null
    val bitmap = (drawable as? BitmapDrawable)?.bitmap?.takeIf { it.width == width && it.height == height }
        ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { target ->
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(target))
        }
    val png = ByteArrayOutputStream().use { out ->
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
        out.toByteArray()
    }
    val (anchorU, anchorV) = SymbolIconFactory.anchorFor(context, waypoint)
    return KmzSymbolImage(png, hotSpotX = anchorU.toDouble(), hotSpotY = 1.0 - anchorV)
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
) = shareExport(context, exportLabel, fileName, mimeType, chooserTitle) {
    generate().toByteArray(Charsets.UTF_8)
}

private suspend fun shareExport(
    context: Context,
    exportLabel: String,
    fileName: String,
    mimeType: String,
    chooserTitle: String,
    generationDispatcher: CoroutineDispatcher = Dispatchers.IO,
    generate: () -> ByteArray,
) {
    val outcome = executeExportPipeline(
        exportLabel = exportLabel,
        driver = AndroidFileExportDriver(
            context = context,
            fileName = fileName,
            mimeType = mimeType,
            chooserTitle = chooserTitle,
            generate = generate,
        ),
        generationDispatcher = generationDispatcher,
    )
    if (!outcome.succeeded) {
        Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
    }
}

private class AndroidFileExportDriver(
    private val context: Context,
    private val fileName: String,
    private val mimeType: String,
    private val chooserTitle: String,
    private val generate: () -> ByteArray,
) : ExportPipelineDriver<Uri> {
    private val appContext = context.applicationContext
    private val workspace = ExportArtifactWorkspace(File(appContext.cacheDir, "exports"))
    private var grantedUri: Uri? = null

    override fun cleanupStaleArtifacts() = workspace.cleanupStaleArtifacts()

    override fun generateContent(): ByteArray = generate()

    override fun prepareArtifact(): ExportArtifact = workspace.prepareArtifact(fileName)

    override fun writeArtifact(artifact: ExportArtifact, content: ByteArray) {
        FileOutputStream(artifact.partialFile).use { output ->
            output.write(content)
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

internal fun pdfImportUserMessage(failure: Throwable): String = when {
    failure is PdfImportRejectedException -> failure.message ?: L10n.text("Unable to import PDF map.")
    generateSequence(failure as Throwable?) { it.cause }
        .any { it.message == L10n.text("Import exceeds the supported size limit") } ->
        L10n.text("This PDF is larger than TacMap's 256 MB import limit.")
    else -> L10n.text("TacMap could not read the first page. The PDF may be invalid or password-protected.")
}

/**
 * pdfium has to open page 0 or there's nothing to show. /Rotate is fine now: the
 * georef lives in raw user space and the renderer maps it through the rotation.
 */
internal fun preflightPdfImport(context: Context, file: File): Pair<Int, Int> {
    val size = try {
        PdfPageRenderer.firstPageRendererSize(context.applicationContext, Uri.fromFile(file))
    } catch (_: Exception) {
        throw PdfImportRejectedException(
            L10n.text("TacMap could not read the first page. The PDF may be invalid or password-protected.")
        )
    }
    if (size.first <= 0 || size.second <= 0) {
        throw PdfImportRejectedException(
            L10n.text("TacMap could not read the first page. The PDF may be invalid or password-protected.")
        )
    }
    return size
}

/** what the import found, so the UI can say it out loud instead of a quiet camera box */
internal sealed class PdfImportOutcome {
    data class Georeferenced(val origin: GeorefOrigin, val datumAssumed: Boolean) : PdfImportOutcome()
    /** this exact file was calibrated before (content hash), put back from the library */
    data object RestoredCalibration : PdfImportOutcome()
    /** plain PDF: provisional placement, straight into calibration */
    data object NoGeoreference : PdfImportOutcome()
    /** declared but unusable: provisional placement, alert with the reason */
    data class Rejected(val reason: GeorefRejectReason) : PdfImportOutcome()
}

internal data class PdfImportResult(val source: PdfMapSource, val outcome: PdfImportOutcome)

internal fun importPdfMapSource(
    context: Context,
    sourceUri: Uri,
    cameraLat: Double,
    cameraLng: Double,
    operationKey: String,
    copyJournal: DocumentImportCopyStateStore,
): PdfImportResult {
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
    preflightPdfImport(appContext, dest)
    val page = try {
        PdfDocumentInspector.inspect(appContext, dest)
    } catch (_: Exception) {
        throw PdfImportRejectedException(
            L10n.text("TacMap could not read the first page. The PDF may be invalid or password-protected.")
        )
    }
    val geometry = page.geometry
    val baseName = displayName.removeSuffix(".pdf").removeSuffix(".PDF")
    val camera = Wgs84Coordinate(cameraLat, cameraLng)

    // Manual calibration (user-dropped fiduciaries with real MGRS strings)
    // wins over auto-parsing. Auto-parsed calibrations are NOT honored here
    // b/c they're reproducible from the PDF, so short-circuiting the re-parse
    // would pin a stale result that a parser fix can never correct on re-import.
    // Stored lat/lon are WGS84, so refit them in UTM on the page's raw space.
    PdfSessionStore(appContext).libraryFiduciaries(dest)
        ?.takeIf { saved -> saved.fids.any { it.mgrs.isNotBlank() } }
        ?.let { saved ->
            val fids = if (saved.rawPageSpace) saved.fids else saved.fids.mapNotNull { fid ->
                PdfSessionMigration.v1PointToRaw(fid.pdfX, fid.pdfY, geometry.rendererWidth, geometry.rendererHeight, geometry)
                    ?.let { fid.copy(pdfX = it.x, pdfY = it.y) }
            }
            val georef = FiduciaryFitter.refitStored(fids, geometry.visibleCrop())?.georeference(geometry.visibleCrop())
            if (georef != null) {
                val restored = PdfMapSource(fileUri, baseName, MapSourceKind.CALIBRATED_PDF, Calibration.Fiduciaries(fids, georef), geometry)
                if (restored.coverage != null) return PdfImportResult(restored, PdfImportOutcome.RestoredCalibration)
            }
        }

    // GeoPDF (/VP or LGIDict) straight into the plan 02 georef; anything declared
    // but unusable, or nothing at all, comes back as its own outcome
    return when (val result = page.georeference()) {
        is GeoPdfGeorefResult.Georeferenced -> {
            val source = PdfMapSource.geoPdf(fileUri, baseName, result.georef, geometry)
            if (source.coverage != null) {
                Log.i("GeoPdfImport", "georeferenced via ${result.georef.origin.code}")
                PdfImportResult(source, PdfImportOutcome.Georeferenced(result.georef.origin, result.georef.datumAssumed))
            } else {
                PdfImportResult(
                    PdfMapSource.uncalibrated(fileUri, baseName, geometry, camera, PdfGeorefIssue.Rejected(GeorefRejectReason.MALFORMED)),
                    PdfImportOutcome.Rejected(GeorefRejectReason.MALFORMED),
                )
            }
        }
        is GeoPdfGeorefResult.Rejected -> PdfImportResult(
            PdfMapSource.uncalibrated(fileUri, baseName, geometry, camera, PdfGeorefIssue.Rejected(result.reason)),
            PdfImportOutcome.Rejected(result.reason),
        )
        GeoPdfGeorefResult.NoGeoreference -> PdfImportResult(
            PdfMapSource.uncalibrated(fileUri, baseName, geometry, camera, PdfGeorefIssue.NoMetadata),
            PdfImportOutcome.NoGeoreference,
        )
    }
}

/**
 * Contract M import step 2-4: with the crash guard armed, open the page, check its frame,
 * draw the base raster and run the blank check on the import worker, before the map is
 * persisted. A PDF that kills pdfium dies here, not on every launch after. The session
 * (open page + raster) stays warm for 30 s so the first paint is immediate.
 */
internal suspend fun probeImportedPdf(
    context: Context,
    result: PdfImportResult,
    operationKey: String,
    guard: com.tacmap.map.render.pdf.PdfRenderGuard,
): PdfImportResult {
    val source = result.source
    val file = source.uri.path?.let(::File) ?: throw PdfImportRejectedException(Messages.pdfRenderReasonCannotOpen())
    // hash here on the worker so a later save never does it on main (D5-08)
    val contentKey = kotlinx.coroutines.withContext(Dispatchers.IO) {
        com.tacmap.calibration.PdfCalibrationIdentity.contentKey(file)?.also { com.tacmap.calibration.PdfStoredFile.remember(file, it) }
    }
    val probed = source.withRender(source.render.copy(contentKey = contentKey))
    val token = probed.render.renderGuardToken
    val georef = probed.placement ?: return result.copy(source = probed)
    guard.arm(com.tacmap.map.render.pdf.GuardKind.IMPORT, token, operationKey = operationKey)
    try {
        val footprint = com.tacmap.map.render.pdf.PdfFootprint.build(georef, probed.geometry.visibleBox)
        val policy = com.tacmap.map.render.pdf.PdfZoomPolicy.of(georef, footprint)
        val (ram, low) = com.tacmap.map.render.TileMemoryBudget.physicalMemory(context)
        val plan = policy.baseRasterPlan(footprint, com.tacmap.map.render.TileMemoryBudget.baseBudgetPx(ram, low))
        val session = com.tacmap.map.render.pdf.PdfRenderSessions.acquire(file, georef.page)
        try {
            session.baseRaster(plan, probed.geometry, footprint.clip, com.tacmap.map.render.pdf.PdfRenderExecutor.Band.VISIBLE, background = true).await()
        } finally {
            com.tacmap.map.render.pdf.PdfRenderSessions.release(session)
        }
    } catch (e: com.tacmap.map.render.pdf.PdfRenderException) {
        throw PdfImportRejectedException(pdfRenderFailureMessage(e.failure))
    } catch (oom: OutOfMemoryError) {
        throw PdfImportRejectedException(pdfRenderFailureMessage(com.tacmap.map.render.pdf.PdfRenderFailure.OUT_OF_MEMORY))
    } finally {
        // the process lived through it either way, that's all the guard is about
        guard.complete(com.tacmap.map.render.pdf.GuardKind.IMPORT, token)
    }
    return result.copy(source = probed)
}

/** the shared alert copy for each render failure (contract L) */
internal fun pdfRenderFailureMessage(reason: com.tacmap.map.render.pdf.PdfRenderFailure): String = when (reason) {
    com.tacmap.map.render.pdf.PdfRenderFailure.CANNOT_OPEN -> Messages.pdfRenderReasonCannotOpen()
    com.tacmap.map.render.pdf.PdfRenderFailure.PASSWORD_PROTECTED -> Messages.pdfRenderReasonPasswordProtected()
    com.tacmap.map.render.pdf.PdfRenderFailure.PAGE_MISSING -> Messages.pdfRenderReasonPageMissing()
    com.tacmap.map.render.pdf.PdfRenderFailure.PAGE_GEOMETRY -> Messages.pdfRenderReasonPageGeometry()
    com.tacmap.map.render.pdf.PdfRenderFailure.BLANK -> Messages.pdfRenderReasonBlank()
    com.tacmap.map.render.pdf.PdfRenderFailure.OUT_OF_MEMORY -> Messages.pdfRenderReasonOutOfMemory()
    com.tacmap.map.render.pdf.PdfRenderFailure.RENDER_ERROR -> Messages.pdfRenderReasonRenderError()
}

/** a refused GeoPDF parked behind the alert: not on the map, not in the library yet */
internal data class PendingGeorefRejection(val source: PdfMapSource, val reason: GeorefRejectReason)

/** Cancel on the refused-GeoPDF alert: the private copy is ours alone (one file per import op), drop it */
internal fun discardRejectedPdfImport(source: PdfMapSource) {
    val path = source.uri.path ?: return
    val file = File(path)
    if (file.isFile && !file.delete()) Log.w("GeoPdfImport", "couldn't drop a refused import copy")
}

/** reason clause slotted into pdf_georef_rejected_message, shared catalog keys with iOS */
internal fun pdfGeorefRejectionReason(reason: GeorefRejectReason): String = when (reason) {
    GeorefRejectReason.LPTS_OUT_OF_RANGE -> Messages.pdfGeorefReasonLptsOutOfRange()
    GeorefRejectReason.NON_FINITE -> Messages.pdfGeorefReasonNonFinite()
    GeorefRejectReason.GPTS_OFF_EARTH -> Messages.pdfGeorefReasonOffEarth()
    GeorefRejectReason.RMS_GATE -> Messages.pdfGeorefReasonRmsGate()
    GeorefRejectReason.DEGENERATE_VIEWPORT -> Messages.pdfGeorefReasonDegenerate()
    GeorefRejectReason.MALFORMED -> Messages.pdfGeorefReasonMalformed()
    GeorefRejectReason.UNKNOWN_DATUM -> Messages.pdfGeorefReasonUnknownDatum()
    GeorefRejectReason.UNSUPPORTED_PROJECTION -> Messages.pdfGeorefReasonUnsupportedProjection()
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

// iOS accepts 512 MB, but only the copy here streams. The rotation and GeoPDF
// checks open the file with PDFBox-Android 2.0, which parses every object on
// load and buffers stream contents in heap memory, so a larger sheet would run
// out of memory during import. Raise this once those checks stop doing that.
private const val MAX_PDF_IMPORT_BYTES = 256L * 1024 * 1024
private const val MAX_MBTILES_IMPORT_BYTES = 4L * 1024 * 1024 * 1024
