import Foundation
import CoreLocation
import CoreGraphics
import Combine

/// State machine for an in-progress fiduciary calibration of a PDF source.
///
/// Basically: start(for:) enters calibration mode, user taps known features
/// on the PDF, each tap becomes a pendingTap while we ask for MGRS coords,
/// confirmFiduciary saves the (pdfPoint, lat/lon) pair. Once 3+ fiduciaries
/// are placed user taps Finish and we fit them in UTM (FiduciaryFitter).
final class CalibrationSession: ObservableObject {

    /// Geometry of a tap that's awaiting MGRS entry.
    struct PendingTap {
        /// PDF user space (y-up, origin bottom-left of media box).
        let pdfPoint: CGPoint
        /// Screen point where the user actually tapped (so we can flash a
        /// pending marker until they confirm).
        let screenPoint: CGPoint
    }

    @Published private(set) var isCalibrating: Bool = false
    @Published private(set) var fiduciaries: [Fiduciary] = []
    @Published private(set) var pendingTap: PendingTap? = nil
    @Published private(set) var lastFitRMSMetres: Double? = nil

    /// Datum the sheet's grid references are in. Defaults to WGS84 (no-op);
    /// set to GDA94/GDA2020 for Aussie MGA sheets so fiduciary coords get
    /// shifted to WGS84 before storing. See `Datum`.
    @Published var datum: Datum = .wgs84

    /// Source being calibrated. Weak ref so we don't keep the old source
    /// alive after replacement.
    private(set) weak var source: PDFMapSource?

    func start(for source: PDFMapSource) {
        self.source = source
        // Seed with existing fiduciaries so user can refine instead of
        // starting over. no calibration yet but saved points that couldn't be
        // refit (old v1 set) -> start from those
        self.fiduciaries = source.fiduciaries ?? source.pendingFiduciaries
        self.pendingTap = nil
        self.lastFitRMSMetres = nil
        self.isCalibrating = true
    }

    func cancel() {
        isCalibrating = false
        fiduciaries = []
        pendingTap = nil
        source = nil
        lastFitRMSMetres = nil
    }

    /// User tapped a feature. Hold the geometry, sheet collects MGRS.
    func recordTap(pdfPoint: CGPoint, screenPoint: CGPoint) {
        pendingTap = PendingTap(pdfPoint: pdfPoint, screenPoint: screenPoint)
    }

    func clearPendingTap() {
        pendingTap = nil
    }

    /// Convert pending tap + MGRS string into a saved fiduciary.
    /// Returns false if MGRS fails to parse.
    @discardableResult
    func confirmFiduciary(mgrs: String, label: String? = nil) -> Bool {
        guard let pending = pendingTap,
              let parsed = MGRSFormatter.coordinate(from: mgrs) else { return false }
        // MGRS is in the sheet's datum, shift to WGS84 before storing so
        // overlays and GeoJSON export all use one consistent datum.
        let coord = datum.toWGS84(parsed)
        let fid = Fiduciary(
            pdfX: Double(pending.pdfPoint.x),
            pdfY: Double(pending.pdfPoint.y),
            mgrs: mgrs,
            latitude: coord.latitude,
            longitude: coord.longitude,
            label: label
        )
        fiduciaries.append(fid)
        pendingTap = nil
        return true
    }

    func removeFiduciary(id: UUID) {
        fiduciaries.removeAll { $0.id == id }
    }

    var canFinish: Bool { fiduciaries.count >= 3 }

    struct FinishResult {
        /// page -> UTM (zone of the first point) fit of the fiduciaries
        let georef: PdfGeoreference
        /// best-fit lon/lat view of it, what the persisted record keeps
        let transform: AffineTransform2D
        let rmsMetres: Double
        let crossValidated: Bool
    }

    /// Fit the fiduciaries in UTM (zone of the first point) over the source's
    /// crop. nil if fewer than 3 are placed or they're collinear/clustered.
    func finish() -> FinishResult? {
        guard canFinish, let source else { return nil }
        guard let georef = FiduciaryFitter.georeference(fromWGS84: fiduciaries, crop: source.pdfRenderRect,
                                                        page: source.georef.page),
              let transform = georef.bestFitLatLonAffine() else {
            print("[Calibration] fiduciary fit refused")
            return nil
        }
        let rms = georef.fit?.rmsMetres ?? 0
        lastFitRMSMetres = rms
        return FinishResult(georef: georef, transform: transform, rmsMetres: rms,
                            crossValidated: georef.fit?.crossValidated ?? false)
    }
}
