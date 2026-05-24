import Foundation
import Combine

/// Persistent store for the user's saved drawings. Mirrors `WaypointStore`.
final class DrawingStore: ObservableObject {
    @Published private(set) var shapes: [DrawingShape] = []

    private let url: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                            in: .userDomainMask).first!
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("drawings.json")
    }()

    init() { load() }

    func add(_ shape: DrawingShape) {
        shapes.append(shape)
        persist()
    }

    func update(_ shape: DrawingShape) {
        guard let idx = shapes.firstIndex(where: { $0.id == shape.id }) else { return }
        shapes[idx] = shape
        persist()
    }

    func remove(_ shape: DrawingShape) {
        shapes.removeAll { $0.id == shape.id }
        persist()
    }

    func removeAll() {
        shapes.removeAll()
        persist()
    }

    private func load() {
        guard let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode([DrawingShape].self, from: data) else { return }
        shapes = decoded
    }

    private func persist() {
        do {
            let data = try JSONEncoder().encode(shapes)
            try data.write(to: url, options: .atomic)
        } catch {
            print("[DrawingStore] persist failed: \(error)")
        }
    }
}
