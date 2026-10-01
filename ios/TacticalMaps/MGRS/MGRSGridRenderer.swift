import Foundation
import CoreLocation
import CoreGraphics
import UIKit
import MGRS
import Grid

/// MGRS grid overlay geometry. The whole policy (levels, dp based LOD, label
/// text, densify, zone/band clipping, label placement, cache) is pinned by
/// testdata/mgrs_grid.json and spelled out in testdata/README.md, Android runs
/// the same rules in MgrsGridRenderer.kt.
///
/// We don't use NGA's GridZone.lines any more. It hands back one straight chord
/// per cell (100 km lines end up 200 m off), rounds its bounds out past the
/// zone edge without clipping (first column diagonals into the next zone) and
/// emits band edge rows twice. So lines get built here per GZD cell in that
/// zone's UTM with an accurate TM, densified, then split at the cell edges.
///
/// build() is pure and thread safe, the container runs it off the main queue.
/// layoutLabels() is cheap and runs every frame against the live camera.
enum MGRSGridRenderer {

    // MARK: - policy

    /// Grid levels, coarse to fine. No 100 m level, no GZD line level.
    enum Level: Int, CaseIterable, Comparable {
        case km100 = 0, km10, km1

        var metres: Int {
            switch self {
            case .km100: return 100_000
            case .km10: return 10_000
            case .km1: return 1_000
            }
        }

        /// Fixture spelling ("100km" etc).
        var name: String {
            switch self {
            case .km100: return "100km"
            case .km10: return "10km"
            case .km1: return "1km"
            }
        }

        /// coarse < fine
        static func < (a: Level, b: Level) -> Bool { a.rawValue < b.rawValue }
    }

    enum Hemisphere: Hashable { case north, south }
    enum Axis: Hashable { case easting, northing }

    /// One grid line: value is the easting or northing it sits on, in the
    /// zone's own UTM (southern northings include the 10,000 km false northing).
    struct LineKey: Hashable {
        let zone: Int
        let hemisphere: Hemisphere
        let axis: Axis
        let value: Int
    }

    static let tileSizeDp = 256.0
    static let lineMinDp = 32.0
    static let labelMinDp = 40.0
    static let labelInsetDp = 16.0
    static let squareLabelMinDp = 40.0
    static let squareLabelOffsetDp = 32.0
    static let maxSagittaPx = 0.25
    static let gridLatMin = -80.0
    static let gridLatMax = 84.0
    static let labelBoxPadDp = 2.0
    static let rebuildZoomDelta = 0.5
    static let rebuildCentreMoveDp = 64.0
    static let coverageMarginDp = 32.0
    /// Lines whose visible bit inside the label region is shorter than this
    /// don't get a label, it'd just be floating next to nothing.
    static let minLabelledLengthDp = 1.0
    /// chord tolerance for 100 km square outlines (labels only, never stroked)
    static let squareOutlineTolDp = 0.5

    /// We check the sagitta at the chord's parameter midpoint, which is a hair
    /// under the true max on a curve that isn't a perfect arc. Aim a bit lower
    /// so the real max still stays inside 0.25 px.
    private static let sagittaSafety = 0.8

    private static let bands = Array("CDEFGHJKLMNPQRSTUVWX")

    /// Neutral dark-grey ink for lines and labels (unchanged look).
    static let inkColor = UIColor(red: 0.18, green: 0.18, blue: 0.18, alpha: 0.85)
    static let labelTextColor = UIColor(red: 0.16, green: 0.16, blue: 0.16, alpha: 1.0)

    // MARK: - web mercator, normalised to 0...1 (x east, y south)

    static func mercX(_ lon: Double) -> Double { (lon + 180) / 360 }

    static func mercY(_ lat: Double) -> Double {
        let l = min(max(lat, -WebMercator.latLimit), WebMercator.latLimit)
        let s = sin(l * .pi / 180)
        return 0.5 - log((1 + s) / (1 - s)) / (4 * .pi)
    }

    static func latitude(mercY y: Double) -> Double {
        atan(sinh(Double.pi - 2 * Double.pi * y)) * 180 / .pi
    }

    // MARK: - level of detail (dp, same thresholds as Android)

    struct LOD: Equatable {
        /// spacing of each level on screen in dp/pt, indexed by Level.rawValue
        let spacingDp: [Double]
        let drawn: [Level]
        let labelled: [Level]

        static let none = LOD(spacingDp: [0, 0, 0], drawn: [], labelled: [])

        func spacing(_ level: Level) -> Double { spacingDp[level.rawValue] }

        /// L in the contract: finest labelled level among 10 km and 1 km.
        /// nil when only 100 km (or nothing) is labelled, then no line labels.
        var lineLabelLevel: Level? {
            if labelled.contains(.km1) { return .km1 }
            if labelled.contains(.km10) { return .km10 }
            return nil
        }

        var labelsSquares: Bool { labelled.contains(.km100) }

        func sameSets(as other: LOD) -> Bool { drawn == other.drawn && labelled == other.labelled }
    }

    /// spacingDp = metres * 256 * 2^zoom / (2 pi R cos(lat)). Continuous zoom,
    /// camera-centre latitude. 256 pt tiles on iOS so dp == pt here.
    static func lod(zoom: Double, latitude: Double) -> LOD {
        let mpp = WebMercator.groundResolution(latitude: latitude, zoom: zoom)
        let spacing = Level.allCases.map { Double($0.metres) / mpp }
        return LOD(spacingDp: spacing,
                   drawn: Level.allCases.filter { spacing[$0.rawValue] >= lineMinDp },
                   labelled: Level.allCases.filter { spacing[$0.rawValue] >= labelMinDp })
    }

    /// Coarsest drawn level whose spacing divides the value. So N=4200000 is a
    /// 100 km line even while 1 km is drawn, and it's only emitted once.
    static func ownerLevel(value: Int, drawn: [Level]) -> Level? {
        Level.allCases.first { drawn.contains($0) && value % $0.metres == 0 }
    }

    // MARK: - label text

    /// Line label = the line's own value, %02d of (value mod 100 km) / 1 km.
    /// Same both hemispheres. nil when this line isn't labelled at L.
    static func lineLabelText(value: Int, lineLabelLevel: Level?) -> String? {
        guard let level = lineLabelLevel, level != .km100, value % level.metres == 0 else { return nil }
        let km = (((value % 100_000) + 100_000) % 100_000) / 1_000
        return String(format: "%02d", km)
    }

    /// 100 km square letters (AA scheme). Column set by zone mod 3, row is
    /// floor(N / 100 km) mod 20, shifted 5 for even zones.
    static func squareLetters(zone: Int, easting: Int, northing: Int) -> String? {
        let columnSets = [Array("STUVWXYZ"), Array("ABCDEFGH"), Array("JKLMNPQR")]
        let rows = Array("ABCDEFGHJKLMNPQRSTUV")
        let col = easting / 100_000 - 1
        guard zone >= 1, zone <= 60, col >= 0, col < 8, northing >= 0 else { return nil }
        var row = (northing / 100_000) % 20
        if zone % 2 == 0 { row = (row + 5) % 20 }
        return String([columnSets[zone % 3][col], rows[row]])
    }

    // MARK: - GZD cells

    struct GeoBox: Equatable {
        var south: Double
        var west: Double
        var north: Double
        var east: Double
    }

    /// One grid zone designation cell, eg 32T. Spans carry the Norway and
    /// Svalbard exceptions.
    struct Cell: Hashable {
        let zone: Int
        let band: Character
        let lonW: Double
        let lonE: Double
        let latS: Double
        let latN: Double

        var hemisphere: Hemisphere { band >= "N" ? .north : .south }
        var isSouth: Bool { hemisphere == .south }
        var gzd: String { "\(zone)\(band)" }
    }

    static func bandLatitudes(_ band: Character) -> (south: Double, north: Double)? {
        guard let i = bands.firstIndex(of: band) else { return nil }
        let s = gridLatMin + 8 * Double(i)
        return (s, band == "X" ? gridLatMax : s + 8)
    }

    /// nil for the cells that don't exist (32X, 34X, 36X).
    static func zoneLongitudes(zone: Int, band: Character) -> (west: Double, east: Double)? {
        switch (zone, band) {
        case (31, "V"): return (0, 3)
        case (32, "V"): return (3, 12)
        case (31, "X"): return (0, 9)
        case (32, "X"), (34, "X"), (36, "X"): return nil
        case (33, "X"): return (9, 21)
        case (35, "X"): return (21, 33)
        case (37, "X"): return (33, 42)
        default: return (Double(6 * zone - 186), Double(6 * zone - 180))
        }
    }

    static func cell(zone: Int, band: Character) -> Cell? {
        guard (1 ... 60).contains(zone), let lat = bandLatitudes(band),
              let lon = zoneLongitudes(zone: zone, band: band) else { return nil }
        return Cell(zone: zone, band: band, lonW: lon.west, lonE: lon.east, latS: lat.south, latN: lat.north)
    }

    /// Cells whose span overlaps the box (strictly, touching doesn't count).
    static func cells(in box: GeoBox) -> [Cell] {
        var out: [Cell] = []
        for band in bands {
            guard let lat = bandLatitudes(band), lat.south < box.north, lat.north > box.south else { continue }
            for zone in 1 ... 60 {
                guard let lon = zoneLongitudes(zone: zone, band: band),
                      lon.west < box.east, lon.east > box.west else { continue }
                out.append(Cell(zone: zone, band: band, lonW: lon.west, lonE: lon.east,
                                latS: lat.south, latN: lat.north))
            }
        }
        return out
    }

    // MARK: - built geometry

    /// A vertex: lat/lon for tests + callers, x/y normalised web mercator so the
    /// per-frame projection is just a scale, shift and rotate.
    struct GridPoint {
        let lat: Double
        let lon: Double
        let x: Double
        let y: Double

        init(lat: Double, lon: Double) {
            self.lat = lat
            self.lon = lon
            x = MGRSGridRenderer.mercX(lon)
            y = MGRSGridRenderer.mercY(lat)
        }

        fileprivate init(lat: Double, lon: Double, x: Double, y: Double) {
            self.lat = lat
            self.lon = lon
            self.x = x
            self.y = y
        }
    }

    /// Bounding box in normalised mercator, for cheap culling.
    struct MercBounds {
        let minX: Double
        let minY: Double
        let maxX: Double
        let maxY: Double

        init(_ pts: [GridPoint]) {
            var x0 = Double.infinity, y0 = Double.infinity, x1 = -Double.infinity, y1 = -Double.infinity
            for p in pts {
                x0 = min(x0, p.x); x1 = max(x1, p.x)
                y0 = min(y0, p.y); y1 = max(y1, p.y)
            }
            minX = x0; minY = y0; maxX = x1; maxY = y1
        }
    }

    /// One drawn piece of a line inside one GZD cell. level is the owner
    /// level, which sets stroke width and label font.
    struct Piece {
        let key: LineKey
        let cell: Cell
        let level: Level
        let points: [GridPoint]
        let bounds: MercBounds
    }

    /// A 100 km square, whole (not clipped to its cell yet, labels do that
    /// per frame against the viewport).
    struct Square {
        let cell: Cell
        let easting: Int
        let northing: Int
        let text: String
        let ring: [GridPoint]
        let bounds: MercBounds
    }

    struct Grid {
        let lod: LOD
        let pieces: [Piece]
        let squares: [Square]

        static let empty = Grid(lod: .none, pieces: [], squares: [])

        var isEmpty: Bool { pieces.isEmpty && squares.isEmpty }
        var vertexCount: Int { pieces.reduce(0) { $0 + $1.points.count } }
    }

    // MARK: - cache / rebuild policy

    /// What a grid got built for. The container keeps the last one and only
    /// rebuilds when needsRebuild says so.
    struct BuildRequest {
        let centre: CLLocationCoordinate2D
        let zoom: Double
        let viewportSize: CGSize
        let pxPerDp: Double
        let lod: LOD

        init(camera: MapCamera, pxPerDp: Double) {
            centre = camera.center
            zoom = camera.zoom
            viewportSize = camera.viewportSize
            self.pxPerDp = max(pxPerDp, 1)
            lod = MGRSGridRenderer.lod(zoom: camera.zoom, latitude: camera.center.latitude)
        }

        /// densify for the most zoomed-in camera this build still serves
        var densifyZoom: Double { zoom + MGRSGridRenderer.rebuildZoomDelta }

        var coverage: GeoBox {
            MGRSGridRenderer.coverageBox(centre: centre, zoom: zoom, viewportSize: viewportSize)
        }
    }

    /// Half-side of the north-up coverage square in dp at the build zoom. Any
    /// heading, a zoom-out of up to 0.5 and a centre move of up to 64 dp all
    /// stay inside it, plus 32 dp of slack for the build in flight.
    static func coverageHalfSideDp(viewportSize: CGSize) -> Double {
        let half = hypot(Double(viewportSize.width), Double(viewportSize.height)) / 2
        return (half + rebuildCentreMoveDp) * pow(2, rebuildZoomDelta) + coverageMarginDp
    }

    static func coverageBox(centre: CLLocationCoordinate2D, zoom: Double, viewportSize: CGSize) -> GeoBox {
        let size = tileSizeDp * pow(2, zoom)
        let half = coverageHalfSideDp(viewportSize: viewportSize) / size
        let cx = mercX(centre.longitude), cy = mercY(centre.latitude)
        let x0 = max(cx - half, 0), x1 = min(cx + half, 1)
        let y0 = max(cy - half, 0), y1 = min(cy + half, 1)
        return GeoBox(south: latitude(mercY: y1), west: x0 * 360 - 180,
                      north: latitude(mercY: y0), east: x1 * 360 - 180)
    }

    /// Rebuild when the drawn/labelled sets change, zoom drifts 0.5 from the
    /// build, the centre moves 64 dp (at the current zoom) or the viewport or
    /// screen scale changes. Anything smaller just reprojects cached geometry.
    static func needsRebuild(_ built: BuildRequest?, camera: MapCamera, pxPerDp: Double) -> Bool {
        guard let built else { return true }
        if camera.viewportSize != built.viewportSize || max(pxPerDp, 1) != built.pxPerDp { return true }
        if abs(camera.zoom - built.zoom) >= rebuildZoomDelta { return true }
        if !lod(zoom: camera.zoom, latitude: camera.center.latitude).sameSets(as: built.lod) { return true }
        let size = tileSizeDp * pow(2, camera.zoom)
        let dx = (mercX(camera.center.longitude) - mercX(built.centre.longitude)) * size
        let dy = (mercY(camera.center.latitude) - mercY(built.centre.latitude)) * size
        return hypot(dx, dy) >= rebuildCentreMoveDp
    }

    // MARK: - build

    static func build(_ request: BuildRequest) -> Grid {
        build(box: request.coverage, lod: request.lod,
              densifyZoom: request.densifyZoom, pxPerDp: request.pxPerDp)
    }

    /// Every drawn line (and, when 100 km is labelled, every 100 km square)
    /// for the box. Lines are densified so the chord sagitta is <= 0.25 px at
    /// densifyZoom and pxPerDp, then clipped to each cell (half-open on the
    /// north and east edges) and to the box.
    static func build(box: GeoBox, lod: LOD, densifyZoom: Double, pxPerDp: Double) -> Grid {
        guard !lod.drawn.isEmpty else { return Grid(lod: lod, pieces: [], squares: []) }
        let clamped = GeoBox(south: max(box.south, gridLatMin), west: max(box.west, -180),
                             north: min(box.north, gridLatMax), east: min(box.east, 180))
        guard clamped.south < clamped.north, clamped.west < clamped.east else {
            return Grid(lod: lod, pieces: [], squares: [])
        }
        let tol = maxSagittaPx * sagittaSafety / (max(pxPerDp, 1) * tileSizeDp * pow(2, densifyZoom))
        // a square whose diagonal is under 40 dp even at the most zoomed-in camera
        // this build serves can't pass the square label size check at any heading
        // (0.9 because the corner box ignores the edge bulge)
        let minSquareDiagonal = 0.9 * squareLabelMinDp / (tileSizeDp * pow(2, densifyZoom))
        // square outlines only feed label placement (2 dp slop), half a dp is plenty
        let squareTol = squareOutlineTolDp / (tileSizeDp * pow(2, densifyZoom))

        // group by zone + hemisphere so a line gets densified once and split at
        // the band edges, then both neighbours share the exact same edge point
        var order: [GroupKey] = []
        var groups: [GroupKey: [Region]] = [:]
        for cell in cells(in: clamped) {
            guard let r = Region(cell: cell, box: clamped) else { continue }
            let key = GroupKey(zone: cell.zone, south: cell.isSouth)
            if groups[key] == nil { order.append(key) }
            groups[key, default: []].append(r)
        }

        var pieces: [Piece] = []
        var squares: [Square] = []
        for key in order {
            guard let regions = groups[key] else { continue }
            buildLines(zone: key.zone, south: key.south, regions: regions, lod: lod, tol: tol, into: &pieces)
            if lod.labelsSquares {
                buildSquares(zone: key.zone, south: key.south, regions: regions, tol: squareTol,
                             minDiagonal: minSquareDiagonal, into: &squares)
            }
        }
        return Grid(lod: lod, pieces: pieces, squares: squares)
    }

    private struct GroupKey: Hashable {
        let zone: Int
        let south: Bool
    }

    /// cell intersected with the build box. Box edges are closed, the cell's
    /// own north/east edges are half open (whatever sits exactly on them
    /// belongs to the neighbour).
    private struct Region {
        let cell: Cell
        let lonW: Double
        let lonE: Double
        let latS: Double
        let latN: Double
        let yTop: Double
        let yBottom: Double
        let northOpen: Bool
        let eastOpen: Bool

        init?(cell: Cell, box: GeoBox) {
            let s = max(cell.latS, box.south), n = min(cell.latN, box.north)
            let w = max(cell.lonW, box.west), e = min(cell.lonE, box.east)
            guard s < n, w < e else { return nil }
            self.cell = cell
            lonW = w; lonE = e; latS = s; latN = n
            yTop = MGRSGridRenderer.mercY(n)
            yBottom = MGRSGridRenderer.mercY(s)
            northOpen = cell.latN <= box.north
            eastOpen = cell.lonE <= box.east
        }
    }

    private struct UTMExtent {
        var eMin = Double.infinity
        var eMax = -Double.infinity
        var nMin = Double.infinity
        var nMax = -Double.infinity

        mutating func add(_ e: Double, _ n: Double) {
            eMin = min(eMin, e); eMax = max(eMax, e)
            nMin = min(nMin, n); nMax = max(nMax, n)
        }

        mutating func add(_ other: UTMExtent) {
            eMin = min(eMin, other.eMin); eMax = max(eMax, other.eMax)
            nMin = min(nMin, other.nMin); nMax = max(nMax, other.nMax)
        }
    }

    /// UTM extent of a region by walking its edges. Parallels bottom out at
    /// the CM so that's sampled explicitly too.
    private static func utmExtent(_ r: Region, zone: Int, south: Bool) -> UTMExtent {
        var ext = UTMExtent()
        let k = 16
        var lons = (0 ... k).map { r.lonW + (r.lonE - r.lonW) * Double($0) / Double(k) }
        let cm = KrugerUTM.centralMeridian(zone: zone)
        if cm > r.lonW, cm < r.lonE { lons.append(cm) }
        for lon in lons {
            for lat in [r.latS, r.latN] {
                let p = KrugerUTM.forward(latitude: lat, longitude: lon, zone: zone, south: south)
                ext.add(p.easting, p.northing)
            }
        }
        for i in 1 ..< k {
            let lat = r.latS + (r.latN - r.latS) * Double(i) / Double(k)
            for lon in [r.lonW, r.lonE] {
                let p = KrugerUTM.forward(latitude: lat, longitude: lon, zone: zone, south: south)
                ext.add(p.easting, p.northing)
            }
        }
        return ext
    }

    /// slack on UTM ranges so edge sampling never drops a line; anything extra
    /// just clips to nothing
    private static func pad(_ lo: Double, _ hi: Double) -> Double { 50 + 0.002 * max(hi - lo, 0) }

    private static func buildLines(zone: Int, south: Bool, regions: [Region], lod: LOD, tol: Double,
                                   into pieces: inout [Piece]) {
        guard let finest = lod.drawn.max() else { return }
        var ext = UTMExtent()
        for r in regions { ext.add(utmExtent(r, zone: zone, south: south)) }
        guard ext.eMin.isFinite, ext.nMin.isFinite else { return }
        let step = finest.metres
        let hemisphere: Hemisphere = south ? .south : .north

        for axis in [Axis.easting, .northing] {
            let (lo, hi) = axis == .easting ? (ext.eMin, ext.eMax) : (ext.nMin, ext.nMax)
            let (tLo, tHi) = axis == .easting ? (ext.nMin, ext.nMax) : (ext.eMin, ext.eMax)
            let padV = pad(lo, hi), padT = pad(tLo, tHi)
            var v = Int(ceil((lo - padV) / Double(step))) * step
            let vMax = hi + padV
            while Double(v) <= vMax {
                defer { v += step }
                switch axis {
                case .easting:
                    if v <= 0 || v >= 1_000_000 { continue }
                case .northing:
                    // equator is the northern N=0 line only, never the southern 10,000 km one
                    if south ? v >= 10_000_000 : v < 0 { continue }
                }
                guard let owner = ownerLevel(value: v, drawn: lod.drawn) else { continue }
                let value = Double(v)
                let line = densify(from: tLo - padT, to: tHi + padT, initialSegments: 4, tol: tol) { t in
                    linePoint(zone: zone, south: south, axis: axis, value: value, t: t)
                }
                let key = LineKey(zone: zone, hemisphere: hemisphere, axis: axis, value: v)
                let lineBounds = MercBounds(line)
                for r in regions {
                    // cheap reject before clipping
                    guard lineBounds.maxX >= mercX(r.lonW), lineBounds.minX <= mercX(r.lonE),
                          lineBounds.maxY >= r.yTop, lineBounds.minY <= r.yBottom else { continue }
                    for run in clip(line, to: r) {
                        pieces.append(Piece(key: key, cell: r.cell, level: owner,
                                            points: run, bounds: MercBounds(run)))
                    }
                }
            }
        }
    }

    private static func buildSquares(zone: Int, south: Bool, regions: [Region], tol: Double,
                                     minDiagonal: Double, into squares: inout [Square]) {
        let side = 100_000.0
        for r in regions {
            let ext = utmExtent(r, zone: zone, south: south)
            guard ext.eMin.isFinite, ext.nMin.isFinite else { continue }
            let pe = pad(ext.eMin, ext.eMax), pn = pad(ext.nMin, ext.nMax)
            var e0 = floor((ext.eMin - pe) / side) * side
            while e0 <= ext.eMax + pe {
                defer { e0 += side }
                guard e0 >= 100_000, e0 < 900_000 else { continue }
                var n0 = floor((ext.nMin - pn) / side) * side
                while n0 <= ext.nMax + pn {
                    defer { n0 += side }
                    guard south ? n0 < 10_000_000 : n0 >= 0,
                          let text = squareLetters(zone: zone, easting: Int(e0), northing: Int(n0)) else { continue }
                    // skip the ones that can never get a label before the next rebuild,
                    // zoomed out at high latitude that's most of the coverage square
                    let corners = [(e0, n0), (e0 + side, n0), (e0 + side, n0 + side), (e0, n0 + side)].map {
                        linePoint(zone: zone, south: south, axis: .easting, value: $0.0, t: $0.1)
                    }
                    let box = MercBounds(corners)
                    guard hypot(box.maxX - box.minX, box.maxY - box.minY) >= minDiagonal else { continue }
                    let ring = squareRing(zone: zone, south: south, e0: e0, n0: n0, side: side, tol: tol)
                    squares.append(Square(cell: r.cell, easting: Int(e0), northing: Int(n0), text: text,
                                          ring: ring, bounds: MercBounds(ring)))
                }
            }
        }
    }

    /// Square outline, each side densified like a grid line.
    private static func squareRing(zone: Int, south: Bool, e0: Double, n0: Double, side: Double,
                                   tol: Double) -> [GridPoint] {
        let sides: [(Axis, Double, Double, Double)] = [
            (.northing, n0, e0, e0 + side),
            (.easting, e0 + side, n0, n0 + side),
            (.northing, n0 + side, e0 + side, e0),
            (.easting, e0, n0 + side, n0),
        ]
        var ring: [GridPoint] = []
        for (axis, value, t0, t1) in sides {
            let edge = densify(from: t0, to: t1, initialSegments: 1, tol: tol) { t in
                linePoint(zone: zone, south: south, axis: axis, value: value, t: t)
            }
            ring.append(contentsOf: edge.dropLast())   // next side starts at this corner
        }
        return ring
    }

    /// Point on a grid line. t is the northing for easting lines and the
    /// easting for northing lines.
    private static func linePoint(zone: Int, south: Bool, axis: Axis, value: Double, t: Double) -> GridPoint {
        let ll = axis == .easting
            ? KrugerUTM.inverse(easting: value, northing: t, zone: zone, south: south)
            : KrugerUTM.inverse(easting: t, northing: value, zone: zone, south: south)
        return GridPoint(lat: ll.latitude, lon: ll.longitude)
    }

    /// Adaptive split until each chord's sagitta (checked at the parameter
    /// midpoint, in normalised mercator) is under tol.
    private static func densify(from t0: Double, to t1: Double, initialSegments: Int, tol: Double,
                                _ f: (Double) -> GridPoint) -> [GridPoint] {
        var out: [GridPoint] = []
        out.reserveCapacity(initialSegments * 4 + 1)
        var prevT = t0
        var prev = f(t0)
        out.append(prev)
        for i in 1 ... initialSegments {
            let t = i == initialSegments ? t1 : t0 + (t1 - t0) * Double(i) / Double(initialSegments)
            let p = f(t)
            subdivide(prevT, prev, t, p, tol: tol, depth: 0, f, &out)
            prevT = t
            prev = p
        }
        return out
    }

    private static func subdivide(_ ta: Double, _ a: GridPoint, _ tb: Double, _ b: GridPoint,
                                  tol: Double, depth: Int, _ f: (Double) -> GridPoint,
                                  _ out: inout [GridPoint]) {
        let tm = (ta + tb) / 2
        let m = f(tm)
        if depth >= 24 || chordDistance(m, a, b) <= tol {
            out.append(b)
            return
        }
        subdivide(ta, a, tm, m, tol: tol, depth: depth + 1, f, &out)
        subdivide(tm, m, tb, b, tol: tol, depth: depth + 1, f, &out)
    }

    private static func chordDistance(_ p: GridPoint, _ a: GridPoint, _ b: GridPoint) -> Double {
        let dx = b.x - a.x, dy = b.y - a.y
        let len = hypot(dx, dy)
        guard len > 0 else { return hypot(p.x - a.x, p.y - a.y) }
        return abs((p.x - a.x) * dy - (p.y - a.y) * dx) / len
    }

    // MARK: - clipping (lon / mercator-y space, so clipped points sit on the drawn chord)

    private enum Edge { case none, west, east, north, south }

    /// Clip a densified line to one region. Returns the runs inside it.
    private static func clip(_ line: [GridPoint], to r: Region) -> [[GridPoint]] {
        var runs: [[GridPoint]] = []
        var cur: [GridPoint] = []
        func flush() {
            if cur.count >= 2 { runs.append(cur) }
            cur.removeAll(keepingCapacity: true)
        }
        guard line.count >= 2 else { return [] }
        for i in 0 ..< line.count - 1 {
            let a = line[i], b = line[i + 1]
            guard let hit = liangBarsky(a, b, r), hit.t1 - hit.t0 > 1e-12 else { flush(); continue }
            let p0 = hit.t0 == 0 ? a : interpolate(a, b, hit.t0, hit.e0, r)
            let p1 = hit.t1 == 1 ? b : interpolate(a, b, hit.t1, hit.e1, r)
            // lying along a half-open edge = the neighbour's line, not ours
            let midLat = (p0.lat + p1.lat) / 2, midLon = (p0.lon + p1.lon) / 2
            if (r.northOpen && midLat >= r.latN - 1e-12) || (r.eastOpen && midLon >= r.lonE - 1e-12) {
                flush()
                continue
            }
            if cur.isEmpty {
                cur = [p0, p1]
            } else if hit.t0 == 0 {
                cur.append(p1)
            } else {
                flush()
                cur = [p0, p1]
            }
            if hit.t1 < 1 { flush() }
        }
        flush()
        return runs
    }

    private static func liangBarsky(_ a: GridPoint, _ b: GridPoint, _ r: Region)
        -> (t0: Double, e0: Edge, t1: Double, e1: Edge)? {
        let du = b.lon - a.lon, dv = b.y - a.y
        let ps = [-du, du, -dv, dv]
        let qs = [a.lon - r.lonW, r.lonE - a.lon, a.y - r.yTop, r.yBottom - a.y]
        let edges: [Edge] = [.west, .east, .north, .south]
        var t0 = 0.0, t1 = 1.0
        var e0 = Edge.none, e1 = Edge.none
        for i in 0 ..< 4 {
            let p = ps[i], q = qs[i]
            if p == 0 {
                if q < 0 { return nil }
            } else {
                let t = q / p
                if p < 0 {
                    if t > t1 { return nil }
                    if t > t0 { t0 = t; e0 = edges[i] }
                } else {
                    if t < t0 { return nil }
                    if t < t1 { t1 = t; e1 = edges[i] }
                }
            }
        }
        return (t0, e0, t1, e1)
    }

    /// The clipped point goes exactly onto the edge value so "inside its own
    /// cell" holds to the last bit.
    private static func interpolate(_ a: GridPoint, _ b: GridPoint, _ t: Double, _ edge: Edge,
                                    _ r: Region) -> GridPoint {
        switch edge {
        case .west, .east:
            let lon = edge == .west ? r.lonW : r.lonE
            let y = a.y + t * (b.y - a.y)
            let lat = min(max(latitude(mercY: y), r.latS), r.latN)
            return GridPoint(lat: lat, lon: lon, x: mercX(lon), y: y)
        case .north, .south:
            let lat = edge == .north ? r.latN : r.latS
            let lon = min(max(a.lon + t * (b.lon - a.lon), r.lonW), r.lonE)
            return GridPoint(lat: lat, lon: lon, x: mercX(lon), y: edge == .north ? r.yTop : r.yBottom)
        case .none:
            return GridPoint(lat: a.lat + t * (b.lat - a.lat), lon: a.lon + t * (b.lon - a.lon))
        }
    }

    // MARK: - styling

    /// Stroke width per owner level. Coarser grids get thicker lines so 100 km
    /// cells don't get lost in the 10 km / 1 km sub-grids.
    static func lineWidth(for level: Level) -> CGFloat {
        switch level {
        case .km100: return 2.0
        case .km10: return 1.3
        case .km1: return 0.8
        }
    }

    /// Applied grid width: one physical display pixel thicker than the base
    /// cartographic weight. On Retina screens a pixel is 1 / scale points,
    /// so this avoids accidentally adding two or three pixels.
    static func appliedLineWidth(for level: Level, screenScale: CGFloat) -> CGFloat {
        lineWidth(for: level) + 1 / max(screenScale, 1)
    }

    /// Label font size per owner level (pt).
    static func labelFontSize(for level: Level) -> CGFloat {
        switch level {
        case .km100: return 14
        case .km10: return 12
        case .km1: return 11
        }
    }

    static func labelFont(for level: Level) -> UIFont {
        UIFont.systemFont(ofSize: labelFontSize(for: level), weight: .bold)
    }

    /// Unrotated text size, what the declutter boxes are made of.
    static func labelTextSize(_ text: String, level: Level) -> CGSize {
        (text as NSString).size(withAttributes: [.font: labelFont(for: level)])
    }

    // old NGA GridType spellings, kept so existing callers/tests don't churn

    private static func level(for type: GridType) -> Level? {
        switch type {
        case .HUNDRED_KILOMETER: return .km100
        case .TEN_KILOMETER: return .km10
        case .KILOMETER: return .km1
        default: return nil
        }
    }

    static func lineWidth(for type: GridType) -> CGFloat {
        level(for: type).map { lineWidth(for: $0) } ?? 0.6
    }

    static func appliedLineWidth(for type: GridType, screenScale: CGFloat) -> CGFloat {
        lineWidth(for: type) + 1 / max(screenScale, 1)
    }
}
