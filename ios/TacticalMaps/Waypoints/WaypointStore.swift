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
        guard let data = try? Data(contentsOf: url) else {
            seedDemoIfEmpty()
            return
        }
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

    /// Seed the mockup waypoints so a first run looks like the design.
    private func seedDemoIfEmpty() {
        waypoints = [
            Waypoint(name: "Camp Alpha",        latitude: 37.7820, longitude: -122.4310, elevation: 2345, kind: .camp),
            Waypoint(name: "Water Source",      latitude: 37.7750, longitude: -122.4250, elevation: 1856, kind: .water),
            Waypoint(name: "Observation Point", latitude: 37.7790, longitude: -122.4080, elevation: 2120, kind: .observation),
            Waypoint(name: "Drop Zone",         latitude: 37.7730, longitude: -122.4140, elevation: 1620, kind: .dropZone)
        ]
        persist()
    }
}
