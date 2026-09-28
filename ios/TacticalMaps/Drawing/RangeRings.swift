import Foundation

/// Range rings around a symbol, generated as ordinary closed line drawings so
/// they sync, export, hide with their layer and undo like any other drawing.
/// Each ring remembers its symbol and radius and is regenerated when the
/// symbol moves (see `RangeRingFollower`).
/// Android mirrors this in `RangeRings.kt`; `testdata/range_rings.json` pins both.
enum RangeRings {
    /// Points per ring before the closing point (every 5 degrees).
    static let segments = 72
    static let maxRings = 10
    /// Keeps generated rings well inside the range where a closed ring on a
    /// Web Mercator map is still a meaningful shape.
    static let maxRadiusMetres = 1_000_000.0
    static let dashPattern: [Double] = [8, 4]
    static let strokeWidth = 2.0

    /// Radii for `count` rings `interval` metres apart, or nil when the
    /// request is outside the supported range.
    static func radii(intervalMetres interval: Double, count: Int) -> [Double]? {
        guard interval.isFinite, interval > 0,
              (1...maxRings).contains(count),
              interval * Double(count) <= maxRadiusMetres else { return nil }
        return (1...count).map { interval * Double($0) }
    }

    /// Closed ring of `segments + 1` points clockwise from true north. The
    /// final point repeats the first so line renderers and GeoJSON close it.
    static func ring(center: Coordinate2D, radiusMetres: Double) -> [Coordinate2D] {
        var points = (0..<segments).map { index in
            destination(from: center,
                        bearingDegrees: Double(index) * 360 / Double(segments),
                        distanceMetres: radiusMetres)
        }
        if let first = points.first { points.append(first) }
        return points
    }

    /// Stroke colour for rings around `waypoint`: its affiliation colour, or
    /// the layer colour for symbols without one.
    static func strokeColorHex(for waypoint: Waypoint, layerColorHex: String?) -> String {
        switch SymbolListAffiliation(waypoint) {
        case .friend: return "#0E5FD8"
        case .hostile: return "#D8281F"
        case .neutral: return "#1E8A34"
        case .unknown: return "#E2A400"
        case .other: return layerColorHex ?? DrawingStyle.default.strokeColorHex
        }
    }

    /// Drawings for every ring, on the symbol's layer.
    static func shapes(around waypoint: Waypoint,
                       radii: [Double],
                       layerColorHex: String?,
                       createdAt: Date = .now) -> [DrawingShape] {
        let center = Coordinate2D(latitude: waypoint.latitude, longitude: waypoint.longitude)
        let style = DrawingStyle(
            strokeColorHex: strokeColorHex(for: waypoint, layerColorHex: layerColorHex),
            fillColorHex: nil,
            strokeWidth: strokeWidth,
            fillOpacity: 0,
            dashPattern: dashPattern,
            lineGraphic: nil
        )
        return radii.map { radius in
            DrawingShape(
                name: Messages.ringsRingName(DisplayFormat.distance(radius), waypoint.name),
                kind: .polyline,
                coordinates: ring(center: center, radiusMetres: radius),
                style: style,
                createdAt: createdAt,
                layerID: waypoint.layerID,
                anchorWaypointID: waypoint.id,
                ringRadiusMetres: radius
            )
        }
    }

    /// `shape` regenerated around its symbol's current position, or nil when
    /// it is not a ring of `waypoint` or already sits exactly there.
    static func followed(_ shape: DrawingShape, waypoint: Waypoint) -> DrawingShape? {
        guard shape.anchorWaypointID == waypoint.id,
              let radius = shape.ringRadiusMetres,
              radius > 0, radius <= maxRadiusMetres else { return nil }
        let center = Coordinate2D(latitude: waypoint.latitude, longitude: waypoint.longitude)
        let points = ring(center: center, radiusMetres: radius)
        guard shape.coordinates != points || shape.rotation != 0
                || shape.scaleX != 1 || shape.scaleY != 1 else { return nil }
        var moved = shape
        moved.coordinates = points
        moved.rotation = 0
        moved.scaleX = 1
        moved.scaleY = 1
        return moved
    }

    // MARK: - WGS84 geodesic direct problem (Vincenty, 1975)

    private static let a = 6_378_137.0
    private static let f = 1 / 298.257223563
    private static let b = (1 - f) * a

    static func destination(from start: Coordinate2D,
                            bearingDegrees: Double,
                            distanceMetres s: Double) -> Coordinate2D {
        // Short, explicitly typed steps keep Swift type-checking fast.
        let α1: Double = bearingDegrees * .pi / 180
        let sinα1: Double = sin(α1)
        let cosα1: Double = cos(α1)
        let tanU1: Double = (1 - f) * tan(start.latitude * .pi / 180)
        let cosU1: Double = 1 / (1 + tanU1 * tanU1).squareRoot()
        let sinU1: Double = tanU1 * cosU1
        let σ1: Double = atan2(tanU1, cosα1)
        let sinα: Double = cosU1 * sinα1
        let cos2α: Double = 1 - sinα * sinα
        let u2: Double = cos2α * (a * a - b * b) / (b * b)
        let aInner: Double = 4096 + u2 * (-768 + u2 * (320 - 175 * u2))
        let A: Double = 1 + u2 / 16384 * aInner
        let bInner: Double = 256 + u2 * (-128 + u2 * (74 - 47 * u2))
        let B: Double = u2 / 1024 * bInner

        var σ: Double = s / (b * A)
        var cos2σm: Double = 0
        var sinσ: Double = 0
        var cosσ: Double = 0
        for _ in 0..<200 {
            cos2σm = cos(2 * σ1 + σ)
            sinσ = sin(σ)
            cosσ = cos(σ)
            let Δσ = deltaSigma(B: B, sinσ: sinσ, cosσ: cosσ, cos2σm: cos2σm)
            let previous = σ
            σ = s / (b * A) + Δσ
            if abs(σ - previous) < 1e-12 { break }
        }
        cos2σm = cos(2 * σ1 + σ)
        sinσ = sin(σ)
        cosσ = cos(σ)

        let x: Double = sinU1 * sinσ - cosU1 * cosσ * cosα1
        let φ2Numerator: Double = sinU1 * cosσ + cosU1 * sinσ * cosα1
        let φ2Denominator: Double = (1 - f) * (sinα * sinα + x * x).squareRoot()
        let φ2: Double = atan2(φ2Numerator, φ2Denominator)
        let λ: Double = atan2(sinσ * sinα1, cosU1 * cosσ - sinU1 * sinσ * cosα1)
        let C: Double = f / 16 * cos2α * (4 + f * (4 - 3 * cos2α))
        let series: Double = cos2σm + C * cosσ * (-1 + 2 * cos2σm * cos2σm)
        let L: Double = λ - (1 - C) * f * sinα * (σ + C * sinσ * series)
        return Coordinate2D(latitude: φ2 * 180 / .pi,
                            longitude: normalizedLongitude(start.longitude + L * 180 / .pi))
    }

    private static func deltaSigma(B: Double, sinσ: Double, cosσ: Double, cos2σm: Double) -> Double {
        let cos2σmSquared: Double = cos2σm * cos2σm
        let first: Double = cosσ * (-1 + 2 * cos2σmSquared)
        let second: Double = B / 6 * cos2σm * (-3 + 4 * sinσ * sinσ) * (-3 + 4 * cos2σmSquared)
        return B * sinσ * (cos2σm + B / 4 * (first - second))
    }

    private static func normalizedLongitude(_ value: Double) -> Double {
        let wrapped = (value + 180).truncatingRemainder(dividingBy: 360)
        return (wrapped < 0 ? wrapped + 360 : wrapped) - 180
    }
}
