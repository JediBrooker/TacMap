import Foundation
import CoreLocation

#if DEBUG
/// Launch environment hooks for on-device verification runs (screenshots,
/// grid alignment). DEBUG builds only, the whole type is compiled out of
/// release. See docs/DEBUG_HOOKS.md.
///
///   TACMAP_DEBUG_IMPORT_PDF=/abs/path.pdf   import it through the normal pipeline, no picker
///   TACMAP_DEBUG_CAMERA=lat,lon,zoom[,heading]   set the camera once launched (after the import)
///   TACMAP_DEBUG_GRID=1   switch the MGRS grid on
enum DebugHooks {
    struct Camera: Equatable {
        let latitude: Double
        let longitude: Double
        let zoom: Double
        let heading: Double
    }

    static var environment: [String: String] { ProcessInfo.processInfo.environment }

    static var importPDF: URL? {
        guard let path = environment["TACMAP_DEBUG_IMPORT_PDF"], path.hasPrefix("/") else { return nil }
        return URL(fileURLWithPath: path)
    }

    static var camera: Camera? { parseCamera(environment["TACMAP_DEBUG_CAMERA"]) }

    static var calibrationPoint: (x: Double, y: Double)? {
        parseCalibrationPoint(environment["TACMAP_DEBUG_CALIBRATION_POINT"])
    }

    static func parseCalibrationPoint(_ raw: String?) -> (x: Double, y: Double)? {
        guard let raw else { return nil }
        let parts = raw.split(separator: ",").map { Double($0.trimmingCharacters(in: .whitespaces)) }
        guard parts.count == 2, let x = parts[0], let y = parts[1], x.isFinite, y.isFinite else { return nil }
        return (x, y)
    }

    static var gridOn: Bool { environment["TACMAP_DEBUG_GRID"] == "1" }

    /// any hook set: a scripted run. No location prompt and no first run tour
    /// on top of the map (system alerts land in screenshots)
    static var active: Bool { importPDF != nil || camera != nil || gridOn || calibrationPoint != nil }

    /// "lat,lon,zoom[,heading]", junk is ignored rather than guessed at
    static func parseCamera(_ raw: String?) -> Camera? {
        guard let raw else { return nil }
        let parts = raw.split(separator: ",").map { Double($0.trimmingCharacters(in: .whitespaces)) }
        guard parts.count == 3 || parts.count == 4, parts.allSatisfy({ $0?.isFinite == true }) else { return nil }
        let lat = parts[0]!, lon = parts[1]!, zoom = parts[2]!
        guard abs(lat) <= 85, abs(lon) <= 180 else { return nil }
        return Camera(latitude: lat, longitude: lon, zoom: zoom, heading: parts.count == 4 ? parts[3]! : 0)
    }
}
#endif
