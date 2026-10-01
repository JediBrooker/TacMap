import XCTest
import Combine
import CoreLocation
@testable import TacticalMaps

/// import_limits.json "lifecycle" + "georefRejectReasons": the session, draft
/// and library rules both apps share (contract s2, s8). Every row of every
/// table runs, with a count guard so a fixture regen can't quietly drop one.
final class CalibrationLifecycleContractTests: XCTestCase {

    private lazy var fx = WP4Fixtures.load("import_limits.json")
    private var life: [String: Any] { fx["lifecycle"] as? [String: Any] ?? [:] }

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
    private var toasts: [String] = []
    private var bag: Set<AnyCancellable> = []

    override func setUp() {
        super.setUp()
        drafts = InMemoryCalibrationDraftStore()
        session = CalibrationSession(drafts: drafts, clock: { Date(timeIntervalSince1970: 1_800_000_000) })
        target = CalibrationTarget(entryID: UUID(), contentKey: "sha256:" + String(repeating: "e", count: 64),
                                   pageIndex: 0, pageBox: box, rotate: 0)
        provisional = PdfGeoreference.provisional(pageBox: target.pageRect, rotation: 0,
                                                  centredOn: CLLocationCoordinate2D(latitude: 37.77, longitude: -122.44))
        toasts = []
        session.toasts.sink { [unowned self] in self.toasts.append($0) }.store(in: &bag)
    }

    override func tearDown() {
        bag.removeAll()
        super.tearDown()
    }

    // MARK: - helpers

    private func start(seed: CalibrationState = CalibrationState(), embeddedDatum: String? = nil) {
        session.start(target: target, entryName: "rot5", base: provisional, seed: seed,
                      embeddedDatumID: embeddedDatum, wasPreview: true)
    }

    private func capture(_ page: PdfPagePoint) -> CalibrationCapture? {
        guard let d = session.display, let c = d.georef.toWGS84(x: page.x, y: page.y) else { return nil }
        let cam = MapCamera(center: c, zoom: 17, headingDegrees: 0, viewportSize: CGSize(width: 390, height: 844))
        return CalibrationCapture.capture(georef: d.georef, pageBox: box, camera: cam, generation: d.generation)
    }

    @discardableResult
    private func add(_ i: Int) -> Bool {
        guard session.beginAdd(capture: capture(targets[i].0)) == .ok else { return false }
        session.entryText = targets[i].1
        return session.commitEntry()
    }

    private func point(_ i: Int, number: Int) -> CalibrationPoint {
        let e = [547_000.0, 551_000, 551_000, 547_000][i], n = [4_177_000.0, 4_177_000, 4_183_000, 4_183_000][i]
        return CalibrationPoint(number: number, page: targets[i].0, input: targets[i].1,
                                reference: .grid(zone: 10, south: false, easting: e, northing: n, cellSizeM: 1, source: .mgrs))
    }

    private func draft(_ points: [CalibrationPoint], active: Bool) -> CalibrationDraft {
        CalibrationDraft(contentKey: target.contentKey, pageIndex: 0, entryId: target.entryID, datumId: "WGS84",
                         points: points, nextNumber: (points.map(\.number).max() ?? 0) + 1, pending: nil,
                         active: active, updatedAtMs: 1)
    }

    /// every calibration_* / map_* key the table names has to be real copy for iOS
    private func catalogKeys(in any: Any) -> Set<String> {
        var out = Set<String>()
        func walk(_ a: Any) {
            if let s = a as? String, s.range(of: #"^(calibration|map)_[a-z_]+$"#, options: .regularExpression) != nil { out.insert(s) }
            // button tables use the key itself as the dictionary key
            if let d = a as? [String: Any] { for (k, v) in d { walk(k); walk(v) } }
            if let l = a as? [Any] { l.forEach(walk) }
        }
        walk(any)
        return out
    }

    // MARK: - tables

    func testEveryLifecycleMessageKeyIsInTheCatalogForIOS() throws {
        let url = try XCTUnwrap({ () -> URL? in
            var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            for _ in 0..<8 {
                let c = dir.appendingPathComponent("localization/catalog.json")
                if FileManager.default.fileExists(atPath: c.path) { return c }
                dir = dir.deletingLastPathComponent()
            }
            return nil
        }())
        let catalog = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let keys = catalogKeys(in: life)
        XCTAssertGreaterThanOrEqual(keys.count, 20, "lifecycle lost its keys")
        for k in keys.sorted() {
            let entry = catalog[k] as? [String: Any]
            XCTAssertNotNil(entry, "\(k) missing from the catalog")
            XCTAssertTrue((entry?["platforms"] as? [String] ?? []).contains("ios"), "\(k) not on iOS")
        }
    }

    func testEntryPointsAndStartPreconditions() {
        let entries = life["entryPoints"] as? [[String: Any]] ?? []
        XCTAssertEqual(entries.compactMap { $0["id"] as? String }, ["E1", "E2", "E3"], "the only three")
        XCTAssertEqual((life["startPreconditions"] as? [Any])?.count, 3)
        // E3 toast: an active draft resumes silently and says how many points
        let e3 = entries.first { $0["id"] as? String == "E3" }
        let toast = e3?["toast"] as? [String: Any]
        XCTAssertEqual(toast?["key"] as? String, "calibration_resumed")
        let n = (toast?["args"] as? [String: Any])?["points"] as? Int ?? 0
        drafts.drafts[target.draftKey] = draft((0..<n).map { point($0, number: $0 + 1) }, active: true)
        start()
        XCTAssertEqual(session.state.points.count, n)
        XCTAssertEqual(toasts.last, Messages.calibrationResumed(Messages.calibrationPointCount(n)))
        // not already calibrating: a second start while one runs is the caller's
        // guard (ContentView.startCalibration), the session is still the same one
        XCTAssertTrue(session.isCalibrating)
    }

    func testSeedTable() {
        let rows = life["seed"] as? [[String: Any]] ?? []
        XCTAssertEqual(rows.count, 3)
        let manual = CalibrationState(datumID: "WGS84", points: [point(0, number: 1), point(1, number: 2)])
        let drafted = [point(0, number: 1), point(1, number: 2), point(2, number: 3)]
        for row in rows {
            let hasDraft = row["draft"] as? Bool ?? false, hasManual = row["manual"] as? Bool ?? false
            setUp()
            if hasDraft { drafts.drafts[target.draftKey] = draft(drafted, active: false) }
            start(seed: hasManual ? manual : CalibrationState())
            if session.phase == .resumePrompt { session.resume() }
            switch row["use"] as? String {
            case "draft": XCTAssertEqual(session.state.points.count, 3, "\(row)")
            case "manual": XCTAssertEqual(session.state.points.count, 2, "\(row)")
            case "empty":
                XCTAssertTrue(session.state.points.isEmpty)
                XCTAssertEqual(session.phase, .datumSheet, "no datum yet: the sheet comes first")
                start(embeddedDatum: "ED50")
                XCTAssertEqual(session.phase, .placing, "a GeoPDF preselects its own datum")
                XCTAssertEqual(session.state.datumID, "ED50")
            default: XCTFail("unknown seed row \(row)")
            }
        }
    }

    func testResumeTable() {
        let rows = life["resume"] as? [[String: Any]] ?? []
        XCTAssertEqual(rows.count, 4)
        let seed = CalibrationState(datumID: "WGS84", points: [point(0, number: 1)])
        for row in rows {
            setUp()
            if row["draftExists"] as? Bool == true {
                let pts = row["draftDiffersFromSeed"] as? Bool == true ? [point(0, number: 1), point(1, number: 2)] : seed.points
                drafts.drafts[target.draftKey] = draft(pts, active: row["draftActive"] as? Bool ?? false)
            }
            start(seed: seed)
            switch row["action"] as? String {
            case "none":
                XCTAssertNotEqual(session.phase, .resumePrompt, "\(row)")
                XCTAssertEqual(session.state.points, seed.points)
            case "prompt":
                XCTAssertEqual(session.phase, .resumePrompt)
                let p = row["prompt"] as? [String: Any]
                XCTAssertEqual(p?["title"] as? String, "calibration_resume_title")
                XCTAssertEqual(Set((p?["buttons"] as? [String: Any] ?? [:]).keys), ["calibration_resume", "calibration_start_over"])
                XCTAssertEqual(session.resumeDraft?.points.count, 2)
                // Start over: draft gone, seed used
                session.startOver()
                XCTAssertNil(drafts.drafts[target.draftKey])
                XCTAssertEqual(session.state.points, seed.points)
            case "silentResume":
                XCTAssertNotEqual(session.phase, .resumePrompt)
                XCTAssertEqual(session.state.points.count, 2)
            default: XCTFail("unknown resume row \(row)")
            }
        }
    }

    func testLeaveTableAndAfterLeave() throws {
        let rows = life["leave"] as? [[String: Any]] ?? []
        XCTAssertEqual(rows.count, 2)
        for row in rows {
            setUp()
            start()
            session.chooseDatum("WGS84")
            if row["dirty"] as? Bool == true { add(0) }
            session.leaveTapped()
            switch row["action"] as? String {
            case "endSession":
                XCTAssertFalse(session.isCalibrating)
            case "dialog":
                XCTAssertEqual(session.phase, .leaveDialog)
                XCTAssertEqual(row["title"] as? String, "calibration_leave_title")
                let buttons = row["buttons"] as? [[String: Any]] ?? []
                XCTAssertEqual(buttons.compactMap { $0["key"] as? String },
                               ["calibration_leave_keep", "calibration_leave_discard", "calibration_leave_continue"])
                // continue = cancel, still calibrating
                session.continueCalibrating()
                XCTAssertTrue(session.isCalibrating)
                XCTAssertEqual(session.phase, .placing)
                // keep = draft inactive
                session.leaveTapped()
                session.leave(keepDraft: true)
                XCTAssertFalse(session.isCalibrating)
                XCTAssertEqual(drafts.drafts[target.draftKey]?.active, false)
                // discard = draft gone
                start()
                session.resume()
                add(1)
                session.leaveTapped()
                session.leave(keepDraft: false)
                XCTAssertNil(drafts.drafts[target.draftKey])
            default: XCTFail("unknown leave row \(row)")
            }
        }
        XCTAssertNotNil((life["afterLeave"] as? [String: Any])?["preview"])
    }

    func testSuspend() {
        let s = life["suspend"] as? [String: Any] ?? [:]
        XCTAssertEqual((s["toast"] as? [String: Any])?["key"] as? String, "calibration_paused")
        start()
        session.chooseDatum("WGS84")
        add(0)
        session.suspend()
        XCTAssertFalse(session.isCalibrating)
        XCTAssertEqual(drafts.drafts[target.draftKey]?.active, false)
        XCTAssertEqual(toasts.last, Messages.calibrationPaused())
    }

    func testCommitSuccessToastsAndFailure() throws {
        let c = life["commit"] as? [String: Any] ?? [:]
        let success = c["success"] as? [Any] ?? []
        let toastsRow = success.compactMap { $0 as? [String: Any] }.first ?? [:]
        XCTAssertEqual((toastsRow["n3"] as? [String: Any])?["key"] as? String, "calibration_done_exact")
        XCTAssertEqual((toastsRow["n4plus"] as? [String: Any])?["key"] as? String, "calibration_done")

        // n = 3: exact, confirm, then the unchecked toast
        start()
        session.chooseDatum("WGS84")
        for i in 0..<3 { add(i) }
        XCTAssertNil(session.finishTapped())
        XCTAssertNotNil(session.finishAnyway())
        session.didCommit()
        XCTAssertEqual(toasts.last, Messages.calibrationDoneExact())
        XCTAssertNil(drafts.drafts[target.draftKey], "finish success drops the draft")

        // n = 4: ready, points + RMS
        setUp()
        start()
        session.chooseDatum("WGS84")
        for i in 0..<4 { add(i) }
        let manual = try XCTUnwrap(session.finishTapped())
        XCTAssertEqual(manual.n, 4)
        let rms = try XCTUnwrap(manual.rmsM)
        session.didCommit()
        // OD-F12 replaced the whole metres here: one decimal under 9.95 m
        XCTAssertEqual(toasts.last, Messages.calibrationDone(Messages.calibrationPointCount(4), DisplayFormat.rms(rms)))

        // failure: stays, draft kept, Retry / Not now
        let failure = c["failure"] as? [String: Any] ?? [:]
        XCTAssertEqual((failure["alert"] as? [String: Any])?["key"] as? String, "calibration_save_failed")
        XCTAssertEqual(failure["buttons"] as? [String], ["Retry", "Not Now"])
        setUp()
        start()
        session.chooseDatum("WGS84")
        for i in 0..<4 { add(i) }
        XCTAssertNotNil(session.finishTapped())
        session.commitFailed()
        XCTAssertTrue(session.isCalibrating, "never a silent no-op, never ends the session")
        XCTAssertEqual(session.phase, .saveFailed)
        XCTAssertEqual(drafts.drafts[target.draftKey]?.points.count, 4)
        XCTAssertEqual(drafts.drafts[target.draftKey]?.active, true)
    }

    func testFinishConfirmButtons() {
        let f = life["finishConfirm"] as? [String: Any] ?? [:]
        XCTAssertEqual(f["title"] as? String, "calibration_finish_confirm_title")
        let b = f["buttons"] as? [[String: Any]] ?? []
        XCTAssertEqual(b.compactMap { $0["key"] as? String }, ["calibration_add_more", "calibration_finish_anyway"])
        XCTAssertEqual(b.first?["role"] as? String, "cancel")
        XCTAssertEqual(b.first?["default"] as? Bool, true)
    }

    func testDraftRules() throws {
        let d = life["draft"] as? [String: Any] ?? [:]
        XCTAssertEqual(d["maxDrafts"] as? Int, CalibrationLimits.maxDrafts)
        XCTAssertEqual(d["keyFormat"] as? String, "<contentKey>#<pageIndex>")
        XCTAssertEqual(CalibrationDraft.key(contentKey: "sha256:ab", pageIndex: 3), "sha256:ab#3")
        XCTAssertEqual((d["writeFailure"] as? [String: Any])?["key"] as? String, "calibration_draft_unsaved")
        let deletedOn = d["deletedOn"] as? [String] ?? []
        XCTAssertEqual(deletedOn.count, 5)
        // reconcile without its contentKey: prune drops it, keeps the rest
        let store = InMemoryCalibrationDraftStore()
        try store.save(draft([], active: false))
        var other = draft([], active: false)
        other.contentKey = "sha256:" + String(repeating: "f", count: 64)
        try store.save(other)
        store.prune(keepingContentKeys: [other.contentKey])
        XCTAssertEqual(Array(store.drafts.keys), [other.key])
        // finish success + discard are in testCommitSuccessToastsAndFailure /
        // testLeaveTableAndAfterLeave; entry delete in ImportedMapLibraryTests;
        // change page drops the old page's draft in MapViewModel.changePage
        XCTAssertTrue(deletedOn.contains("change page"))
    }

    func testMutationRules() {
        let m = life["mutations"] as? [String: Any] ?? [:]
        XCTAssertEqual(m["undoDepth"] as? Int, CalibrationLimits.undoDepth)
        // undo depth is really capped: 55 datum flips, 50 undos, then nothing
        start()
        session.chooseDatum("WGS84")
        add(0)
        for i in 0..<55 { session.chooseDatum(i % 2 == 0 ? "ED50" : "WGS84") }
        var undos = 0
        while session.canUndo { session.undo(); undos += 1 }
        XCTAssertEqual(undos, CalibrationLimits.undoDepth)

        // delete toast carries the stable number
        let del = m["deleteToast"] as? [String: Any] ?? [:]
        XCTAssertEqual(del["key"] as? String, "calibration_point_deleted")
        setUp()
        start()
        session.chooseDatum("WGS84")
        for i in 0..<3 { add(i) }
        let third = session.state.points[2]
        session.delete(third.id)
        XCTAssertEqual(toasts.last, Messages.calibrationPointDeleted(DisplayFormat.number(3, decimals: 0)))
        // datum toast names the new datum
        let dt = m["datumToast"] as? [String: Any] ?? [:]
        XCTAssertEqual(dt["key"] as? String, "calibration_datum_changed")
        let datum = (dt["args"] as? [String: Any])?["datum"] as? String ?? ""
        session.chooseDatum(datum)
        XCTAssertEqual(toasts.last, Messages.calibrationDatumChanged(CalibrationDatumChoice.displayName(datum)))
        XCTAssertEqual(((m["maxPointsStatus"] as? [String: Any])?["args"] as? [String: Any])?["max"] as? Int,
                       CalibrationLimits.maxCalibrationPoints)
    }

    func testLibraryWritesTable() throws {
        let rows = life["libraryWrites"] as? [[String: Any]] ?? []
        XCTAssertEqual(rows.count, 7)
        let g = try XCTUnwrap(provisional)
        func pdfEntry(embedded: PdfGeoreference?, derivedFrom: UUID? = nil, kind: ImportedMapEntry.Kind = .pdf) -> ImportedMapEntry {
            ImportedMapEntry(id: UUID(), kind: kind, fileName: "ImportedMaps/map-\(UUID().uuidString).pdf", displayName: "x",
                             contentKey: "sha256:" + String(repeating: "1", count: 64), byteCount: 1, fileModifiedAtMs: 0,
                             importedAtMs: 0, derivedFromId: derivedFrom,
                             pdf: kind == .pdf ? .init(pageCount: 2, pageIndex: 0, rotate: 0, pageBox: box, embedded: embedded) : nil)
        }
        let geo = pdfEntry(embedded: g)
        let plain = pdfEntry(embedded: nil)
        var base = LibraryState()
        base.entries = [geo, plain]
        base.active = .entry(geo.id)
        let manual = ManualCalibration(datumId: "WGS84", points: [], nextNumber: 1, georef: g, n: 4, rmsM: 1, grade: .good,
                                       savedAtMs: 0)
        for row in rows {
            let t = row["transition"] as? String ?? ""
            switch t {
            case "select online":
                let r = try LibraryReducer.apply(.selectOnline(.osmStreet), to: base)
                XCTAssertEqual(r.state.active, .online(.osmStreet))
            case "activate entry":
                var s = base
                s.active = .online(.osmTopo)
                XCTAssertEqual(try LibraryReducer.apply(.activateEntry(geo.id), to: s).state.active, .entry(geo.id))
                XCTAssertThrowsError(try LibraryReducer.apply(.activateEntry(plain.id), to: s), "uncalibrated is never durable")
            case "import commit":
                let new = pdfEntry(embedded: g)
                let r = try LibraryReducer.apply(.addEntry(new, activate: true), to: base)
                XCTAssertEqual(r.state.entries.count, 3, "importing B keeps A")
                XCTAssertEqual(r.state.active, .entry(new.id))
            case "commit calibration":
                let r = try LibraryReducer.apply(.commitCalibration(plain.id, manual, contentKey: plain.contentKey!, pageIndex: 0), to: base)
                XCTAssertEqual(r.state.entry(plain.id)?.pdf?.manual?.n, 4)
                XCTAssertEqual(r.state.active, .entry(plain.id))
            case "revert to embedded":
                var s = base
                s.entries[0].pdf?.manual = manual
                let r = try LibraryReducer.apply(.revertToEmbedded(geo.id), to: s)
                XCTAssertNil(r.state.entry(geo.id)?.pdf?.manual)
                XCTAssertEqual(r.state.active, .entry(geo.id), "still has its own georef")
            case "change page":
                var s = base
                s.entries[0].pdf?.manual = manual
                let r = try LibraryReducer.apply(.changePage(geo.id, pageIndex: 1, rotate: 90, pageBox: box,
                                                             embedded: nil, embeddedIssue: "rmsGate"), to: s)
                let e = try XCTUnwrap(r.state.entry(geo.id)?.pdf)
                XCTAssertEqual(e.pageIndex, 1)
                XCTAssertNil(e.manual)
                XCTAssertNil(e.embedded)
                XCTAssertEqual(e.embeddedIssue, "rmsGate")
                if case .entry? = r.state.active { XCTFail("no georef on the new page, never durable") }
            case "delete":
                let tiles = pdfEntry(embedded: nil, derivedFrom: geo.id, kind: .mbtiles)
                var s = base
                s.entries.append(tiles)
                s.active = .entry(tiles.id)
                let r = try LibraryReducer.apply(.deleteEntry(geo.id), to: s)
                XCTAssertEqual(Set(r.removed.map(\.id)), [geo.id, tiles.id], "derived tiles go with it")
                XCTAssertEqual(r.state.active, .online(s.preferredStyle))
                let confirm = row["confirm"] as? [String: Any]
                XCTAssertEqual((confirm?["title"] as? [String: Any])?["key"] as? String, "map_delete_title")
                XCTAssertEqual(confirm?["message"] as? String, "map_delete_message")
            default:
                XCTFail("unknown library write \(t)")
            }
        }
    }

    // MARK: - r1 tables (T3)

    /// C1/C5/C6: the draft's active flag after each step sequence, through the
    /// real session and draft store
    func testDraftActiveTable() throws {
        let rows = life["draftActive"] as? [[String: Any]] ?? []
        XCTAssertEqual(rows.count, 18)
        for row in rows {
            let id = row["id"] as? String ?? "?"
            setUp()
            switch row["draftBefore"] as? String {
            case "active": drafts.drafts[target.draftKey] = draft([], active: true)
            case "inactive": drafts.drafts[target.draftKey] = draft([], active: false)
            default: break
            }
            let hadDraft = drafts.drafts[target.draftKey] != nil
            var dead = false
            for step in row["steps"] as? [String] ?? [] {
                switch step {
                case "start":
                    start()
                    if session.phase == .datumSheet { session.chooseDatum("WGS84") }
                case "startPlainPdf": start()
                case "chooseDatumFromInitialSheet": session.chooseDatum("WGS84")
                case "beginAdd": XCTAssertEqual(session.beginAdd(capture: capture(targets[0].0)), .ok, id)
                case "cancelEntry": session.cancelEntry()
                case "savePoint":
                    if session.pendingEntry == nil { XCTAssertEqual(session.beginAdd(capture: capture(targets[0].0)), .ok, id) }
                    session.entryText = targets[0].1
                    XCTAssertTrue(session.commitEntry(), id)
                case "savePoint3": for i in 0..<3 { XCTAssertTrue(add(i), id) }
                case "undo": session.undo()
                case "leave": session.leaveTapped()
                case "leaveKeep": session.leave(keepDraft: true)
                case "leaveDiscard": session.leave(keepDraft: false)
                case "leaveContinue": session.continueCalibrating()
                case "suspend": session.suspend()
                case "resumePromptResume":
                    XCTAssertEqual(session.phase, .resumePrompt, id)
                    session.resume()
                case "resumePromptStartOver":
                    XCTAssertEqual(session.phase, .resumePrompt, id)
                    session.startOver()
                case "launchAutoResume":
                    session.start(target: target, entryName: "rot5", base: provisional, seed: CalibrationState(),
                                  embeddedDatumID: nil, wasPreview: true, resumeSilently: true)
                case "processDeath": dead = true
                case "finish":
                    if session.finishTapped() == nil, case .finishConfirm = session.phase { XCTAssertNotNil(session.finishAnyway(), id) }
                    session.didCommit()
                case "setDatum": session.chooseDatum("ED50")
                default: XCTFail("\(id): unknown step \(step)")
                }
            }
            let e = row["expect"] as? [String: Any] ?? [:]
            let d = drafts.drafts[target.draftKey]
            switch e["draft"] as? String {
            case "none": XCTAssertTrue(d == nil && !hadDraft && drafts.writes == 0, "\(id) draft none")
            case "active": XCTAssertEqual(d?.active, true, "\(id) draft active")
            case "inactive": XCTAssertEqual(d?.active, false, "\(id) draft inactive")
            case "deleted": XCTAssertTrue(d == nil && (hadDraft || drafts.writes > 0), "\(id) draft deleted")
            default: XCTFail("\(id): unknown draft state")
            }
            let ended = dead || !session.isCalibrating
            XCTAssertEqual(ended ? "ended" : "running", e["session"] as? String, "\(id) session")
            if let t = e["toast"] as? [String: Any] {
                XCTAssertEqual(toasts.last, Self.render(t), "\(id) toast")
            } else {
                XCTAssertTrue(toasts.isEmpty, "\(id) toasts \(toasts)")
            }
        }
    }

    private static func render(_ t: [String: Any]) -> String? {
        let args = t["args"] as? [String: Any] ?? [:]
        switch t["key"] as? String {
        case "calibration_paused": return Messages.calibrationPaused()
        case "calibration_done_exact": return Messages.calibrationDoneExact()
        case "calibration_datum_changed":
            return Messages.calibrationDatumChanged(CalibrationDatumChoice.displayName(args["datum"] as? String ?? ""))
        case "calibration_resumed":
            return Messages.calibrationResumed(Messages.calibrationPointCount(args["points"] as? Int ?? 0))
        default: return nil
        }
    }

    /// E3 (M10, C2, C6, C8, F3): the launch decision, then what a resume does
    func testAutoResumeTable() throws {
        let rows = life["autoResume"] as? [[String: Any]] ?? []
        XCTAssertEqual(rows.count, 12)
        for row in rows {
            let id = row["id"] as? String ?? "?"
            setUp()
            let dj = row["draft"] as? [String: Any]
            if let dj {
                let n = dj["points"] as? Int ?? 0
                var d = draft((0..<n).map { point($0, number: $0 + 1) }, active: dj["active"] as? Bool ?? false)
                if dj["pending"] as? Bool == true { d.pending = CalibrationPending(page: targets[3].0, editingId: nil) }
                drafts.drafts[target.draftKey] = d
            }
            let entry: ImportedMapState? = ["ok": ImportedMapState.calibrated, "unavailable": .unavailable][row["entry"] as? String ?? ""]
            let library: LibraryLoadStatus = ["loaded": .loaded, "locked": .locked, "corrupt": .corrupt][row["library"] as? String ?? ""] ?? .unknown
            let action = CalibrationAutoResume.decide(draftActive: (dj?["active"] as? Bool), entry: entry, library: library,
                                                      suspectPending: row["crashSuspect"] as? String != "none")
            let e = row["expect"] as? [String: Any] ?? [:]
            XCTAssertEqual(action.rawValue, e["action"] as? String, id)
            XCTAssertEqual(action == .resume, e["frameSheet"] as? Bool, "\(id) frameSheet")
            if action == .resume {
                session.start(target: target, entryName: "rot5", base: provisional, seed: CalibrationState(),
                              embeddedDatumID: nil, wasPreview: true, resumeSilently: true)
                XCTAssertEqual(session.pendingEntry != nil, e["reopenPending"] as? Bool, "\(id) reopenPending")
            } else {
                XCTAssertEqual(e["reopenPending"] as? Bool, false, id)
            }
            switch e["draftAfter"] as? String {
            case "active"?: XCTAssertEqual(drafts.drafts[target.draftKey]?.active, true, "\(id) draftAfter")
            case "inactive"?: XCTAssertEqual(drafts.drafts[target.draftKey]?.active, false, "\(id) draftAfter")
            default: XCTAssertNil(drafts.drafts[target.draftKey], "\(id) draftAfter")
            }
            if let t = e["toast"] as? [String: Any] {
                XCTAssertEqual(toasts.last, Self.render(t), "\(id) toast")
            } else {
                XCTAssertTrue(toasts.isEmpty, "\(id) toasts \(toasts)")
            }
        }
    }

    /// E1/E4/E14: Choose page on a library entry
    func testChoosePageTable() throws {
        let cp = life["choosePage"] as? [String: Any] ?? [:]
        let rows = cp["rows"] as? [[String: Any]] ?? []
        XCTAssertEqual(rows.count, 5)
        for r in rows {
            let id = r["id"] as? String ?? "?"
            let got = ChoosePageDecision.decide(currentPage: r["currentPage"] as? Int ?? -1, pickedPage: r["pickedPage"] as? Int ?? -2,
                                                hasManual: r["hasManual"] as? Bool ?? false,
                                                pickedPageHasValidGeoref: r["pickedPageGeoref"] as? String == "valid",
                                                entryIsActive: r["entryIsActive"] as? Bool ?? false)
            let e = r["expect"] as? [String: Any] ?? [:]
            XCTAssertEqual(got.confirm, e["confirm"] as? String, id)
            XCTAssertEqual(got.write, e["write"] as? Bool, id)
            XCTAssertEqual(got.then.rawValue, e["then"] as? String, id)
            XCTAssertEqual(got.activeOnline, e["activeOnline"] as? Bool ?? false, "\(id) activeOnline")
            // the write itself agrees about the online fallback
            if got.write {
                var entry = ImportedMapEntry(id: UUID(), kind: .pdf, fileName: "ImportedMaps/map-x.pdf", displayName: "x",
                                             contentKey: "sha256:" + String(repeating: "2", count: 64), byteCount: 1,
                                             fileModifiedAtMs: 0, importedAtMs: 0, derivedFromId: nil,
                                             pdf: .init(pageCount: 3, pageIndex: r["currentPage"] as? Int ?? 0, rotate: 0,
                                                        pageBox: box, embedded: provisional))
                entry.pdf?.manual = (r["hasManual"] as? Bool ?? false)
                    ? ManualCalibration(datumId: "WGS84", points: [], nextNumber: 1, georef: provisional, n: 4, rmsM: 1,
                                        grade: .good, savedAtMs: 0) : nil
                var s = LibraryState()
                s.entries = [entry]
                s.active = (r["entryIsActive"] as? Bool ?? false) ? .entry(entry.id) : .online(.osmTopo)
                let next = try LibraryReducer.apply(.changePage(entry.id, pageIndex: r["pickedPage"] as? Int ?? 0, rotate: 0, pageBox: box,
                                                                embedded: got.then == .activate ? provisional : nil, embeddedIssue: nil),
                                                    to: s).state
                XCTAssertNil(next.entries[0].pdf?.manual, "\(id) the calibration goes")
                if got.activeOnline { XCTAssertEqual(next.active, .online(s.preferredStyle), id) }
            }
        }
        // E14: its own picker message, not the import one
        let pm = cp["pickerMessage"] as? [String: Any] ?? [:]
        XCTAssertEqual(pm["key"] as? String, "map_choose_page_message")
        let pages = String((pm["args"] as? [String: Any])?["pages"] as? Int ?? 0)
        XCTAssertNotEqual(Messages.mapChoosePageMessage(pages), Messages.mapImportChoosePageMessage(pages))
        XCTAssertTrue(Messages.mapChoosePageMessage(pages).contains(pages))
    }

    /// E2: Cancel only while copying or reading; the cancelled toast
    func testImportPipelineTable() {
        let p = life["importPipeline"] as? [String: Any] ?? [:]
        XCTAssertEqual(p["order"] as? [String], ["precheck", "copyAndHash", "dedupe", "inspect", "decision", "probe", "write"])
        XCTAssertEqual(p["duplicateSkips"] as? [String], ["inspect", "decision", "probe"])
        let visible = Set(p["cancelVisible"] as? [String] ?? [])
        let stages: [(String, ImportProgress)] = [("copying", .copying(done: 1, total: 2)), ("reading", .reading(page: 1, pages: 2)),
                                                  ("saving", .saving)]
        for (name, stage) in stages {
            XCTAssertEqual(MapImportController.cancelVisible(stage), visible.contains(name), name)
        }
        XCTAssertFalse(MapImportController.cancelVisible(nil))
        let onCancel = p["onCancel"] as? [String: Any] ?? [:]
        XCTAssertEqual((onCancel["toast"] as? [String: Any])?["key"] as? String, MapImportError.cancelled.messageKey)
        XCTAssertEqual(onCancel["write"] as? Bool, false)
        XCTAssertEqual(p["cancelRechecked"] as? [String], ["after copyAndHash", "after inspect", "after probe"])
    }

    // MARK: - georefRejectReasons

    func testGeorefRejectReasonsMatchAndAllHaveCopy() {
        let want = fx["georefRejectReasons"] as? [String] ?? []
        XCTAssertEqual(want.count, 8)
        XCTAssertEqual(Set(PdfGeorefRejectReason.allCases.map(\.rawValue)), Set(want))
        for r in PdfGeorefRejectReason.allCases {
            XCTAssertFalse(r.displayReason.isEmpty, r.rawValue)
            XCTAssertNotEqual(r.displayReason, r.rawValue, "\(r.rawValue) shows the raw code")
            // the alert sentence carries the clause, not the code
            let alert = CalibrationMessage(key: "map_import_georef_rejected", args: ["reason": .text(r.rawValue)]).text
            XCTAssertTrue(alert.contains(r.displayReason), r.rawValue)
        }
    }
}

/// Camera framing while calibrating: the page gets framed once when the
/// crosshair isn't on it (auto resume at launch), never yanked otherwise.
final class CalibrationFramingTests: XCTestCase {

    private let box = CalibrationTarget.box(CGRect(x: 0, y: 0, width: 900.7839644, height: 1106.9282314))

    func testPageFrameOnlyWhenTheCrosshairIsOffTheSheet() throws {
        let g = try XCTUnwrap(PdfGeoreference.provisional(pageBox: CGRect(x: 0, y: 0, width: 900.7839644, height: 1106.9282314),
                                                          rotation: 0, centredOn: CLLocationCoordinate2D(latitude: 37.77, longitude: -122.44)))
        let viewport = CGSize(width: 402, height: 874)
        // camera where the app starts (0,0, zoom 4): frame the page
        let far = MapCamera(center: CLLocationCoordinate2D(latitude: 0, longitude: 0), zoom: 4, headingDegrees: 30, viewportSize: viewport)
        let framed = try XCTUnwrap(TileMapContainer.Coordinator.pageFrame(g, pageBox: box, camera: far, viewport: viewport))
        let centre = try XCTUnwrap(g.toWGS84(x: 900.7839644 / 2, y: 1106.9282314 / 2))
        XCTAssertEqual(framed.center.latitude, centre.latitude, accuracy: 1e-9)
        XCTAssertEqual(framed.center.longitude, centre.longitude, accuracy: 1e-9)
        XCTAssertEqual(framed.headingDegrees, 30, "heading untouched")
        // the whole page fits: both sides on screen at that zoom
        let spp = try XCTUnwrap(CalibrationCameraAnchor.screenPointsPerPagePoint(g, at: PdfPagePoint(x: 450, y: 553), zoom: framed.zoom))
        XCTAssertLessThanOrEqual(900.7839644 * spp, Double(viewport.width) + 1e-6)
        XCTAssertLessThanOrEqual(1106.9282314 * spp, Double(viewport.height) + 1e-6)
        XCTAssertGreaterThan(1106.9282314 * spp, Double(viewport.height) * 0.4, "not a speck either")
        // on the sheet already: nothing moves
        let on = MapCamera(center: centre, zoom: 17, headingDegrees: 0, viewportSize: viewport)
        XCTAssertNil(TileMapContainer.Coordinator.pageFrame(g, pageBox: box, camera: on, viewport: viewport))
        // zero bounds on the first layout pass still frames
        XCTAssertNotNil(TileMapContainer.Coordinator.pageFrame(g, pageBox: box, camera: far, viewport: .zero))
    }
}
