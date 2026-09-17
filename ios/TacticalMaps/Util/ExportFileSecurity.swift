import Foundation

enum ExportFileSecurity {
    private static let directoryName = "TacMap-Sensitive-Exports"
    private static let staleArtifactAge: TimeInterval = 3_600
    private static let maximumRetainedArtifacts = 32

    static func freshURL(fileName: String) throws -> URL {
        let fileManager = FileManager.default
        let directory = managedDirectory(fileManager: fileManager)
        try fileManager.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.complete]
        )
        guard isManagedDirectory(directory),
              cleanup(directory: directory, fileManager: fileManager) else {
            throw CocoaError(.fileWriteNoPermission)
        }
        return directory.appendingPathComponent("\(UUID().uuidString)-\(fileName)")
    }

    /// Removes abandoned plaintext exports before the app presents mission UI.
    /// A missing directory is the normal first-launch case and is not created.
    @discardableResult
    static func purgeStaleArtifactsOnLaunch() -> Bool {
        purgeStaleArtifactsOnLaunch(
            in: managedDirectory(fileManager: .default),
            fileManager: .default
        )
    }

    /// Injectable boundary used by focused lifecycle and containment tests.
    @discardableResult
    static func purgeStaleArtifactsOnLaunch(
        in directory: URL,
        now: Date = Date(),
        fileManager: FileManager = .default
    ) -> Bool {
        var isDirectory: ObjCBool = false
        guard fileManager.fileExists(atPath: directory.path, isDirectory: &isDirectory) else {
            return true
        }
        guard isDirectory.boolValue, isManagedDirectory(directory) else {
            return false
        }
        return cleanup(directory: directory, now: now, fileManager: fileManager)
    }

    static func protect(_ url: URL) throws {
        try FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.complete],
            ofItemAtPath: url.path
        )
    }

    static func remove(_ url: URL?) {
        guard let url else { return }
        try? FileManager.default.removeItem(at: url)
    }

    private static func managedDirectory(fileManager: FileManager) -> URL {
        fileManager.temporaryDirectory
            .appendingPathComponent(directoryName, isDirectory: true)
    }

    private static func isManagedDirectory(_ directory: URL) -> Bool {
        guard directory.isFileURL,
              let values = try? directory.resourceValues(
                forKeys: [.isDirectoryKey, .isSymbolicLinkKey]
              ),
              values.isDirectory == true,
              values.isSymbolicLink != true else {
            return false
        }
        return true
    }

    @discardableResult
    private static func cleanup(
        directory: URL,
        now: Date = Date(),
        fileManager: FileManager
    ) -> Bool {
        let keys: Set<URLResourceKey> = [
            .contentModificationDateKey,
            .isRegularFileKey,
            .isSymbolicLinkKey
        ]
        guard isManagedDirectory(directory),
              let entries = try? fileManager.contentsOfDirectory(
                at: directory,
                includingPropertiesForKeys: Array(keys),
                options: [.skipsSubdirectoryDescendants, .skipsPackageDescendants]
              ) else {
            return false
        }

        var complete = true
        var regularFiles: [(url: URL, modified: Date)] = []
        let root = directory.resolvingSymlinksInPath().standardizedFileURL
        for entry in entries {
            guard entry.deletingLastPathComponent().standardizedFileURL
                    == directory.standardizedFileURL,
                  let values = try? entry.resourceValues(forKeys: keys) else {
                complete = false
                continue
            }
            if values.isSymbolicLink == true {
                do {
                    // Unlink this direct entry without traversing to its target.
                    try fileManager.removeItem(at: entry)
                } catch let error as CocoaError where error.code == .fileNoSuchFile {
                    // A concurrent completion handler already removed it.
                } catch {
                    complete = false
                }
                continue
            }
            guard values.isRegularFile == true,
                  entry.resolvingSymlinksInPath().standardizedFileURL
                    .deletingLastPathComponent() == root else {
                // Never recurse into unexpected directories or packages.
                complete = false
                continue
            }
            regularFiles.append((entry, values.contentModificationDate ?? .distantPast))
        }

        let sorted = regularFiles.sorted { $0.modified > $1.modified }
        for (index, entry) in sorted.enumerated() {
            let age = max(0, now.timeIntervalSince(entry.modified))
            guard age >= staleArtifactAge || index >= maximumRetainedArtifacts else {
                continue
            }
            do {
                try fileManager.removeItem(at: entry.url)
            } catch let error as CocoaError where error.code == .fileNoSuchFile {
                // A concurrent completion handler removed the same export.
            } catch {
                complete = false
            }
        }
        return complete
    }
}
