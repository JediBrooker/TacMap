import XCTest
@testable import TacticalMaps

/// Undo only ever moves your own edits. Unit Sync writes from peers never register undo, and
/// undoing a local edit leaves an object alone once a peer has changed it. Mirrors Android's
/// SyncedEditUndoTest.
@MainActor
final class SyncedEditUndoTests: XCTestCase {
    private var waypoints: WaypointStore!
    private var drawings: DrawingStore!
    private var follower: RangeRingFollower!
    private var undo: UndoManager!
    private let testKey = Data((0..<32).map { UInt8($0) })

    override func setUpWithError() throws {
        try super.setUpWithError()
        SafeStore.keyProvider = { [testKey] in testKey }
        SealedMigrationPolicy.resetForTests(key: testKey)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        waypoints = WaypointStore(storageURL: dir.appendingPathComponent("waypoints.json"),
                                  persistenceWriter: { _, _, _ in })
        drawings = DrawingStore(storageURL: dir.appendingPathComponent("drawings.json"),
                                persistenceWriter: { _, _, _ in })
        follower = RangeRingFollower()
        follower.attach(waypointStore: waypoints, drawingStore: drawings)
        // explicit groups like the other store tests. A peer write that still tried to register
        // undo outside a group would blow up here, which is exactly what we want to catch
        undo = UndoManager()
        undo.groupsByEvent = false
        waypoints.undoManager = undo
        drawings.undoManager = undo
    }

    override func tearDown() {
        SafeStore.keyProvider = { try DataKey.key() }
        SealedMigrationPolicy.resetForTests(key: testKey)
        super.tearDown()
    }

    private func local(_ action: () throws -> Void) rethrows {
        undo.beginUndoGrouping()
        defer { undo.endUndoGrouping() }
        try action()
    }

    private func peer(_ waypoint: Waypoint) throws {
        try SyncRemoteModelApplier.apply(
            GeoJSONImporter.Result(waypoints: [waypoint], drawings: [], newLayers: [], invalidSkipped: 0),
            waypointStore: waypoints, drawingStore: drawings)
    }

    private func peer(_ shape: DrawingShape) throws {
        try SyncRemoteModelApplier.apply(
            GeoJSONImporter.Result(waypoints: [], drawings: [shape], newLayers: [], invalidSkipped: 0),
            waypointStore: waypoints, drawingStore: drawings)
    }

    private func peerDelete(_ id: UUID) throws {
        try SyncRemoteModelApplier.delete(localID: id.uuidString,
                                          waypointStore: waypoints, drawingStore: drawings)
    }

    private func point(_ name: String, _ lat: Double = -33.8688) -> Waypoint {
        Waypoint(name: name, latitude: lat, longitude: 151.2093, layerID: drawings.layers[0].id)
    }

    func testUndoRemovesYourRingsNotTheUnitAPeerPlacedAfterThem() throws {
        // two device repro: rings from the symbol card, peer drops a marker, Undo
        let op = point("OP")
        try local { _ = try waypoints.addDurably(op) }
        let rings = RangeRings.shapes(around: op, radii: [500, 1000], layerColorHex: nil)
        try local { _ = try drawings.addBatchDurably(rings, actionName: "Add Range Rings") }
        let peerMarker = point("B COY", -33.86)
        try peer(peerMarker)

        undo.undo()
        XCTAssertFalse(drawings.shapes.contains { $0.anchorWaypointID == op.id }, "undo takes our rings")
        XCTAssertEqual(waypoints.waypoints.map(\.id), [op.id, peerMarker.id])

        undo.undo()
        XCTAssertEqual(waypoints.waypoints.map(\.id), [peerMarker.id], "peer marker survives")
        XCTAssertFalse(undo.canUndo)
    }

    func testPeerWritesAloneNeverEnableUndo() throws {
        var marker = point("peer")
        try peer(marker)
        marker.name = "peer renamed"
        try peer(marker)
        let ring = RangeRings.shapes(around: marker, radii: [250], layerColorHex: nil)[0]
        try peer(ring)
        try peerDelete(ring.id)
        try peerDelete(marker.id)
        XCTAssertFalse(undo.canUndo)
    }

    func testUndoingYourEditLeavesAPeersLaterEditAlone() throws {
        let original = point("OP")
        try local { _ = try waypoints.addDurably(original) }
        var mine = original
        mine.name = "OP renamed by me"
        try local { _ = try waypoints.commitEdit(mine) }
        var theirs = mine
        theirs.notes = "peer note"
        try peer(theirs)

        undo.undo()
        XCTAssertEqual(waypoints.waypoints, [theirs], "their newer version wins over undoing ours")
        // undoing the create still removes it, peer edit included. Same as Android
        undo.undo()
        XCTAssertTrue(waypoints.waypoints.isEmpty)
    }

    func testUnchangedPeerReapplyDoesNotCancelYourUndo() throws {
        let original = point("OP")
        try local { _ = try waypoints.addDurably(original) }
        var mine = original
        mine.name = "mine"
        try local { _ = try waypoints.commitEdit(mine) }
        // e.g. a reconnect snapshot replaying the record we already have
        try peer(mine)

        undo.undo()
        XCTAssertEqual(waypoints.waypoints, [original])
    }

    func testPeerDeleteNeitherCrashesNorResurrectsOnUndoAndRedo() throws {
        let op = point("OP")
        let ring = RangeRings.shapes(around: op, radii: [300], layerColorHex: nil)[0]
        try local { _ = try drawings.addDurably(ring) }
        var edited = ring
        edited.name = "edited"
        try local { _ = try drawings.commitEdit(edited) }
        try peerDelete(ring.id)

        undo.undo()
        undo.undo()
        XCTAssertFalse(drawings.shapes.contains { $0.id == ring.id })
        while undo.canRedo { undo.redo() }
        XCTAssertFalse(drawings.shapes.contains { $0.id == ring.id })
    }

    func testBatchEditUndoLeavesAShapeThePeerChangedSince() throws {
        // commitEdits is what ring follow uses, one step for several shapes
        let shapes = RangeRings.shapes(around: point("OP"), radii: [500, 1000], layerColorHex: nil)
        try local { _ = try drawings.addBatchDurably(shapes, actionName: "Add Range Rings") }
        var a = shapes[0]
        a.name = "a mine"
        var b = shapes[1]
        b.name = "b mine"
        try local { _ = try drawings.commitEdits([a, b], actionName: "Edit Rings") }
        var theirs = b
        theirs.notes = "peer note"
        try peer(theirs)

        undo.undo()
        XCTAssertEqual(drawings.shapes.first { $0.id == a.id }, shapes[0])
        XCTAssertEqual(drawings.shapes.first { $0.id == b.id }, theirs)
    }

    // v3 rooms dont go through apply()/delete() at all, every peer record lands via applyBatch.
    // these drive that path so the guard isnt only proven on the v2 committer

    private func peerBatch(_ waypoints: [Waypoint] = [], _ shapes: [DrawingShape] = [],
                           deleting: [UUID] = []) throws {
        let upserts = waypoints.map {
            SyncRemoteModelApplier.BatchOp.upsert(
                GeoJSONImporter.Result(waypoints: [$0], drawings: [], newLayers: [], invalidSkipped: 0))
        } + shapes.map {
            SyncRemoteModelApplier.BatchOp.upsert(
                GeoJSONImporter.Result(waypoints: [], drawings: [$0], newLayers: [], invalidSkipped: 0))
        }
        let outcome = try SyncRemoteModelApplier.applyBatch(
            upserts + deleting.map { .delete($0.uuidString) },
            waypointStore: self.waypoints, drawingStore: drawings)
        XCTAssertTrue(outcome.refused.isEmpty)
    }

    func testV3BatchPeerEditSurvivesUndoOfYourEarlierEdit() throws {
        let original = point("OP")
        try local { _ = try waypoints.addDurably(original) }
        var mine = original
        mine.name = "OP renamed by me"
        try local { _ = try waypoints.commitEdit(mine) }
        var theirs = mine
        theirs.latitude = -33.85
        try peerBatch([theirs])

        undo.undo()
        XCTAssertEqual(waypoints.waypoints, [theirs], "the peer's move must survive our undo")
    }

    func testV3BatchPeerEditOfADrawingSurvivesUndo() throws {
        let ring = RangeRings.shapes(around: point("OP"), radii: [300], layerColorHex: nil)[0]
        try local { _ = try drawings.addDurably(ring) }
        var mine = ring
        mine.name = "mine"
        try local { _ = try drawings.commitEdit(mine) }
        var theirs = mine
        theirs.notes = "peer note"
        try peerBatch([], [theirs])

        undo.undo()
        XCTAssertEqual(drawings.shapes.first { $0.id == ring.id }, theirs)
    }

    func testV3BatchEditUndoLeavesOnlyTheShapeThePeerChanged() throws {
        let shapes = RangeRings.shapes(around: point("OP"), radii: [500, 1000], layerColorHex: nil)
        try local { _ = try drawings.addBatchDurably(shapes, actionName: "Add Range Rings") }
        var a = shapes[0]
        a.name = "a mine"
        var b = shapes[1]
        b.name = "b mine"
        try local { _ = try drawings.commitEdits([a, b], actionName: "Edit Rings") }
        var theirs = b
        theirs.notes = "peer note"
        // a in the same batch unchanged, that one still has to undo
        try peerBatch([], [a, theirs])

        undo.undo()
        XCTAssertEqual(drawings.shapes.first { $0.id == a.id }, shapes[0])
        XCTAssertEqual(drawings.shapes.first { $0.id == b.id }, theirs)
    }

    func testV3BatchReplayOfUnchangedRecordsKeepsYourUndo() throws {
        let original = point("OP")
        let other = point("other", -33.80)
        try local { _ = try waypoints.addDurably(original) }
        try peerBatch([other])
        var mine = original
        mine.name = "mine"
        try local { _ = try waypoints.commitEdit(mine) }
        // reconnect snapshot replaying what we already have, plus a peer change to some other object
        var otherMoved = other
        otherMoved.latitude = -33.81
        try peerBatch([mine, otherMoved])

        undo.undo()
        XCTAssertEqual(waypoints.waypoints.first { $0.id == original.id }, original)
        XCTAssertEqual(waypoints.waypoints.first { $0.id == other.id }, otherMoved)
    }

    func testV3BatchPeerDeleteThenUndoDoesNotResurrect() throws {
        let original = point("OP")
        try local { _ = try waypoints.addDurably(original) }
        var mine = original
        mine.name = "mine"
        try local { _ = try waypoints.commitEdit(mine) }
        try peerBatch(deleting: [original.id])

        undo.undo()
        XCTAssertTrue(waypoints.waypoints.isEmpty)
    }

    func testUndoRegistrationIsReenabledAfterAFailedPeerWrite() throws {
        let marker = point("peer")
        let collidingShape = RangeRings.shapes(around: marker, radii: [100], layerColorHex: nil)[0]
        try peer(collidingShape)
        let clash = Waypoint(id: collidingShape.id, name: "clash", latitude: marker.latitude,
                             longitude: marker.longitude, layerID: marker.layerID)
        XCTAssertThrowsError(try peer(clash))
        XCTAssertTrue(undo.isUndoRegistrationEnabled)
        try local { _ = try waypoints.addDurably(point("mine")) }
        XCTAssertTrue(undo.canUndo)
    }
}

/// Same guard, but end to end through a real v3 SyncManager: the peer's record arrives as a
/// sealed, signed live put or in a reconnect snapshot, and Undo must not publish our stale
/// copy over it room wide.
@MainActor
final class SyncedEditUndoV3RoomTests: XCTestCase {
    private var harness: SyncManagerHarness!
    private var undo: UndoManager!

    override func setUp() async throws {
        harness = try SyncManagerHarness(joinCode: "3:a8-undo-peer-edits-room-01")
        undo = UndoManager()
        undo.groupsByEvent = false
        harness.waypointStore.undoManager = undo
        harness.drawingStore.undoManager = undo
    }

    override func tearDown() async throws {
        harness?.tearDown()
        harness = nil
        undo = nil
    }

    private func local(_ action: () throws -> Void) rethrows {
        undo.beginUndoGrouping()
        defer { undo.endUndoGrouping() }
        try action()
    }

    /// Publishes whatever the local edit produced and acks it, returns its counter.
    @discardableResult
    private func publishAndAck() throws -> Int64 {
        harness.pump(250)
        let put = try XCTUnwrap(harness.sentMutations().last)
        harness.ack(put)
        return try XCTUnwrap(VersionStamp.parse(put["vs"] as! String)).counter
    }

    private func sentFrames(for id: UUID) -> [String] {
        let wire = harness.wireId(id)
        return harness.factory.sockets.flatMap { $0.sentObjects() }.compactMap { frame in
            frame["id"] as? String == wire ? frame["vs"] as? String : nil
        }
    }

    private func assertTheirs(_ theirs: Waypoint, _ message: String = "",
                              file: StaticString = #filePath, line: UInt = #line) {
        let stored = harness.waypointStore.waypoints.filter { $0.id == theirs.id }
        XCTAssertEqual(stored.count, 1, message, file: file, line: line)
        XCTAssertEqual(stored.first?.name, theirs.name, message, file: file, line: line)
        XCTAssertEqual(stored.first?.latitude ?? 0, theirs.latitude, accuracy: 1e-9, message, file: file, line: line)
    }

    /// A then renames W and publishes it, so the room has our rename before the peer moves it.
    private func localAddAndRename() throws -> (original: Waypoint, mine: Waypoint, counter: Int64) {
        harness.join()
        harness.connect()
        harness.socket.deliver(harness.peerHello())
        harness.pump()
        let original = Waypoint(name: "OP", latitude: -33.86, longitude: 151.2,
                                layerID: DrawingLayer.legacyFallbackID)
        try local { _ = try harness.waypointStore.addDurably(original) }
        try publishAndAck()
        var mine = original
        mine.name = "OP renamed by me"
        try local { _ = try harness.waypointStore.commitEdit(mine) }
        let counter = try publishAndAck()
        return (original, mine, counter)
    }

    func testLivePeerEditSurvivesUndoAndIsNotOverwrittenRoomWide() throws {
        let (_, mine, counter) = try localAddAndRename()
        var theirs = mine
        theirs.latitude = -33.85
        harness.socket.deliver(harness.peerWaypointPut(theirs, counter: counter + 5, live: true))
        harness.pump()
        assertTheirs(theirs)
        let sentBefore = sentFrames(for: mine.id).count

        undo.undo()
        harness.pump(1_000)
        assertTheirs(theirs, "their newer move wins over our undo")
        XCTAssertEqual(sentFrames(for: mine.id).count, sentBefore, "nothing republished over the peer's edit")
    }

    func testReconnectSnapshotPeerEditSurvivesUndo() throws {
        let (_, mine, counter) = try localAddAndRename()
        harness.socket.remoteClose(code: 1006)
        harness.pump()
        harness.advanceUntilNewSocket()
        XCTAssertEqual(harness.socketCount, 2)
        var theirs = mine
        theirs.latitude = -33.85
        harness.connect(items: [harness.peerWaypointPut(theirs, counter: counter + 5)], seq: 3)
        assertTheirs(theirs)
        let sentBefore = sentFrames(for: mine.id).count

        undo.undo()
        harness.pump(1_000)
        assertTheirs(theirs)
        XCTAssertEqual(sentFrames(for: mine.id).count, sentBefore)
    }

    func testLivePeerEditOfADrawingSurvivesUndo() throws {
        harness.join()
        harness.connect()
        harness.socket.deliver(harness.peerHello())
        harness.pump()
        let layer = try XCTUnwrap(harness.drawingStore.layers.first)
        let anchor = Waypoint(name: "OP", latitude: -33.86, longitude: 151.2, layerID: layer.id)
        let ring = RangeRings.shapes(around: anchor, radii: [300], layerColorHex: nil)[0]
        try local { _ = try harness.drawingStore.addDurably(ring) }
        try publishAndAck()
        var mine = ring
        mine.name = "mine"
        try local { _ = try harness.drawingStore.commitEdit(mine) }
        let counter = try publishAndAck()
        var theirs = mine
        theirs.notes = "peer note"
        let layers = harness.drawingStore.layers.filter { $0.id == theirs.layerID }
        harness.socket.deliver(harness.peerPut(wireId: harness.wireId(theirs.id),
                                               content: harness.peerContent(drawing: theirs, layers: layers),
                                               counter: counter + 5, kind: "drawing", live: true))
        harness.pump()
        XCTAssertEqual(harness.drawingStore.shapes.first { $0.id == ring.id }?.notes, "peer note")

        undo.undo()
        harness.pump(1_000)
        let stored = harness.drawingStore.shapes.first { $0.id == ring.id }
        XCTAssertEqual(stored?.notes, "peer note", "their note survives our undo")
        XCTAssertEqual(stored?.name, "mine")
    }

    func testYourEditStillUndoesWhenThePeerOnlyTouchedSomethingElse() throws {
        let (original, mine, counter) = try localAddAndRename()
        let other = Waypoint(name: "B COY", latitude: -33.80, longitude: 151.1,
                             layerID: DrawingLayer.legacyFallbackID)
        harness.socket.deliver(harness.peerWaypointPut(other, counter: counter + 5, live: true))
        harness.pump()
        XCTAssertEqual(harness.waypointStore.waypoints.count, 2)

        undo.undo()
        harness.pump(250)
        XCTAssertEqual(harness.waypointStore.waypoints.first { $0.id == mine.id }, original)
        XCTAssertEqual(harness.waypointStore.waypoints.first { $0.id == other.id }?.name, other.name)
        let last = try XCTUnwrap(harness.sentMutations().last)
        XCTAssertEqual(last["id"] as? String, harness.wireId(mine.id), "the undo goes out like any local edit")
    }
}
