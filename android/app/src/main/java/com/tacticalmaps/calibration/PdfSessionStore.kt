package com.tacticalmaps.calibration

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists the currently-active calibrated PDF map source across app
 * launches so the user doesn't have to re-import after closing the
 * app.
 *
 * The PDF file itself is already copied to internal `pdf_maps/` on
 * import, so it lives across restarts as long as the OS doesn't clear
 * app data. What we add here is a small JSON sidecar in
 * `SharedPreferences` that captures the **non-bitmap** state
 * (filename, display name, page dimensions, calibration affine + the
 * fiduciaries we fit it from) so we can reconstruct a [PdfMapSource]
 * with full GeoPDF accuracy on startup.
 *
 * Only [PdfMapSource] instances that carry a [Calibration.Fiduciaries]
 * are saved — an uncalibrated source has no real geographic position
 * and re-reading it on startup would just resurrect a useless rough
 * fallback box.
 */
class PdfSessionStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun save(source: PdfMapSource) {
        val calibration = source.calibration as? Calibration.Fiduciaries ?: run {
            /// Uncalibrated PDF — don't bother persisting, the
            /// fallback bounds aren't worth restoring.
            return
        }
        val pageInfo = source.pageInfo ?: return
        val coverage = source.coverage ?: return
        /// Strip the URI down to just the file's basename inside our
        /// private `pdf_maps/` directory. The full URI baked at
        /// import time is `file:///data/.../files/pdf_maps/foo.pdf`,
        /// which is stable across the app's lifetime — but storing
        /// just the basename lets us recover gracefully if the
        /// sandbox path ever shifts.
        val fileName = File(source.uri.path ?: return).name
        val dto = PersistedPdfSource(
            fileName = fileName,
            displayName = source.displayName,
            pageWidth = pageInfo.pageWidth,
            pageHeight = pageInfo.pageHeight,
            calibration = PersistedCalibration(
                fids = calibration.fids,
                transform = calibration.transform
            ),
            coverage = PersistedBounds(
                swLat = coverage.southwest.latitude,
                swLng = coverage.southwest.longitude,
                neLat = coverage.northeast.latitude,
                neLng = coverage.northeast.longitude
            )
        )
        runCatching { json.encodeToString(dto) }
            .onSuccess { prefs.edit().putString(KEY_PDF, it).apply() }
            .onFailure { Log.w(TAG, "Couldn't encode PDF for persistence: ${it.message}") }
    }

    fun load(): PdfMapSource? {
        val raw = prefs.getString(KEY_PDF, null) ?: return null
        val dto = runCatching { json.decodeFromString<PersistedPdfSource>(raw) }
            .onFailure { Log.w(TAG, "Couldn't decode persisted PDF: ${it.message}") }
            .getOrNull() ?: return null
        val pdfDir = File(context.filesDir, "pdf_maps")
        val file = File(pdfDir, dto.fileName)
        if (!file.exists()) {
            Log.w(TAG, "Persisted PDF file no longer exists: ${file.absolutePath}")
            clear()
            return null
        }
        return PdfMapSource(
            uri = Uri.fromFile(file),
            displayName = dto.displayName,
            kind = MapSourceKind.CALIBRATED_PDF,
            coverage = Wgs84Bounds(
                southwest = Wgs84Coordinate(dto.coverage.swLat, dto.coverage.swLng),
                northeast = Wgs84Coordinate(dto.coverage.neLat, dto.coverage.neLng)
            ),
            calibration = Calibration.Fiduciaries(
                fids = dto.calibration.fids,
                transform = dto.calibration.transform
            ),
            pageInfo = PdfPageInfo(dto.pageWidth, dto.pageHeight)
        )
    }

    fun clear() {
        prefs.edit().remove(KEY_PDF).apply()
    }

    private companion object {
        const val PREFS_NAME = "pdf_session"
        const val KEY_PDF = "active_pdf"
        const val TAG = "PdfSessionStore"
    }
}

@Serializable
private data class PersistedPdfSource(
    val fileName: String,
    val displayName: String,
    val pageWidth: Int,
    val pageHeight: Int,
    val calibration: PersistedCalibration,
    val coverage: PersistedBounds
)

@Serializable
private data class PersistedCalibration(
    val fids: List<Fiduciary>,
    val transform: AffineTransform2D
)

@Serializable
private data class PersistedBounds(
    val swLat: Double,
    val swLng: Double,
    val neLat: Double,
    val neLng: Double
)
