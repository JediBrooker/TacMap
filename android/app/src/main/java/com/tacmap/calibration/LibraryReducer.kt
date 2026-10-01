package com.tacmap.calibration

/**
 * One library transition (contract s8.2 commit points). Each one is exactly one
 * sealed write of the candidate state the reducer builds, then publication.
 * Same cases + rules as iOS LibraryReducer.
 */
internal sealed class LibraryTransition {
    data class SelectOnline(val style: String) : LibraryTransition()
    data class ActivateEntry(val id: String) : LibraryTransition()
    data class AddEntry(val entry: ImportedMapEntry, val activate: Boolean) : LibraryTransition()
    data class CommitCalibration(
        val id: String,
        val manual: ManualCalibration,
        val contentKey: String,
        val pageIndex: Int,
    ) : LibraryTransition()
    data class RevertToEmbedded(val id: String) : LibraryTransition()
    /** P1 "Choose page...": the new page with its own georef/issue, manual dropped */
    data class ChangePage(
        val id: String,
        val pageIndex: Int,
        val rotate: Int,
        val pageBox: List<List<Double>>,
        val embedded: PersistedGeoreference?,
        val embeddedIssue: String?,
        val geometry: PdfPageGeometry?,
    ) : LibraryTransition()
    /**
     * E9: an unavailable entry re-imported with the exact same bytes points at the new
     * copy. calibration, page and bake record all stay, they were made for these bytes
     */
    data class Relink(
        val id: String,
        val contentKey: String,
        val fileName: String,
        val byteCount: Long,
        val fileModifiedAtMs: Long,
    ) : LibraryTransition()
    /**
     * a derived MBTiles under its PDF, shown straight away. Only legacy data has these now,
     * a WP2 bake rides on its PDF entry instead ([AttachBake]); kept for the reducer tests
     */
    data class AddDerived(val entry: ImportedMapEntry) : LibraryTransition()
    data class DeleteEntry(val id: String) : LibraryTransition()
    /**
     * WP2 bake publish (M3): only onto the entry it was made from, same bytes, same guard
     * token, and its key has to match the entry's effective georef right now
     */
    data class AttachBake(
        val id: String,
        val contentKey: String?,
        val renderGuardToken: String,
        val bake: PersistedPdfBake,
    ) : LibraryTransition()
    /** Remove Offline Tiles (M6): only clears the record it was asked about */
    data class ClearBake(val id: String, val fileName: String) : LibraryTransition()
    /**
     * D7: the render session verified the bytes against contentKey, so the file's new size +
     * mtime are the entry's now. Only for the same bytes
     */
    data class RefreshFileStamp(
        val id: String,
        val contentKey: String,
        val byteCount: Long,
        val fileModifiedAtMs: Long,
    ) : LibraryTransition()
}

internal enum class LibraryTransitionError {
    UNKNOWN_ENTRY, NOT_ACTIVATABLE, TARGET_MISMATCH, LIBRARY_FULL,
    /** the entry moved on since the bake started (recalibrated, reimported, gone) */
    SOURCE_CHANGED,
    /** a bake record we'd never have written */
    INVALID_BAKE,
}

internal sealed class LibraryReduction {
    data class Ok(val state: LibraryState, val removed: List<ImportedMapEntry> = emptyList()) : LibraryReduction()
    data class Rejected(val error: LibraryTransitionError) : LibraryReduction()
}

/** builds the candidate state for one transition. pure, no IO, JVM tested */
internal object LibraryReducer {
    private fun bad(e: LibraryTransitionError) = LibraryReduction.Rejected(e)

    private fun LibraryState.onlinePreferred(): ActiveRef = ActiveRef.online(preferredOnlineStyle)

    private fun LibraryState.updatePdf(id: String, f: (PdfEntryInfo) -> PdfEntryInfo): LibraryState? {
        val e = entry(id) ?: return null
        val pdf = e.pdf ?: return null
        return replacing(e.copy(pdf = f(pdf)))
    }

    fun apply(t: LibraryTransition, s: LibraryState): LibraryReduction = when (t) {
        is LibraryTransition.SelectOnline ->
            LibraryReduction.Ok(s.copy(active = ActiveRef.online(t.style), preferredOnlineStyle = t.style))

        is LibraryTransition.ActivateEntry -> {
            val e = s.entry(t.id)
            when {
                e == null -> bad(LibraryTransitionError.UNKNOWN_ENTRY)
                // the file check is the caller's (it needs IO); here only what the entry itself allows
                !LibraryEntryRules.canBeDurableActive(LibraryEntryRules.state(LibraryEntryRules.facts(e), EntryFileStatus.OK)) ->
                    bad(LibraryTransitionError.NOT_ACTIVATABLE)
                else -> LibraryReduction.Ok(s.copy(active = ActiveRef.entry(t.id)))
            }
        }

        is LibraryTransition.AddEntry -> {
            if (s.entries.size >= ImportLimits.MAX_LIBRARY_ENTRIES && t.entry.derivedFromId == null) {
                bad(LibraryTransitionError.LIBRARY_FULL)
            } else {
                LibraryReduction.Ok(
                    s.copy(
                        entries = s.entries.filterNot { it.id == t.entry.id } + t.entry,
                        active = if (t.activate) ActiveRef.entry(t.entry.id) else s.active,
                    )
                )
            }
        }

        is LibraryTransition.CommitCalibration -> {
            val e = s.entry(t.id)
            when {
                e?.pdf == null -> bad(LibraryTransitionError.UNKNOWN_ENTRY)
                // never put a calibration on another file or page (D5-10)
                e.contentKey != t.contentKey || e.pdf.pageIndex != t.pageIndex -> bad(LibraryTransitionError.TARGET_MISMATCH)
                // a new georef makes the old bake wrong, the reconcile reaps the file (M4)
                else -> LibraryReduction.Ok(
                    s.replacing(e.copy(pdf = e.pdf.copy(manual = t.manual, bake = null))).copy(active = ActiveRef.entry(t.id))
                )
            }
        }

        is LibraryTransition.RevertToEmbedded -> {
            val next = s.updatePdf(t.id) { it.copy(manual = null, bake = null) }
            if (next == null) {
                bad(LibraryTransitionError.UNKNOWN_ENTRY)
            } else {
                val lostGeoref = s.active.entryId == t.id && next.entry(t.id)?.pdf?.embedded == null
                LibraryReduction.Ok(if (lostGeoref) next.copy(active = s.onlinePreferred()) else next)
            }
        }

        is LibraryTransition.ChangePage -> {
            val next = s.updatePdf(t.id) {
                it.copy(
                    pageIndex = t.pageIndex,
                    rotate = t.rotate,
                    pageBox = t.pageBox,
                    embedded = t.embedded,
                    embeddedIssue = t.embeddedIssue,
                    manual = null,
                    geometry = t.geometry,
                    bake = null,
                )
            }
            if (next == null) {
                bad(LibraryTransitionError.UNKNOWN_ENTRY)
            } else {
                // a page with no georef can't stay the basemap
                val drop = s.active.entryId == t.id && t.embedded == null
                LibraryReduction.Ok(if (drop) next.copy(active = s.onlinePreferred()) else next)
            }
        }

        is LibraryTransition.Relink -> {
            val e = s.entry(t.id)
            when {
                e == null -> bad(LibraryTransitionError.UNKNOWN_ENTRY)
                // only ever the same bytes, anything else is a new map
                e.contentKey != t.contentKey -> bad(LibraryTransitionError.TARGET_MISMATCH)
                else -> LibraryReduction.Ok(
                    s.replacing(e.copy(fileName = t.fileName, byteCount = t.byteCount, fileModifiedAtMs = t.fileModifiedAtMs))
                )
            }
        }

        is LibraryTransition.AddDerived -> {
            val parent = t.entry.derivedFromId
            if (parent == null || s.entry(parent) == null) {
                bad(LibraryTransitionError.UNKNOWN_ENTRY)
            } else {
                LibraryReduction.Ok(s.copy(entries = s.entries + t.entry, active = ActiveRef.entry(t.entry.id)))
            }
        }

        is LibraryTransition.DeleteEntry -> {
            if (s.entry(t.id) == null) {
                bad(LibraryTransitionError.UNKNOWN_ENTRY)
            } else {
                val removed = s.withDerived(t.id)
                val ids = removed.map { it.id }.toSet()
                val next = s.copy(
                    entries = s.entries.filterNot { it.id in ids },
                    active = if (s.active.entryId in ids) s.onlinePreferred() else s.active,
                )
                LibraryReduction.Ok(next, removed)
            }
        }

        is LibraryTransition.AttachBake -> {
            val e = s.entry(t.id)
            val pdf = e?.pdf
            when {
                e == null || pdf == null -> bad(LibraryTransitionError.SOURCE_CHANGED)
                e.contentKey != t.contentKey || e.renderGuardToken != t.renderGuardToken -> bad(LibraryTransitionError.SOURCE_CHANGED)
                !isValidBakeRecord(t.bake) -> bad(LibraryTransitionError.INVALID_BAKE)
                bakeKeyFor(pdf, t.bake.tilePx) != t.bake.bakeKey -> bad(LibraryTransitionError.SOURCE_CHANGED)
                else -> LibraryReduction.Ok(s.replacing(e.copy(pdf = pdf.copy(bake = t.bake))))
            }
        }

        is LibraryTransition.ClearBake -> {
            val e = s.entry(t.id)
            val pdf = e?.pdf
            if (e == null || pdf?.bake?.fileName != t.fileName) bad(LibraryTransitionError.SOURCE_CHANGED)
            else LibraryReduction.Ok(s.replacing(e.copy(pdf = pdf.copy(bake = null))))
        }

        is LibraryTransition.RefreshFileStamp -> {
            val e = s.entry(t.id)
            when {
                e == null -> bad(LibraryTransitionError.UNKNOWN_ENTRY)
                e.contentKey != t.contentKey -> bad(LibraryTransitionError.TARGET_MISMATCH)
                else -> LibraryReduction.Ok(s.replacing(e.copy(byteCount = t.byteCount, fileModifiedAtMs = t.fileModifiedAtMs)))
            }
        }
    }

    /** the bake key the entry's effective georef gives at [tilePx], null without one */
    fun bakeKeyFor(pdf: PdfEntryInfo, tilePx: Int): String? {
        val g = (pdf.manual?.georef ?: pdf.embedded)?.let(PdfGeoreferenceCodec::decode) ?: return null
        return com.tacmap.map.render.pdf.PdfBakePlan.bakeKey(g.canonicalJson(), tilePx)
    }
}
