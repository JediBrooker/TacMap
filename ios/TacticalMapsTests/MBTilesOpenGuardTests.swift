import XCTest
import SQLite3
@testable import TacticalMaps

/// 3.0.2 M1 on iOS: the MBTiles open guard (import_limits.json
/// mbtilesOpenGuard), its wiring in MapViewModel, and the import / rebuild /
/// migration markers round every other MBTiles open.
final class MBTilesOpenGuardTests: XCTestCase {

    private var dir: URL!
    private var originalSupport: (() -> URL)!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("mbtiles-guard-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        originalSupport = ImportedMapStorage.applicationSupportProvider
        let support = dir.appendingPathComponent("support", isDirectory: true)
        ImportedMapStorage.applicationSupportProvider = { support }
    }

    override func tearDownWithError() throws {
        MBTilesStore.admissionOpenHookForTesting = nil
        ImportedMapStorage.applicationSupportProvider = originalSupport
        try? FileManager.default.removeItem(at: dir)
    }

    private func fixture() throws -> [String: Any] {
        let url = try XCTUnwrap(PDFTileRenderFixtureTests.testdataURL("import_limits.json"))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        return try XCTUnwrap(json["mbtilesOpenGuard"] as? [String: Any])
    }

    private func stateJSON(_ s: MBTilesOpenGuardState) -> [String: Any] {
        ["v": 1, "inProgress": s.inProgress, "suspect": s.suspect.map { $0 as Any } ?? NSNull()]
    }

    // MARK: fixture

    func testConstantsMatchTheSharedFixture() throws {
        let fx = try fixture()
        XCTAssertEqual(fx["fileName"] as? String, MBTilesOpenGuard.fileName)
        XCTAssertEqual(fx["fileVersion"] as? Int, MBTilesOpenGuardState.fileVersion)
        XCTAssertEqual(fx["maxInProgress"] as? Int, MBTilesOpenGuardState.maxInProgress)
        XCTAssertEqual(fx["firstDrawQuietMs"] as? Int, MBTilesFirstDrawWatch.firstDrawQuietMs)
        XCTAssertEqual(fx["noReadCompleteMs"] as? Int, MBTilesFirstDrawWatch.noReadCompleteMs)
        // next to the PDF guard's file, Application Support
        XCTAssertEqual(MBTilesOpenGuard.defaultURL.deletingLastPathComponent(),
                       PDFRenderGuard.defaultURL.deletingLastPathComponent())
        XCTAssertEqual(MBTilesOpenGuard.defaultURL.lastPathComponent, "mbtiles_open_guard.json")
    }

    /// one step of a fixture case on the pure reducer, result as the fixture writes it
    private func apply(_ event: [String: Any], to s: inout MBTilesOpenGuardState) throws -> [String: String] {
        switch try XCTUnwrap(event["op"] as? String) {
        case "arm":
            let armed = MBTilesOpenGuardReducer.arm(&s, token: try XCTUnwrap(event["token"] as? String),
                                                    foreground: try XCTUnwrap(event["foreground"] as? Bool))
            return ["armed": armed ? "true" : "false"]
        case "complete":
            MBTilesOpenGuardReducer.complete(&s, token: try XCTUnwrap(event["token"] as? String))
        case "disarmBackground":
            MBTilesOpenGuardReducer.disarmBackground(&s)
        case "launch":
            return ["decision": MBTilesOpenGuardReducer.launch(&s, restoredToken: event["restoredToken"] as? String).rawValue]
        case "resolve":
            let choice = try XCTUnwrap(PDFSuspectResolution(rawValue: try XCTUnwrap(event["choice"] as? String)))
            MBTilesOpenGuardReducer.resolve(&s, choice)
        case let op:
            XCTFail("unknown op \(op)")
        }
        return [:]
    }

    private func expectedResult(_ step: [String: Any]) -> [String: String] {
        var out: [String: String] = [:]
        for (k, v) in step["result"] as? [String: Any] ?? [:] {
            if let b = v as? Bool { out[k] = b ? "true" : "false" } else { out[k] = "\(v)" }
        }
        return out
    }

    func testSharedReducerCases() throws {
        let cases = try XCTUnwrap(try fixture()["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 9)
        for c in cases {
            let label = try XCTUnwrap(c["label"] as? String)
            var s = MBTilesOpenGuardState()
            for (i, step) in try XCTUnwrap(c["steps"] as? [[String: Any]], label).enumerated() {
                let result = try apply(try XCTUnwrap(step["event"] as? [String: Any]), to: &s)
                XCTAssertEqual(result, expectedResult(step), "\(label) step \(i)")
                let want = try XCTUnwrap(step["state"] as? [String: Any])
                XCTAssertEqual(s.inProgress, want["inProgress"] as? [String], "\(label) step \(i)")
                XCTAssertEqual(s.suspect, want["suspect"] as? String, "\(label) step \(i)")
                // and what goes on disk reads back as the same state
                XCTAssertEqual(MBTilesOpenGuardState.decode(try XCTUnwrap(s.encoded())), s, "\(label) step \(i)")
            }
        }
    }

    /// the same cases through the durable store, a fresh process (reload from
    /// the file) before every step
    func testSharedCasesThroughTheFileWithAReloadEveryStep() throws {
        let cases = try XCTUnwrap(try fixture()["cases"] as? [[String: Any]])
        for (n, c) in cases.enumerated() {
            let url = dir.appendingPathComponent("case-\(n).json")
            for step in try XCTUnwrap(c["steps"] as? [[String: Any]]) {
                let g = MBTilesOpenGuard(url: url)
                let event = try XCTUnwrap(step["event"] as? [String: Any])
                switch event["op"] as? String {
                case "arm":
                    _ = g.arm(token: try XCTUnwrap(event["token"] as? String), foreground: event["foreground"] as? Bool ?? true)
                case "complete": g.complete(token: try XCTUnwrap(event["token"] as? String))
                case "disarmBackground": g.disarmForBackground()
                case "launch":
                    XCTAssertEqual(g.launchDecision(restoredToken: event["restoredToken"] as? String).rawValue,
                                   expectedResult(step)["decision"])
                case "resolve":
                    g.resolveSuspect(try XCTUnwrap(PDFSuspectResolution(rawValue: event["choice"] as? String ?? "")))
                default: XCTFail("op")
                }
                let want = try XCTUnwrap(step["state"] as? [String: Any])
                let onDisk = MBTilesOpenGuard(url: url).snapshot
                XCTAssertEqual(onDisk.inProgress, want["inProgress"] as? [String])
                XCTAssertEqual(onDisk.suspect, want["suspect"] as? String)
            }
        }
    }

    /// two overlapping opens of one pack: one marker on disk, it comes off with
    /// the last complete, not the first
    func testOverlappingArmsOfOnePackKeepTheMarkerUntilTheLastCompletes() {
        let url = dir.appendingPathComponent("overlap.json")
        let g = MBTilesOpenGuard(url: url)
        let a = UUID().uuidString, b = UUID().uuidString
        XCTAssertTrue(g.arm(token: a, foreground: true))
        XCTAssertTrue(g.arm(token: b, foreground: true))
        XCTAssertTrue(g.arm(token: a, foreground: true))
        XCTAssertEqual(MBTilesOpenGuard(url: url).snapshot.inProgress, [b, a], "still one per pack on disk")
        g.complete(token: a)
        XCTAssertEqual(MBTilesOpenGuard(url: url).snapshot.inProgress, [b, a], "the other open of a is still running")
        g.complete(token: b)
        g.complete(token: a)
        XCTAssertEqual(MBTilesOpenGuard(url: url).snapshot.inProgress, [])
        // a background arm never counts, the foreground one still clears alone
        XCTAssertFalse(g.arm(token: a, foreground: false))
        XCTAssertTrue(g.arm(token: a, foreground: true))
        g.complete(token: a)
        XCTAssertEqual(MBTilesOpenGuard(url: url).snapshot.inProgress, [])
        // an extra complete stays a no op
        g.complete(token: a)
        XCTAssertEqual(MBTilesOpenGuard(url: url).snapshot.inProgress, [])
    }

    func testFileDecodeIsStrictAndOnlyHoldsEntryIds() throws {
        let a = UUID().uuidString, b = UUID().uuidString, c = UUID().uuidString, d = UUID().uuidString
        let e = UUID().uuidString
        XCTAssertEqual(MBTilesOpenGuardState.decode(Data("junk".utf8)), MBTilesOpenGuardState())
        XCTAssertEqual(MBTilesOpenGuardState.decode(Data("{\"v\":2,\"inProgress\":[\"\(a)\"],\"suspect\":\"\(a)\"}".utf8)),
                       MBTilesOpenGuardState())
        let messy = "{\"v\":1,\"inProgress\":[\"/etc/passwd\",\"\(a)\",\"\(b)\",7,\"\(c)\",\"\(d)\",\"\(e)\"],\"suspect\":\"x\"}"
        let s = MBTilesOpenGuardState.decode(Data(messy.utf8))
        XCTAssertEqual(s.inProgress, [b, c, d, e], "non UUIDs dropped, the newest 4 kept")
        XCTAssertNil(s.suspect)
        // written atomically, outside backups, nothing but the state
        let url = dir.appendingPathComponent(MBTilesOpenGuard.fileName)
        XCTAssertFalse(MBTilesOpenGuard(url: url).arm(token: "not-a-uuid", foreground: true))
        XCTAssertTrue(MBTilesOpenGuard(url: url).arm(token: a, foreground: true))
        let text = try String(contentsOf: url, encoding: .utf8)
        XCTAssertEqual(text, "{\"v\":1,\"inProgress\":[\"\(a)\"],\"suspect\":null}")
        XCTAssertEqual(try url.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
        try Data("{\"v\":1,\"inProgress\":".utf8).write(to: url)
        XCTAssertEqual(MBTilesOpenGuard(url: url).launchDecision(restoredToken: a), .none, "a torn file starts clean")
    }

    // MARK: first draw

    /// timers by hand: (ms, work) queued, fired when the test says
    private final class ManualClock {
        var queued: [(ms: Int, work: () -> Void)] = []
        lazy var schedule: MBTilesFirstDrawWatch.Scheduler = { [unowned self] ms, work in self.queued.append((ms, work)) }
        func fire(_ ms: Int) {
            let due = queued.filter { $0.ms == ms }
            queued.removeAll { $0.ms == ms }
            due.forEach { $0.work() }
        }
    }

    func testFirstDrawSettlesAfterADeliveredReadAndAQuietSpell() {
        let clock = ManualClock()
        var settled = 0
        let watch = MBTilesFirstDrawWatch(schedule: clock.schedule) { settled += 1 }
        watch.start()
        watch.readStarted()
        watch.readStarted()
        watch.readFinished(delivered: true)
        XCTAssertTrue(clock.queued.filter { $0.ms == 500 }.isEmpty, "one still pending, no quiet timer yet")
        watch.readFinished(delivered: true)
        // a new read inside the quiet spell restarts it
        watch.readStarted()
        clock.fire(500)
        XCTAssertEqual(settled, 0)
        watch.readFinished(delivered: true)
        clock.fire(2000)
        XCTAssertEqual(settled, 0, "reads were asked for, the no read rule doesnt apply")
        clock.fire(500)
        XCTAssertEqual(settled, 1)
        watch.settle()
        XCTAssertEqual(settled, 1, "once")
    }

    func testFirstDrawSettlesWhenNothingIsAskedOrTheSourceGoesFirst() {
        let clock = ManualClock()
        var settled = 0
        let idle = MBTilesFirstDrawWatch(schedule: clock.schedule) { settled += 1 }
        idle.start()
        clock.fire(2000)
        XCTAssertEqual(settled, 1, "camera outside the pack: nothing asked within noReadCompleteMs")

        // only cancelled reads: still armed
        let cancelled = MBTilesFirstDrawWatch(schedule: clock.schedule) { settled += 1 }
        cancelled.start()
        cancelled.readStarted()
        cancelled.readFinished(delivered: false)
        clock.fire(500)
        clock.fire(2000)
        XCTAssertEqual(settled, 1)
        cancelled.settle()
        XCTAssertEqual(settled, 2, "replaced or closed first")

        // dropped without ever being published
        var dropped: MBTilesFirstDrawWatch? = MBTilesFirstDrawWatch(schedule: clock.schedule) { settled += 1 }
        XCTAssertNotNil(dropped)
        dropped = nil
        XCTAssertEqual(settled, 3)
    }

    // MARK: MapViewModel

    private func makePack(name: String = "pack.mbtiles") throws -> URL {
        let url = dir.appendingPathComponent(name)
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &db), SQLITE_OK)
        defer { sqlite3_close(db) }
        XCTAssertEqual(sqlite3_exec(db, """
        CREATE TABLE metadata (name TEXT, value TEXT);
        CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);
        INSERT INTO metadata VALUES ('name','Sample'),('format','png'),('bounds','-1.0,-2.0,3.0,4.0');
        INSERT INTO tiles VALUES (0,0,0,X'01'),(1,0,0,X'0203');
        """, nil, nil, nil), SQLITE_OK)
        return url
    }

    private func library(_ pack: URL, active: Bool = true) -> (ImportedMapEntry, LibraryState) {
        let entry = ImportedMapEntry(id: UUID(), kind: .mbtiles, fileName: "ImportedMaps/map-test.mbtiles",
                                     displayName: "Ridge pack", contentKey: nil, byteCount: 0, fileModifiedAtMs: 0,
                                     importedAtMs: 0, derivedFromId: nil, pdf: nil)
        var state = LibraryState()
        state.active = active ? .entry(entry.id) : .online(OnlineRasterBasemapSource.defaultStyle)
        state.entries = [entry]
        return (entry, state)
    }

    /// one launch: a fresh view model over the given guard file. M2 opens the
    /// pack off main, this waits for it to land
    private func launch(_ state: LibraryState, pack: URL, guardFile: URL) -> MapViewModel {
        let vm = MapViewModel(libraryDependencies: PDFRenderGuardStoreTests.inMemoryLibrary(state, fileURL: { _ in pack }),
                              initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        vm.pdfRenderGuard = PDFRenderGuard(url: dir.appendingPathComponent("pdf_render_guard.json"))
        vm.mbtilesOpenGuard = MBTilesOpenGuard(url: guardFile)
        vm.guardIsForeground = { true }
        _ = vm.restoreActiveMapSelection()
        landed(vm)
        return vm
    }

    private func waitFor(_ timeout: TimeInterval = 4, _ done: () -> Bool) {
        let end = Date().addingTimeInterval(timeout)
        while !done(), Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
    }

    private func landed(_ vm: MapViewModel, file: StaticString = #filePath, line: UInt = #line) {
        waitFor { !vm.packOpenPending }
        XCTAssertFalse(vm.packOpenPending, "pack open never landed", file: file, line: line)
    }

    func testACrashWhileTheRestoredPackOpensHoldsItBackUntilOpenAnyway() throws {
        let pack = try makePack()
        let (entry, state) = library(pack)
        let guardFile = dir.appendingPathComponent("mbtiles_open_guard.json")
        let token = entry.id.uuidString

        // launch 1: the guard is on disk while the pack opens. A crash right
        // there leaves exactly that file
        var atOpen: Data?
        MBTilesStore.admissionOpenHookForTesting = { _ in atOpen = try? Data(contentsOf: guardFile) }
        let first = launch(state, pack: pack, guardFile: guardFile)
        XCTAssertEqual((first.mapSource as? OfflineTileMapSource)?.entryID, entry.id)
        let crashed = dir.appendingPathComponent("crashed.json")
        try XCTUnwrap(atOpen).write(to: crashed)
        XCTAssertEqual(MBTilesOpenGuardState.decode(try XCTUnwrap(atOpen)).inProgress, [token])

        // launch 2: held back, never opened, online in memory, durable selection untouched
        var opens = 0
        MBTilesStore.admissionOpenHookForTesting = { _ in opens += 1 }
        let second = launch(state, pack: pack, guardFile: crashed)
        XCTAssertEqual(opens, 0, "the suspect isn't opened again")
        XCTAssertEqual(second.mbtilesCrashSuspect?.id, entry.id)
        XCTAssertTrue(second.crashSuspectAlertShowing)
        XCTAssertEqual(second.crashSuspectDisplayName, "Ridge pack")
        XCTAssertTrue(second.crashSuspectPending)
        XCTAssertTrue(second.mapSource is OnlineRasterBasemapSource)
        XCTAssertEqual(second.library?.activeEntryID, entry.id)
        XCTAssertEqual(MBTilesOpenGuard(url: crashed).suspect, token)
        // an unlock / Retry later in the same launch still holds it back
        _ = second.restoreActiveMapSelection()
        XCTAssertEqual(opens, 0)
        XCTAssertTrue(second.mapSource is OnlineRasterBasemapSource)

        // Not Now: the next launch asks again
        second.dismissCrashSuspect()
        XCTAssertNil(second.mbtilesCrashSuspect)
        let third = launch(state, pack: pack, guardFile: crashed)
        XCTAssertEqual(third.mbtilesCrashSuspect?.id, entry.id)
        XCTAssertEqual(opens, 0)

        // Open Anyway: the normal guarded open, armed again until its first draw
        third.openCrashSuspectAnyway()
        landed(third)
        XCTAssertEqual(opens, 1)
        let shown = try XCTUnwrap(third.mapSource as? OfflineTileMapSource)
        XCTAssertEqual(shown.entryID, entry.id)
        XCTAssertNil(third.mbtilesCrashSuspect)
        XCTAssertNil(MBTilesOpenGuard(url: crashed).suspect)
        XCTAssertEqual(MBTilesOpenGuard(url: crashed).snapshot.inProgress, [token], "armed until the first draw")
        // the renderer delivers a tile, then nothing pending for firstDrawQuietMs
        let watch = try XCTUnwrap(shown.firstDraw)
        watch.readStarted()
        watch.readFinished(delivered: true)
        waitFor { MBTilesOpenGuard(url: crashed).snapshot.inProgress.isEmpty }
        XCTAssertEqual(MBTilesOpenGuard(url: crashed).snapshot.inProgress, [])

        // and the launch after that restores it like nothing happened
        let fourth = launch(state, pack: pack, guardFile: crashed)
        XCTAssertNil(fourth.mbtilesCrashSuspect)
        XCTAssertEqual((fourth.mapSource as? OfflineTileMapSource)?.entryID, entry.id)
    }

    func testACleanRestoreClearsItsMarkerAndARefusedPackClearsItAtOnce() throws {
        let pack = try makePack()
        let (entry, state) = library(pack)
        let guardFile = dir.appendingPathComponent("mbtiles_open_guard.json")
        let vm = launch(state, pack: pack, guardFile: guardFile)
        XCTAssertEqual((vm.mapSource as? OfflineTileMapSource)?.entryID, entry.id)
        XCTAssertEqual(MBTilesOpenGuard(url: guardFile).snapshot.inProgress, [entry.id.uuidString])
        // nothing draws in a test, so the no read rule clears it
        waitFor { MBTilesOpenGuard(url: guardFile).snapshot.inProgress.isEmpty }
        XCTAssertEqual(MBTilesOpenGuard(url: guardFile).snapshot.inProgress, [])

        // a pack that refuses to open: online, and no marker left to blame it
        let junk = dir.appendingPathComponent("junk.mbtiles")
        try Data("not sqlite at all".utf8).write(to: junk)
        let other = dir.appendingPathComponent("other_guard.json")
        var armedAtOpen: [String] = []
        MBTilesStore.admissionOpenHookForTesting = { _ in armedAtOpen = MBTilesOpenGuard(url: other).snapshot.inProgress }
        let refused = launch(state, pack: junk, guardFile: other)
        XCTAssertTrue(refused.mapSource is OnlineRasterBasemapSource)
        XCTAssertEqual(armedAtOpen, [entry.id.uuidString])
        XCTAssertEqual(MBTilesOpenGuard(url: other).snapshot.inProgress, [])
    }

    func testBackgroundNeverArmsAndGoingThereDisarms() throws {
        let pack = try makePack()
        let (_, state) = library(pack)
        let guardFile = dir.appendingPathComponent("mbtiles_open_guard.json")
        let vm = MapViewModel(libraryDependencies: PDFRenderGuardStoreTests.inMemoryLibrary(state, fileURL: { _ in pack }),
                              initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        vm.pdfRenderGuard = PDFRenderGuard(url: dir.appendingPathComponent("pdf_render_guard.json"))
        vm.mbtilesOpenGuard = MBTilesOpenGuard(url: guardFile)
        vm.guardIsForeground = { false }
        _ = vm.restoreActiveMapSelection()
        landed(vm)
        XCTAssertTrue(vm.mapSource is OfflineTileMapSource)
        XCTAssertEqual(MBTilesOpenGuard(url: guardFile).snapshot.inProgress, [])

        // armed in the foreground, then the app goes to the background
        let fg = launch(state, pack: pack, guardFile: guardFile)
        XCTAssertFalse(MBTilesOpenGuard(url: guardFile).snapshot.inProgress.isEmpty)
        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        // PDF render services listen too, put them back before anything else runs
        NotificationCenter.default.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        XCTAssertEqual(MBTilesOpenGuard(url: guardFile).snapshot.inProgress, [])
        withExtendedLifetime(fg) {}
    }

    func testPickingTheHeldBackPackOrDeletingItResolvesTheGuard() throws {
        let pack = try makePack()
        let (entry, state) = library(pack)
        let crashed = dir.appendingPathComponent("crashed.json")
        MBTilesOpenGuard(url: crashed).arm(token: entry.id.uuidString, foreground: true)
        let vm = launch(state, pack: pack, guardFile: crashed)
        XCTAssertEqual(vm.mbtilesCrashSuspect?.id, entry.id)
        // Layers tap counts as Open Anyway
        XCTAssertTrue(vm.activateLibraryEntry(entry.id))
        landed(vm)
        XCTAssertNil(vm.mbtilesCrashSuspect)
        XCTAssertNil(MBTilesOpenGuard(url: crashed).suspect)
        XCTAssertEqual((vm.mapSource as? OfflineTileMapSource)?.entryID, entry.id)

        // Delete Map through the usual delete
        let again = dir.appendingPathComponent("crashed-again.json")
        MBTilesOpenGuard(url: again).arm(token: entry.id.uuidString, foreground: true)
        let vm2 = launch(state, pack: pack, guardFile: again)
        XCTAssertEqual(vm2.mbtilesCrashSuspect?.id, entry.id)
        XCTAssertTrue(vm2.deleteCrashSuspect())
        XCTAssertNil(vm2.mbtilesCrashSuspect)
        XCTAssertNil(MBTilesOpenGuard(url: again).suspect)
        XCTAssertNil(vm2.library?.entry(entry.id))
    }

    /// fixture row 4 in the app: the crash was admitting a tapped pack before
    /// its library write, the map that's actually restored is left alone
    func testACrashOpeningATappedPackBeforeItsWriteLeavesTheRestoredMapAlone() throws {
        let pack = try makePack()
        let (entry, state) = library(pack, active: false)
        let guardFile = dir.appendingPathComponent("mbtiles_open_guard.json")
        let vm = launch(state, pack: pack, guardFile: guardFile)
        var atOpen: Data?
        MBTilesStore.admissionOpenHookForTesting = { _ in atOpen = try? Data(contentsOf: guardFile) }
        XCTAssertTrue(vm.activateLibraryEntry(entry.id))
        landed(vm)
        let crashed = dir.appendingPathComponent("crashed.json")
        try XCTUnwrap(atOpen).write(to: crashed)
        // the write never happened: online is still the durable selection
        let next = launch(state, pack: pack, guardFile: crashed)
        XCTAssertNil(next.mbtilesCrashSuspect)
        XCTAssertTrue(next.mapSource is OnlineRasterBasemapSource)
        XCTAssertEqual(MBTilesOpenGuard(url: crashed).snapshot, MBTilesOpenGuardState())
    }

    // MARK: the other doors (s9.8 import marker, rebuild marker)

    func testTheImportAdmissionRunsUnderTheMarkerAndADeathThereIsCleanedUp() async throws {
        let source = try makePack(name: "Ridge.mbtiles")
        var markerAtOpen: String?
        MBTilesStore.admissionOpenHookForTesting = { _ in
            markerAtOpen = MapImportPipeline.markerURL.flatMap { try? String(contentsOf: $0, encoding: .utf8) }
        }
        let payload = try await MapImportPipeline.prepareMBTiles(url: source, entryCount: 0, libraryLoaded: true,
                                                                 isCancelled: { false }, progress: { _ in })
        let token = payload.copy.url.deletingPathExtension().lastPathComponent
        XCTAssertEqual(markerAtOpen, token, "the marker names the copy while it's admitted")
        let marker = try XCTUnwrap(MapImportPipeline.markerURL)
        XCTAssertFalse(FileManager.default.fileExists(atPath: marker.path), "off again after a clean admission")

        // what dying in there leaves: the marker naming the copy, and a fresh
        // process where nothing is in flight. The library adoption keeps away
        // from it and the launch sweep removes it
        InFlightImportFiles.unregister(payload.copy.url)
        try Data(token.utf8).write(to: marker)
        XCTAssertTrue(MapImportPipeline.interruptedImportFiles().contains(payload.copy.url))
        XCTAssertFalse(ImportedMapLibraryRecovery.candidates(inFlight: []).map(\.lastPathComponent)
            .contains(payload.copy.url.lastPathComponent))
        XCTAssertTrue(MapImportPipeline.recoverInterruptedImport())
        XCTAssertFalse(FileManager.default.fileExists(atPath: payload.copy.url.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: marker.path))

        // a clean refusal takes its marker off too
        let junk = dir.appendingPathComponent("Junk.mbtiles")
        try Data("nope".utf8).write(to: junk)
        do {
            _ = try await MapImportPipeline.prepareMBTiles(url: junk, entryCount: 0, libraryLoaded: true,
                                                           isCancelled: { false }, progress: { _ in })
            XCTFail("junk admitted")
        } catch {}
        XCTAssertFalse(FileManager.default.fileExists(atPath: marker.path))
    }

    /// 3.0.3 DL-2: unlock and the locked library Retry run the s9.8 sweep again,
    /// and an import can still be admitting its pack right then. That copy is a
    /// live import's, so the sweep leaves it and the marker alone
    func testTheSweepFromAnUnlockMidAdmissionLeavesTheLiveImportAlone() async throws {
        let source = try makePack(name: "Ridge.mbtiles")
        let parked = DispatchSemaphore(value: 0)
        let release = DispatchSemaphore(value: 0)
        var admitting: URL?
        MBTilesStore.admissionOpenHookForTesting = { url in
            admitting = url
            parked.signal()
            _ = release.wait(timeout: .now() + 30)
        }
        let admission = Task.detached {
            try await MapImportPipeline.prepareMBTiles(url: source, entryCount: 0, libraryLoaded: true,
                                                       isCancelled: { false }, progress: { _ in })
        }
        let started = await withCheckedContinuation { c in
            DispatchQueue.global().async { c.resume(returning: parked.wait(timeout: .now() + 10) == .success) }
        }
        XCTAssertTrue(started, "admission never got to the open")
        let copy = try XCTUnwrap(admitting)
        let marker = try XCTUnwrap(MapImportPipeline.markerURL)
        XCTAssertTrue(FileManager.default.fileExists(atPath: marker.path), "marker is on mid admission")

        // what publishRestoredBasemap does on main after DataKey.unlock or Retry
        let swept = await MainActor.run { MapImportPipeline.recoverInterruptedImport() }
        XCTAssertFalse(swept, "a live import isn't an interrupted one")
        XCTAssertTrue(FileManager.default.fileExists(atPath: copy.path), "the live copy survives the sweep")
        XCTAssertTrue(FileManager.default.fileExists(atPath: marker.path), "marker left for the import to take off")

        release.signal()
        let payload = try await admission.value
        defer { InFlightImportFiles.unregister(payload.copy.url) }
        XCTAssertEqual(payload.copy.url, copy)
        XCTAssertTrue(FileManager.default.fileExists(atPath: copy.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: marker.path))
        XCTAssertFalse(MapImportPipeline.recoverInterruptedImport(), "nothing to report afterwards either")
    }

    func testTheRebuildNeverReopensAPackItDiedOn() throws {
        let imported = try ImportedMapStorage.importedMapsDirectory()
        let file = imported.appendingPathComponent("map-\(UUID().uuidString.lowercased()).mbtiles")
        try FileManager.default.moveItem(at: try makePack(), to: file)
        let marker = try XCTUnwrap(ImportedMapLibraryRecovery.markerURL)
        var markerAtOpen: String?
        MBTilesStore.admissionOpenHookForTesting = { _ in markerAtOpen = try? String(contentsOf: marker, encoding: .utf8) }
        let clean = ImportedMapLibraryRecovery.rebuild(inFlight: [], inspect: { _ in nil })
        XCTAssertEqual(markerAtOpen, file.lastPathComponent)
        XCTAssertFalse(FileManager.default.fileExists(atPath: marker.path))
        XCTAssertEqual(clean.entries.count, 1)
        XCTAssertNotEqual(clean.entries.first?.fileModifiedAtMs, -1, "opened fine, available")

        // the pass died in that open: the next one adopts it unavailable, unopened
        try Data(file.lastPathComponent.utf8).write(to: marker)
        var opens = 0
        MBTilesStore.admissionOpenHookForTesting = { _ in opens += 1 }
        let again = ImportedMapLibraryRecovery.rebuild(inFlight: [], inspect: { _ in nil })
        XCTAssertEqual(opens, 0)
        XCTAssertEqual(again.entries.map(\.kind), [.mbtiles])
        XCTAssertEqual(again.entries.first?.fileModifiedAtMs, -1)
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path), "never deletes")
        XCTAssertFalse(FileManager.default.fileExists(atPath: marker.path))
    }

    func testTheMigrationsNameReadFallsBackToTheStemAfterDyingThere() throws {
        let imported = try ImportedMapStorage.importedMapsDirectory()
        let legacy = imported.appendingPathComponent("Ridge.mbtiles")
        try FileManager.default.moveItem(at: try makePack(), to: legacy)
        let marker = try XCTUnwrap(ImportedMapLibraryRecovery.markerURL)
        var markerAtOpen: String?
        var openedAt: URL?
        var opaqueAtOpen: [String] = []
        MBTilesStore.admissionOpenHookForTesting = { url in
            markerAtOpen = try? String(contentsOf: marker, encoding: .utf8)
            openedAt = url
            // what a crash right here would leave behind
            opaqueAtOpen = ((try? FileManager.default.contentsOfDirectory(atPath: imported.path)) ?? [])
                .filter(ImportedMapLibraryRecovery.isOpaqueImportName)
        }
        let made = try XCTUnwrap(ImportedMapLibraryMigration.mbtilesEntry(legacy, nowMs: 0))
        XCTAssertEqual(made.entry.displayName, "Sample")
        XCTAssertEqual(markerAtOpen, "Ridge.mbtiles", "keyed on the 2.x name, the opaque link is new every try")
        XCTAssertEqual(openedAt?.lastPathComponent, "Ridge.mbtiles", "read on the 2.x file itself")
        XCTAssertEqual(opaqueAtOpen, [], "no second name for the bytes yet, dying here leaves nothing for adopt to reopen")
        XCTAssertFalse(FileManager.default.fileExists(atPath: marker.path))
        if let link = made.link { ImportedMapStorage.unlink(link.new) }

        // died in there last time (or adopt did, on another file): stem, no open,
        // and the marker stays for the adoption that runs next to skip that file
        for named in ["Ridge.mbtiles", "map-\(UUID().uuidString.lowercased()).mbtiles"] {
            try Data(named.utf8).write(to: marker)
            var opens = 0
            MBTilesStore.admissionOpenHookForTesting = { _ in opens += 1 }
            let again = try XCTUnwrap(ImportedMapLibraryMigration.mbtilesEntry(legacy, nowMs: 0))
            XCTAssertEqual(opens, 0, named)
            XCTAssertEqual(again.entry.displayName, "Ridge", named)
            XCTAssertTrue(FileManager.default.fileExists(atPath: legacy.path))
            XCTAssertEqual(try? String(contentsOf: marker, encoding: .utf8), named, "left untouched")
            if let link = again.link { ImportedMapStorage.unlink(link.new) }
        }
    }
}
