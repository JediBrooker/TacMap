import XCTest
import CryptoKit
@testable import TacticalMaps

final class TacMapChatCryptoTests: XCTestCase {
    private var fixture: [String: Any]!

    override func setUpWithError() throws {
        fixture = try loadFixture()
    }

    func testSharedChatKeyAdvertisementAndRoomSubkey() throws {
        let room = fixture["room"] as! [String: Any]
        let sessions = fixture["sessions"] as! [String: Any]
        let actorA = sessions["a"] as! [String: Any]
        let chatKeyVector = (fixture["chat_keys"] as! [String: Any])["a"] as! [String: Any]
        let expectedFrame = chatKeyVector["frame"] as! [String: Any]

        let localA = try makeSession(actorA, room: room)
        XCTAssertEqual(localA.actorId, expectedFrame["by"] as? String)
        XCTAssertEqual(localA.sessionDomain, expectedFrame["sd"] as? String)
        XCTAssertEqual(localA.keyExchange, expectedFrame["kx"] as? String)
        XCTAssertEqual(localA.keyId, expectedFrame["kid"] as? String)
        // CryptoKit may hedge Ed25519 signing with randomness, so compare the
        // signed bytes and verify the result instead of requiring identical
        // signature bytes from two valid implementations.
        let preimage = data(hex: chatKeyVector["preimage_hex"] as! String)
        let signingPublicKey = SyncSigning.publicKey(
            data(hex: actorA["ed25519_seed_hex"] as! String)
        )!
        XCTAssertTrue(SyncSigning.verify(signingPublicKey, preimage, localA.signature))

        let roomKey = SymmetricKey(data: data(hex: room["room_key_hex"] as! String))
        let derived = TacMapChatCrypto.roomChatKey(roomKey)
        XCTAssertEqual(
            derived.withUnsafeBytes { Data($0) },
            data(hex: room["room_chat_key_hex"] as! String)
        )
    }

    func testSharedRoomAndDirectFramesVerifyBeforeDecryptAndOpen() throws {
        let room = fixture["room"] as! [String: Any]
        let sessions = fixture["sessions"] as! [String: Any]
        let actorA = sessions["a"] as! [String: Any]
        let actorB = sessions["b"] as! [String: Any]
        let keyAObject = ((fixture["chat_keys"] as! [String: Any])["a"] as! [String: Any])["frame"] as! [String: Any]
        let senderPublicKey = SyncSigning.publicKey(data(hex: actorA["ed25519_seed_hex"] as! String))!
        let roomRaw = data(hex: room["room_id_raw_hex"] as! String)
        let roomKey = SymmetricKey(data: data(hex: room["room_key_hex"] as! String))
        let localB = try makeSession(actorB, room: room)
        let peerA = try XCTUnwrap(TacMapChatCrypto.decodeAndVerifyPeerKey(
            keyAObject,
            roomIdRaw: roomRaw,
            signingPublicKey: senderPublicKey
        ))

        for (fixtureKey, expectedKind, expectedBody) in [
            ("room_message", TacMapChatContentKind.report, "SITREP: Route clear."),
            ("direct_message", TacMapChatContentKind.text, "RV at checkpoint 4.")
        ] {
            let vector = fixture[fixtureKey] as! [String: Any]
            let object = vector["frame"] as! [String: Any]
            let decoded = try TacMapChatCrypto.decodeFrame(object)
            XCTAssertEqual(
                try TacMapChatCrypto.makeHeader(roomIdRaw: roomRaw, frame: decoded),
                data(hex: vector["header_hex"] as! String),
                fixtureKey
            )
            let opened = try TacMapChatCrypto.open(
                object,
                roomIdRaw: roomRaw,
                roomKey: roomKey,
                localSession: localB,
                senderKey: peerA,
                senderSigningPublicKey: senderPublicKey
            )
            XCTAssertEqual(opened.payload.kind, expectedKind, fixtureKey)
            XCTAssertEqual(opened.payload.body, expectedBody, fixtureKey)
            XCTAssertTrue(TacMapChatMessage.isCanonical32(opened.fingerprint), fixtureKey)
        }
    }

    func testTamperedSignedFrameAndUnexpectedKeysAreRejected() throws {
        let room = fixture["room"] as! [String: Any]
        let sessions = fixture["sessions"] as! [String: Any]
        let actorA = sessions["a"] as! [String: Any]
        let actorB = sessions["b"] as! [String: Any]
        let roomRaw = data(hex: room["room_id_raw_hex"] as! String)
        let roomKey = SymmetricKey(data: data(hex: room["room_key_hex"] as! String))
        let publicA = SyncSigning.publicKey(data(hex: actorA["ed25519_seed_hex"] as! String))!
        let keyAObject = ((fixture["chat_keys"] as! [String: Any])["a"] as! [String: Any])["frame"] as! [String: Any]
        let peerA = try XCTUnwrap(TacMapChatCrypto.decodeAndVerifyPeerKey(
            keyAObject,
            roomIdRaw: roomRaw,
            signingPublicKey: publicA
        ))
        let localB = try makeSession(actorB, room: room)
        let original = (fixture["room_message"] as! [String: Any])["frame"] as! [String: Any]

        var tampered = original
        var signature = tampered["sig"] as! String
        signature.replaceSubrange(signature.startIndex...signature.startIndex, with: signature.first == "A" ? "B" : "A")
        tampered["sig"] = signature
        XCTAssertThrowsError(try TacMapChatCrypto.open(
            tampered,
            roomIdRaw: roomRaw,
            roomKey: roomKey,
            localSession: localB,
            senderKey: peerA,
            senderSigningPublicKey: publicA
        )) { error in
            XCTAssertEqual(error as? TacMapChatCrypto.CryptoError, .invalidSignature)
        }

        var extra = original
        extra["senderName"] = "UNSIGNED"
        XCTAssertThrowsError(try TacMapChatCrypto.decodeFrame(extra))

        var extraAdvert = keyAObject
        extraAdvert["extra"] = true
        XCTAssertNil(TacMapChatCrypto.decodeAndVerifyPeerKey(
            extraAdvert,
            roomIdRaw: roomRaw,
            signingPublicKey: publicA
        ))
    }

    func testMessageIdsAreCanonicalRandomSixteenByteValues() throws {
        let first = try TacMapChatMessage.makeMessageID()
        let second = try TacMapChatMessage.makeMessageID()
        XCTAssertTrue(TacMapChatMessage.isCanonicalMessageID(first))
        XCTAssertTrue(TacMapChatMessage.isCanonicalMessageID(second))
        XCTAssertNotEqual(first, second)
    }

    func testPlaintextRejectsUnknownFieldsAndReservedReceipts() {
        let unknown = Data(
            #"{"pv":1,"kind":"text","body":"hello","createdAt":1720000000000,"senderName":"UNSIGNED"}"#.utf8
        )
        XCTAssertThrowsError(try TacMapChatCrypto.decodePayload(unknown)) { error in
            XCTAssertEqual(error as? TacMapChatCrypto.CryptoError, .invalidPayload)
        }

        let receipt = Data(
            #"{"pv":1,"kind":"receipt","body":"delivered","createdAt":1720000000000}"#.utf8
        )
        XCTAssertThrowsError(try TacMapChatCrypto.decodePayload(receipt)) { error in
            XCTAssertEqual(error as? TacMapChatCrypto.CryptoError, .invalidPayload)
        }
    }

    func testRecipientSelectionIsBoundToEndpointTupleNotCallsign() {
        let actor = SyncIdentity.urlB64Encode(Data(repeating: 0x31, count: 32))
        let session = SyncIdentity.urlB64Encode(Data(repeating: 0x32, count: 32))
        let key = SyncIdentity.urlB64Encode(Data(repeating: 0x33, count: 32))
        let selected = TacMapChatRecipient(
            actorId: actor,
            displayName: "ALPHA",
            sessionDomain: session,
            keyId: key
        )
        XCTAssertEqual(
            selected,
            TacMapChatRecipient(
                actorId: actor,
                displayName: "ALPHA RENAMED",
                sessionDomain: session,
                keyId: key
            ),
            "cosmetic callsign changes must not invalidate the endpoint"
        )
        XCTAssertNotEqual(
            selected,
            TacMapChatRecipient(
                actorId: actor,
                displayName: "ALPHA",
                sessionDomain: SyncIdentity.urlB64Encode(Data(repeating: 0x34, count: 32)),
                keyId: key
            ),
            "a replacement websocket session must require explicit re-selection"
        )
        XCTAssertNotEqual(
            selected,
            TacMapChatRecipient(
                actorId: actor,
                displayName: "ALPHA",
                sessionDomain: session,
                keyId: SyncIdentity.urlB64Encode(Data(repeating: 0x35, count: 32))
            ),
            "a replacement ephemeral key must require explicit re-selection"
        )
    }

    private func makeSession(_ actor: [String: Any],
                             room: [String: Any]) throws -> TacMapChatCrypto.LocalSession {
        try TacMapChatCrypto.makeLocalSession(
            roomIdRaw: data(hex: room["room_id_raw_hex"] as! String),
            actorId: actor["actor_id"] as! String,
            sessionDomain: data(hex: actor["sd_hex"] as! String),
            signingSeed: data(hex: actor["ed25519_seed_hex"] as! String),
            privateKeyRaw: data(hex: actor["x25519_private_hex"] as! String)
        )
    }

    private func loadFixture() throws -> [String: Any] {
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = directory.appendingPathComponent("testdata/tacmap_chat_v1.json")
            if FileManager.default.fileExists(atPath: candidate.path) {
                let bytes = try Data(contentsOf: candidate)
                return try XCTUnwrap(
                    JSONSerialization.jsonObject(with: bytes) as? [String: Any]
                )
            }
            directory.deleteLastPathComponent()
        }
        throw CocoaError(.fileNoSuchFile)
    }

    private func data(hex: String) -> Data {
        SyncIdentity.hexToBytes(hex)
    }
}
