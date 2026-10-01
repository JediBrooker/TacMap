import Foundation
import CoreLocation
import Combine

/// The calibration state machine (WP4 contract s2). Owns the points, the fit
/// report, undo, the draft and what the map should be drawing; ContentView and
/// the map container just render it and feed it captures.
///
/// Every mutation: push undo -> apply -> refit -> report -> displayed georef
/// (generation bumps only when it actually changed) -> synchronous draft write.
/// Main thread only, like the other view models (not @MainActor so the UIKit
/// editing layer can poke it without hopping).
final class CalibrationSession: ObservableObject {

    enum Phase: Equatable {
        case placing
        case entering(CalibrationPending)
        case moving(UUID)
        case datumSheet
        case pointsSheet
        case leaveDialog
        case finishConfirm([CalibrationConfirmReason])
        case resumePrompt
        case saveFailed
    }

    /// what the map draws right now (s7.1). provisional = the 1:50k guess
    struct Display: Equatable {
        var georef: PdfGeoreference
        var generation: Int
        var isProvisional: Bool
        /// true when it's the live fit rather than the saved/embedded/provisional base
        var isFit: Bool
    }

    enum AddResult: Equatable { case ok, offSheet, maxPoints, notReady }

    // MARK: published

    @Published private(set) var isCalibrating = false
    @Published var phase: Phase = .placing
    @Published private(set) var target: CalibrationTarget?
    @Published private(set) var entryName = ""
    @Published private(set) var state = CalibrationState()
    @Published private(set) var report = CalibrationFitReport()
    @Published private(set) var selectedID: UUID?
    @Published private(set) var display: Display?
    @Published private(set) var draftUnsaved = false
    @Published var gridOn = false
    @Published private(set) var canUndo = false
    /// the draft waiting on Resume / Start over
    @Published private(set) var resumeDraft: CalibrationDraft?

    // entry card
    @Published var entryText = "" { didSet { if entryText != oldValue { reparse() } } }
    @Published var entryKind: CalibrationPointKind = .intersection { didSet { if entryKind != oldValue { reparse() } } }
    @Published var entryLabel = ""
    @Published private(set) var entryParse: Result<ParsedReference, CoordinateParseError>?
    @Published private(set) var entryCheck: CalibrationFitEvaluator.EntryCheck?
    /// set by Use my position, cleared as soon as the text is edited
    private var gpsFix: (text: String, coordinate: CLLocationCoordinate2D)?

    /// one line toasts for ContentView (point deleted, datum changed ...)
    let toasts = PassthroughSubject<String, Never>()
    /// fly the camera to a point at the current zoom/heading
    let flyRequests = PassthroughSubject<CLLocationCoordinate2D, Never>()

    // MARK: private

    private(set) var base: PdfGeoreference?
    private var seed = CalibrationState()
    private var startState = CalibrationState()
    private var undoStack: [CalibrationState] = []
    private(set) var wasPreview = false
    private var embeddedDatumID: String?
    private var nextGeneration = 1
    /// C1: a draft is on disk for this target (found at start, or written since).
    /// A clean leave or a suspend still has to flip it inactive, or E3 reopens it
    private var draftExists = false
    private let drafts: CalibrationDraftStoring
    private let clock: () -> Date

    init(drafts: CalibrationDraftStoring = CalibrationDraftStore.shared, clock: @escaping () -> Date = Date.init) {
        self.drafts = drafts
        self.clock = clock
    }

    var isDirty: Bool { state != startState }

    var entryID: UUID? { target?.entryID }

    var pendingEntry: CalibrationPending? {
        if case .entering(let p) = phase { return p }
        return nil
    }

    var movingID: UUID? {
        if case .moving(let id) = phase { return id }
        return nil
    }

    // MARK: - start / end

    /// s2.3 steps 10-12. ContentView did steps 1-9 (modes, chrome, display).
    /// - Parameters:
    ///   - base: provisional, embedded or saved manual georef the map shows first
    ///   - seed: the saved manual calibration as a state, empty otherwise
    ///   - resumeSilently: E3 auto-resume after a kill, no prompt
    func start(target: CalibrationTarget, entryName: String, base: PdfGeoreference, seed: CalibrationState,
               embeddedDatumID: String?, wasPreview: Bool, resumeSilently: Bool = false) {
        self.target = target
        self.entryName = entryName
        self.base = base
        self.seed = seed
        self.embeddedDatumID = embeddedDatumID
        self.wasPreview = wasPreview
        undoStack = []
        canUndo = false
        selectedID = nil
        gridOn = false
        draftUnsaved = false
        resumeDraft = nil
        clearEntry()
        isCalibrating = true

        let draft: CalibrationDraft?
        do {
            draft = try drafts.draft(contentKey: target.contentKey, pageIndex: target.pageIndex)
        } catch {
            draft = nil
            draftUnsaved = true
        }
        draftExists = draft != nil
        var initial = seed
        var resumedSilently = false
        phase = .placing
        if let draft, draft.entryId == target.entryID || draft.contentKey == target.contentKey {
            let ds = draft.state
            if draft.active || resumeSilently {
                initial = ds
                resumedSilently = true
                // C6: no "resumed" toast for a draft with nothing in it
                if !draft.points.isEmpty {
                    toasts.send(Messages.calibrationResumed(Messages.calibrationPointCount(draft.points.count)))
                }
            } else if ds != seed {
                resumeDraft = draft
                phase = .resumePrompt
            }
        }
        startState = seed
        apply(initial, recordUndo: false, writeDraft: false)
        if resumedSilently {
            // C6: an E3 resume is active again straight away, and the entry card
            // that was open when the app died comes back with it
            if let pending = draft?.pending {
                phase = .entering(pending)
                writeDraft(active: true)
                reparse()
                return
            }
            writeDraft(active: true)
        }
        if phase == .placing { promptForDatumIfNeeded() }
    }

    private func promptForDatumIfNeeded() {
        guard state.datumID == nil else { return }
        if let embeddedDatumID {
            // a GeoPDF refine preselects what the PDF says, no question asked
            state.datumID = embeddedDatumID
            startState.datumID = startState.datumID ?? embeddedDatumID
            refresh()
        } else {
            phase = .datumSheet
        }
    }

    /// Resume prompt answers
    func resume() {
        guard let d = resumeDraft else { return }
        resumeDraft = nil
        phase = .placing
        apply(d.state, recordUndo: false, writeDraft: true)
        promptForDatumIfNeeded()
    }

    func startOver() {
        guard let t = target else { return }
        resumeDraft = nil
        drafts.delete(contentKey: t.contentKey, pageIndex: t.pageIndex)
        draftExists = false
        phase = .placing
        apply(seed, recordUndo: false, writeDraft: false)
        promptForDatumIfNeeded()
    }

    /// tear down, the caller restores chrome/heading/display
    private func end() {
        isCalibrating = false
        phase = .placing
        target = nil
        display = nil
        base = nil
        selectedID = nil
        undoStack = []
        canUndo = false
        resumeDraft = nil
        draftExists = false
        clearEntry()
        state = CalibrationState()
        report = CalibrationFitReport()
    }

    // MARK: - leave / suspend / finish

    /// the X button (and nothing else): clean -> out, dirty -> the 3 button dialog
    func leaveTapped() {
        if isDirty { phase = .leaveDialog; return }
        // C1: add + Cancel (or add + undo) is back at the seed but left a draft
        // behind. Flip it inactive or E3 resumes it every launch
        if draftExists { writeDraft(active: false) }
        end()
    }

    /// keep = draft stays (active false) for later, else the draft is deleted
    func leave(keepDraft: Bool) {
        guard let t = target else { return end() }
        if keepDraft {
            writeDraft(active: false)
        } else {
            drafts.delete(contentKey: t.contentKey, pageIndex: t.pageIndex)
        }
        end()
    }

    func continueCalibrating() { phase = .placing }

    /// the active map changed under us: stop, keep the points
    func suspend() {
        guard isCalibrating else { return }
        // C1: an existing draft goes inactive whether or not anything changed
        if isDirty || draftExists { writeDraft(active: false) }
        end()
        toasts.send(Messages.calibrationPaused())
    }

    /// Finish pressed. Blocked does nothing (the button is disabled and the
    /// reason is on screen), confirm asks, ready returns what to commit.
    func finishTapped() -> ManualCalibration? {
        switch report.finishability {
        case .blocked: return nil
        case .confirm(let reasons):
            phase = .finishConfirm(reasons)
            return nil
        case .ready:
            return manualCalibration()
        }
    }

    /// "Finish anyway"
    func finishAnyway() -> ManualCalibration? {
        phase = .placing
        return manualCalibration()
    }

    func manualCalibration() -> ManualCalibration? {
        guard let g = report.georef, let datum = state.datumID ?? Optional("WGS84") else { return nil }
        return ManualCalibration(datumId: datum, points: state.points, nextNumber: state.nextNumber, georef: g,
                                 n: report.n, rmsM: report.n >= 4 ? report.rmsM : nil, grade: report.grade,
                                 savedAtMs: Int64(clock().timeIntervalSince1970 * 1000))
    }

    /// the library write went through: drop the draft, end, say so
    func didCommit() {
        guard let t = target else { return }
        let n = report.n, rms = report.rmsM
        drafts.delete(contentKey: t.contentKey, pageIndex: t.pageIndex)
        end()
        if n >= 4, let rms {
            toasts.send(Messages.calibrationDone(Messages.calibrationPointCount(n), DisplayFormat.rms(rms)))
        } else {
            toasts.send(Messages.calibrationDoneExact())
        }
    }

    /// the write failed: stay put, keep the draft, show Retry / Not now
    func commitFailed() {
        writeDraft(active: true)
        phase = .saveFailed
    }

    // MARK: - datum

    func chooseDatum(_ id: String) {
        let old = state.datumID
        if phase == .datumSheet { phase = .placing }
        guard id != old else { return }
        if old == nil && state.points.isEmpty {
            // first pick of a fresh calibration isn't an edit worth undoing
            var s = state
            s.datumID = id
            startState.datumID = startState.datumID ?? id
            apply(s, recordUndo: false, writeDraft: false)
            return
        }
        mutate(.setDatum(id))
        if old != nil, let d = GeoDatum.named(id) {
            toasts.send(Messages.calibrationDatumChanged(CalibrationDatumChoice.displayName(d.id)))
        }
    }

    // MARK: - add / enter

    /// "Add point": capture at the crosshair (read live by the map), open the entry card
    @discardableResult
    func beginAdd(capture: CalibrationCapture?) -> AddResult {
        guard isCalibrating, let display, let capture else { return .notReady }
        guard capture.generation == display.generation else { return .notReady }
        guard capture.onSheet else { return .offSheet }
        guard state.points.count < CalibrationLimits.maxCalibrationPoints else { return .maxPoints }
        clearEntry()
        selectedID = nil
        phase = .entering(CalibrationPending(page: capture.page, editingId: nil))
        writeDraft(active: true)
        reparse()
        return .ok
    }

    /// edit an existing point's coordinate, prefilled
    func beginEdit(_ id: UUID) {
        guard let p = state.point(id) else { return }
        clearEntry()
        phase = .entering(CalibrationPending(page: p.page, editingId: id))
        entryKind = p.kind
        entryLabel = p.label ?? ""
        entryText = p.input
        writeDraft(active: true)
        reparse()
    }

    /// re-place the pending point at the crosshair without losing the text
    func moveEntryToCrosshair(capture: CalibrationCapture?) {
        guard case .entering(var pending) = phase, let capture, capture.onSheet,
              capture.generation == display?.generation else { return }
        pending.page = capture.page
        phase = .entering(pending)
        writeDraft(active: true)
        reparse()
    }

    func useGPS(_ location: CLLocation) {
        guard location.horizontalAccuracy >= 0, location.horizontalAccuracy <= CalibrationLimits.gpsMaxAccuracyM else { return }
        let c = location.coordinate
        let text = String(format: "%.6f, %.6f", c.latitude, c.longitude)
        entryText = text
        gpsFix = (text, c)
        reparse()
    }

    func cancelEntry() {
        clearEntry()
        phase = .placing
        writeDraft(active: true)
    }

    private func clearEntry() {
        entryText = ""
        entryKind = .intersection
        entryLabel = ""
        entryParse = nil
        entryCheck = nil
        gpsFix = nil
    }

    /// the parse context for what's being typed (s4.2 shorthand completion)
    func parseContext(for pending: CalibrationPending) -> CoordinateParseContext {
        let datumID = state.datumID ?? "WGS84"
        let others = state.points.filter { $0.id != pending.editingId }
        var anchor: GridAnchor?
        if let last = others.filter({ $0.reference.isGrid }).max(by: { $0.number < $1.number }),
           case let .grid(zone, south, e, n, _, _) = last.reference,
           let sq = CoordinateInputParser.square(zone: zone, e: e, n: n) {
            let datum = state.sheetDatum
            let lat = GeoCrs.utm(zone: zone, south: south).inverse(x: e, y: n, ellipsoid: datum.ellipsoid)?.lat
            if let lat, let band = CoordinateInputParser.band(lat: lat) {
                anchor = GridAnchor(zone: zone, band: band, square: sq, fromPoint: last.number)
            }
        }
        var predicted: CLLocationCoordinate2D?
        if let d = display, !d.isProvisional {
            predicted = d.georef.toWGS84(x: pending.page.x, y: pending.page.y)
        }
        return CoordinateParseContext(datumID: datumID, isFirstPoint: others.isEmpty, kind: entryKind,
                                      gridAnchor: anchor, predicted: predicted)
    }

    private func reparse() {
        if let gps = gpsFix, gps.text != entryText { gpsFix = nil }
        guard case .entering(let pending) = phase else {
            entryParse = nil
            entryCheck = nil
            return
        }
        let trimmed = entryText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            entryParse = nil
            entryCheck = nil
            return
        }
        let parsed = CoordinateInputParser.parse(entryText, context: parseContext(for: pending))
        entryParse = parsed
        if let point = pendingPoint(pending, parsed) {
            entryCheck = CalibrationFitEvaluator.entryCheck(state: state, report: report, pageBox: target?.pageBox ?? [],
                                                            rotate: target?.rotate ?? 0, pending: point,
                                                            editing: pending.editingId, base: base)
        } else {
            entryCheck = nil
        }
    }

    private func pendingPoint(_ pending: CalibrationPending,
                              _ parsed: Result<ParsedReference, CoordinateParseError>) -> CalibrationPoint? {
        if let gps = gpsFix, gps.text == entryText {
            // GPS is WGS84 whatever the sheet says
            return CalibrationPoint(number: 0, page: pending.page, input: gps.text,
                                    reference: .geographic(latitude: gps.coordinate.latitude, longitude: gps.coordinate.longitude),
                                    kind: .intersection, datumOverride: "WGS84", label: entryLabel.isEmpty ? nil : entryLabel)
        }
        guard case .success(let r) = parsed else { return nil }
        return CalibrationPoint(number: 0, page: pending.page, input: entryText.trimmingCharacters(in: .whitespacesAndNewlines),
                                reference: r.reference, kind: r.effectiveKind ?? .intersection, datumOverride: nil,
                                label: entryLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil
                                    : entryLabel.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    var canSaveEntry: Bool {
        guard case .entering(let p) = phase else { return false }
        return pendingPoint(p, entryParse ?? .failure(.empty)) != nil
    }

    /// Save point / Save anyway
    @discardableResult
    func commitEntry() -> Bool {
        guard case .entering(let pending) = phase,
              let point = pendingPoint(pending, entryParse ?? .failure(.empty)) else { return false }
        clearEntry()
        phase = .placing
        if let id = pending.editingId {
            mutate(.editReference(id, input: point.input, reference: point.reference, kind: point.kind,
                                  datumOverride: point.datumOverride, label: point.label))
            if let p = state.point(id), p.page != pending.page { mutate(.move(id, pending.page)) }
        } else {
            mutate(.add(point))
        }
        return true
    }

    // MARK: - select / move / delete / undo

    func select(_ id: UUID?) {
        guard isCalibrating else { return }
        if case .entering = phase { return }
        if case .moving = phase { return }
        selectedID = id
        if phase == .pointsSheet { phase = .placing }
        if let id, let p = state.point(id), let w = display?.georef.toWGS84(x: p.page.x, y: p.page.y) {
            flyRequests.send(w)
        }
    }

    func beginMove(_ id: UUID) {
        guard state.point(id) != nil else { return }
        selectedID = id
        phase = .moving(id)
    }

    func confirmMove(capture: CalibrationCapture?) {
        guard case .moving(let id) = phase, let capture, capture.onSheet,
              capture.generation == display?.generation else { return }
        phase = .placing
        mutate(.move(id, capture.page))
    }

    func cancelMove() {
        if case .moving = phase { phase = .placing }
    }

    func delete(_ id: UUID) {
        guard let p = state.point(id) else { return }
        if selectedID == id { selectedID = nil }
        mutate(.delete(id))
        toasts.send(Messages.calibrationPointDeleted(DisplayFormat.number(Double(p.number), decimals: 0)))
    }

    func undo() {
        guard let previous = undoStack.popLast() else { return }
        canUndo = !undoStack.isEmpty
        if let s = selectedID, previous.point(s) == nil { selectedID = nil }
        apply(previous, recordUndo: false, writeDraft: true)
    }

    // MARK: - pipeline

    private func mutate(_ edit: CalibrationEdit) {
        let next = state.applying(edit)
        guard next != state else { return }
        apply(next, recordUndo: true, writeDraft: true)
    }

    private func apply(_ next: CalibrationState, recordUndo: Bool, writeDraft draft: Bool) {
        if recordUndo {
            undoStack.append(state)
            if undoStack.count > CalibrationLimits.undoDepth { undoStack.removeFirst(undoStack.count - CalibrationLimits.undoDepth) }
        }
        canUndo = !undoStack.isEmpty
        state = next
        refresh()
        if draft { writeDraft(active: true) }
    }

    /// refit + report + displayed georef. Generation bumps only on a real change
    private func refresh() {
        guard let t = target, let base else { return }
        report = CalibrationFitEvaluator.evaluate(state, pageBox: t.pageBox, rotate: t.rotate, pageIndex: t.pageIndex)
        #if DEBUG
        // the panel rounds to whole metres, handy to see the real numbers when checking a sheet
        if let rms = report.rmsM {
            NSLog("[Calibration] fit n=%d rms=%.3f m max=%.3f m tol=%.1f m", report.n, rms, report.maxM ?? 0, report.toleranceM ?? 0)
        }
        #endif
        var next: PdfGeoreference
        var isFit = false
        if state.points.count >= 3, let g = report.georef {
            next = g
            isFit = true
        } else {
            next = base
        }
        // collar + tick labels stay visible while calibrating (s1 Terms)
        next.crop = t.pageBox
        if display?.georef != next {
            display = Display(georef: next, generation: nextGeneration, isProvisional: next.origin == .provisional, isFit: isFit)
            nextGeneration += 1
        } else if display?.isFit != isFit {
            display?.isFit = isFit
        }
    }

    // MARK: - draft

    private func writeDraft(active: Bool) {
        guard let t = target else { return }
        var pending: CalibrationPending?
        if case .entering(let p) = phase { pending = p }
        let d = CalibrationDraft(contentKey: t.contentKey, pageIndex: t.pageIndex, entryId: t.entryID,
                                 datumId: state.datumID, points: state.points, nextNumber: state.nextNumber,
                                 pending: pending, active: active,
                                 updatedAtMs: Int64(clock().timeIntervalSince1970 * 1000))
        do {
            try drafts.save(d)
            draftUnsaved = false
            draftExists = true
        } catch {
            // key locked mid calibration: keep going in memory, say so, retry on unlock
            draftUnsaved = true
        }
    }

    /// mission data unlocked again: the draft that couldn't be written goes now
    func onDataKeyUnlocked() {
        guard isCalibrating, draftUnsaved else { return }
        writeDraft(active: true)
    }
}

/// The datum picker's fixed list (s10) and display names.
enum CalibrationDatumChoice {
    static let order = ["WGS84", "GDA2020", "GDA94", "NAD83", "ETRS89", "ED50", "NAD27", "NAD27_CONUS_EAST",
                        "NAD27_CONUS_WEST", "NAD27_ALASKA", "NAD27_CANADA", "OSGB36", "SK42", "TOKYO", "CH1903", "NTF"]

    /// A1: the one name table (calibration_input.json display.datumDisplayNames),
    /// technical names, the same in every language and on both platforms
    static func displayName(_ id: String) -> String {
        switch id {
        case "NAD27_CONUS_EAST": return "NAD27 CONUS East"
        case "NAD27_CONUS_WEST": return "NAD27 CONUS West"
        case "NAD27_ALASKA": return "NAD27 Alaska"
        case "NAD27_CANADA": return "NAD27 Canada"
        case "SK42": return "SK-42"
        case "TOKYO": return "Tokyo"
        default: return id
        }
    }
}

/// E3 at launch (s2.1 r1, import_limits.json lifecycle.autoResume). Pure, the
/// caller only asks once the library is Loaded and runs it once per launch
enum CalibrationAutoResume {
    enum Action: String { case resume, skip, deferUntilLoaded }

    /// draftActive nil = no draft, entry nil = its entry isnt in the library
    static func decide(draftActive: Bool?, entry: ImportedMapState?, library: LibraryLoadStatus,
                       suspectPending: Bool) -> Action {
        guard draftActive == true else { return .skip }
        switch library {
        case .unknown, .locked: return .deferUntilLoaded
        case .corrupt: return .skip
        case .loaded: break
        }
        // M10/C2/C8: any pending crash suspect, the draft stays active for next time
        guard !suspectPending else { return .skip }
        guard let entry, entry != .unavailable else { return .skip }
        return .resume
    }
}
