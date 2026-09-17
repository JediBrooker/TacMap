import Foundation
import CoreLocation
import Combine

/// State machine for an in-progress drawing.
///
/// When `activeKind` is non-nil we're in drawing mode: map taps add vertices,
/// bottom HUD swaps to `DrawToolbar`, pan/zoom still works (gestures aren't
/// swallowed). Finish writes the shape to `DrawingStore`.
final class DrawingSessionViewModel: ObservableObject {
    @Published private(set) var activeKind: DrawingKind? = nil
    @Published private(set) var inProgressCoordinates: [Coordinate2D] = []

    /// Stroke colour for the in-progress drawing. It is deliberately
    /// independent from polygon fill colour.
    @Published var strokeColorHex: String = DrawingPalette.default.hex

    /// Polygon fill hue and opacity. Both persist across drawing sessions,
    /// just like stroke colour, so a user's chosen style remains convenient.
    @Published var fillColorHex: String = DrawingPalette.default.hex
    @Published var fillOpacity: Double = 0.2

    /// When true, finished lines and polygon strokes render dashed.
    /// Persists across sessions until the user toggles it.
    @Published var isDashed: Bool = false

    /// Layer the in-progress drawing will be saved to when finished.
    /// Nil = no layer was supplied to `start`; finish() will refuse.
    @Published private(set) var targetLayerID: UUID? = nil

    /// Optional name the user typed into the toolbar's name field while
    /// drawing. Reset on each start/finish/cancel.
    @Published var shapeName: String = ""

    var isDrawing: Bool { activeKind != nil }

    var canFinish: Bool {
        guard let kind = activeKind else { return false }
        return inProgressCoordinates.count >= kind.minimumVertices
    }

    func start(kind: DrawingKind, layerID: UUID) {
        activeKind = kind
        inProgressCoordinates = []
        targetLayerID = layerID
        shapeName = ""
    }

    /// Add a vertex from a map tap. Returns `true` if the shape auto-commits
    /// (single-point drawings do; lines and polygons require Finish).
    func addPoint(_ coord: CLLocationCoordinate2D) -> Bool {
        guard isDrawing else { return false }
        inProgressCoordinates.append(
            Coordinate2D(latitude: coord.latitude, longitude: coord.longitude)
        )
        return activeKind == .point
    }

    /// Only append a freehand point when its far enough from the last
    /// vertex - avoids recording hundreds of near-identical coords
    /// during a fast drag. Threshold is ~5 m at mid-latitudes.
    func addFreeDrawPoint(_ coord: CLLocationCoordinate2D) {
        guard isDrawing else { return }
        if let last = inProgressCoordinates.last {
            let dLat = coord.latitude  - last.latitude
            let dLon = coord.longitude - last.longitude
            guard dLat * dLat + dLon * dLon > 2e-9 else { return }
        }
        inProgressCoordinates.append(
            Coordinate2D(latitude: coord.latitude, longitude: coord.longitude)
        )
    }

    func undo() {
        guard !inProgressCoordinates.isEmpty else { return }
        inProgressCoordinates.removeLast()
    }

    func cancel() {
        activeKind = nil
        inProgressCoordinates = []
        targetLayerID = nil
        shapeName = ""
    }

    /// Build the final shape and reset. Returns nil if there's nothing to
    /// commit (no active kind, not enough verts, or no target layer).
    func finish() -> DrawingShape? {
        defer {
            activeKind = nil
            inProgressCoordinates = []
            targetLayerID = nil
            shapeName = ""
        }
        guard let kind = activeKind,
              let layerID = targetLayerID,
              inProgressCoordinates.count >= kind.minimumVertices else {
            return nil
        }
        // 8pt on, 6pt off - reads ok at our default 3pt stroke width
        // without losing the shape outline on satellite.
        let style = DrawingStyle(
            strokeColorHex: strokeColorHex,
            fillColorHex:   fillColorHex,
            fillOpacity:    min(max(fillOpacity.isFinite ? fillOpacity : 0.2, 0), 1),
            dashPattern:    isDashed ? [8, 6] : nil
        )
        let trimmedName = shapeName.trimmingCharacters(in: .whitespaces)
        // free draw is just a capture mode, store as polyline
        let storedKind: DrawingKind = kind == .freedraw ? .polyline : kind
        return DrawingShape(
            name: trimmedName.isEmpty ? nil : trimmedName,
            kind: storedKind,
            coordinates: inProgressCoordinates,
            style: style,
            layerID: layerID
        )
    }
}
