import Foundation
import UIKit
import MapKit
import PDFKit

/// PDF-backed map source. Placement is a PdfGeoreference (page space ->
/// projected plane -> WGS84). It comes from the GeoPDF's own metadata (ISO /VP
/// or LGIDict, via GeoPDFReader), or the user's fiduciaries fitted in UTM
/// (applyCalibration), or for a plain PDF a provisional camera-centred guess
/// that's only there so the user can calibrate. That last one has
/// isUncalibrated set and the UI has to say so.
///
/// The live overlay still pins one rasterised image with a single linear map,
/// so bounds/placementTransform are a best-fit lon/lat view of the georef
/// until the tile renderer rework lands.
final class PDFMapSource: MapSource {
    let id = UUID()
    let displayName: String
    private(set) var kind: MapSourceKind
    private(set) var coverage: MKCoordinateRegion?
    private(set) var calibration: Calibration?
    let url: URL
    /// SHA-256 identity of the imported bytes. Import workers provide this
    /// off-main; restored legacy sessions may fill it while validating disk.
    let contentKey: String?

    /// The real placement. Everything below is derived from it.
    private(set) var georef: PdfGeoreference

    /// lat/lon box + crop rect + best-fit lon/lat affine for the overlay/tiler
    private(set) var bounds: GeoPDFReader.Bounds?

    /// Raw page user-space rect rasterised into cachedImage: the bounding
    /// rect of the georef crop (box origin included, /Rotate ignored).
    private(set) var pdfRenderRect: CGRect = .zero

    /// Fiduciaries from the most recent calibration, if any.
    private(set) var fiduciaries: [Fiduciary]?

    /// Saved points that aren't a calibration (a v1 set the UTM refit refused,
    /// collinear etc). Raw page space. The next calibration starts from them so
    /// nobody has to place them all again, same as Android pendingFiduciaries.
    private(set) var pendingFiduciaries: [Fiduciary] = []

    private var cachedImage: UIImage?
    private let cachedImageLock = NSLock()

    /// Plain PDF on a made-up placement. Never a basemap you can trust.
    var isUncalibrated: Bool { georef.origin == .provisional }

    /// PDF user space -> WGS84 for the single-affine overlay and tiler.
    var placementTransform: AffineTransform2D? { bounds?.placementAffine }

    init(url: URL, georef: PdfGeoreference, contentKey: String? = nil) {
        self.url = url
        self.contentKey = contentKey
        self.displayName = url.deletingPathExtension().lastPathComponent
        self.georef = georef
        self.kind = Self.kind(for: georef.origin)
        publish(georef, displayBounds: nil)
    }

    /// A placement only known as a lat/lon box (or lon/lat affine): old
    /// sessions that can't be rebuilt, and tests. Always provisional.
    convenience init(url: URL,
                     bounds: GeoPDFReader.Bounds,
                     preflightMediaBox: CGRect? = nil,
                     contentKey: String? = nil) {
        var crop = (bounds.pdfCropRect ?? preflightMediaBox ?? Self.mediaBox(for: url)).standardized
        if !(crop.width > 0 && crop.height > 0) { crop = CGRect(x: 0, y: 0, width: 1, height: 1) }
        let georef = Self.provisionalGeoref(for: bounds, crop: crop)
            ?? PdfGeoreference.provisional(pageBox: crop, rotation: 0, centredOn: bounds.centre)
            ?? Self.lastResortGeoref
        self.init(url: url, georef: georef, contentKey: contentKey)
        // keep the caller's box verbatim so a round trip doesn't wobble in the last bit
        publish(georef, displayBounds: GeoPDFReader.Bounds(
            southWest: bounds.southWest, northEast: bounds.northEast,
            pdfCropRect: crop, placementAffine: georef.bestFitLatLonAffine()))
    }

    private static func provisionalGeoref(for b: GeoPDFReader.Bounds, crop: CGRect) -> PdfGeoreference? {
        let c = crop.standardized
        guard c.width > 0, c.height > 0 else { return nil }
        let t = b.placementAffine ?? AffineTransform2D(
            a: (b.northEast.longitude - b.southWest.longitude) / Double(c.width), b: 0,
            c: b.southWest.longitude - (b.northEast.longitude - b.southWest.longitude) / Double(c.width) * Double(c.minX),
            d: 0, e: (b.northEast.latitude - b.southWest.latitude) / Double(c.height),
            f: b.southWest.latitude - (b.northEast.latitude - b.southWest.latitude) / Double(c.height) * Double(c.minY))
        return PdfGeoreference.geographic(latLonAffine: t, crop: c, origin: .provisional)
    }

    /// a 1 pt page on null island, only reachable with garbage bounds
    private static let lastResortGeoref = PdfGeoreference(
        crs: .geographic, datum: .wgs84, affine: PlaneAffine(a: 1e-4, b: 0, c: 0, d: 0, e: 1e-4, f: 0),
        crop: [PdfPagePoint(x: 0, y: 0), PdfPagePoint(x: 1, y: 0), PdfPagePoint(x: 1, y: 1), PdfPagePoint(x: 0, y: 1)],
        origin: .provisional, datumAssumed: true)

    private static func kind(for origin: PdfGeoreference.Origin) -> MapSourceKind {
        switch origin {
        case .adobeVP, .lgiDict: return .geoPDF
        case .fiduciaries, .provisional: return .calibratedPDF
        }
    }

    private func publish(_ g: PdfGeoreference, displayBounds: GeoPDFReader.Bounds?) {
        georef = g
        kind = Self.kind(for: g.origin)
        pdfRenderRect = g.cropBoundingRect
        bounds = displayBounds ?? GeoPDFReader.Bounds(georef: g)
        if let b = bounds {
            let span = MKCoordinateSpan(
                latitudeDelta:  abs(b.northEast.latitude  - b.southWest.latitude)  * 1.2,
                longitudeDelta: abs(b.northEast.longitude - b.southWest.longitude) * 1.2
            )
            coverage = MKCoordinateRegion(center: b.centre, span: span)
        } else {
            coverage = nil
        }
        cachedImageLock.lock()
        cachedImage = nil
        cachedImageLock.unlock()
    }

    static func mediaBox(for url: URL) -> CGRect {
        guard let doc = CGPDFDocument(url as CFURL), let page = doc.page(at: 1) else {
            return CGRect(x: 0, y: 0, width: 1, height: 1)
        }
        return GeoPDFReader.pageGeometry(page).cropBox
    }

    /// The rasterised page if already rendered, nil if not yet. Does NOT
    /// trigger a blocking rasterisation. Lets the overlay sync render off
    /// main thread and attach the page when its ready.
    var cachedRenderedImage: UIImage? {
        cachedImageLock.lock()
        defer { cachedImageLock.unlock() }
        return cachedImage
    }

    /// Cached rasterisation of the crop, in raw page user space.
    /// Heavy (decodes page to bitmap) - call off main thread on first use;
    /// subsequent calls just return the cache.
    func renderedImage() -> UIImage? {
        cachedImageLock.lock()
        if let cached = cachedImage {
            cachedImageLock.unlock()
            return cached
        }
        let crop = georef.crop
        let rect = pdfRenderRect
        let pageIndex = georef.page
        cachedImageLock.unlock()
        guard let img = PDFRasteriser.render(url: url, pageIndex: pageIndex, cropRect: rect, cropPolygon: crop)
        else { return nil }
        cachedImageLock.lock()
        defer { cachedImageLock.unlock() }
        if let cached = cachedImage { return cached }
        cachedImage = img
        return img
    }

    /// Fiduciary calibration. The user's points (WGS84, already datum
    /// shifted) get refit in the UTM zone of the first one; transform is the
    /// session's lon/lat affine, kept for the persisted record only.
    /// Refuses junk (non-finite, singular, collinear) and leaves the old
    /// placement alone.
    func applyCalibration(transform: AffineTransform2D,
                          fiduciaries: [Fiduciary]) {
        guard fiduciaries.count >= 3,
              fiduciaries.allSatisfy(isSafeAffineInput),
              transform.hasFiniteCoefficients,
              transform.inverted() != nil else { return }
        let r = pdfRenderRect
        let rectValues = [Double(r.minX), Double(r.minY), Double(r.maxX), Double(r.maxY)]
        guard rectValues.allSatisfy(\.isFinite), r.width > 0, r.height > 0,
              rectValues.allSatisfy({ abs($0) <= maximumSafePDFCoordinateMagnitude }),
              let fitted = FiduciaryFitter.georeference(fromWGS84: fiduciaries, crop: r, page: georef.page),
              let display = GeoPDFReader.Bounds(georef: fitted) else { return }
        publish(fitted, displayBounds: display)
        calibration = .fiduciaries(fiduciaries, transform: transform)
        self.fiduciaries = fiduciaries
        pendingFiduciaries = []
    }

    /// Park points that couldn't become a calibration. No-op once calibrated,
    /// junk (non-finite, off the earth) gets dropped.
    func keepPendingFiduciaries(_ fids: [Fiduciary]) {
        guard calibration == nil else { return }
        pendingFiduciaries = Array(fids.filter(isSafeAffineInput).prefix(Self.maximumPendingFiduciaries))
    }

    /// way more than anyone places by hand, keeps a junk record small
    static let maximumPendingFiduciaries = 256

    /// Restore path: a fiduciary georef + its record straight from disk, no
    /// refit (a later sheet-datum calibration can't be rebuilt from WGS84 points).
    func adoptCalibration(georef fitted: PdfGeoreference, fiduciaries: [Fiduciary], transform: AffineTransform2D) {
        guard fitted.origin == .fiduciaries, fitted.isStructurallyValid,
              fiduciaries.count >= 3, fiduciaries.allSatisfy(isSafeAffineInput),
              transform.hasFiniteCoefficients, transform.inverted() != nil,
              let display = GeoPDFReader.Bounds(georef: fitted) else { return }
        publish(fitted, displayBounds: display)
        calibration = .fiduciaries(fiduciaries, transform: transform)
        self.fiduciaries = fiduciaries
        pendingFiduciaries = []
    }
}
