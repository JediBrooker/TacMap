import Foundation
import CryptoKit
import LocalAuthentication
import Security

/// Optional app-access lock (PIN + biometric). A deterrent for a lost/borrowed
/// device — NOT full at-rest OPSEC (a captured, unlocked device can still be
/// forensically imaged). The PIN is never stored: we keep a random salt plus a
/// stretched SHA-256 hash (120k rounds) so the tiny 4-digit space isn't trivially
/// brute-forced from the stored value.
///
/// The salt, hash, and failed-attempt counters live in the **Keychain**
/// (`WhenUnlockedThisDeviceOnly`) — not UserDefaults — so they are not written to
/// device/iCloud backups and are unreadable while the device is locked. Online
/// guessing is throttled with escalating lockouts, and disabling/changing the PIN
/// requires the current PIN.
enum AppLock {
    private static let saltKey = "applock.salt"
    private static let hashKey = "applock.hash"
    private static let failKey = "applock.failcount"
    private static let lockUntilKey = "applock.lockeduntil"
    private static let iterations = 120_000

    // Legacy UserDefaults keys (pre-Keychain) — migrated on first access.
    private static let legacySaltKey = "applock.salt.v1"
    private static let legacyHashKey = "applock.hash.v1"

    /// After this many consecutive failures, lockouts kick in.
    private static let freeAttempts = 5
    /// Escalating lockout durations (seconds), indexed by failures past the free
    /// allowance and clamped to the last entry.
    private static let lockoutLadder: [TimeInterval] = [30, 60, 300, 900, 3600]

    static var isEnabled: Bool {
        migrateIfNeeded()
        return keychainGet(hashKey) != nil
    }

    static func setPIN(_ pin: String) {
        let salt = randomSalt()
        keychainSet(saltKey, salt)
        keychainSet(hashKey, hash(pin, salt: salt))
        resetThrottle()
    }

    /// Disable the lock. Requires the current PIN when one is set (returns false
    /// and changes nothing if the PIN is wrong), so a lock can't be silently
    /// removed by someone who doesn't know it.
    @discardableResult
    static func disable(currentPIN: String) -> Bool {
        guard isEnabled else { clearAll(); return true }
        guard verify(currentPIN) else { return false }
        clearAll()
        return true
    }

    /// Unconditional clear — only for internal/first-time-setup use.
    static func clear() { clearAll() }

    private static func clearAll() {
        keychainDelete(saltKey)
        keychainDelete(hashKey)
        resetThrottle()
        UserDefaults.standard.removeObject(forKey: legacySaltKey)
        UserDefaults.standard.removeObject(forKey: legacyHashKey)
    }

    /// Seconds remaining on the current lockout, or 0 if unlocked-for-attempts.
    static var lockoutRemaining: TimeInterval {
        guard let data = keychainGet(lockUntilKey),
              let until = data.toDouble() else { return 0 }
        return max(0, until - Date().timeIntervalSince1970)
    }

    static func verify(_ pin: String) -> Bool {
        if lockoutRemaining > 0 { return false } // throttled — reject without checking
        guard let salt = keychainGet(saltKey),
              let stored = keychainGet(hashKey) else { return false }
        if constantTimeEqual(hash(pin, salt: salt), stored) {
            resetThrottle()
            return true
        }
        registerFailure()
        return false
    }

    // MARK: throttling

    private static func registerFailure() {
        let fails = (keychainGet(failKey)?.toInt() ?? 0) + 1
        keychainSet(failKey, Data(fromInt: fails))
        if fails >= freeAttempts {
            let idx = min(fails - freeAttempts, lockoutLadder.count - 1)
            let until = Date().timeIntervalSince1970 + lockoutLadder[idx]
            keychainSet(lockUntilKey, Data(fromDouble: until))
        }
    }

    private static func resetThrottle() {
        keychainDelete(failKey)
        keychainDelete(lockUntilKey)
    }

    // MARK: hashing

    private static func randomSalt() -> Data {
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes)
    }

    private static func hash(_ pin: String, salt: Data) -> Data {
        var data = salt + Data(pin.utf8) + salt
        for _ in 0..<iterations { data = Data(SHA256.hash(data: data)) }
        return data
    }

    private static func constantTimeEqual(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count { diff |= a[i] ^ b[i] }
        return diff == 0
    }

    // MARK: legacy migration

    /// Move any pre-Keychain UserDefaults salt/hash into the Keychain once.
    private static func migrateIfNeeded() {
        guard keychainGet(hashKey) == nil,
              let salt = UserDefaults.standard.data(forKey: legacySaltKey),
              let oldHash = UserDefaults.standard.data(forKey: legacyHashKey) else { return }
        keychainSet(saltKey, salt)
        keychainSet(hashKey, oldHash)
        UserDefaults.standard.removeObject(forKey: legacySaltKey)
        UserDefaults.standard.removeObject(forKey: legacyHashKey)
    }

    // MARK: Keychain

    private static func keychainSet(_ account: String, _ data: Data) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.tacmap.applock",
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    private static func keychainGet(_ account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.tacmap.applock",
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data else { return nil }
        return data
    }

    private static func keychainDelete(_ account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.tacmap.applock",
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }

    // MARK: biometrics

    static var biometryAvailable: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
    }

    static func authenticateBiometric(reason: String, completion: @escaping (Bool) -> Void) {
        let ctx = LAContext()
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) else {
            completion(false); return
        }
        ctx.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason) { ok, _ in
            DispatchQueue.main.async { completion(ok) }
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
