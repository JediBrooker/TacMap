import Foundation
import MapKit

/// Fallback source when no PDF has been imported. Just renders standard MapKit
/// satellite imagery, no calibration needed.
final class AppleSatelliteMapSource: MapSource {
    let id = UUID()
    let displayName = "Apple Satellite"
    let kind: MapSourceKind = .appleSatellite
    let coverage: MKCoordinateRegion? = nil
    let calibration: Calibration? = nil
}
