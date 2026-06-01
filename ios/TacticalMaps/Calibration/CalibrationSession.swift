import Foundation
import CoreLocation
import CoreGraphics
import Combine

/// State machine for an in-progress fiduciary calibration of a PDF source.
///
/// Flow:
///   1. `start(for:)` flips the app into calibration mode for a specific
///      `PDFMapSource`.
///   2. The user taps known features on the PDF. Each tap is converted to a
///      PDF user-space point and held as `pendingTap` while a sheet asks for
///      the MGRS coordinate of that point.
///   3. `confirmFiduciary(mgrs:)` saves the (pdfPoint, lat/lon) pair.
///   4. Once 3+ fiduciaries are placed the user taps Finish; we run
///      `AffineFitter` and the resulting transform is applied to the source.
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

    /// Datum the sheet's grid references are in. Defaults to WGS84 (a no-op);
    /// set to GDA94/GDA2020 for Australian MGA sheets so fiduciary coordinates
    /// are shifted to WGS84 before storing. See `Datum`.
    @Published var datum: Datum = .wgs84

    /// The source being calibrated. Held weakly so we don't keep the old
    /// source alive after replacement.
    private(set) weak var source: PDFMapSource?

    func start(for source: PDFMapSource) {
        self.source = source
        // Seed with any existing fiduciaries so the user can refine instead of
        // start over.
        self.fiduciaries = source.fiduciaries ?? []
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

    /// User tapped a feature. We hold the geometry; the sheet collects MGRS.
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
        // The MGRS is in the sheet's datum; shift to WGS84 before storing so
        // every overlay (and the GeoJSON export) is in one consistent datum.
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

    /// Fit an affine to the current fiduciaries. Returns nil if fewer than 3
    /// are placed or the points are degenerate.
    func finish() -> AffineFitter.Result? {
        guard canFinish else { return nil }
        do {
            let result = try AffineFitter.fit(fiduciaries)
            lastFitRMSMetres = result.rmsMetres
            return result
        } catch {
            print("[Calibration] affine fit failed: \(error)")
            return nil
        }
    }
}
