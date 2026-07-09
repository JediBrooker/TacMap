import Foundation
import CoreLocation
import MapKit

/// Abstract source of underlying basemap imagery.
///
/// Three concrete kinds today:
/// - AppleSatelliteMapSource - MapKit satellite (fallback when no PDF loaded).
/// - PDFMapSource (.geoPDF)  - GeoPDF with neat-line/projection tags parsed.
/// - PDFMapSource (.calibrated) - regular PDF fitted with 3+ fiduciaries.
///
/// All sources expose a common contract: given WGS84 coord render the correct
/// pixels; given screen point return the underlying WGS84 coord. Overlays
/// (waypoints, drawings) stored in WGS84 and travel between sources unchanged.
protocol MapSource: AnyObject {
    var id: UUID { get }
    var displayName: String { get }
    var kind: MapSourceKind { get }

    /// Region the source can show. nil for satellite (unbounded).
    var coverage: MKCoordinateRegion? { get }

    /// Calibration state. nil for AppleSatellite, .parsed for GeoPDF,
    /// .fiduciaries(...) for hand-calibrated PDFs.
    var calibration: Calibration? { get }
}

enum MapSourceKind: String, Codable { case appleSatellite, onlineRaster, geoPDF, calibratedPDF, offlineTiles }

/// Calibration metadata for a PDF source.
enum Calibration {
    /// GeoPDF self-describes via OGC / Adobe Geospatial extensions.
    case parsed(crs: String, transform: AffineTransform2D)
    /// User placed N>=3 fiduciaries, we fit a best-effort affine transform.
    case fiduciaries([Fiduciary], transform: AffineTransform2D)
}
