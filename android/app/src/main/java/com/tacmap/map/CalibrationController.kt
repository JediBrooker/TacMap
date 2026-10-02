package com.tacmap.map

import com.tacmap.calibration.CalibrationDraftStorage
import com.tacmap.calibration.GeoDatums
import com.tacmap.calibration.GeorefOrigin
import com.tacmap.calibration.ManualCalibration
import com.tacmap.calibration.PagePoint
import com.tacmap.calibration.PdfGeoreference
import com.tacmap.calibration.PdfGeoreferenceCodec
import com.tacmap.calibration.fiducial.BoundedUndoStack
import com.tacmap.calibration.fiducial.CalibrationCapture
import com.tacmap.calibration.fiducial.CalibrationDraft
import com.tacmap.calibration.fiducial.CalibrationEdit
import com.tacmap.calibration.fiducial.CalibrationFitReport
import com.tacmap.calibration.fiducial.CalibrationPoint
import com.tacmap.calibration.fiducial.CalibrationPointKind
import com.tacmap.calibration.fiducial.CalibrationReference
import com.tacmap.calibration.fiducial.CalibrationState
import com.tacmap.calibration.fiducial.CalibrationTarget
import com.tacmap.calibration.fiducial.EntryCheck
import com.tacmap.calibration.fiducial.FitReport
import com.tacmap.calibration.fiducial.Finishability
import com.tacmap.calibration.fiducial.PendingDraftPoint
import com.tacmap.calibration.fiducial.StoredPagePoint
import com.tacmap.calibration.fiducial.WGS84_OVERRIDE
import com.tacmap.calibration.fiducial.resolved
import com.tacmap.mgrs.CoordinateInputParser
import com.tacmap.mgrs.CoordinateParseContext
import com.tacmap.mgrs.CoordinateSource
import com.tacmap.mgrs.GridAnchor
import com.tacmap.mgrs.ParseOutcome
import com.tacmap.mgrs.ParsedReference
import com.tacmap.calibration.GeoCrs
import com.tacmap.calibration.Wgs84Coordinate
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.Locale
import kotlin.math.abs

/** the georef the map draws right now + a generation that bumps on every swap (contract s7.1/7.2) */
internal data class CalibrationDisplay(
    val georef: PdfGeoreference,
    val generation: Long,
    val isFit: Boolean,
) {
    val provisional: Boolean get() = georef.origin == GeorefOrigin.PROVISIONAL
}

internal sealed class CalibrationPhase {
    data object Placing : CalibrationPhase()
    data class Entering(val page: PagePoint, val editingId: String?) : CalibrationPhase()
    data class Moving(val id: String) : CalibrationPhase()
    data object DatumSheet : CalibrationPhase()
    data object PointsSheet : CalibrationPhase()
    data object LeaveDialog : CalibrationPhase()
    data object FinishConfirm : CalibrationPhase()
    data object ResumePrompt : CalibrationPhase()
    data object SaveFailed : CalibrationPhase()
}

/** the open entry card. text isn't persisted (contract s8.1) */
internal data class EntryUi(
    val text: String = "",
    val kind: CalibrationPointKind = CalibrationPointKind.INTERSECTION,
    val label: String = "",
    val parse: ParseOutcome? = null,
    val check: EntryCheck? = null,
    /** the text came off a GPS fix: lat/lon are WGS84, not the sheet datum */
    val gpsOverride: Boolean = false,
) {
    val parsed: ParsedReference? get() = (parse as? ParseOutcome.Ok)?.reference
    val canSave: Boolean get() = parsed != null
}

internal sealed class CalibrationEvent {
    data class PointDeleted(val number: Int) : CalibrationEvent()
    data class DatumChanged(val datumId: String) : CalibrationEvent()
    data class Resumed(val points: Int) : CalibrationEvent()
    data object Paused : CalibrationEvent()
    data class Done(val points: Int, val rmsM: Double) : CalibrationEvent()
    data object DoneExact : CalibrationEvent()
    data object MaxPoints : CalibrationEvent()
}

/** everything start() needs from the library side */
internal data class CalibrationStart(
    val target: CalibrationTarget,
    val entryName: String,
    /** provisional (plain PDF) or the effective georef, crop already widened to the page box */
    val base: PdfGeoreference,
    /** the entry's saved effective georef, the PREVIEW tag compares against it */
    val savedEffective: PdfGeoreference?,
    /** saved manual calibration's datum + points, the seed */
    val seedDatumId: String?,
    val seedPoints: List<CalibrationPoint>,
    /** a GeoPDF preselects its own datum */
    val embeddedDatumId: String?,
    val draft: CalibrationDraft?,
    val isPreview: Boolean,
    /** E3: re-entered at launch off an active draft */
    val resumeSilently: Boolean = false,
)

internal data class CalibrationUiState(
    val target: CalibrationTarget,
    val entryName: String,
    val phase: CalibrationPhase,
    val state: CalibrationState,
    val report: FitReport,
    val display: CalibrationDisplay,
    val base: PdfGeoreference,
    val savedEffective: PdfGeoreference?,
    val selectedId: String? = null,
    val entry: EntryUi? = null,
    val draftUnsaved: Boolean = false,
    val canUndo: Boolean = false,
    val gridOn: Boolean = false,
    val isPreview: Boolean = false,
    /** the draft the resume prompt is about */
    val resumeDraft: CalibrationDraft? = null,
    val seedDatumId: String? = null,
    val seedPoints: List<CalibrationPoint> = emptyList(),
) {
    val dirty: Boolean get() = state.datumId != seedDatumId || state.points != seedPoints

    /** header tag: the map shows something other than what's saved */
    val showsPreview: Boolean get() = display.isFit && display.georef.affine != savedEffective?.affine

    val pendingPage: PagePoint? get() = (phase as? CalibrationPhase.Entering)?.page

    val selected: CalibrationPoint? get() = selectedId?.let(state::point)
}

/**
 * The calibration state machine (WP4 contract s2), owned by MapViewModel so it
 * survives MapScreen being torn down on every pause. Pure, the map + library
 * side goes through MapViewModel. Every mutation: push undo, apply, refit, new
 * display, synchronous draft write.
 */
internal class CalibrationController(
    private val drafts: CalibrationDraftStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<CalibrationUiState?>(null)
    val state: StateFlow<CalibrationUiState?> = _state.asStateFlow()

    // a channel, not a shared flow: the E3 resume fires from VM init and a suspend can land
    // while MapScreen is torn down for a pause, both before anyone's collecting. Queued
    // until MapScreen is back so the toast still shows
    private val _events = Channel<CalibrationEvent>(16, BufferOverflow.DROP_OLDEST)
    val events: Flow<CalibrationEvent> = _events.receiveAsFlow()

    private val undo = BoundedUndoStack<CalibrationState>()
    private var generation = 0L

    val isActive: Boolean get() = _state.value != null
    val targetEntryId: String? get() = _state.value?.target?.entryId

    private fun update(block: (CalibrationUiState) -> CalibrationUiState) {
        _state.value = _state.value?.let(block)
    }

    // ------------------------------------------------------------------ start / resume

    fun start(p: CalibrationStart): Boolean {
        if (isActive) return false
        undo.clear()
        val seed = CalibrationState(
            target = p.target,
            datumId = p.seedDatumId ?: p.embeddedDatumId,
            points = p.seedPoints,
            nextNumber = (p.seedPoints.maxOfOrNull { it.number } ?: 0) + 1,
        )
        val draft = p.draft?.takeIf { it.key == p.target.draftKey }
        val silent = draft != null && (draft.active || p.resumeSilently)
        val prompt = draft != null && !silent && !draft.sameContentAs(seed.datumId, seed.points)
        val initial = if (silent) fromDraft(seed, draft!!) else seed
        val report = CalibrationFitReport.evaluate(initial.sheet, initial.points)
        val display = displayFor(report, p.base, null)
        _state.value = CalibrationUiState(
            target = p.target,
            entryName = p.entryName,
            phase = when {
                prompt -> CalibrationPhase.ResumePrompt
                initial.datumId == null -> CalibrationPhase.DatumSheet
                else -> CalibrationPhase.Placing
            },
            state = initial,
            report = report,
            display = display,
            base = p.base,
            savedEffective = p.savedEffective,
            isPreview = p.isPreview,
            resumeDraft = draft.takeIf { prompt },
            seedDatumId = seed.datumId,
            seedPoints = seed.points,
        )
        if (silent) {
            // reopen the card where it was when the app went away (C6, Android)
            val pend = draft!!.pending
            pend?.let {
                update { s -> s.copy(phase = CalibrationPhase.Entering(it.page.page, it.editingId), entry = EntryUi()) }
            }
            // a resume is active from here, written straight away (C6, iOS)
            writeDraft(pending = pend)
            // nothing to say "resumed" about at 0 points
            if (initial.points.isNotEmpty()) _events.trySend(CalibrationEvent.Resumed(initial.points.size))
        }
        return true
    }

    private fun fromDraft(seed: CalibrationState, d: CalibrationDraft): CalibrationState = seed.copy(
        datumId = d.datumId ?: seed.datumId,
        points = d.points,
        nextNumber = maxOf(d.nextNumber, (d.points.maxOfOrNull { it.number } ?: 0) + 1),
    )

    fun resume() {
        val s = _state.value ?: return
        val d = s.resumeDraft ?: return
        val next = fromDraft(s.state, d)
        // Resume makes it the active draft at once, before any edit (C6)
        applyState(next, pushUndo = false, writeDraft = true)
        update { it.copy(resumeDraft = null, phase = if (next.datumId == null) CalibrationPhase.DatumSheet else CalibrationPhase.Placing) }
    }

    fun startOver() {
        val s = _state.value ?: return
        s.resumeDraft?.let { drafts.delete(it.key) }
        update { it.copy(resumeDraft = null, phase = if (it.state.datumId == null) CalibrationPhase.DatumSheet else CalibrationPhase.Placing) }
    }

    // ------------------------------------------------------------------ mutations

    private fun displayFor(report: FitReport, base: PdfGeoreference, current: CalibrationDisplay?): CalibrationDisplay {
        val fit = report.usableGeoref
        if (fit == null) {
            if (current != null && !current.isFit) return current
            return CalibrationDisplay(base, ++generation, isFit = false)
        }
        if (current != null && current.isFit && current.georef.affine == fit.affine && current.georef.crs == fit.crs) return current
        return CalibrationDisplay(fit, ++generation, isFit = true)
    }

    private fun applyState(next: CalibrationState, pushUndo: Boolean, writeDraft: Boolean, previous: CalibrationState? = null) {
        val s = _state.value ?: return
        if (pushUndo) undo.push(previous ?: s.state)
        val report = CalibrationFitReport.evaluate(next.sheet, next.points)
        val display = displayFor(report, s.base, s.display)
        _state.value = s.copy(state = next, report = report, display = display, canUndo = !undo.isEmpty)
        if (writeDraft) writeDraft(pending = null)
    }

    /** contract s2.5: undo push, apply, refit, display, draft. false if the edit didn't apply */
    fun mutate(edit: CalibrationEdit): Boolean {
        val s = _state.value ?: return false
        if (edit is CalibrationEdit.Add && s.state.points.size >= CalibrationState.MAX_POINTS) {
            _events.trySend(CalibrationEvent.MaxPoints)
            return false
        }
        val next = s.state.applying(edit) ?: return false
        applyState(next, pushUndo = true, writeDraft = true, previous = s.state)
        return true
    }

    fun undo(): Boolean {
        val prev = undo.pop() ?: return false
        val s = _state.value ?: return false
        val report = CalibrationFitReport.evaluate(prev.sheet, prev.points)
        _state.value = s.copy(
            state = prev,
            report = report,
            display = displayFor(report, s.base, s.display),
            canUndo = !undo.isEmpty,
            selectedId = s.selectedId?.takeIf { prev.point(it) != null },
        )
        writeDraft(pending = null)
        return true
    }

    // ------------------------------------------------------------------ datum

    fun openDatumSheet() = update { it.copy(phase = CalibrationPhase.DatumSheet) }

    fun chooseDatum(datumId: String) {
        val s = _state.value ?: return
        val id = GeoDatums.byId(datumId)?.id ?: return
        if (s.state.datumId == null) {
            // the first pick isn't a change, nothing to undo back to. at 0 points it's part of
            // the start seed (C5): not dirty, no draft, no leave dialog
            applyState(s.state.copy(datumId = id), pushUndo = false, writeDraft = false)
            if (s.state.points.isEmpty()) update { it.copy(seedDatumId = id) }
        } else if (s.state.datumId != id) {
            if (mutate(CalibrationEdit.SetDatum(id))) _events.trySend(CalibrationEvent.DatumChanged(id))
        }
        update { it.copy(phase = CalibrationPhase.Placing) }
    }

    /** sheet dismissed: still nothing chosen means WGS84, same as "not sure" */
    fun dismissDatumSheet() {
        val s = _state.value ?: return
        if (s.state.datumId == null) chooseDatum(GeoDatums.WGS84.id) else update { it.copy(phase = CalibrationPhase.Placing) }
    }

    // ------------------------------------------------------------------ add / entry card

    /** "Add point": the live crosshair through the shown georef, contract s7.4 */
    fun beginAdd(capture: CalibrationCapture?): Boolean {
        val s = _state.value ?: return false
        if (capture == null || !capture.onSheet) return false
        if (s.state.points.size >= CalibrationState.MAX_POINTS) {
            _events.trySend(CalibrationEvent.MaxPoints)
            return false
        }
        update {
            it.copy(
                phase = CalibrationPhase.Entering(capture.page, null),
                entry = EntryUi(),
                selectedId = null,
            )
        }
        writeDraft(pending = PendingDraftPoint(StoredPagePoint.of(capture.page)))
        return true
    }

    fun beginEdit(id: String) {
        val s = _state.value ?: return
        val p = s.state.point(id) ?: return
        update {
            it.copy(
                phase = CalibrationPhase.Entering(p.pagePoint, id),
                entry = reparse(it, EntryUi(text = p.input, kind = p.kind, label = p.label ?: "", gpsOverride = p.datumOverride == WGS84_OVERRIDE)),
                selectedId = id,
            )
        }
        writeDraft(pending = PendingDraftPoint(p.page, id))
    }

    fun updateEntryText(text: String) = update { s ->
        val e = s.entry ?: return@update s
        // typing over a GPS fill makes it a sheet datum reference again
        s.copy(entry = reparse(s, e.copy(text = text, gpsOverride = e.gpsOverride && text == e.text)))
    }

    fun setEntryKind(kind: CalibrationPointKind) = update { s ->
        val e = s.entry ?: return@update s
        s.copy(entry = reparse(s, e.copy(kind = kind)))
    }

    fun setEntryLabel(label: String) = update { s -> s.entry?.let { s.copy(entry = it.copy(label = label)) } ?: s }

    /** GPS point, only at <= 20 m (Android also wants Precise, the caller checks that) */
    fun useGps(latitude: Double, longitude: Double, accuracyM: Double): Boolean {
        if (!(accuracyM <= com.tacmap.calibration.ImportLimits.GPS_MAX_ACCURACY_M)) return false
        update { s ->
            val e = s.entry ?: return@update s
            val text = String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
            s.copy(entry = reparse(s, e.copy(text = text, gpsOverride = true)))
        }
        return true
    }

    fun moveEntryToCrosshair(capture: CalibrationCapture?) {
        if (capture == null || !capture.onSheet) return
        update { s ->
            val ph = s.phase as? CalibrationPhase.Entering ?: return@update s
            val moved = s.copy(phase = ph.copy(page = capture.page))
            moved.entry?.let { moved.copy(entry = reparse(moved, it)) } ?: moved
        }
        val s = _state.value ?: return
        val ph = s.phase as? CalibrationPhase.Entering ?: return
        writeDraft(pending = PendingDraftPoint(StoredPagePoint.of(ph.page), ph.editingId))
    }

    private fun gridAnchor(s: CalibrationUiState, editingId: String?): GridAnchor? {
        val latest = s.state.points.filter { it.id != editingId && it.reference is CalibrationReference.Grid }
            .maxByOrNull { it.number } ?: return null
        val g = latest.reference as CalibrationReference.Grid
        val square = CoordinateInputParser.squareOf(g.zone, g.easting, g.northing) ?: return null
        val lat = GeoCrs.utm(g.zone, g.south).inverse(g.easting, g.northing, s.state.datum.ellipsoid)?.latitude ?: return null
        val band = CoordinateInputParser.bandOf(lat) ?: return null
        return GridAnchor(g.zone, band, square, latest.number)
    }

    private fun reparse(s: CalibrationUiState, e: EntryUi): EntryUi {
        val ph = s.phase as? CalibrationPhase.Entering
        val page = ph?.page ?: s.state.point(ph?.editingId ?: "")?.pagePoint
        val editingId = ph?.editingId
        if (e.text.isBlank()) return e.copy(parse = null, check = null)
        val predicted = if (!s.display.provisional && page != null) {
            s.display.georef.toWGS84(page.x, page.y)?.let { Wgs84Coordinate(it.latitude, it.longitude) }
        } else {
            null
        }
        val ctx = CoordinateParseContext(
            datum = s.state.datum,
            isFirstPoint = s.state.points.none { it.id != editingId },
            kind = e.kind,
            gridAnchor = gridAnchor(s, editingId),
            predicted = predicted,
        )
        val parse = CoordinateInputParser.parse(e.text, ctx)
        val ok = (parse as? ParseOutcome.Ok)?.reference
        val check = if (ok != null && page != null) {
            val editingNumber = editingId?.let { s.state.point(it)?.number }
            val pending = pendingPoint(ok, e, page, editingNumber ?: s.state.nextNumber)
            CalibrationFitReport.entryCheck(
                sheet = s.state.sheet,
                points = s.state.points,
                editingNumber = editingNumber,
                pending = pending,
                base = s.base.takeIf { it.origin != GeorefOrigin.PROVISIONAL },
                current = s.report,
            )
        } else {
            null
        }
        return e.copy(parse = parse, check = check)
    }

    private fun pendingPoint(r: ParsedReference, e: EntryUi, page: PagePoint, number: Int): CalibrationPoint = CalibrationPoint(
        id = "pending",
        number = number,
        page = StoredPagePoint.of(page),
        input = e.text.trim(),
        reference = r.toReference(),
        kind = r.effectiveKind ?: CalibrationPointKind.INTERSECTION,
        datumOverride = if (e.gpsOverride && r.source == CoordinateSource.LAT_LON) WGS84_OVERRIDE else null,
        label = e.label.trim().ifBlank { null },
        canonical = r.canonical,
    )

    /** Save point / Save anyway */
    fun commitEntry(): Boolean {
        val s = _state.value ?: return false
        val ph = s.phase as? CalibrationPhase.Entering ?: return false
        val e = s.entry ?: return false
        val r = e.parsed ?: return false
        val pt = pendingPoint(r, e, ph.page, 0)
        val edit = if (ph.editingId == null) {
            CalibrationEdit.Add(ph.page, pt.input, pt.reference, pt.kind, pt.datumOverride, pt.label, pt.canonical)
        } else {
            CalibrationEdit.EditReference(ph.editingId, pt.input, pt.reference, pt.kind, pt.datumOverride, pt.label, pt.canonical, ph.page)
        }
        // card closes first so the display may swap generation (s7.1: never while the card is open)
        update { it.copy(phase = CalibrationPhase.Placing, entry = null) }
        val ok = mutate(edit)
        if (!ok) update { it.copy(phase = ph, entry = e) }
        return ok
    }

    fun cancelEntry() {
        val s = _state.value ?: return
        if (s.phase !is CalibrationPhase.Entering) return
        update { it.copy(phase = CalibrationPhase.Placing, entry = null) }
        if (s.dirty || drafts.load(s.target.draftKey) != null) writeDraft(pending = null)
    }

    // ------------------------------------------------------------------ select / move / delete

    fun select(id: String?) = update { s ->
        if (id != null && s.state.point(id) == null) s
        else s.copy(selectedId = id, phase = if (s.phase is CalibrationPhase.PointsSheet) CalibrationPhase.Placing else s.phase)
    }

    fun beginMove(id: String) = update { s -> if (s.state.point(id) == null) s else s.copy(phase = CalibrationPhase.Moving(id), selectedId = id) }

    fun confirmMove(capture: CalibrationCapture?): Boolean {
        val s = _state.value ?: return false
        val ph = s.phase as? CalibrationPhase.Moving ?: return false
        if (capture == null || !capture.onSheet) return false
        update { it.copy(phase = CalibrationPhase.Placing) }
        return mutate(CalibrationEdit.Move(ph.id, capture.page))
    }

    fun cancelMove() = update { s -> if (s.phase is CalibrationPhase.Moving) s.copy(phase = CalibrationPhase.Placing) else s }

    fun delete(id: String): Boolean {
        val s = _state.value ?: return false
        val p = s.state.point(id) ?: return false
        if (!mutate(CalibrationEdit.Delete(id))) return false
        update { it.copy(selectedId = null, phase = if (it.phase is CalibrationPhase.Entering) CalibrationPhase.Placing else it.phase) }
        _events.trySend(CalibrationEvent.PointDeleted(p.number))
        return true
    }

    fun toggleGrid() = update { it.copy(gridOn = !it.gridOn) }

    fun openPoints() = update { it.copy(phase = CalibrationPhase.PointsSheet) }

    fun closeSheet() = update { s ->
        if (s.phase is CalibrationPhase.PointsSheet || s.phase is CalibrationPhase.DatumSheet) s.copy(phase = CalibrationPhase.Placing) else s
    }

    // ------------------------------------------------------------------ finish / leave

    /** true = commit right now (ready). confirm shows the dialog, blocked does nothing */
    fun finishTapped(): Boolean {
        val s = _state.value ?: return false
        return when (s.report.finish) {
            is Finishability.Blocked -> false
            is Finishability.Confirm -> {
                update { it.copy(phase = CalibrationPhase.FinishConfirm) }
                false
            }
            Finishability.Ready -> true
        }
    }

    fun cancelFinish() = update { s -> if (s.phase is CalibrationPhase.FinishConfirm || s.phase is CalibrationPhase.SaveFailed) s.copy(phase = CalibrationPhase.Placing) else s }

    /** contract s2.6: {datumId, points, georef: fit, n, rmsM, grade} */
    fun manualForCommit(): ManualCalibration? {
        val s = _state.value ?: return null
        val g = s.report.usableGeoref ?: return null
        val n = s.report.n
        return ManualCalibration(
            datumId = s.state.datum.id,
            points = s.state.sortedPoints,
            georef = PdfGeoreferenceCodec.encode(g.copy(page = s.target.pageIndex)),
            n = n,
            rmsM = if (n >= 4) s.report.rmsM else null,
            grade = s.report.grade?.code,
            savedAtMs = clock(),
        )
    }

    /** after the one library write landed */
    fun endAfterCommit() {
        val s = _state.value ?: return
        drafts.delete(s.target.draftKey)
        val n = s.report.n
        _events.trySend(if (n >= 4) CalibrationEvent.Done(n, s.report.rmsM ?: 0.0) else CalibrationEvent.DoneExact)
        end()
    }

    /** the library write failed: stay, keep the draft, say so (never a silent no-op) */
    fun commitFailed() = update { it.copy(phase = CalibrationPhase.SaveFailed) }

    /** ✕ / Back. true = the session already ended (nothing to keep) */
    fun leaveTapped(): Boolean {
        val s = _state.value ?: return true
        when (s.phase) {
            is CalibrationPhase.Entering -> { cancelEntry(); return false }
            is CalibrationPhase.Moving -> { cancelMove(); return false }
            CalibrationPhase.PointsSheet, CalibrationPhase.DatumSheet -> { closeSheet(); return false }
            else -> Unit
        }
        if (!s.dirty) {
            // nothing new; a draft from before stays as it was
            drafts.load(s.target.draftKey)?.takeIf { it.active }?.let { drafts.save(it.copy(active = false)) }
            end()
            return true
        }
        update { it.copy(phase = CalibrationPhase.LeaveDialog) }
        return false
    }

    fun continueCalibrating() = update { s -> if (s.phase is CalibrationPhase.LeaveDialog) s.copy(phase = CalibrationPhase.Placing) else s }

    /** Keep points for later (keep = true) or Discard changes */
    fun leave(keep: Boolean) {
        val s = _state.value ?: return
        if (keep) writeDraft(pending = null, active = false) else drafts.delete(s.target.draftKey)
        end()
    }

    /** the map changed under us (s2.8): keep the points, stop */
    fun suspend() {
        val s = _state.value ?: return
        if (s.dirty) {
            writeDraft(pending = null, active = false)
        } else {
            // C1: not dirty, but a draft's there: just switch it off, its points stay as they were
            drafts.load(s.target.draftKey)?.takeIf { it.active }?.let { drafts.save(it.copy(active = false)) }
        }
        end()
        _events.trySend(CalibrationEvent.Paused)
    }

    /** VM publish hook: a different entry became the map */
    fun onActiveSourceChanged(entryId: String?) {
        val s = _state.value ?: return
        if (entryId != s.target.entryId) suspend()
    }

    private fun end() {
        undo.clear()
        _state.value = null
    }

    // ------------------------------------------------------------------ draft

    private fun writeDraft(pending: PendingDraftPoint?, active: Boolean = true) {
        val s = _state.value ?: return
        val d = CalibrationDraft(
            contentKey = s.target.contentKey,
            pageIndex = s.target.pageIndex,
            entryId = s.target.entryId,
            datumId = s.state.datumId,
            points = s.state.sortedPoints,
            nextNumber = s.state.nextNumber,
            pending = pending,
            active = active,
            updatedAtMs = clock(),
        )
        val ok = drafts.save(d)
        if (ok != !s.draftUnsaved) update { it.copy(draftUnsaved = !ok) }
    }

    /** back from a lock: try the write that failed again */
    fun retryDraftWrite() {
        val s = _state.value ?: return
        if (!s.draftUnsaved) return
        val ph = s.phase as? CalibrationPhase.Entering
        writeDraft(pending = ph?.let { PendingDraftPoint(StoredPagePoint.of(it.page), it.editingId) })
    }

    /** contract s7.5: residual line target = where the typed reference sits on the shown georef */
    fun typedPosition(point: CalibrationPoint): Wgs84Coordinate? {
        val s = _state.value ?: return null
        val r = point.resolved(s.state.datum) ?: return null
        val w = s.state.datum.toWGS84(r.latitude, r.longitude) ?: return null
        return Wgs84Coordinate(w.latitude, w.longitude)
    }

    /** test hook */
    internal fun undoDepth(): Int = undo.size

    companion object {
        /** distance (dp) past which "Move to crosshair" shows */
        fun moveToCrosshairVisible(screenDistanceDp: Double): Boolean =
            abs(screenDistanceDp) > com.tacmap.calibration.ImportLimits.MOVE_TO_CROSSHAIR_MIN_SCREEN_DP
    }
}
