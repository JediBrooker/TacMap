import Foundation

enum ExportFileSecurity {
    private static let directoryName = "TacMap-Sensitive-Exports"

    static func freshURL(fileName: String) throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(directoryName, isDirectory: true)
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.complete]
        )
        cleanup(directory: directory)
        return directory.appendingPathComponent("\(UUID().uuidString)-\(fileName)")
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

    private static func cleanup(directory: URL) {
        let keys: Set<URLResourceKey> = [.contentModificationDateKey, .isRegularFileKey]
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: Array(keys),
            options: [.skipsHiddenFiles]
        ) else { return }
        let now = Date()
        let sorted = files.sorted {
            let a = (try? $0.resourceValues(forKeys: keys).contentModificationDate) ?? .distantPast
            let b = (try? $1.resourceValues(forKeys: keys).contentModificationDate) ?? .distantPast
            return a > b
        }
        for (index, url) in sorted.enumerated() {
            let values = try? url.resourceValues(forKeys: keys)
            let stale = values?.contentModificationDate.map { now.timeIntervalSince($0) > 3_600 } ?? true
            if stale || index >= 32 { try? FileManager.default.removeItem(at: url) }
        }
    }
}
