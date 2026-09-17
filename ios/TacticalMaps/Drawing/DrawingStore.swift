import Foundation
import Combine

struct DrawingBatchImportCommit: Equatable {
    let insertedLayerCount: Int
    let insertedDrawingCount: Int
    let skippedExistingDrawingCount: Int
}

struct LayerDeletionCommit: Equatable {
    let reassignedWaypointCount: Int
    let reassignedDrawingCount: Int
    let fallbackLayerID: UUID
}

enum DrawingMutationError: LocalizedError {
    case locked
    case missing
    case persistenceFailed(Error)

    var errorDescription: String? {
        switch self {
        case .locked:
            return L10n.text("Mission drawings are locked. Unlock mission data, then try again.")
        case .missing:
            return L10n.text("That drawing no longer exists. Close its controls and try again.")
        case .persistenceFailed(let error):
            return L10n.text("The drawing change could not be saved: %1$@", error.localizedDescription)
        }
    }
}

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

    /// True when the store couldn't be opened because the at-rest key is locked.
    /// Blocks persist() so an empty doc never lands on readable-but-locked data.
    @Published private(set) var locked = false

    func reloadAfterUnlock() {
        guard locked else { return }
        locked = false
        loadError = nil
        load()
    }

    /// Schema version. Bump when on-disk format changes so old files
    /// migrate instead of looking like corruption.
    private static let currentSchema = 3

    /// Bound in as AEAD associated data.
    private static let label = "drawings.json"

    /// Set by ContentView from @Environment(\.undoManager) after view
    /// appears. Weak so we don't extend the window's lifetime.
    weak var undoManager: UndoManager?

    typealias PersistenceWriter = (Data, URL, String) throws -> Void

    private static let defaultURL: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                            in: .userDomainMask).first!
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("drawings.json")
    }()
    private let url: URL
    private let persistenceWriter: PersistenceWriter
    private var protectedDefaultLayerIDs: Set<UUID> = []
    private var unresolvedLegacyLayerIDs: Set<UUID> = []
    private var protectionBasis: DefaultLayerProtectionProvenance.Basis = .deterministicIDs

    init(storageURL: URL? = nil,
         persistenceWriter: @escaping PersistenceWriter = { data, url, label in
             try SafeStore.write(data, to: url, label: label)
         }) {
        self.url = storageURL ?? Self.defaultURL
        self.persistenceWriter = persistenceWriter
        load()
    }

    // MARK: - Layer CRUD

    func addLayer(name: String, defaultColorHex: String) throws -> DrawingLayer {
        guard !locked else { throw MissionLayerMutationError.locked(store: "drawings") }
        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanName.isEmpty else { throw MissionLayerMutationError.invalidName }
        guard Self.isValidHexColor(defaultColorHex) else {
            throw MissionLayerMutationError.invalidColor
        }
        let layer = DrawingLayer(name: cleanName, defaultColorHex: defaultColorHex.uppercased())
        let candidateLayers = layers + [layer]
        let candidateActive = activeLayerID ?? layer.id
        try commitLayerCandidate(candidateLayers, activeLayerID: candidateActive,
                                 failureContext: L10n.text("new layer"))
        undoManager?.registerUndo(withTarget: self) { s in s.removeLayerUndo(layer) }
        undoManager?.setActionName(L10n.text("Add Layer"))
        return layer
    }

    /// Undo-only removal of a layer that had no shapes (just added).
    /// Not exposed as public API - don't want to accidentally skip the
    /// shape-deletion logic in removeLayer.
    private func removeLayerUndo(_ layer: DrawingLayer) {
        let candidateLayers = layers.filter { $0.id != layer.id }
        let candidateActive = activeLayerID == layer.id
            ? (candidateLayers.first(where: { $0.visible })?.id ?? candidateLayers.first?.id)
            : activeLayerID
        do {
            try commitLayerCandidate(candidateLayers, activeLayerID: candidateActive,
                                     failureContext: L10n.text("layer undo"))
            undoManager?.registerUndo(withTarget: self) { [layer] s in
                _ = try? s.addLayerVerbatim(layer)
                s.undoManager?.setActionName(L10n.text("Add Layer"))
            }
            undoManager?.setActionName(L10n.text("Add Layer"))
        } catch {
            loadError = L10n.text("Could not undo the new layer: %1$@", error.localizedDescription)
        }
    }

    /// Insert layer as-is. Used by GeoJSON import so drawings referencing
    /// the imported layer id don't become orphaned.
    @discardableResult
    func addLayerVerbatim(_ layer: DrawingLayer) throws -> Bool {
        guard !locked else { throw MissionLayerMutationError.locked(store: "drawings") }
        guard !layers.contains(where: { $0.id == layer.id }) else { return false }
        let candidateLayers = layers + [layer]
        try commitLayerCandidate(candidateLayers,
                                 activeLayerID: activeLayerID ?? layer.id,
                                 failureContext: L10n.text("imported layer"))
        return true
    }

    /// Adds any missing authenticated/imported layer identities in one durable
    /// write. Existing IDs are retained verbatim so a retried remote model apply
    /// is idempotent and cannot publish a layer that failed to reach disk.
    @discardableResult
    func addLayersVerbatimDurably(_ incoming: [DrawingLayer]) throws -> Int {
        guard !locked else { throw MissionLayerMutationError.locked(store: "drawings") }
        var occupied = Set(layers.map(\.id))
        let additions = incoming.filter { occupied.insert($0.id).inserted }
        guard !additions.isEmpty else { return 0 }
        let candidateLayers = layers + additions
        try commitLayerCandidate(candidateLayers,
                                 activeLayerID: activeLayerID ?? candidateLayers.first?.id,
                                 failureContext: L10n.text("synced layers"))
        return additions.count
    }

    func isProtectedDefaultLayer(_ layer: DrawingLayer) -> Bool {
        protectedDefaultLayerIDs.contains(layer.id)
    }

    func needsLegacyProtectionReview(_ layer: DrawingLayer) -> Bool {
        unresolvedLegacyLayerIDs.contains(layer.id)
    }

    /// Resolves an intentionally-conservative schema-v1 migration. The old
    /// document did not persist default IDs, so an ambiguous layer stays
    /// non-destructively locked until the user explicitly classifies it.
    func resolveLegacyProtectionReview(_ layer: DrawingLayer,
                                       asProtectedDefault: Bool) throws {
        guard unresolvedLegacyLayerIDs.contains(layer.id) else { return }
        var candidateUnresolved = unresolvedLegacyLayerIDs
        candidateUnresolved.remove(layer.id)
        var candidateProtected = protectedDefaultLayerIDs
        if asProtectedDefault { candidateProtected.insert(layer.id) }
        try write(layers: layers,
                  shapes: shapes,
                  activeLayerID: activeLayerID,
                  protectedDefaultLayerIDs: candidateProtected,
                  unresolvedLegacyLayerIDs: candidateUnresolved,
                  protectionBasis: protectionBasis)
        unresolvedLegacyLayerIDs = candidateUnresolved
        protectedDefaultLayerIDs = candidateProtected
        clearLayerSaveError()
    }

    /// Deletes only a custom layer. Waypoints are made durable first, then the
    /// drawing document reassigns shapes and removes the layer in one write.
    /// If the second store fails, the layer remains and retry is idempotent.
    @discardableResult
    func removeLayer(_ layer: DrawingLayer,
                     reassigningWaypointsIn waypointStore: WaypointStore) throws -> LayerDeletionCommit {
        guard !locked else { throw MissionLayerMutationError.locked(store: "drawings") }
        guard layers.contains(where: { $0.id == layer.id }) else {
            throw MissionLayerMutationError.layerMissing
        }
        guard !isProtectedDefaultLayer(layer) else {
            throw MissionLayerMutationError.protectedDefault
        }
        guard !needsLegacyProtectionReview(layer) else {
            throw MissionLayerMutationError.legacyProtectionReview
        }
        let fallbackID = DrawingLayer.legacyFallbackID
        guard fallbackID != layer.id,
              layers.contains(where: { $0.id == fallbackID }) else {
            throw MissionLayerMutationError.fallbackMissing
        }

        let waypointCount = try waypointStore.reassignLayer(from: layer.id, to: fallbackID)
        let drawingCount = shapes.filter { $0.layerID == layer.id }.count
        let candidateLayers = layers.filter { $0.id != layer.id }
        let candidateShapes = shapes.map { shape -> DrawingShape in
            guard shape.layerID == layer.id else { return shape }
            var moved = shape
            moved.layerID = fallbackID
            return moved
        }
        let candidateActive = activeLayerID == layer.id
            ? fallbackID
            : activeLayerID
        do {
            try write(layers: candidateLayers,
                      shapes: candidateShapes,
                      activeLayerID: candidateActive)
        } catch {
            loadError = L10n.text("Could not save reassigned drawings to disk: %1$@", error.localizedDescription)
            throw MissionLayerMutationError.persistenceFailed(store: "drawings", underlying: error)
        }
        layers = candidateLayers
        shapes = candidateShapes
        activeLayerID = candidateActive
        if loadError?.hasPrefix(L10n.text("Could not save")) == true { loadError = nil }
        return LayerDeletionCommit(reassignedWaypointCount: waypointCount,
                                   reassignedDrawingCount: drawingCount,
                                   fallbackLayerID: fallbackID)
    }

    func setLayerVisible(_ layer: DrawingLayer, _ visible: Bool) throws {
        guard !locked else { throw MissionLayerMutationError.locked(store: "drawings") }
        guard let idx = layers.firstIndex(where: { $0.id == layer.id }) else {
            throw MissionLayerMutationError.layerMissing
        }
        guard layers[idx].visible != visible else { return }
        var candidate = layers
        candidate[idx].visible = visible
        try commitLayerCandidate(candidate, activeLayerID: activeLayerID,
                                 failureContext: L10n.text("layer visibility"))
    }

    /// Renames and recolours in one candidate document and one durable write.
    /// Defaults are immutable; visibility remains independently editable.
    func updateLayer(_ layer: DrawingLayer,
                     name: String,
                     defaultColorHex: String) throws {
        guard !locked else { throw MissionLayerMutationError.locked(store: "drawings") }
        guard let idx = layers.firstIndex(where: { $0.id == layer.id }) else {
            throw MissionLayerMutationError.layerMissing
        }
        guard !isProtectedDefaultLayer(layer) else {
            throw MissionLayerMutationError.protectedDefault
        }
        guard !needsLegacyProtectionReview(layer) else {
            throw MissionLayerMutationError.legacyProtectionReview
        }
        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanName.isEmpty else { throw MissionLayerMutationError.invalidName }
        guard Self.isValidHexColor(defaultColorHex) else {
            throw MissionLayerMutationError.invalidColor
        }
        var candidate = layers
        candidate[idx].name = cleanName
        candidate[idx].defaultColorHex = defaultColorHex.uppercased()
        guard candidate[idx] != layers[idx] else { return }
        try commitLayerCandidate(candidate, activeLayerID: activeLayerID,
                                 failureContext: L10n.text("layer details"))
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

    /// Persists a new drawing before publishing it to UI or Sync observers.
    /// Duplicate IDs are a no-op so a retried presentation cannot duplicate it.
    @discardableResult
    func addDurably(_ shape: DrawingShape) throws -> Bool {
        guard !locked else { throw DrawingMutationError.locked }
        guard !shapes.contains(where: { $0.id == shape.id }) else { return false }
        let candidate = shapes + [shape]
        do {
            try write(layers: layers,
                      shapes: candidate,
                      activeLayerID: activeLayerID)
        } catch {
            loadError = L10n.text("Could not save new drawing to disk: %1$@", error.localizedDescription)
            throw DrawingMutationError.persistenceFailed(error)
        }
        shapes = candidate
        if loadError?.hasPrefix(L10n.text("Could not save new drawing")) == true { loadError = nil }
        undoManager?.registerUndo(withTarget: self) { store in
            _ = try? store.deleteDurably(shape)
        }
        undoManager?.setActionName(L10n.text("Add Drawing"))
        return true
    }

    /// Persists an edited candidate before publishing it to UI or Sync.
    /// Returns false for an unchanged candidate and performs no write.
    @discardableResult
    func commitEdit(_ shape: DrawingShape,
                    actionName: String = L10n.text("Edit Drawing")) throws -> Bool {
        guard !locked else { throw DrawingMutationError.locked }
        guard let index = shapes.firstIndex(where: { $0.id == shape.id }) else {
            throw DrawingMutationError.missing
        }
        let old = shapes[index]
        guard old != shape else { return false }
        var candidate = shapes
        candidate[index] = shape
        do {
            try write(layers: layers,
                      shapes: candidate,
                      activeLayerID: activeLayerID)
        } catch {
            loadError = L10n.text("Could not save drawing change to disk: %1$@", error.localizedDescription)
            throw DrawingMutationError.persistenceFailed(error)
        }
        shapes = candidate
        if loadError?.hasPrefix(L10n.text("Could not save drawing change")) == true { loadError = nil }
        undoManager?.registerUndo(withTarget: self) { store in
            _ = try? store.commitEdit(old, actionName: actionName)
        }
        undoManager?.setActionName(actionName)
        return true
    }

    /// Persists a confirmed deletion before publishing it to UI or Sync.
    @discardableResult
    func deleteDurably(_ shape: DrawingShape) throws -> Bool {
        guard !locked else { throw DrawingMutationError.locked }
        guard let index = shapes.firstIndex(where: { $0.id == shape.id }) else {
            throw DrawingMutationError.missing
        }
        var candidate = shapes
        let removed = candidate.remove(at: index)
        do {
            try write(layers: layers,
                      shapes: candidate,
                      activeLayerID: activeLayerID)
        } catch {
            loadError = L10n.text("Could not delete drawing from disk: %1$@", error.localizedDescription)
            throw DrawingMutationError.persistenceFailed(error)
        }
        shapes = candidate
        if loadError?.hasPrefix(L10n.text("Could not delete drawing")) == true { loadError = nil }
        undoManager?.registerUndo(withTarget: self) { store in
            _ = try? store.restoreDurably(removed, at: index)
        }
        undoManager?.setActionName(L10n.text("Delete Drawing"))
        return true
    }

    @discardableResult
    private func restoreDurably(_ shape: DrawingShape, at index: Int) throws -> Bool {
        guard !locked else { throw DrawingMutationError.locked }
        guard !shapes.contains(where: { $0.id == shape.id }) else { return false }
        var candidate = shapes
        candidate.insert(shape, at: min(index, candidate.count))
        do {
            try write(layers: layers,
                      shapes: candidate,
                      activeLayerID: activeLayerID)
        } catch {
            loadError = L10n.text("Could not restore drawing to disk: %1$@", error.localizedDescription)
            throw DrawingMutationError.persistenceFailed(error)
        }
        shapes = candidate
        if loadError?.hasPrefix(L10n.text("Could not restore drawing")) == true { loadError = nil }
        undoManager?.registerUndo(withTarget: self) { store in
            _ = try? store.deleteDurably(shape)
        }
        undoManager?.setActionName(L10n.text("Delete Drawing"))
        return true
    }

    /// Commits imported layers and drawings as one candidate document and one
    /// durable write. Existing drawing IDs are skipped for retry idempotence;
    /// imported layer IDs and every shape's layer reference are left untouched.
    @discardableResult
    func importBatch(layers importedLayers: [DrawingLayer],
                     drawings importedShapes: [DrawingShape],
                     batchKey: String) throws -> DrawingBatchImportCommit {
        guard !locked else { throw BatchImportStoreError.locked }

        var layerIDs = Set(layers.map(\.id))
        var layersToInsert: [DrawingLayer] = []
        for layer in importedLayers where layerIDs.insert(layer.id).inserted {
            layersToInsert.append(layer)
        }

        var shapeIDs = Set(shapes.map(\.id))
        var shapesToInsert: [DrawingShape] = []
        var skipped = 0
        for shape in importedShapes {
            if shapeIDs.insert(shape.id).inserted {
                shapesToInsert.append(shape)
            } else {
                skipped += 1
            }
        }
        guard !layersToInsert.isEmpty || !shapesToInsert.isEmpty else {
            return DrawingBatchImportCommit(insertedLayerCount: 0,
                                            insertedDrawingCount: 0,
                                            skippedExistingDrawingCount: skipped)
        }

        let candidateLayers = layers + layersToInsert
        let candidateShapes = shapes + shapesToInsert
        let candidateActiveLayerID = activeLayerID ?? candidateLayers.first?.id
        do {
            try write(layers: candidateLayers,
                      shapes: candidateShapes,
                      activeLayerID: candidateActiveLayerID)
        } catch {
            loadError = L10n.text("Could not save imported drawings to disk: %1$@", error.localizedDescription)
            throw BatchImportStoreError.persistenceFailed(error)
        }

        layers = candidateLayers
        shapes = candidateShapes
        activeLayerID = candidateActiveLayerID
        if loadError?.hasPrefix(L10n.text("Could not save")) == true { loadError = nil }
        let insertedLayerIDs = Set(layersToInsert.map(\.id))
        let insertedShapeIDs = Set(shapesToInsert.map(\.id))
        undoManager?.registerUndo(withTarget: self) { store in
            store.removeImportedBatch(layerIDs: insertedLayerIDs,
                                      shapeIDs: insertedShapeIDs,
                                      batchKey: batchKey)
        }
        undoManager?.setActionName(L10n.text("Import Drawings"))
        return DrawingBatchImportCommit(insertedLayerCount: layersToInsert.count,
                                        insertedDrawingCount: shapesToInsert.count,
                                        skippedExistingDrawingCount: skipped)
    }

    // MARK: - Persistence

    /// Versioned, durable evidence for which layers are app-owned defaults.
    /// Mutable labels and colours are deliberately absent from this contract.
    private struct DefaultLayerProtectionProvenance: Codable, Equatable {
        enum Basis: String, Codable {
            case deterministicIDs
            case legacySeedCohort
            case ambiguousLegacy
        }

        let contractVersion: Int
        let basis: Basis
        let protectedIDs: [UUID]
        let unresolvedIDs: [UUID]
    }

    private struct Persisted: Codable {
        /// nil = written before schema versioning, treat as v1. Has to be
        /// optional so existing files without this key still decode -
        /// otherwise the upgrade would fail and wipe the data. Ironic.
        var schemaVersion: Int?
        var layers: [DrawingLayer]
        var shapes: [DrawingShape]
        var activeLayerID: UUID?
        var protectedDefaultLayerIDs: [UUID]?
        var defaultLayerProtection: DefaultLayerProtectionProvenance?
    }

    private func removeImportedBatch(layerIDs: Set<UUID>,
                                     shapeIDs: Set<UUID>,
                                     batchKey: String) {
        let removedShapes = shapes.filter { shapeIDs.contains($0.id) }
        let remainingShapes = shapes.filter { !shapeIDs.contains($0.id) }
        // Do not orphan later edits that reused an imported layer.
        let removableLayerIDs = layerIDs.filter { id in
            !remainingShapes.contains(where: { $0.layerID == id })
        }
        let removedLayers = layers.filter { removableLayerIDs.contains($0.id) }
        guard !removedShapes.isEmpty || !removedLayers.isEmpty else { return }
        let remainingLayers = layers.filter { !removableLayerIDs.contains($0.id) }
        let candidateActive = remainingLayers.contains(where: { $0.id == activeLayerID })
            ? activeLayerID
            : remainingLayers.first?.id
        do {
            try write(layers: remainingLayers,
                      shapes: remainingShapes,
                      activeLayerID: candidateActive)
            layers = remainingLayers
            shapes = remainingShapes
            activeLayerID = candidateActive
            undoManager?.registerUndo(withTarget: self) { store in
                _ = try? store.importBatch(layers: removedLayers,
                                           drawings: removedShapes,
                                           batchKey: batchKey)
            }
            undoManager?.setActionName(L10n.text("Import Drawings"))
        } catch {
            loadError = L10n.text("Could not undo imported drawings: %1$@", error.localizedDescription)
        }
    }

    /// Try decoding as current schema, fall back to legacy flat
    /// [DrawingShape] array. Throws if neither works so SafeStore
    /// quarantines instead of silently discarding.
    private static func decodeAny(_ data: Data) throws -> (Persisted, migrated: Bool) {
        let dec = JSONDecoder()
        if var payload = try? dec.decode(Persisted.self, from: data) {
            let needsMigration = payload.schemaVersion != currentSchema
                || payload.defaultLayerProtection?.contractVersion != 1
            if needsMigration {
                let provenance = protectionProvenance(for: payload.layers)
                payload.schemaVersion = currentSchema
                payload.defaultLayerProtection = provenance
                payload.protectedDefaultLayerIDs = provenance.protectedIDs
            }
            return (payload, needsMigration)
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
            activeLayerID: DrawingLayer.seedDefaults.first?.id,
            protectedDefaultLayerIDs: DrawingLayer.seedDefaults.map(\.id),
            defaultLayerProtection: protectionProvenance(for: DrawingLayer.seedDefaults)
        )
        return (migrated, true)
    }

    private func load() {
        switch SafeStore.read(url, label: Self.label, decode: { try Self.decodeAny($0) }) {
        case .loaded(let (payload, migrated)):
            locked = false
            layers = payload.layers
            shapes = payload.shapes
            activeLayerID = payload.activeLayerID ?? layers.first?.id
            let provenance = payload.defaultLayerProtection
                ?? Self.protectionProvenance(for: layers)
            protectedDefaultLayerIDs = Set(provenance.protectedIDs)
            unresolvedLegacyLayerIDs = Set(provenance.unresolvedIDs)
            protectionBasis = provenance.basis
            ensureSeedLayers()
            if migrated { persist() } // one-time provenance upgrade
            if !unresolvedLegacyLayerIDs.isEmpty,
               loadError == nil {
                loadError = L10n.text("Some legacy layers have ambiguous default-layer history. ")
                    + L10n.text("Confirm each one as custom or default before renaming, recolouring, or deleting it.")
            }
        case .empty:
            seedFreshInstall()
        case .corrupt(let quarantine, _):
            // Don't clobber the unreadable file - set it aside and tell the
            // user, otherwise the next edit would persist an empty doc over
            // the only copy
            loadError = L10n.text("Saved drawings could not be read and were set aside ")
                + L10n.text("(%1$@). Starting with an empty map.", quarantine?.lastPathComponent ?? "recovery copy")
            seedFreshInstall()
        case .locked(let error):
            // The file is intact, we just can't open it yet. Give the UI some
            // layers to render but never write: persist() is gated on `locked`
            // so an empty doc can't land on top of real drawings.
            locked = true
            loadError = L10n.text("Drawings are encrypted and locked. %1$@", error.localizedDescription)
            layers = DrawingLayer.seedDefaults
            shapes = []
            activeLayerID = layers.first?.id
            protectedDefaultLayerIDs = Set(layers.map(\.id))
            unresolvedLegacyLayerIDs = []
            protectionBasis = .deterministicIDs
        }
    }

    /// Fresh install - seed default layers so the user has somewhere to
    /// draw without having to create a layer first.
    private func seedFreshInstall() {
        layers = DrawingLayer.seedDefaults
        shapes = []
        activeLayerID = layers.first?.id
        protectedDefaultLayerIDs = Set(layers.map(\.id))
        unresolvedLegacyLayerIDs = []
        protectionBasis = .deterministicIDs
        persist()
    }

    /// If saved file has zero layers (user deleted them all), put the
    /// seed layers back so new drawings have somewhere to go.
    private func ensureSeedLayers() {
        if layers.isEmpty {
            layers = DrawingLayer.seedDefaults
            activeLayerID = layers.first?.id
            protectedDefaultLayerIDs = Set(layers.map(\.id))
            unresolvedLegacyLayerIDs = []
            protectionBasis = .deterministicIDs
        }
        if activeLayerID == nil || layer(id: activeLayerID!) == nil {
            activeLayerID = layers.first?.id
        }
    }

    /// Schema-v1 used random IDs for Hostile/Unknown/Civilian. Their durable
    /// provenance is structural: the original four defaults were inserted as
    /// the first, single creation-time cohort, led by the immutable Friendly
    /// fallback ID. Names and colours may have changed and are never evidence.
    ///
    /// If deletion/reordering makes that evidence ambiguous, only deterministic
    /// IDs are confirmed and all other pre-migration IDs require an explicit
    /// user classification before destructive metadata edits.
    private static func protectionProvenance(for layers: [DrawingLayer])
        -> DefaultLayerProtectionProvenance {
        let stableIDs = Set(DrawingLayer.seedDefaults.map(\.id))
        let presentStable = Set(layers.map(\.id)).intersection(stableIDs)
        if presentStable.count == stableIDs.count {
            return DefaultLayerProtectionProvenance(
                contractVersion: 1,
                basis: .deterministicIDs,
                protectedIDs: presentStable.sorted(by: uuidSort),
                unresolvedIDs: []
            )
        }

        if layers.count >= 4,
           layers[0].id == DrawingLayer.legacyFallbackID {
            let cohort = Array(layers.prefix(4))
            let ids = cohort.map(\.id)
            let times = cohort.map(\.createdAt.timeIntervalSinceReferenceDate)
            let span = (times.max() ?? 0) - (times.min() ?? 0)
            let randomHistoricalIDs = Set(ids.dropFirst()).isDisjoint(
                with: stableIDs.subtracting(Set([DrawingLayer.legacyFallbackID]))
            )
            if Set(ids).count == 4, span >= 0, span <= 2,
               randomHistoricalIDs {
                return DefaultLayerProtectionProvenance(
                    contractVersion: 1,
                    basis: .legacySeedCohort,
                    protectedIDs: ids.sorted(by: uuidSort),
                    unresolvedIDs: []
                )
            }
        }

        let unresolved = Set(layers.map(\.id)).subtracting(presentStable)
        return DefaultLayerProtectionProvenance(
            contractVersion: 1,
            basis: .ambiguousLegacy,
            protectedIDs: presentStable.sorted(by: uuidSort),
            unresolvedIDs: unresolved.sorted(by: uuidSort)
        )
    }

    private static func uuidSort(_ lhs: UUID, _ rhs: UUID) -> Bool {
        lhs.uuidString < rhs.uuidString
    }

    private static func isValidHexColor(_ value: String) -> Bool {
        guard value.count == 7, value.first == "#" else { return false }
        return value.dropFirst().allSatisfy { $0.isHexDigit }
    }

    private func commitLayerCandidate(_ candidateLayers: [DrawingLayer],
                                      activeLayerID candidateActive: UUID?,
                                      failureContext: String) throws {
        do {
            try write(layers: candidateLayers,
                      shapes: shapes,
                      activeLayerID: candidateActive)
        } catch {
            loadError = L10n.text("Could not save %1$@ to disk: %2$@", failureContext, error.localizedDescription)
            throw MissionLayerMutationError.persistenceFailed(store: "drawings", underlying: error)
        }
        layers = candidateLayers
        activeLayerID = candidateActive
        clearLayerSaveError()
    }

    private func clearLayerSaveError() {
        if loadError?.hasPrefix(L10n.text("Could not save")) == true { loadError = nil }
    }

    private func persist() {
        guard !locked else { return }
        do {
            try write(layers: layers, shapes: shapes, activeLayerID: activeLayerID)
            if loadError?.hasPrefix(L10n.text("Could not save")) == true { loadError = nil }
        } catch {
            // don't swallow this, user is editing but nothing is hitting disk
            print("[DrawingStore] persist failed")
            loadError = L10n.text("Could not save drawings to disk: %1$@", error.localizedDescription)
        }
    }

    private func write(layers: [DrawingLayer],
                       shapes: [DrawingShape],
                       activeLayerID: UUID?,
                       protectedDefaultLayerIDs candidateProtected: Set<UUID>? = nil,
                       unresolvedLegacyLayerIDs candidateUnresolved: Set<UUID>? = nil,
                       protectionBasis candidateBasis: DefaultLayerProtectionProvenance.Basis? = nil) throws {
        let protected = candidateProtected ?? protectedDefaultLayerIDs
        let unresolved = candidateUnresolved ?? unresolvedLegacyLayerIDs
        let basis = candidateBasis ?? protectionBasis
        let provenance = DefaultLayerProtectionProvenance(
            contractVersion: 1,
            basis: basis,
            protectedIDs: protected.sorted(by: Self.uuidSort),
            unresolvedIDs: unresolved.sorted(by: Self.uuidSort)
        )
        let payload = Persisted(schemaVersion: Self.currentSchema,
                                layers: layers,
                                shapes: shapes,
                                activeLayerID: activeLayerID,
                                protectedDefaultLayerIDs: provenance.protectedIDs,
                                defaultLayerProtection: provenance)
        let data = try JSONEncoder().encode(payload)
        try persistenceWriter(data, url, Self.label)
    }
}
