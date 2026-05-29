import Foundation
import CoreLocation

/// Persists the currently-active calibrated PDF map source across app
/// launches. The PDF file itself is already copied to the Documents
/// directory on import, so it survives an app relaunch. What we add
/// here is a small JSON sidecar in `UserDefaults` that captures the
/// non-bitmap state — file name, GeoPDF bounds (sw/ne lat/lng), PDF
/// crop rect, plus (when calibrated) the affine + fiduciaries — so
/// we can reconstruct the same `PDFMapSource` on startup without
/// re-parsing or asking the user to re-import.
enum PDFSessionStore {
    private static let key = "active_pdf_v1"

    static func save(_ source: PDFMapSource) {
        guard let bounds = source.bounds else {
            /// No bounds at all — nothing to anchor the page to.
            return
        }
        /// Only persist genuinely georeferenced sources: a GeoPDF whose
        /// position came from embedded tags, or one the user has fitted
        /// with fiduciaries. A plain PDF carrying only the rough
        /// camera-centred fallback box isn't worth resurrecting — it
        /// would reappear at an arbitrary location on the next launch.
        guard source.kind == .geoPDF || source.calibration != nil else { return }
        let cropRect = source.pdfRenderRect
        let cal: PersistedCalibration?
        if case .fiduciaries(let fids, let transform) = source.calibration {
            cal = PersistedCalibration(fids: fids, transform: transform)
        } else {
            cal = nil
        }
        let dto = PersistedPDF(
            fileName: source.url.lastPathComponent,
            displayName: source.displayName,
            swLat: bounds.southWest.latitude,
            swLng: bounds.southWest.longitude,
            neLat: bounds.northEast.latitude,
            neLng: bounds.northEast.longitude,
            cropX: Double(cropRect.origin.x),
            cropY: Double(cropRect.origin.y),
            cropW: Double(cropRect.size.width),
            cropH: Double(cropRect.size.height),
            kind: source.kind.rawValue,
            calibration: cal
        )
        if let data = try? JSONEncoder().encode(dto) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    static func load() -> PDFMapSource? {
        guard let data = UserDefaults.standard.data(forKey: key) else { return nil }
        guard let dto = try? JSONDecoder().decode(PersistedPDF.self, from: data) else {
            UserDefaults.standard.removeObject(forKey: key)
            return nil
        }
        let docsDir = FileManager.default.urls(
            for: .documentDirectory, in: .userDomainMask
        ).first!
        let url = docsDir.appendingPathComponent(dto.fileName)
        guard FileManager.default.fileExists(atPath: url.path) else {
            NSLog("[PDFSessionStore] file vanished, clearing: \(url.path)")
            UserDefaults.standard.removeObject(forKey: key)
            return nil
        }
        let bounds = GeoPDFReader.Bounds(
            southWest: CLLocationCoordinate2D(latitude: dto.swLat, longitude: dto.swLng),
            northEast: CLLocationCoordinate2D(latitude: dto.neLat, longitude: dto.neLng),
            pdfCropRect: CGRect(
                x: dto.cropX, y: dto.cropY,
                width: dto.cropW, height: dto.cropH
            )
        )
        let source = PDFMapSource(
            url: url,
            bounds: bounds,
            fromGeoPDF: dto.kind == MapSourceKind.geoPDF.rawValue
        )
        if let cal = dto.calibration {
            source.applyCalibration(transform: cal.transform, fiduciaries: cal.fids)
        }
        return source
    }

    static func clear() {
        UserDefaults.standard.removeObject(forKey: key)
    }
}

private struct PersistedPDF: Codable {
    let fileName: String
    let displayName: String
    let swLat: Double
    let swLng: Double
    let neLat: Double
    let neLng: Double
    let cropX: Double
    let cropY: Double
    let cropW: Double
    let cropH: Double
    let kind: String
    let calibration: PersistedCalibration?
}

private struct PersistedCalibration: Codable {
    let fids: [Fiduciary]
    let transform: AffineTransform2D
}
