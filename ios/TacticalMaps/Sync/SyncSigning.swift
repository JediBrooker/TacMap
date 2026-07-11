import Foundation
import CryptoKit

/// Per-device signing identity for unit sync. Each device holds an Ed25519
/// keypair (seed sealed at rest by SyncManager); presence updates carry the
/// device's public key + a signature so a room member CANNOT impersonate
/// another established peer's callsign/position, and a coerced relay's replay of
/// an older signed update is caught by the monotonic counter that rides inside
/// the signed content.
///
/// Standard Ed25519 via CryptoKit `Curve25519.Signing` - byte-for-byte
/// interoperable with Android's Bouncycastle `SyncSigning` (both RFC 8032).
///
/// NOTE: this authenticates *established* peers (trust-on-first-use per
/// clientId). It does not stop a room-key holder from inventing a brand-new fake
/// clientId - room membership is still the trust boundary (THREAT_MODEL §7).
enum SyncSigning {
    /// 32-byte Ed25519 seed for a fresh device identity.
    static func generateSeed() -> Data {
        Curve25519.Signing.PrivateKey().rawRepresentation
    }

    /// Base64url (no pad) public key derived from `seed`, or nil if seed invalid.
    static func publicKey(_ seed: Data) -> String? {
        guard let priv = try? Curve25519.Signing.PrivateKey(rawRepresentation: seed) else { return nil }
        return b64url(priv.publicKey.rawRepresentation)
    }

    /// Ed25519 signature over `message`, base64url (no pad), or nil on failure.
    static func sign(_ seed: Data, _ message: Data) -> String? {
        guard let priv = try? Curve25519.Signing.PrivateKey(rawRepresentation: seed),
              let sig = try? priv.signature(for: message) else { return nil }
        return b64url(Data(sig))
    }

    /// True iff `signatureB64` is a valid signature of `message` under
    /// `publicKeyB64`. Any malformed input -> false.
    static func verify(_ publicKeyB64: String, _ message: Data, _ signatureB64: String) -> Bool {
        guard let pubBytes = deB64(publicKeyB64),
              let sigBytes = deB64(signatureB64),
              let pub = try? Curve25519.Signing.PublicKey(rawRepresentation: pubBytes) else { return false }
        return pub.isValidSignature(sigBytes, for: message)
    }

    /// Canonical, serialization-independent bytes a presence signature covers.
    /// Fields are joined by U+001F (unit separator); coordinates use fixed %.6f
    /// so both platforms - and sender vs receiver - build byte-identical input.
    /// MUST byte-match Android's `SyncSigning.presenceMessage`.
    static func presenceMessage(
        _ clientId: String, _ ts: Int64, _ lat: Double, _ lon: Double,
        _ heading: Double, _ speed: Double, _ callsign: String,
        _ affiliation: String, _ echelon: String, _ function: String, _ isHQ: Bool
    ) -> Data {
        func f(_ x: Double) -> String { String(format: "%.6f", x) }
        let s = [clientId, String(ts), f(lat), f(lon), f(heading), f(speed),
                 callsign, affiliation, echelon, function, isHQ ? "1" : "0"]
            .joined(separator: "\u{1F}")
        return Data(s.utf8)
    }

    /// Canonical bytes an object-write signature covers: the routing metadata a
    /// receiver reconstructs from the relay record (id, v, kind, by) plus the
    /// exact plaintext content that was sealed. Joined by U+001F like
    /// `presenceMessage` so it's serialization-independent and byte-identical to
    /// Android. For a delete, kind is "del" and content is "". Signing `by` and
    /// `v` means the relay can't re-attribute or roll back a write - either
    /// change breaks the signature. MUST byte-match Android's `objectMessage`.
    static func objectMessage(_ id: String, _ v: Int64, _ kind: String, _ by: String, _ content: String) -> Data {
        Data([id, String(v), kind, by, content].joined(separator: "\u{1F}").utf8)
    }

    private static func b64url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func deB64(_ s: String) -> Data? {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        return Data(base64Encoded: t)
    }
}
