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
 * Only [PdfMapSource] instances with [Calibration.Fiduciaries] are
 * saved - don't bother persisting uncalibrated sources since they have
 * no real geographic position, just a useless rough fallback box.
 */
class PdfSessionStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun save(source: PdfMapSource) {
        val calibration = source.calibration as? Calibration.Fiduciaries ?: run {
            // uncalibrated PDF, don't bother persisting - fallback
            // bounds aren't worth restoring
            return
        }
        val pageInfo = source.pageInfo ?: return
        val coverage = source.coverage ?: return
        // Strip URI to just the basename inside our private `pdf_maps/`
        // dir. The full URI from import time is stable across the app's
        // lifetime but storing just the basename lets us recover if the
        // sandbox path ever shifts.
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
        runCatching { sealPref(LABEL_PDF, json.encodeToString(dto)) }
            .onSuccess { prefs.edit().putString(KEY_PDF, it).apply() }
            .onFailure { Log.w(TAG, "Couldn't encode PDF for persistence: ${it.message}") }
        // Also stash in the per-PDF library so switching PDFs and back
        // restores this one's calibration. Keyed by file CONTENT hash,
        // not display name - two different sheets that share a filename
        // must NOT inherit each other's affine.
        val key = runCatching { contentKeyFor(File(source.uri.path ?: return)) }.getOrNull()
            ?: source.displayName // fall back to name only if the file is unreadable
        saveToLibrary(key, calibration)
    }

    fun load(): PdfMapSource? {
        val stored = prefs.getString(KEY_PDF, null) ?: return null
        val raw = openPref(LABEL_PDF, stored) ?: return null
        val dto = runCatching { json.decodeFromString<PersistedPdfSource>(raw) }
            .onFailure { Log.w(TAG, "Couldn't decode persisted PDF: ${it.message}") }
            .getOrNull() ?: return null
        // Written by a pre-encryption build, seal it in place.
        if (isLegacyPlaintext(stored)) runCatching { sealPref(LABEL_PDF, raw) }
            .onSuccess { prefs.edit().putString(KEY_PDF, it).apply() }
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
            .onSuccess { prefs.edit().putString(KEY_LIBRARY, it).apply() }
            .onFailure { Log.w(TAG, "Couldn't encode PDF calibration library: ${it.message}") }
    }

    private fun loadLibrary(): Map<String, PersistedCalibration> {
        val stored = prefs.getString(KEY_LIBRARY, null) ?: return emptyMap()
        val raw = openPref(LABEL_LIBRARY, stored) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, PersistedCalibration>>(raw) }
            .getOrDefault(emptyMap())
    }

    // MARK: at-rest sealing
    //
    // The affine + fiduciaries + coverage bounds in here pin down exactly which
    // sheet is loaded and what ground it covers, so this is area-of-interest
    // data and gets the same treatment as waypoints. Values are sealed and
    // base64'd; SharedPreferences only ever sees ciphertext.

    private fun sealPref(label: String, plaintext: String): String =
        Base64.encodeToString(
            SealedEnvelope.sealFile(SafeStore.keyProvider.key(), plaintext.toByteArray(Charsets.UTF_8), label),
            Base64.NO_WRAP
        )

    /** Returns plaintext, or null when locked / tampered. Legacy values pass through. */
    private fun openPref(label: String, stored: String): String? {
        if (isLegacyPlaintext(stored)) return stored
        return runCatching {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            SealedEnvelope.openFile(SafeStore.keyProvider.key(), blob, label)?.toString(Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "Couldn't open sealed pref $label: ${it.message}") }.getOrNull()
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
        const val TAG = "PdfSessionStore"
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
    val calibration: PersistedCalibration,
    val coverage: Wgs84Bounds
)

@Serializable
private data class PersistedCalibration(
    val fids: List<Fiduciary>,
    val transform: AffineTransform2D
)
