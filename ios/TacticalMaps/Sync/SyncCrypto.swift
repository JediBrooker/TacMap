import Foundation
import CryptoKit

/// End-to-end crypto for unit sync. A unit shares a high-entropy **join code**;
/// from it each device derives `roomId` (routing only — the relay sees this,
/// never the key) and `roomKey` (the AEAD key, never leaves the device).
///
/// Objects are sealed with AES-256-GCM. `AES.GCM.SealedBox.combined` is
/// `nonce(12) ‖ ciphertext ‖ tag(16)` — byte-identical to the Android
/// `SyncCrypto` layout, so an iOS and an Android device on the same join code
/// interoperate. Derivation (SHA-256 room id, HKDF-SHA256 key with the same
/// salt/info) matches Android exactly.
enum SyncCrypto {
    private static let salt = Data("tacmap-sync-salt-v1".utf8)
    private static let info = Data("tacmap-e2e".utf8)

    /// base64url(no-pad) of SHA-256("tacmap-room|" + joinCode). Routing only.
    static func roomId(_ joinCode: String) -> String {
        let digest = SHA256.hash(data: Data("tacmap-room|\(joinCode)".utf8))
        return Data(digest).base64URLEncodedStringNoPad()
    }

    /// 32-byte AES key via HKDF-SHA256 over the join code.
    static func roomKey(_ joinCode: String) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: Data(joinCode.utf8)),
            salt: salt,
            info: info,
            outputByteCount: 32
        )
    }

    /// Seal plaintext → `nonce ‖ ct ‖ tag`, or nil on failure.
    static func seal(_ key: SymmetricKey, _ plaintext: Data) -> Data? {
        try? AES.GCM.seal(plaintext, using: key).combined
    }

    /// Open `nonce ‖ ct ‖ tag` → plaintext, or nil on tamper / wrong key.
    static func open(_ key: SymmetricKey, _ blob: Data) -> Data? {
        guard let box = try? AES.GCM.SealedBox(combined: blob) else { return nil }
        return try? AES.GCM.open(box, using: key)
    }
}

extension Data {
    /// URL-safe base64 with no padding (matches Android's Base64.getUrlEncoder().withoutPadding()).
    func base64URLEncodedStringNoPad() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
