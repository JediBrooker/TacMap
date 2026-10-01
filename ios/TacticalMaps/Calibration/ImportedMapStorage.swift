import Foundation
import CryptoKit

/// Where imported maps live on disk and how bytes get there (contract s9.3 / s9.9).
///
/// - ImportedMaps/ holds imports under opaque names (map-<uuid>.pdf|.mbtiles),
///   offline_tiles/ holds WP2 bakes. Both are Application Support, never
///   Documents (no Files.app surface).
/// - completeUntilFirstUserAuthentication, not .complete: a partial copy or a
///   bake has to survive the screen locking mid-write.
/// - isExcludedFromBackup on both dirs and every file: an operational map
///   doesn't belong in an iCloud/Finder backup (D5-14). Re-applied after every
///   rename and swept at launch because some moves drop the flag.
enum ImportedMapStorage {

    static var applicationSupportProvider: () -> URL = {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    }

    static func applicationSupportDirectory() -> URL { applicationSupportProvider() }

    static let importedDirectoryName = "ImportedMaps"
    static let tilesDirectoryName = "offline_tiles"
    static let inspectingMarkerName = ".import-inspecting"

    static func importedMapsDirectory(fileManager fm: FileManager = .default) throws -> URL {
        try managedDirectory(importedDirectoryName, fileManager: fm)
    }

    static func offlineTilesDirectory(fileManager fm: FileManager = .default) throws -> URL {
        try managedDirectory(tilesDirectoryName, fileManager: fm)
    }

    private static func managedDirectory(_ name: String, fileManager fm: FileManager) throws -> URL {
        let dir = applicationSupportDirectory().appendingPathComponent(name, isDirectory: true)
        try fm.createDirectory(at: dir, withIntermediateDirectories: true,
                               attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        // older builds made these .complete, which kills a partial write once the device locks
        try? fm.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: dir.path)
        excludeFromBackup(dir)
        return dir
    }

    /// ImportedMaps/map-<uuid>.<ext>
    static func opaqueDestination(id: UUID, ext: String) throws -> URL {
        try importedMapsDirectory().appendingPathComponent("map-\(id.uuidString.lowercased()).\(ext)", isDirectory: false)
    }

    /// "ImportedMaps/x.pdf" for a file inside one of the two managed dirs
    static func relativePath(for url: URL) -> String? {
        let support = applicationSupportDirectory().standardizedFileURL.resolvingSymlinksInPath().path
        let path = url.standardizedFileURL.resolvingSymlinksInPath().path
        let prefix = support.hasSuffix("/") ? support : support + "/"
        guard path.hasPrefix(prefix) else { return nil }
        let rel = String(path.dropFirst(prefix.count))
        return resolveManaged(relativePath: rel) == nil ? nil : rel
    }

    /// Only "ImportedMaps/<name>" or "offline_tiles/<name>", no traversal, no nesting.
    static func resolveManaged(relativePath rel: String) -> URL? {
        let parts = rel.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 2, [importedDirectoryName, tilesDirectoryName].contains(parts[0]),
              !parts[1].isEmpty, parts[1] != ".", parts[1] != "..", !parts[1].contains("\\") else { return nil }
        return applicationSupportDirectory()
            .appendingPathComponent(parts[0], isDirectory: true)
            .appendingPathComponent(parts[1], isDirectory: false)
    }

    static func excludeFromBackup(_ url: URL) {
        var u = url
        var v = URLResourceValues()
        v.isExcludedFromBackup = true
        try? u.setResourceValues(v)
    }

    static func protectAndExclude(_ url: URL) {
        try? FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                                               ofItemAtPath: url.path)
        excludeFromBackup(url)
    }

    /// launch backstop: every file in both dirs gets the flag again
    static func sweepBackupExclusion(fileManager fm: FileManager = .default) {
        for name in [importedDirectoryName, tilesDirectoryName] {
            let dir = applicationSupportDirectory().appendingPathComponent(name, isDirectory: true)
            guard fm.fileExists(atPath: dir.path) else { continue }
            excludeFromBackup(dir)
            let kids = (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
            kids.forEach(excludeFromBackup)
        }
    }

    // MARK: - copy + hash in one pass

    struct CopyResult: Sendable {
        var url: URL
        var byteCount: Int64
        /// sha256:<hex>, the content key
        var contentKey: String
    }

    /// Stream source -> destination.partial in 1 MiB chunks, hashing as it goes
    /// (the hash is the content key, no second read). The partial is registered
    /// in flight so a concurrent reconcile leaves it alone, then renamed.
    /// Throws CancellationError within a chunk of a cancel.
    static func copyHashing(_ source: URL, to destination: URL, maximumBytes: Int64,
                            chunkSize: Int = ImportLimits.cancelCopyGranularityBytes,
                            isCancelled: @escaping () -> Bool = { false },
                            progress: (Int64, Int64) -> Void = { _, _ in },
                            fileManager fm: FileManager = .default) throws -> CopyResult {
        let keys: Set<URLResourceKey> = [.fileSizeKey, .isRegularFileKey, .isSymbolicLinkKey]
        let before = try source.resourceValues(forKeys: keys)
        guard before.isRegularFile == true, before.isSymbolicLink != true, let s = before.fileSize, s >= 0 else {
            throw MapImportError.failed(detail: CocoaError(.fileReadUnknown).localizedDescription)
        }
        let size = Int64(s)
        guard size <= maximumBytes else { throw MapImportError.tooLarge(limit: maximumBytes) }
        let partial = destination.appendingPathExtension("partial")
        try fm.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        InFlightImportFiles.register(partial)
        InFlightImportFiles.register(destination)
        defer { InFlightImportFiles.unregister(partial) }
        var hasher = SHA256()
        var done: Int64 = 0
        do {
            guard fm.createFile(atPath: partial.path, contents: nil,
                                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]) else {
                throw CocoaError(.fileWriteUnknown)
            }
            excludeFromBackup(partial)
            let input = try FileHandle(forReadingFrom: source)
            defer { try? input.close() }
            let output = try FileHandle(forWritingTo: partial)
            defer { try? output.close() }
            progress(0, size)
            while true {
                if isCancelled() || Task.isCancelled { throw CancellationError() }
                guard let chunk = try input.read(upToCount: max(1, chunkSize)), !chunk.isEmpty else { break }
                // the file grew under us, refuse rather than copy past the limit
                guard done + Int64(chunk.count) <= size else { throw MapImportError.failed(detail: CocoaError(.fileReadCorruptFile).localizedDescription) }
                hasher.update(data: chunk)
                try output.write(contentsOf: chunk)
                done += Int64(chunk.count)
                progress(done, size)
            }
            if isCancelled() || Task.isCancelled { throw CancellationError() }
            guard done == size else { throw MapImportError.failed(detail: CocoaError(.fileReadCorruptFile).localizedDescription) }
            try output.synchronize()
            if fm.fileExists(atPath: destination.path) { try fm.removeItem(at: destination) }
            try fm.moveItem(at: partial, to: destination)
            protectAndExclude(destination)
        } catch {
            try? fm.removeItem(at: partial)
            InFlightImportFiles.unregister(destination)
            throw error
        }
        let key = "sha256:" + hasher.finalize().map { String(format: "%02x", $0) }.joined()
        return CopyResult(url: destination, byteCount: size, contentKey: key)
    }

    static func modifiedAtMs(_ url: URL) -> Int64 {
        let d = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
        return d.map { Int64(($0.timeIntervalSince1970 * 1000).rounded()) } ?? 0
    }

    static func freeBytes() -> Int64 {
        let url = applicationSupportDirectory()
        let v = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return v?.volumeAvailableCapacityForImportantUsage ?? Int64.max
    }

    /// remove a map file and its SQLite sidecars, best effort
    static func unlink(_ url: URL, fileManager fm: FileManager = .default) {
        for suffix in ["", "-wal", "-shm", "-journal"] {
            let u = suffix.isEmpty ? url : URL(fileURLWithPath: url.path + suffix)
            try? fm.removeItem(at: u)
        }
    }
}

/// Legacy copier kept for MBTiles validation tests and the bounded copy they
/// pin. New imports go through ImportedMapStorage.copyHashing.
enum ImportedMapFileCopier {
    static let maxPDFBytes = Int(ImportLimits.pdfMaxBytes)
    static let maxMBTilesBytes = Int(ImportLimits.mbtilesMaxBytes)

    static func copyToImportedMaps(_ source: URL,
                                   maximumBytes: Int = maxPDFBytes,
                                   preferredExtension: String? = nil,
                                   fileManager: FileManager = .default) throws -> URL {
        let dir = try importedMapsDirectory(fileManager: fileManager)
        let dest = try copy(source, into: dir, maximumBytes: maximumBytes,
                            preferredExtension: preferredExtension, fileManager: fileManager)
        do {
            try fileManager.setAttributes(
                [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                ofItemAtPath: dest.path)
        } catch {
            try? fileManager.removeItem(at: dest)
            throw error
        }
        ImportedMapStorage.excludeFromBackup(dest)
        return dest
    }

    static func copy(_ source: URL,
                     into directory: URL,
                     maximumBytes: Int = maxPDFBytes,
                     preferredExtension: String? = nil,
                     fileManager: FileManager = .default,
                     chunkSize: Int = 1_048_576,
                     beforeCreate: ((URL) throws -> Void)? = nil,
                     afterChunk: (() throws -> Void)? = nil) throws -> URL {
        try Task.checkCancellation()
        let keys: Set<URLResourceKey> = [.fileSizeKey, .isRegularFileKey, .isSymbolicLinkKey]
        let before = try source.resourceValues(forKeys: keys)
        guard before.isRegularFile == true, before.isSymbolicLink != true,
              let size = before.fileSize, size >= 0, size <= maximumBytes else {
            throw CocoaError(.fileReadTooLarge)
        }
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = uniqueDestination(for: source, in: directory,
                                            preferredExtension: preferredExtension, fileManager: fileManager)
        var ownsDestination = false
        do {
            try beforeCreate?(destination)
            try Data().write(to: destination, options: .withoutOverwriting)
            ownsDestination = true
            let input = try FileHandle(forReadingFrom: source)
            defer { try? input.close() }
            let output = try FileHandle(forWritingTo: destination)
            defer { try? output.close() }
            let readSize = max(1, chunkSize)
            var copiedBytes = 0
            while true {
                try Task.checkCancellation()
                guard let chunk = try input.read(upToCount: readSize), !chunk.isEmpty else { break }
                guard copiedBytes <= size,
                      chunk.count <= size - copiedBytes,
                      copiedBytes <= maximumBytes,
                      chunk.count <= maximumBytes - copiedBytes else {
                    throw CocoaError(.fileReadCorruptFile)
                }
                try output.write(contentsOf: chunk)
                copiedBytes += chunk.count
                try afterChunk?()
            }
            try Task.checkCancellation()
            guard copiedBytes == size else { throw CocoaError(.fileReadCorruptFile) }
            try output.synchronize()
            let copied = try destination.resourceValues(forKeys: keys)
            guard copied.isRegularFile == true, copied.isSymbolicLink != true,
                  let copiedSize = copied.fileSize, copiedSize == size, copiedSize <= maximumBytes else {
                throw CocoaError(.fileReadCorruptFile)
            }
        } catch {
            if ownsDestination { try? fileManager.removeItem(at: destination) }
            throw error
        }
        return destination
    }

    /// Opaque on purpose: the sheet name says where your AO is, so it only ever
    /// lives sealed in the library, never in a file name.
    private static func uniqueDestination(for source: URL,
                                          in directory: URL,
                                          preferredExtension: String?,
                                          fileManager: FileManager) -> URL {
        let ext = preferredExtension ?? source.pathExtension
        while true {
            let base = directory.appendingPathComponent("map-\(UUID().uuidString.lowercased())", isDirectory: false)
            let next = ext.isEmpty ? base : base.appendingPathExtension(ext)
            if !fileManager.fileExists(atPath: next.path) { return next }
        }
    }

    static func importedMapsDirectory(fileManager: FileManager = .default) throws -> URL {
        try ImportedMapStorage.importedMapsDirectory(fileManager: fileManager)
    }
}
