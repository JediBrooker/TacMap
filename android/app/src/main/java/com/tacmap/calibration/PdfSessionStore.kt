package com.tacmap.calibration

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.annotation.WorkerThread
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
 * Persists the active PDF map source across app launches so the user doesn't
 * have to re-import every time they close the app.
 *
 * The PDF file itself is already copied to internal pdf_maps/ on import so it
 * survives restarts (unless OS clears app data). What we add here is a small
 * sealed JSON sidecar in SharedPreferences with the non-bitmap state: page
 * geometry, the plan 02 georef (schema 2) and the fiduciaries it came from.
 *
 * Schema 1 sessions (a page -> lon/lat affine) are migrated on load: GeoPDFs are
 * re-parsed, user fiduciaries refitted in UTM, and the old camera-centred
 * fallback box becomes an honest "uncalibrated" source. See [PdfSessionMigration].
 */
class PdfSessionStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun save(source: PdfMapSource): Boolean = save(source, onlyIfStillStored = null)

    /** the active blob a background write expects to still be there (null = nothing stored) */
    private class StoredBlob(val value: String?)

    private fun save(source: PdfMapSource, onlyIfStillStored: StoredBlob?): Boolean {
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
        val calibration = source.calibration
        // Strip URI to just the basename inside our private `pdf_maps/`
        // dir. The full URI from import time is stable across the app's
        // lifetime but storing just the basename lets us recover if the
        // sandbox path ever shifts.
        val dto = PersistedPdfSource(
            schemaVersion = SCHEMA_VERSION,
            fileName = sourceFile.name,
            displayName = source.displayName,
            pageWidth = source.geometry.rendererWidth,
            pageHeight = source.geometry.rendererHeight,
            sourceKind = source.kind.name,
            calibrationKind = when (calibration) {
                is Calibration.Fiduciaries -> PdfSessionMigration.KIND_FIDUCIARIES
                is Calibration.Parsed -> PdfSessionMigration.KIND_PARSED
                null -> PdfSessionMigration.KIND_NONE
            },
            calibration = (calibration as? Calibration.Fiduciaries)?.let {
                PersistedCalibration(fids = it.fids, pageSpace = PAGE_SPACE_RAW)
            } ?: source.pendingFiduciaries.takeIf { it.isNotEmpty() }?.let {
                PersistedCalibration(fids = it, pageSpace = PAGE_SPACE_RAW)
            },
            coverage = coverage,
            georef = calibration?.georef?.let(PdfGeoreferenceCodec::encode),
            provisional = source.provisional?.let(PdfGeoreferenceCodec::encode),
            geometry = source.geometry,
            georefIssue = PdfGeoreferenceCodec.encodeIssue(source.georefIssue),
            renderGuardToken = source.render.renderGuardToken,
            contentKey = source.render.contentKey,
            bake = source.render.bake,
        )
        val persisted = runCatching { sealPref(LABEL_PDF, json.encodeToString(dto)) }
            .map { sealed ->
                synchronized(SESSION_LOCK) {
                    // a background migration only writes over the exact blob it migrated, never
                    // over a session the user saved in the meantime
                    if (onlyIfStillStored != null && prefs.getString(KEY_PDF, null) != onlyIfStillStored.value) {
                        Log.w(TAG, "PDF session changed while migrating, dropping the migrated copy")
                        return false
                    }
                    prefs.edit().putString(KEY_PDF, sealed).putBoolean(KEY_PDF_SEALED_ONLY, true).commit()
                }
            }
            .onFailure { Log.w(TAG, "Couldn't encode PDF session for persistence") }
            .getOrDefault(false)
        // Also stash in the per-PDF library so switching PDFs and back
        // restores this one's calibration. Keyed by file CONTENT hash,
        // not display name - two different sheets that share a filename
        // must NOT inherit each other's affine.
        (calibration as? Calibration.Fiduciaries)?.let { fiducial ->
            // the import worker already hashed it, don't do a 38 MB sha256 on main again (D5-08)
            (source.render.contentKey ?: PdfCalibrationIdentity.contentKey(sourceFile))?.let { key ->
                saveToLibrary(key, fiducial.fids)
            } ?: Log.w(TAG, "Couldn't fingerprint PDF; calibration library entry was not saved")
        }
        return persisted
    }

    /**
     * Blocking restore, migration included. Can open and parse the PDF, so worker
     * thread only; the UI goes through [loadSession] + [migrate].
     */
    @WorkerThread
    fun load(): PdfMapSource? = when (val session = loadSession()) {
        is PdfSessionLoad.Ready -> session.source
        is PdfSessionLoad.NeedsMigration -> migrate(session)
        null -> null
    }

    /** the active session's PDF, without restoring or migrating anything */
    fun activeFile(): File? = when (val session = loadSession()) {
        is PdfSessionLoad.Ready -> session.source.uri.path?.let(::File)
        is PdfSessionLoad.NeedsMigration -> session.file
        null -> null
    }

    /**
     * What's persisted, read off prefs only: no PDF is opened, so this is fine on
     * main. A schema 2 session comes straight back; a schema 1 one, or one whose
     * georef no longer decodes, comes back as [PdfSessionLoad.NeedsMigration] for
     * [migrate] to finish off main (a 38 MB GeoPDF parse at launch is an ANR).
     */
    fun loadSession(): PdfSessionLoad? {
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
        // a legacy plaintext blob was just re-sealed above, the migration guard has to
        // compare against what's in prefs now or it'd never be allowed to save
        val current = if (isLegacyPlaintext(stored)) prefs.getString(KEY_PDF, null) else stored
        val restored = file?.let { restoreOrDefer(dto, it, current) }
        return when (
            storedFileVerdict(
                validPath = file != null && file.path.startsWith(pdfRoot.path + File.separator),
                fileThere = file?.isFile == true,
                contentBound = dto.contentKey != null && restored is PdfSessionLoad.Ready,
            )
        ) {
            StoredFileVerdict.RESTORE -> restored
            StoredFileVerdict.KEEP_UNAVAILABLE -> {
                // OD-F4: the record stays sealed as it is. The source points where the file
                // should be, the render session checks the bytes against contentKey before
                // drawing anything, so this is cannotOpen (G1) until the right file is back
                Log.w(TAG, "Active PDF file is missing, keeping the session")
                restored
            }
            StoredFileVerdict.REJECT -> {
                Log.w(TAG, "Rejected missing or invalid persisted PDF path")
                clear()
                null
            }
        }
    }

    /**
     * Finish a session [loadSession] couldn't restore on its own: opens the PDF,
     * re-parses / refits, and re-saves it as schema 2 (only over the exact blob
     * that was loaded). null when the PDF won't open or the user saved another
     * session meanwhile, in which case this result is stale anyway.
     */
    @WorkerThread
    fun migrate(pending: PdfSessionLoad.NeedsMigration): PdfMapSource? {
        val dto = pending.dto
        val geometry = dto.geometry?.takeIf { dto.schemaVersion >= SCHEMA_VERSION && it.isValid() }
        val source = if (geometry != null) recoverV2(dto, pending.file, geometry) else migrateV1(dto, pending.file)
        source ?: return null
        if (!save(source, onlyIfStillStored = StoredBlob(pending.stored))) {
            Log.w(TAG, "Migrated PDF session wasn't re-saved, will migrate again next launch")
            if (prefs.getString(KEY_PDF, null) != pending.stored) return null
        }
        return source
    }

    /** schema 2 restores, anything needing the PDF or a re-save is deferred to [migrate] */
    internal fun restoreOrDefer(dto: PersistedPdfSource, file: File, stored: String?): PdfSessionLoad {
        val geometry = dto.geometry
        if (dto.schemaVersion >= SCHEMA_VERSION && geometry != null && geometry.isValid()) {
            val georef = dto.georef?.let(PdfGeoreferenceCodec::decode)
            val lost = georef == null && dto.calibrationKind != PdfSessionMigration.KIND_NONE
            if (!lost) return PdfSessionLoad.Ready(restore(dto, file, geometry, georef))
        }
        return PdfSessionLoad.NeedsMigration(dto, file, stored)
    }

    /** test hook: restore, or migrate on the calling thread */
    @WorkerThread
    internal fun restoreOrMigrate(dto: PersistedPdfSource, file: File): PdfMapSource? =
        when (val session = restoreOrDefer(dto, file, stored = prefs.getString(KEY_PDF, null))) {
            is PdfSessionLoad.Ready -> session.source
            is PdfSessionLoad.NeedsMigration -> migrate(session)
        }

    private fun restore(dto: PersistedPdfSource, file: File, geometry: PdfPageGeometry, georef: PdfGeoreference?): PdfMapSource {
        val uri = Uri.fromFile(file)
        val fids = dto.calibration?.fids.orEmpty()
        val calibration: Calibration? = when {
            georef == null -> null
            dto.calibrationKind == PdfSessionMigration.KIND_PARSED -> Calibration.Parsed(georef)
            dto.calibrationKind == PdfSessionMigration.KIND_FIDUCIARIES && fids.size >= 3 ->
                Calibration.Fiduciaries(fids, georef)
            else -> null
        }
        val render = renderMeta(dto)
        if (calibration != null) {
            val kind = if (calibration is Calibration.Parsed) MapSourceKind.GEO_PDF else MapSourceKind.CALIBRATED_PDF
            return PdfMapSource(uri, dto.displayName, kind, calibration, geometry, render = render)
        }
        // an uncalibrated sheet comes back on its provisional placement, still labelled
        val issue = PdfGeoreferenceCodec.decodeIssue(dto.georefIssue)
            ?: if (dto.calibrationKind == PdfSessionMigration.KIND_NONE) PdfGeorefIssue.NoMetadata
            else PdfGeorefIssue.CalibrationLost
        val provisional = dto.provisional?.let(PdfGeoreferenceCodec::decode)
            ?.takeIf { it.origin == GeorefOrigin.PROVISIONAL }
        return PdfMapSource(
            uri = uri,
            displayName = dto.displayName,
            kind = MapSourceKind.CALIBRATED_PDF,
            calibration = null,
            geometry = geometry,
            provisional = provisional
                ?: PdfGeoreference.provisional(dto.coverage?.center ?: Wgs84Coordinate(0.0, 0.0), geometry.visibleCrop(), geometry.rotation),
            georefIssue = issue,
            pendingFiduciaries = fids,
            render = render.copy(bake = null),
        )
    }

    /** the stored render bits, a fresh token when an older blob has none */
    private fun renderMeta(dto: PersistedPdfSource): PdfRenderMeta {
        val token = dto.renderGuardToken?.takeIf { isUuid(it) }
        return PdfRenderMeta(
            renderGuardToken = token ?: java.util.UUID.randomUUID().toString(),
            contentKey = dto.contentKey,
            // a record we wouldn't have written never gets read, and the sweep treats it as naming nothing (F6)
            bake = dto.bake?.takeIf(::isValidBakeRecord),
            tokenMinted = token == null,
        )
    }

    /**
     * Schema 1 -> 2. Needs the PDF itself (page frame, and a re-parse for GeoPDFs),
     * so it's a one-off bit of file IO on the first launch after the update.
     */
    private fun migrateV1(dto: PersistedPdfSource, file: File): PdfMapSource? {
        val page = runCatching { PdfDocumentInspector.inspect(context, file) }
            .onFailure { Log.w(TAG, "Couldn't open the PDF behind a v1 session") }
            .getOrNull() ?: return null
        val outcome = PdfSessionMigration.migrate(
            calibrationKind = dto.calibrationKind,
            fiduciaries = dto.calibration?.fids.orEmpty(),
            v1RendererWidth = dto.pageWidth,
            v1RendererHeight = dto.pageHeight,
            geometry = page.geometry,
            reparse = { page.georeference() },
        )
        return sourceFor(dto, file, page.geometry, outcome)
    }

    /**
     * Schema 2 whose georef didn't decode. Fiduciaries are raw already so they just
     * refit; only a GeoPDF needs the file opened and re-parsed.
     */
    private fun recoverV2(dto: PersistedPdfSource, file: File, geometry: PdfPageGeometry): PdfMapSource? {
        // a re-parse brings its own (current) page frame, a refit keeps the saved one
        val page = if (dto.calibrationKind == PdfSessionMigration.KIND_PARSED) {
            runCatching { PdfDocumentInspector.inspect(context, file) }
                .onFailure { Log.w(TAG, "Couldn't re-read the PDF behind a lost georef") }
                .getOrNull() ?: return null
        } else {
            null
        }
        val outcome = PdfSessionMigration.recover(
            calibrationKind = dto.calibrationKind,
            rawFiduciaries = dto.calibration?.fids.orEmpty(),
            geometry = page?.geometry ?: geometry,
            reparse = { page?.georeference() },
        )
        return sourceFor(dto, file, page?.geometry ?: geometry, outcome)
    }

    private fun sourceFor(
        dto: PersistedPdfSource,
        file: File,
        geometry: PdfPageGeometry,
        outcome: PdfSessionMigration.Outcome,
    ): PdfMapSource? {
        val uri = Uri.fromFile(file)
        val source = when (outcome) {
            is PdfSessionMigration.Outcome.Georeferenced -> {
                val kind = if (outcome.calibration is Calibration.Parsed) MapSourceKind.GEO_PDF else MapSourceKind.CALIBRATED_PDF
                // a migrated georef isn't the one any old bake was keyed on
                PdfMapSource(uri, dto.displayName, kind, outcome.calibration, geometry, render = renderMeta(dto).copy(bake = null))
            }
            is PdfSessionMigration.Outcome.Uncalibrated -> PdfMapSource.uncalibrated(
                uri = uri,
                name = dto.displayName,
                geometry = geometry,
                // where they last saw it, it's labelled uncalibrated so nobody reads grids off it
                center = dto.coverage?.center ?: Wgs84Coordinate(0.0, 0.0),
                issue = outcome.issue,
                pendingFiduciaries = outcome.pendingFiduciaries,
                render = renderMeta(dto),
            )
        }
        return source.takeIf { it.coverage != null }
    }

    // WP2 kept the bake record + guard token in here. Since the WP4 merge they live on the
    // library entry (merge spec M1-M3), this store is a migration-only reader again: load()
    // still decodes renderGuardToken/bake so the migration can carry the token over

    /** Capture the exact encrypted active-session preference for rollback. */
    internal fun snapshotActiveSession(): PdfSessionSnapshot = PdfSessionSnapshot(
        encodedSession = prefs.getString(KEY_PDF, null),
        sealedOnly = prefs.getBoolean(KEY_PDF_SEALED_ONLY, false),
    )

    /** Restore an exact pre-transition snapshot after selector persistence fails. */
    internal fun restoreActiveSession(snapshot: PdfSessionSnapshot): Boolean = synchronized(SESSION_LOCK) {
        val editor = prefs.edit()
        if (snapshot.encodedSession == null) editor.remove(KEY_PDF)
        else editor.putString(KEY_PDF, snapshot.encodedSession)
        if (snapshot.sealedOnly) editor.putBoolean(KEY_PDF_SEALED_ONLY, true)
        else editor.remove(KEY_PDF_SEALED_ONLY)
        editor.commit()
    }

    fun clear(): Boolean = synchronized(SESSION_LOCK) { prefs.edit().remove(KEY_PDF).commit() }

    /** anything left over from the pre-library builds */
    fun hasLegacyState(): Boolean = prefs.contains(KEY_PDF) || prefs.contains(KEY_LIBRARY)

    /** an active PDF record is stored, readable or not (the migration fails closed on one it can't read) */
    fun hasActivePdf(): Boolean = prefs.contains(KEY_PDF)

    /**
     * the content-keyed calibration library as the library migration sees it, never re-sealed
     * or cleared here. null = none stored, false = stored but it won't open or decode (the
     * migration then leaves it where it is instead of clearing it)
     */
    internal fun calibrationsReadable(): Boolean? {
        val stored = prefs.getString(KEY_LIBRARY, null) ?: return null
        val raw = openPref(LABEL_LIBRARY, stored) ?: return false
        return runCatching { json.decodeFromString<Map<String, PersistedCalibration>>(raw) }.isSuccess
    }

    /**
     * The library owns calibrations now (contract s8.2), so after a migration both
     * the active session and the content-hash calibration library go. Calibrations
     * for files that aren't in the library any more just disappear with it (D5-19).
     */
    fun clearAfterLibraryMigration(): Boolean = synchronized(SESSION_LOCK) {
        prefs.edit().remove(KEY_PDF).remove(KEY_LIBRARY).commit()
    }

    /**
     * Per-PDF calibration library. Only a content hash can prove that saved
     * fiduciaries belong to these exact PDF bytes. Legacy filename-only entries are
     * intentionally ignored: filenames are user-controlled and frequently
     * reused for revisions of a sheet. Entries saved before schema 2 hold v1
     * (renderer relative) page points; [StoredFiduciaries.rawPageSpace] says which.
     */
    fun libraryFiduciaries(file: File?): StoredFiduciaries? {
        val lib = loadLibrary()
        val cal = PdfCalibrationIdentity.lookup(file, lib) ?: return null
        if (cal.fids.size < 3) return null
        return StoredFiduciaries(cal.fids, rawPageSpace = cal.pageSpace == PAGE_SPACE_RAW)
    }

    // load + modify + commit all under the one lock, the migration runs on IO now and two
    // saves racing here used to drop whichever library entry got written first
    private fun saveToLibrary(key: String, fids: List<Fiduciary>) = synchronized(SESSION_LOCK) {
        val lib = loadLibrary().toMutableMap()
        lib[key] = PersistedCalibration(fids, pageSpace = PAGE_SPACE_RAW)
        runCatching { sealPref(LABEL_LIBRARY, json.encodeToString(lib)) }
            .onSuccess { prefs.edit().putString(KEY_LIBRARY, it).putBoolean(KEY_LIBRARY_SEALED_ONLY, true).commit() }
            .onFailure { Log.w(TAG, "Couldn't encode PDF calibration library") }
        Unit
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

    private fun isUuid(s: String): Boolean =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(s)

    /** Pre-encryption builds stored bare JSON. Base64 of the seal never starts with '{'. */
    private fun isLegacyPlaintext(stored: String): Boolean = stored.startsWith("{")

    private companion object {
        /** one per process: the UI and a background migration write the same pref */
        val SESSION_LOCK = Any()
        const val PREFS_NAME = "pdf_session"
        const val KEY_PDF = "active_pdf"
        const val KEY_LIBRARY = "pdf_calibrations"
        const val KEY_PDF_SEALED_ONLY = "active_pdf_sealed_only_v1"
        const val KEY_LIBRARY_SEALED_ONLY = "pdf_calibrations_sealed_only_v1"
        const val TAG = "PdfSessionStore"
        const val SCHEMA_VERSION = 2
        const val PAGE_SPACE_RAW = "raw"
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
/** what a restore does with the stored PDF path (OD-F4) */
internal enum class StoredFileVerdict { RESTORE, KEEP_UNAVAILABLE, REJECT }

/**
 * OD-F4, pure: an invalid record (bad name, escapes pdf_maps, won't decode) is cleared.
 * A content bound one (contentKey + a georef that restores without the file) whose file
 * is missing is kept, the render side fails it as cannotOpen until the bytes are back.
 * A legacy record with no content key can't prove which bytes it belongs to, so a
 * missing file still clears it, same as iOS
 */
internal fun storedFileVerdict(validPath: Boolean, fileThere: Boolean, contentBound: Boolean): StoredFileVerdict = when {
    !validPath -> StoredFileVerdict.REJECT
    fileThere -> StoredFileVerdict.RESTORE
    contentBound -> StoredFileVerdict.KEEP_UNAVAILABLE
    else -> StoredFileVerdict.REJECT
}

/**
 * The stored PDF's bytes against the session's contentKey (OD-F4). Memoised per
 * path + size + mtime + file key so a 40 MB sheet gets hashed once a process, any
 * change to the bytes changes one of those. Off main, it can hash the file
 */
internal object PdfStoredFile {
    private data class Stamp(val path: String, val size: Long, val modified: Long, val fileKey: String)

    private val memo = HashMap<Stamp, String>()
    /** one lock per file path: a cold restore asks from 3 places at once, only the first hashes (AND-R2-3) */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Any>()
    /** what does the hashing, tests swap in a counting one */
    @Volatile internal var hasher: (File) -> String? = PdfCalibrationIdentity::contentKey

    private fun stamp(file: File): Stamp? = runCatching {
        val a = java.nio.file.Files.readAttributes(
            file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS,
        )
        if (!a.isRegularFile) return null
        Stamp(file.canonicalPath, a.size(), a.lastModifiedTime().toMillis(), a.fileKey()?.toString().orEmpty())
    }.getOrNull()

    fun contentKey(file: File): String? {
        val before = stamp(file) ?: return null
        synchronized(memo) { memo[before] }?.let { return it }
        // single flight per path. whoever waited here finds the memo filled when they get in
        val lock = inFlight.computeIfAbsent(before.path) { Any() }
        synchronized(lock) {
            val now = stamp(file) ?: return null
            synchronized(memo) { memo[now] }?.let { return it }
            val key = hasher(file) ?: return null
            // only trust it if nothing moved under us while hashing
            if (stamp(file) == now) synchronized(memo) { memo[now] = key }
            return key
        }
    }

    /** true only when the file is there and hashes to [expected] */
    fun matches(file: File, expected: String): Boolean = contentKey(file) == expected

    /** the import worker just hashed it, no need to do 40 MB again on first paint */
    fun remember(file: File, key: String) {
        stamp(file)?.let { synchronized(memo) { memo[it] = key } }
    }
}

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

/** what [PdfSessionStore.loadSession] found */
sealed class PdfSessionLoad {
    data class Ready(val source: PdfMapSource) : PdfSessionLoad()

    /**
     * Schema 1, or a schema 2 georef that won't decode: [PdfSessionStore.migrate]
     * has to open the PDF. [stored] is the sealed blob this came from, so the
     * migrated copy never lands on top of a newer session.
     */
    class NeedsMigration internal constructor(
        internal val dto: PersistedPdfSource,
        val file: File,
        internal val stored: String?,
    ) : PdfSessionLoad()
}

/**
 * A bake record we'd have written: plain .mbtiles name, 64 hex bake key, sane tile size and
 * zooms. Same rule as iOS PDFSessionStore.validBake
 */
internal fun isValidBakeRecord(b: PersistedPdfBake): Boolean {
    val name = b.fileName
    // D9: only the tacmap-bake-<id>.mbtiles form we publish, same rule the sweep and Remove use
    return name.isNotEmpty() && name == File(name).name && '/' !in name && '\\' !in name &&
        ManagedImportedMapFileLifecycle.isGeneratedBakeName(name) &&
        b.bakeKey.length == 64 && b.bakeKey.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } &&
        b.tilePx in 16..1024 &&
        b.minZoom >= 0 && b.minZoom <= b.maxZoom && b.maxZoom <= com.tacmap.map.render.pdf.PdfZoomPolicy.MAX_ZOOM_CAP &&
        b.bytes >= 0
}

/** what [PdfSessionStore.readBakeRecord] found. only a Read lets the sweep delete anything */
internal sealed class PdfBakeRecordRead {
    /** the bake names an authoritative read vouches for (the library can name one per PDF) */
    data class Read(val fileNames: Set<String>) : PdfBakeRecordRead() {
        constructor(fileName: String?) : this(setOfNotNull(fileName))
    }
    data object Unreadable : PdfBakeRecordRead()
}

private val canonicalJson = Json { ignoreUnknownKeys = true }

internal fun canonicalGeorefJson(p: PersistedGeoreference): String = canonicalJson.encodeToString(p)

/** canonical json of a georef: what the bake key, the tile cache key and attachBake compare */
fun PdfGeoreference.canonicalJson(): String = canonicalGeorefJson(PdfGeoreferenceCodec.encode(this))

/** fiduciaries from the content-keyed library, v1 ones still in renderer space */
data class StoredFiduciaries(val fids: List<Fiduciary>, val rawPageSpace: Boolean)

@Serializable
internal data class PersistedPdfSource(
    /** 1 = page -> lon/lat affine era (missing in those blobs), 2 = plan 02 georef */
    val schemaVersion: Int = 1,
    val fileName: String,
    val displayName: String,
    /** PdfRenderer int size; v1 page points are relative to this */
    val pageWidth: Int = 0,
    val pageHeight: Int = 0,
    val sourceKind: String? = null,
    val calibrationKind: String = "fiduciaries",
    val calibrationCrs: String? = null,
    val calibration: PersistedCalibration? = null,
    /** written in v2 too so an older build can still decode the blob and fail closed */
    val coverage: Wgs84Bounds? = null,
    val georef: PersistedGeoreference? = null,
    val provisional: PersistedGeoreference? = null,
    val geometry: PdfPageGeometry? = null,
    val georefIssue: String? = null,
    /** WP2: crash guard key, a random uuid. minted on first load when an older blob lacks it */
    val renderGuardToken: String? = null,
    /** sha256 of the pdf bytes, worked out once on the import worker */
    val contentKey: String? = null,
    /** Generate Offline Tiles output that goes with this exact georef */
    val bake: PersistedPdfBake? = null,
)

@Serializable
internal data class PersistedCalibration(
    val fids: List<Fiduciary>,
    /** v1 only: the old page -> lon/lat affine, ignored now */
    val transform: AffineTransform2D? = null,
    /** "raw" for schema 2 raw user space points, missing = v1 renderer space */
    val pageSpace: String? = null,
)
