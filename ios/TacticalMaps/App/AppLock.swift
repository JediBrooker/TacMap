import Foundation
import CryptoKit
import LocalAuthentication
import Security

protocol AppLockKeychainClient {
    func read(account: String) throws -> Data?
    func updateOrAdd(_ data: Data, account: String) throws
    func delete(account: String) throws
}

enum AppLockPersistenceError: LocalizedError {
    case keychain(operation: String, status: OSStatus)
    case invalidCredential
    case readBackFailed
    case randomGeneration(status: OSStatus)

    var errorDescription: String? {
        switch self {
        case .keychain:
            return L10n.text("The App Lock credential could not be saved securely. Your previous setting was kept.")
        case .invalidCredential:
            return L10n.text("The saved App Lock credential is invalid. Your previous setting was kept.")
        case .readBackFailed:
            return L10n.text("The App Lock credential could not be verified after saving. Your previous setting was kept.")
        case .randomGeneration:
            return L10n.text("A secure App Lock credential could not be generated. Your previous setting was kept.")
        }
    }
}

struct SecurityAppLockKeychainClient: AppLockKeychainClient {
    private let service = "com.tacmap.applock"

    func read(account: String) throws -> Data? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &out)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = out as? Data else {
            throw AppLockPersistenceError.keychain(operation: "read", status: status)
        }
        return data
    }

    func updateOrAdd(_ data: Data, account: String) throws {
        let query = baseQuery(account: account)
        let values: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        let updateStatus = SecItemUpdate(query as CFDictionary, values as CFDictionary)
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else {
            throw AppLockPersistenceError.keychain(operation: "update", status: updateStatus)
        }
        var add = query
        values.forEach { add[$0.key] = $0.value }
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw AppLockPersistenceError.keychain(operation: "add", status: addStatus)
        }
    }

    func delete(account: String) throws {
        let status = SecItemDelete(baseQuery(account: account) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw AppLockPersistenceError.keychain(operation: "delete", status: status)
        }
    }

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}

private struct AppLockCredentialRecord: Codable, Equatable {
    static let currentVersion = 1
    let version: Int
    let salt: Data
    let hash: Data
}

/// Testable credential and throttle store. Salt + hash share one versioned
/// Keychain item so the app can never observe half of a PIN update.
final class AppLockStore {
    static let credentialAccount = "applock.credential"
    static let legacySaltAccount = "applock.salt"
    static let legacyHashAccount = "applock.hash"
    static let failureAccount = "applock.failcount"
    static let lockUntilAccount = "applock.lockeduntil"
    static let legacyDefaultsSaltKey = "applock.salt.v1"
    static let legacyDefaultsHashKey = "applock.hash.v1"

    private let keychain: AppLockKeychainClient
    private let defaults: UserDefaults
    private let iterations: Int
    private let now: () -> TimeInterval
    private let saltProvider: () throws -> Data
    private let freeAttempts = 5
    private let lockoutLadder: [TimeInterval] = [30, 60, 300, 900, 3600]

    init(
        keychain: AppLockKeychainClient,
        defaults: UserDefaults,
        iterations: Int = 120_000,
        now: @escaping () -> TimeInterval = { Date().timeIntervalSince1970 },
        saltProvider: @escaping () throws -> Data = AppLockStore.secureRandomSalt
    ) {
        self.keychain = keychain
        self.defaults = defaults
        self.iterations = iterations
        self.now = now
        self.saltProvider = saltProvider
    }

    var isEnabled: Bool {
        try? migrateIfNeeded()
        do {
            if try keychain.read(account: Self.credentialAccount) != nil { return true }
            let legacySalt = try keychain.read(account: Self.legacySaltAccount)
            let legacyHash = try keychain.read(account: Self.legacyHashAccount)
            if legacySalt != nil || legacyHash != nil { return true }
        } catch {
            // Fail closed if Keychain availability is inconclusive. A
            // transient read error must never silently bypass an enabled lock.
            return true
        }
        return defaults.data(forKey: Self.legacyDefaultsSaltKey) != nil
            || defaults.data(forKey: Self.legacyDefaultsHashKey) != nil
    }

    func setPIN(_ pin: String) throws {
        // Fail before touching the working credential if throttle persistence
        // cannot be reset for the newly chosen PIN.
        try resetThrottle()
        let salt = try saltProvider()
        let record = AppLockCredentialRecord(
            version: AppLockCredentialRecord.currentVersion,
            salt: salt,
            hash: hash(pin, salt: salt)
        )
        let encoded = try JSONEncoder().encode(record)
        let previous = try keychain.read(account: Self.credentialAccount)
        try persistAndVerify(encoded, previous: previous)
    }

    func disable(currentPIN: String) throws -> Bool {
        guard isEnabled else {
            try clearAll()
            return true
        }
        guard verify(currentPIN) else { return false }
        // Verification may have fallen back to a legacy split credential when
        // migration was unavailable. Establish the equivalent combined record
        // and verify its exact bytes before deleting any part of that only
        // working legacy credential.
        try ensureVerifiedCombinedCredential()
        try clearAll()
        return true
    }

    func clear() throws { try clearAll() }

    var lockoutRemaining: TimeInterval {
        guard let data = try? keychain.read(account: Self.lockUntilAccount),
              let until = data.toDouble() else { return 0 }
        return max(0, until - now())
    }

    func verify(_ pin: String) -> Bool {
        if lockoutRemaining > 0 { return false }
        guard let credential = credentialForUse() else { return false }
        if Self.constantTimeEqual(hash(pin, salt: credential.salt), credential.hash) {
            try? resetThrottle()
            return true
        }
        try? registerFailure()
        return false
    }

    /// Writes the combined record and reads the exact bytes back before any
    /// legacy item is removed. Migration failure therefore leaves the prior
    /// working split/defaults credential untouched.
    func migrateIfNeeded() throws {
        if try readCurrentCredential() != nil {
            // A prior migration may have verified the combined record and
            // then failed while removing stale legacy data. Keep retrying that
            // cleanup without rewriting the already-valid credential.
            if try legacyArtifactsExist() { try removeLegacyArtifacts() }
            return
        }
        guard let legacy = try readLegacyCredential() else { return }
        let encoded = try JSONEncoder().encode(legacy)
        try persistAndVerify(encoded, previous: nil)
        try removeLegacyArtifacts()
    }

    private func credentialForUse() -> AppLockCredentialRecord? {
        do { try migrateIfNeeded() } catch {
            // A checked migration failure must not lock out someone whose
            // legacy credential is still intact.
        }
        if let current = try? readCurrentCredential() { return current }
        return try? readLegacyCredential()
    }

    private func readCurrentCredential() throws -> AppLockCredentialRecord? {
        guard let data = try keychain.read(account: Self.credentialAccount) else { return nil }
        guard let record = try? JSONDecoder().decode(AppLockCredentialRecord.self, from: data),
              record.version == AppLockCredentialRecord.currentVersion,
              !record.salt.isEmpty,
              !record.hash.isEmpty else {
            throw AppLockPersistenceError.invalidCredential
        }
        return record
    }

    private func readLegacyCredential() throws -> AppLockCredentialRecord? {
        let splitSalt = try keychain.read(account: Self.legacySaltAccount)
        let splitHash = try keychain.read(account: Self.legacyHashAccount)
        let salt: Data
        let storedHash: Data
        if let splitSalt, let splitHash {
            salt = splitSalt
            storedHash = splitHash
        } else if let defaultsSalt = defaults.data(forKey: Self.legacyDefaultsSaltKey),
                  let defaultsHash = defaults.data(forKey: Self.legacyDefaultsHashKey) {
            salt = defaultsSalt
            storedHash = defaultsHash
        } else {
            return nil
        }
        guard !salt.isEmpty, !storedHash.isEmpty else { return nil }
        return AppLockCredentialRecord(
            version: AppLockCredentialRecord.currentVersion,
            salt: salt,
            hash: storedHash
        )
    }

    private func ensureVerifiedCombinedCredential() throws {
        if try readCurrentCredential() != nil { return }
        guard let legacy = try readLegacyCredential() else {
            throw AppLockPersistenceError.invalidCredential
        }
        let encoded = try JSONEncoder().encode(legacy)
        try persistAndVerify(encoded, previous: nil)
    }

    private func legacyArtifactsExist() throws -> Bool {
        let splitSaltExists = try keychain.read(account: Self.legacySaltAccount) != nil
        let splitHashExists = try keychain.read(account: Self.legacyHashAccount) != nil
        return splitSaltExists
            || splitHashExists
            || defaults.data(forKey: Self.legacyDefaultsSaltKey) != nil
            || defaults.data(forKey: Self.legacyDefaultsHashKey) != nil
    }

    private func removeLegacyArtifacts() throws {
        try runAll([
            { try self.keychain.delete(account: Self.legacySaltAccount) },
            { try self.keychain.delete(account: Self.legacyHashAccount) },
        ])
        defaults.removeObject(forKey: Self.legacyDefaultsSaltKey)
        defaults.removeObject(forKey: Self.legacyDefaultsHashKey)
    }

    private func persistAndVerify(_ data: Data, previous: Data?) throws {
        var writeCompleted = false
        do {
            try keychain.updateOrAdd(data, account: Self.credentialAccount)
            writeCompleted = true
            guard try keychain.read(account: Self.credentialAccount) == data else {
                throw AppLockPersistenceError.readBackFailed
            }
        } catch {
            if writeCompleted {
                if let previous {
                    try? keychain.updateOrAdd(previous, account: Self.credentialAccount)
                } else {
                    try? keychain.delete(account: Self.credentialAccount)
                }
            }
            throw error
        }
    }

    private func clearAll() throws {
        // The active combined credential is the rollback boundary. Attempt
        // every fallible legacy/throttle cleanup first and leave the active PIN
        // untouched if any of them fail.
        try runAll([
            { try self.keychain.delete(account: Self.legacySaltAccount) },
            { try self.keychain.delete(account: Self.legacyHashAccount) },
            { try self.keychain.delete(account: Self.failureAccount) },
            { try self.keychain.delete(account: Self.lockUntilAccount) },
        ])
        defaults.removeObject(forKey: Self.legacyDefaultsSaltKey)
        defaults.removeObject(forKey: Self.legacyDefaultsHashKey)
        try keychain.delete(account: Self.credentialAccount)
    }

    private func runAll(_ operations: [() throws -> Void]) throws {
        var firstError: Error?
        for operation in operations {
            do {
                try operation()
            } catch {
                if firstError == nil { firstError = error }
            }
        }
        if let firstError { throw firstError }
    }

    private func registerFailure() throws {
        let fails = ((try keychain.read(account: Self.failureAccount))?.toInt() ?? 0) + 1
        try keychain.updateOrAdd(Data(fromInt: fails), account: Self.failureAccount)
        if fails >= freeAttempts {
            let index = min(fails - freeAttempts, lockoutLadder.count - 1)
            try keychain.updateOrAdd(
                Data(fromDouble: now() + lockoutLadder[index]),
                account: Self.lockUntilAccount
            )
        }
    }

    private func resetThrottle() throws {
        try runAll([
            { try self.keychain.delete(account: Self.failureAccount) },
            { try self.keychain.delete(account: Self.lockUntilAccount) },
        ])
    }

    private func hash(_ pin: String, salt: Data) -> Data {
        var data = salt + Data(pin.utf8) + salt
        for _ in 0..<iterations { data = Data(SHA256.hash(data: data)) }
        return data
    }

    private static func constantTimeEqual(_ first: Data, _ second: Data) -> Bool {
        guard first.count == second.count else { return false }
        var difference: UInt8 = 0
        for index in first.indices { difference |= first[index] ^ second[index] }
        return difference == 0
    }

    private static func secureRandomSalt() throws -> Data {
        var bytes = [UInt8](repeating: 0, count: 16)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        guard status == errSecSuccess else {
            throw AppLockPersistenceError.randomGeneration(status: status)
        }
        return Data(bytes)
    }
}

/// Optional app-access lock (PIN + biometric). PIN persistence is delegated to
/// an injectable store; this static facade keeps existing UI call sites small.
enum AppLock {
    /// RootGate posts the actual overlay state so mounted sensitive views can
    /// purge transient plaintext even when the DataKey is device-bound.
    static let stateChanged = Notification.Name("TacMapAppLockStateChanged")

    private static let store = AppLockStore(
        keychain: SecurityAppLockKeychainClient(),
        defaults: .standard
    )

    static var isEnabled: Bool { store.isEnabled }
    static var lockoutRemaining: TimeInterval { store.lockoutRemaining }
    static func setPIN(_ pin: String) throws { try store.setPIN(pin) }
    static func verify(_ pin: String) -> Bool { store.verify(pin) }
    static func disable(currentPIN: String) throws -> Bool {
        try store.disable(currentPIN: currentPIN)
    }
    static func clear() throws { try store.clear() }

    static var biometryAvailable: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
    }

    static func authenticateBiometric(reason: String, completion: @escaping (Bool) -> Void) {
        let context = LAContext()
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) else {
            completion(false)
            return
        }
        context.evaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics,
            localizedReason: reason
        ) { success, _ in
            DispatchQueue.main.async { completion(success) }
        }
    }
}

private extension Data {
    init(fromInt value: Int) { var v = value; self.init(bytes: &v, count: MemoryLayout<Int>.size) }
    init(fromDouble value: Double) { var v = value; self.init(bytes: &v, count: MemoryLayout<Double>.size) }
    func toInt() -> Int? {
        guard count == MemoryLayout<Int>.size else { return nil }
        return withUnsafeBytes { $0.load(as: Int.self) }
    }
    func toDouble() -> Double? {
        guard count == MemoryLayout<Double>.size else { return nil }
        return withUnsafeBytes { $0.load(as: Double.self) }
    }
}
