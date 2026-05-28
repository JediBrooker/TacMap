import Foundation
import Combine

/// Shared object controlling which overlays are rendered on the map. Bound
/// from `LayersSheet` toggles and consumed by `MapContainerView`.
final class LayerVisibility: ObservableObject {
    @Published var waypointsVisible:    Bool = true
    @Published var drawingsVisible:     Bool = true
    @Published var userLocationVisible: Bool = true
    /// Imported PDF overlay (GeoPDF basemap). Defaults on; users can toggle
    /// off to compare against satellite, or to hide a temporarily misaligned
    /// PDF without unloading it.
    @Published var pdfOverlayVisible:   Bool = true

    /// Whether the name-label pill is rendered alongside each drawing.
    @Published var drawingLabelsVisible: Bool = true
    /// Whether the name-label pill is rendered under each military / generic
    /// waypoint icon.
    @Published var unitLabelsVisible:    Bool = true
    /// Whether the name-label is rendered inside each tactical control
    /// measure (task graphic). Separate from units because tasks have
    /// different rendering geometry — labels sit inside the bubble, not
    /// below it — and users often want one toggled without the other.
    @Published var taskLabelsVisible:    Bool = true

    /// MGRS military grid overlay. Defaults off because the grid is a
    /// performance- and visual-cost feature most users won't want on by
    /// default; render detail (100km → 10km → 1km) auto-selects from
    /// current zoom.
    @Published var mgrsGridVisible:      Bool = false
}
