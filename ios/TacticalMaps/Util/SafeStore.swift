import Foundation

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
        let sealed = try SealedEnvelope.sealFile(key: try keyProvider(), plaintext: data, label: label)
        try writeSealed(sealed, to: url)
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
            let plain: Data
            if wasSealed {
                guard let opened = SealedEnvelope.openFile(key: key, blob: raw, label: label) else {
                    throw SealError()
                }
                plain = opened
            } else {
                plain = raw // pre-encryption build wrote this
            }
            let value = try decode(plain)
            if !wasSealed {
                try writeSealed(SealedEnvelope.sealFile(key: key, plaintext: plain, label: label), to: url)
            }
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
}
