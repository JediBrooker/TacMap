import Foundation
import Security
import LocalAuthentication

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
/// We cache the DEK only while the app is active. AUTH mode prompts again after
/// backgrounding or App Lock; an active track keeps a private, short-lived copy
/// inside TrackRecorder so background fixes do not require this global cache.
enum DataKey {
    static let lockChanged = Notification.Name("DataKeyLockChanged")

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
    private static let accounts = ["dek.v1", "dek.v1.slot2"]
    private static let activeAccountDefaultsKey = "datakey.activeAccount.v2"
    private static let installedDefaultsKey = "datakey.installed.v2"
    private static let metadataAccount = "dek.metadata.v2"
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
        if let metadata = loadMetadata() {
            guard accounts.contains(metadata.activeAccount) else { return }
            UserDefaults.standard.set(metadata.activeAccount, forKey: activeAccountDefaultsKey)
            UserDefaults.standard.set(metadata.authBound, forKey: modeDefaultsKey)
            UserDefaults.standard.set(true, forKey: installedDefaultsKey)
            // Complete cleanup if a prior rotation crashed after committing
            // metadata but before removing the old slot.
            if itemState(metadata.activeAccount) != .missing {
                accounts.filter { $0 != metadata.activeAccount }.forEach { delete(account: $0) }
            }
            return
        }
        let states = accounts.map(itemState)
        if states.contains(.present) || states.contains(.inaccessible) {
            let selected = accounts.first(where: { itemState($0) != .missing }) ?? accounts[0]
            // Never claim that a legacy item is auth-bound when its metadata is
            // gone: merely attaching an LAContext does not add an ACL to an
            // existing device-bound item. Unknown legacy state is displayed as
            // device-bound; toggling the setting performs a real two-slot ACL
            // rotation. An actually auth-bound legacy item still enforces its own
            // Keychain ACL when read.
            let authBound = UserDefaults.standard.object(forKey: modeDefaultsKey) != nil
                && UserDefaults.standard.bool(forKey: modeDefaultsKey)
            _ = storeMetadata(Metadata(activeAccount: selected, authBound: authBound))
            UserDefaults.standard.set(selected, forKey: activeAccountDefaultsKey)
            UserDefaults.standard.set(true, forKey: installedDefaultsKey)
            return
        }
        // Once a key has existed, absence is data loss, not a fresh install.
        // Never silently mint a replacement that would make existing ciphertext
        // look corrupt and invite callers to overwrite it.
        guard !UserDefaults.standard.bool(forKey: installedDefaultsKey) else { return }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { return }
        let dek = Data(bytes)
        let account = accounts[0]
        if store(dek, account: account, authBound: false) {
            guard storeMetadata(Metadata(activeAccount: account, authBound: false)) else {
                delete(account: account)
                return
            }
            UserDefaults.standard.set(account, forKey: activeAccountDefaultsKey)
            UserDefaults.standard.set(true, forKey: installedDefaultsKey)
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
        lock.lock()
        cached = nil
        lock.unlock()
        NotificationCenter.default.post(name: lockChanged, object: nil)
    }

    /// Move the DEK between device-bound and auth-bound access control.
    /// Files are untouched. Reads the current DEK first, which in AUTH mode
    /// means the user gets prompted.
    static func setAuthBound(_ enabled: Bool) throws {
        guard enabled != isAuthBound else { return }
        let dek = try key()
        lock.lock(); defer { lock.unlock() }
        let oldAccount = activeAccount
        let newAccount = accounts.first(where: { $0 != oldAccount }) ?? accounts[1]

        // Two-slot rotation: the readable old item remains authoritative until
        // the replacement has been added and independently read back.
        delete(account: newAccount)
        guard store(dek, account: newAccount, authBound: enabled) else {
            throw UnrecoverableError(status: errSecIO)
        }
        do {
            let verified = try load(account: newAccount, authBound: enabled)
            guard verified == dek else { throw UnrecoverableError(status: errSecDecode) }
        } catch {
            delete(account: newAccount)
            throw error
        }
        guard storeMetadata(Metadata(activeAccount: newAccount, authBound: enabled)) else {
            delete(account: newAccount)
            throw UnrecoverableError(status: errSecIO)
        }
        UserDefaults.standard.set(newAccount, forKey: activeAccountDefaultsKey)
        UserDefaults.standard.set(enabled, forKey: modeDefaultsKey)
        UserDefaults.standard.set(true, forKey: installedDefaultsKey)
        cached = dek
        // Metadata now durably selects the new slot, so the old ACL can no
        // longer become a downgrade path after settings loss/reinstall.
        delete(account: oldAccount)
    }

    // MARK: - Keychain

    private enum ItemState: Equatable { case missing, present, inaccessible }

    private struct Metadata: Codable {
        let activeAccount: String
        let authBound: Bool
    }

    private static var activeAccount: String {
        if let metadata = loadMetadata(), accounts.contains(metadata.activeAccount) {
            return metadata.activeAccount
        }
        let saved = UserDefaults.standard.string(forKey: activeAccountDefaultsKey)
        return accounts.contains(saved ?? "") ? saved! : accounts[0]
    }

    private static func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private static func itemState(_ account: String) -> ItemState {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = false
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        // Don't trip the auth prompt just to answer "is there a key".
        query[kSecUseAuthenticationUI as String] = kSecUseAuthenticationUISkip
        let status = SecItemCopyMatching(query as CFDictionary, nil)
        if status == errSecSuccess { return .present }
        if status == errSecItemNotFound { return .missing }
        return .inaccessible
    }

    private static func store(_ dek: Data, account: String, authBound: Bool) -> Bool {
        var add = baseQuery(account: account)
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
        do {
            return try load(account: activeAccount, authBound: isAuthBound)
        } catch let error as UnrecoverableError where error.status == errSecItemNotFound && loadMetadata() == nil {
            // A crash between writing the second slot and flipping the pointer,
            // or legacy settings loss, may leave the other valid slot present.
            if let fallback = accounts.first(where: { $0 != activeAccount && itemState($0) != .missing }) {
                return try load(account: fallback, authBound: isAuthBound)
            }
            throw error
        }
    }

    private static func load(account: String, authBound: Bool) throws -> Data {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        if authBound {
            let authContext = LAContext()
            authContext.localizedReason = "Unlock mission data"
            query[kSecUseAuthenticationContext as String] = authContext
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

    private static func delete(account: String) {
        SecItemDelete(baseQuery(account: account) as CFDictionary)
    }

    private static func loadMetadata() -> Metadata? {
        var query = baseQuery(account: metadataAccount)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data else { return nil }
        return try? JSONDecoder().decode(Metadata.self, from: data)
    }

    private static func storeMetadata(_ metadata: Metadata) -> Bool {
        guard let data = try? JSONEncoder().encode(metadata) else { return false }
        let base = baseQuery(account: metadataAccount)
        let update = [kSecValueData as String: data]
        var status = SecItemUpdate(base as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var add = base
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            status = SecItemAdd(add as CFDictionary, nil)
        }
        return status == errSecSuccess
    }
}
