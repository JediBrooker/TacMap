import XCTest
@testable import TacticalMaps

/// S4-12 / S6-09: the hostile corpus used to stop at JSONSerialization. Here
/// every frame goes through the real SyncManager receive path, in every
/// connection phase, and must leave stores and sealed replay/chat files
/// untouched. Fence violations are allowed to cost a reconnect, never a
/// fail-closed stop.
@MainActor
final class SyncHostileFrameHandlerTests: XCTestCase {
    enum Phase: String, CaseIterable {
        case connecting, snapshotting, awaitingHelloAck, connected
    }

    enum Expectation: Equatable {
        case ignored
        case reconnect
    }

    private struct Case {
        let name: String
        let frame: String
    }

    private func corpus() throws -> [Case] {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let file = dir.appendingPathComponent("testdata/malicious_frames.json")
            if let data = try? Data(contentsOf: file) {
                let root = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
                return (root["cases"] as? [[String: Any]] ?? []).map {
                    Case(name: $0["name"] as! String, frame: $0["frame"] as! String)
                }
            }
            dir = dir.deletingLastPathComponent()
        }
        XCTFail("Could not locate testdata/malicious_frames.json")
        return []
    }

    private func digest(_ directory: URL) -> [String: Data] {
        var out: [String: Data] = [:]
        let enumerator = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: nil)
        while let url = enumerator?.nextObject() as? URL {
            if let data = try? Data(contentsOf: url) { out[url.path] = data }
        }
        return out
    }

    private func drive(_ harness: SyncManagerHarness, to phase: Phase) {
        harness.join()
        switch phase {
        case .connecting:
            break
        case .snapshotting:
            harness.beginSnapshot()
        case .awaitingHelloAck:
            harness.beginSnapshot()
            harness.page([])
            harness.endSnapshot()
        case .connected:
            harness.connect()
        }
    }

    private func check(
        _ name: String, frame: String, phase: Phase, expect: Expectation,
        joinCode: String = "3:sp2-hostile-room-0001", file: StaticString = #filePath, line: UInt = #line
    ) throws {
        let harness = try SyncManagerHarness(joinCode: joinCode)
        defer { harness.tearDown() }
        drive(harness, to: phase)
        let socket = harness.socket
        let statusBefore = harness.manager.status
        let filesBefore = digest(harness.directory)
        let waypointsBefore = harness.waypointStore.waypoints
        let shapesBefore = harness.drawingStore.shapes
        let layersBefore = harness.drawingStore.layers

        socket.deliverText(frame)
        harness.pump()

        let label = "\(name) @ \(phase.rawValue)"
        XCTAssertNil(harness.manager.pausedForAction, label, file: file, line: line)
        XCTAssertNotNil(harness.manager.room, label, file: file, line: line)
        XCTAssertEqual(harness.waypointStore.waypoints, waypointsBefore, label, file: file, line: line)
        XCTAssertEqual(harness.drawingStore.shapes, shapesBefore, label, file: file, line: line)
        XCTAssertEqual(harness.drawingStore.layers, layersBefore, label, file: file, line: line)
        XCTAssertEqual(digest(harness.directory), filesBefore, "\(label): durable state changed", file: file, line: line)
        switch expect {
        case .ignored:
            XCTAssertFalse(socket.isCancelled, label, file: file, line: line)
            XCTAssertEqual(harness.manager.status, statusBefore, label, file: file, line: line)
        case .reconnect:
            XCTAssertTrue(socket.isCancelled, label, file: file, line: line)
            XCTAssertNotNil(harness.manager.reconnectDueMs, "\(label): must recover, not fail closed", file: file, line: line)
            harness.advanceUntilNewSocket()
            XCTAssertEqual(harness.socketCount, 2, label, file: file, line: line)
        }
    }

    func testSharedCorpusThroughTheRealV3HandlerInEveryPhase() throws {
        for phase in Phase.allCases {
            for item in try corpus() {
                let isPage = (try? JSONSerialization.jsonObject(with: Data(item.frame.utf8)) as? [String: Any])?["t"] as? String == "snapshot"
                try check(item.name, frame: item.frame, phase: phase, expect: isPage ? .reconnect : .ignored)
            }
        }
    }

    func testSharedCorpusThroughTheRealV2Handler() throws {
        for phase in [Phase.connecting, .connected] {
            for item in try corpus() {
                let parsed = (try? JSONSerialization.jsonObject(with: Data(item.frame.utf8))) as? [String: Any]
                // before its snapshot completes a v2 socket rejects anything that parses
                let expect: Expectation = phase == .connecting && parsed != nil ? .reconnect : .ignored
                try checkV2(item, phase: phase, expect: expect)
            }
        }
    }

    private func checkV2(_ item: Case, phase: Phase, expect: Expectation) throws {
        let harness = try SyncManagerHarness(joinCode: "2:sp2-hostile-v2-room-01")
        defer { harness.tearDown() }
        harness.join()
        if phase == .connected {
            harness.socket.deliver(["t": "snapshot-begin", "seq": 1])
            harness.socket.deliver(["t": "snapshot", "items": [], "more": false])
            harness.socket.deliver(["t": "snapshot-end", "seq": 1])
            harness.pump()
            XCTAssertEqual(harness.manager.status, .connected)
        }
        let socket = harness.socket
        let statusBefore = harness.manager.status
        let waypointsBefore = harness.waypointStore.waypoints
        socket.deliverText(item.frame)
        harness.pump()
        let label = "v2 \(item.name) @ \(phase.rawValue)"
        XCTAssertNil(harness.manager.pausedForAction, label)
        XCTAssertEqual(harness.waypointStore.waypoints, waypointsBefore, label)
        switch expect {
        case .ignored:
            XCTAssertFalse(socket.isCancelled, label)
            XCTAssertEqual(harness.manager.status, statusBefore, label)
        case .reconnect:
            XCTAssertTrue(socket.isCancelled, label)
            XCTAssertNotNil(harness.manager.reconnectDueMs, label)
        }
    }

    // MARK: v3 hostile cases the shared corpus does not have yet

    private func json(_ object: [String: Any]) -> String {
        String(data: try! JSONSerialization.data(withJSONObject: object), encoding: .utf8)!
    }

    func testV3FenceViolationsCostAReconnectNotAStop() throws {
        let item: [String: Any] = ["id": SyncIdentity.urlB64Encode(Data(repeating: 1, count: 32))]
        try check("second_begin", frame: json(["t": "snapshot-begin", "seq": 2]), phase: .snapshotting, expect: .reconnect)
        try check("begin_after_connected", frame: json(["t": "snapshot-begin", "seq": 2]), phase: .connected, expect: .reconnect)
        try check("page_before_begin", frame: json(["t": "snapshot", "items": [], "more": false]), phase: .connecting, expect: .reconnect)
        try check("page_after_final", frame: json(["t": "snapshot", "items": [], "more": false]), phase: .awaitingHelloAck, expect: .reconnect)
        try check("end_before_final_page", frame: json(["t": "snapshot-end", "seq": 1]), phase: .snapshotting, expect: .reconnect)
        try check("more_not_boolean", frame: json(["t": "snapshot", "items": [], "more": 0]), phase: .snapshotting, expect: .reconnect)
        try check("items_not_array", frame: json(["t": "snapshot", "items": "x", "more": false]), phase: .snapshotting, expect: .reconnect)
        try check("item_not_object", frame: json(["t": "snapshot", "items": [1], "more": false]), phase: .snapshotting, expect: .reconnect)
        try check("item_id_not_canonical", frame: json(["t": "snapshot", "items": [["id": "abc"]], "more": false]),
                  phase: .snapshotting, expect: .reconnect)
        try check("duplicate_ids", frame: json(["t": "snapshot", "items": [item, item], "more": false]),
                  phase: .snapshotting, expect: .reconnect)
        try check("seq_not_integer", frame: json(["t": "snapshot-begin", "seq": 1.5]), phase: .connecting, expect: .reconnect)
    }

    func testV3EndSeqMismatchIsStructural() throws {
        let harness = try SyncManagerHarness(joinCode: "3:sp2-hostile-room-0001")
        defer { harness.tearDown() }
        harness.join()
        harness.beginSnapshot(seq: 7)
        harness.page([])
        harness.socket.deliver(["t": "snapshot-end", "seq": 8])
        harness.pump()
        XCTAssertNil(harness.lastHello)
        XCTAssertTrue(harness.socket.isCancelled)
        XCTAssertNotNil(harness.manager.reconnectDueMs)
        XCTAssertNil(harness.manager.pausedForAction)
    }

    func testV3FramesThatMustBeIgnored() throws {
        let unknown = SyncIdentity.urlB64Encode(Data(repeating: 9, count: 32))
        try check("hello_ack_wrong_sd", frame: json(["t": "hello-ack", "by": unknown, "sd": unknown, "vs": "x"]),
                  phase: .awaitingHelloAck, expect: .ignored)
        try check("op_ack_wrong_cth", frame: json([
            "t": "op-ack", "av": 1, "rid": "abcdefabcdefabcdef", "by": unknown, "sd": unknown,
            "id": unknown, "vs": "x", "kind": "waypoint", "cth": unknown]), phase: .connected, expect: .ignored)
        try check("leave_unknown_session", frame: json(["t": "leave", "by": unknown, "sd": unknown, "lv": 1]),
                  phase: .connected, expect: .ignored)
        try check("chat_unknown_session", frame: json(["t": "chat", "cv": 1, "by": unknown, "sd": unknown]),
                  phase: .connected, expect: .ignored)
        try check("counter_above_2_63", frame: json([
            "t": "put", "id": unknown, "vs": "8000000000000000:\(unknown)", "by": unknown,
            "kind": "waypoint", "ct": "AAAA", "pub": unknown, "sd": unknown]), phase: .connected, expect: .ignored)
        try check("put_before_snapshot", frame: json([
            "t": "put", "id": unknown, "vs": "0000000000000001:\(unknown)", "by": unknown,
            "kind": "waypoint", "ct": "AAAA", "pub": unknown, "sd": unknown]), phase: .snapshotting, expect: .ignored)
        var deep: Any = 1
        for _ in 0..<200 { deep = [deep] }
        try check("deep_nesting", frame: json(["t": "put", "id": deep]), phase: .connected, expect: .ignored)
    }
}
