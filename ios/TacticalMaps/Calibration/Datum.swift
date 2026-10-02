import Foundation
import CoreLocation

/// Geodetic datum that a calibrated map's grid references are expressed in.
/// Most Aussie defence / topo sheets use MGA (GDA94 or GDA2020), not WGS84,
/// and they can be up to ~1.8 m apart. When calibrating such a sheet the MGRS
/// the user types is in the sheet's datum; we shift each fiduciary to WGS84
/// before storing so overlays line up with satellite basemap and GeoJSON exports.
enum Datum: String, CaseIterable, Codable {
    case wgs84
    case gda94
    case gda2020

    var displayName: String {
        switch self {
        case .wgs84:   return "WGS84"
        case .gda94:   return "GDA94 / MGA94"
        case .gda2020: return "GDA2020 / MGA2020"
        }
    }

    /// Shift a coordinate in this datum to WGS84. GDA2020 and WGS84 are
    /// basically coincident (both ~ITRF2014 @ epoch 2020); GDA94 goes through
    /// the shared GeoDatum row (ICSM 7-param), same numbers the GeoPDF path
    /// and Android use, so a sheet can't land in two places any more.
    func toWGS84(_ c: CLLocationCoordinate2D) -> CLLocationCoordinate2D {
        switch self {
        case .wgs84, .gda2020: return c
        case .gda94:
            guard let w = GeoDatum.named("GDA94")?.toWGS84(lat: c.latitude, lon: c.longitude) else { return c }
            return CLLocationCoordinate2D(latitude: w.lat, longitude: w.lon)
        }
    }
}
