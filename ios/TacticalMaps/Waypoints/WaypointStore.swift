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
            return L10n.text("Mission data is locked. Unlock it before importing.")
        case .persistenceFailed(let error):
            return L10n.text("The import could not be saved: %1$@", error.localizedDescription)
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
            return L10n.text("Default mission layers cannot be renamed, recoloured, or deleted.")
        case .legacyProtectionReview:
            return L10n.text("This layer predates default-layer provenance. Confirm whether it is a custom layer before renaming, recolouring, or deleting it.")
        case .layerMissing:
            return L10n.text("That layer no longer exists. Refresh the layer list and try again.")
        case .fallbackMissing:
            return L10n.text("The default fallback layer is unavailable. Restore the default layers before deleting this layer.")
        case .invalidName:
            return L10n.text("Enter a non-empty layer name.")
        case .invalidColor:
            return L10n.text("Choose a valid six-digit layer colour.")
        case .locked(let store):
            return L10n.text("Mission %1$@ are locked. Unlock mission data, then try again.", store)
        case .persistenceFailed(let store, let underlying):
            return L10n.text("Could not save mission %1$@: %2$@. No unsafe layer change was published; try again.", store, underlying.localizedDescription)
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
            return L10n.text("Mission waypoints are locked. Unlock mission data, then try again.")
        case .missing:
            return L10n.text("That symbol no longer exists. Close the editor and try again.")
        case .persistenceFailed(let error):
            return L10n.text("The symbol change could not be saved: %1$@", error.localizedDescription)
        }
    }
}

/// In-memory waypoint store, persists to Application Support/waypoints.json.
final class WaypointStore: ObservableObject {
    @Published private(set) var waypoints: [Waypoint] = []

    /// Non-nil when waypoints file was unreadable (quarantined) or a save
    /// failed. Surfaced so user doesn't see an empty list and think they
    /// have no waypoints when really decode just blew up.
    // Message identity controls recovery; translated wording never controls state.
    private static let saveErrorIDs: Set<String> = [
        "id.ui_could_not_save_imported_waypoints_to_disk_1_2890a324",
        "id.ui_could_not_save_new_waypoint_to_disk_1_ea209d9a",
        "id.ui_could_not_save_reassigned_waypoints_to_disk_1_a62f01a5",
        "id.ui_could_not_save_waypoint_change_to_disk_1_9ef95837",
    ]

    @Published private var pendingLoadError: LocalizedMessage?
    var loadError: String? {
        get { pendingLoadError?.text }
        set { pendingLoadError = newValue.map(LocalizedMessage.literal) }
    }

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
            pendingLoadError = Messages.couldNotSaveNewWaypointToDiskMessage(error.localizedDescription)
            throw WaypointMutationError.persistenceFailed(error)
        }
        waypoints = candidate
        if pendingLoadError?.id == "id.ui_could_not_save_new_waypoint_to_disk_1_ea209d9a" { loadError = nil }
        undoManager?.registerUndo(withTarget: self) { store in
            _ = try? store.deleteDurably(waypoint)
        }
        undoManager?.setActionName(L10n.text("Add Waypoint"))
        return true
    }

    /// Persists an edited candidate before publishing it to UI, undo, or Sync.
    /// Returns false when the candidate is unchanged and performs no write.
    @discardableResult
    func commitEdit(_ waypoint: Waypoint, actionName: String = L10n.text("Edit Waypoint")) throws -> Bool {
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
            pendingLoadError = Messages.couldNotSaveWaypointChangeToDiskMessage(error.localizedDescription)
            throw WaypointMutationError.persistenceFailed(error)
        }
        waypoints = candidate
        if pendingLoadError?.id == "id.ui_could_not_save_waypoint_change_to_disk_1_9ef95837" { loadError = nil }
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
            pendingLoadError = Messages.couldNotDeleteWaypointFromDiskMessage(error.localizedDescription)
            throw WaypointMutationError.persistenceFailed(error)
        }
        waypoints = candidate
        if pendingLoadError?.id == "id.ui_could_not_delete_waypoint_from_disk_1_dec5b1a5" { loadError = nil }
        undoManager?.registerUndo(withTarget: self) { store in
            _ = try? store.restoreDurably(removed, at: index)
        }
        undoManager?.setActionName(L10n.text("Delete Waypoint"))
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
            pendingLoadError = Messages.couldNotRestoreWaypointToDiskMessage(error.localizedDescription)
            throw WaypointMutationError.persistenceFailed(error)
        }
        waypoints = candidate
        if pendingLoadError?.id == "id.ui_could_not_restore_waypoint_to_disk_1_216a74a6" { loadError = nil }
        undoManager?.registerUndo(withTarget: self) { store in
            _ = try? store.deleteDurably(waypoint)
        }
        undoManager?.setActionName(L10n.text("Delete Waypoint"))
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
            pendingLoadError = Messages.couldNotSaveReassignedWaypointsToDiskMessage(error.localizedDescription)
            throw MissionLayerMutationError.persistenceFailed(store: "waypoints", underlying: error)
        }
        waypoints = candidate
        if pendingLoadError?.id.map(Self.saveErrorIDs.contains) == true { loadError = nil }
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
            pendingLoadError = Messages.couldNotSaveImportedWaypointsToDiskMessage(error.localizedDescription)
            throw BatchImportStoreError.persistenceFailed(error)
        }

        // Publish and register undo only after the candidate is durable. Sync's
        // model observers therefore cannot advertise data that failed to save.
        waypoints = candidate
        if pendingLoadError?.id.map(Self.saveErrorIDs.contains) == true { loadError = nil }
        let insertedIDs = Set(additions.map(\.id))
        undoManager?.registerUndo(withTarget: self) { store in
            store.removeImportedBatch(ids: insertedIDs, batchKey: batchKey)
        }
        undoManager?.setActionName(L10n.text("Import Waypoints"))
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
            undoManager?.setActionName(L10n.text("Import Waypoints"))
        } catch {
            pendingLoadError = Messages.couldNotUndoImportedWaypointsMessage(error.localizedDescription)
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
            pendingLoadError = Messages.waypointsQuarantinedMessage(quarantine?.lastPathComponent ?? Messages.recoveryCopyFallback())
        case .locked(let error):
            locked = true
            pendingLoadError = Messages.waypointsAreEncryptedAndLockedMessage(error.localizedDescription)
        }
    }

    private func write(_ candidate: [Waypoint]) throws {
        let data = try JSONEncoder().encode(candidate)
        try persistenceWriter(data, url, Self.label)
    }
}
