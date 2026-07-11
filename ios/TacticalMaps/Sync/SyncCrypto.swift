import Foundation
import CryptoKit
import CommonCrypto

/// E2E crypto for unit sync. Everyone in a unit shares a join code,
/// and from that we derive three values via one PBKDF2 stretch:
///
///   master    = PBKDF2-HMAC-SHA256(joinCode, salt, 210k iters) - 32 bytes
///   roomId    = base64url(HMAC(master, "...roomid..."))  - routing id (relay sees this)
///   roomKey   = HMAC(master, "...roomkey...")             - AES-256-GCM key (never sent)
///   authToken = base64url(HMAC(master, "...auth..."))    - bearer token for writer auth
///
/// The whole point of PBKDF2 is that a short join code would otherwise be
/// brute-forceable offline against retained ciphertext. Since roomId is also
/// behind the same 210k-iteration stretch, its not a cheap offline verifier
/// either, and is unguessable without the code. authToken only travels in the
/// WebSocket handshake header (not URL/logs), so a leaked roomId alone can't
/// write to a room.
///
/// Objects are sealed with AES-256-GCM, routing metadata bound as AEAD
/// associated data. `AES.GCM.SealedBox.combined` = `nonce(12) || ct || tag(16)`,
/// byte-identical to Android's `SyncCrypto` layout so both platforms interoperate
/// on the same join code (codes are ASCII so PBKDF2 bytes match).
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

    /// Below this a join code is too short to resist offline guessing against
    /// the relay-visible roomId. Generated codes clear it comfortably.
    static let minJoinCodeLength = 14

    /// A strong, unambiguous join code (~78 bits) - the recommended way to start
    /// a room. Confidentiality rests ENTIRELY on join-code entropy: roomId is
    /// relay-visible and derived from the same code, so a coerced relay can
    /// offline-guess a weak/human code ("bravo-tonight") and recover roomKey +
    /// authToken (see THREAT_MODEL). Uppercase base32 minus look-alikes so it
    /// survives being read out over the net. randomElement() draws from the
    /// system CSPRNG.
    static func generateJoinCode() -> String {
        let alphabet = Array("23456789ABCDEFGHJKMNPQRSTVWXYZ")
        return String((0..<16).map { _ in alphabet.randomElement()! })
    }

    static func isJoinCodeTooWeak(_ code: String) -> Bool {
        code.trimmingCharacters(in: .whitespacesAndNewlines).count < minJoinCodeLength
    }

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

    /// AEAD associated data - binds ciphertext to routing metadata.
    static func aad(id: String, v: Int, kind: String) -> Data {
        Data("\(id)|\(v)|\(kind)".utf8)
    }

    /// Seal plaintext into nonce+ct+tag, authenticating [aad]. nil on failure.
    static func seal(_ key: SymmetricKey, _ plaintext: Data, aad: Data) -> Data? {
        try? AES.GCM.seal(plaintext, using: key, authenticating: aad).combined
    }

    /// Open nonce+ct+tag back to plaintext, nil if tampered / wrong key / AAD mismatch.
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
