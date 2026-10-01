import XCTest
import CryptoKit
@testable import TacticalMaps

/// The v3 hostile cases contract section 2.1 adds (S4-12, S6-09). Every name
/// in the fixture's snapshot.skipUnverified / skipUnsupported / fatalStructural
/// lists gets a record built here and pushed through the REAL SyncManager
/// receive path, once inside a snapshot and once as a live frame. A skip has
/// to be side-effect free: no model change, no reconnect, no stop, and no
/// replay commit (checked by a lower-stamped probe for the same id that still
/// gets applied afterwards).
@MainActor
final class SyncHostileRecordTests: XCTestCase {

    private static func loadContract() -> [String: Any] {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let file = dir.appendingPathComponent("testdata/sync_client_behaviour.json")
            if let data = try? Data(contentsOf: file),
               let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                return object
            }
            dir = dir.deletingLastPathComponent()
        }
        XCTFail("Could not locate testdata/sync_client_behaviour.json")
        return [:]
    }

    private lazy var snapshotRules: [String: Any] = Self.loadContract()["snapshot"] as? [String: Any] ?? [:]

    private func names(_ key: String) -> [String] { snapshotRules[key] as? [String] ?? [] }

    /// Classifier guard that a well formed wire record can never reach: the
    /// receiver hash only fails when our own exporter throws on an object our
    /// own importer just produced. Kept in the contract as a defensive skip.
    private static let unreachableThroughTheWire: Set<String> = ["expected_model_hash_unavailable"]

    // MARK: record building

    /// What one hostile case needs.
    private struct Hostile {
        let name: String
        let category: SnapshotSkipCategory
        /// snapshot item shape
        let item: [String: Any]
        /// the same record as a live put/del frame
        let live: [String: Any]
        /// UUID the record targets, nil when the probe would be refused anyway
        let probe: UUID?
        /// seed the replay state before join (pinned key case)
        var pinPeerTo: String?
        /// local drawing that has to exist before join (identity collision)
        var localDrawing: DrawingShape?
    }

    private static let hostileCounter: Int64 = 50
    private static let probeCounter: Int64 = 10

    private func jsonData(_ object: Any) -> Data {
        try! JSONSerialization.data(withJSONObject: object)
    }

    private func waypoint(_ id: UUID, name: String = "Hostile") -> Waypoint {
        Waypoint(id: id, name: name, latitude: -33.86, longitude: 151.21, layerID: DrawingLayer.legacyFallbackID)
    }

    /// Sealed + signed by peer B unless told otherwise. inner gets the real
    /// signature and returns the plaintext to seal.
    private func record(
        _ h: SyncManagerHarness,
        wireId: String,
        kind: String,
        deleted: Bool = false,
        content: String = "",
        signer: Data? = nil,
        inner: ((String) -> Data)? = nil
    ) -> [String: Any] {
        let counter = Self.hostileCounter
        let vs = VersionStamp(counter: counter, actorId: h.peerActor).encode()
        let preimage = SyncIdentity.buildPreimage(
            domain: deleted ? SyncIdentity.domainDelete : SyncIdentity.domainPut,
            roomIdRaw: h.keys.roomIdRaw, actorId: h.peerActor, sessionDomain: h.peerSessionRaw,
            counterHex16: VersionStamp.counterHex16(counter),
            objectId: wireId, kind: kind, payloadHash: SyncIdentity.sha256(Data(content.utf8)))
        let sig = SyncSigning.sign(signer ?? h.peerSeed, preimage)!
        let plain = inner?(sig) ?? (deleted ? jsonData(["sig": sig]) : jsonData(["c": content, "sig": sig]))
        let sealed = SyncCrypto.seal(h.keys.roomKey, plain, aad: SyncCrypto.aadV3(wireObjectId: wireId, vs: vs, kind: kind))!
        return [
            "id": wireId, "vs": vs, "by": h.peerActor, "kind": kind,
            "ct": sealed.base64EncodedString(), "pub": h.peerPub, "sd": h.peerSession, "deleted": deleted
        ]
    }

    private func asLive(_ item: [String: Any], deleted: Bool) -> [String: Any] {
        var frame = item
        frame["t"] = deleted ? "del" : "put"
        frame["seq"] = 2
        return frame
    }

    private func hostile(_ name: String, _ h: SyncManagerHarness) -> Hostile? {
        let target = UUID()
        let wire = h.wireId(target)
        let content = h.peerContent(waypoint: waypoint(target))
        let otherSeed = Data(repeating: 0x0e, count: 32)
        let valid = record(h, wireId: wire, kind: "waypoint", content: content)

        func unverified(_ item: [String: Any], deleted: Bool = false, probe: UUID? = target) -> Hostile {
            Hostile(name: name, category: .unverified, item: item, live: asLive(item, deleted: deleted), probe: probe)
        }
        func unsupported(_ item: [String: Any], probe: UUID? = target) -> Hostile {
            Hostile(name: name, category: .unsupported, item: item, live: asLive(item, deleted: false), probe: probe)
        }

        switch name {
        // skip, unverified
        case "vs_unparseable":
            var item = valid; item["vs"] = "zz"
            return unverified(item)
        case "by_not_equal_vs_actor":
            var item = valid; item["by"] = SyncIdentity.urlB64Encode(Data(repeating: 0x71, count: 32))
            return unverified(item)
        case "pub_or_sd_not_canonical":
            var item = valid; item["sd"] = "AAAA"
            return unverified(item)
        case "actor_binding_mismatch":
            var item = valid; item["pub"] = SyncSigning.publicKey(otherSeed)!
            return unverified(item)
        case "pub_differs_from_pinned_key":
            // only a damaged or ancient replay file can pin another key to a
            // bound actor, so seed one. Every probe from B is refused too.
            var hostile = unverified(valid, probe: nil)
            hostile.pinPeerTo = SyncSigning.publicKey(otherSeed)!
            return hostile
        case "kind_syntax_invalid":
            var item = valid; item["kind"] = "way point!"
            return unverified(item)
        case "type_and_deleted_inconsistent":
            // a put shaped record flagged deleted / sent as a del frame
            var item = valid; item["deleted"] = true
            return Hostile(name: name, category: .unverified, item: item,
                           live: asLive(valid, deleted: true), probe: target)
        case "ct_not_canonical_or_too_short_or_too_long":
            var item = valid; item["ct"] = "AAAA"
            return unverified(item)
        case "aead_open_failed":
            var item = valid
            item["ct"] = Data((0..<64).map { UInt8(truncatingIfNeeded: $0 &* 37 &+ 11) }).base64EncodedString()
            return unverified(item)
        case "inner_json_invalid":
            return unverified(record(h, wireId: wire, kind: "waypoint", content: content) { _ in Data("not json".utf8) })
        case "signature_missing_or_invalid":
            return unverified(record(h, wireId: wire, kind: "waypoint", content: content, signer: otherSeed))
        case "tombstone_inner_has_extra_keys":
            let tomb = record(h, wireId: wire, kind: "del", deleted: true) { sig in self.jsonData(["sig": sig, "c": ""]) }
            return unverified(tomb, deleted: true)

        // skip, unsupported (all of these are sealed and signed by B)
        case "kind_unknown_but_authentic":
            return unsupported(record(h, wireId: wire, kind: "route", content: content))
        case "put_content_missing_or_empty":
            return unsupported(record(h, wireId: wire, kind: "waypoint", content: ""))
        case "importer_failed":
            return unsupported(record(h, wireId: wire, kind: "waypoint", content: "this is not geojson"))
        case "importer_invalid_skipped_nonzero":
            var collection = try! JSONSerialization.jsonObject(with: Data(content.utf8)) as! [String: Any]
            var features = collection["features"] as! [Any]
            features.append(["type": "Feature", "properties": [:] as [String: Any],
                             "geometry": ["type": "Point", "coordinates": [] as [Double]]])
            collection["features"] = features
            let broken = String(data: jsonData(collection), encoding: .utf8)!
            return unsupported(record(h, wireId: wire, kind: "waypoint", content: broken))
        case "object_count_not_exactly_one":
            let two = try! GeoJSONExporter.export(waypoints: [waypoint(target), waypoint(UUID(), name: "Extra")])
            return unsupported(record(h, wireId: wire, kind: "waypoint", content: two))
        case "kind_content_mismatch":
            return unsupported(record(h, wireId: wire, kind: "drawing", content: content))
        case "embedded_uuid_does_not_match_wire_id":
            let other = h.peerContent(waypoint: waypoint(UUID()))
            return unsupported(record(h, wireId: wire, kind: "waypoint", content: other))
        case "identity_collision_with_other_object_kind":
            // a waypoint record carrying the UUID of one of our drawings
            let shape = DrawingShape(id: target, kind: .polyline, coordinates: [
                Coordinate2D(latitude: -33.8, longitude: 151.2), Coordinate2D(latitude: -33.81, longitude: 151.21)
            ], layerID: DrawingLayer.legacyFallbackID)
            var hostile = unsupported(valid, probe: nil)
            hostile.localDrawing = shape
            return hostile
        default:
            return nil
        }
    }

    // MARK: classifier names the exact check

    func testEveryFixtureSkipNameHasAHostileRecordThatTripsExactlyThatCheck() throws {
        let harness = try SyncManagerHarness(joinCode: "3:sp2-hostile-record-room")
        defer { harness.tearDown() }
        let expected: [(String, SnapshotSkipCategory)] =
            names("skipUnverified").map { ($0, .unverified) } + names("skipUnsupported").map { ($0, .unsupported) }
        XCTAssertGreaterThanOrEqual(expected.count, 20, "fixture lists went missing")
        for (name, category) in expected {
            if Self.unreachableThroughTheWire.contains(name) { continue }
            guard let hostile = hostile(name, harness) else {
                XCTFail("no hostile record for fixture category \(name), add one")
                continue
            }
            XCTAssertEqual(hostile.category, category, name)
            let deleted = SnapshotValidator.strictJSONBoolean(hostile.item["deleted"]) ?? false
            let collisionId = hostile.localDrawing?.id
            let pinned = hostile.pinPeerTo
            let context = SnapshotRecordContext(
                keys: harness.keys,
                roomKey: harness.keys.roomKey,
                actorKeyIsAcceptable: { actor, pub in pinned == nil || actor != harness.peerActor || pub == pinned },
                layers: [],
                fallbackLayerID: DrawingLayer.legacyFallbackID,
                isWaypointID: { _ in false },
                isDrawingID: { $0 == collisionId },
                localIdForWireId: { _ in nil },
                wireIdForUUID: { harness.wireId($0) })
            switch SnapshotRecordClassifier.classify(hostile.item, deleted: deleted, context: context) {
            case .skip(let got, let reason):
                XCTAssertEqual(got, category, name)
                XCTAssertEqual(reason, name, "\(name) tripped an earlier check")
            case .valid:
                XCTFail("\(name) was accepted")
            }
        }
    }

    // MARK: through the real handler

    private func makeHarness(for name: String) throws -> (SyncManagerHarness, Hostile) {
        let harness = try SyncManagerHarness(joinCode: "3:sp2-hostile-record-room")
        let hostile = try XCTUnwrap(self.hostile(name, harness), name)
        if let shape = hostile.localDrawing {
            _ = try harness.drawingStore.addDurably(shape)
        }
        if let pin = hostile.pinPeerTo {
            let seeded = SyncReplayState(roomId: harness.keys.roomId, containerURL: harness.directory)
            XCTAssertTrue(seeded.load())
            XCTAssertTrue(seeded.registerActor(harness.peerActor, pubkey: pin))
            try seeded.save()
        }
        return (harness, hostile)
    }

    private func skipIssue(_ category: SnapshotSkipCategory) -> SyncIssueCode {
        category == .unverified ? .skippedUnverified : .skippedUnsupported
    }

    /// The skipped record left no stamp behind: a lower stamp for the same id
    /// still wins, and nothing reconnected or stopped.
    private func assertSkippedWithoutSideEffects(
        _ harness: SyncManagerHarness, _ hostile: Hostile, path: String,
        file: StaticString = #filePath, line: UInt = #line
    ) {
        let label = "\(hostile.name) via \(path)"
        harness.pump(60_000)
        XCTAssertEqual(harness.manager.status, .connected, label, file: file, line: line)
        XCTAssertEqual(harness.socketCount, 1, "\(label): a skip never reconnects", file: file, line: line)
        XCTAssertFalse(harness.socket.isCancelled, label, file: file, line: line)
        XCTAssertNil(harness.manager.pausedForAction, label, file: file, line: line)
        XCTAssertEqual(harness.manager.surfacedIssueLog.filter { $0 == skipIssue(hostile.category) }.count, 1,
                       "\(label): surfaced once", file: file, line: line)
        XCTAssertFalse(harness.manager.surfacedIssueLog.contains(.snapshotStructural), label, file: file, line: line)
        if hostile.category == .unverified {
            XCTAssertEqual(harness.manager.lastIssueKind, .security,
                           "\(label): unverified skip is not a clean snapshot", file: file, line: line)
        }
        guard let target = hostile.probe else { return }
        XCTAssertFalse(harness.waypointStore.waypoints.contains { $0.id == target }, label, file: file, line: line)
        // nothing was committed for it, so an older honest record still applies
        let honest = waypoint(target, name: "Honest")
        harness.socket.deliver(harness.peerWaypointPut(honest, counter: Self.probeCounter, live: true))
        harness.pump()
        XCTAssertEqual(harness.waypointStore.waypoints.first { $0.id == target }?.name, "Honest",
                       "\(label): the skipped record must not be committed to replay state", file: file, line: line)
        XCTAssertEqual(harness.socketCount, 1, label, file: file, line: line)
    }

    func testEverySkipCategoryInsideASnapshotIsSkippedAndTheHandshakeCompletes() throws {
        let all = names("skipUnverified") + names("skipUnsupported")
        for name in all where !Self.unreachableThroughTheWire.contains(name) {
            let (harness, hostile) = try makeHarness(for: name)
            defer { harness.tearDown() }
            let bystander = waypoint(UUID(), name: "Bystander")
            harness.join()
            harness.connect(items: [harness.peerWaypointPut(bystander, counter: 2), hostile.item])
            XCTAssertNotNil(harness.lastHello, "\(name): hello still goes out")
            if hostile.pinPeerTo == nil {
                // (with B pinned to another key the bystander from B is refused too)
                XCTAssertTrue(harness.waypointStore.waypoints.contains { $0.id == bystander.id },
                              "\(name): the rest of the snapshot still applies")
            }
            assertSkippedWithoutSideEffects(harness, hostile, path: "snapshot")
        }
    }

    func testEverySkipCategoryAsALiveFrameIsSkippedWithoutAReconnect() throws {
        let all = names("skipUnverified") + names("skipUnsupported")
        for name in all where !Self.unreachableThroughTheWire.contains(name) {
            let (harness, hostile) = try makeHarness(for: name)
            defer { harness.tearDown() }
            harness.join()
            harness.connect()
            // our own first-join publish (the colliding drawing) gets its ack,
            // otherwise ack exhaustion would reconnect and muddy the check
            harness.pump(250)
            harness.sentMutations().forEach { harness.ack($0) }
            XCTAssertNil(harness.manager.lastError, name)
            harness.socket.deliver(hostile.live)
            harness.pump()
            assertSkippedWithoutSideEffects(harness, hostile, path: "live")
            // the same thing again is not a second notice (once per join)
            harness.socket.deliver(hostile.live)
            harness.pump()
            XCTAssertEqual(harness.manager.surfacedIssueLog.filter { $0 == skipIssue(hostile.category) }.count, 1, name)
        }
    }

    // MARK: fatal structural, by fixture name

    private func jsonText(_ object: [String: Any]) -> String {
        String(data: try! JSONSerialization.data(withJSONObject: object), encoding: .utf8)!
    }

    private enum StructuralPhase { case connecting, snapshotting, finalPage, connected }

    private func canonicalId(_ n: Int) -> String {
        var bytes = Data(repeating: 0, count: 32)
        withUnsafeBytes(of: UInt64(n).bigEndian) { bytes.replaceSubrange(24..<32, with: $0) }
        return SyncIdentity.urlB64Encode(bytes)
    }

    /// Frames for each fatalStructural fixture name, and the phase to send them in.
    private func structural(_ name: String) -> (StructuralPhase, [String])? {
        let item: [String: Any] = ["id": canonicalId(1)]
        switch name {
        case "snapshot_begin_when_not_connecting":
            return (.connected, [jsonText(["t": "snapshot-begin", "seq": 2])])
        case "second_snapshot_begin":
            return (.snapshotting, [jsonText(["t": "snapshot-begin", "seq": 2])])
        case "seq_not_nonnegative_integer":
            return (.connecting, [jsonText(["t": "snapshot-begin", "seq": -1])])
        case "page_before_begin":
            return (.connecting, [jsonText(["t": "snapshot", "items": [], "more": false])])
        case "page_after_final_page":
            return (.finalPage, [jsonText(["t": "snapshot", "items": [], "more": false])])
        case "more_not_boolean":
            return (.snapshotting, [jsonText(["t": "snapshot", "items": [], "more": 1])])
        case "items_not_array":
            return (.snapshotting, [jsonText(["t": "snapshot", "items": ["id": "x"], "more": false])])
        case "item_not_object":
            return (.snapshotting, [jsonText(["t": "snapshot", "items": ["x"], "more": false])])
        case "item_id_missing_or_not_canonical_32_byte_base64url":
            return (.snapshotting, [jsonText(["t": "snapshot", "items": [["vs": "x"]], "more": false])])
        case "duplicate_wire_id":
            return (.snapshotting, [jsonText(["t": "snapshot", "items": [item], "more": true]),
                                    jsonText(["t": "snapshot", "items": [item], "more": false])])
        case "end_before_final_page":
            return (.snapshotting, [jsonText(["t": "snapshot", "items": [], "more": true]),
                                    jsonText(["t": "snapshot-end", "seq": 1])])
        case "end_seq_mismatch":
            return (.finalPage, [jsonText(["t": "snapshot-end", "seq": 9])])
        case "aggregate_bytes_over_54525952":
            // 1 MiB pages until the handshake byte ceiling is gone
            let pad = String(repeating: "p", count: 1_040_000)
            return (.snapshotting, (0..<53).map { page in
                "{\"t\":\"snapshot\",\"more\":true,\"items\":[{\"id\":\"\(canonicalId(page + 10))\",\"pad\":\"\(pad)\"}]}"
            })
        case "item_count_over_10000":
            return (.snapshotting, [jsonText(["t": "snapshot", "more": false,
                                          "items": (0..<10_001).map { ["id": canonicalId($0 + 100)] }])])
        default:
            return nil
        }
    }

    func testEveryFatalStructuralNameRejectsTheWholeSnapshotAndReconnects() throws {
        let fatal = names("fatalStructural")
        XCTAssertGreaterThanOrEqual(fatal.count, 14)
        for name in fatal {
            guard let (phase, frames) = structural(name) else {
                XCTFail("no hostile frames for fatal structural case \(name), add them")
                continue
            }
            let harness = try SyncManagerHarness(joinCode: "3:sp2-hostile-structural")
            defer { harness.tearDown() }
            harness.join()
            // one honest record so "nothing committed" means something
            let honest = waypoint(UUID(), name: "Should not land")
            switch phase {
            case .connecting:
                harness.socket.open()
            case .snapshotting:
                harness.beginSnapshot()
                harness.socket.deliver(["t": "snapshot", "items": [harness.peerWaypointPut(honest, counter: 3)], "more": true])
            case .finalPage:
                harness.beginSnapshot()
                harness.page([harness.peerWaypointPut(honest, counter: 3)])
            case .connected:
                harness.connect()
            }
            harness.pump()
            let socket = harness.socket
            let helloBefore = socket.sent(type: "hello").count
            for frame in frames { socket.deliverText(frame) }
            harness.pump()
            XCTAssertTrue(socket.isCancelled, "\(name): the socket has to go")
            XCTAssertNil(harness.manager.pausedForAction, "\(name): one failure is not a stop")
            XCTAssertNotNil(harness.manager.reconnectDueMs, "\(name): reconnects")
            XCTAssertEqual(socket.sent(type: "hello").count, helloBefore, "\(name): no hello for a rejected snapshot")
            if phase != .connected {
                XCTAssertFalse(harness.waypointStore.waypoints.contains { $0.id == honest.id },
                               "\(name): nothing from a rejected snapshot is committed")
            }
            harness.advanceUntilNewSocket()
            XCTAssertEqual(harness.socketCount, 2, name)
        }
    }

    /// Three structural failures in a row stop with SNAPSHOT_MALFORMED_STOPPED
    /// and the first one is the SECURITY "snapshot authentication failed".
    func testStructuralFailureIsASecurityIssueOncePerSession() throws {
        let harness = try SyncManagerHarness(joinCode: "3:sp2-hostile-structural")
        defer { harness.tearDown() }
        harness.join()
        harness.beginSnapshot()
        harness.socket.deliverText(jsonText(["t": "snapshot", "items": ["x"], "more": false]))
        harness.pump()
        XCTAssertEqual(harness.manager.lastIssueKind, .security)
        XCTAssertEqual(harness.manager.surfacedIssueLog.filter { $0 == .snapshotStructural }.count, 1)
        XCTAssertNil(harness.manager.pausedForAction)
    }
}
