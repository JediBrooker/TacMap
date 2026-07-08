import Foundation

/// Durable persistence primitives shared by the on-device stores.
///
/// The critical guarantee is on the *read* path: a present-but-unreadable file
/// is never treated as "no data". It is moved aside as `<name>.corrupt-<epoch>`
/// and reported, so a caller cannot seed-then-overwrite the only copy of the
/// user's drawings / waypoints. Writes use `Data.write(options: .atomic)`, which
/// already writes to a temp file and renames, so a crash mid-write cannot
/// truncate the target.
enum SafeStore {

    enum Load<T> {
        /// File does not exist — a genuine fresh install.
        case empty
        /// Decoded cleanly.
        case loaded(T)
        /// File existed but could not be read/decoded; it was preserved.
        case corrupt(quarantinedTo: URL?, error: Error)
    }

    static func write(_ data: Data, to url: URL) throws {
        // Encrypt at rest. `...UntilFirstUserAuthentication` (not `.complete`) is
        // deliberate: it keeps the file readable/writable after the first device
        // unlock following a boot, so background GPX track writing (screen off)
        // and normal reads still work, while the data stays encrypted at rest.
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    static func read<T>(_ url: URL, decode: (Data) throws -> T) -> Load<T> {
        let fm = FileManager.default
        guard fm.fileExists(atPath: url.path) else { return .empty }
        do {
            let data = try Data(contentsOf: url)
            return .loaded(try decode(data))
        } catch {
            let quarantine = url.deletingLastPathComponent()
                .appendingPathComponent(url.lastPathComponent + ".corrupt-\(Int(Date().timeIntervalSince1970))")
            let moved = (try? fm.moveItem(at: url, to: quarantine)) != nil
            return .corrupt(quarantinedTo: moved ? quarantine : nil, error: error)
        }
    }
}
