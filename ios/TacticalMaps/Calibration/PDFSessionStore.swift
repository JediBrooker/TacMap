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
        // Remember this PDF's calibration in the per-file library too, so it can
        // be restored even after the user switches to a different PDF and back.
        if let cal { saveToLibrary(fileName: source.url.lastPathComponent, cal) }
        let dto = PersistedPDF(
            fileName: source.url.lastPathComponent,
            swLat: bounds.southWest.latitude,
            swLng: bounds.southWest.longitude,
            neLat: bounds.northEast.latitude,
            neLng: bounds.northEast.longitude,
            cropX: Double(cropRect.origin.x),
            cropY: Double(cropRect.origin.y),
            cropW: Double(cropRect.size.width),
            cropH: Double(cropRect.size.height),
            kind: source.kind.rawValue,
            calibration: cal,
            placementAffine: bounds.placementAffine
        )
        do {
            let data = try JSONEncoder().encode(dto)
            UserDefaults.standard.set(data, forKey: key)
        } catch {
            /// Don't silently drop the write — a stale entry would then
            /// be restored on next launch with no clue why.
            NSLog("[PDFSessionStore] failed to encode active PDF: \(error)")
        }
    }

    static func load() -> PDFMapSource? {
        guard let data = UserDefaults.standard.data(forKey: key) else { return nil }
        guard let dto = try? JSONDecoder().decode(PersistedPDF.self, from: data) else {
            UserDefaults.standard.removeObject(forKey: key)
            return nil
        }
        guard let docsDir = FileManager.default.urls(
            for: .documentDirectory, in: .userDomainMask
        ).first else { return nil }
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
            ),
            placementAffine: dto.placementAffine
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

    // MARK: - Per-PDF calibration library
    //
    // Beyond the single *active* source above, keep every PDF's calibration
    // keyed by file name, so importing / switching between several PDFs restores
    // each one's own fiduciaries + affine instead of only the last one used.

    private static let libraryKey = "pdf_calibrations_v1"

    /// Apply a previously-saved calibration for this source's file, if any.
    /// Called on import so a re-imported PDF lands already calibrated.
    static func applyCalibrationIfKnown(to source: PDFMapSource) {
        guard let cal = loadLibrary()[source.url.lastPathComponent] else { return }
        source.applyCalibration(transform: cal.transform, fiduciaries: cal.fids)
    }

    private static func saveToLibrary(fileName: String, _ cal: PersistedCalibration) {
        var lib = loadLibrary()
        lib[fileName] = cal
        if let data = try? JSONEncoder().encode(lib) {
            UserDefaults.standard.set(data, forKey: libraryKey)
        }
    }

    private static func loadLibrary() -> [String: PersistedCalibration] {
        guard let data = UserDefaults.standard.data(forKey: libraryKey),
              let lib = try? JSONDecoder().decode([String: PersistedCalibration].self, from: data)
        else { return [:] }
        return lib
    }
}

private struct PersistedPDF: Codable {
    /// `displayName` is intentionally not stored: PDFMapSource always
    /// derives it from the file's URL on init, so persisting it would be
    /// dead data that could drift out of sync with the filename.
    let fileName: String
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
    /// GeoPDF auto-fit placement affine (rotation/scale-correct). Optional so
    /// older persisted entries (which predate it) still decode → bbox fallback.
    let placementAffine: AffineTransform2D?
}

private struct PersistedCalibration: Codable {
    let fids: [Fiduciary]
    let transform: AffineTransform2D
}
