import Foundation
import MapKit

/// Fallback source when no PDF has been imported. Renders the standard MapKit
/// satellite imagery — no calibration required.
final class AppleSatelliteMapSource: MapSource {
    let id = UUID()
    let displayName = "Apple Satellite"
    let kind: MapSourceKind = .appleSatellite
    let coverage: MKCoordinateRegion? = nil
    let calibration: Calibration? = nil
}
