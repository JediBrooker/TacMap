import Foundation
import Security
import CryptoKit

/// Versioned downgrade policy stored outside the file tree and authenticated
/// under the mission DEK. Deleting/replacing a `.sealed-only` sidecar used to
/// reopen the legacy plaintext reader; a sealed Keychain record cannot be
/// forged without both Keychain access and the DEK.
enum SealedMigrationPolicy {
    private static let service = "com.tacmap.sealed-policy"
    private static let envelopeLabel = "sealed-migration-policy/v1"
    private static let lock = NSLock()

    private struct State: Codable { var version = 1; var identifiers: Set<String> = [] }

    #if DEBUG
    /// Unit-test hosts do not consistently receive the app's Keychain access.
    /// Tests explicitly opt a fixed key into this process-local backend.
    private static var testStates: [String: State] = [:]
    #endif

    static func requiresSealed(_ identifier: String, key: Data) throws -> Bool {
        lock.lock(); defer { lock.unlock() }
        #if DEBUG
        if let state = testStates[account(for: key)] {
            return state.identifiers.contains(identifier)
        }
        #endif
        return try load(key: key).identifiers.contains(identifier)
    }

    static func markSealed(_ identifier: String, key: Data) throws {
        lock.lock(); defer { lock.unlock() }
        #if DEBUG
        let keyAccount = account(for: key)
        if var state = testStates[keyAccount] {
            state.identifiers.insert(identifier)
            testStates[keyAccount] = state
            return
        }
        #endif
        var state = try load(key: key)
        guard state.identifiers.insert(identifier).inserted else { return }
        let plain = try JSONEncoder().encode(state)
        let sealed = try SealedEnvelope.sealFile(key: key, plaintext: plain, label: envelopeLabel)
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                  kSecAttrService as String: service,
                                  kSecAttrAccount as String: account(for: key)]
        var status = SecItemUpdate(base as CFDictionary,
                                   [kSecValueData as String: sealed] as CFDictionary)
        if status == errSecItemNotFound {
            var add = base
            add[kSecValueData as String] = sealed
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            status = SecItemAdd(add as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw CocoaError(.fileWriteNoPermission) }
    }

    private static func load(key: Data) throws -> State {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccount as String: account(for: key),
                                    kSecReturnData as String: true,
                                    kSecMatchLimit as String: kSecMatchLimitOne]
        var out: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &out)
        if status == errSecItemNotFound { return State() }
        guard status == errSecSuccess, let sealed = out as? Data,
              let plain = SealedEnvelope.openFile(key: key, blob: sealed, label: envelopeLabel),
              let state = try? JSONDecoder().decode(State.self, from: plain), state.version == 1 else {
            throw SafeStore.SealError()
        }
        return state
    }

    private static func account(for key: Data) -> String {
        "policy.v1." + SHA256.hash(data: key).prefix(8).map { String(format: "%02x", $0) }.joined()
    }

    #if DEBUG
    static func resetForTests(key: Data) {
        lock.lock(); defer { lock.unlock() }
        testStates[account(for: key)] = State()
    }
    #endif
}

/// Shared persistence helpers for on-device stores.
///
/// Three things going on here, all of them because this is mission data:
///
///  - Writes go through `Data.write(options: .atomic)` which does the
///    temp-file-then-rename dance, so a crash mid-write can't truncate anything.
///
///  - Everything is sealed with AES-256-GCM under the `DataKey` before it hits
///    the disk. The `label` you pass is bound in as associated data, so a blob
///    can't be moved from one store to another.
///
///  - If a file exists but we can't decode it, we never treat it as "no data".
///    It gets moved aside as `<name>.corrupt-<epoch>` so we don't clobber the
///    user's drawings/waypoints.
///
/// Note the difference between `.corrupt` and `.locked`. Corrupt means the bytes
/// are bad and we set them aside. Locked means the bytes are almost certainly
/// fine but the key is auth-bound and nobody has authenticated yet. Quarantining
/// on locked would be a disaster: we'd move a perfectly good waypoints.json
/// aside and the first edit would persist an empty list on top. So locked
/// touches nothing, and callers must not write either.
///
/// Legacy plaintext files from builds before at-rest encryption are spotted by
/// the missing magic and re-sealed in place on first read: read plaintext,
/// decode it (that's the verify), atomic-write the sealed bytes over it. The
/// rename means there's never a window with two copies and never an orphan.
enum SafeStore {

    /// Supplies the at-rest key. Production wires `DataKey`; tests swap in a fixed key.
    static var keyProvider: () throws -> Data = { try DataKey.key() }

    enum Load<T> {
        /// File does not exist, genuine fresh install.
        case empty
        /// Decoded cleanly.
        case loaded(T)
        /// File existed but could not be read/decoded; it was preserved.
        case corrupt(quarantinedTo: URL?, error: Error)
        /// Key is auth-bound and locked. File untouched. Do not write.
        case locked(Error)
    }

    struct SealError: LocalizedError {
        var errorDescription: String? { "Sealed store failed authentication (tampered, or wrong store)." }
    }

    /// Seal `data` under `label` and atomically replace the file at `url`.
    static func write(_ data: Data, to url: URL, label: String) throws {
        let key = try keyProvider()
        let sealed = try SealedEnvelope.sealFile(key: key, plaintext: data, label: label)
        try writeSealed(sealed, to: url)
        try SealedMigrationPolicy.markSealed(policyID(url, label), key: key)
    }

    static func read<T>(_ url: URL, label: String, decode: (Data) throws -> T) -> Load<T> {
        let fm = FileManager.default
        guard fm.fileExists(atPath: url.path) else { return .empty }

        let key: Data
        do {
            key = try keyProvider()
        } catch {
            // Either locked, or the keychain item is gone and these bytes will
            // never decrypt again. Either way don't quarantine, just say so.
            return .locked(error)
        }

        do {
            let raw = try Data(contentsOf: url)
            let wasSealed = SealedEnvelope.isSealedFile(raw)
            let legacyMarker = fm.fileExists(atPath: legacyMarkerURL(for: url).path)
            if legacyMarker { try SealedMigrationPolicy.markSealed(policyID(url, label), key: key) }
            let sealedOnly = try SealedMigrationPolicy.requiresSealed(policyID(url, label), key: key)
            let plain: Data
            if wasSealed {
                guard let opened = SealedEnvelope.openFile(key: key, blob: raw, label: label) else {
                    throw SealError()
                }
                plain = opened
            } else {
                guard !sealedOnly else { throw SealError() }
                plain = raw // pre-encryption build wrote this
            }
            let value = try decode(plain)
            if !wasSealed {
                try writeSealed(SealedEnvelope.sealFile(key: key, plaintext: plain, label: label), to: url)
            }
            // Durable downgrade barrier: after a successful sealed read or
            // migration, this path will never again accept plaintext.
            try SealedMigrationPolicy.markSealed(policyID(url, label), key: key)
            if legacyMarker { try? fm.removeItem(at: legacyMarkerURL(for: url)) }
            return .loaded(value)
        } catch {
            let quarantine = url.deletingLastPathComponent()
                .appendingPathComponent(url.lastPathComponent + ".corrupt-\(Int(Date().timeIntervalSince1970))")
            let moved = (try? fm.moveItem(at: url, to: quarantine)) != nil
            return .corrupt(quarantinedTo: moved ? quarantine : nil, error: error)
        }
    }

    /// Belt and braces: the bytes are already ciphertext, but keep the platform
    /// file protection too. `...UntilFirstUserAuthentication` rather than
    /// `.complete` on purpose - `.complete` would lock us out during background
    /// GPX recording with the screen off.
    private static func writeSealed(_ bytes: Data, to url: URL) throws {
        try bytes.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private static func legacyMarkerURL(for url: URL) -> URL {
        url.appendingPathExtension("sealed-only")
    }

    private static func policyID(_ url: URL, _ label: String) -> String {
        "file:\(url.standardizedFileURL.path):\(label)"
    }
}
