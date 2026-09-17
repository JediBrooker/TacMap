package com.tacmap.calibration

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.tacmap.util.DurablePreferenceCommit
import com.tacmap.util.SafeStore
import com.tacmap.util.SealedEnvelope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

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
        val pdfRoot = runCatching { File(context.filesDir, "pdf_maps").canonicalFile }
            .getOrNull() ?: return false
        val sourceFile = source.uri.path
            ?.let(::File)
            ?.let { runCatching { it.canonicalFile }.getOrNull() }
            ?: return false
        if (!sourceFile.isFile ||
            !sourceFile.name.endsWith(".pdf", ignoreCase = true) ||
            !sourceFile.path.startsWith(pdfRoot.path + File.separator)
        ) {
            Log.w(TAG, "Rejected PDF session outside the private PDF map library")
            return false
        }
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
        val fileName = sourceFile.name
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
            PdfCalibrationIdentity.contentKey(sourceFile)?.let { key ->
                saveToLibrary(key, calibration)
            } ?: Log.w(TAG, "Couldn't fingerprint PDF; calibration library entry was not saved")
        }
        return persisted
    }

    fun load(): PdfMapSource? {
        val stored = prefs.getString(KEY_PDF, null) ?: return null
        val raw = openPref(LABEL_PDF, stored) ?: return null
        val dto = runCatching { json.decodeFromString<PersistedPdfSource>(raw) }
            .onFailure { Log.w(TAG, "Couldn't decode persisted PDF session") }
            .getOrNull() ?: return null
        // Never publish a decoded legacy session until its encrypted replacement
        // is durably committed. sealPref() writes the authenticated sealed-only
        // marker first, so a failed preference write must fail closed now and on
        // the next launch rather than using state that can no longer be recovered.
        if (isLegacyPlaintext(stored) &&
            PdfLegacyPreferenceMigration.publishAfterMigration(
                plaintext = raw,
                decoded = dto,
                seal = { sealPref(LABEL_PDF, it) },
                persist = { sealed ->
                    commitLegacyReplacement(KEY_PDF, KEY_PDF_SEALED_ONLY, sealed)
                },
            ) == null
        ) {
            Log.w(TAG, "Couldn't durably migrate legacy PDF session")
            return null
        }
        val pdfRoot = runCatching { File(context.filesDir, "pdf_maps").canonicalFile }
            .getOrNull() ?: return null
        val file = dto.fileName
            .takeIf { name ->
                name == File(name).name &&
                    '/' !in name &&
                    '\\' !in name &&
                    name.endsWith(".pdf", ignoreCase = true)
            }
            ?.let { name -> runCatching { File(pdfRoot, name).canonicalFile }.getOrNull() }
        if (file == null ||
            !file.isFile ||
            !file.path.startsWith(pdfRoot.path + File.separator)
        ) {
            Log.w(TAG, "Rejected missing or invalid persisted PDF path")
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

    /** Capture the exact encrypted active-session preference for rollback. */
    internal fun snapshotActiveSession(): PdfSessionSnapshot = PdfSessionSnapshot(
        encodedSession = prefs.getString(KEY_PDF, null),
        sealedOnly = prefs.getBoolean(KEY_PDF_SEALED_ONLY, false),
    )

    /** Restore an exact pre-transition snapshot after selector persistence fails. */
    internal fun restoreActiveSession(snapshot: PdfSessionSnapshot): Boolean {
        val editor = prefs.edit()
        if (snapshot.encodedSession == null) editor.remove(KEY_PDF)
        else editor.putString(KEY_PDF, snapshot.encodedSession)
        if (snapshot.sealedOnly) editor.putBoolean(KEY_PDF_SEALED_ONLY, true)
        else editor.remove(KEY_PDF_SEALED_ONLY)
        return editor.commit()
    }

    fun clear(): Boolean = prefs.edit().remove(KEY_PDF).commit()

    /**
     * Per-PDF calibration library. Only a content hash can prove that a saved
     * affine belongs to these exact PDF bytes. Legacy filename-only entries are
     * intentionally ignored: filenames are user-controlled and frequently
     * reused for revisions of a sheet.
     */
    fun calibration(file: File?): Calibration.Fiduciaries? {
        val lib = loadLibrary()
        val cal = PdfCalibrationIdentity.lookup(file, lib) ?: return null
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
            val migrated = PdfLegacyPreferenceMigration.publishAfterMigration(
                plaintext = raw,
                decoded = decoded,
                seal = { sealPref(LABEL_LIBRARY, it) },
                persist = { sealed ->
                    commitLegacyReplacement(KEY_LIBRARY, KEY_LIBRARY_SEALED_ONLY, sealed)
                },
            )
            if (migrated == null) {
                Log.w(TAG, "Couldn't durably migrate legacy PDF calibration library")
                return emptyMap()
            }
        }
        return decoded
    }

    private fun commitLegacyReplacement(key: String, sealedOnlyKey: String, sealed: String): Boolean =
        DurablePreferenceCommit.preferences(
            preferences = prefs,
            keys = setOf(key, sealedOnlyKey),
            mutate = { putString(key, sealed).putBoolean(sealedOnlyKey, true) },
            publish = {},
        )

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
            if (!PdfLegacyPreferenceMigration.acceptPlaintext(sealedOnly)) {
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

/**
 * Content-bound identity for reusable PDF calibrations.
 *
 * Keeping this pure makes the collision and cold-restore contract testable
 * without Android storage. A missing/unreadable file has no identity: callers
 * must never substitute a filename or display label.
 */
internal object PdfCalibrationIdentity {
    private const val BUFFER_BYTES = 64 * 1024
    private val hex = "0123456789abcdef".toCharArray()

    fun contentKey(file: File?): String? {
        if (file == null || !file.isFile) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_BYTES)
            try {
                file.inputStream().use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) digest.update(buffer, 0, count)
                    }
                }
            } finally {
                buffer.fill(0)
            }
            "sha256:" + digest.digest().toLowerHex()
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun <T> lookup(file: File?, library: Map<String, T>): T? =
        contentKey(file)?.let(library::get)

    private fun ByteArray.toLowerHex(): String {
        val output = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            output[index * 2] = hex[value ushr 4]
            output[index * 2 + 1] = hex[value and 0x0f]
        }
        return String(output)
    }
}

/** Durable-before-publication gate for the two legacy PDF preferences. */
internal object PdfLegacyPreferenceMigration {
    fun acceptPlaintext(sealedOnlyAuthenticated: Boolean): Boolean =
        !sealedOnlyAuthenticated

    fun <T : Any> publishAfterMigration(
        plaintext: String,
        decoded: T,
        seal: (String) -> String,
        persist: (String) -> Boolean,
    ): T? {
        val sealed = try {
            seal(plaintext)
        } catch (_: Exception) {
            return null
        }
        val committed = try {
            persist(sealed)
        } catch (_: Exception) {
            false
        }
        return decoded.takeIf { committed }
    }
}

internal data class PdfSessionSnapshot(
    val encodedSession: String?,
    val sealedOnly: Boolean,
)

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
