import XCTest
import CryptoKit
@testable import TacticalMaps

/// Validates iOS v3 protocol implementation against the shared fixture
/// (testdata/sync_protocol_v3.json). If these pass, iOS interoperates
/// byte-for-byte with the relay and Android.
final class SyncProtocolV3Tests: XCTestCase {

    /// The hosted XCTest process shares the app's real Application Support and
    /// standard defaults. Preserve only the exact artifacts touched by the live
    /// manager test, remove them while it runs, and restore them byte-for-byte.
    /// This keeps the opt-in test from leaving its fixed-key identity/replay data
    /// behind or overwriting a developer's local simulator state.
    private final class HostedAppSyncStateSnapshot {
        private let defaults = UserDefaults.standard
        private let defaultKeys = ["sync.clientId", "sync.deviceSeed", "sync.presenceConfig", "opsec.relayURL"]
        private var savedDefaults: [String: Any] = [:]
        private let files: [URL]
        private let replayDirectory: URL
        private let replayDirectoryExisted: Bool
        private let backupDirectory: URL
        private var movedFiles: [(original: URL, backup: URL)] = []
        private var restored = false

        init(roomId: String) throws {
            let support = try XCTUnwrap(
                FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first)
            replayDirectory = support.appendingPathComponent("sync_replay", isDirectory: true)
            replayDirectoryExisted = FileManager.default.fileExists(atPath: replayDirectory.path)
            files = [
                support.appendingPathComponent("waypoints.json"),
                support.appendingPathComponent("drawings.json"),
                support.appendingPathComponent("sync_model_revisions.json"),
                replayDirectory.appendingPathComponent("\(roomId).json")
            ]
            backupDirectory = FileManager.default.temporaryDirectory
                .appendingPathComponent("tacmap-live-sync-backup-\(UUID().uuidString)", isDirectory: true)

            do {
                try FileManager.default.createDirectory(at: backupDirectory, withIntermediateDirectories: true)
                for key in defaultKeys {
                    if let value = defaults.object(forKey: key) { savedDefaults[key] = value }
                    defaults.removeObject(forKey: key)
                }
                defaults.synchronize()
                for (index, original) in files.enumerated()
                    where FileManager.default.fileExists(atPath: original.path) {
                    let backup = backupDirectory.appendingPathComponent(String(index))
                    try FileManager.default.moveItem(at: original, to: backup)
                    movedFiles.append((original, backup))
                }
            } catch {
                restore()
                throw error
            }
        }

        func restore() {
            guard !restored else { return }
            restored = true
            let fm = FileManager.default
            for file in files where fm.fileExists(atPath: file.path) { try? fm.removeItem(at: file) }
            for entry in movedFiles {
                try? fm.createDirectory(at: entry.original.deletingLastPathComponent(), withIntermediateDirectories: true)
                try? fm.moveItem(at: entry.backup, to: entry.original)
            }
            for key in defaultKeys { defaults.removeObject(forKey: key) }
            for (key, value) in savedDefaults { defaults.set(value, forKey: key) }
            defaults.synchronize()
            if !replayDirectoryExisted,
               (try? fm.contentsOfDirectory(atPath: replayDirectory.path).isEmpty) == true {
                try? fm.removeItem(at: replayDirectory)
            }
            try? fm.removeItem(at: backupDirectory)
        }

        deinit { restore() }
    }

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
        let vs = VersionStamp(counter: 42, actorId: actorA)
        XCTAssertEqual(vs.encode(), "000000000000002a:\(actorA)")
        let parsed = VersionStamp.parse(vs.encode())!
        XCTAssertEqual(vs, parsed)
    }

    func testVersionStampMaxCounter() {
        let vs = VersionStamp(counter: VersionStamp.maxCounter, actorId: actorA)
        XCTAssertEqual(vs.encode(), "7fffffffffffffff:\(actorA)")
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
        XCTAssertTrue(state.advance("obj1", stamp(3, actorA)))
        XCTAssertTrue(state.advance("obj1", stamp(5, actorA)))
    }

    func testReplayRejectOlder() {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(state.advance("obj1", stamp(5, actorA)))
        XCTAssertFalse(state.advance("obj1", stamp(3, actorA)))
    }

    func testReplayRejectEqualSameActor() {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(state.advance("obj1", stamp(5, actorA)))
        XCTAssertFalse(state.advance("obj1", stamp(5, actorA)))
    }

    func testReplayAcceptEqualCounterHigherActor() {
        let state = SyncReplayState(roomId: "test-room")
        let low = min(actorA, actorB), high = max(actorA, actorB)
        XCTAssertTrue(state.advance("obj1", stamp(5, low)))
        XCTAssertTrue(state.advance("obj1", stamp(5, high)))
    }

    func testReplayTombstonePersists() {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(state.tombstone("obj1", stamp(5, actorA)))
        XCTAssertTrue(state.isTombstoned("obj1"))
        XCTAssertFalse(state.advance("obj1", stamp(3, actorB)))
        XCTAssertTrue(state.advance("obj1", stamp(7, actorB)))
        XCTAssertFalse(state.isTombstoned("obj1"))
    }

    func testCounterAdvanceWindowEnforced() {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(state.advance("obj1", stamp(100, actorA)))
        XCTAssertTrue(state.advance("obj2", stamp(10099, actorA)))
        XCTAssertFalse(state.advance("obj3", stamp(20100, actorA)))
        XCTAssertTrue(state.advance("obj3", stamp(20099, actorA)))
    }

    func testActorRegistrationRejectsKeySwap() {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(state.registerActor(actorA, pubkey: pubA))
        XCTAssertTrue(state.registerActor(actorA, pubkey: pubA))
        XCTAssertFalse(state.registerActor(actorA, pubkey: pubB))
    }

    func testPresenceCounterIsBoundToSignedSession() throws {
        let state = SyncReplayState(roomId: "test-room")
        XCTAssertTrue(try state.acceptHello(actorId: actorA, pubkey: pubA, sessionDomain: sessionDomain, epochHex: "0000000000000001"))
        XCTAssertTrue(try state.acceptPresence(actorId: actorA, sessionDomain: sessionDomain, counter: 1))
        XCTAssertTrue(try state.acceptPresence(actorId: actorA, sessionDomain: sessionDomain, counter: 5))
        XCTAssertFalse(try state.acceptPresence(actorId: actorA, sessionDomain: sessionDomain, counter: 3))
        XCTAssertFalse(try state.acceptPresence(actorId: actorA, sessionDomain: sessionDomain, counter: 5))
        let otherSession = Data(repeating: 7, count: 32).base64URLEncodedStringNoPad()
        XCTAssertTrue(try state.acceptHello(actorId: actorA, pubkey: pubA, sessionDomain: otherSession, epochHex: "0000000000000002"))
        XCTAssertTrue(try state.acceptPresence(actorId: actorA, sessionDomain: otherSession, counter: 1))
        XCTAssertFalse(try state.acceptPresence(actorId: actorA, sessionDomain: sessionDomain, counter: 6))
    }

    func testPresenceEnvelopeCarriesExactAwkwardPayloadBytes() throws {
        let payload = SyncManager.PresencePayload(
            lat: -35.281982, lon: 149.131032,
            heading: 12.3456789012345, speed: 0.0000004,
            callsign: "A/1 🛰️", affiliation: "friend",
            echelon: "team", function: "infantry", isHQ: true)
        let signed = try XCTUnwrap(SyncManager.buildPresencePayloadBytes(payload))
        var inner = SyncManager.makePresenceEnvelope(
            payload: payload, signedPayload: signed,
            publicKey: pubA, signature: "test-signature")

        XCTAssertEqual(inner["pv"] as? Int, 1)
        XCTAssertEqual(inner["p"] as? String, signed.base64EncodedString())

        // Simulate the actual sealed-JSON round trip. Even if compatibility
        // fields are independently changed, a v1 receiver must parse the exact
        // authenticated `p` bytes, not reconstruct numbers from those fields.
        inner["lat"] = 0.0
        let wire = try JSONSerialization.data(withJSONObject: inner)
        let decodedObject = try XCTUnwrap(
            JSONSerialization.jsonObject(with: wire) as? [String: Any])
        let decoded = try XCTUnwrap(SyncManager.decodePresenceEnvelope(decodedObject))
        XCTAssertEqual(decoded.signedPayload, signed)
        XCTAssertEqual(decoded.payload, payload)

        var unknownVersion = decodedObject
        unknownVersion["pv"] = 2
        XCTAssertNil(SyncManager.decodePresenceEnvelope(unknownVersion))
        var nonCanonicalBase64 = decodedObject
        nonCanonicalBase64["p"] = signed.base64EncodedString() + "\n"
        XCTAssertNil(SyncManager.decodePresenceEnvelope(nonCanonicalBase64))
    }

    func testExactPresenceEnvelopeVerifiesSharedFixtureWithoutReserializing() throws {
        let signedPreimage = fixture["signed_preimage"] as! [String: Any]
        let value = try XCTUnwrap(
            (signedPreimage["cases"] as! [[String: Any]])
                .first { $0["name"] as? String == "presence_update" })
        let identity = fixture["identity"] as! [String: Any]
        let device = identity["device_a"] as! [String: Any]
        let publicKey = device["pubkey_base64url"] as! String
        let plaintext = Data((value["plaintext"] as! String).utf8)
        let inner: [String: Any] = [
            "pv": 1,
            "p": plaintext.base64EncodedString(),
            "pub": publicKey,
            "sig": value["signature_base64url"] as! String
        ]
        let decoded = try XCTUnwrap(SyncManager.decodePresenceEnvelope(inner))
        XCTAssertEqual(decoded.signedPayload, plaintext)
        XCTAssertEqual(hex(SyncIdentity.sha256(decoded.signedPayload)),
                       value["payload_hash_hex"] as? String)
        XCTAssertNotEqual(SyncManager.buildPresencePayloadBytes(decoded.payload), plaintext,
                          "verification must use embedded bytes, not Foundation reserialization")

        let keys = fixture["key_derivation"] as! [String: Any]
        let roomIdRaw = SyncIdentity.hexToBytes(keys["room_id_raw_hex"] as! String)
        let session = SyncIdentity.hexToBytes(signedPreimage["session_domain_hex"] as! String)
        let actor = value["actor_id"] as! String
        let counter = (value["counter"] as! NSNumber).int64Value
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainPresence, roomIdRaw: roomIdRaw,
            actorId: actor, sessionDomain: session,
            counterHex16: VersionStamp.counterHex16(counter),
            objectId: "", kind: "loc",
            payloadHash: SyncIdentity.sha256(decoded.signedPayload))
        XCTAssertEqual(hex(preimage), value["preimage_hex"] as? String)
        XCTAssertTrue(SyncSigning.verify(
            publicKey, preimage, decoded.signature))
    }

    func testEqualHelloReconnectRestoresSessionAndRejectsPresenceReplay() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("sync-presence-reconnect-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let previousKeyProvider = SafeStore.keyProvider
        let key = Data(repeating: 0x75, count: 32)
        SafeStore.keyProvider = { key }
        SealedMigrationPolicy.resetForTests(key: key)
        defer {
            SafeStore.keyProvider = previousKeyProvider
            try? FileManager.default.removeItem(at: directory)
        }

        let room = "presence-reconnect"
        let first = SyncReplayState(roomId: room, containerURL: directory)
        XCTAssertTrue(first.load())
        XCTAssertTrue(try first.acceptHello(
            actorId: actorA, pubkey: pubA, sessionDomain: sessionDomain,
            epochHex: "0000000000000001"))
        XCTAssertTrue(try first.acceptPresence(
            actorId: actorA, sessionDomain: sessionDomain, counter: 5))

        let restarted = SyncReplayState(roomId: room, containerURL: directory)
        XCTAssertTrue(restarted.load())
        XCTAssertEqual(restarted.activeSessionDomain(actorA), sessionDomain)
        XCTAssertTrue(try restarted.acceptHello(
            actorId: actorA, pubkey: pubA, sessionDomain: sessionDomain,
            epochHex: "0000000000000001"),
            "the relay may replay the current signed hello after this client reconnects")
        XCTAssertFalse(try restarted.acceptPresence(
            actorId: actorA, sessionDomain: sessionDomain, counter: 5))
        XCTAssertTrue(try restarted.acceptPresence(
            actorId: actorA, sessionDomain: sessionDomain, counter: 6))

        let differentSession = Data(repeating: 0x33, count: 32).base64URLEncodedStringNoPad()
        XCTAssertFalse(try restarted.acceptHello(
            actorId: actorA, pubkey: pubA, sessionDomain: differentSession,
            epochHex: "0000000000000001"),
            "an equal epoch must never activate a different session domain")

        let restartedAgain = SyncReplayState(roomId: room, containerURL: directory)
        XCTAssertTrue(restartedAgain.load())
        XCTAssertFalse(try restartedAgain.acceptPresence(
            actorId: actorA, sessionDomain: sessionDomain, counter: 6))
        XCTAssertTrue(try restartedAgain.acceptPresence(
            actorId: actorA, sessionDomain: sessionDomain, counter: 7))
    }

    func testSignedHelloProductionVerifierMatchesFixtureAndRejectsTampering() {
        let hello = (fixture["signed_preimage"] as! [String: Any])["cases"] as! [[String: Any]]
        let value = hello.first { $0["name"] as? String == "hello_announcement" }!
        let roomRaw = SyncIdentity.hexToBytes((fixture["key_derivation"] as! [String: Any])["room_id_raw_hex"] as! String)
        let sig = value["signature_base64url"] as! String
        XCTAssertTrue(SyncIdentity.verifyHello(
            actorId: actorA, publicKey: pubA, sessionDomain: sessionDomain,
            versionStamp: stamp(1, actorA).encode(), signature: sig, roomIdRaw: roomRaw))
        XCTAssertFalse(SyncIdentity.verifyHello(
            actorId: actorB, publicKey: pubA, sessionDomain: sessionDomain,
            versionStamp: stamp(1, actorB).encode(), signature: sig, roomIdRaw: roomRaw))
        let sdRaw = SyncIdentity.urlB64Decode(sessionDomain)!
        let helloVS = stamp(1, actorA).encode()
        XCTAssertTrue(SyncIdentity.helloAckMatches(actorId: actorA, sessionDomain: sdRaw, expectedVersion: helloVS,
                                                   frameActorId: actorA, frameSessionDomain: sessionDomain, frameVersion: helloVS))
        XCTAssertFalse(SyncIdentity.helloAckMatches(actorId: actorA, sessionDomain: sdRaw, expectedVersion: helloVS,
                                                    frameActorId: actorB, frameSessionDomain: sessionDomain, frameVersion: helloVS))
    }

    func testAuthenticatedSnapshotRaisesCounterAndNeverLowersFence() throws {
        let state = SyncReplayState(roomId: "test-room")
        let mutation = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(50_000, actorA), publicKey: pubA,
            kind: .put(contentHash: String(repeating: "a", count: 64)))
        XCTAssertEqual(try state.commitSnapshot([mutation], seq: 10), [.newlyPersisted])
        XCTAssertEqual(state.localCounter, 50_000)
        XCTAssertEqual(try state.reserveNextCounter(), 50_001)
        XCTAssertEqual(try state.commitSnapshot([], seq: 3), [])
        XCTAssertEqual(state.lastSnapshotSeq, 10)
    }

    func testPersistenceFailureRollsBackReservedCounter() throws {
        enum Expected: Error { case unavailable }
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("sync-replay-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        var writes = 0
        let state = SyncReplayState(
            roomId: "persistence-test",
            containerURL: directory,
            persistenceWriter: { _, _, _ in
                writes += 1
                if writes > 1 { throw Expected.unavailable }
            })
        XCTAssertEqual(try state.reserveNextCounter(), 1)
        XCTAssertThrowsError(try state.reserveNextCounter())
        XCTAssertEqual(state.localCounter, 1, "an unsaved counter must not become sendable")
    }

    func testHelloEpochAndCrashRecoveryRequireExactMutationKindAndHash() throws {
        let state = SyncReplayState(roomId: "recovery-test")
        XCTAssertEqual(try state.reserveHelloEpoch(actorId: actorA), "0000000000000001")
        XCTAssertEqual(try state.reserveHelloEpoch(actorId: actorA), "0000000000000002")
        let hash = String(repeating: "a", count: 64)
        let put = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(1, actorA), publicKey: pubA,
            kind: .put(contentHash: hash))
        XCTAssertTrue(try state.commit(put))
        XCTAssertEqual(state.recoverableLocalPut(
            wireObjectId: wireId, actorId: actorA, pubkey: pubA, contentHash: hash), stamp(1, actorA))
        XCTAssertNil(state.recoverableLocalPut(
            wireObjectId: wireId, actorId: actorA, pubkey: pubA, contentHash: String(repeating: "b", count: 64)))
        let deletion = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(2, actorA), publicKey: pubA, kind: .delete)
        XCTAssertTrue(try state.commit(deletion))
        XCTAssertNil(state.recoverableLocalPut(
            wireObjectId: wireId, actorId: actorA, pubkey: pubA, contentHash: hash))
        XCTAssertEqual(state.recoverableLocalDeletes(actorId: actorA, pubkey: pubA).first?.1, stamp(2, actorA))
    }

    func testSnapshotReappliesOnlyExactMutationAfterPersistBeforeModelCrash() throws {
        let state = SyncReplayState(roomId: "snapshot-crash-recovery")
        let putHash = String(repeating: "1", count: 64)
        let put = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(1, actorA), publicKey: pubA,
            kind: .put(contentHash: putHash))

        // Simulate persistence succeeding and the process dying before model apply.
        XCTAssertTrue(try state.commit(put))
        let putResult = try state.commitSnapshot([put], seq: 1)
        XCTAssertEqual(putResult, [.exactAlreadyPersisted])
        XCTAssertTrue(putResult[0].shouldApplyToModel)

        let conflictingHash = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(1, actorA), publicKey: pubA,
            kind: .put(contentHash: String(repeating: "2", count: 64)))
        let conflictingKind = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(1, actorA), publicKey: pubA, kind: .delete)
        XCTAssertEqual(try state.commitSnapshot([conflictingHash], seq: 2), [.conflictOrStale])
        XCTAssertEqual(try state.commitSnapshot([conflictingKind], seq: 3), [.conflictOrStale])

        let delete = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(2, actorA), publicKey: pubA, kind: .delete)
        XCTAssertTrue(try state.commit(delete))
        let deleteResult = try state.commitSnapshot([delete], seq: 4)
        XCTAssertEqual(deleteResult, [.exactAlreadyPersisted])
        XCTAssertTrue(deleteResult[0].shouldApplyToModel)

        let putAtDeleteStamp = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(2, actorA), publicKey: pubA,
            kind: .put(contentHash: putHash))
        XCTAssertEqual(try state.commitSnapshot([putAtDeleteStamp], seq: 5), [.conflictOrStale])
    }

    func testPendingModelMarkerProtectsOfflinePutDeleteAndRecreate() throws {
        let prior = String(repeating: "1", count: 64)
        let incoming = String(repeating: "2", count: 64)
        let offline = String(repeating: "3", count: 64)

        func putState() throws -> (SyncReplayState, SyncReplayState.DurableMutation) {
            let state = SyncReplayState(roomId: "pending-put")
            let mutation = SyncReplayState.DurableMutation(
                wireObjectId: wireId, stamp: stamp(1, actorA), publicKey: pubA,
                kind: .put(contentHash: incoming))
            XCTAssertTrue(try state.commitRemote(.init(mutation: mutation, priorModelHash: prior)))
            return (state, mutation)
        }

        do {
            let (state, mutation) = try putState()
            XCTAssertEqual(state.pendingModelDecision(mutation, currentModelHash: prior), .applyIncoming)
            XCTAssertTrue(try state.clearPendingModelApplication(mutation))
        }
        do {
            let (state, mutation) = try putState()
            XCTAssertEqual(state.pendingModelDecision(mutation, currentModelHash: incoming), .alreadyApplied)
            XCTAssertTrue(try state.clearPendingModelApplication(mutation))
        }
        do {
            let (state, mutation) = try putState()
            XCTAssertEqual(state.pendingModelDecision(mutation, currentModelHash: offline), .localDiverged)
            XCTAssertTrue(try state.clearPendingModelApplication(mutation))
            let local = SyncReplayState.DurableMutation(
                wireObjectId: wireId, stamp: stamp(2, actorA), publicKey: pubA,
                kind: .put(contentHash: offline))
            XCTAssertTrue(try state.commit(local))
            XCTAssertGreaterThan(local.stamp.counter, mutation.stamp.counter)
        }
        do {
            let (state, mutation) = try putState()
            XCTAssertEqual(state.pendingModelDecision(mutation, currentModelHash: nil), .localDiverged)
            XCTAssertTrue(try state.clearPendingModelApplication(mutation))
            let localDelete = SyncReplayState.DurableMutation(
                wireObjectId: wireId, stamp: stamp(2, actorA), publicKey: pubA, kind: .delete)
            XCTAssertTrue(try state.commit(localDelete))
            XCTAssertGreaterThan(localDelete.stamp.counter, mutation.stamp.counter)
        }

        func deleteState() throws -> (SyncReplayState, SyncReplayState.DurableMutation) {
            let state = SyncReplayState(roomId: "pending-delete")
            let mutation = SyncReplayState.DurableMutation(
                wireObjectId: wireId, stamp: stamp(1, actorA), publicKey: pubA, kind: .delete)
            XCTAssertTrue(try state.commitRemote(.init(mutation: mutation, priorModelHash: prior)))
            return (state, mutation)
        }
        do {
            let (state, mutation) = try deleteState()
            XCTAssertEqual(state.pendingModelDecision(mutation, currentModelHash: prior), .applyIncoming)
            XCTAssertTrue(try state.clearPendingModelApplication(mutation))
        }
        do {
            let (state, mutation) = try deleteState()
            XCTAssertEqual(state.pendingModelDecision(mutation, currentModelHash: nil), .alreadyApplied)
            XCTAssertTrue(try state.clearPendingModelApplication(mutation))
        }
        do {
            let (state, mutation) = try deleteState()
            XCTAssertEqual(state.pendingModelDecision(mutation, currentModelHash: offline), .localDiverged)
            XCTAssertTrue(try state.clearPendingModelApplication(mutation))
            let recreated = SyncReplayState.DurableMutation(
                wireObjectId: wireId, stamp: stamp(2, actorA), publicKey: pubA,
                kind: .put(contentHash: offline))
            XCTAssertTrue(try state.commit(recreated))
            XCTAssertGreaterThan(recreated.stamp.counter, mutation.stamp.counter)
        }
    }

    func testPendingModelMarkerSeparatesSenderPayloadHashFromReceiverModelHash() throws {
        let senderPayloadHash = String(repeating: "41", count: 32)
        let receiverModelHash = String(repeating: "42", count: 32)
        let mutation = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(1, actorA), publicKey: pubA,
            kind: .put(contentHash: senderPayloadHash))
        let state = SyncReplayState(roomId: "cross-export-hash")
        XCTAssertTrue(try state.commitRemote(.init(
            mutation: mutation, priorModelHash: nil, expectedModelHash: receiverModelHash)))
        XCTAssertEqual(state.pendingModelDecision(
            mutation, currentModelHash: receiverModelHash), .alreadyApplied)
        XCTAssertEqual(state.pendingModelDecision(
            mutation, currentModelHash: senderPayloadHash), .localDiverged)
    }

    func testSealedReplayReloadPreservesDistinctReceiverModelHash() throws {
        let rawHash = String(repeating: "51", count: 32)
        let expectedHash = String(repeating: "52", count: 32)
        let mutation = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(1, actorA), publicKey: pubA,
            kind: .put(contentHash: rawHash))
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("sync-replay-expected-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let previousKeyProvider = SafeStore.keyProvider
        let key = Data(repeating: 0x73, count: 32)
        SafeStore.keyProvider = { key }
        SealedMigrationPolicy.resetForTests(key: key)
        defer {
            SafeStore.keyProvider = previousKeyProvider
            try? FileManager.default.removeItem(at: dir)
        }
        let state = SyncReplayState(roomId: "sealed-expected", containerURL: dir)
        XCTAssertTrue(state.load())
        XCTAssertTrue(try state.commitRemote(.init(
            mutation: mutation, priorModelHash: nil, expectedModelHash: expectedHash)))
        let file = dir.appendingPathComponent("sync_replay/sealed-expected.json")
        XCTAssertTrue(SealedEnvelope.isSealedFile(try Data(contentsOf: file)))

        let reloaded = SyncReplayState(roomId: "sealed-expected", containerURL: dir)
        XCTAssertTrue(reloaded.load())
        XCTAssertEqual(reloaded.pendingModelDecision(
            mutation, currentModelHash: expectedHash), .alreadyApplied)
        XCTAssertEqual(reloaded.pendingModelDecision(
            mutation, currentModelHash: rawHash), .localDiverged)
    }

    func testLegacySealedPendingWithoutExpectedHashFallsBackToRawHash() throws {
        let rawHash = String(repeating: "61", count: 32)
        let expectedHash = String(repeating: "62", count: 32)
        let mutation = SyncReplayState.DurableMutation(
            wireObjectId: wireId, stamp: stamp(1, actorA), publicKey: pubA,
            kind: .put(contentHash: rawHash))
        let room = "legacy-pending"
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("sync-replay-legacy-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let previousKeyProvider = SafeStore.keyProvider
        let key = Data(repeating: 0x74, count: 32)
        SafeStore.keyProvider = { key }
        SealedMigrationPolicy.resetForTests(key: key)
        defer {
            SafeStore.keyProvider = previousKeyProvider
            try? FileManager.default.removeItem(at: dir)
        }
        let state = SyncReplayState(roomId: room, containerURL: dir)
        XCTAssertTrue(state.load())
        XCTAssertTrue(try state.commitRemote(.init(
            mutation: mutation, priorModelHash: nil, expectedModelHash: expectedHash)))
        let file = dir.appendingPathComponent("sync_replay/\(room).json")
        let label = "sync/room/\(room)"
        let sealed = try Data(contentsOf: file)
        let plain = try XCTUnwrap(SealedEnvelope.openFile(key: key, blob: sealed, label: label))
        var root = try XCTUnwrap(JSONSerialization.jsonObject(with: plain) as? [String: Any])
        var pending = try XCTUnwrap(root["pendingModelApplications"] as? [String: [String: Any]])
        var record = try XCTUnwrap(pending[wireId])
        record.removeValue(forKey: "expectedHash")
        pending[wireId] = record
        root["pendingModelApplications"] = pending
        try SafeStore.write(
            JSONSerialization.data(withJSONObject: root, options: [.sortedKeys]),
            to: file, label: label)

        let reloaded = SyncReplayState(roomId: room, containerURL: dir)
        XCTAssertTrue(reloaded.load())
        XCTAssertEqual(reloaded.pendingModelDecision(
            mutation, currentModelHash: rawHash), .alreadyApplied)
        XCTAssertEqual(reloaded.pendingModelDecision(
            mutation, currentModelHash: expectedHash), .localDiverged)
    }

    func testGlobalGenerationClosesAbaAcrossLeaveAndRestart() throws {
        let localId = "71d0f3d2-7d33-4af4-a593-d4cb70fb808d"
        let prior = String(repeating: "4", count: 64)
        let incoming = String(repeating: "5", count: 64)
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("model-generation-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let key = Data(repeating: 0x6a, count: 32)
        let journal = LocalModelRevisionJournal(containerURL: directory, testKey: key)
        XCTAssertTrue(journal.load())

        func pending() throws -> (SyncReplayState, SyncReplayState.DurableMutation) {
            let state = SyncReplayState(roomId: "aba-room")
            let mutation = SyncReplayState.DurableMutation(
                wireObjectId: wireId, stamp: stamp(1, actorA), publicKey: pubA,
                kind: .put(contentHash: incoming))
            XCTAssertTrue(try state.commitRemote(.init(
                mutation: mutation, priorModelHash: prior, localModelId: localId,
                acceptedGeneration: journal.generation(localId))))
            return (state, mutation)
        }

        do {
            let (state, mutation) = try pending()
            XCTAssertEqual(state.pendingModelDecision(
                mutation, currentModelHash: prior, currentGeneration: journal.generation(localId)), .applyIncoming)
            XCTAssertEqual(state.pendingModelDecision(
                mutation, currentModelHash: incoming, currentGeneration: journal.generation(localId)), .alreadyApplied)
        }

        // Journal survives independently of replay/room lifetime: Leave/restart,
        // then an offline delete of the applied remote PUT cannot look like prior nil.
        do {
            let (state, mutation) = try pending()
            try journal.bump(localId)
            XCTAssertEqual(state.pendingModelDecision(
                mutation, currentModelHash: nil, currentGeneration: journal.generation(localId)), .localDiverged)
        }

        do {
            let (state, mutation) = try pending()
            let accepted = journal.generation(localId)
            try journal.bump(localId); try journal.bump(localId) // edit then exact revert
            XCTAssertEqual(state.pendingModelDecision(
                mutation, currentModelHash: prior, currentGeneration: journal.generation(localId)), .localDiverged)
            XCTAssertGreaterThan(journal.generation(localId), accepted)
        }

        do {
            let (state, mutation) = try pending()
            try journal.bump(localId); try journal.bump(localId) // recreate/delete cycle
            XCTAssertEqual(state.pendingModelDecision(
                mutation, currentModelHash: incoming, currentGeneration: journal.generation(localId)), .alreadyApplied)
        }

        let restarted = LocalModelRevisionJournal(containerURL: directory, testKey: key)
        XCTAssertTrue(restarted.load())
        XCTAssertEqual(restarted.generation(localId), journal.generation(localId),
                       "sealed generation must survive Leave/process restart")
    }

    @MainActor func testCallsignBoundUsesUnicodeScalars() {
        let manager = SyncManager()
        let value = String(repeating: "🛰️", count: 40)
        XCTAssertEqual(manager.boundedCallsign(value).unicodeScalars.count, 64)
    }

    func testProductionJSONIntegerParserAcceptsFreshSnapshotSequence() throws {
        func decoded(_ json: String) throws -> Any? {
            let data = try XCTUnwrap(json.data(using: .utf8))
            let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
            return object["seq"]
        }

        // Foundation's NSNumber bridge makes numeric 0 and 1 satisfy `is Bool`.
        // Exercise the exact JSON-decoded values used by SyncManager rather than
        // constructing convenient Swift Ints that would miss the live regression.
        XCTAssertEqual(SyncManager.strictJSONInteger(try decoded("{\"seq\":0}"), minimum: 0, maximum: Int64.max), 0)
        XCTAssertEqual(SyncManager.strictJSONInteger(try decoded("{\"seq\":1}"), minimum: 0, maximum: Int64.max), 1)
        XCTAssertEqual(SyncManager.strictJSONInteger(try decoded("{\"seq\":2}"), minimum: 0, maximum: Int64.max), 2)
        XCTAssertEqual(SyncManager.strictJSONInteger(try decoded("{\"seq\":9223372036854775807}"), minimum: 0, maximum: Int64.max), Int64.max)
        XCTAssertEqual(SyncManager.strictJSONInteger(try decoded("{\"seq\":-9223372036854775808}"), minimum: Int64.min, maximum: Int64.max), Int64.min)
        XCTAssertNil(SyncManager.strictJSONInteger(try decoded("{\"seq\":false}"), minimum: 0, maximum: Int64.max))
        XCTAssertNil(SyncManager.strictJSONInteger(try decoded("{\"seq\":1.0}"), minimum: 0, maximum: Int64.max))
        XCTAssertNil(SyncManager.strictJSONInteger(try decoded("{\"seq\":999999999999.000001}"), minimum: 0, maximum: Int64.max))
        XCTAssertNil(SyncManager.strictJSONInteger(try decoded("{\"seq\":1.5}"), minimum: 0, maximum: Int64.max))
        XCTAssertNil(SyncManager.strictJSONInteger(try decoded("{\"seq\":-1}"), minimum: 0, maximum: Int64.max))
        XCTAssertNil(SyncManager.strictJSONInteger(try decoded("{\"seq\":18446744073709551615}"), minimum: Int64.min, maximum: Int64.max))
    }

    @MainActor func testLockedSigningKeyRefusesJoinWithoutCreatingIdentity() throws {
        let joinCode = "3:identity-locked-regression-20260712"
        let roomId = SyncCrypto.deriveRoomV3(String(joinCode.dropFirst(2))).roomId
        let hostedState = try HostedAppSyncStateSnapshot(roomId: roomId)
        let previousKeyProvider = SafeStore.keyProvider
        let manager = SyncManager()
        defer {
            manager.leave()
            SafeStore.keyProvider = previousKeyProvider
            hostedState.restore()
        }

        SafeStore.keyProvider = { throw DataKey.LockedError() }
        manager.join(joinCode)

        XCTAssertNil(manager.room)
        XCTAssertEqual(manager.lastError,
                       "Signing identity is locked or unavailable. Unlock the device and try again.")
        XCTAssertNil(UserDefaults.standard.data(forKey: "sync.deviceSeed"),
                     "a locked key must not create identity material it cannot protect")
    }

    @MainActor func testCorruptSigningSeedRefusesJoinWithoutSilentRotation() throws {
        let joinCode = "3:identity-corrupt-regression-20260712"
        let roomId = SyncCrypto.deriveRoomV3(String(joinCode.dropFirst(2))).roomId
        let hostedState = try HostedAppSyncStateSnapshot(roomId: roomId)
        let previousKeyProvider = SafeStore.keyProvider
        let testKey = Data(repeating: 0x6b, count: 32)
        let invalidSeed = Data(repeating: 0x41, count: 31)
        let corruptBlob = try SealedEnvelope.sealFile(
            key: testKey, plaintext: invalidSeed, label: "sync/deviceSeed")
        let manager = SyncManager()
        defer {
            manager.leave()
            SafeStore.keyProvider = previousKeyProvider
            hostedState.restore()
        }

        SafeStore.keyProvider = { testKey }
        UserDefaults.standard.set(corruptBlob, forKey: "sync.deviceSeed")
        XCTAssertTrue(UserDefaults.standard.synchronize())
        manager.join(joinCode)

        XCTAssertNil(manager.room)
        XCTAssertEqual(manager.lastError,
                       "Signing identity is locked or unavailable. Unlock the device and try again.")
        XCTAssertEqual(UserDefaults.standard.data(forKey: "sync.deviceSeed"), corruptBlob,
                       "invalid identity material must remain untouched rather than rotate silently")
    }

    @MainActor func testSigningSeedPersistsAndReloadsWithoutRotation() throws {
        let joinCode = "3:identity-reload-regression-20260712"
        let roomId = SyncCrypto.deriveRoomV3(String(joinCode.dropFirst(2))).roomId
        let hostedState = try HostedAppSyncStateSnapshot(roomId: roomId)
        let previousKeyProvider = SafeStore.keyProvider
        let previousRelay = OpsecSettings.shared.relayURL
        let testKey = Data(repeating: 0x7c, count: 32)
        var managers: [SyncManager] = []
        defer {
            managers.forEach { $0.leave() }
            SafeStore.keyProvider = previousKeyProvider
            OpsecSettings.shared.relayURL = previousRelay
            hostedState.restore()
        }

        SafeStore.keyProvider = { testKey }
        SealedMigrationPolicy.resetForTests(key: testKey)
        OpsecSettings.shared.relayURL = "ws://127.0.0.1:9"

        let first = SyncManager()
        managers.append(first)
        first.configure(waypointStore: WaypointStore(), drawingStore: DrawingStore())
        first.join(joinCode)
        XCTAssertEqual(first.room, joinCode)
        let persisted = try XCTUnwrap(UserDefaults.standard.data(forKey: "sync.deviceSeed"))
        let seed = try XCTUnwrap(SealedEnvelope.openFile(
            key: testKey, blob: persisted, label: "sync/deviceSeed"))
        XCTAssertEqual(seed.count, 32)
        first.leave()

        // A new manager models process-level cache loss. It must reopen the
        // existing seed and retain the same durable actor identity.
        let second = SyncManager()
        managers.append(second)
        second.configure(waypointStore: WaypointStore(), drawingStore: DrawingStore())
        second.join(joinCode)
        XCTAssertEqual(second.room, joinCode)
        XCTAssertEqual(UserDefaults.standard.data(forKey: "sync.deviceSeed"), persisted)
        second.leave()
    }

    /// Opt-in integration regression for the complete production manager path.
    /// Run with TACMAP_LIVE_RELAY=ws://127.0.0.1:8791 and
    /// TACMAP_LIVE_JOIN_CODE=3:<code> while a local Wrangler relay is active.
    @MainActor func testLiveProductionHandshakeWhenRelayIsProvided() async throws {
        let environment = ProcessInfo.processInfo.environment
        guard let relay = environment["TACMAP_LIVE_RELAY"],
              let joinCode = environment["TACMAP_LIVE_JOIN_CODE"] else {
            throw XCTSkip("Set TACMAP_LIVE_RELAY and TACMAP_LIVE_JOIN_CODE to run the live handshake regression.")
        }
        guard joinCode.hasPrefix("3:") else {
            XCTFail("The live production regression requires a v3 join code.")
            return
        }
        let roomId = SyncCrypto.deriveRoomV3(String(joinCode.dropFirst(2))).roomId
        let hostedState = try HostedAppSyncStateSnapshot(roomId: roomId)

        let previousRelay = OpsecSettings.shared.relayURL
        let previousKeyProvider = SafeStore.keyProvider
        OpsecSettings.shared.relayURL = relay
        // The unsigned unit-test host has no Keychain access-group entitlement;
        // substitute only the at-rest key source. The manager, WebSocket,
        // snapshot parser, signed hello, replay state and relay ack remain the
        // production implementations under test.
        let liveTestKey = Data(repeating: 0x5a, count: 32)
        SafeStore.keyProvider = { liveTestKey }
        SealedMigrationPolicy.resetForTests(key: liveTestKey)
        let waypointStore = WaypointStore()
        let manager = SyncManager()
        manager.configure(waypointStore: waypointStore, drawingStore: DrawingStore())
        defer {
            manager.leave()
            SafeStore.keyProvider = previousKeyProvider
            OpsecSettings.shared.relayURL = previousRelay
            hostedState.restore()
        }

        manager.join(joinCode)
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline {
            if case .connected = manager.status {
                if environment["TACMAP_LIVE_EMIT_OBJECT"] == "1" {
                    waypointStore.add(Waypoint(
                        name: "iOS live interop marker", notes: "TACMAP-IOS-ANDROID-INTEROP",
                        latitude: -33.8688, longitude: 151.2093))
                    try await Task.sleep(nanoseconds: 1_000_000_000)
                }
                return
            }
            if manager.lastError != nil { break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        XCTFail("Production v3 handshake did not connect: \(manager.lastError ?? "no protocol error")")
    }

    // MARK: - Helpers

    private func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    private var identity: [String: Any] { fixture["identity"] as! [String: Any] }
    private var deviceA: [String: Any] { identity["device_a"] as! [String: Any] }
    private var deviceB: [String: Any] { identity["device_b"] as! [String: Any] }
    private var actorA: String { deviceA["actor_id"] as! String }
    private var actorB: String { deviceB["actor_id"] as! String }
    private var pubA: String { deviceA["pubkey_base64url"] as! String }
    private var pubB: String { deviceB["pubkey_base64url"] as! String }
    private var sessionDomain: String {
        let hex = (fixture["signed_preimage"] as! [String: Any])["session_domain_hex"] as! String
        return SyncIdentity.hexToBytes(hex).base64URLEncodedStringNoPad()
    }
    private var wireId: String {
        let cases = (fixture["wire_object_ids"] as! [String: Any])["cases"] as! [[String: Any]]
        return cases[0]["wire_object_id"] as! String
    }
    private func stamp(_ counter: Int64, _ actor: String) -> VersionStamp {
        VersionStamp(counter: counter, actorId: actor)
    }
}
