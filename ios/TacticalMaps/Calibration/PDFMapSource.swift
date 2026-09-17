import Foundation
import UIKit
import MapKit
import PDFKit

/// PDF-backed map source. After import the map renders this PDF as a
/// UIImageView subview pinned to the PDF’s geographic bounds.
///
/// Bounds come from one of three sources, in order:
///  1. OGC GeoPDF (LGIDict) or Adobe Geospatial (/VP/Measure) via GeoPDFReader.
///  2. Known-sheet table - hardcoded bounds for the demo Holsworthy sheet.
///  3. Camera-centre fallback - 10km square around current camera.
///
/// Bounds can also be re-derived at runtime by applyCalibration which feeds
/// a least-squares affine fit from user-placed fiduciaries.
final class PDFMapSource: MapSource {
    let id = UUID()
    let displayName: String
    var kind: MapSourceKind
    private(set) var coverage: MKCoordinateRegion?
    private(set) var calibration: Calibration?
    let url: URL
    private(set) var bounds: GeoPDFReader.Bounds?
    /// SHA-256 identity of the imported bytes. Import workers provide this
    /// off-main; restored legacy sessions may fill it while validating disk.
    let contentKey: String?

    /// PDF-page rect actually rasterised into cachedImage. Mirrors
    /// bounds?.pdfCropRect when set, else the media box.
    private(set) var pdfRenderRect: CGRect = .zero

    /// Fiduciaries from the most recent calibration, if any.
    private(set) var fiduciaries: [Fiduciary]?

    private var cachedImage: UIImage?
    private let cachedImageLock = NSLock()

    /// Affine (PDF user-space -> WGS84) used to place the page on the map with
    /// true rotation + scale instead of stretching to lat/lon box. Manual
    /// fiduciary calibration wins, otherwise GeoPDF auto-fit affine from
    /// bounds. nil means overlay falls back to bbox stretch.
    var placementTransform: AffineTransform2D? {
        if case .fiduciaries(_, let t)? = calibration { return t }
        return bounds?.placementAffine
    }

    init(url: URL,
         bounds: GeoPDFReader.Bounds?,
         fromGeoPDF: Bool = false,
         preflightMediaBox: CGRect? = nil,
         contentKey: String? = nil) {
        self.url = url
        self.contentKey = contentKey
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
        self.pdfRenderRect = bounds?.pdfCropRect ?? preflightMediaBox ?? Self.mediaBox(for: url)
    }

    private static func mediaBox(for url: URL) -> CGRect {
        guard let doc = PDFDocument(url: url), let page = doc.page(at: 0) else {
            return CGRect(x: 0, y: 0, width: 1, height: 1)
        }
        return page.bounds(for: .mediaBox)
    }

    /// The rasterised page if already rendered, nil if not yet. Does NOT
    /// trigger a blocking rasterisation. Lets the overlay sync render off
    /// main thread and attach the page when its ready.
    var cachedRenderedImage: UIImage? {
        cachedImageLock.lock()
        defer { cachedImageLock.unlock() }
        return cachedImage
    }

    /// Cached PDF rasterisation, cropped to LGIDict Neatline if known.
    /// Heavy (decodes page to bitmap) - call off main thread on first use;
    /// subsequent calls just return the cache.
    func renderedImage() -> UIImage? {
        cachedImageLock.lock()
        if let cached = cachedImage {
            cachedImageLock.unlock()
            return cached
        }
        cachedImageLock.unlock()
        guard let img = PDFRasteriser.render(url: url,
                                              cropRect: bounds?.pdfCropRect)
        else { return nil }
        cachedImageLock.lock()
        defer { cachedImageLock.unlock() }
        if let cached = cachedImage { return cached }
        cachedImage = img
        return img
    }

    /// Replace geographic bounds using an affine fit from user fiduciaries.
    /// Map UI is expected to swap this source for a fresh PDFMapSource so
    /// new bounds take effect (overlay view caches bounds at init).
    func applyCalibration(transform: AffineTransform2D,
                          fiduciaries: [Fiduciary]) {
        guard fiduciaries.count >= 3,
              fiduciaries.allSatisfy(isSafeAffineInput),
              transform.hasFiniteCoefficients,
              transform.inverted() != nil else { return }
        // Apply the affine to the 4 corners of the rendered rect to derive
        // axis-aligned geographic bounds.
        let r = pdfRenderRect
        let rectValues = [Double(r.minX), Double(r.minY),
                          Double(r.maxX), Double(r.maxY),
                          Double(r.width), Double(r.height)]
        guard rectValues.allSatisfy(\.isFinite),
              r.width > 0, r.height > 0,
              rectValues.allSatisfy({ abs($0) <= maximumSafePDFCoordinateMagnitude }) else {
            return
        }
        let corners = [
            CGPoint(x: r.minX, y: r.minY),
            CGPoint(x: r.maxX, y: r.minY),
            CGPoint(x: r.maxX, y: r.maxY),
            CGPoint(x: r.minX, y: r.maxY)
        ]
        let geo = corners.map { transform.apply($0) }
        guard geo.allSatisfy(isValidEarthCoordinate) else { return }
        let lats = geo.map { $0.latitude }
        let lons = geo.map { $0.longitude }
        guard let minLat = lats.min(), let maxLat = lats.max(),
              let minLon = lons.min(), let maxLon = lons.max(),
              minLat < maxLat, minLon < maxLon else { return }

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
