import Foundation
import CoreLocation
import CoreGraphics

/// One known correspondence between a PDF page point and a real-world MGRS grid
/// reference. Need at least three to fit an affine; more gives you a least-squares
/// best fit and an RMS residual to show the user.
///
/// Stored as primitive doubles b/c it mirrors the Android model and avoids the
/// CLLocationCoordinate2D/CGPoint not-Hashable headache.
struct Fiduciary: Identifiable, Codable, Hashable {
    let id: UUID
    /// PDF user-space (origin bottom-left, units = points).
    var pdfX: Double
    var pdfY: Double
    var mgrs: String
    var latitude: Double
    var longitude: Double
    /// Free-form label (e.g. "NE corner of grid").
    var label: String?

    init(id: UUID = UUID(),
         pdfX: Double, pdfY: Double,
         mgrs: String,
         latitude: Double, longitude: Double,
         label: String? = nil) {
        self.id = id
        self.pdfX = pdfX; self.pdfY = pdfY
        self.mgrs = mgrs
        self.latitude = latitude; self.longitude = longitude
        self.label = label
    }

    var pdfPoint: CGPoint { CGPoint(x: pdfX, y: pdfY) }
    var wgs84: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: latitude, longitude: longitude) }
}
