import Foundation
import Security

/// Minimal Keychain wrapper for the trial timestamps (first launch + the
/// clock-rollback high-water mark). Generic-password items survive app
/// deletion, which is the whole point: a reinstall sees the same trial clock.
///
/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` keeps the items
/// device-bound (not migrated via iCloud Keychain / encrypted backup to a
/// different device), matching the Android Block Store config.
enum KeychainStore {
    private static let service = "com.tacticalmaps.app.entitlement"

    static func data(for account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess else { return nil }
        return result as? Data
    }

    @discardableResult
    static func set(_ data: Data, for account: String) -> Bool {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        // Try update first, then add.
        let update: [String: Any] = [kSecValueData as String: data]
        var status = SecItemUpdate(base as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var add = base
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] =
                kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            status = SecItemAdd(add as CFDictionary, nil)
        }
        return status == errSecSuccess
    }

    // Convenience: dates stored as epoch-seconds Double.
    static func date(for account: String) -> Date? {
        guard let d = data(for: account), d.count == 8 else { return nil }
        let epoch = d.withUnsafeBytes { $0.load(as: Double.self) }
        return Date(timeIntervalSince1970: epoch)
    }

    @discardableResult
    static func set(_ date: Date, for account: String) -> Bool {
        var epoch = date.timeIntervalSince1970
        return set(Data(bytes: &epoch, count: 8), for: account)
    }
}
