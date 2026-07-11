import XCTest
import CryptoKit
@testable import TacticalMaps

/// Validates iOS v3 protocol implementation against the shared fixture
/// (testdata/sync_protocol_v3.json). If these pass, iOS interoperates
/// byte-for-byte with the relay and Android.
final class SyncProtocolV3Tests: XCTestCase {

    private lazy var fixture: [String: Any] = {
        var dir = URL(fileURLWithPath: #file).deletingLastPathComponent()
        for _ in 0..<8 {
            let f = dir.appendingPathComponent("testdata/sync_protocol_v3.json")
            if FileManager.default.fileExists(atPath: f.path),
               let data = try? Data(contentsOf: f),
               let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                return obj
            }
            dir = dir.deletingLastPathComponent()
        }
        XCTFail("Could not locate testdata/sync_protocol_v3.json")
        return [:]
    }()

    // MARK: - Key derivation

    func testV3KeyDerivationMatchesFixture() {
        let kd = fixture["key_derivation"] as! [String: Any]
        let joinCode = kd["join_code"] as! String
        let keys = SyncCrypto.deriveRoomV3(joinCode)

        XCTAssertEqual(keys.roomId, kd["room_id"] as? String)
        XCTAssertEqual(hex(keys.roomIdRaw), kd["room_id_raw_hex"] as? String)
        XCTAssertEqual(hex(keys.roomKey.withUnsafeBytes { Data($0) }), kd["room_key_hex"] as? String)
        XCTAssertEqual(hex(keys.metadataKey), kd["metadata_key_hex"] as? String)
        XCTAssertEqual(keys.authToken, kd["auth_token_base64url"] as? String)
    }

    // MARK: - Actor ID

    func testActorIdMatchesFixture() {
        let kd = fixture["key_derivation"] as! [String: Any]
        let identity = fixture["identity"] as! [String: Any]
        let roomIdRaw = SyncIdentity.hexToBytes(kd["room_id_raw_hex"] as! String)

        let devA = identity["device_a"] as! [String: Any]
        let pubA = SyncIdentity.hexToBytes(devA["pubkey_raw_hex"] as! String)
        XCTAssertEqual(SyncIdentity.actorId(roomIdRaw: roomIdRaw, pubkeyRaw: pubA),
                       devA["actor_id"] as? String)

        let devB = identity["device_b"] as! [String: Any]
        let pubB = SyncIdentity.hexToBytes(devB["pubkey_raw_hex"] as! String)
        XCTAssertEqual(SyncIdentity.actorId(roomIdRaw: roomIdRaw, pubkeyRaw: pubB),
                       devB["actor_id"] as? String)
    }

    func testCrossRoomCorrelationPrevented() {
        let identity = fixture["identity"] as! [String: Any]
        let devA = identity["device_a"] as! [String: Any]
        let cross = identity["cross_room_correlation"] as! [String: Any]

        let pubA = SyncIdentity.hexToBytes(devA["pubkey_raw_hex"] as! String)
        let altKeys = SyncCrypto.deriveRoomV3(cross["alt_join_code"] as! String)
        let altActorId = SyncIdentity.actorId(roomIdRaw: altKeys.roomIdRaw, pubkeyRaw: pubA)

        XCTAssertEqual(altActorId, cross["device_a_actor_id_in_alt_room"] as? String)
        XCTAssertNotEqual(altActorId, devA["actor_id"] as? String)
    }

    // MARK: - Wire object IDs

    func testWireObjectIdMatchesFixture() {
        let wids = fixture["wire_object_ids"] as! [String: Any]
        let metadataKey = SyncIdentity.hexToBytes(wids["metadata_key_hex"] as! String)
        let cases = wids["cases"] as! [[String: Any]]

        for c in cases {
            let localUuid = SyncIdentity.hexToBytes(c["local_uuid_hex"] as! String)
            XCTAssertEqual(
                SyncIdentity.wireObjectId(metadataKey: metadataKey, localUuidBytes: localUuid),
                c["wire_object_id"] as? String)
        }
    }

    // MARK: - VersionStamp

    func testVersionStampComparisonMatchesFixture() {
        let vs = fixture["version_stamp"] as! [String: Any]
        let cases = vs["comparison_cases"] as! [[String: Any]]

        for c in cases {
            let a = VersionStamp.parse(c["a"] as! String)!
            let b = VersionStamp.parse(c["b"] as! String)!
            let winner = c["winner"] as! String
            if winner == "a" {
                XCTAssertTrue(a > b, "\(c["name"]!): a should win")
            } else {
                XCTAssertTrue(b > a, "\(c["name"]!): b should win")
            }
        }
    }

    func testVersionStampRoundTrip() {
        let vs = VersionStamp(counter: 42, actorId: "test-actor-id-here")
        XCTAssertEqual(vs.encode(), "000000000000002a:test-actor-id-here")
        let parsed = VersionStamp.parse(vs.encode())!
        XCTAssertEqual(vs, parsed)
    }

    func testVersionStampMaxCounter() {
        let vs = VersionStamp(counter: VersionStamp.maxCounter, actorId: "actor")
        XCTAssertEqual(vs.encode(), "7fffffffffffffff:actor")
        let parsed = VersionStamp.parse(vs.encode())!
        XCTAssertEqual(parsed.counter, VersionStamp.maxCounter)
    }

    func testVersionStampParseBadInput() {
        XCTAssertNil(VersionStamp.parse(""))
        XCTAssertNil(VersionStamp.parse("not-a-stamp"))
        XCTAssertNil(VersionStamp.parse("0000000000000001"))
        XCTAssertNil(VersionStamp.parse("000000000000001:x"))
        XCTAssertNil(VersionStamp.parse("00000000000000001:x"))
        XCTAssertNil(VersionStamp.parse("000000000000000g:x"))
    }

    // MARK: - Signed preimage

    func testSignedPreimageMatchesFixture() {
        let sp = fixture["signed_preimage"] as! [String: Any]
        let kd = fixture["key_derivation"] as! [String: Any]
        let roomIdRaw = SyncIdentity.hexToBytes(kd["room_id_raw_hex"] as! String)
        let sessionDomain = SyncIdentity.hexToBytes(sp["session_domain_hex"] as! String)
        let cases = sp["cases"] as! [[String: Any]]

        for c in cases {
            let domainStr = c["domain_byte"] as! String
            let domainByte = UInt8(domainStr.dropFirst(2), radix: 16)!
            let actorId = c["actor_id"] as! String
            let counter = (c["counter"] as! NSNumber).int64Value
            let objectId = c["object_id"] as! String
            let kind = c["kind"] as! String
            let payloadHash = SyncIdentity.hexToBytes(c["payload_hash_hex"] as! String)

            let preimage = SyncIdentity.buildPreimage(
                domain: domainByte, roomIdRaw: roomIdRaw,
                actorId: actorId, sessionDomain: sessionDomain,
                counterHex16: VersionStamp.counterHex16(counter),
                objectId: objectId, kind: kind, payloadHash: payloadHash)

            XCTAssertEqual(hex(preimage), c["preimage_hex"] as? String,
                           "\(c["name"]!): preimage mismatch")
        }
    }

    func testSignatureVerifiesAgainstFixture() {
        let sp = fixture["signed_preimage"] as! [String: Any]
        let identity = fixture["identity"] as! [String: Any]
        let devA = identity["device_a"] as! [String: Any]
        let pubKeyB64 = devA["pubkey_base64url"] as! String
        let cases = sp["cases"] as! [[String: Any]]

        for c in cases {
            let preimageBytes = SyncIdentity.hexToBytes(c["preimage_hex"] as! String)
            let sigB64 = c["signature_base64url"] as! String
            XCTAssertTrue(SyncSigning.verify(pubKeyB64, preimageBytes, sigB64),
                          "\(c["name"]!): signature should verify")
        }
    }

    // MARK: - Auth verification

    func testAuthVerificationMatchesFixture() {
        let av = fixture["auth_verification"] as! [String: Any]
        let authTokenB64 = av["authTokenBase64url"] as! String
        let expectedRoomId = av["roomId"] as! String
        let authTokenRaw = SyncIdentity.urlB64Decode(authTokenB64)!
        var hasher = SHA256()
        hasher.update(data: Data("tacmap-room-id-v3\0".utf8))
        hasher.update(data: authTokenRaw)
        let roomIdRaw = Data(hasher.finalize())
        XCTAssertEqual(roomIdRaw.base64URLEncodedStringNoPad(), expectedRoomId)
    }

    // MARK: - Replay state

    func testReplayAcceptNewer() {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(state.advance("obj1", VersionStamp.parse("0000000000000003:actorA")!))
        XCTAssertTrue(state.advance("obj1", VersionStamp.parse("0000000000000005:actorA")!))
    }

    func testReplayRejectOlder() {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(state.advance("obj1", VersionStamp.parse("0000000000000005:actorA")!))
        XCTAssertFalse(state.advance("obj1", VersionStamp.parse("0000000000000003:actorA")!))
    }

    func testReplayRejectEqualSameActor() {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(state.advance("obj1", VersionStamp.parse("0000000000000005:actorA")!))
        XCTAssertFalse(state.advance("obj1", VersionStamp.parse("0000000000000005:actorA")!))
    }

    func testReplayAcceptEqualCounterHigherActor() {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(state.advance("obj1", VersionStamp.parse("0000000000000005:actorA")!))
        XCTAssertTrue(state.advance("obj1", VersionStamp.parse("0000000000000005:actorB")!))
    }

    func testReplayTombstonePersists() {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(state.tombstone("obj1", VersionStamp.parse("0000000000000005:actorA")!))
        XCTAssertTrue(state.isTombstoned("obj1"))
        XCTAssertFalse(state.advance("obj1", VersionStamp.parse("0000000000000003:actorB")!))
        XCTAssertTrue(state.advance("obj1", VersionStamp.parse("0000000000000007:actorB")!))
    }

    func testCounterAdvanceWindowEnforced() {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(state.advance("obj1", VersionStamp(counter: 100, actorId: "actorA")))
        XCTAssertTrue(state.advance("obj2", VersionStamp(counter: 10099, actorId: "actorA")))
        XCTAssertFalse(state.advance("obj3", VersionStamp(counter: 20100, actorId: "actorA")))
        XCTAssertTrue(state.advance("obj3", VersionStamp(counter: 20099, actorId: "actorA")))
    }

    func testActorRegistrationRejectsKeySwap() {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(state.registerActor("actorA", pubkey: "pubkey-A"))
        XCTAssertTrue(state.registerActor("actorA", pubkey: "pubkey-A"))
        XCTAssertFalse(state.registerActor("actorA", pubkey: "pubkey-B"))
    }

    func testPresenceCounterEnforced() {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(state.advancePresence("actorA", counter: 1))
        XCTAssertTrue(state.advancePresence("actorA", counter: 5))
        XCTAssertFalse(state.advancePresence("actorA", counter: 3))
        XCTAssertFalse(state.advancePresence("actorA", counter: 5))
        XCTAssertTrue(state.advancePresence("actorA", counter: 6))
    }

    // MARK: - Helpers

    private func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }
}
