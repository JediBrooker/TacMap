import Foundation
import Combine
import CoreLocation

/// In-memory waypoint store with disk persistence to Application Support/waypoints.json.
final class WaypointStore: ObservableObject {
    @Published private(set) var waypoints: [Waypoint] = []

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
    }

    func remove(_ wp: Waypoint) {
        waypoints.removeAll { $0.id == wp.id }
        persist()
    }

    func update(_ wp: Waypoint) {
        guard let idx = waypoints.firstIndex(where: { $0.id == wp.id }) else { return }
        waypoints[idx] = wp
        persist()
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
