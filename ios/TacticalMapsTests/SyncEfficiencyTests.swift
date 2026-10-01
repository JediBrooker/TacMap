import XCTest
import Combine
import CoreLocation
@testable import TacticalMaps

/// SP3 efficiency (contract sections 17-21). Every test counts the real
/// durable writes / HMACs / exports the manager does through the S6-02 seam
/// and prints a SP3-MEASURE line, so the before/after numbers in the change
/// description come straight out of the test log.
@MainActor
final class SyncEfficiencyTests: XCTestCase {
    private var harness: SyncManagerHarness!

    override func setUp() async throws {
        harness = try SyncManagerHarness(joinCode: "3:sp3-efficiency-room-0001")
    }

    override func tearDown() async throws {
        harness?.tearDown()
        harness = nil
    }

    private var manager: SyncManager { harness.manager }

    private func report(_ name: String, _ values: KeyValuePairs<String, Any>) {
        let line = values.map { "\($0.key)=\($0.value)" }.joined(separator: " ")
        print("SP3-MEASURE \(name) \(line)")
    }

    private func ms(since start: CFAbsoluteTime) -> Int {
        Int(((CFAbsoluteTimeGetCurrent() - start) * 1000).rounded())
    }

    private func remoteWaypoint(_ index: Int) -> Waypoint {
        Waypoint(name: "Remote \(index)", latitude: -33.0 - Double(index) / 10_000,
                 longitude: 151.0 + Double(index) / 10_000, layerID: DrawingLayer.legacyFallbackID)
    }

    private func randomWireId() -> String {
        SyncIdentity.urlB64Encode(Data((0..<32).map { _ in UInt8.random(in: 0...255) }))
    }

    private func deliverInPages(_ items: [[String: Any]], pageSize: Int = 250) {
        var start = 0
        while start < items.count {
            let end = min(items.count, start + pageSize)
            harness.page(Array(items[start..<end]), more: end < items.count)
            start = end
        }
        if items.isEmpty { harness.page([]) }
    }

    // MARK: 17 snapshot apply: one commit, one clear, one store write

    func testSnapshotOfManyRecordsCommitsOncePerStore() throws {
        let executor = ManualSyncOffMainExecutor()
        harness.tearDown()
        harness = try SyncManagerHarness(joinCode: "3:sp3-efficiency-room-0001", offMainExecutor: executor)
        let count = 300
        let items = (0..<count).map { harness.peerWaypointPut(remoteWaypoint($0), counter: Int64($0 + 2)) }
        harness.join()
        executor.runAll()
        harness.beginSnapshot()
        deliverInPages(items)
        harness.writes.reset()
        let start = CFAbsoluteTimeGetCurrent()
        harness.endSnapshot()
        let snapshotEndOnMainMs = ms(since: start)
        let timed = executor.runNextTimed()
        harness.pump()
        let writes = harness.writes
        report("snapshotApply", ["records": count, "replayWrites": writes.replayWrites,
                                 "waypointStoreWrites": writes.waypointWrites,
                                 "drawingStoreWrites": writes.drawingWrites,
                                 "journalWrites": writes.journalWrites,
                                 "snapshotEndFrameOnMainMs": snapshotEndOnMainMs,
                                 "validationOffMainMs": timed.workMs,
                                 "commitAndApplyOnMainMs": timed.completionMs,
                                 "ms": snapshotEndOnMainMs + timed.workMs + timed.completionMs])
        XCTAssertNotNil(harness.lastHello, "hello goes out after the single commit")
        XCTAssertEqual(harness.waypointStore.waypoints.count, count)
        // commit + marker clear + hello epoch
        XCTAssertLessThanOrEqual(writes.replayWrites, 3)
        XCTAssertLessThanOrEqual(writes.waypointWrites, 1)
        XCTAssertLessThanOrEqual(writes.drawingWrites, 1)
        XCTAssertEqual(writes.journalWrites, 0, "a remote apply is not a local edit")
        harness.ackHello()
        XCTAssertEqual(manager.status, .connected)
        harness.pump(1_000)
        XCTAssertTrue(harness.sentMutations().isEmpty, "applied remote records are never echoed")
    }

    // MARK: 18 wire-id index (S5-02, S4-03, S3-10)

    func testTombstoneSnapshotDoesNotScanEveryLocalObject() throws {
        let executor = ManualSyncOffMainExecutor()
        harness.tearDown()
        harness = try SyncManagerHarness(joinCode: "3:sp3-efficiency-room-0001", offMainExecutor: executor)
        let localCount = 2_000
        let tombstoneCount = 2_000
        let locals = (0..<localCount).map {
            Waypoint(name: "Local \($0)", latitude: -34.0 - Double($0) / 10_000, longitude: 150.0,
                     layerID: DrawingLayer.legacyFallbackID)
        }
        _ = try harness.waypointStore.importBatch(locals, batchKey: "seed")
        let tombstones = (0..<tombstoneCount).map { harness.peerDel(wireId: randomWireId(), counter: Int64($0 + 2)) }

        SyncCostCounters.reset()
        let joinStart = CFAbsoluteTimeGetCurrent()
        harness.join()
        executor.runAll()
        harness.beginSnapshot()
        deliverInPages(tombstones, pageSize: 500)
        let snapshotStart = CFAbsoluteTimeGetCurrent()
        harness.endSnapshot()
        let timed = executor.runNextTimed()
        harness.pump()
        let snapshotMs = ms(since: snapshotStart)
        let totalMs = ms(since: joinStart)
        let hmacs = SyncCostCounters.value(SyncCostCounters.wireIdHmac)
        report("tombstoneReverseLookup", ["localObjects": localCount, "tombstones": tombstoneCount,
                                          "wireIdHmacs": hmacs, "snapshotEndMs": snapshotMs,
                                          "validationOffMainMs": timed.workMs,
                                          "commitOnMainMs": timed.completionMs,
                                          "joinToHelloMs": totalMs])
        XCTAssertNotNil(harness.lastHello)
        // the index is built once (one HMAC per local object), lookups are O(1)
        XCTAssertLessThanOrEqual(hmacs, localCount + 64)
        XCTAssertEqual(harness.waypointStore.waypoints.count, localCount, "absent ids delete nothing")
    }

    // MARK: 17.1 presence fence persistence (S5-01, S3-12, S4-04)

    func testPresenceFramesAreNotResealedPerFrame() throws {
        let tombstones = (0..<500).map { harness.peerDel(wireId: randomWireId(), counter: Int64($0 + 2)) }
        harness.join()
        harness.beginSnapshot()
        deliverInPages(tombstones)
        harness.endSnapshot()
        harness.ackHello()
        harness.socket.deliver(harness.peerHello())
        harness.pump()
        harness.writes.reset()
        let frames = 100
        for counter in 1...frames {
            harness.socket.deliver(harness.peerLoc(counter: Int64(counter), lat: -33.86 + Double(counter) / 100_000))
            harness.pump(5_000)
        }
        let writes = harness.writes
        report("presenceFrames", ["frames": frames, "cadenceMs": 5_000, "replayWrites": writes.replayWrites,
                                  "replayBytes": writes.replayBytes])
        XCTAssertEqual(manager.peers[harness.peerActor]?.lat ?? 0, -33.86 + Double(frames) / 100_000, accuracy: 1e-9)
        XCTAssertLessThanOrEqual(writes.replayWrites, frames / 10)
    }

    // MARK: 17 journal + outbound reservation batching (S5-03, S5 verifier note 1)

    func testLocalImportBumpsTheJournalOnceAndReservesOncePerDiffPass() throws {
        harness.join()
        harness.connect()
        let count = 500
        let imported = (0..<count).map {
            Waypoint(name: "Imported \($0)", latitude: -35.0 - Double($0) / 10_000, longitude: 149.0,
                     layerID: DrawingLayer.legacyFallbackID)
        }
        harness.writes.reset()
        SyncCostCounters.reset()
        let importStart = CFAbsoluteTimeGetCurrent()
        _ = try harness.waypointStore.importBatch(imported, batchKey: "kml")
        let importMs = ms(since: importStart)
        let journalWrites = harness.writes.journalWrites
        let importExports = SyncCostCounters.value(SyncCostCounters.geoJSONExport)

        SyncCostCounters.reset()
        let diffStart = CFAbsoluteTimeGetCurrent()
        harness.pump(250)
        let diffMs = ms(since: diffStart)
        let reservationWrites = harness.writes.replayWrites
        let diffExports = SyncCostCounters.value(SyncCostCounters.geoJSONExport)

        // an ack marks the diff dirty, the debounced pass must not re-export
        // the whole unchanged model
        let first = try XCTUnwrap(harness.sentMutations().first)
        SyncCostCounters.reset()
        harness.ack(first)
        harness.pump(250)
        let ackExports = SyncCostCounters.value(SyncCostCounters.geoJSONExport)
        report("localImport", ["objects": count, "journalWrites": journalWrites,
                               "importExports": importExports, "importMs": importMs,
                               "diffReplayWrites": reservationWrites, "diffExports": diffExports,
                               "diffMs": diffMs, "exportsAfterOneAck": ackExports])
        XCTAssertEqual(journalWrites, 1, "one journal write per store mutation event")
        XCTAssertLessThanOrEqual(importExports, count)
        XCTAssertEqual(reservationWrites, 1, "one reservation write per diff pass")
        XCTAssertLessThanOrEqual(diffExports, count)
        XCTAssertLessThanOrEqual(ackExports, 2)
    }

    // MARK: 20.2 presence-only redraw (S5-12)

    private func ackOurChatKey() throws {
        let advert = try XCTUnwrap(harness.socket.sent(type: "chat-key").last)
        harness.socket.deliver(["t": "chat-key-ack", "cv": 1, "by": advert["by"]!, "sd": advert["sd"]!, "kid": advert["kid"]!])
        harness.pump()
    }

    func testPresenceFramesDoNotRepublishTheManager() throws {
        harness.join()
        harness.connect()
        // otherwise the unanswered chat-key retries republish on their own
        try ackOurChatKey()
        harness.socket.deliver(harness.peerHello())
        harness.pump()
        harness.socket.deliver(harness.peerLoc(counter: 1))
        harness.pump(1_000)
        var changes = 0
        let watcher = manager.objectWillChange.sink { _ in changes += 1 }
        for counter in 2...61 {
            harness.socket.deliver(harness.peerLoc(counter: Int64(counter), lat: -33.86 + Double(counter) / 100_000))
            harness.pump(1_000)
        }
        watcher.cancel()
        report("presenceRootRepublish", ["frames": 60, "managerObjectWillChange": changes])
        XCTAssertEqual(manager.peers[harness.peerActor]?.lat ?? 0, -33.86 + 61.0 / 100_000, accuracy: 1e-9)
        XCTAssertEqual(changes, 0, "a presence frame may only touch the presence layer")
    }

    // MARK: 21.1 / 21.2 background entry (S2-05)

    func testBackgroundEntryWithPendingDeliveryNeitherRetriesNorKillsTheSocket() throws {
        harness.join()
        harness.connect()
        let local = try harness.addLocalWaypoint("Dropped then pocketed")
        harness.pump(250)
        XCTAssertEqual(harness.sentMutations().count, 1)
        harness.pump(100)
        manager.updateLifecycle(foregroundReady: false, backgroundPresenceEnabled: true, backgroundInterval: 900)
        harness.pump(30_000)
        let retransmits = harness.sentMutations().count - 1
        report("backgroundPendingDelivery", ["retransmitsIn30s": retransmits,
                                             "socketCancelled": harness.socket.isCancelled])
        XCTAssertEqual(retransmits, 0)
        XCTAssertFalse(harness.socket.isCancelled)
        XCTAssertNil(manager.lastError)
        harness.pump(570_000)
        manager.updateLifecycle(foregroundReady: true, backgroundPresenceEnabled: true, backgroundInterval: 900)
        harness.pump()
        XCTAssertEqual(harness.socketCount, 2)
        harness.connect(seq: 2)
        harness.pump(1_000)
        let wire = harness.wireId(local.id)
        XCTAssertEqual(harness.sentMutations().filter { $0["id"] as? String == wire }.count, 1,
                       "the unconfirmed op goes to reconciliation and is published once")
    }

    // MARK: 1 + 17 live record batch

    func testLiveRecordBurstIsOneCommitOneStoreWriteOneClear() throws {
        harness.join()
        harness.connect()
        harness.socket.deliver(harness.peerHello())
        harness.pump()
        let count = 50
        let records = (0..<count).map { harness.peerWaypointPut(remoteWaypoint(1_000 + $0), counter: Int64(100 + $0), live: true) }
        harness.writes.reset()
        // a peer's import arrives as a burst, all of it queued before the drain runs
        for record in records { harness.socket.deliver(record) }
        harness.pump()
        let writes = harness.writes
        report("liveRecordBurst", ["records": count, "replayWrites": writes.replayWrites,
                                   "waypointStoreWrites": writes.waypointWrites,
                                   "journalWrites": writes.journalWrites])
        XCTAssertEqual(harness.waypointStore.waypoints.count, count)
        XCTAssertEqual(writes.replayWrites, 2, "one commit before the model, one marker clear after")
        XCTAssertEqual(writes.waypointWrites, 1)
        XCTAssertEqual(writes.journalWrites, 0)
        harness.pump(1_000)
        XCTAssertTrue(harness.sentMutations().isEmpty, "remote records are not echoed")

        // a later edit of one of them by the same peer still lands
        var edited = remoteWaypoint(1_000)
        edited.name = "Edited remotely"
        harness.socket.deliver(harness.peerWaypointPut(edited, counter: 500, live: true))
        harness.pump()
        XCTAssertEqual(harness.waypointStore.waypoints.first { $0.id == edited.id }?.name, "Edited remotely")
    }

    func testPutThenDeleteOfOneObjectInOneBatchEndsDeleted() throws {
        harness.join()
        harness.connect()
        harness.socket.deliver(harness.peerHello())
        harness.pump()
        let waypoint = remoteWaypoint(7)
        harness.socket.deliver(harness.peerWaypointPut(waypoint, counter: 40, live: true))
        harness.socket.deliver(harness.peerDel(wireId: harness.wireId(waypoint.id), counter: 41, live: true))
        harness.pump()
        XCTAssertFalse(harness.waypointStore.waypoints.contains { $0.id == waypoint.id })
        XCTAssertNotEqual(manager.lastIssueKind, .security)
        XCTAssertEqual(manager.status, .connected)
    }

    // MARK: 19 off-main snapshot validation

    func testSnapshotValidatesOffMainAndRevalidatesAfterALayerEdit() throws {
        let executor = ManualSyncOffMainExecutor()
        harness.tearDown()
        harness = try SyncManagerHarness(joinCode: "3:sp3-efficiency-room-0002", offMainExecutor: executor)
        harness.join()
        executor.runAll() // PBKDF2 off main
        XCTAssertEqual(harness.socketCount, 1)
        let layerId = UUID()
        let recon = DrawingLayer(id: layerId, name: "Recon", defaultColorHex: "#112233")
        let shape = DrawingShape(kind: .polyline, coordinates: [
            Coordinate2D(latitude: -33.8, longitude: 151.2), Coordinate2D(latitude: -33.81, longitude: 151.21)
        ], layerID: layerId)
        let record = harness.peerPut(wireId: harness.wireId(shape.id),
                                     content: harness.peerContent(drawing: shape, layers: [recon]),
                                     counter: 2, kind: "drawing")
        harness.beginSnapshot()
        harness.page([record])
        harness.endSnapshot()
        XCTAssertEqual(executor.pendingCount, 1, "validation is queued off main")
        XCTAssertNil(harness.lastHello, "nothing is committed before the validated result is back")
        // frames that arrive meanwhile wait in the queue
        harness.socket.deliver(harness.peerHello())
        harness.pump()
        XCTAssertTrue(manager.onlineMembers.isEmpty)

        // the user touches a layer while we validate: the staged hashes are
        // stale, so it validates again against the current layers
        _ = try harness.drawingStore.addLayer(name: "Local edit", defaultColorHex: "#445566")
        executor.runNext()
        XCTAssertNil(harness.lastHello)
        XCTAssertEqual(executor.pendingCount, 1)
        executor.runNext()
        harness.pump()
        XCTAssertNotNil(harness.lastHello)
        XCTAssertTrue(harness.drawingStore.shapes.contains { $0.id == shape.id })
        XCTAssertEqual(harness.drawingStore.layers.first { $0.id == layerId }?.name, "Recon")
        harness.ackHello()
        XCTAssertEqual(manager.status, .connected)
        XCTAssertNotNil(manager.onlineMembers[harness.peerActor], "the queued hello is processed after the commit")
        XCTAssertNotEqual(manager.lastIssueKind, .security)
    }

    // MARK: 20.3 PBKDF2 off the UI thread (S5-13)

    func testJoinShowsConnectingAndDerivesTheRoomKeyOffMain() throws {
        let executor = ManualSyncOffMainExecutor()
        harness.tearDown()
        harness = try SyncManagerHarness(joinCode: "3:sp3-pbkdf2-room-000003", offMainExecutor: executor,
                                         realKeyDerivation: true)
        let joinStart = CFAbsoluteTimeGetCurrent()
        manager.join(harness.joinCode)
        let joinMs = ms(since: joinStart)
        XCTAssertEqual(manager.status, .connecting)
        XCTAssertEqual(manager.room, harness.joinCode)
        XCTAssertEqual(harness.socketCount, 0, "no socket before the room key exists")
        XCTAssertEqual(executor.pendingCount, 1)
        let deriveStart = CFAbsoluteTimeGetCurrent()
        executor.runNext()
        let deriveMs = ms(since: deriveStart)
        report("pbkdf2", ["joinCallMsOnMain": joinMs, "derivationMsOffMain": deriveMs])
        XCTAssertEqual(harness.socketCount, 1)
        XCTAssertLessThan(joinMs, deriveMs, "join returns before the 210k iterations run")

        // leave (or another join) while deriving drops the result
        manager.leave()
        manager.join(harness.joinCode)
        manager.leave()
        executor.runAll()
        XCTAssertEqual(harness.socketCount, 1)
        XCTAssertEqual(manager.status, .offline)
        XCTAssertNil(manager.room)
    }

    // MARK: 20.2 drawings overlay redraw (S5-12)

    func testDrawingsOverlayRedrawsOnlyWhenItsShapesChange() {
        let view = DrawingsOverlayView()
        view.frame = CGRect(x: 0, y: 0, width: 320, height: 480)
        view.project = { CGPoint(x: $0.longitude * 10, y: $0.latitude * 10) }
        let shapes = (0..<200).map { index in
            PDFVectorShape(sourceID: UUID(), coords: [
                CLLocationCoordinate2D(latitude: Double(index), longitude: 1),
                CLLocationCoordinate2D(latitude: Double(index), longitude: 2)
            ], isPolygon: false, style: DrawingStyle(), isSelected: false, inProgress: false)
        }
        SyncCostCounters.reset()
        view.update(shapes: shapes)
        view.layer.displayIfNeeded()
        let first = SyncCostCounters.value(SyncCostCounters.drawingsOverlayDisplay)
        for _ in 0..<60 {
            view.update(shapes: shapes)
            view.layer.displayIfNeeded()
        }
        let afterIdle = SyncCostCounters.value(SyncCostCounters.drawingsOverlayDisplay)
        report("drawingsOverlay", ["drawings": 200, "updates": 60, "redraws": afterIdle - first])
        XCTAssertEqual(first, 1)
        XCTAssertEqual(afterIdle, first, "identical shapes never redraw")
        var moved = shapes
        moved[0] = PDFVectorShape(sourceID: shapes[0].sourceID, coords: [
            CLLocationCoordinate2D(latitude: 50, longitude: 1), CLLocationCoordinate2D(latitude: 51, longitude: 2)
        ], isPolygon: false, style: DrawingStyle(), isSelected: false, inProgress: false)
        view.update(shapes: moved)
        view.layer.displayIfNeeded()
        XCTAssertEqual(SyncCostCounters.value(SyncCostCounters.drawingsOverlayDisplay), first + 1)
    }

    // MARK: 21.6 chat to a backgrounded peer (S6-04)

    func testDirectChatToABackgroundedPeerIsBlockedNotRouted() throws {
        harness.join()
        harness.connect()
        try ackOurChatKey()
        harness.socket.deliver(harness.peerHello())
        harness.socket.deliver(try harness.peerChatKey())
        harness.socket.deliver(harness.peerLoc(counter: 1))
        harness.pump()
        let recipient = try XCTUnwrap(manager.chatRecipients[harness.peerActor])
        let sentBefore = harness.socket.sent(type: "chat").count
        XCTAssertNoThrow(try manager.sendChat(body: "foreground", kind: .text, scope: .direct, recipient: recipient))
        harness.pump()
        XCTAssertEqual(harness.socket.sent(type: "chat").count, sentBefore + 1)

        // peer pockets the phone: its bridge frame advertises background retention
        harness.socket.deliver(harness.peerLoc(counter: 2, retentionSeconds: 1_200))
        harness.pump()
        XCTAssertThrowsError(try manager.sendChat(body: "RTB now", kind: .text, scope: .direct, recipient: recipient)) {
            XCTAssertEqual($0 as? SyncManager.ChatSendError, .recipientInBackground)
        }
        harness.pump()
        XCTAssertEqual(harness.socket.sent(type: "chat").count, sentBefore + 1, "never routed")
        // room sends are unchanged
        XCTAssertNoThrow(try manager.sendChat(body: "room", kind: .text, scope: .room, recipient: nil))
    }

    // MARK: 21.5 background pause is surfaced

    func testABackgroundDropIsSurfacedOnTheNextForegroundReturn() throws {
        harness.join()
        harness.connect()
        manager.updateLifecycle(foregroundReady: false, backgroundPresenceEnabled: true, backgroundInterval: 900)
        harness.pump()
        harness.socket.remoteClose(code: 1006)
        harness.pump(120_000)
        XCTAssertEqual(manager.status, .offline)
        XCTAssertEqual(harness.socketCount, 1, "no background reconnect until doc change D1")
        XCTAssertFalse(manager.surfacedIssueLog.contains(.backgroundPaused), "shown on return, not behind the lock")
        manager.updateLifecycle(foregroundReady: true, backgroundPresenceEnabled: true, backgroundInterval: 900)
        harness.pump()
        XCTAssertEqual(manager.surfacedIssueLog.filter { $0 == .backgroundPaused }.count, 1)
        XCTAssertNotEqual(manager.lastIssueKind, .security)
        XCTAssertEqual(harness.socketCount, 2, "foreground return reconnects as before")

        // a user-initiated pause (sharing turned off) is not a drop
        harness.connect(seq: 2)
        manager.updateLifecycle(foregroundReady: false, backgroundPresenceEnabled: false, backgroundInterval: 900)
        manager.updateLifecycle(foregroundReady: true, backgroundPresenceEnabled: false, backgroundInterval: 900)
        harness.pump()
        XCTAssertEqual(manager.surfacedIssueLog.filter { $0 == .backgroundPaused }.count, 1)
    }

    // MARK: 18 indexes

    func testWireIndexAndModelIndexStayInStepWithTheStores() throws {
        let keys = harness.keys
        let index = SyncWireIdIndex(metadataKey: keys.metadataKey)
        let a = UUID(), b = UUID()
        SyncCostCounters.reset()
        index.insert([a, b])
        XCTAssertEqual(index.wireId(for: a), harness.wireId(a))
        XCTAssertEqual(index.uuid(forWireId: harness.wireId(b)), b)
        XCTAssertNil(index.uuid(forWireId: randomWireId()), "a miss is a miss, no scan")
        XCTAssertEqual(SyncCostCounters.value(SyncCostCounters.wireIdHmac), 2 + 2,
                       "two inserts, the index lookups are free, the 2 extra are the harness's reference HMACs")

        let model = SyncModelIndex()
        let layer = DrawingLayer(name: "Grid", defaultColorHex: "#101010")
        let waypoint = Waypoint(name: "W", latitude: -33.8, longitude: 151.2, layerID: layer.id)
        _ = model.updateLayers([layer])
        _ = model.updateWaypoints([waypoint])
        SyncCostCounters.reset()
        let first = try XCTUnwrap(model.export(waypoint.id))
        XCTAssertEqual(model.export(waypoint.id)?.hash, first.hash)
        XCTAssertEqual(SyncCostCounters.value(SyncCostCounters.geoJSONExport), 1, "second read is cached")
        XCTAssertEqual(first.content, try GeoJSONExporter.export(waypoints: [waypoint], drawings: [], layers: [layer]))
        var hidden = layer
        hidden.visible.toggle()
        XCTAssertTrue(model.updateLayers([hidden]).isEmpty, "visibility is not part of the export")
        var renamed = layer
        renamed.name = "Renamed"
        XCTAssertEqual(model.updateLayers([renamed]), [waypoint.id])
        XCTAssertNotEqual(model.export(waypoint.id)?.hash, first.hash)
    }

    func testBatchedReplayCommitRollsBackEverythingSinceTheLastDurableWrite() throws {
        enum Boom: Error { case disk }
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("sp3-undo-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        var fail = false
        let state = SyncReplayState(roomId: "sp3-undo-room", containerURL: directory) { data, url, label in
            if fail { throw Boom.disk }
            try SafeStore.write(data, to: url, label: label)
        }
        let actor = SyncIdentity.urlB64Encode(Data(repeating: 0x71, count: 32))
        let pub = SyncIdentity.urlB64Encode(Data(repeating: 0x72, count: 32))
        let wire = SyncIdentity.urlB64Encode(Data(repeating: 0x73, count: 32))
        let hash = String(repeating: "a", count: 64)
        func remote(_ counter: Int64, _ id: String) -> SyncReplayState.RemoteMutation {
            .init(mutation: .init(wireObjectId: id, stamp: VersionStamp(counter: counter, actorId: actor),
                                  publicKey: pub, kind: .put(contentHash: hash)),
                  priorModelHash: nil, localModelId: UUID().uuidString)
        }
        XCTAssertEqual(try state.commitRemoteBatch([remote(5, wire)]), [true])
        XCTAssertEqual(state.localCounter, 5)
        state.beginBatch()
        let other = SyncIdentity.urlB64Encode(Data(repeating: 0x74, count: 32))
        XCTAssertEqual(try state.commitRemoteBatch([remote(9, wire), remote(12, other)]), [true, true])
        XCTAssertTrue(try state.acceptHello(actorId: actor, pubkey: pub,
                                            sessionDomain: SyncIdentity.urlB64Encode(Data(repeating: 0x75, count: 32)),
                                            epochHex: "0000000000000003"))
        XCTAssertEqual(state.localCounter, 12, "in memory inside the batch")
        fail = true
        XCTAssertThrowsError(try state.endBatch())
        XCTAssertEqual(state.localCounter, 5)
        XCTAssertEqual(state.getStamp(wire), VersionStamp(counter: 5, actorId: actor))
        XCTAssertNil(state.getStamp(other))
        XCTAssertNil(state.getHelloEpoch(actor))
        XCTAssertEqual(state.pendingRemoteMutations().count, 1)
        // running high water is back too: 5 + window is still the edge
        XCTAssertEqual(state.liveAcceptance(other, VersionStamp(counter: 10_005, actorId: actor)), .accept)
        XCTAssertEqual(state.liveAcceptance(other, VersionStamp(counter: 10_006, actorId: actor)), .outsideWindow)
        fail = false
        let reloaded = SyncReplayState(roomId: "sp3-undo-room", containerURL: directory)
        XCTAssertTrue(reloaded.load())
        XCTAssertEqual(reloaded.localCounter, 5)
    }

    func testJournalBumpAllIsOneWriteAndRollsBackTogether() throws {
        enum Boom: Error { case disk }
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("sp3-journal-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        var writes = 0
        var fail = false
        let journal = LocalModelRevisionJournal(containerURL: directory) { data, url, label in
            writes += 1
            if fail { throw Boom.disk }
            try SafeStore.write(data, to: url, label: label)
        }
        let ids = (0..<300).map { _ in UUID().uuidString }
        try journal.bumpAll(ids)
        XCTAssertEqual(writes, 1)
        XCTAssertTrue(ids.allSatisfy { journal.generation($0) == 1 })
        fail = true
        let fresh = UUID().uuidString
        XCTAssertThrowsError(try journal.bumpAll([ids[0], fresh]))
        XCTAssertEqual(journal.generation(ids[0]), 1)
        XCTAssertEqual(journal.generation(fresh), 0)
    }

    // MARK: 21.4 background presence reconnect (gated on doc change D1)

    private func helloEpoch(_ hello: [String: Any]?) -> UInt64? {
        (hello?["vs"] as? String).flatMap { UInt64($0.prefix(16), radix: 16) }
    }

    func testBackgroundReconnectUsesASpareEpochDrainsTheSnapshotAndWritesNothing() throws {
        harness.tearDown()
        harness = try SyncManagerHarness(joinCode: "3:sp3-bg-reconnect-00001", backgroundReconnectEnabled: true)
        var config = manager.presenceConfig
        config.shareLocation = true
        XCTAssertTrue(manager.updatePresenceConfig(config))
        harness.join()
        harness.connect()
        let foregroundEpoch = try XCTUnwrap(helloEpoch(harness.lastHello))
        manager.updateLifecycle(foregroundReady: false, backgroundPresenceEnabled: true, backgroundInterval: 900)
        XCTAssertTrue(manager.backgroundPresenceSustained)

        harness.socket.remoteClose(code: 1006)
        harness.pump(120_000)
        XCTAssertEqual(harness.socketCount, 1, "waits for the next presence opportunity, no timers")
        harness.writes.reset()
        manager.locationDidUpdate()
        XCTAssertEqual(harness.socketCount, 2)
        let backgroundSocket = harness.socket
        let remote = remoteWaypoint(77)
        harness.beginSnapshot(seq: 5)
        harness.page([harness.peerWaypointPut(remote, counter: 9)])
        harness.endSnapshot(seq: 5)
        XCTAssertEqual(helloEpoch(harness.lastHello), foregroundEpoch + 1, "the next spare, nothing reserved")
        harness.ackHello()
        XCTAssertEqual(manager.status, .connected)
        let writes = harness.writes
        report("backgroundReconnect", ["replayWrites": writes.replayWrites, "journalWrites": writes.journalWrites,
                                       "storeWrites": writes.waypointWrites + writes.drawingWrites])
        XCTAssertEqual(writes.replayWrites + writes.journalWrites + writes.waypointWrites + writes.drawingWrites, 0,
                       "nothing durable behind the lock")
        XCTAssertFalse(harness.waypointStore.waypoints.contains { $0.id == remote.id }, "snapshot drained unparsed")
        let sentTypes = Set(backgroundSocket.sentObjects().compactMap { $0["t"] as? String })
        XCTAssertTrue(sentTypes.isSubset(of: BackgroundPresencePolicy.allowedFrameTypes), "\(sentTypes)")

        // back in the foreground: a normal session, past every spare
        manager.updateLifecycle(foregroundReady: true, backgroundPresenceEnabled: true, backgroundInterval: 900)
        harness.pump()
        XCTAssertFalse(manager.backgroundPresenceSustained)
        XCTAssertEqual(harness.socketCount, 3)
        harness.connect(seq: 6)
        let next = try XCTUnwrap(helloEpoch(harness.lastHello))
        XCTAssertGreaterThan(next, foregroundEpoch + HelloEpochPolicy.backgroundSpareBlock)
        XCTAssertFalse(manager.surfacedIssueLog.contains(.backgroundPaused))
    }

    func testBackgroundReconnectPausesAfterThreeFailedAttempts() throws {
        harness.tearDown()
        harness = try SyncManagerHarness(joinCode: "3:sp3-bg-reconnect-00002", backgroundReconnectEnabled: true)
        var config = manager.presenceConfig
        config.shareLocation = true
        XCTAssertTrue(manager.updatePresenceConfig(config))
        harness.join()
        harness.connect()
        manager.updateLifecycle(foregroundReady: false, backgroundPresenceEnabled: true, backgroundInterval: 900)
        harness.socket.remoteClose(code: 1006)
        harness.pump()
        for attempt in 1...3 {
            manager.locationDidUpdate()
            XCTAssertEqual(harness.socketCount, 1 + attempt)
            harness.socket.upgradeFailed(httpStatus: nil)
            harness.pump(60_000)
        }
        manager.locationDidUpdate()
        XCTAssertEqual(harness.socketCount, 4)
        XCTAssertFalse(manager.backgroundPresenceSustained, "paused, background location can stop")
        manager.updateLifecycle(foregroundReady: true, backgroundPresenceEnabled: true, backgroundInterval: 900)
        harness.pump()
        XCTAssertEqual(manager.surfacedIssueLog.filter { $0 == .backgroundPaused }.count, 1)
    }
}
