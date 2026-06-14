import Foundation
import CryptoKit

/// Offline voucher unlock. Codes are validated against an embedded list of
/// SHA-256 hashes (salted), so the plaintext codes never ship in the binary
/// and can't be recovered by dumping strings from the app.
///
/// Generate codes + hashes with `scripts/generate_vouchers.py` and paste the
/// hash list into `validHashes` below (same list goes into the Android
/// VoucherManager so any code works on both platforms).
///
/// Redemption is stored in the Keychain so it survives reinstall, like the
/// trial clock. Note this is *device-scoped* — a voucher redeemed on an
/// iPhone won't carry to the user's iPad (unlike an App Store purchase).
@MainActor
final class VoucherManager: ObservableObject {

    /// Must match SALT in scripts/generate_vouchers.py.
    private static let salt = "tacmap-voucher-v1"

    /// SHA-256(salt + normalizedCode), lowercase hex.
    /// REPLACE with the output of scripts/generate_vouchers.py.
    private static let validHashes: Set<String> = [
        // "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    ]

    private static let kcRedeemed = "voucherRedeemed"

    @Published private(set) var isRedeemed: Bool

    init() {
        isRedeemed = KeychainStore.bool(for: Self.kcRedeemed)
    }

    /// Uppercases and strips spaces/hyphens so "tacmap 1234..." and
    /// "TACMAP-1234-..." both validate.
    static func normalize(_ code: String) -> String {
        code.uppercased().filter { $0.isLetter || $0.isNumber }
    }

    /// Attempt to redeem. Returns true (and persists) on a valid code.
    @discardableResult
    func redeem(_ code: String) -> Bool {
        let normalized = Self.normalize(code)
        guard !normalized.isEmpty else { return false }
        let digest = SHA256.hash(data: Data((Self.salt + normalized).utf8))
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        guard Self.validHashes.contains(hex) else { return false }
        KeychainStore.set(true, for: Self.kcRedeemed)
        isRedeemed = true
        return true
    }
}
