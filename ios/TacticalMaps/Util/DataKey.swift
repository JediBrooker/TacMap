import Foundation
import Security
import LocalAuthentication

/// Small transaction kernel shared by live access-control rotation and its
/// cold-start recovery path. AUTH upgrades commit only after the prior DEVICE
/// slot is verified absent. DEVICE transitions commit their honest weaker mode
/// first, then remove the redundant stronger slot on a best-effort basis.
enum DataKeyRotationFinalizer {
    enum Outcome: Equatable { case committed, rolledBack, pending, unrecoverable }

    static func finish(
        targetAuthBound: Bool,
        deletePrevious: () -> Bool,
        commitTargetMetadata: () -> Bool,
        rollbackMetadata: () -> Bool,
        deleteReplacement: () -> Bool
    ) -> Outcome {
        // Moving to DEVICE mode intentionally creates the weaker slot. Once it
        // has been verified, publish that honest mode first; a leftover AUTH
        // slot is redundant but cannot weaken DEVICE-mode protection.
        if !targetAuthBound {
            guard commitTargetMetadata() else {
                // Pending metadata cannot be finalized. Remove the newly weak
                // DEVICE copy before restoring an AUTH claim. If deletion is
                // unverifiable, leave the transaction pending so callers
                // conservatively report DEVICE rather than overclaim.
                guard deleteReplacement() else { return .unrecoverable }
                return rollbackMetadata() ? .rolledBack : .pending
            }
            _ = deletePrevious()
            return .committed
        }

        // Moving to AUTH mode is different: never publish the stronger claim
        // while the old DEVICE-readable copy remains queryable.
        if deletePrevious() {
            return commitTargetMetadata() ? .committed : .pending
        }
        let metadataRolledBack = rollbackMetadata()
        guard metadataRolledBack else {
            // Metadata still names the replacement. Keep both slots and the
            // pending marker; deleting the replacement here could leave a
            // stronger claim pointing at a missing item while DEVICE survives.
            return .pending
        }
        let replacementDeleted = deleteReplacement()
        return replacementDeleted ? .rolledBack : .unrecoverable
    }
}

enum DataKeyReportedMode {
    static func isAuthBound(
        metadataAuthBound: Bool?,
        transitionPending: Bool,
        legacyFallback: Bool
    ) -> Bool {
        // Either transition direction has a DEVICE-readable slot until the
        // transaction is finalized or safely rolled back. Never display the
        // stronger AUTH claim while metadata says cleanup is incomplete.
        if transitionPending { return false }
        return metadataAuthBound ?? legacyFallback
    }
}

enum DataKeyLegacyRecovery {
    struct Plan: Equatable {
        let activeAccount: String
        let authBound: Bool
    }

    static func plan(
        accounts: [String],
        savedAccount: String?,
        legacyAuthBound _: Bool,
        isAvailable: (String) -> Bool
    ) -> Plan? {
        let selected = savedAccount.flatMap { candidate in
            accounts.contains(candidate) && isAvailable(candidate) ? candidate : nil
        } ?? accounts.first(where: isAvailable)
        guard let selected else { return nil }
        // A mutable legacy preference cannot prove the Keychain ACL. Reporting
        // DEVICE is conservative and forces any future AUTH claim through the
        // checked two-slot transaction above.
        return Plan(activeAccount: selected, authBound: false)
    }
}

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
///    prompt. `ThisDeviceOnly` keeps it out of iCloud Keychain and prevents a
///    protected backup from restoring the item onto a different device; Apple
///    can restore it back to the same device. The class blocks ordinary
///    cross-device backup migration, but it does NOT defeat a live jailbreak
///    attacker with code execution as this app after first unlock.
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
        var errorDescription: String? { L10n.text("Mission data key is locked. Authenticate to continue.") }
    }

    /// The Keychain item is gone or undecryptable. Data is unreadable.
    struct UnrecoverableError: LocalizedError {
        let status: OSStatus
        var errorDescription: String? {
            L10n.text("Mission data key was invalidated by a device security change (%1$@).", status)
        }
    }

    struct RotationCleanupError: LocalizedError {
        var errorDescription: String? {
            L10n.text("Mission-data access control was not changed because the previous key slot could not be removed securely. Try again.")
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
        let metadata = loadMetadata()
        let unresolvedAlternate = metadata?.authBound == true && accounts.contains {
            $0 != metadata?.activeAccount && itemState($0) != .missing
        }
        return DataKeyReportedMode.isAuthBound(
            metadataAuthBound: metadata?.authBound,
            transitionPending: metadata?.pendingDeletionAccount != nil || unresolvedAlternate,
            legacyFallback: false
        )
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
            if let previous = metadata.pendingDeletionAccount,
               accounts.contains(previous), previous != metadata.activeAccount {
                let prior = Metadata(
                    activeAccount: previous,
                    authBound: !metadata.authBound
                )

                // If the replacement disappeared but the authoritative prior
                // slot survived, finish the rollback before allowing reads.
                // This is the inverse of the normal crash point (prior already
                // gone, replacement present), which the finalizer commits.
                if itemState(metadata.activeAccount) == .missing {
                    guard itemState(previous) != .missing,
                          storeMetadata(prior) else {
                        cached = nil
                        return
                    }
                    publishMetadataDefaults(prior)
                    return
                }
                let final = Metadata(
                    activeAccount: metadata.activeAccount,
                    authBound: metadata.authBound
                )
                switch DataKeyRotationFinalizer.finish(
                    targetAuthBound: metadata.authBound,
                    deletePrevious: { deleteChecked(account: previous) },
                    commitTargetMetadata: { storeMetadata(final) },
                    rollbackMetadata: { storeMetadata(prior) },
                    deleteReplacement: { deleteChecked(account: metadata.activeAccount) }
                ) {
                case .committed:
                    publishMetadataDefaults(final)
                case .rolledBack:
                    publishMetadataDefaults(prior)
                case .pending:
                    if !metadata.authBound { publishMetadataDefaults(final) }
                    cached = nil
                case .unrecoverable:
                    publishMetadataDefaults(metadata.authBound ? prior : final)
                    cached = nil
                }
                return
            }
            guard itemState(metadata.activeAccount) != .missing else { return }

            // Clean up a pre-transaction orphan. Historical AUTH metadata plus
            // a surviving alternate slot may be the old DEVICE copy from a
            // build that did not verify deletion; if it cannot be removed,
            // roll back the claim to the weaker (honest) mode.
            if let stale = accounts.first(where: {
                $0 != metadata.activeAccount && itemState($0) != .missing
            }) {
                if metadata.authBound {
                    let prior = Metadata(activeAccount: stale, authBound: false)
                    let pending = Metadata(
                        activeAccount: metadata.activeAccount,
                        authBound: true,
                        pendingDeletionAccount: stale
                    )
                    guard storeMetadata(pending) else {
                        cached = nil
                        return
                    }
                    switch DataKeyRotationFinalizer.finish(
                        targetAuthBound: true,
                        deletePrevious: { deleteChecked(account: stale) },
                        commitTargetMetadata: { storeMetadata(metadata) },
                        rollbackMetadata: { storeMetadata(prior) },
                        deleteReplacement: { deleteChecked(account: metadata.activeAccount) }
                    ) {
                    case .committed:
                        break
                    case .rolledBack:
                        publishMetadataDefaults(prior)
                        return
                    case .pending:
                        cached = nil
                        return
                    case .unrecoverable:
                        publishMetadataDefaults(prior)
                        cached = nil
                        return
                    }
                } else {
                    _ = deleteChecked(account: stale)
                }
            }
            publishMetadataDefaults(metadata)
            return
        }
        let states = accounts.map(itemState)
        if states.contains(.present) || states.contains(.inaccessible) {
            let saved = UserDefaults.standard.string(forKey: activeAccountDefaultsKey)
            guard let recovery = DataKeyLegacyRecovery.plan(
                accounts: accounts,
                savedAccount: saved,
                legacyAuthBound: UserDefaults.standard.bool(forKey: modeDefaultsKey),
                isAvailable: { itemState($0) != .missing }
            ) else { return }
            let selected = recovery.activeAccount
            // Never claim that a legacy item is auth-bound when its metadata is
            // gone: merely attaching an LAContext does not add an ACL to an
            // existing device-bound item. Prefer the old durable slot pointer,
            // but classify an unproven legacy ACL conservatively as DEVICE.
            // The user can then perform a fresh, checked DEVICE -> AUTH rotation.
            // Do not select or delete a slot until an actual 32-byte DEK read
            // proves it. An AUTH slot may be inaccessible without a prompt and
            // a saved slot may be corrupt while the alternate remains valid.
            // key() completes metadata recovery after that user-initiated read.
            publishMetadataDefaults(Metadata(
                activeAccount: selected,
                authBound: recovery.authBound
            ))
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
                _ = deleteChecked(account: account)
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
        let resolved = try loadResolved()
        let dek = resolved.key
        if loadMetadata() == nil {
            let recovered = Metadata(activeAccount: resolved.account, authBound: false)
            if storeMetadata(recovered) {
                publishMetadataDefaults(recovered)
            } else {
                // Preserve every slot and keep the public claim conservative;
                // a later read/startup can retry the metadata commit.
                publishMetadataDefaults(recovered)
            }
        }
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
        let oldAuthBound = isAuthBound
        guard enabled != oldAuthBound else { return }
        let dek = try key()
        lock.lock(); defer { lock.unlock() }
        let oldAccount = activeAccount
        let newAccount = accounts.first(where: { $0 != oldAccount }) ?? accounts[1]

        // Two-slot rotation: the readable old item remains authoritative until
        // the replacement has been added and independently read back.
        guard deleteChecked(account: newAccount) else {
            throw RotationCleanupError()
        }
        guard store(dek, account: newAccount, authBound: enabled) else {
            throw UnrecoverableError(status: errSecIO)
        }
        do {
            let verified = try load(account: newAccount, authBound: enabled)
            guard verified == dek else { throw UnrecoverableError(status: errSecDecode) }
        } catch {
            _ = deleteChecked(account: newAccount)
            throw error
        }
        let pending = Metadata(
            activeAccount: newAccount,
            authBound: enabled,
            pendingDeletionAccount: oldAccount
        )
        guard storeMetadata(pending) else {
            _ = deleteChecked(account: newAccount)
            throw UnrecoverableError(status: errSecIO)
        }
        let prior = Metadata(activeAccount: oldAccount, authBound: oldAuthBound)
        let final = Metadata(activeAccount: newAccount, authBound: enabled)
        switch DataKeyRotationFinalizer.finish(
            targetAuthBound: enabled,
            deletePrevious: { deleteChecked(account: oldAccount) },
            commitTargetMetadata: { storeMetadata(final) },
            rollbackMetadata: { storeMetadata(prior) },
            deleteReplacement: { deleteChecked(account: newAccount) }
        ) {
        case .committed:
            publishMetadataDefaults(final)
            cached = dek
        case .rolledBack:
            publishMetadataDefaults(prior)
            cached = dek
            throw RotationCleanupError()
        case .pending:
            if !enabled { publishMetadataDefaults(final) }
            cached = nil
            throw UnrecoverableError(status: errSecIO)
        case .unrecoverable:
            publishMetadataDefaults(enabled ? prior : final)
            cached = nil
            throw UnrecoverableError(status: errSecIO)
        }
    }

    // MARK: - Keychain

    private enum ItemState: Equatable { case missing, present, inaccessible }

    private struct Metadata: Codable, Equatable {
        let activeAccount: String
        let authBound: Bool
        let pendingDeletionAccount: String?

        init(
            activeAccount: String,
            authBound: Bool,
            pendingDeletionAccount: String? = nil
        ) {
            self.activeAccount = activeAccount
            self.authBound = authBound
            self.pendingDeletionAccount = pendingDeletionAccount
        }

        private enum CodingKeys: String, CodingKey {
            case activeAccount, authBound, pendingDeletionAccount
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            activeAccount = try container.decode(String.self, forKey: .activeAccount)
            authBound = try container.decode(Bool.self, forKey: .authBound)
            pendingDeletionAccount = try container.decodeIfPresent(
                String.self,
                forKey: .pendingDeletionAccount
            )
        }
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

    private static func loadResolved() throws -> (key: Data, account: String) {
        let metadata = loadMetadata()
        if metadata?.pendingDeletionAccount != nil {
            throw UnrecoverableError(status: errSecNotAvailable)
        }
        let selectedAccount = metadata?.activeAccount ?? activeAccount
        let selectedAuthBound = metadata?.authBound
            ?? false
        do {
            return (try load(account: selectedAccount, authBound: selectedAuthBound), selectedAccount)
        } catch let error as UnrecoverableError where metadata == nil {
            // Metadata-less upgrades keep both candidates until a real read
            // proves one. If the saved slot is missing/corrupt, try the other;
            // a user-cancelled AUTH prompt is LockedError and never falls back.
            for fallback in accounts where fallback != selectedAccount && itemState(fallback) != .missing {
                do {
                    return (try load(account: fallback, authBound: false), fallback)
                } catch is LockedError {
                    throw LockedError()
                } catch {
                    continue
                }
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
            authContext.localizedReason = L10n.text("Unlock mission data")
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

    private static func deleteChecked(account: String) -> Bool {
        let status = SecItemDelete(baseQuery(account: account) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { return false }
        return itemState(account) == .missing
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
        return status == errSecSuccess && loadMetadata() == metadata
    }

    private static func publishMetadataDefaults(_ metadata: Metadata) {
        UserDefaults.standard.set(metadata.activeAccount, forKey: activeAccountDefaultsKey)
        UserDefaults.standard.set(metadata.authBound, forKey: modeDefaultsKey)
        UserDefaults.standard.set(true, forKey: installedDefaultsKey)
    }
}
