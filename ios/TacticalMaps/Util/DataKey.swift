import Foundation
import Security

/// The at-rest data-encryption key (DEK) for mission data, and where it lives.
///
/// Android wraps a DEK under a non-exportable Keystore KEK. On iOS the Keychain
/// already gives us the same property, so the DEK just lives there directly as
/// 32 bytes: hardware-encrypted, device-bound, and unwrapped by keys the Secure
/// Enclave manages. Flipping [setAuthBound] re-adds that one item with different
/// access control. Files are never re-encrypted either way.
///
/// A note on the Secure Enclave, since it's easy to overclaim: the SEP only
/// holds NIST P-256 keys, so you cannot put a raw AES-256 key "in" it. What
/// actually protects this item is the Keychain's class-key hierarchy, whose
/// keys the SEP wraps and holds. That is a real hardware guarantee. It is not
/// the same sentence as "the key is in the Secure Enclave", so we don't write
/// that sentence.
///
/// Two modes, and the difference is the whole point of THREAT_MODEL section 7:
///
///  - DEVICE mode (default). `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`.
///    Readable by this app any time after the first unlock since boot, with no
///    prompt. `ThisDeviceOnly` keeps it out of iCloud Keychain and encrypted
///    backups. So this defeats offline attacks - a filesystem image, a backup,
///    a seized locked handset, a binned device - and it does NOT defeat a live
///    jailbreak attacker with code exec as this app.
///
///  - AUTH mode (opt-in). Adds a `SecAccessControl` with `.userPresence`, so
///    reading the item makes the Secure Enclave demand Face ID / Touch ID /
///    passcode. A jailbreak alone gets nothing. Cost is that after the process
///    dies nothing can read or write mission data until the user authenticates,
///    which includes background track recording.
///
/// We cache the DEK in memory for the process lifetime so AUTH mode prompts
/// once per launch, not once per waypoint.
enum DataKey {

    /// Auth-bound and the user hasn't authenticated. Recoverable: prompt, retry.
    struct LockedError: LocalizedError {
        var errorDescription: String? { "Mission data key is locked. Authenticate to continue." }
    }

    /// The Keychain item is gone or undecryptable. Data is unreadable.
    struct UnrecoverableError: LocalizedError {
        let status: OSStatus
        var errorDescription: String? {
            "Mission data key was invalidated by a device security change (\(status))."
        }
    }

    private static let service = "com.tacmap.datakey"
    private static let account = "dek.v1"
    /// Records which access control the item was written with. The Keychain
    /// won't tell us without reading the item, and reading it prompts.
    private static let modeDefaultsKey = "datakey.authBound.v1"

    private static let lock = NSLock()
    private static var cached: Data?

    /// True when the DEK needs a user auth before it can be read.
    static var isAuthBound: Bool {
        UserDefaults.standard.bool(forKey: modeDefaultsKey)
    }

    /// True when a store can read/write right now without prompting.
    static var isUnlocked: Bool {
        lock.lock(); defer { lock.unlock() }
        return cached != nil || !isAuthBound
    }

    /// Create the DEK on first ever run. Safe to call before the user has
    /// authenticated: in AUTH mode an existing key is not read here.
    static func install() {
        lock.lock(); defer { lock.unlock() }
        guard !itemExists() else { return }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { return }
        let dek = Data(bytes)
        if store(dek, authBound: false) {
            UserDefaults.standard.set(false, forKey: modeDefaultsKey)
            cached = dek
        }
    }

    /// The DEK. Throws `LockedError` in AUTH mode when the user hasn't
    /// authenticated, `UnrecoverableError` when the item is gone.
    static func key() throws -> Data {
        lock.lock(); defer { lock.unlock() }
        if let cached { return cached }
        let dek = try load()
        cached = dek
        return dek
    }

    /// Drop the in-memory DEK. AUTH mode will prompt again after this.
    static func lockKey() {
        lock.lock(); defer { lock.unlock() }
        cached = nil
    }

    /// Move the DEK between device-bound and auth-bound access control.
    /// Files are untouched. Reads the current DEK first, which in AUTH mode
    /// means the user gets prompted.
    static func setAuthBound(_ enabled: Bool) throws {
        guard enabled != isAuthBound else { return }
        let dek = try key()
        lock.lock(); defer { lock.unlock() }
        delete()
        guard store(dek, authBound: enabled) else {
            // Put it back the way it was rather than leaving no key at all.
            _ = store(dek, authBound: !enabled)
            throw UnrecoverableError(status: errSecIO)
        }
        UserDefaults.standard.set(enabled, forKey: modeDefaultsKey)
        cached = dek
    }

    // MARK: - Keychain

    private static func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private static func itemExists() -> Bool {
        var query = baseQuery()
        query[kSecReturnData as String] = false
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        // Don't trip the auth prompt just to answer "is there a key".
        query[kSecUseAuthenticationUI as String] = kSecUseAuthenticationUISkip
        let status = SecItemCopyMatching(query as CFDictionary, nil)
        return status == errSecSuccess || status == errSecInteractionNotAllowed
    }

    private static func store(_ dek: Data, authBound: Bool) -> Bool {
        var add = baseQuery()
        add[kSecValueData as String] = dek
        if authBound {
            guard let access = SecAccessControlCreateWithFlags(
                nil,
                kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                .userPresence,
                nil
            ) else { return false }
            add[kSecAttrAccessControl as String] = access
        } else {
            // After-first-unlock, not when-unlocked: background track recording
            // writes fixes with the screen off and must not be locked out.
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        }
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    private static func load() throws -> Data {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        if isAuthBound {
            query[kSecUseOperationPrompt as String] = "Unlock mission data"
        }
        var out: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &out)
        switch status {
        case errSecSuccess:
            guard let data = out as? Data, data.count == 32 else {
                throw UnrecoverableError(status: status)
            }
            return data
        case errSecInteractionNotAllowed, errSecAuthFailed, errSecUserCanceled:
            throw LockedError()
        default:
            throw UnrecoverableError(status: status)
        }
    }

    private static func delete() {
        SecItemDelete(baseQuery() as CFDictionary)
    }
}
