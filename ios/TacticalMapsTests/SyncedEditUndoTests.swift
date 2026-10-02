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
