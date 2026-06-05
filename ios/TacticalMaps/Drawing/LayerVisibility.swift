import Foundation
import Combine

/// Shared object controlling which overlays/labels are rendered on the map.
/// Bound from `LayersSheet` toggles and consumed by `MapContainerView`.
///
/// Every toggle persists to `UserDefaults` so the user's choices survive
/// quitting and relaunching the app. (Previously these were in-memory only
/// and reset to defaults on every launch.)
final class LayerVisibility: ObservableObject {
    @Published var waypointsVisible:     Bool = true  { didSet { d.set(waypointsVisible,     forKey: K.waypoints) } }
    @Published var drawingsVisible:      Bool = true  { didSet { d.set(drawingsVisible,      forKey: K.drawings) } }
    @Published var userLocationVisible:  Bool = true  { didSet { d.set(userLocationVisible,  forKey: K.userLocation) } }
    /// Imported PDF overlay (GeoPDF basemap). Defaults on; users can toggle
    /// off to compare against satellite, or to hide a temporarily misaligned
    /// PDF without unloading it.
    @Published var pdfOverlayVisible:    Bool = true  { didSet { d.set(pdfOverlayVisible,    forKey: K.pdfOverlay) } }

    /// Whether the name-label pill is rendered alongside each drawing.
    @Published var drawingLabelsVisible: Bool = false { didSet { d.set(drawingLabelsVisible, forKey: K.drawingLabels) } }
    /// Whether the name-label pill is rendered under each military / generic
    /// waypoint icon.
    @Published var unitLabelsVisible:    Bool = false { didSet { d.set(unitLabelsVisible,    forKey: K.unitLabels) } }
    /// Whether the name-label is rendered inside each tactical control
    /// measure (task graphic). Separate from units because tasks have
    /// different rendering geometry — labels sit inside the bubble, not
    /// below it — and users often want one toggled without the other.
    @Published var taskLabelsVisible:    Bool = false { didSet { d.set(taskLabelsVisible,    forKey: K.taskLabels) } }

    /// MGRS military grid overlay. Defaults off because the grid is a
    /// performance- and visual-cost feature most users won't want on by
    /// default; render detail (100km → 10km → 1km) auto-selects from
    /// current zoom.
    @Published var mgrsGridVisible:      Bool = false { didSet { d.set(mgrsGridVisible,      forKey: K.mgrsGrid) } }

    private let d = UserDefaults.standard

    private enum K {
        static let waypoints     = "layers.waypointsVisible"
        static let drawings      = "layers.drawingsVisible"
        static let userLocation  = "layers.userLocationVisible"
        static let pdfOverlay    = "layers.pdfOverlayVisible"
        static let drawingLabels = "layers.drawingLabelsVisible"
        static let unitLabels    = "layers.unitLabelsVisible"
        static let taskLabels    = "layers.taskLabelsVisible"
        static let mgrsGrid      = "layers.mgrsGridVisible"
    }

    init() {
        // Restore saved toggles, falling back to the declared defaults when a
        // key has never been written (fresh install).
        func restore(_ key: String, default fallback: Bool) -> Bool {
            d.object(forKey: key) == nil ? fallback : d.bool(forKey: key)
        }
        waypointsVisible     = restore(K.waypoints,     default: true)
        drawingsVisible      = restore(K.drawings,      default: true)
        userLocationVisible  = restore(K.userLocation,  default: true)
        pdfOverlayVisible    = restore(K.pdfOverlay,    default: true)
        drawingLabelsVisible = restore(K.drawingLabels, default: false)
        unitLabelsVisible    = restore(K.unitLabels,    default: false)
        taskLabelsVisible    = restore(K.taskLabels,    default: false)
        mgrsGridVisible      = restore(K.mgrsGrid,      default: false)
    }
}
