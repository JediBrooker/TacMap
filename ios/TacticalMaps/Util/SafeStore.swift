import Foundation

/// Shared persistence helpers for on-device stores.
///
/// Key thing on the read path: if a file exists but we can't decode it, we
/// never treat it as "no data". It gets moved aside as `<name>.corrupt-<epoch>`
/// so we don't accidentally clobber the user's drawings/waypoints. Writes go
/// through `Data.write(options: .atomic)` which does the temp-file-then-rename
/// dance, so a crash mid-write can't truncate anything.
enum SafeStore {

    enum Load<T> {
        /// File does not exist, genuine fresh install.
        case empty
        /// Decoded cleanly.
        case loaded(T)
        /// File existed but could not be read/decoded; it was preserved.
        case corrupt(quarantinedTo: URL?, error: Error)
    }

    static func write(_ data: Data, to url: URL) throws {
        // Encrypt at rest. Using `...UntilFirstUserAuthentication` instead of
        // `.complete` on purpose - `.complete` would lock us out during background
        // GPX recording (screen off). This way file stays accessible after first
        // unlock but still encrypted at rest.
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
