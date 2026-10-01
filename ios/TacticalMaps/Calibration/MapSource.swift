import Foundation
import CoreLocation
import MapKit

/// Abstract source of underlying basemap imagery.
///
/// Concrete kinds today:
/// - OnlineRasterBasemapSource - Esri Satellite/Topo, OSM Topo/Street tiles.
/// - PDFMapSource (.geoPDF)  - GeoPDF with neat-line/projection tags parsed.
/// - PDFMapSource (.calibrated) - regular PDF fitted with 3+ fiduciaries, or
///   still on its provisional (uncalibrated) placement.
/// - OfflineTileMapSource - sideloaded MBTiles raster.
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

    /// Manual calibration state. GeoPDF placement is carried by the parsed
    /// bounds/placement affine; hand-calibrated PDFs retain their fiduciaries.
    var calibration: Calibration? { get }
}

enum MapSourceKind: String, Codable { case onlineRaster, geoPDF, calibratedPDF, offlineTiles }

/// Calibration metadata for a PDF source.
enum Calibration {
    /// User placed N>=3 fiduciaries. The real fit is the source's
    /// PdfGeoreference (page -> UTM of the first point), transform is just
    /// the lon/lat record we've always persisted alongside them.
    case fiduciaries([Fiduciary], transform: AffineTransform2D)
}
