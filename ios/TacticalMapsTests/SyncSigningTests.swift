import XCTest
@testable import TacticalMaps

final class SyncSigningTests: XCTestCase {

    private func hex(_ s: String) -> Data {
        var d = Data(); var i = s.startIndex
        while i < s.endIndex {
            let j = s.index(i, offsetBy: 2)
            d.append(UInt8(s[i..<j], radix: 16)!); i = j
        }
        return d
    }

    // RFC 8032 Ed25519 TEST 1 (empty message). Same base64url values as the
    // Android SyncSigningTest - if either platform's Ed25519 drifts, one goes red.
    private let pubB64 = "11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo"
    private let sigB64 = "5VZDAMNgrHKQhuLMgG6CioSHfx645dl02HPgZSJJAVVfuIIVkKM7rMYeOXAc-bRr0lv18FlbviRlUUFDjnoQCw"

    func testMatchesRfc8032Vector() {
        // Key derivation is deterministic and standard, so the pubkey is
        // byte-identical to Android.
        let seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        XCTAssertEqual(SyncSigning.publicKey(seed), pubB64)
        // CryptoKit Ed25519 signatures are randomized (hedged nonce), so they are
        // NOT byte-identical to the deterministic RFC/Bouncycastle signature - but
        // both are valid standard Ed25519. What matters for interop is that iOS
        // VERIFIES the deterministic Android/RFC signature (Android-sign ->
        // iOS-verify), and that an iOS-produced signature verifies too (it's
        // standard, so it verifies on Android as well).
        XCTAssertTrue(SyncSigning.verify(pubB64, Data(), sigB64))
        XCTAssertTrue(SyncSigning.verify(SyncSigning.publicKey(seed)!, Data(),
                                         SyncSigning.sign(seed, Data())!))
    }

    func testPresenceMessageIsPinnedForCrossPlatform() {
        // Same hex as Android SyncSigningTest.presenceMessageIsPinnedForCrossPlatform,
        // so an Android-signed presence verifies on iOS and vice-versa.
        let msg = SyncSigning.presenceMessage(
            "dev-1", 1_700_000_000_000, 37.8065, -122.4103, 90.0, 1.5,
            "ALPHA-1", "FRIEND", "TEAM", "INFANTRY", true)
        let hex = msg.map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(hex,
            "6465762d311f313730303030303030303030301f33372e3830363530301f2d3132322e34313033" +
            "30301f39302e3030303030301f312e3530303030301f414c5048412d311f465249454e441f5445" +
            "414d1f494e46414e5452591f31")
    }

    func testObjectMessageIsPinnedForCrossPlatform() {
        // Same hex as Android SyncSigningTest.objectMessageIsPinnedForCrossPlatform,
        // so an Android-signed object write verifies on iOS and vice-versa.
        let put = SyncSigning.objectMessage("obj-1", 7, "waypoint", "dev-1", "GEO")
        XCTAssertEqual(put.map { String(format: "%02x", $0) }.joined(),
                       "6f626a2d311f371f776179706f696e741f6465762d311f47454f")
        let del = SyncSigning.objectMessage("obj-1", 7, "del", "dev-1", "")
        XCTAssertEqual(del.map { String(format: "%02x", $0) }.joined(),
                       "6f626a2d311f371f64656c1f6465762d311f")
        // A signature over the put must not verify against the delete message, so
        // a relay can't swap a write for a delete.
        let seed = SyncSigning.generateSeed()
        let pub = SyncSigning.publicKey(seed)!
        let putSig = SyncSigning.sign(seed, put)!
        XCTAssertTrue(SyncSigning.verify(pub, put, putSig))
        XCTAssertFalse(SyncSigning.verify(pub, del, putSig))
    }

    func testRejectsTamperedMessageWrongKeyAndGarbage() {
        let seed = SyncSigning.generateSeed()
        let msg = Data("grid 1234 5678".utf8)
        let sig = SyncSigning.sign(seed, msg)!
        let pub = SyncSigning.publicKey(seed)!
        XCTAssertTrue(SyncSigning.verify(pub, msg, sig))
        XCTAssertFalse(SyncSigning.verify(pub, Data("grid 1234 5679".utf8), sig))  // tamper
        XCTAssertFalse(SyncSigning.verify(pubB64, msg, sig))                        // wrong key
        XCTAssertFalse(SyncSigning.verify(pub, msg, "not-base64!!"))                // garbage
    }
}
