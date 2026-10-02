package com.tacmap.calibration

import com.tacmap.calibration.fiducial.CalibrationDraft
import com.tacmap.util.SafeStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** what the controller needs from draft storage, a fake slots in for the JVM tests */
internal interface CalibrationDraftStorage {
    /** null if absent, unreadable, or keyed to a different file/page (that one gets deleted) */
    fun load(key: String): CalibrationDraft?

    /** synchronous, false when the key's locked or the disk said no */
    fun save(draft: CalibrationDraft): Boolean
    fun delete(key: String): Boolean
    fun all(): List<CalibrationDraft>
}

/**
 * Contract s8.1: filesDir/calibration_drafts.json, sealed. One draft per
 * contentKey#pageIndex, max 16 kept by updatedAtMs. Writes are synchronous on
 * purpose: MainActivity locks the mission key on every onPause, a deferred write
 * could land after that (fails auth-bound, or quietly re-unwraps device-bound).
 */
internal class CalibrationDraftStore(filesDir: File) : CalibrationDraftStorage {
    private val file = File(filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Document(val schemaVersion: Int = 1, val drafts: Map<String, CalibrationDraft> = emptyMap())

    private sealed class Read {
        data class Ok(val doc: Document) : Read()
        data object Unavailable : Read()
    }

    private fun read(): Read = when (val r = SafeStore.readOrQuarantine(file, LABEL) { json.decodeFromString<Document>(it) }) {
        is SafeStore.LoadResult.Loaded -> Read.Ok(r.value)
        // a corrupt file is quarantined by SafeStore, carry on with nothing rather than lose new points
        SafeStore.LoadResult.Empty, is SafeStore.LoadResult.Corrupt -> Read.Ok(Document())
        is SafeStore.LoadResult.Locked -> Read.Unavailable
    }

    private fun write(doc: Document): Boolean = runCatching {
        SafeStore.writeAtomically(file, LABEL, json.encodeToString(doc))
    }.onFailure { QuietLog.w(TAG, "calibration draft write failed") }.isSuccess

    @Synchronized
    override fun load(key: String): CalibrationDraft? {
        val doc = (read() as? Read.Ok)?.doc ?: return null
        val d = doc.drafts[key] ?: return null
        if (d.key != key) {
            // D5-10: a draft never applies to another file or page, drop it
            write(doc.copy(drafts = doc.drafts - key))
            return null
        }
        return d
    }

    @Synchronized
    override fun save(draft: CalibrationDraft): Boolean {
        val doc = (read() as? Read.Ok)?.doc ?: return false
        // LRU: the one being saved always stays, plus the 15 most recently touched others
        val others = doc.drafts.values.filter { it.key != draft.key }
            .sortedByDescending { it.updatedAtMs }
            .take(ImportLimits.MAX_DRAFTS - 1)
        return write(doc.copy(drafts = (others + draft).associateBy { it.key }))
    }

    @Synchronized
    override fun delete(key: String): Boolean {
        val doc = (read() as? Read.Ok)?.doc ?: return false
        if (key !in doc.drafts) return true
        return write(doc.copy(drafts = doc.drafts - key))
    }

    @Synchronized
    override fun all(): List<CalibrationDraft> = ((read() as? Read.Ok)?.doc?.drafts?.values?.toList() ?: emptyList())
        .filter { it.key.isNotEmpty() }

    /** reconcile: drop drafts whose file isn't in the library any more */
    @Synchronized
    fun prune(keepContentKeys: Set<String>): Boolean {
        val doc = (read() as? Read.Ok)?.doc ?: return false
        val kept = doc.drafts.filter { (k, d) -> d.contentKey in keepContentKeys && d.key == k }
        if (kept.size == doc.drafts.size) return true
        return write(doc.copy(drafts = kept))
    }

    companion object {
        const val FILE_NAME = "calibration_drafts.json"
        const val LABEL = "calibration/drafts_v1"
        private const val TAG = "CalibrationDrafts"
    }
}
