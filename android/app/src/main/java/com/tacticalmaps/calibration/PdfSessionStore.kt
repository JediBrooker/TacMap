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
            coverage = coverage
        )
        runCatching { json.encodeToString(dto) }
            .onSuccess { prefs.edit().putString(KEY_PDF, it).apply() }
            .onFailure { Log.w(TAG, "Couldn't encode PDF for persistence: ${it.message}") }
        // Also remember it in the per-PDF library so switching PDFs and back
        // restores this one's own calibration.
        saveToLibrary(source.displayName, calibration)
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
            coverage = dto.coverage,
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

    // Per-PDF calibration library keyed by display name — restores each PDF's
    // own fiduciaries + affine when the user re-imports / switches PDFs.
    fun calibration(displayName: String): Calibration.Fiduciaries? {
        val cal = loadLibrary()[displayName] ?: return null
        return Calibration.Fiduciaries(cal.fids, cal.transform)
    }

    private fun saveToLibrary(displayName: String, cal: Calibration.Fiduciaries) {
        val lib = loadLibrary().toMutableMap()
        lib[displayName] = PersistedCalibration(cal.fids, cal.transform)
        runCatching { json.encodeToString(lib) }
            .onSuccess { prefs.edit().putString(KEY_LIBRARY, it).apply() }
            .onFailure { Log.w(TAG, "Couldn't encode PDF calibration library: ${it.message}") }
    }

    private fun loadLibrary(): Map<String, PersistedCalibration> {
        val raw = prefs.getString(KEY_LIBRARY, null) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, PersistedCalibration>>(raw) }
            .getOrDefault(emptyMap())
    }

    private companion object {
        const val PREFS_NAME = "pdf_session"
        const val KEY_PDF = "active_pdf"
        const val KEY_LIBRARY = "pdf_calibrations"
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
    val coverage: Wgs84Bounds
)

@Serializable
private data class PersistedCalibration(
    val fids: List<Fiduciary>,
    val transform: AffineTransform2D
)
