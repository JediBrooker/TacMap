import Foundation
import Combine

/// Persistent store for drawings, grouped into layers.
///
/// Schema lives at Application Support/drawings.json as
/// `{"layers": [...], "shapes": [...]}`. Older versions wrote a bare
/// [DrawingShape] array; we still read that format - those shapes get
/// re-stamped with `DrawingLayer.legacyFallbackID` and seed layers
/// are inserted so the user sees a sane multi-layer state.
final class DrawingStore: ObservableObject {
    @Published private(set) var layers: [DrawingLayer] = []
    @Published private(set) var shapes: [DrawingShape] = []
    /// Layer for new drawings when session doesn't specify one. First
    /// visible layer, or just the first layer as fallback.
    @Published var activeLayerID: UUID?

    /// Non-nil when the drawings file was unreadable (quarantined) or a
    /// save failed. Surfaced in the UI so user knows whats going on
    /// instead of just seeing a blank map and thinking there's no data.
    @Published var loadError: String?

    /// Schema version. Bump when on-disk format changes so old files
    /// migrate instead of looking like corruption.
    private static let currentSchema = 1

    /// Set by ContentView from @Environment(\.undoManager) after view
    /// appears. Weak so we don't extend the window's lifetime.
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

    /// Undo-only removal of a layer that had no shapes (just added).
    /// Not exposed as public API - don't want to accidentally skip the
    /// shape-deletion logic in removeLayer.
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

    /// Insert layer as-is. Used by GeoJSON import so drawings referencing
    /// the imported layer id don't become orphaned.
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

    /// Inserts shape at index (undo of remove) and registers the redo
    /// so undo/redo cycle stays complete.
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
        /// nil = written before schema versioning, treat as v1. Has to be
        /// optional so existing files without this key still decode -
        /// otherwise the upgrade would fail and wipe the data. Ironic.
        var schemaVersion: Int?
        var layers: [DrawingLayer]
        var shapes: [DrawingShape]
        var activeLayerID: UUID?
    }

    /// Try decoding as current schema, fall back to legacy flat
    /// [DrawingShape] array. Throws if neither works so SafeStore
    /// quarantines instead of silently discarding.
    private static func decodeAny(_ data: Data) throws -> (Persisted, migrated: Bool) {
        let dec = JSONDecoder()
        if let payload = try? dec.decode(Persisted.self, from: data) {
            return (payload, false)
        }
        let legacyShapes = try dec.decode([DrawingShape].self, from: data)
        let migrated = Persisted(
            schemaVersion: currentSchema,
            layers: DrawingLayer.seedDefaults,
            shapes: legacyShapes.map { shape in
                var s = shape
                s.layerID = DrawingLayer.legacyFallbackID
                return s
            },
            activeLayerID: DrawingLayer.seedDefaults.first?.id
        )
        return (migrated, true)
    }

    private func load() {
        switch SafeStore.read(url, decode: { try Self.decodeAny($0) }) {
        case .loaded(let (payload, migrated)):
            layers = payload.layers
            shapes = payload.shapes
            activeLayerID = payload.activeLayerID ?? layers.first?.id
            ensureSeedLayers()
            if migrated { persist() } // upgrade the on-disk format once
        case .empty:
            seedFreshInstall()
        case .corrupt(let quarantine, _):
            // Don't clobber the unreadable file - set it aside and tell the
            // user, otherwise the next edit would persist an empty doc over
            // the only copy
            loadError = "Saved drawings could not be read and were set aside "
                + "(\(quarantine?.lastPathComponent ?? "recovery copy")). Starting with an empty map."
            seedFreshInstall()
        }
    }

    /// Fresh install - seed default layers so the user has somewhere to
    /// draw without having to create a layer first.
    private func seedFreshInstall() {
        layers = DrawingLayer.seedDefaults
        shapes = []
        activeLayerID = layers.first?.id
        persist()
    }

    /// If saved file has zero layers (user deleted them all), put the
    /// seed layers back so new drawings have somewhere to go.
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
            let payload = Persisted(schemaVersion: Self.currentSchema,
                                    layers: layers,
                                    shapes: shapes,
                                    activeLayerID: activeLayerID)
            let data = try JSONEncoder().encode(payload)
            try SafeStore.write(data, to: url)
            if loadError?.hasPrefix("Could not save") == true { loadError = nil }
        } catch {
            // don't swallow this, user is editing but nothing is hitting disk
            print("[DrawingStore] persist failed: \(error)")
            loadError = "Could not save drawings to disk: \(error.localizedDescription)"
        }
    }
}
