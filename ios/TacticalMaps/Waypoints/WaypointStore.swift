import Foundation
import Combine
import CoreLocation

/// In-memory waypoint store with disk persistence to Application Support/waypoints.json.
final class WaypointStore: ObservableObject {
    @Published private(set) var waypoints: [Waypoint] = []

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

    private func load() {
        // Fresh installs start with an empty waypoint list — no demo
        // seed. Previously we shipped a handful of "Pl, A Coy" /
        // "Med Post" markers around San Francisco so the map wasn't
        // blank on first launch, but that confused real users who
        // hadn't placed anything yet.
        guard let data = try? Data(contentsOf: url) else { return }
        if let decoded = try? JSONDecoder().decode([Waypoint].self, from: data) {
            waypoints = decoded
        }
    }

    private func persist() {
        do {
            let data = try JSONEncoder().encode(waypoints)
            try data.write(to: url, options: .atomic)
        } catch {
            print("[WaypointStore] persist failed: \(error)")
        }
    }
}
