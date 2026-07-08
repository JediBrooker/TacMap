import Foundation
import CryptoKit
import CommonCrypto

/// End-to-end crypto for unit sync. A unit shares a **join code**; from it each
/// device derives three values via ONE expensive password-stretch:
///
///   master     = PBKDF2-HMAC-SHA256(joinCode, salt, iterations) — 32 B
///   roomId     = base64url(HMAC(master, "…roomid…"))  — routing id (relay-visible)
///   roomKey    = HMAC(master, "…roomkey…")            — AES-256-GCM key (never sent)
///   authToken  = base64url(HMAC(master, "…auth…"))    — writer-auth bearer token
///
/// Password stretching (PBKDF2) is the point: a memorable join code is otherwise
/// brute-forceable offline against retained ciphertext. Because roomId is now
/// downstream of the same 210k-iteration PBKDF2, it is no longer a *cheap*
/// offline verifier and is unguessable without the code; authToken travels only
/// in the WebSocket handshake header (never the URL/logs), so a leaked roomId
/// alone cannot write to a room.
///
/// Objects are sealed AES-256-GCM with the routing metadata bound in as AEAD
/// associated data. `AES.GCM.SealedBox.combined` is `nonce(12) ‖ ct ‖ tag(16)` —
/// byte-identical to the Android `SyncCrypto` layout, so iOS and Android on the
/// same join code interoperate (join codes are ASCII, so the PBKDF2 byte
/// encoding matches).
enum SyncCrypto {
    private static let salt = "tacmap-sync-salt-v2"
    private static let pbkdf2Iterations: UInt32 = 210_000

    struct RoomKeys {
        let roomId: String
        let roomKey: SymmetricKey
        let authToken: String
    }

    /// Run the expensive PBKDF2 once and derive all three room values.
    static func deriveRoom(_ joinCode: String) -> RoomKeys {
        let key = SymmetricKey(data: master(joinCode))
        return RoomKeys(
            roomId: subKey(key, "tacmap-roomid-v2").base64URLEncodedStringNoPad(),
            roomKey: SymmetricKey(data: subKey(key, "tacmap-roomkey-v2")),
            authToken: subKey(key, "tacmap-auth-v2").base64URLEncodedStringNoPad()
        )
    }

    static func roomId(_ joinCode: String) -> String { deriveRoom(joinCode).roomId }
    static func roomKey(_ joinCode: String) -> SymmetricKey { deriveRoom(joinCode).roomKey }
    static func authToken(_ joinCode: String) -> String { deriveRoom(joinCode).authToken }

    private static func master(_ joinCode: String) -> Data {
        let pwd = Array(joinCode.utf8)
        let saltBytes = Array(salt.utf8)
        var out = [UInt8](repeating: 0, count: 32)
        let status = pwd.withUnsafeBufferPointer { pwdPtr in
            saltBytes.withUnsafeBufferPointer { saltPtr in
                CCKeyDerivationPBKDF(
                    CCPBKDFAlgorithm(kCCPBKDF2),
                    pwdPtr.baseAddress, pwd.count,
                    saltPtr.baseAddress, saltBytes.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                    pbkdf2Iterations,
                    &out, out.count
                )
            }
        }
        precondition(status == kCCSuccess, "PBKDF2 derivation failed")
        return Data(out)
    }

    private static func subKey(_ key: SymmetricKey, _ label: String) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: Data(label.utf8), using: key))
    }

    /// AEAD associated data binding ciphertext to its routing metadata.
    static func aad(id: String, v: Int, kind: String) -> Data {
        Data("\(id)|\(v)|\(kind)".utf8)
    }

    /// Seal plaintext → `nonce ‖ ct ‖ tag`, authenticating [aad]; nil on failure.
    static func seal(_ key: SymmetricKey, _ plaintext: Data, aad: Data) -> Data? {
        try? AES.GCM.seal(plaintext, using: key, authenticating: aad).combined
    }

    /// Open `nonce ‖ ct ‖ tag` → plaintext, or nil on tamper / wrong key / AAD mismatch.
    static func open(_ key: SymmetricKey, _ blob: Data, aad: Data) -> Data? {
        guard let box = try? AES.GCM.SealedBox(combined: blob) else { return nil }
        return try? AES.GCM.open(box, using: key, authenticating: aad)
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
