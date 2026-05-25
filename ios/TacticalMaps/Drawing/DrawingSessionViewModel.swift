import Foundation
import CoreLocation
import Combine

/// State machine for an in-progress drawing.
///
/// When `activeKind` is non-nil the app is in **drawing mode**: tapping the map
/// adds a vertex, the bottom HUD swaps to `DrawToolbar`, and pan/zoom still
/// works (gestures aren't swallowed). Finish writes the shape to `DrawingStore`.
final class DrawingSessionViewModel: ObservableObject {
    @Published private(set) var activeKind: DrawingKind? = nil
    @Published private(set) var inProgressCoordinates: [Coordinate2D] = []

    /// User-selected stroke / fill colour for the in-progress drawing.
    /// Persists across sessions until the user picks something else.
    @Published var strokeColorHex: String = DrawingPalette.default.hex

    var isDrawing: Bool { activeKind != nil }

    var canFinish: Bool {
        guard let kind = activeKind else { return false }
        return inProgressCoordinates.count >= kind.minimumVertices
    }

    func start(kind: DrawingKind) {
        activeKind = kind
        inProgressCoordinates = []
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

    func undo() {
        guard !inProgressCoordinates.isEmpty else { return }
        inProgressCoordinates.removeLast()
    }

    func cancel() {
        activeKind = nil
        inProgressCoordinates = []
    }

    /// Build the final shape and reset session state. Returns nil if there's
    /// nothing to commit.
    func finish() -> DrawingShape? {
        defer {
            activeKind = nil
            inProgressCoordinates = []
        }
        guard let kind = activeKind,
              inProgressCoordinates.count >= kind.minimumVertices else {
            return nil
        }
        let style = DrawingStyle(
            strokeColorHex: strokeColorHex,
            fillColorHex:   strokeColorHex   // polygons fill with same hue
        )
        return DrawingShape(
            kind: kind,
            coordinates: inProgressCoordinates,
            style: style
        )
    }
}
