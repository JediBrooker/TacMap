import Foundation
import Combine

/// Controls which overlays/labels are rendered on the map.
/// Bound from `LayersSheet` toggles, consumed by `MapContainerView`.
///
/// All toggles persist to UserDefaults now so they survive app restart.
/// (Used to be memory-only and reset every launch, which was annoying.)
final class LayerVisibility: ObservableObject {
    @Published var waypointsVisible:     Bool = true  { didSet { d.set(waypointsVisible,     forKey: K.waypoints) } }
    @Published var drawingsVisible:      Bool = true  { didSet { d.set(drawingsVisible,      forKey: K.drawings) } }
    @Published var userLocationVisible:  Bool = true  { didSet { d.set(userLocationVisible,  forKey: K.userLocation) } }
    /// PDF overlay (GeoPDF basemap). On by default, user can toggle off
    /// to compare against satellite or hide a misaligned PDF without
    /// unloading it.
    @Published var pdfOverlayVisible:    Bool = true  { didSet { d.set(pdfOverlayVisible,    forKey: K.pdfOverlay) } }

    /// Whether the name-label pill is rendered alongside each drawing.
    @Published var drawingLabelsVisible: Bool = false { didSet { d.set(drawingLabelsVisible, forKey: K.drawingLabels) } }
    /// Whether the name-label pill is rendered under each military / generic
    /// waypoint icon.
    @Published var unitLabelsVisible:    Bool = false { didSet { d.set(unitLabelsVisible,    forKey: K.unitLabels) } }
    /// Name-label inside each task graphic. Seperate toggle from units
    /// b/c tasks render labels inside the bubble (not below it) and
    /// users often want one on without the other.
    @Published var taskLabelsVisible:    Bool = false { didSet { d.set(taskLabelsVisible,    forKey: K.taskLabels) } }

    /// MGRS grid overlay. Off by default, its a perf + visual cost most
    /// users won't want. Render detail (100km / 10km / 1km) auto-selects
    /// from zoom level.
    @Published var mgrsGridVisible:      Bool = false { didSet { d.set(mgrsGridVisible,      forKey: K.mgrsGrid) } }

    /// Terrain heat-map (DEM shading). Off by default, fetches DEM
    /// samples over the network when enabled.
    @Published var terrainHeatmapVisible: Bool = false { didSet { d.set(terrainHeatmapVisible, forKey: K.terrainHeatmap) } }

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
        static let terrainHeatmap = "layers.terrainHeatmapVisible"
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
        terrainHeatmapVisible = restore(K.terrainHeatmap, default: false)
    }
}
