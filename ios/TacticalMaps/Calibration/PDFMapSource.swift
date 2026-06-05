import Foundation
import UIKit
import MapKit
import PDFKit

/// PDF-backed map source. After import, the map renders this PDF as a
/// `UIImageView` subview pinned to the PDF’s geographic bounds.
///
/// Bounds come from one of three sources, in order:
///  1. **OGC GeoPDF (LGIDict) or Adobe Geospatial (/VP/Measure)** via
///     `GeoPDFReader`.
///  2. **Known-sheet table** — hardcoded bounds for the demo Holsworthy sheet.
///  3. **Camera-centre fallback** — 10km square around current camera.
///
/// Bounds can also be *re-derived* at runtime by `applyCalibration(_:_:)`
/// which feeds a least-squares affine fit from user-placed fiduciaries.
final class PDFMapSource: MapSource {
    let id = UUID()
    let displayName: String
    var kind: MapSourceKind
    private(set) var coverage: MKCoordinateRegion?
    private(set) var calibration: Calibration?
    let url: URL
    private(set) var bounds: GeoPDFReader.Bounds?

    /// PDF-page rect actually rasterised into `cachedImage`. Mirrors
    /// `bounds?.pdfCropRect` when set, else the media box.
    private(set) var pdfRenderRect: CGRect = .zero

    /// Fiduciaries from the most recent calibration, if any.
    private(set) var fiduciaries: [Fiduciary]?

    private var cachedImage: UIImage?

    /// Affine (PDF user-space → WGS84) used to PLACE the page on the map with
    /// true rotation + scale, instead of stretching it to the lat/lon box.
    /// A manual fiduciary calibration wins; otherwise the GeoPDF auto-fit
    /// affine from `bounds`. nil → the overlay falls back to a bbox stretch.
    var placementTransform: AffineTransform2D? {
        if case .fiduciaries(_, let t)? = calibration { return t }
        return bounds?.placementAffine
    }

    init(url: URL,
         bounds: GeoPDFReader.Bounds?,
         fromGeoPDF: Bool = false) {
        self.url = url
        self.displayName = url.deletingPathExtension().lastPathComponent
        self.bounds = bounds
        self.kind = fromGeoPDF ? .geoPDF : .calibratedPDF
        if let b = bounds {
            let span = MKCoordinateSpan(
                latitudeDelta:  abs(b.northEast.latitude  - b.southWest.latitude)  * 1.2,
                longitudeDelta: abs(b.northEast.longitude - b.southWest.longitude) * 1.2
            )
            self.coverage = MKCoordinateRegion(center: b.centre, span: span)
        } else {
            self.coverage = nil
        }
        self.calibration = nil
        self.pdfRenderRect = bounds?.pdfCropRect ?? Self.mediaBox(for: url)
    }

    private static func mediaBox(for url: URL) -> CGRect {
        guard let doc = PDFDocument(url: url), let page = doc.page(at: 0) else {
            return CGRect(x: 0, y: 0, width: 1, height: 1)
        }
        return page.bounds(for: .mediaBox)
    }

    /// The rasterised page if it's already been rendered — `nil` if not yet,
    /// WITHOUT triggering a (blocking) rasterisation. Lets the overlay sync
    /// render off the main thread and attach the page when it's ready.
    var cachedRenderedImage: UIImage? { cachedImage }

    /// Cached PDF rasterisation, cropped to the LGIDict Neatline if known.
    /// Heavy (decodes the page to a bitmap) — call OFF the main thread on first
    /// use; subsequent calls return the cache.
    func renderedImage() -> UIImage? {
        if let cached = cachedImage { return cached }
        guard let img = PDFRasteriser.render(url: url,
                                              cropRect: bounds?.pdfCropRect)
        else { return nil }
        cachedImage = img
        return img
    }

    /// Replace the geographic bounds using an affine fit from user fiduciaries.
    /// Map UI is expected to swap this source for a fresh `PDFMapSource` so
    /// that the new bounds take effect (the overlay view caches the bounds
    /// at init).
    func applyCalibration(transform: AffineTransform2D,
                          fiduciaries: [Fiduciary]) {
        // Apply the affine to the 4 corners of the rendered rect to derive
        // axis-aligned geographic bounds.
        let r = pdfRenderRect
        let corners = [
            CGPoint(x: r.minX, y: r.minY),
            CGPoint(x: r.maxX, y: r.minY),
            CGPoint(x: r.maxX, y: r.maxY),
            CGPoint(x: r.minX, y: r.maxY)
        ]
        let geo = corners.map { transform.apply($0) }
        let lats = geo.map { $0.latitude }
        let lons = geo.map { $0.longitude }
        guard let minLat = lats.min(), let maxLat = lats.max(),
              let minLon = lons.min(), let maxLon = lons.max() else { return }

        self.bounds = GeoPDFReader.Bounds(
            southWest: CLLocationCoordinate2D(latitude: minLat, longitude: minLon),
            northEast: CLLocationCoordinate2D(latitude: maxLat, longitude: maxLon),
            pdfCropRect: r
        )
        self.kind = .calibratedPDF
        self.calibration = .fiduciaries(fiduciaries, transform: transform)
        self.fiduciaries = fiduciaries
        if let b = bounds {
            let span = MKCoordinateSpan(
                latitudeDelta:  abs(b.northEast.latitude  - b.southWest.latitude)  * 1.2,
                longitudeDelta: abs(b.northEast.longitude - b.southWest.longitude) * 1.2
            )
            self.coverage = MKCoordinateRegion(center: b.centre, span: span)
        }
    }

    static func placeholder(for url: URL) -> PDFMapSource {
        PDFMapSource(url: url, bounds: nil)
    }
}
