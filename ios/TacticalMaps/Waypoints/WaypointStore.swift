import Foundation
import Combine
import CoreLocation

/// In-memory waypoint store, persists to Application Support/waypoints.json.
final class WaypointStore: ObservableObject {
    @Published private(set) var waypoints: [Waypoint] = []

    /// Non-nil when waypoints file was unreadable (quarantined) or a save
    /// failed. Surfaced so user doesn't see an empty list and think they
    /// have no waypoints when really decode just blew up.
    @Published var loadError: String?

    /// Set by ContentView from `@Environment(\.undoManager)` after the view appears.
    weak var undoManager: UndoManager?

    private let url: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                            in: .userDomainMask).first!
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("waypoints.json")
    }()

    init() { load() }

    func add(_ wp: Waypoint) {
        waypoints.append(wp)
        persist()
        undoManager?.registerUndo(withTarget: self) { s in s.remove(wp) }
        undoManager?.setActionName("Add Waypoint")
    }

    func remove(_ wp: Waypoint) {
        guard let idx = waypoints.firstIndex(where: { $0.id == wp.id }) else { return }
        let removed = waypoints.remove(at: idx)
        persist()
        undoManager?.registerUndo(withTarget: self) { s in s.insertWaypoint(removed, at: idx) }
        undoManager?.setActionName("Delete Waypoint")
    }

    func update(_ wp: Waypoint) {
        guard let idx = waypoints.firstIndex(where: { $0.id == wp.id }) else { return }
        let old = waypoints[idx]
        waypoints[idx] = wp
        persist()
        undoManager?.registerUndo(withTarget: self) { s in s.update(old) }
        undoManager?.setActionName("Edit Waypoint")
    }

    private func insertWaypoint(_ wp: Waypoint, at idx: Int) {
        waypoints.insert(wp, at: min(idx, waypoints.count))
        persist()
        undoManager?.registerUndo(withTarget: self) { s in s.remove(wp) }
        undoManager?.setActionName("Delete Waypoint")
    }

    // MARK: - Persistence

    /// True when the store couldn't be opened because the at-rest key is locked.
    /// Nothing may be persisted while this holds or we'd write an empty list
    /// over data we simply couldn't read.
    @Published private(set) var locked = false

    private static let label = "waypoints.json"

    private func load() {
        // Fresh installs just start empty, no demo seed. We used to
        // ship a handful of "Pl, A Coy" / "Med Post" markers around
        // San Francisco so the map wasn't blank on first launch but
        // that confused real users who hadn't placed anything.
        switch SafeStore.read(url, label: Self.label, decode: { try JSONDecoder().decode([Waypoint].self, from: $0) }) {
        case .loaded(let decoded):
            waypoints = decoded
        case .empty:
            break // genuine fresh install
        case .corrupt(let quarantine, _):
            // Preserve the unreadable file rather than letting next add()
            // clobber it with a one-element list.
            loadError = "Saved waypoints could not be read and were set aside "
                + "(\(quarantine?.lastPathComponent ?? "recovery copy")). Starting with no waypoints."
        case .locked(let error):
            locked = true
            loadError = "Waypoints are encrypted and locked. \(error.localizedDescription)"
        }
    }

    private func persist() {
        guard !locked else { return }
        do {
            let data = try JSONEncoder().encode(waypoints)
            try SafeStore.write(data, to: url, label: Self.label)
            if loadError?.hasPrefix("Could not save") == true { loadError = nil }
        } catch {
            print("[WaypointStore] persist failed: \(error)")
            loadError = "Could not save waypoints to disk: \(error.localizedDescription)"
        }
    }
}
