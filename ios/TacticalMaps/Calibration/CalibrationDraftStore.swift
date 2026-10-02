import Foundation

/// Where in-progress calibrations live between app launches (contract s8.1).
protocol CalibrationDraftStoring: AnyObject {
    /// the draft for contentKey#pageIndex, nil when there's none. A draft whose
    /// embedded identity doesn't match its key is deleted, never returned (D5-10)
    func draft(contentKey: String, pageIndex: Int) throws -> CalibrationDraft?
    func save(_ draft: CalibrationDraft) throws
    func delete(contentKey: String, pageIndex: Int)
    /// every draft of a file, any page (entry delete)
    func deleteAll(contentKey: String)
    func prune(keepingContentKeys: Set<String>)
    /// the first draft still marked active (app died mid calibration), for E3
    func activeDraft() -> CalibrationDraft?
    /// points per contentKey#page, Layers shows them as a subtitle
    func summaries() -> [String: Int]
}

enum CalibrationDraftError: Error {
    case locked
}

/// One sealed file, Application Support/calibration-drafts.json, label
/// calibration/drafts_v1. Written synchronously on every mutation (a couple of
/// ms at 50 points), never deferred: the data key can lock any time.
final class CalibrationDraftStore: CalibrationDraftStoring {
    static let shared = CalibrationDraftStore()
    static let label = "calibration/drafts_v1"

    private struct File: Codable {
        var schemaVersion = 1
        var drafts: [String: CalibrationDraft] = [:]
    }

    private let urlProvider: () -> URL
    private let lock = NSLock()

    init(url: (() -> URL)? = nil) {
        urlProvider = url ?? { ImportedMapStorage.applicationSupportDirectory().appendingPathComponent("calibration-drafts.json") }
    }

    private func read() throws -> File {
        switch SafeStore.read(urlProvider(), label: Self.label, decode: { try JSONDecoder().decode(File.self, from: $0) }) {
        case .empty: return File()
        case .loaded(let f): return f
        // a corrupt file got quarantined by SafeStore, start clean
        case .corrupt: return File()
        case .locked: throw CalibrationDraftError.locked
        }
    }

    private func write(_ f: File) throws {
        let url = urlProvider()
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if f.drafts.isEmpty {
            try? FileManager.default.removeItem(at: url)
            return
        }
        try SafeStore.write(JSONEncoder().encode(f), to: url, label: Self.label)
    }

    func draft(contentKey: String, pageIndex: Int) throws -> CalibrationDraft? {
        lock.lock(); defer { lock.unlock() }
        var f = try read()
        let key = CalibrationDraft.key(contentKey: contentKey, pageIndex: pageIndex)
        guard let d = f.drafts[key] else { return nil }
        guard d.contentKey == contentKey, d.pageIndex == pageIndex, d.key == key else {
            f.drafts[key] = nil
            try? write(f)
            return nil
        }
        return d
    }

    func save(_ draft: CalibrationDraft) throws {
        lock.lock(); defer { lock.unlock() }
        var f = try read()
        f.drafts[draft.key] = draft
        // LRU by updatedAt, 16 max
        if f.drafts.count > CalibrationLimits.maxDrafts {
            let keep = f.drafts.values.sorted { $0.updatedAtMs > $1.updatedAtMs }.prefix(CalibrationLimits.maxDrafts)
            f.drafts = Dictionary(uniqueKeysWithValues: keep.map { ($0.key, $0) })
        }
        try write(f)
    }

    func delete(contentKey: String, pageIndex: Int) {
        lock.lock(); defer { lock.unlock() }
        guard var f = try? read() else { return }
        f.drafts[CalibrationDraft.key(contentKey: contentKey, pageIndex: pageIndex)] = nil
        try? write(f)
    }

    func deleteAll(contentKey: String) {
        lock.lock(); defer { lock.unlock() }
        guard var f = try? read() else { return }
        f.drafts = f.drafts.filter { $0.value.contentKey != contentKey }
        try? write(f)
    }

    func prune(keepingContentKeys keys: Set<String>) {
        lock.lock(); defer { lock.unlock() }
        guard var f = try? read() else { return }
        let before = f.drafts.count
        f.drafts = f.drafts.filter { keys.contains($0.value.contentKey) }
        if f.drafts.count != before { try? write(f) }
    }

    func activeDraft() -> CalibrationDraft? {
        lock.lock(); defer { lock.unlock() }
        return (try? read())?.drafts.values.filter(\.active).max { $0.updatedAtMs < $1.updatedAtMs }
    }

    func summaries() -> [String: Int] {
        lock.lock(); defer { lock.unlock() }
        return ((try? read())?.drafts ?? [:]).mapValues { $0.points.count }
    }
}

/// In-memory store for tests and previews.
final class InMemoryCalibrationDraftStore: CalibrationDraftStoring {
    var drafts: [String: CalibrationDraft] = [:]
    var locked = false
    private(set) var writes = 0

    func draft(contentKey: String, pageIndex: Int) throws -> CalibrationDraft? {
        if locked { throw CalibrationDraftError.locked }
        return drafts[CalibrationDraft.key(contentKey: contentKey, pageIndex: pageIndex)]
    }

    func save(_ draft: CalibrationDraft) throws {
        if locked { throw CalibrationDraftError.locked }
        writes += 1
        drafts[draft.key] = draft
    }

    func delete(contentKey: String, pageIndex: Int) { drafts[CalibrationDraft.key(contentKey: contentKey, pageIndex: pageIndex)] = nil }
    func deleteAll(contentKey: String) { drafts = drafts.filter { $0.value.contentKey != contentKey } }
    func prune(keepingContentKeys keys: Set<String>) { drafts = drafts.filter { keys.contains($0.value.contentKey) } }
    func activeDraft() -> CalibrationDraft? { drafts.values.first { $0.active } }
    func summaries() -> [String: Int] { drafts.mapValues { $0.points.count } }
}
