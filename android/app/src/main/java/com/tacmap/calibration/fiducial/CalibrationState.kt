package com.tacmap.calibration.fiducial

import com.tacmap.calibration.GeoDatum
import com.tacmap.calibration.GeoDatums
import com.tacmap.calibration.PagePoint
import kotlinx.serialization.Serializable
import java.util.UUID

/** which PDF page a calibration belongs to. contentKey + pageIndex is the draft key */
data class CalibrationTarget(
    val entryId: String,
    val contentKey: String,
    val pageIndex: Int,
    val pageBox: List<PagePoint>,
    val rotate: Int,
) {
    val draftKey: String get() = draftKey(contentKey, pageIndex)

    fun sheet(datum: GeoDatum): CalibrationSheet = CalibrationSheet(datum, pageBox, rotate, pageIndex)

    companion object {
        fun draftKey(contentKey: String, pageIndex: Int) = "$contentKey#$pageIndex"
    }
}

sealed class CalibrationEdit {
    data class Add(
        val page: PagePoint,
        val input: String,
        val reference: CalibrationReference,
        val kind: CalibrationPointKind,
        val datumOverride: String? = null,
        val label: String? = null,
        val canonical: String? = null,
        val id: String = UUID.randomUUID().toString(),
    ) : CalibrationEdit()

    data class Move(val id: String, val page: PagePoint) : CalibrationEdit()

    data class EditReference(
        val id: String,
        val input: String,
        val reference: CalibrationReference,
        val kind: CalibrationPointKind,
        val datumOverride: String? = null,
        val label: String? = null,
        val canonical: String? = null,
        /** set when the typed point also moved ("move to crosshair" inside the card) */
        val page: PagePoint? = null,
    ) : CalibrationEdit()

    data class Delete(val id: String) : CalibrationEdit()
    data class SetDatum(val datumId: String) : CalibrationEdit()
}

/**
 * The calibration being built. Point numbers come off [nextNumber] and never get
 * reused or renumbered (a delete leaves a gap). Mutations are pure; the
 * controller does undo, refit and the draft write around them.
 */
data class CalibrationState(
    val target: CalibrationTarget,
    val datumId: String?,
    val points: List<CalibrationPoint> = emptyList(),
    val nextNumber: Int = 1,
) {
    val datum: GeoDatum get() = datumId?.let(GeoDatums::byId) ?: GeoDatums.WGS84

    val sheet: CalibrationSheet get() = target.sheet(datum)

    val sortedPoints: List<CalibrationPoint> get() = points.sortedBy { it.number }

    fun point(id: String): CalibrationPoint? = points.firstOrNull { it.id == id }

    /** null when the edit can't apply (full, unknown id, unknown datum) */
    fun applying(edit: CalibrationEdit): CalibrationState? = when (edit) {
        is CalibrationEdit.Add -> {
            if (points.size >= MAX_POINTS) {
                null
            } else {
                copy(
                    points = points + CalibrationPoint(
                        id = edit.id,
                        number = nextNumber,
                        page = StoredPagePoint.of(edit.page),
                        input = edit.input,
                        reference = edit.reference,
                        kind = edit.kind,
                        datumOverride = edit.datumOverride,
                        label = edit.label,
                        canonical = edit.canonical,
                    ),
                    nextNumber = nextNumber + 1,
                )
            }
        }
        is CalibrationEdit.Move -> point(edit.id)?.let { p ->
            copy(points = points.map { if (it.id == p.id) it.copy(page = StoredPagePoint.of(edit.page)) else it })
        }
        is CalibrationEdit.EditReference -> point(edit.id)?.let { p ->
            copy(points = points.map {
                if (it.id != p.id) it else it.copy(
                    input = edit.input,
                    reference = edit.reference,
                    kind = edit.kind,
                    datumOverride = edit.datumOverride,
                    label = edit.label,
                    canonical = edit.canonical,
                    page = edit.page?.let(StoredPagePoint::of) ?: it.page,
                )
            })
        }
        is CalibrationEdit.Delete -> point(edit.id)?.let { p -> copy(points = points - p) }
        is CalibrationEdit.SetDatum -> GeoDatums.byId(edit.datumId)?.let { copy(datumId = it.id) }
    }

    companion object {
        const val MAX_POINTS = 50
        const val UNDO_DEPTH = 50
    }
}

/** last-in first-out with a cap, the oldest step falls off the bottom */
class BoundedUndoStack<T>(private val depth: Int = CalibrationState.UNDO_DEPTH) {
    private val items = ArrayDeque<T>()

    val size: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()

    fun push(item: T) {
        items.addLast(item)
        while (items.size > depth) items.removeFirst()
    }

    fun pop(): T? = items.removeLastOrNull()

    fun clear() = items.clear()
}

/** unsaved typed entry is NOT persisted, only where it's going */
@Serializable
data class PendingDraftPoint(
    val page: StoredPagePoint,
    val editingId: String? = null,
)

/** contract s8.1 draft, one per contentKey#pageIndex in the sealed drafts file */
@Serializable
data class CalibrationDraft(
    val contentKey: String,
    val pageIndex: Int,
    val entryId: String,
    val datumId: String? = null,
    val points: List<CalibrationPoint> = emptyList(),
    val nextNumber: Int = 1,
    val pending: PendingDraftPoint? = null,
    val active: Boolean = false,
    val updatedAtMs: Long = 0L,
) {
    val key: String get() = CalibrationTarget.draftKey(contentKey, pageIndex)

    /** same points + datum as [state]? (pending + timestamps don't count) */
    fun sameContentAs(datumId: String?, points: List<CalibrationPoint>): Boolean =
        this.datumId == datumId && this.points == points
}
