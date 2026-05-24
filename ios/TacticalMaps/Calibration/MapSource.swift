import Foundation
import CoreLocation
import MapKit

/// Abstract source of underlying basemap imagery.
///
/// Three concrete kinds today:
/// - `AppleSatelliteMapSource`  — MapKit satellite (fallback when no PDF is loaded).
/// - `PDFMapSource` (.geoPDF)    — a GeoPDF whose neat-line/projection tags are parsed.
/// - `PDFMapSource` (.calibrated)— a regular PDF the user has fitted with 3+ fiduciaries.
///
/// All sources expose a *common contract*: given a WGS84 coordinate, render the
/// correct pixels; given a screen point, return the underlying WGS84 coordinate.
/// Overlays (waypoints, drawings) are stored in WGS84 and travel between sources
/// unchanged.
protocol MapSource: AnyObject {
    var id: UUID { get }
    var displayName: String { get }
    var kind: MapSourceKind { get }

    /// Region the source can show. `nil` for satellite (“unbounded”).
    var coverage: MKCoordinateRegion? { get }

    /// Calibration state. `nil` for AppleSatellite, `.parsed` for GeoPDF, `.fiduciaries(…)`
    /// for hand-calibrated PDFs.
    var calibration: Calibration? { get }
}

enum MapSourceKind: String, Codable { case appleSatellite, geoPDF, calibratedPDF }

/// Calibration metadata for a PDF source.
enum Calibration {
    /// GeoPDF self-describes via OGC GeoPDF / Adobe Geospatial extensions.
    case parsed(crs: String, transform: AffineTransform2D)
    /// User has placed N≥3 fiduciaries; we fit a best-effort affine transform.
    case fiduciaries([Fiduciary], transform: AffineTransform2D)
}
