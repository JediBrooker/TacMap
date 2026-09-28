import XCTest
@testable import TacticalMaps

/// Pins range-ring geometry to testdata/range_rings.json (shared with Android)
/// and checks that rings commit as ordinary drawings in one undo step.
final class RangeRingsTests: XCTestCase {
    private let testKey = Data((0..<32).map { UInt8($0) })
    private var fixture: [String: Any]!

    override func setUpWithError() throws {
        try super.setUpWithError()
        SafeStore.keyProvider = { [testKey] in testKey }
        SealedMigrationPolicy.resetForTests(key: testKey)
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("testdata/range_rings.json")
            if FileManager.default.fileExists(atPath: candidate.path) {
                fixture = try XCTUnwrap(
                    JSONSerialization.jsonObject(with: Data(contentsOf: candidate)) as? [String: Any]
                )
                return
            }
            dir = dir.deletingLastPathComponent()
        }
        XCTFail("Could not locate testdata/range_rings.json")
    }

    override func tearDown() {
        SafeStore.keyProvider = { try DataKey.key() }
        SealedMigrationPolicy.resetForTests(key: testKey)
        super.tearDown()
    }

    private func dbl(_ value: Any?) -> Double { (value as? NSNumber)?.doubleValue ?? .nan }

    func testRingGeometryMatchesSharedFixture() {
        XCTAssertEqual(RangeRings.segments, fixture["segments"] as? Int)
        XCTAssertEqual(RangeRings.maxRings, fixture["maxRings"] as? Int)
        XCTAssertEqual(RangeRings.maxRadiusMetres, dbl(fixture["maxRadiusMetres"]))
        let tolerance = dbl(fixture["toleranceDegrees"])
        for testCase in fixture["cases"] as! [[String: Any]] {
            let name = testCase["name"] as! String
            let center = testCase["center"] as! [String: Any]
            let ring = RangeRings.ring(
                center: Coordinate2D(latitude: dbl(center["lat"]), longitude: dbl(center["lon"])),
                radiusMetres: dbl(testCase["radiusMetres"])
            )
            XCTAssertEqual(ring.count, fixture["pointsPerRing"] as? Int, name)
            XCTAssertEqual(ring.first, ring.last, "\(name) must close")
            for sample in testCase["samples"] as! [[String: Any]] {
                let point = ring[sample["index"] as! Int]
                XCTAssertEqual(point.latitude, dbl(sample["lat"]), accuracy: tolerance, name)
                let lonError = abs((point.longitude - dbl(sample["lon"]) + 540)
                    .truncatingRemainder(dividingBy: 360) - 180)
                XCTAssertLessThan(lonError, tolerance, name)
                XCTAssertGreaterThanOrEqual(point.longitude, -180, name)
                XCTAssertLessThan(point.longitude, 180, name)
            }
        }
    }

    func testRadiiContract() {
        for valid in fixture["radii"] as! [[String: Any]] {
            XCTAssertEqual(
                RangeRings.radii(intervalMetres: dbl(valid["intervalMetres"]), count: valid["count"] as! Int),
                (valid["expected"] as! [NSNumber]).map(\.doubleValue)
            )
        }
        for invalid in fixture["invalid"] as! [[String: Any]] {
            XCTAssertNil(RangeRings.radii(intervalMetres: dbl(invalid["intervalMetres"]),
                                          count: invalid["count"] as! Int))
        }
        XCTAssertNil(RangeRings.radii(intervalMetres: .nan, count: 1))
        XCTAssertNil(RangeRings.radii(intervalMetres: .infinity, count: 1))
    }

    func testShapesAreDashedLinesOnTheSymbolLayerInItsAffiliationColour() {
        let colors = fixture["strokeColors"] as! [String: String]
        let layerID = UUID()
        let hostile = Waypoint(name: "Enemy MG", latitude: -33.8688, longitude: 151.2093,
                               kind: .military(MilitarySymbolSpec(affiliation: .hostile, echelon: .team)),
                               layerID: layerID)
        let shapes = RangeRings.shapes(around: hostile, radii: [300, 600], layerColorHex: "#123456")
        XCTAssertEqual(shapes.count, 2)
        for shape in shapes {
            XCTAssertEqual(shape.kind, .polyline)
            XCTAssertEqual(shape.layerID, layerID)
            XCTAssertEqual(shape.style.strokeColorHex, colors["hostile"])
            XCTAssertEqual(shape.style.dashPattern, RangeRings.dashPattern)
            XCTAssertEqual(shape.coordinates.count, RangeRings.segments + 1)
            XCTAssertEqual(shape.coordinates.first, shape.coordinates.last)
        }
        XCTAssertNotEqual(shapes[0].id, shapes[1].id)

        let friendlyTask = Waypoint(name: "OBJ", latitude: 0, longitude: 0,
                                    kind: .controlMeasure(.seize), taskColor: .blue)
        XCTAssertEqual(RangeRings.strokeColorHex(for: friendlyTask, layerColorHex: nil), colors["friend"])
        let marker = Waypoint(name: "RV", latitude: 0, longitude: 0)
        XCTAssertEqual(RangeRings.strokeColorHex(for: marker, layerColorHex: "#123456"), "#123456")
    }

    func testBatchAddIsOneDurableWriteAndOneUndoStep() throws {
        var writes = 0
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = DrawingStore(storageURL: dir.appendingPathComponent("drawings.json"),
                                 persistenceWriter: { _, _, _ in writes += 1 })
        writes = 0 // discard the fresh-install seed write
        let undo = UndoManager()
        undo.groupsByEvent = false
        store.undoManager = undo
        let before = store.shapes

        let waypoint = Waypoint(name: "OP", latitude: -33.8688, longitude: 151.2093,
                                layerID: store.layers[0].id)
        let rings = RangeRings.shapes(around: waypoint, radii: [500, 1000, 1500], layerColorHex: nil)
        undo.beginUndoGrouping()
        XCTAssertEqual(try store.addBatchDurably(rings, actionName: "Add Range Rings"), 3)
        undo.endUndoGrouping()
        XCTAssertEqual(writes, 1, "one ring batch must produce one durable write")
        XCTAssertEqual(store.shapes.map(\.id), before.map(\.id) + rings.map(\.id))
        XCTAssertEqual(try store.addBatchDurably(rings, actionName: "Add Range Rings"), 0,
                       "a retried batch must not duplicate rings")

        undo.undo()
        XCTAssertEqual(store.shapes.map(\.id), before.map(\.id))
        XCTAssertTrue(undo.canRedo)
        undo.redo()
        XCTAssertEqual(Set(store.shapes.map(\.id)), Set(before.map(\.id) + rings.map(\.id)))
    }

    func testFailedBatchWritePublishesNothing() throws {
        var fail = false
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let store = DrawingStore(storageURL: dir.appendingPathComponent("drawings.json"),
                                 persistenceWriter: { _, _, _ in if fail { throw CocoaError(.fileWriteUnknown) } })
        let undo = UndoManager()
        store.undoManager = undo
        let before = store.shapes
        fail = true
        let waypoint = Waypoint(name: "OP", latitude: 1, longitude: 2)
        XCTAssertThrowsError(try store.addBatchDurably(
            RangeRings.shapes(around: waypoint, radii: [100], layerColorHex: nil),
            actionName: "Add Range Rings"
        ))
        XCTAssertEqual(store.shapes, before)
        XCTAssertFalse(undo.canUndo)
    }
}
