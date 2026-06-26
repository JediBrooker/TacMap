import Foundation
import CryptoKit
import LocalAuthentication

/// Optional app-access lock (PIN + biometric). A deterrent for a lost/borrowed
/// device — NOT full at-rest OPSEC (a captured, unlocked device can still be
/// forensically imaged). The PIN is never stored: we keep a random salt plus a
/// stretched SHA-256 hash (120k rounds) so the tiny 4-digit space isn't trivially
/// brute-forced from the stored value.
enum AppLock {
    private static let saltKey = "applock.salt.v1"
    private static let hashKey = "applock.hash.v1"
    private static let iterations = 120_000

    static var isEnabled: Bool { UserDefaults.standard.data(forKey: hashKey) != nil }

    static func setPIN(_ pin: String) {
        let salt = randomSalt()
        UserDefaults.standard.set(salt, forKey: saltKey)
        UserDefaults.standard.set(hash(pin, salt: salt), forKey: hashKey)
    }

    static func clear() {
        UserDefaults.standard.removeObject(forKey: saltKey)
        UserDefaults.standard.removeObject(forKey: hashKey)
    }

    static func verify(_ pin: String) -> Bool {
        guard let salt = UserDefaults.standard.data(forKey: saltKey),
              let stored = UserDefaults.standard.data(forKey: hashKey) else { return false }
        return constantTimeEqual(hash(pin, salt: salt), stored)
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
