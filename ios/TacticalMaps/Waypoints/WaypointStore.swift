import Foundation
import Combine
import CoreLocation

struct BatchImportCommit: Equatable {
    let insertedCount: Int
    let skippedExistingCount: Int
}

enum BatchImportStoreError: LocalizedError {
    case locked
    case persistenceFailed(Error)

    var errorDescription: String? {
        switch self {
        case .locked:
            return "Mission data is locked. Unlock it before importing."
        case .persistenceFailed(let error):
            return "The import could not be saved: \(error.localizedDescription)"
        }
    }
}

enum MissionLayerMutationError: LocalizedError {
    case protectedDefault
    case legacyProtectionReview
    case layerMissing
    case fallbackMissing
    case invalidName
    case invalidColor
    case locked(store: String)
    case persistenceFailed(store: String, underlying: Error)

    var errorDescription: String? {
        switch self {
        case .protectedDefault:
            return "Default mission layers cannot be renamed, recoloured, or deleted."
        case .legacyProtectionReview:
            return "This layer predates default-layer provenance. Confirm whether it is a custom layer before renaming, recolouring, or deleting it."
        case .layerMissing:
            return "That layer no longer exists. Refresh the layer list and try again."
        case .fallbackMissing:
            return "The default fallback layer is unavailable. Restore the default layers before deleting this layer."
        case .invalidName:
            return "Enter a non-empty layer name."
        case .invalidColor:
            return "Choose a valid six-digit layer colour."
        case .locked(let store):
            return "Mission \(store) are locked. Unlock mission data, then try again."
        case .persistenceFailed(let store, let underlying):
            return "Could not save mission \(store): \(underlying.localizedDescription). No unsafe layer change was published; try again."
        }
    }
}

enum WaypointMutationError: LocalizedError {
    case locked
    case missing
    case persistenceFailed(Error)

    var errorDescription: String? {
        switch self {
        case .locked:
            return "Mission waypoints are locked. Unlock mission data, then try again."
        case .missing:
            return "That symbol no longer exists. Close the editor and try again."
        case .persistenceFailed(let error):
            return "The symbol change could not be saved: \(error.localizedDescription)"
        }
    }
}

/// In-memory waypoint store, persists to Application Support/waypoints.json.
final class WaypointStore: ObservableObject {
    @Published private(set) var waypoints: [Waypoint] = []

    /// Non-nil when waypoints file was unreadable (quarantined) or a save
    /// failed. Surfaced so user doesn't see an empty list and think they
    /// have no waypoints when really decode just blew up.
    @Published var loadError: String?

    /// Set by ContentView from `@Environment(\.undoManager)` after the view appears.
    weak var undoManager: UndoManager?

    typealias PersistenceWriter = (Data, URL, String) throws -> Void

    private static let defaultURL: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                            in: .userDomainMask).first!
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("waypoints.json")
    }()
    private let url: URL
    private let persistenceWriter: PersistenceWriter

    init(storageURL: URL? = nil,
         persistenceWriter: @escaping PersistenceWriter = { data, url, label in
             try SafeStore.write(data, to: url, label: label)
         }) {
        self.url = storageURL ?? Self.defaultURL
        self.persistenceWriter = persistenceWriter
        load()
    }

    /// Persists a new symbol before publishing it to UI, undo, or Sync.
    /// Duplicate IDs are a no-op so a retried presentation cannot duplicate data.
    @discardableResult
    func addDurably(_ waypoint: Waypoint) throws -> Bool {
        guard !locked else { throw WaypointMutationError.locked }
        guard !waypoints.contains(where: { $0.id == waypoint.id }) else { return false }
        let candidate = waypoints + [waypoint]
        do {
            try write(candidate)
        } catch {
            loadError = "Could not save new waypoint to disk: \(error.localizedDescription)"
            throw WaypointMutationError.persistenceFailed(error)
        }
        waypoints = candidate
        if loadError?.hasPrefix("Could not save new waypoint") == true { loadError = nil }
        undoManager?.registerUndo(withTarget: self) { store in
            _ = try? store.deleteDurably(waypoint)
        }
        undoManager?.setActionName("Add Waypoint")
        return true
    }

    /// Persists an edited candidate before publishing it to UI, undo, or Sync.
    /// Returns false when the candidate is unchanged and performs no write.
    @discardableResult
    func commitEdit(_ waypoint: Waypoint, actionName: String = "Edit Waypoint") throws -> Bool {
        guard !locked else { throw WaypointMutationError.locked }
        guard let index = waypoints.firstIndex(where: { $0.id == waypoint.id }) else {
            throw WaypointMutationError.missing
        }
        let old = waypoints[index]
        guard old != waypoint else { return false }
        var candidate = waypoints
        candidate[index] = waypoint
        do {
            try write(candidate)
        } catch {
            loadError = "Could not save waypoint change to disk: \(error.localizedDescription)"
            throw WaypointMutationError.persistenceFailed(error)
        }
        waypoints = candidate
        if loadError?.hasPrefix("Could not save waypoint change") == true { loadError = nil }
        undoManager?.registerUndo(withTarget: self) { store in
            _ = try? store.commitEdit(old, actionName: actionName)
        }
        undoManager?.setActionName(actionName)
        return true
    }

    /// Confirmed editor deletion with the same durable-before-publish ordering.
    @discardableResult
    func deleteDurably(_ waypoint: Waypoint) throws -> Bool {
        guard !locked else { throw WaypointMutationError.locked }
        guard let index = waypoints.firstIndex(where: { $0.id == waypoint.id }) else {
            throw WaypointMutationError.missing
        }
        var candidate = waypoints
        let removed = candidate.remove(at: index)
        do {
            try write(candidate)
        } catch {
            loadError = "Could not delete waypoint from disk: \(error.localizedDescription)"
            throw WaypointMutationError.persistenceFailed(error)
        }
        waypoints = candidate
        if loadError?.hasPrefix("Could not delete waypoint") == true { loadError = nil }
        undoManager?.registerUndo(withTarget: self) { store in
            _ = try? store.restoreDurably(removed, at: index)
        }
        undoManager?.setActionName("Delete Waypoint")
        return true
    }

    @discardableResult
    private func restoreDurably(_ waypoint: Waypoint, at index: Int) throws -> Bool {
        guard !locked else { throw WaypointMutationError.locked }
        guard !waypoints.contains(where: { $0.id == waypoint.id }) else { return false }
        var candidate = waypoints
        candidate.insert(waypoint, at: min(index, candidate.count))
        do {
            try write(candidate)
        } catch {
            loadError = "Could not restore waypoint to disk: \(error.localizedDescription)"
            throw WaypointMutationError.persistenceFailed(error)
        }
        waypoints = candidate
        if loadError?.hasPrefix("Could not restore waypoint") == true { loadError = nil }
        undoManager?.registerUndo(withTarget: self) { store in
            _ = try? store.deleteDurably(waypoint)
        }
        undoManager?.setActionName("Delete Waypoint")
        return true
    }

    /// Durably moves every waypoint off a layer before publishing the candidate.
    /// A no-op retry does not rewrite the store.
    @discardableResult
    func reassignLayer(from sourceLayerID: UUID, to fallbackLayerID: UUID) throws -> Int {
        guard !locked else { throw MissionLayerMutationError.locked(store: "waypoints") }
        let affected = waypoints.filter { $0.layerID == sourceLayerID }.count
        guard affected > 0 else { return 0 }
        let candidate = waypoints.map { waypoint -> Waypoint in
            guard waypoint.layerID == sourceLayerID else { return waypoint }
            var moved = waypoint
            moved.layerID = fallbackLayerID
            return moved
        }
        do {
            try write(candidate)
        } catch {
            loadError = "Could not save reassigned waypoints to disk: \(error.localizedDescription)"
            throw MissionLayerMutationError.persistenceFailed(store: "waypoints", underlying: error)
        }
        waypoints = candidate
        if loadError?.hasPrefix("Could not save") == true { loadError = nil }
        return affected
    }

    /// Commits one external-import waypoint batch with a single durable write.
    /// Existing IDs are skipped so retrying a partially applied cross-store
    /// import cannot duplicate the already committed half.
    @discardableResult
    func importBatch(_ imported: [Waypoint], batchKey: String) throws -> BatchImportCommit {
        guard !locked else { throw BatchImportStoreError.locked }

        var occupied = Set(waypoints.map(\.id))
        var additions: [Waypoint] = []
        var skipped = 0
        for waypoint in imported {
            if occupied.insert(waypoint.id).inserted {
                additions.append(waypoint)
            } else {
                skipped += 1
            }
        }
        guard !additions.isEmpty else {
            return BatchImportCommit(insertedCount: 0, skippedExistingCount: skipped)
        }

        let candidate = waypoints + additions
        do {
            try write(candidate)
        } catch {
            loadError = "Could not save imported waypoints to disk: \(error.localizedDescription)"
            throw BatchImportStoreError.persistenceFailed(error)
        }

        // Publish and register undo only after the candidate is durable. Sync's
        // model observers therefore cannot advertise data that failed to save.
        waypoints = candidate
        if loadError?.hasPrefix("Could not save") == true { loadError = nil }
        let insertedIDs = Set(additions.map(\.id))
        undoManager?.registerUndo(withTarget: self) { store in
            store.removeImportedBatch(ids: insertedIDs, batchKey: batchKey)
        }
        undoManager?.setActionName("Import Waypoints")
        return BatchImportCommit(insertedCount: additions.count, skippedExistingCount: skipped)
    }

    private func removeImportedBatch(ids: Set<UUID>, batchKey: String) {
        let removed = waypoints.filter { ids.contains($0.id) }
        guard !removed.isEmpty else { return }
        let candidate = waypoints.filter { !ids.contains($0.id) }
        do {
            try write(candidate)
            waypoints = candidate
            undoManager?.registerUndo(withTarget: self) { store in
                _ = try? store.importBatch(removed, batchKey: batchKey)
            }
            undoManager?.setActionName("Import Waypoints")
        } catch {
            loadError = "Could not undo imported waypoints: \(error.localizedDescription)"
        }
    }

    // MARK: - Persistence

    /// True when the store couldn't be opened because the at-rest key is locked.
    /// Nothing may be persisted while this holds or we'd write an empty list
    /// over data we simply couldn't read.
    @Published private(set) var locked = false

    func reloadAfterUnlock() {
        guard locked else { return }
        locked = false
        loadError = nil
        load()
    }

    private static let label = "waypoints.json"

    private func load() {
        // Fresh installs just start empty, no demo seed. We used to
        // ship a handful of "Pl, A Coy" / "Med Post" markers around
        // San Francisco so the map wasn't blank on first launch but
        // that confused real users who hadn't placed anything.
        switch SafeStore.read(url, label: Self.label, decode: { try JSONDecoder().decode([Waypoint].self, from: $0) }) {
        case .loaded(let decoded):
            waypoints = decoded
            locked = false
        case .empty:
            break // genuine fresh install
        case .corrupt(let quarantine, _):
            // Preserve the unreadable file rather than letting the next write
            // clobber it with a one-element list.
            loadError = "Saved waypoints could not be read and were set aside "
                + "(\(quarantine?.lastPathComponent ?? "recovery copy")). Starting with no waypoints."
        case .locked(let error):
            locked = true
            loadError = "Waypoints are encrypted and locked. \(error.localizedDescription)"
        }
    }

    private func write(_ candidate: [Waypoint]) throws {
        let data = try JSONEncoder().encode(candidate)
        try persistenceWriter(data, url, Self.label)
    }
}
