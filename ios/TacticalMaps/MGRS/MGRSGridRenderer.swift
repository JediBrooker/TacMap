import Foundation
import MapKit
import MGRS
import Grid

/// Generates MGRS grid line polylines + per-line grid-square labels
/// covering a visible map region. Detail (100km / 10km / 1km) is
/// picked from the map's current zoom: zoomed all the way out, only
/// the 100km lines render; zoom in and the finer grids appear
/// progressively.
///
/// Label placement follows the convention used on military 1:50,000
/// topo sheets — eastings on vertical lines, northings on horizontal
/// lines, centred along the visible portion of each line.
enum MGRSGridRenderer {

    /// One polyline + its grid-type tag, ready for MKMapView to consume.
    struct LineSegment {
        let polyline: MKPolyline
        let gridType: GridType
    }

    /// One axis-specific label centred on a grid line. `isVertical`
    /// drives the rendering orientation (vertical lines get the easting
    /// label rotated to match the line, horizontal lines stay flat).
    struct LabelMark {
        let text: String
        let coordinate: CLLocationCoordinate2D
        let gridType: GridType
        let isVertical: Bool
    }

    /// Tactical-mode neutral dark-grey ink used for both lines and labels.
    static let inkColor = UIColor(red: 0.18, green: 0.18, blue: 0.18, alpha: 0.85)
    static let labelTextColor = UIColor(red: 0.16, green: 0.16, blue: 0.16, alpha: 1.0)

    /// One-time Grids configuration: NGA's defaults disable the 10km
    /// labeler and gate the 100km labeler to zoom ≥ 6. We never use
    /// the library's per-square labels (we emit our own per-line ones),
    /// but we DO use the Grids configuration for line generation.
    private static let configuredGrids = Grids()

    /// Generate every visible grid line + per-line label for the
    /// supplied map region. `mapWidthPoints` is the on-screen width
    /// used to convert the region into a tile-zoom level the NGA
    /// library understands.
    static func build(for region: MKCoordinateRegion,
                      mapWidthPoints: CGFloat) -> (lines: [LineSegment], labels: [LabelMark]) {
        // Clamp to the range the NGA grid library accepts. A cold-launch or
        // world-spanning region can otherwise hand GridZones a longitude at
        // exactly ±180 (→ UTM zone 61) or a polar latitude, tripping the
        // library's zone-number assertion (a hard crash in debug). UTM is
        // defined for lon [-180, 180) and lat [-80, 84].
        let west  = (region.center.longitude - region.span.longitudeDelta / 2).clamped(to: -180 ... 179.9999)
        let east  = (region.center.longitude + region.span.longitudeDelta / 2).clamped(to: -180 ... 179.9999)
        let south = (region.center.latitude  - region.span.latitudeDelta  / 2).clamped(to: -80 ... 84)
        let north = (region.center.latitude  + region.span.latitudeDelta  / 2).clamped(to: -80 ... 84)

        // Approximate tile zoom from horizontal span. MapKit doesn't expose
        // a tile-zoom number, so back into it from degrees-per-pixel.
        let degreesPerPoint = region.span.longitudeDelta / Double(max(mapWidthPoints, 1))
        let zoom = Int((log2(360.0 / (256.0 * degreesPerPoint))).rounded())
            .clamped(to: 0...20)

        let bounds = Bounds.degrees(west, south, east, north)
        let zones = GridZones.zones(bounds)

        // Always show 100km. Add 10km from zoom 8, 1km from zoom 12.
        var types: [GridType] = [.HUNDRED_KILOMETER]
        if zoom >= 8 { types.append(.TEN_KILOMETER) }
        if zoom >= 12 { types.append(.KILOMETER) }

        var lineOut: [LineSegment] = []
        var labelOut: [LabelMark] = []
        lineOut.reserveCapacity(256)
        labelOut.reserveCapacity(128)
        for type in types {
            let grid = configuredGrids.grid(type)
            for zone in zones {
                guard let lines = grid.lines(bounds, zone) else { continue }
                for line in lines {
                    // Endpoints in degrees → MKPolyline.
                    let degLine = line.toDegrees()
                    let p1 = degLine.point1
                    let p2 = degLine.point2
                    var coords = [
                        CLLocationCoordinate2D(latitude: p1.latitude, longitude: p1.longitude),
                        CLLocationCoordinate2D(latitude: p2.latitude, longitude: p2.longitude)
                    ]
                    let polyline = MKPolyline(coordinates: &coords, count: 2)
                    lineOut.append(LineSegment(polyline: polyline, gridType: type))

                    // Direction in UTM metres — only here can we tell
                    // easting-axis vs northing-axis without longitude
                    // bands distorting things.
                    let mLine = line.toMeters()
                    let dE = abs(mLine.point1.longitude - mLine.point2.longitude)
                    let dN = abs(mLine.point1.latitude  - mLine.point2.latitude)
                    let isVertical = dE < dN

                    // Midpoint as the label anchor. Use the degrees
                    // version so the label coordinate is the geographic
                    // centre of the segment.
                    let midLat = (p1.latitude  + p2.latitude)  / 2
                    let midLng = (p1.longitude + p2.longitude) / 2
                    let midCoord = CLLocationCoordinate2D(latitude: midLat, longitude: midLng)
                    let mgrs = MGRS.from(midCoord)

                    let text = lineLabelText(gridType: type, mgrs: mgrs, isVertical: isVertical)
                    if !text.isEmpty {
                        labelOut.append(LabelMark(
                            text: text,
                            coordinate: midCoord,
                            gridType: type,
                            isVertical: isVertical
                        ))
                    }
                }
            }
        }
        return (lineOut, labelOut)
    }

    /// Format the easting/northing value for a single grid line. 1km
    /// lines get 2-digit numbers (e.g. "20"), 10km lines get a single
    /// digit, 100km lines get the column or row letter so the user can
    /// read the full square ID off the intersection.
    private static func lineLabelText(gridType: GridType, mgrs: MGRS, isVertical: Bool) -> String {
        switch gridType {
        case .HUNDRED_KILOMETER:
            return isVertical ? String(mgrs.column) : String(mgrs.row)
        case .TEN_KILOMETER:
            let value = isVertical ? mgrs.easting : mgrs.northing
            return String((value / 10_000) % 10)
        case .KILOMETER:
            let value = isVertical ? mgrs.easting : mgrs.northing
            return String(format: "%02d", (value / 1_000) % 100)
        default:
            return ""
        }
    }

    /// Stroke width per grid type. Coarser grids draw heavier so the
    /// 100km cells stand out against the 10km / 1km sub-grids.
    static func lineWidth(for type: GridType) -> CGFloat {
        switch type {
        case .HUNDRED_KILOMETER: return 2.0
        case .TEN_KILOMETER:    return 1.3
        case .KILOMETER:        return 0.8
        default:                return 0.6
        }
    }

    /// Label text size in points. 100km labels read at any zoom; finer
    /// grids get smaller text so they don't clutter the screen when the
    /// user is zoomed all the way in.
    static func labelFontSize(for type: GridType) -> CGFloat {
        switch type {
        case .HUNDRED_KILOMETER: return 14
        case .TEN_KILOMETER:    return 12
        case .KILOMETER:        return 11
        default:                return 9
        }
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
