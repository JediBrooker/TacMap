import Foundation
import Combine

/// Persistent store for the user's drawings, grouped into layers.
///
/// Schema lives at `Application Support/drawings.json` as a single
/// `{"layers": [...], "shapes": [...]}` object. Older versions of the app
/// wrote a bare `[DrawingShape]` array; that legacy shape is still
/// readable — those shapes get re-stamped with `DrawingLayer.legacyFallbackID`
/// and the default seed layers are inserted so the user lands in a
/// recognisable multi-layer state.
final class DrawingStore: ObservableObject {
    @Published private(set) var layers: [DrawingLayer] = []
    @Published private(set) var shapes: [DrawingShape] = []
    /// Layer used for newly-started drawings when the session doesn't
    /// specify one. Defaults to the first visible layer, falling back to
    /// the first layer.
    @Published var activeLayerID: UUID?

    /// Set by ContentView from `@Environment(\.undoManager)` after the
    /// view appears. Weak so the store doesn't extend the window's lifetime.
    weak var undoManager: UndoManager?

    private let url: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                            in: .userDomainMask).first!
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("drawings.json")
    }()

    init() { load() }

    // MARK: - Layer CRUD

    func addLayer(name: String, defaultColorHex: String) -> DrawingLayer {
        let layer = DrawingLayer(name: name, defaultColorHex: defaultColorHex)
        layers.append(layer)
        if activeLayerID == nil { activeLayerID = layer.id }
        persist()
        undoManager?.registerUndo(withTarget: self) { s in s.removeLayerUndo(layer) }
        undoManager?.setActionName("Add Layer")
        return layer
    }

    /// Undo-only removal of a layer that had no shapes (i.e. was just added).
    /// Only called from the undo handler for addLayer — not exposed as a
    /// general-purpose API so we don't accidentally skip the shape-deletion
    /// logic in removeLayer.
    private func removeLayerUndo(_ layer: DrawingLayer) {
        layers.removeAll { $0.id == layer.id }
        if activeLayerID == layer.id {
            activeLayerID = layers.first(where: { $0.visible })?.id ?? layers.first?.id
        }
        persist()
        undoManager?.registerUndo(withTarget: self) { [layer] s in
            s.layers.append(layer)
            if s.activeLayerID == nil { s.activeLayerID = layer.id }
            s.persist()
            s.undoManager?.setActionName("Add Layer")
        }
        undoManager?.setActionName("Add Layer")
    }

    /// Insert a layer exactly as supplied. Used by GeoJSON import so drawings
    /// that reference the imported layer id do not become orphaned.
    func addLayerVerbatim(_ layer: DrawingLayer) {
        guard !layers.contains(where: { $0.id == layer.id }) else { return }
        layers.append(layer)
        if activeLayerID == nil { activeLayerID = layer.id }
        persist()
    }

    /// Remove a layer along with every shape on it.
    func removeLayer(_ layer: DrawingLayer) {
        layers.removeAll { $0.id == layer.id }
        shapes.removeAll { $0.layerID == layer.id }
        if activeLayerID == layer.id {
            activeLayerID = layers.first(where: { $0.visible })?.id ?? layers.first?.id
        }
        persist()
    }

    func setLayerVisible(_ layer: DrawingLayer, _ visible: Bool) {
        guard let idx = layers.firstIndex(where: { $0.id == layer.id }) else { return }
        layers[idx].visible = visible
        persist()
    }

    func renameLayer(_ layer: DrawingLayer, to newName: String) {
        guard let idx = layers.firstIndex(where: { $0.id == layer.id }) else { return }
        layers[idx].name = newName
        persist()
    }

    func updateLayerColor(_ layer: DrawingLayer, to hex: String) {
        guard let idx = layers.firstIndex(where: { $0.id == layer.id }) else { return }
        layers[idx].defaultColorHex = hex
        persist()
    }

    func layer(id: UUID) -> DrawingLayer? {
        layers.first { $0.id == id }
    }

    /// Shapes belonging to a specific layer.
    func shapes(in layerID: UUID) -> [DrawingShape] {
        shapes.filter { $0.layerID == layerID }
    }

    /// All shapes whose layer is currently visible.
    var visibleShapes: [DrawingShape] {
        let visibleIDs = Set(layers.filter { $0.visible }.map(\.id))
        return shapes.filter { visibleIDs.contains($0.layerID) }
    }

    // MARK: - Shape CRUD

    func add(_ shape: DrawingShape) {
        shapes.append(shape)
        persist()
        undoManager?.registerUndo(withTarget: self) { s in s.remove(shape) }
        undoManager?.setActionName("Add Drawing")
    }

    func update(_ shape: DrawingShape) {
        guard let idx = shapes.firstIndex(where: { $0.id == shape.id }) else { return }
        let old = shapes[idx]
        shapes[idx] = shape
        persist()
        undoManager?.registerUndo(withTarget: self) { s in s.update(old) }
        undoManager?.setActionName("Edit Drawing")
    }

    func remove(_ shape: DrawingShape) {
        guard let idx = shapes.firstIndex(where: { $0.id == shape.id }) else { return }
        let removed = shapes.remove(at: idx)
        persist()
        undoManager?.registerUndo(withTarget: self) { s in s.insertShape(removed, at: idx) }
        undoManager?.setActionName("Delete Drawing")
    }

    /// Inserts a shape at a specific index (used by undo of remove) and
    /// registers the corresponding redo so the cycle is complete.
    private func insertShape(_ shape: DrawingShape, at idx: Int) {
        shapes.insert(shape, at: min(idx, shapes.count))
        persist()
        undoManager?.registerUndo(withTarget: self) { s in s.remove(shape) }
        undoManager?.setActionName("Delete Drawing")
    }

    func removeAll() {
        shapes.removeAll()
        persist()
    }

    // MARK: - Persistence

    private struct Persisted: Codable {
        var layers: [DrawingLayer]
        var shapes: [DrawingShape]
        var activeLayerID: UUID?
    }

    private func load() {
        guard let data = try? Data(contentsOf: url) else {
            seedFreshInstall()
            return
        }

        // Try the new multi-layer schema first.
        if let payload = try? JSONDecoder().decode(Persisted.self, from: data) {
            layers = payload.layers
            shapes = payload.shapes
            activeLayerID = payload.activeLayerID ?? layers.first?.id
            ensureSeedLayers()
            return
        }

        // Fall back to the legacy flat `[DrawingShape]` schema.
        if let legacyShapes = try? JSONDecoder().decode([DrawingShape].self, from: data) {
            layers = DrawingLayer.seedDefaults
            shapes = legacyShapes.map { shape in
                var s = shape
                s.layerID = DrawingLayer.legacyFallbackID
                return s
            }
            activeLayerID = layers.first?.id
            persist()
            return
        }

        // Corrupted file — fall through to fresh seed.
        seedFreshInstall()
    }

    /// First-time install: seed the four default layers so the user has
    /// somewhere to draw on without having to create a layer first.
    private func seedFreshInstall() {
        layers = DrawingLayer.seedDefaults
        shapes = []
        activeLayerID = layers.first?.id
        persist()
    }

    /// If a saved file has zero layers (e.g. user deleted every one), put
    /// the seeds back so new drawings have a destination.
    private func ensureSeedLayers() {
        if layers.isEmpty {
            layers = DrawingLayer.seedDefaults
            activeLayerID = layers.first?.id
        }
        if activeLayerID == nil || layer(id: activeLayerID!) == nil {
            activeLayerID = layers.first?.id
        }
    }

    private func persist() {
        do {
            let payload = Persisted(layers: layers,
                                    shapes: shapes,
                                    activeLayerID: activeLayerID)
            let data = try JSONEncoder().encode(payload)
            try data.write(to: url, options: .atomic)
        } catch {
            print("[DrawingStore] persist failed: \(error)")
        }
    }
}
