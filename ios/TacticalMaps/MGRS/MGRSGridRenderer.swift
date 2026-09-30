import Foundation
import MapKit
import MGRS
import Grid

/// Builds MGRS grid polylines + per-line labels for the visible map region.
/// Detail level (100km / 10km / 1km) depends on zoom: zoomed way out
/// you only get 100km lines, zoom in and finer grids show up.
///
/// Label placement follows the mil topo sheet convention - eastings on
/// vertical lines, northings on horizontal, centred on the visible
/// portion of each line.
enum MGRSGridRenderer {

    /// One polyline + its grid-type tag for MKMapView.
    struct LineSegment {
        let polyline: MKPolyline
        let gridType: GridType
    }

    /// Label for one axis, centred on a grid line. `isVertical` controls
    /// render orientation - vertical lines get easting label rotated,
    /// horizontal ones stay flat.
    struct LabelMark {
        let text: String
        let coordinate: CLLocationCoordinate2D
        let gridType: GridType
        let isVertical: Bool
    }

    /// Neutral dark-grey ink for lines and labels in tactical mode.
    static let inkColor = UIColor(red: 0.18, green: 0.18, blue: 0.18, alpha: 0.85)
    static let labelTextColor = UIColor(red: 0.16, green: 0.16, blue: 0.16, alpha: 1.0)

    /// One-time Grids setup. NGA's defaults disable the 10km labeler and
    /// gate 100km labeler to zoom >= 6. We don't use the library's
    /// per-square labels (we roll our own per-line ones), but we do need
    /// the Grids config for line generation.
    private static let configuredGrids = Grids()

    /// Build all visible grid lines + per-line labels for the given region.
    /// `mapWidthPoints` is on-screen width, used to figure out tile-zoom
    /// for the NGA library.
    static func build(for region: MKCoordinateRegion,
                      mapWidthPoints: CGFloat) -> (lines: [LineSegment], labels: [LabelMark]) {
        // Clamp to what the NGA grid library accepts. On cold-launch or
        // world-spanning region, GridZones can get a longitude at exactly
        // +/-180 (gives UTM zone 61) or a polar lat, and the library's
        // zone-number assertion blows up (hard crash in debug). UTM is
        // defined for lon [-180, 180) and lat [-80, 84].
        let west  = (region.center.longitude - region.span.longitudeDelta / 2).clamped(to: -180 ... 179.9999)
        let east  = (region.center.longitude + region.span.longitudeDelta / 2).clamped(to: -180 ... 179.9999)
        let south = (region.center.latitude  - region.span.latitudeDelta  / 2).clamped(to: -80 ... 84)
        let north = (region.center.latitude  + region.span.latitudeDelta  / 2).clamped(to: -80 ... 84)

        // Approximate tile zoom from horizontal span. MapKit doesn't give us
        // a tile-zoom number so we back into it from degrees-per-pixel.
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

                    // Direction in UTM metres. Only here can we tell
                    // easting vs northing axis without longitude bands
                    // messing things up.
                    let mLine = line.toMeters()
                    let dE = abs(mLine.point1.longitude - mLine.point2.longitude)
                    let dN = abs(mLine.point1.latitude  - mLine.point2.latitude)
                    let isVertical = dE < dN

                    // Midpoint for label anchor. Use degrees version so
                    // the label sits at geographic center of the segment.
                    let midLat = (p1.latitude  + p2.latitude)  / 2
                    let midLng = (p1.longitude + p2.longitude) / 2
                    let midCoord = CLLocationCoordinate2D(latitude: midLat, longitude: midLng)

                    // Label value comes from the endpoints in this zone's UTM,
                    // they sit on the line to ~1 cm. The lat/lon midpoint sags
                    // off it (1.5 m on a 10 km line, ~150 m on 100 km) and
                    // MGRS.from() truncates, so the 87000 line read "86".
                    let text = lineLabelText(
                        gridType: type,
                        start: UTM.from(p1, zone.number(), zone.hemisphere()),
                        end: UTM.from(p2, zone.number(), zone.hemisphere()),
                        isVertical: isVertical
                    )
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

    /// Format easting/northing for a single grid line. 1km lines get
    /// 2-digit numbers (e.g. "20"), 10km get a single digit, 100km
    /// get the column or row letter so you can read the full square
    /// ID off the intersection. The letter is the square east of a
    /// vertical line / north of a horizontal one, same side the numbers
    /// count from (line 87 is the bottom edge of square 87).
    private static func lineLabelText(gridType: GridType, start: UTM, end: UTM, isVertical: Bool) -> String {
        let interval: Int
        switch gridType {
        case .HUNDRED_KILOMETER: interval = 100_000
        case .TEN_KILOMETER: interval = 10_000
        case .KILOMETER: interval = 1_000
        default: return ""
        }
        let easting = (start.easting + end.easting) / 2
        let northing = (start.northing + end.northing) / 2
        let index = lineIndex(isVertical ? easting : northing, interval: interval)
        switch gridType {
        case .HUNDRED_KILOMETER:
            let inSquare = Double(index * interval + interval / 2)
            let square = (isVertical
                ? UTM(start.zone, start.hemisphere, inSquare, northing)
                : UTM(start.zone, start.hemisphere, easting, inSquare)).toMGRS()
            return isVertical ? String(square.column) : String(square.row)
        case .TEN_KILOMETER:
            return String(index % 10)
        default:
            return String(format: "%02d", index % 100)
        }
    }

    /// Which grid line this is, in units of the interval. The endpoints went
    /// UTM -> lat/lon -> UTM and land a hair either side of the line, so snap
    /// when we're within a metre. Anything that isn't on the interval at all
    /// (e.g. a clipped zone edge) keeps the old floor, i.e. the square it's in.
    private static func lineIndex(_ value: Double, interval: Int) -> Int {
        let step = Double(interval)
        let nearest = (value / step).rounded()
        if abs(value - nearest * step) < lineSnapMetres { return Int(nearest) }
        return Int((value / step).rounded(.down))
    }

    private static let lineSnapMetres = 1.0

    /// Stroke width per grid type. Coarser grids get thicker lines so
    /// 100km cells don't get lost in the 10km / 1km sub-grids.
    static func lineWidth(for type: GridType) -> CGFloat {
        switch type {
        case .HUNDRED_KILOMETER: return 2.0
        case .TEN_KILOMETER:    return 1.3
        case .KILOMETER:        return 0.8
        default:                return 0.6
        }
    }

    /// Applied grid width: one physical display pixel thicker than the base
    /// cartographic weight. On Retina screens a pixel is `1 / scale` points,
    /// so this avoids accidentally adding two or three pixels.
    static func appliedLineWidth(for type: GridType, screenScale: CGFloat) -> CGFloat {
        lineWidth(for: type) + 1 / max(screenScale, 1)
    }

    /// Label font size. 100km labels are readable at any zoom, finer
    /// grids get smaller text so they don't clutter when zoomed way in.
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
