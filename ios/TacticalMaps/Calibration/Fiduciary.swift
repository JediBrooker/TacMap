import Foundation
import CoreLocation
import CoreGraphics

/// One known correspondence between a point in PDF page coordinates and a real-world
/// MGRS grid reference. A minimum of three are required to fit an affine transform;
/// more produce a least-squares best fit and an RMS residual you can show the user.
///
/// Stored as primitive doubles (mirrors the Android model and avoids tangles with
/// CLLocationCoordinate2D/CGPoint not being Hashable).
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
