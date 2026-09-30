import Foundation

/// Elevation profile and line-of-sight maths, kept free of UI and network so
/// it can be pinned to `testdata/elevation_profile.json`. Android mirrors this
/// in `ElevationProfile.kt`.
enum ElevationProfile {
    /// Mean Earth radius (IUGG), metres.
    static let earthRadiusMetres = 6_371_008.8
    /// Standard atmospheric refraction coefficient for visual sight lines:
    /// light bends with the Earth, so the horizon is a little further away.
    static let refractionCoefficient = 0.13
    /// Two Open-Meteo requests' worth of points.
    static let maxSamples = 200
    /// About three DEM cells; the terrain data is no finer than this.
    static let targetSpacingMetres = 30.0

    struct Sample: Equatable {
        /// Distance along the path from its first point, metres.
        let distance: Double
        let latitude: Double
        let longitude: Double
    }

    struct Coordinate: Equatable {
        let latitude: Double
        let longitude: Double
    }

    /// Evenly spaced points along `path`, about every 30 m and never more
    /// than `maxSamples`; the first and last are the path's ends. Empty for
    /// fewer than two points or a path of zero length.
    static func samples(along path: [Coordinate]) -> [Sample] {
        guard path.count >= 2 else { return [] }
        let segments = (0..<(path.count - 1)).map { distance(path[$0], path[$0 + 1]) }
        let length = segments.reduce(0, +)
        guard length > 0, length.isFinite else { return [] }
        let count = min(maxSamples, max(2, Int((length / targetSpacingMetres).rounded(.down)) + 1))
        var cumulative = [0.0]
        for segment in segments { cumulative.append(cumulative[cumulative.count - 1] + segment) }

        var result: [Sample] = []
        result.reserveCapacity(count)
        var k = 0
        for i in 0..<count {
            let d = i == count - 1 ? length : length * Double(i) / Double(count - 1)
            while k < segments.count - 1 && d > cumulative[k + 1] { k += 1 }
            let a = path[k]
            let b = path[k + 1]
            let segment = segments[k]
            let f = segment <= 0 ? 0 : min(1, max(0, (d - cumulative[k]) / segment))
            let latitude = a.latitude + f * (b.latitude - a.latitude)
            let longitude = normalizedLongitude(a.longitude + f * normalizedLongitude(b.longitude - a.longitude))
            result.append(Sample(distance: d, latitude: latitude, longitude: longitude))
        }
        return result
    }

    struct Stats: Equatable {
        let minimum: Double
        let maximum: Double
        /// Sum of every rise along the profile.
        let ascent: Double
        /// Sum of every fall along the profile.
        let descent: Double
    }

    static func stats(elevations: [Double]) -> Stats? {
        guard let minimum = elevations.min(), let maximum = elevations.max() else { return nil }
        var ascent = 0.0
        var descent = 0.0
        for i in 0..<max(0, elevations.count - 1) {
            let step = elevations[i + 1] - elevations[i]
            if step > 0 { ascent += step } else { descent -= step }
        }
        return Stats(minimum: minimum, maximum: maximum, ascent: ascent, descent: descent)
    }

    struct SightLine: Equatable {
        /// True when terrain rises above the straight line between the
        /// observer's eye and the target.
        let blocked: Bool
        /// The sample where terrain comes closest to, or furthest above, the
        /// sight line; nil when the profile has no points between the ends.
        let worstIndex: Int?
        /// Terrain height above the sight line at `worstIndex`, metres:
        /// positive is how much it blocks, negative is the clearance.
        let worstMargin: Double?
        /// Whether the ground at each sample can be seen from the observer's
        /// eye. Hidden stretches are dead ground.
        let visible: [Bool]
        /// The sight line at each sample in the same frame as the terrain
        /// elevations, so it can be drawn over the profile (it curves up
        /// slightly because the Earth curves away beneath it).
        let heights: [Double]
    }

    /// Line of sight from an observer `observerHeight` above the first sample
    /// to a target `targetHeight` above the last, allowing for Earth
    /// curvature and refraction. Nil when there is nothing to compare.
    static func lineOfSight(distances: [Double], elevations: [Double],
                            observerHeight: Double, targetHeight: Double) -> SightLine? {
        guard distances.count == elevations.count, distances.count >= 2,
              let total = distances.last, total > 0 else { return nil }
        let effectiveRadius = earthRadiusMetres / (1 - refractionCoefficient)
        let drops = distances.map { $0 * $0 / (2 * effectiveRadius) }
        let terrain = zip(elevations, drops).map { $0 - $1 }
        let eye = elevations[0] + observerHeight
        let target = terrain[terrain.count - 1] + targetHeight
        let line = distances.map { eye + (target - eye) * $0 / total }

        var worstIndex: Int?
        var worstMargin = -Double.infinity
        for i in 1..<(distances.count - 1) where terrain[i] - line[i] > worstMargin {
            worstMargin = terrain[i] - line[i]
            worstIndex = i
        }

        var visible = [true]
        var steepest = -Double.infinity
        for i in 1..<distances.count {
            let slope = (terrain[i] - eye) / distances[i]
            visible.append(slope >= steepest)
            steepest = max(steepest, slope)
        }

        return SightLine(
            blocked: worstIndex != nil && worstMargin > 0,
            worstIndex: worstIndex,
            worstMargin: worstIndex == nil ? nil : worstMargin,
            visible: visible,
            heights: zip(line, drops).map { $0 + $1 }
        )
    }

    /// Great-circle distance, metres.
    static func distance(_ a: Coordinate, _ b: Coordinate) -> Double {
        let phi1 = a.latitude * .pi / 180
        let phi2 = b.latitude * .pi / 180
        let dPhi = phi2 - phi1
        let dLambda = normalizedLongitude(b.longitude - a.longitude) * .pi / 180
        let h = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * earthRadiusMetres * asin(min(1, h.squareRoot()))
    }

    static func normalizedLongitude(_ value: Double) -> Double {
        var wrapped = fmod(value + 180, 360)
        if wrapped < 0 { wrapped += 360 }
        return wrapped - 180
    }
}
