import XCTest
import CryptoKit
@testable import TacticalMaps

/// iOS side of the cross-platform sync-crypto pin. These values are byte-for-byte
/// identical to the Android SyncCryptoTest fixture, which is how we actually
/// prove the threat model's "both platforms interoperate on the same join code"
/// claim rather than just asserting it. If either side drifts, one of these
/// suites goes red.
final class SyncCryptoTests: XCTestCase {

    func testDerivationIsPinnedForCrossPlatformInterop() {
        // Reference: PBKDF2-HMAC-SHA256 210k over "tacmap-sync-salt-v2", HMAC-SHA256
        // subkeys, base64url no-pad. Same fixture as Android. Rev both together.
        let keys = SyncCrypto.deriveRoom("alpha-bravo-charlie")
        XCTAssertEqual(keys.roomId, "rw6A3NDGVQLSoee4dXFKgBcEJDW5Vlo--Mvvymguc0k")
        XCTAssertEqual(keys.authToken, "KMnOnROo3p8dpbSRyU38w56daHTftk6NN3F6ApgXv7c")
        let roomKeyHex = keys.roomKey.withUnsafeBytes { Data($0) }
            .map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(roomKeyHex,
                       "bf0fc618150a534d2dceaba7f956ecc8db0e389e58f1ec87b3e62305ec15b742")
    }

    func testSealOpenRoundTripsAndRejectsWrongKeyAndAad() {
        let key = SyncCrypto.roomKey("unit-7-key")
        let aad = SyncCrypto.aad(id: "obj-1", v: 7, kind: "waypoint")
        let plaintext = Data(#"{"id":"abc","name":"OP North"}"#.utf8)

        let blob = SyncCrypto.seal(key, plaintext, aad: aad)!
        XCTAssertTrue(blob.count >= plaintext.count + 28)  // iv(12) + ct + tag(16)
        XCTAssertEqual(SyncCrypto.open(key, blob, aad: aad), plaintext)

        // Wrong key fails.
        XCTAssertNil(SyncCrypto.open(SyncCrypto.roomKey("code-two"), blob, aad: aad))
        // A relay swapping the blob onto another object id -> different AAD -> fails.
        let otherAad = SyncCrypto.aad(id: "obj-2", v: 7, kind: "waypoint")
        XCTAssertNil(SyncCrypto.open(key, blob, aad: otherAad))
    }

    func testGeneratedJoinCodeIsStrongAndPassesTheGuard() {
        let code = SyncCrypto.generateJoinCode()
        XCTAssertTrue(code.hasPrefix("3:"))
        XCTAssertEqual(code.count, 18)
        XCTAssertTrue(code.dropFirst(2).allSatisfy { "23456789ABCDEFGHJKMNPQRSTVWXYZ".contains($0) })
        XCTAssertFalse(SyncCrypto.isJoinCodeTooWeak(code))
        XCTAssertTrue(SyncCrypto.isJoinCodeTooWeak("bravo-tonight"))  // 13 chars
    }
}
