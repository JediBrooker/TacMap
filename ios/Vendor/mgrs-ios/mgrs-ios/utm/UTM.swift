//
//  UTM.swift
//  mgrs-ios
//
//  Created by Brian Osborn on 8/23/22.
//

import Foundation
import Grid
import MapKit

/**
 * Universal Transverse Mercator Projection
 */
public class UTM {

    /**
     * Zone number
     */
    public let zone: Int
    
    /**
     * Hemisphere
     */
    public let hemisphere: Hemisphere
    
    /**
     * Easting
     */
    public let easting: Double
    
    /**
     * Northing
     */
    public let northing: Double
    
    /**
     * UTM string pattern
     */
    private static let utmPattern = "^(\\d{1,2})\\s*([N|S])\\s*(\\d+\\.?\\d*)\\s*(\\d+\\.?\\d*)$"
    
    /**
     * UTM regular expression
     */
    private static let utmExpression = try! NSRegularExpression(pattern: utmPattern, options: .caseInsensitive)
    
    /**
     * Create a point from the UTM attributes
     *
     * @param zone
     *            zone number
     * @param hemisphere
     *            hemisphere
     * @param easting
     *            easting
     * @param northing
     *            northing
     * @return point
     */
    public static func point(_ zone: Int, _ hemisphere: Hemisphere, _ easting: Double, _ northing: Double) -> GridPoint {
        return UTM(zone, hemisphere, easting, northing).toPoint()
    }
    
    /**
     * Initialize
     *
     * @param zone
     *            zone number
     * @param hemisphere
     *            hemisphere
     * @param easting
     *            easting
     * @param northing
     *            northing
     */
    public init(_ zone: Int, _ hemisphere: Hemisphere, _ easting: Double, _ northing: Double) {
        self.zone = zone
        self.hemisphere = hemisphere
        self.easting = easting
        self.northing = northing
    }
    
    /**
     * Convert to a point
     *
     * @return point
     */
    public func toPoint() -> GridPoint {
        // Snyder's UTM → geographic conversion (USGS Bulletin 1532, 1987).
        // Replaces the upstream single-expression formula, which exceeds the
        // Swift 6 (Xcode 26) type-checker's complexity budget. Mathematically
        // identical to within centimetre precision for any UTM zone.
        let a: Double = 6378137.0                  // WGS84 semi-major axis
        let f: Double = 1.0 / 298.257223563        // WGS84 flattening
        let e2: Double = 2*f - f*f                 // first eccentricity squared
        let eDash2: Double = e2 / (1 - e2)         // second eccentricity squared
        let k0: Double = 0.9996                    // UTM scale factor
        let FE: Double = 500000.0                  // false easting

        var north = northing
        if hemisphere == Hemisphere.SOUTH {
            north -= 10000000.0                    // remove southern-hemisphere offset
        }

        let xE: Double = easting - FE
        let M: Double = north / k0

        // Footprint latitude phi1 via Snyder eq. 7-19 (series in mu).
        let mu: Double = M / (a * (1 - e2/4 - 3*e2*e2/64 - 5*e2*e2*e2/256))
        let e1: Double = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))
        let e1_2: Double = e1 * e1
        let e1_3: Double = e1_2 * e1
        let e1_4: Double = e1_2 * e1_2
        let phi1: Double = mu
            + (3*e1/2 - 27*e1_3/32) * sin(2*mu)
            + (21*e1_2/16 - 55*e1_4/32) * sin(4*mu)
            + (151*e1_3/96) * sin(6*mu)
            + (1097*e1_4/512) * sin(8*mu)

        let sinPhi1: Double = sin(phi1)
        let cosPhi1: Double = cos(phi1)
        let tanPhi1: Double = tan(phi1)
        let oneMinusE2Sin2: Double = 1 - e2 * sinPhi1 * sinPhi1
        let N1: Double = a / sqrt(oneMinusE2Sin2)
        let T1: Double = tanPhi1 * tanPhi1
        let C1: Double = eDash2 * cosPhi1 * cosPhi1
        let R1: Double = a * (1 - e2) / pow(oneMinusE2Sin2, 1.5)
        let D: Double = xE / (N1 * k0)
        let D2: Double = D*D
        let D3: Double = D2*D
        let D4: Double = D2*D2
        let D5: Double = D4*D
        let D6: Double = D4*D2

        let phi: Double = phi1 - (N1 * tanPhi1 / R1) * (
            D2/2
            - (5 + 3*T1 + 10*C1 - 4*C1*C1 - 9*eDash2) * D4/24
            + (61 + 90*T1 + 298*C1 + 45*T1*T1 - 252*eDash2 - 3*C1*C1) * D6/720
        )
        let lamFromCentral: Double = (D
            - (1 + 2*T1 + C1) * D3/6
            + (5 - 2*C1 + 28*T1 - 3*C1*C1 + 8*eDash2 + 24*T1*T1) * D5/120
        ) / cosPhi1

        let lambda0Deg: Double = Double(zone) * 6 - 183
        var latitude: Double = phi * 180.0 / Double.pi
        var longitude: Double = lambda0Deg + lamFromCentral * 180.0 / Double.pi
        latitude = round(latitude * 10000000) / 10000000
        longitude = round(longitude * 10000000) / 10000000
        return GridPoint.degrees(longitude, latitude)
    }
    
    /**
     * Convert to a MGRS coordinate
     *
     * @return MGRS
     */
    public func toMGRS() -> MGRS {
        return MGRS.from(toPoint())
    }
    
    /**
     * Convert to a location coordinate
     *
     * @return coordinate
     */
    public func toCoordinate() -> CLLocationCoordinate2D {
        return toPoint().toCoordinate()
    }
    
    /**
     * Format to a UTM string
     *
     * @return UTM string
     */
    public func format() -> String {

        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.maximumFractionDigits = 2
        formatter.usesGroupingSeparator = false
        
        return String(format: "%02d", zone)
            + " "
            + (hemisphere == Hemisphere.NORTH ? GridConstants.NORTH_CHAR : GridConstants.SOUTH_CHAR)
            + " "
            + formatter.string(from: easting as NSNumber)!
            + " "
            + formatter.string(from: northing as NSNumber)!
    }
    
    public var description: String {
        return format()
    }
    
    /**
     * Return whether the given string is valid UTM string
     *
     * @param utm
     *            potential UTM string
     * @return true if UTM string is valid, false otherwise
     */
    public static func isUTM(_ utm: String) -> Bool {
        return utmExpression.matches(in: utm, range: NSMakeRange(0, utm.count)).count > 0
    }
    
    /**
     * Parse a UTM value (Zone N|S Easting Northing)
     *
     * @param utm
     *            UTM value
     * @return UTM
     */
    public static func parse(_ utm: String) -> UTM {
        let matches = utmExpression.matches(in: utm, range: NSMakeRange(0, utm.count))
        if matches.count <= 0 {
            preconditionFailure("Invalid UTM: \(utm)")
        }

        let match = matches[0]
        let utmString = utm as NSString
        
        let zone = Int(utmString.substring(with: match.range(at: 1)))!
        let hemisphere = utmString.substring(with: match.range(at: 2)).caseInsensitiveCompare(GridConstants.NORTH_CHAR) == .orderedSame ? Hemisphere.NORTH : Hemisphere.SOUTH
        let easting = Double(utmString.substring(with: match.range(at: 3)))!
        let northing = Double(utmString.substring(with: match.range(at: 4)))!
        
        return UTM(zone, hemisphere, easting, northing)
    }
    
    /**
     * Parse a UTM value (Zone N|S Easting Northing) into a location coordinate
     *
     * @param utm
     *            UTM value
     * @return coordinate
     */
    public static func parseToCoordinate(_ utm: String) -> CLLocationCoordinate2D {
        var coordinate = kCLLocationCoordinate2DInvalid
        if isUTM(utm) {
            coordinate = parse(utm).toCoordinate()
        }
        return coordinate
    }
    
    /**
     * Create from a point
     *
     * @param point
     *            point
     * @return UTM
     */
    public static func from(_ point: GridPoint) -> UTM {
        return from(point, GridZones.zoneNumber(point))
    }

    /**
     * Create from a point and zone number
     *
     * @param point
     *            point
     * @param zone
     *            zone number
     * @return UTM
     */
    public static func from(_ point: GridPoint, _ zone: Int) -> UTM {
        return from(point, zone, Hemisphere.from(point))
    }

    /**
     * Create from a coordinate, zone number, and hemisphere
     *
     * @param point
     *            coordinate
     * @param zone
     *            zone number
     * @param hemisphere
     *            hemisphere
     * @return UTM
     */
    public static func from(_ point: GridPoint, _ zone: Int, _ hemisphere: Hemisphere) -> UTM {

        let pointDegrees = point.toDegrees()

        let latitude = pointDegrees.latitude
        let longitude = pointDegrees.longitude

        // Snyder's geographic → UTM conversion (USGS Bulletin 1532, 1987).
        // Replaces the upstream single-expression formula, which exceeds the
        // Swift 6 (Xcode 26) type-checker's complexity budget.
        let a: Double = 6378137.0                  // WGS84 semi-major axis
        let f: Double = 1.0 / 298.257223563        // WGS84 flattening
        let e2: Double = 2*f - f*f                 // first eccentricity squared
        let eDash2: Double = e2 / (1 - e2)         // second eccentricity squared
        let k0: Double = 0.9996                    // UTM scale factor
        let FE: Double = 500000.0                  // false easting

        let phi: Double = latitude * Double.pi / 180.0
        let lambda: Double = longitude * Double.pi / 180.0
        let lambda0: Double = (Double(zone) * 6 - 183) * Double.pi / 180.0

        let sinPhi: Double = sin(phi)
        let cosPhi: Double = cos(phi)
        let tanPhi: Double = tan(phi)
        let N: Double = a / sqrt(1 - e2 * sinPhi * sinPhi)
        let T: Double = tanPhi * tanPhi
        let C: Double = eDash2 * cosPhi * cosPhi
        let A: Double = cosPhi * (lambda - lambda0)
        let e4: Double = e2 * e2
        let e6: Double = e4 * e2

        // Meridional arc M from the equator.
        let M: Double = a * (
            (1 - e2/4 - 3*e4/64 - 5*e6/256) * phi
            - (3*e2/8 + 3*e4/32 + 45*e6/1024) * sin(2*phi)
            + (15*e4/256 + 45*e6/1024) * sin(4*phi)
            - (35*e6/3072) * sin(6*phi)
        )

        let A2: Double = A*A
        let A3: Double = A2*A
        let A4: Double = A2*A2
        let A5: Double = A4*A
        let A6: Double = A4*A2

        var easting: Double = k0 * N * (
            A
            + (1 - T + C) * A3/6
            + (5 - 18*T + T*T + 72*C - 58*eDash2) * A5/120
        ) + FE

        var northing: Double = k0 * (
            M + N * tanPhi * (
                A2/2
                + (5 - T + 9*C + 4*C*C) * A4/24
                + (61 - 58*T + T*T + 600*C - 330*eDash2) * A6/720
            )
        )

        easting = round(easting * 100) * 0.01


        if hemisphere == Hemisphere.SOUTH {
            northing = northing + 10000000
        }

        northing = round(northing * 100) * 0.01

        return UTM(zone, hemisphere, easting, northing)
    }
    
    /**
     * Create from a coordinate
     *
     * @param coordinate
     *            coordinate
     * @return UTM
     */
    public static func from(_ coordinate: CLLocationCoordinate2D) -> UTM {
        return from(coordinate.longitude, coordinate.latitude)
    }
    
    /**
     * Create from a coordinate in degrees
     *
     * @param longitude
     *            longitude
     * @param latitude
     *            latitude
     * @return UTM
     */
    public static func from(_ longitude: Double, _ latitude: Double) -> UTM {
        return from(longitude, latitude, GridUnit.DEGREE)
    }
    
    /**
     * Create from a coordinate in the unit
     *
     * @param longitude
     *            longitude
     * @param latitude
     *            latitude
     * @param unit
     *            unit
     * @return UTM
     */
    public static func from(_ longitude: Double, _ latitude: Double, _ unit: GridUnit) -> UTM {
        return from(GridPoint(longitude, latitude, unit))
    }
    
    /**
     * Create from a coordinate in degrees and zone number
     *
     * @param longitude
     *            longitude
     * @param latitude
     *            latitude
     * @param zone
     *            zone number
     * @return UTM
     */
    public static func from(_ longitude: Double, _ latitude: Double, _ zone: Int) -> UTM {
        return from(longitude, latitude, GridUnit.DEGREE, zone)
    }
    
    /**
     * Create from a coordinate in the unit and zone number
     *
     * @param longitude
     *            longitude
     * @param latitude
     *            latitude
     * @param unit
     *            unit
     * @param zone
     *            zone number
     * @return UTM
     */
    public static func from(_ longitude: Double, _ latitude: Double, _ unit: GridUnit, _ zone: Int) -> UTM {
        return from(GridPoint(longitude, latitude, unit), zone)
    }
    
    /**
     * Create from a coordinate in degrees, zone number, and hemisphere
     *
     * @param longitude
     *            longitude
     * @param latitude
     *            latitude
     * @param zone
     *            zone number
     * @param hemisphere
     *            hemisphere
     * @return UTM
     */
    public static func from(_ longitude: Double, _ latitude: Double, _ zone: Int, _ hemisphere: Hemisphere) -> UTM {
        return from(longitude, latitude, GridUnit.DEGREE, zone, hemisphere)
    }
    
    /**
     * Create from a coordinate in the unit, zone number, and hemisphere
     *
     * @param longitude
     *            longitude
     * @param latitude
     *            latitude
     * @param unit
     *            unit
     * @param zone
     *            zone number
     * @param hemisphere
     *            hemisphere
     * @return UTM
     */
    public static func from(_ longitude: Double, _ latitude: Double, _ unit: GridUnit, _ zone: Int, _ hemisphere: Hemisphere) -> UTM {
        return from(GridPoint(longitude, latitude, unit), zone, hemisphere)
    }
    
    /**
     * Format to a UTM string from a point
     *
     * @param point
     *            point
     * @return UTM string
     */
    public static func format(_ point: GridPoint) -> String {
        return from(point).format()
    }

    /**
     * Format to a UTM string from a point and zone number
     *
     * @param point
     *            point
     * @param zone
     *            zone number
     * @return UTM string
     */
    public static func format(_ point: GridPoint, _ zone: Int) -> String {
        return from(point, zone).format()
    }

    /**
     * Format to a UTM string from a coordinate, zone number, and hemisphere
     *
     * @param point
     *            coordinate
     * @param zone
     *            zone number
     * @param hemisphere
     *            hemisphere
     * @return UTM string
     */
    public static func format(_ point: GridPoint, _ zone: Int, _ hemisphere: Hemisphere) -> String {
        return from(point, zone, hemisphere).format()
    }
    
    /**
     * Format to a UTM string from a coordinate
     *
     * @param coordinate
     *            coordinate
     * @return UTM string
     */
    public static func format(_ coordinate: CLLocationCoordinate2D) -> String {
        return from(coordinate).format()
    }
    
    /**
     * Format to a UTM string from a coordinate in degrees
     *
     * @param longitude
     *            longitude
     * @param latitude
     *            latitude
     * @return UTM string
     */
    public static func format(_ longitude: Double, _ latitude: Double) -> String {
        return from(longitude, latitude).format()
    }
    
    /**
     * Format to a UTM string from a coordinate in the unit
     *
     * @param longitude
     *            longitude
     * @param latitude
     *            latitude
     * @param unit
     *            unit
     * @return UTM string
     */
    public static func format(_ longitude: Double, _ latitude: Double, _ unit: GridUnit) -> String {
        return from(longitude, latitude, unit).format()
    }
    
    /**
     * Format to a UTM string from a coordinate in degrees and zone number
     *
     * @param longitude
     *            longitude
     * @param latitude
     *            latitude
     * @param zone
     *            zone number
     * @return UTM string
     */
    public static func format(_ longitude: Double, _ latitude: Double, _ zone: Int) -> String {
        return from(longitude, latitude, zone).format()
    }
    
    /**
     * Format to a UTM string from a coordinate in the unit and zone number
     *
     * @param longitude
     *            longitude
     * @param latitude
     *            latitude
     * @param unit
     *            unit
     * @param zone
     *            zone number
     * @return UTM string
     */
    public static func format(_ longitude: Double, _ latitude: Double, _ unit: GridUnit, _ zone: Int) -> String {
        return from(longitude, latitude, unit, zone).format()
    }
    
    /**
     * Format to a UTM string from a coordinate in degrees, zone number, and hemisphere
     *
     * @param longitude
     *            longitude
     * @param latitude
     *            latitude
     * @param zone
     *            zone number
     * @param hemisphere
     *            hemisphere
     * @return UTM string
     */
    public static func format(_ longitude: Double, _ latitude: Double, _ zone: Int, _ hemisphere: Hemisphere) -> String {
        return from(longitude, latitude, zone, hemisphere).format()
    }
    
    /**
     * Format to a UTM string from a coordinate in the unit, zone number, and hemisphere
     *
     * @param longitude
     *            longitude
     * @param latitude
     *            latitude
     * @param unit
     *            unit
     * @param zone
     *            zone number
     * @param hemisphere
     *            hemisphere
     * @return UTM string
     */
    public static func format(_ longitude: Double, _ latitude: Double, _ unit: GridUnit, _ zone: Int, _ hemisphere: Hemisphere) -> String {
        return from(longitude, latitude, unit, zone, hemisphere).format()
    }
    
}
