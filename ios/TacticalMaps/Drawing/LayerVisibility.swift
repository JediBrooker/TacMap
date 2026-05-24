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
}
