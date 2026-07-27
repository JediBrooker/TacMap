package com.tacmap.calibration

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.tacmap.util.SafeStore
import com.tacmap.util.SealedEnvelope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists the active calibrated PDF map source across app launches so
 * the user doesn't have to re-import every time they close the app.
 *
 * The PDF file itself is already copied to internal `pdf_maps/` on
 * import so it survives restarts (unless OS clears app data). What we
 * add here is a small JSON sidecar in SharedPreferences with the
 * non-bitmap state (filename, display name, page dims, calibration
 * affine + the fiduciaries we fit it from) so we can reconstruct a
 * [PdfMapSource] with full GeoPDF accuracy on startup.
 *
 * Calibrated, parsed, and uncalibrated PDF sources are all saved. Even a PDF
 * using its rough fallback box is still the user's active basemap and must not
 * disappear merely because the process was closed.
 */
class PdfSessionStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun save(source: PdfMapSource): Boolean {
        val pageInfo = source.pageInfo ?: return false
        val coverage = source.coverage ?: return false
        val persistedCalibration = when (val calibration = source.calibration) {
            is Calibration.Fiduciaries -> PersistedCalibration(
                fids = calibration.fids,
                transform = calibration.transform
            )
            is Calibration.Parsed -> PersistedCalibration(
                fids = emptyList(),
                transform = calibration.transform
            )
            null -> null
        }
        val calibrationKind = when (source.calibration) {
            is Calibration.Fiduciaries -> CALIBRATION_FIDUCIARIES
            is Calibration.Parsed -> CALIBRATION_PARSED
            null -> CALIBRATION_NONE
        }
        val calibrationCrs = (source.calibration as? Calibration.Parsed)?.crs
        // Strip URI to just the basename inside our private `pdf_maps/`
        // dir. The full URI from import time is stable across the app's
        // lifetime but storing just the basename lets us recover if the
        // sandbox path ever shifts.
        val fileName = File(source.uri.path ?: return false).name
        val dto = PersistedPdfSource(
            fileName = fileName,
            displayName = source.displayName,
            pageWidth = pageInfo.pageWidth,
            pageHeight = pageInfo.pageHeight,
            sourceKind = source.kind.name,
            calibrationKind = calibrationKind,
            calibrationCrs = calibrationCrs,
            calibration = persistedCalibration,
            coverage = coverage
        )
        val persisted = runCatching { sealPref(LABEL_PDF, json.encodeToString(dto)) }
            .map { prefs.edit().putString(KEY_PDF, it).putBoolean(KEY_PDF_SEALED_ONLY, true).commit() }
            .onFailure { Log.w(TAG, "Couldn't encode PDF session for persistence") }
            .getOrDefault(false)
        // Also stash in the per-PDF library so switching PDFs and back
        // restores this one's calibration. Keyed by file CONTENT hash,
        // not display name - two different sheets that share a filename
        // must NOT inherit each other's affine.
        (source.calibration as? Calibration.Fiduciaries)?.let { calibration ->
            val sourcePath = source.uri.path
            val key = sourcePath
                ?.let { runCatching { contentKeyFor(File(it)) }.getOrNull() }
                ?: source.displayName // fall back to name only if the file is unreadable
            saveToLibrary(key, calibration)
        }
        return persisted
    }

    fun load(): PdfMapSource? {
        val stored = prefs.getString(KEY_PDF, null) ?: return null
        val raw = openPref(LABEL_PDF, stored) ?: return null
        val dto = runCatching { json.decodeFromString<PersistedPdfSource>(raw) }
            .onFailure { Log.w(TAG, "Couldn't decode persisted PDF session") }
            .getOrNull() ?: return null
        // Written by a pre-encryption build, seal it in place.
        if (isLegacyPlaintext(stored)) runCatching { sealPref(LABEL_PDF, raw) }
            .onSuccess { prefs.edit().putString(KEY_PDF, it).putBoolean(KEY_PDF_SEALED_ONLY, true).commit() }
        val pdfDir = File(context.filesDir, "pdf_maps")
        val file = File(pdfDir, dto.fileName)
        if (!file.exists()) {
            Log.w(TAG, "Persisted PDF file no longer exists")
            clear()
            return null
        }
        val calibration = when (dto.calibrationKind) {
            CALIBRATION_NONE -> null
            CALIBRATION_PARSED -> dto.calibration?.let {
                Calibration.Parsed(dto.calibrationCrs.orEmpty(), it.transform)
            }
            else -> dto.calibration?.let {
                Calibration.Fiduciaries(fids = it.fids, transform = it.transform)
            }
        }
        val sourceKind = dto.sourceKind
            ?.let { saved -> MapSourceKind.entries.firstOrNull { it.name == saved } }
            ?: if (calibration is Calibration.Parsed) MapSourceKind.GEO_PDF
            else MapSourceKind.CALIBRATED_PDF
        return PdfMapSource(
            uri = Uri.fromFile(file),
            displayName = dto.displayName,
            kind = sourceKind,
            coverage = dto.coverage,
            calibration = calibration,
            pageInfo = PdfPageInfo(dto.pageWidth, dto.pageHeight)
        )
    }

    fun clear() {
        prefs.edit().remove(KEY_PDF).apply()
    }

    /**
     * Per-PDF calibration library. Looks up by content hash first so the
     * correct affine follows the actual bytes even if display name collides
     * with a different sheet. Falls back to legacy display-name key for
     * entries saved before we added content-hash keying.
     */
    fun calibration(file: File?, displayName: String): Calibration.Fiduciaries? {
        val lib = loadLibrary()
        val byHash = file?.let { f -> runCatching { contentKeyFor(f) }.getOrNull()?.let { lib[it] } }
        val cal = byHash ?: lib[displayName] ?: return null
        return Calibration.Fiduciaries(cal.fids, cal.transform)
    }

    private fun saveToLibrary(key: String, cal: Calibration.Fiduciaries) {
        val lib = loadLibrary().toMutableMap()
        lib[key] = PersistedCalibration(cal.fids, cal.transform)
        runCatching { sealPref(LABEL_LIBRARY, json.encodeToString(lib)) }
            .onSuccess { prefs.edit().putString(KEY_LIBRARY, it).putBoolean(KEY_LIBRARY_SEALED_ONLY, true).commit() }
            .onFailure { Log.w(TAG, "Couldn't encode PDF calibration library") }
    }

    private fun loadLibrary(): Map<String, PersistedCalibration> {
        val stored = prefs.getString(KEY_LIBRARY, null) ?: return emptyMap()
        val raw = openPref(LABEL_LIBRARY, stored) ?: return emptyMap()
        val decoded = runCatching { json.decodeFromString<Map<String, PersistedCalibration>>(raw) }
            .getOrDefault(emptyMap())
        if (isLegacyPlaintext(stored)) {
            runCatching { sealPref(LABEL_LIBRARY, raw) }.onSuccess {
                prefs.edit().putString(KEY_LIBRARY, it).putBoolean(KEY_LIBRARY_SEALED_ONLY, true).commit()
            }
        }
        return decoded
    }

    // MARK: at-rest sealing
    //
    // The affine + fiduciaries + coverage bounds in here pin down exactly which
    // sheet is loaded and what ground it covers, so this is area-of-interest
    // data and gets the same treatment as waypoints. Values are sealed and
    // base64'd; SharedPreferences only ever sees ciphertext.

    private fun sealPref(label: String, plaintext: String): String =
        SafeStore.keyProvider.key().let { key ->
            SafeStore.markSealedOnlyAuthenticated(label)
            Base64.encodeToString(
                SealedEnvelope.sealFile(key, plaintext.toByteArray(Charsets.UTF_8), label),
                Base64.NO_WRAP
            )
        }

    /** Returns plaintext, or null when locked / tampered. Legacy values pass through. */
    private fun openPref(label: String, stored: String): String? {
        if (isLegacyPlaintext(stored)) {
            val sealedOnly = runCatching { SafeStore.isSealedOnlyAuthenticated(label) }
                .onFailure { Log.w(TAG, "Couldn't authenticate PDF migration ledger") }
                .getOrElse { return null }
            if (sealedOnly) {
                Log.w(TAG, "Rejected plaintext PDF preference after sealed-only migration")
                return null
            }
            return stored
        }
        return runCatching {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            SealedEnvelope.openFile(SafeStore.keyProvider.key(), blob, label)?.also {
                SafeStore.markSealedOnlyAuthenticated(label)
            }?.toString(Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "Couldn't open sealed PDF preference") }.getOrNull()
    }

    /** Pre-encryption builds stored bare JSON. Base64 of the seal never starts with '{'. */
    private fun isLegacyPlaintext(stored: String): Boolean = stored.startsWith("{")

    /** SHA-256 of the file bytes, streamed so big PDFs dont load whole. */
    private fun contentKeyFor(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return "sha256:" + md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private companion object {
        const val PREFS_NAME = "pdf_session"
        const val KEY_PDF = "active_pdf"
        const val KEY_LIBRARY = "pdf_calibrations"
        const val KEY_PDF_SEALED_ONLY = "active_pdf_sealed_only_v1"
        const val KEY_LIBRARY_SEALED_ONLY = "pdf_calibrations_sealed_only_v1"
        const val TAG = "PdfSessionStore"
        const val CALIBRATION_NONE = "none"
        const val CALIBRATION_PARSED = "parsed"
        const val CALIBRATION_FIDUCIARIES = "fiduciaries"
        /** AEAD associated data, keeps one pref's blob from opening as the other. */
        const val LABEL_PDF = "pdf_session/active_pdf"
        const val LABEL_LIBRARY = "pdf_session/pdf_calibrations"
    }
}

@Serializable
private data class PersistedPdfSource(
    val fileName: String,
    val displayName: String,
    val pageWidth: Int,
    val pageHeight: Int,
    val sourceKind: String? = null,
    val calibrationKind: String = "fiduciaries",
    val calibrationCrs: String? = null,
    val calibration: PersistedCalibration? = null,
    val coverage: Wgs84Bounds
)

@Serializable
private data class PersistedCalibration(
    val fids: List<Fiduciary>,
    val transform: AffineTransform2D
)
