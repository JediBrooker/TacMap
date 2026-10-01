import XCTest
import CoreLocation
@testable import TacticalMaps

/// The WP4 crosshair calibration state machine (contract s2). Replaces the old
/// tap-to-place session tests: points come from captures of the live camera
/// through the displayed georef, references are typed, drafts are sealed.
final class CalibrationSessionTests: XCTestCase {

    // the rot5 plain sheet: page box + its four printed grid ring targets (pdf_georef.json)
    private let box = CalibrationTarget.box(CGRect(x: 0, y: 0, width: 900.7839644, height: 1106.9282314))
    private let targets: [(PdfPagePoint, String)] = [
        (PdfPagePoint(x: 254.1299411, y: 194.8365854), "10SEG 47000 77000"),
        (PdfPagePoint(x: 705.947379, y: 234.3654892), "10SEG 51000 77000"),
        (PdfPagePoint(x: 646.6540233, y: 912.091646), "10SEG 51000 83000"),
        (PdfPagePoint(x: 194.8365854, y: 872.5627422), "10SEG 47000 83000"),
    ]

    private var drafts: InMemoryCalibrationDraftStore!
    private var session: CalibrationSession!
    private var target: CalibrationTarget!
    private var provisional: PdfGeoreference!

    override func setUp() {
        super.setUp()
        drafts = InMemoryCalibrationDraftStore()
        session = CalibrationSession(drafts: drafts, clock: { Date(timeIntervalSince1970: 1_800_000_000) })
        target = CalibrationTarget(entryID: UUID(), contentKey: "sha256:" + String(repeating: "a", count: 64),
                                   pageIndex: 0, pageBox: box, rotate: 0)
        provisional = PdfGeoreference.provisional(pageBox: target.pageRect, rotation: 0,
                                                  centredOn: CLLocationCoordinate2D(latitude: -35, longitude: 149))
    }

    private func start(seed: CalibrationState = CalibrationState(), embeddedDatum: String? = nil,
                       base: PdfGeoreference? = nil, silently: Bool = false) {
        session.start(target: target, entryName: "rot5", base: base ?? provisional, seed: seed,
                      embeddedDatumID: embeddedDatum, wasPreview: base == nil, resumeSilently: silently)
    }

    /// the camera sat exactly on `page` through whatever the map shows now
    private func capture(_ page: PdfPagePoint, zoom: Double = 17) -> CalibrationCapture? {
        guard let d = session.display, let c = d.georef.toWGS84(x: page.x, y: page.y) else { return nil }
        let cam = MapCamera(center: c, zoom: zoom, headingDegrees: 0, viewportSize: CGSize(width: 390, height: 844))
        return CalibrationCapture.capture(georef: d.georef, pageBox: box, camera: cam, generation: d.generation)
    }

    @discardableResult
    private func add(_ page: PdfPagePoint, _ text: String) -> Bool {
        guard session.beginAdd(capture: capture(page)) == .ok else { return false }
        session.entryText = text
        return session.commitEntry()
    }

    func testPlainSheetAsksForTheDatumFirstAndAGeoPDFPreselectsItsOwn() {
        start()
        XCTAssertEqual(session.phase, .datumSheet)
        session.chooseDatum("WGS84")
        XCTAssertEqual(session.phase, .placing)
        XCTAssertFalse(session.canUndo, "the first datum pick isn't an edit")
        XCTAssertFalse(session.isDirty)

        start(embeddedDatum: "NAD27")
        XCTAssertEqual(session.phase, .placing)
        XCTAssertEqual(session.state.datumID, "NAD27")
        XCTAssertFalse(session.isDirty)
    }

    func testCrosshairFlowFitsTheRot5SheetExactly() throws {
        start()
        session.chooseDatum("WGS84")
        XCTAssertEqual(session.display?.isProvisional, true)
        for (page, text) in targets { XCTAssertTrue(add(page, text), text) }
        XCTAssertEqual(session.report.n, 4)
        XCTAssertEqual(session.report.grade, .good)
        XCTAssertLessThan(try XCTUnwrap(session.report.rmsM), 0.01)
        XCTAssertEqual(session.display?.isFit, true)
        XCTAssertEqual(session.display?.isProvisional, false)
        let manual = try XCTUnwrap(session.finishTapped())
        XCTAssertEqual(manual.n, 4)
        XCTAssertEqual(manual.georef.crs.utmZone?.zone, 10)
        XCTAssertEqual(manual.georef.crop, box, "the saved fit shows the whole page")
        session.didCommit()
        XCTAssertFalse(session.isCalibrating)
        XCTAssertTrue(drafts.drafts.isEmpty, "finish drops the draft")
    }

    func testThreePointsInALineAreBlockedAndFinishDoesNothing() {
        start()
        session.chooseDatum("WGS84")
        add(PdfPagePoint(x: 100, y: 500), "10SEG 46000 80000")
        add(PdfPagePoint(x: 450, y: 503), "10SEG 49000 80000")
        add(PdfPagePoint(x: 800, y: 499), "10SEG 52000 80000")
        XCTAssertEqual(session.report.blocked, .degenerate)
        XCTAssertEqual(session.report.finishability, .blocked(.degenerate))
        XCTAssertNil(session.finishTapped())
        XCTAssertEqual(session.phase, .placing)
        // still the provisional placement, a blocked fit is never drawn (s7.1)
        XCTAssertEqual(session.display?.isProvisional, true)
    }

    func testThreePointsConfirmBeforeFinishing() {
        start()
        session.chooseDatum("WGS84")
        for (page, text) in targets.prefix(3) { add(page, text) }
        XCTAssertNil(session.finishTapped())
        XCTAssertEqual(session.phase, .finishConfirm([.exact]))
        XCTAssertNotNil(session.finishAnyway())
    }

    func testOffSheetAndStaleCapturesAreRefused() {
        start()
        session.chooseDatum("WGS84")
        XCTAssertEqual(session.beginAdd(capture: capture(PdfPagePoint(x: -50, y: 300))), .offSheet)
        var stale = capture(targets[0].0)
        stale?.generation -= 1
        XCTAssertEqual(session.beginAdd(capture: stale), .notReady, "a capture through an old georef")
        XCTAssertEqual(session.beginAdd(capture: nil), .notReady)
    }

    func testNumbersAreStableUndoRestoresAndDeleteLeavesAGap() {
        start()
        session.chooseDatum("WGS84")
        for (page, text) in targets.prefix(3) { add(page, text) }
        let second = session.state.points[1]
        let before = session.state
        session.delete(second.id)
        XCTAssertEqual(session.state.points.map(\.number), [1, 3])
        add(targets[3].0, targets[3].1)
        XCTAssertEqual(session.state.points.map(\.number), [1, 3, 4], "never renumbered")
        session.undo()
        session.undo()
        XCTAssertEqual(session.state, before)
    }

    func testFiftyPointCap() {
        start()
        session.chooseDatum("WGS84")
        var s = session.state
        for i in 0..<CalibrationLimits.maxCalibrationPoints {
            s = s.applying(.add(CalibrationPoint(number: 0, page: PdfPagePoint(x: Double(10 + i * 17), y: Double(20 + (i % 7) * 140)),
                                                 input: "x", reference: .geographic(latitude: 37.7, longitude: -122.4))))
        }
        session.start(target: target, entryName: "x", base: provisional, seed: s, embeddedDatumID: "WGS84", wasPreview: true)
        XCTAssertEqual(session.beginAdd(capture: capture(targets[0].0)), .maxPoints)
    }

    func testEveryMutationWritesTheDraftAndALockedKeyIsRetriedOnUnlock() throws {
        start()
        session.chooseDatum("WGS84")
        let writes0 = drafts.writes
        XCTAssertEqual(session.beginAdd(capture: capture(targets[0].0)), .ok)
        XCTAssertEqual(drafts.writes, writes0 + 1, "the pending point is written at beginAdd")
        session.entryText = targets[0].1
        session.commitEntry()
        let d = try XCTUnwrap(drafts.drafts[target.draftKey])
        XCTAssertEqual(d.points.count, 1)
        XCTAssertTrue(d.active)
        XCTAssertNil(d.pending)
        XCTAssertEqual(d.contentKey, target.contentKey)

        drafts.locked = true
        add(targets[1].0, targets[1].1)
        XCTAssertTrue(session.draftUnsaved)
        drafts.locked = false
        session.onDataKeyUnlocked()
        XCTAssertFalse(session.draftUnsaved)
        XCTAssertEqual(drafts.drafts[target.draftKey]?.points.count, 2)
    }

    func testResumePromptVersusSilentResume() throws {
        let draft = CalibrationDraft(contentKey: target.contentKey, pageIndex: 0, entryId: target.entryID, datumId: "WGS84",
                                     points: [CalibrationPoint(number: 1, page: targets[0].0, input: targets[0].1,
                                                               reference: .grid(zone: 10, south: false, easting: 547000, northing: 4177000,
                                                                                cellSizeM: 1, source: .mgrs))],
                                     nextNumber: 2, pending: nil, active: false, updatedAtMs: 1)
        drafts.drafts[draft.key] = draft
        start()
        XCTAssertEqual(session.phase, .resumePrompt)
        XCTAssertTrue(session.state.points.isEmpty, "seed until the user answers")
        session.resume()
        XCTAssertEqual(session.state.points.count, 1)

        // Start over drops it
        drafts.drafts[draft.key] = draft
        start()
        session.startOver()
        XCTAssertTrue(session.state.points.isEmpty)
        XCTAssertNil(drafts.drafts[draft.key])

        // E3: an active draft resumes without asking
        var active = draft
        active.active = true
        drafts.drafts[draft.key] = active
        start()
        XCTAssertNotEqual(session.phase, .resumePrompt)
        XCTAssertEqual(session.state.points.count, 1)
    }

    func testCleanLeaveEndsAtOnceAndDirtyLeaveAsks() throws {
        start()
        session.chooseDatum("WGS84")
        session.leaveTapped()
        XCTAssertFalse(session.isCalibrating, "nothing changed, nothing to ask")

        start()
        session.chooseDatum("WGS84")
        add(targets[0].0, targets[0].1)
        session.leaveTapped()
        XCTAssertEqual(session.phase, .leaveDialog)
        session.leave(keepDraft: true)
        XCTAssertFalse(session.isCalibrating)
        XCTAssertEqual(drafts.drafts[target.draftKey]?.active, false, "kept for later, not auto-resumed")

        start()
        session.resume()
        add(targets[1].0, targets[1].1)
        session.leaveTapped()
        session.leave(keepDraft: false)
        XCTAssertNil(drafts.drafts[target.draftKey], "discard deletes the draft")
    }

    func testSuspendKeepsThePointsInactive() {
        start()
        session.chooseDatum("WGS84")
        add(targets[0].0, targets[0].1)
        session.suspend()
        XCTAssertFalse(session.isCalibrating)
        XCTAssertEqual(drafts.drafts[target.draftKey]?.active, false)
        XCTAssertEqual(drafts.drafts[target.draftKey]?.points.count, 1)
    }

    func testGenerationOnlyBumpsWhenTheShownGeorefChanges() throws {
        start()
        session.chooseDatum("WGS84")
        let g0 = try XCTUnwrap(session.display?.generation)
        add(targets[0].0, targets[0].1)
        add(targets[1].0, targets[1].1)
        XCTAssertEqual(session.display?.generation, g0, "< 3 points: still the provisional base")
        add(targets[2].0, targets[2].1)
        let g1 = try XCTUnwrap(session.display?.generation)
        XCTAssertGreaterThan(g1, g0, "the 3 point fit replaced the guess")
        let id = try XCTUnwrap(session.state.points.first?.id)
        session.select(id)
        session.select(nil)
        XCTAssertEqual(session.display?.generation, g1, "selection isn't a refit")
    }

    func testShorthandCompletesFromTheMapOnceThereIsAFit() throws {
        start()
        session.chooseDatum("WGS84")
        for (page, text) in targets.prefix(3) { add(page, text) }
        XCTAssertEqual(session.beginAdd(capture: capture(targets[3].0)), .ok)
        session.entryText = "470 830"
        guard case .success(let r)? = session.entryParse else { return XCTFail("\(String(describing: session.entryParse))") }
        XCTAssertEqual(r.completedFrom, .map)
        XCTAssertEqual(r.canonical, "10S EG 47000 83000")
        XCTAssertEqual(session.entryCheck?.warn, false)
        // a 1 km slip warns before saving
        session.entryText = "470 840"
        XCTAssertEqual(session.entryCheck?.warn, true)
        XCTAssertNotNil(session.entryCheck?.message)
    }

    func testEditingAPointKeepsItsNumberAndRefits() throws {
        start()
        session.chooseDatum("WGS84")
        for (page, text) in targets { add(page, text) }
        let p2 = session.state.points[1]
        session.beginEdit(p2.id)
        XCTAssertEqual(session.entryText, p2.input)
        session.entryText = "10SEG 51300 77000"
        XCTAssertTrue(session.commitEntry())
        XCTAssertEqual(session.state.point(p2.id)?.number, 2)
        XCTAssertGreaterThan(try XCTUnwrap(session.report.rmsM), 50, "a 300 m typo shows up in the fit")
        session.undo()
        XCTAssertLessThan(try XCTUnwrap(session.report.rmsM), 0.01)
    }

    func testMovingAPointRecapturesIt() throws {
        start()
        session.chooseDatum("WGS84")
        for (page, text) in targets { add(page, text) }
        let p1 = session.state.points[0]
        session.beginMove(p1.id)
        XCTAssertEqual(session.phase, .moving(p1.id))
        let off = PdfPagePoint(x: p1.page.x + 20, y: p1.page.y)
        session.confirmMove(capture: capture(off))
        let moved = try XCTUnwrap(session.state.point(p1.id))
        XCTAssertEqual(moved.page.x, off.x, accuracy: 1e-6)
        XCTAssertEqual(moved.page.y, off.y, accuracy: 1e-6)
    }

    func testCommitWithAMismatchedTargetWritesNothing() throws {
        var lib = LibraryState()
        let entry = ImportedMapEntry(id: target.entryID, kind: .pdf, fileName: "ImportedMaps/map-x.pdf", displayName: "x",
                                     contentKey: target.contentKey, byteCount: 1, fileModifiedAtMs: 0, importedAtMs: 0,
                                     derivedFromId: nil,
                                     pdf: .init(pageCount: 1, pageIndex: 0, rotate: 0, pageBox: box))
        lib.entries = [entry]
        start()
        session.chooseDatum("WGS84")
        for (page, text) in targets { add(page, text) }
        let manual = try XCTUnwrap(session.finishTapped())
        XCTAssertThrowsError(try LibraryReducer.apply(.commitCalibration(entry.id, manual, contentKey: "sha256:" + String(repeating: "b", count: 64),
                                                                         pageIndex: 0), to: lib)) {
            XCTAssertEqual($0 as? LibraryTransitionError, .targetMismatch)
        }
        XCTAssertThrowsError(try LibraryReducer.apply(.commitCalibration(entry.id, manual, contentKey: target.contentKey,
                                                                         pageIndex: 1), to: lib))
        let ok = try LibraryReducer.apply(.commitCalibration(entry.id, manual, contentKey: target.contentKey, pageIndex: 0), to: lib)
        XCTAssertEqual(ok.state.entry(entry.id)?.pdf?.manual?.n, 4)
        XCTAssertEqual(ok.state.active, .entry(entry.id))
    }
}
