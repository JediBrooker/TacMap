import XCTest
@testable import TacticalMaps

final class PresenceReliabilityTests: XCTestCase {
    private lazy var fixture: [String: Any] = loadFixture("presence_accuracy_v3.json")

    func testReconnectBackoffIsExponentialBoundedAndHandshakeResettable() {
        var policy = SyncReconnectBackoff()
        XCTAssertEqual((0..<6).map { _ in policy.nextDelay(randomUnit: 0.5) },
                       [1, 2, 4, 8, 16, 30])
        XCTAssertEqual(policy.attemptCount, 6)
        policy.reset()
        XCTAssertEqual(policy.nextDelay(randomUnit: 0.5), 1)
    }

    func testNetworkFlapRetainsThenExpiresVisiblyStalePeer() {
        let peer = makePeer(receivedAtUptime: 1, session: "old", retention: 65 * 60)
        let stale = PresenceExpiryPolicy.markedStale(peer, nowUptime: 50)
        XCTAssertTrue(stale.isStale)
        XCTAssertEqual(stale.reconnectGraceStartedAtUptime, 50)
        XCTAssertTrue(PresenceExpiryPolicy.shouldRetain(stale, activeSession: nil, nowUptime: 169.999))
        XCTAssertFalse(PresenceExpiryPolicy.shouldRetain(stale, activeSession: nil, nowUptime: 170.001))
        let retryFailure = PresenceExpiryPolicy.markedStale(stale, nowUptime: 100)
        XCTAssertEqual(retryFailure.reconnectGraceStartedAtUptime, 50)
    }

    func testBackgroundToForegroundSessionRotationDoesNotBlink() {
        let peer = makePeer(receivedAtUptime: 1, session: "background", retention: 60 * 60)
        let background = V3ActiveSession(publicKey: "pub", sessionDomain: "background")
        XCTAssertTrue(PresenceExpiryPolicy.shouldRetain(
            peer, activeSession: background, nowUptime: 30 * 60))
        let rotating = PresenceExpiryPolicy.markedStale(peer, nowUptime: 30 * 60)
        let foreground = V3ActiveSession(publicKey: "pub", sessionDomain: "foreground")
        XCTAssertTrue(PresenceExpiryPolicy.shouldRetain(
            rotating, activeSession: foreground, nowUptime: 31 * 60))

        let ageStale = PresenceExpiryPolicy.withFreshness(peer, nowUptime: 60)
        let disconnected = PresenceExpiryPolicy.markedStale(ageStale, nowUptime: 30 * 60)
        XCTAssertEqual(disconnected.staleSinceUptime, 46)
        XCTAssertEqual(disconnected.reconnectGraceStartedAtUptime, 30 * 60)
        XCTAssertTrue(PresenceExpiryPolicy.shouldRetain(
            disconnected, activeSession: nil, nowUptime: 31 * 60))
        XCTAssertFalse(PresenceExpiryPolicy.shouldRetain(
            disconnected, activeSession: nil, nowUptime: (32 * 60) + 0.001))

        let restored = PresenceExpiryPolicy.transportRestored(
            rotating, nowUptime: (30 * 60) + 1)
        XCTAssertNil(restored.reconnectGraceStartedAtUptime)
        XCTAssertTrue(restored.isStale) // the coordinate is still older than the live window
    }

    func testQualityRejectsBadAccuracyAndImplausibleJumpButAllowsRapidMovement() {
        let old = PresenceLocationFix(
            latitude: 0, longitude: 0, horizontalAccuracyMetres: 10, monotonicTime: 1)
        XCTAssertFalse(PresenceLocationQuality.acceptsJump(
            previous: old,
            candidate: .init(latitude: 1, longitude: 1, horizontalAccuracyMetres: 10, monotonicTime: 6),
            allowSimulatorTeleport: false))
        XCTAssertTrue(PresenceLocationQuality.acceptsJump(
            previous: old,
            candidate: .init(latitude: 0.005, longitude: 0, horizontalAccuracyMetres: 10, monotonicTime: 3),
            allowSimulatorTeleport: false))
        XCTAssertFalse(PresenceLocationQuality.acceptsJump(
            previous: old,
            candidate: .init(latitude: 0, longitude: 0, horizontalAccuracyMetres: 5_000, monotonicTime: 2),
            allowSimulatorTeleport: false))
        XCTAssertTrue(PresenceLocationQuality.acceptsJump(
            previous: old,
            candidate: .init(latitude: 40, longitude: 120, horizontalAccuracyMetres: 10, monotonicTime: 2),
            allowSimulatorTeleport: true))
    }

    func testThreeConsistentFixesRecoverFromAPoisonedFirstFixWithoutMovingOldMarkerEarly() {
        let poisoned = PresenceLocationFix(
            latitude: 0, longitude: 0, horizontalAccuracyMetres: 10, monotonicTime: 1)
        var cluster: PresenceCandidateCluster?
        let realFixes = [
            PresenceLocationFix(latitude: 1, longitude: 1, horizontalAccuracyMetres: 8, monotonicTime: 2),
            PresenceLocationFix(latitude: 1.00005, longitude: 1.00005, horizontalAccuracyMetres: 8, monotonicTime: 3),
            PresenceLocationFix(latitude: 1.00010, longitude: 1.00010, horizontalAccuracyMetres: 8, monotonicTime: 4),
        ]
        for (index, candidate) in realFixes.enumerated() {
            let result = PresenceLocationQuality.evaluateJump(
                previous: poisoned,
                candidate: candidate,
                existingCluster: cluster,
                allowSimulatorTeleport: false
            )
            if index < 2 {
                XCTAssertFalse(result.accepted)
                XCTAssertEqual(poisoned.latitude, 0)
                XCTAssertEqual(poisoned.longitude, 0)
            } else {
                XCTAssertTrue(result.accepted)
                XCTAssertTrue(result.recoveredFromOutlier)
            }
            cluster = result.nextCluster
        }
    }

    func testLegacyWireBoundsRejectInvalidCoordinatesAndSpeed() {
        XCTAssertEqual(PresenceLocationQuality.strictWireDouble(1), 1)
        XCTAssertEqual(PresenceLocationQuality.strictWireDouble(1.5), 1.5)
        XCTAssertNil(PresenceLocationQuality.strictWireDouble(true))
        XCTAssertNil(PresenceLocationQuality.strictWireDouble("1"))
        XCTAssertNil(PresenceLocationQuality.strictWireDouble(Double.nan))
        XCTAssertTrue(PresenceLocationQuality.hasValidWireValues(
            latitude: -35, longitude: 149, heading: 359.9, speed: 250))
        XCTAssertFalse(PresenceLocationQuality.hasValidWireValues(
            latitude: 91, longitude: 149, heading: 0, speed: 0))
        XCTAssertFalse(PresenceLocationQuality.hasValidWireValues(
            latitude: -35, longitude: 181, heading: 0, speed: 0))
        XCTAssertFalse(PresenceLocationQuality.hasValidWireValues(
            latitude: -35, longitude: 149, heading: 0, speed: -1))
        XCTAssertFalse(PresenceLocationQuality.hasValidWireValues(
            latitude: -35, longitude: 149, heading: 0, speed: 1_001))
        XCTAssertTrue(PresenceLocationQuality.isPlausibleLegacyTimestamp(
            130_000, nowMilliseconds: 100_000))
        XCTAssertFalse(PresenceLocationQuality.isPlausibleLegacyTimestamp(
            130_001, nowMilliseconds: 100_000))
    }

    func testSignedAccuracyMatchesCrossPlatformFixture() throws {
        let accuracy = (fixture["horizontal_accuracy_metres"] as! NSNumber).doubleValue
        let payload = try XCTUnwrap(PresenceAccuracyAdvertisement.encodePayload(
            horizontalAccuracyMetres: accuracy))
        XCTAssertEqual(payload.base64EncodedString(), fixture["payload_base64"] as? String)
        XCTAssertEqual(hex(SyncIdentity.sha256(payload)), fixture["payload_hash_hex"] as? String)
        let counter = (fixture["counter"] as! NSNumber).int64Value
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainPresence,
            roomIdRaw: SyncIdentity.hexToBytes(fixture["room_id_raw_hex"] as! String),
            actorId: fixture["actor_id"] as! String,
            sessionDomain: SyncIdentity.hexToBytes(fixture["session_domain_hex"] as! String),
            counterHex16: VersionStamp.counterHex16(counter),
            objectId: "",
            kind: fixture["signature_kind"] as! String,
            payloadHash: SyncIdentity.sha256(payload)
        )
        XCTAssertEqual(hex(preimage), fixture["preimage_hex"] as? String)
        let publicKey = fixture["public_key_base64url"] as! String
        // CryptoKit uses a randomized hedged Ed25519 nonce. Verify both the
        // deterministic Android fixture and a captured iOS signature instead
        // of incorrectly requiring identical signature bytes.
        XCTAssertTrue(SyncSigning.verify(
            publicKey, preimage, fixture["signature_base64url"] as! String))
        XCTAssertTrue(SyncSigning.verify(
            publicKey, preimage, fixture["ios_signature_base64url"] as! String))
        let generated = try XCTUnwrap(SyncSigning.sign(
            SyncIdentity.hexToBytes(fixture["seed_hex"] as! String), preimage))
        XCTAssertTrue(SyncSigning.verify(publicKey, preimage, generated))
    }

    func testOptionalAccuracyExtensionLeavesOldExactPayloadDecodable() throws {
        let payload = SyncManager.PresencePayload(
            lat: -35, lon: 149, heading: 90, speed: 1.5,
            callsign: "11A", affiliation: "friend", echelon: "team",
            function: "infantry", isHQ: false)
        let exact = try XCTUnwrap(SyncManager.buildPresencePayloadBytes(payload))
        var envelope = SyncManager.makePresenceEnvelope(
            payload: payload,
            signedPayload: exact,
            publicKey: fixture["public_key_base64url"] as! String,
            signature: "old-payload-signature"
        )
        envelope[PresenceAccuracyAdvertisement.versionField] =
            PresenceAccuracyAdvertisement.envelopeVersion
        envelope[PresenceAccuracyAdvertisement.payloadField] = fixture["payload_base64"]
        envelope[PresenceAccuracyAdvertisement.signatureField] = fixture["signature_base64url"]

        let decoded = try XCTUnwrap(SyncManager.decodePresenceEnvelope(envelope))
        XCTAssertEqual(decoded.payload, payload)
        XCTAssertEqual(decoded.signedPayload, exact)
        guard case .valid = PresenceAccuracyAdvertisement.decode(from: envelope) else {
            return XCTFail("updated decoder rejected the optional extension")
        }
    }

    func testOptionalAccuracyRejectsEveryPartialOrNonCanonicalPermutation() {
        let version = PresenceAccuracyAdvertisement.versionField
        let payload = PresenceAccuracyAdvertisement.payloadField
        let signature = PresenceAccuracyAdvertisement.signatureField
        for partial: [String: Any] in [
            [version: 1],
            [payload: fixture["payload_base64"]!],
            [signature: "signature"],
        ] {
            guard case .invalid = PresenceAccuracyAdvertisement.decode(from: partial) else {
                return XCTFail("partial accuracy extension was accepted")
            }
        }
        func envelope(_ versionValue: Any = 1,
                      payloadValue: String? = nil,
                      signatureValue: Any = "signature") -> [String: Any] {
            [
                version: versionValue,
                payload: payloadValue ?? (fixture["payload_base64"] as! String),
                signature: signatureValue,
            ]
        }
        let malformed = [
            envelope(true),
            envelope(1.5),
            envelope(payloadValue: "not/base64"),
            envelope(payloadValue: Data(repeating: 0, count: 7).base64EncodedString()),
            envelope(signatureValue: ""),
            envelope(signatureValue: 7),
        ]
        for candidate in malformed {
            guard case .invalid = PresenceAccuracyAdvertisement.decode(from: candidate) else {
                return XCTFail("malformed accuracy extension was accepted")
            }
        }
        for invalid in [Double.nan, Double.infinity, 0, 1_001] {
            var bits = invalid.bitPattern.littleEndian
            let bytes = Data(bytes: &bits, count: MemoryLayout<UInt64>.size)
            guard case .invalid = PresenceAccuracyAdvertisement.decode(
                from: envelope(payloadValue: bytes.base64EncodedString())
            ) else { return XCTFail("invalid accuracy value was accepted") }
        }
    }

    func testStaleMarkerPresentationAndAppearanceChangesAreExplicit() {
        var peer = makePeer(receivedAtUptime: 1, session: "session", retention: 120)
        let original = presenceMarkerAppearance(peer, nowUptime: 10)
        XCTAssertEqual(original.presentation.visibleLabel, "11A")

        peer.callsign = "11B"
        XCTAssertNotEqual(original, presenceMarkerAppearance(peer, nowUptime: 10))
        let callsignAppearance = presenceMarkerAppearance(peer, nowUptime: 10)
        peer.affiliation = "hostile"
        XCTAssertNotEqual(callsignAppearance, presenceMarkerAppearance(peer, nowUptime: 10))

        peer.reconnectGraceStartedAtUptime = 2
        let stale = presenceMarkerAppearance(peer, nowUptime: 121)
        XCTAssertEqual(stale.presentation.visibleLabel, "11B · Last known 2m")
        XCTAssertTrue(stale.presentation.accessibilityLabel.contains(
            "Last known 2 minutes ago"))
        XCTAssertNotEqual(callsignAppearance, stale)
    }

    func testExplicitLeaveProofIsBoundToTheExactHelloSession() throws {
        let protocolFixture = loadFixture("sync_protocol_v3.json")
        let signed = protocolFixture["signed_preimage"] as! [String: Any]
        let identities = protocolFixture["identity"] as! [String: Any]
        let device = identities["device_a"] as! [String: Any]
        let keys = protocolFixture["key_derivation"] as! [String: Any]
        let actor = device["actor_id"] as! String
        let room = SyncIdentity.hexToBytes(keys["room_id_raw_hex"] as! String)
        let session = SyncIdentity.hexToBytes(signed["session_domain_hex"] as! String)
        let version = "0000000000000001:\(actor)"
        let preimage = try XCTUnwrap(SyncIdentity.explicitLeavePreimage(
            roomIdRaw: room,
            actorId: actor,
            sessionDomain: session,
            helloVersion: version
        ))
        let seed = SyncIdentity.hexToBytes(device["seed_hex"] as! String)
        let signature = try XCTUnwrap(SyncSigning.sign(seed, preimage))
        XCTAssertTrue(SyncSigning.verify(
            device["pubkey_base64url"] as! String,
            preimage,
            signature
        ))
        var otherSession = session
        otherSession[otherSession.startIndex] ^= 1
        let otherPreimage = try XCTUnwrap(SyncIdentity.explicitLeavePreimage(
            roomIdRaw: room,
            actorId: actor,
            sessionDomain: otherSession,
            helloVersion: version
        ))
        XCTAssertFalse(SyncSigning.verify(
            device["pubkey_base64url"] as! String,
            otherPreimage,
            signature
        ))
    }

    private func makePeer(
        receivedAtUptime: TimeInterval,
        session: String?,
        retention: TimeInterval
    ) -> PresencePeer {
        PresencePeer(
            clientId: "peer", callsign: "11A", affiliation: "friend",
            echelon: "team", function: "infantry", isHQ: false,
            lat: -35, lon: 149, heading: 0, speed: 0, ts: 1,
            sessionDomain: session, retentionWindow: retention,
            receivedAtUptime: receivedAtUptime
        )
    }

    private func loadFixture(_ name: String) -> [String: Any] {
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = directory.appendingPathComponent("testdata/\(name)")
            if let data = try? Data(contentsOf: candidate),
               let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                return object
            }
            directory.deleteLastPathComponent()
        }
        XCTFail("Could not locate testdata/\(name)")
        return [:]
    }

    private func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }
}
