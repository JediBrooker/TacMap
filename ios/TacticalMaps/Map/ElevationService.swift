import Foundation
import CoreLocation

/// A terrain-elevation reading for the crosshair / map-centre coordinate.
struct ElevationReading: Equatable {
    /// Metres above sea level.
    let metres: Double
    /// True when this value came from the nearby-cache fallback because the
    /// network was unavailable. The UI marks stale readings approximate ("~");
    /// a fresh fetch (or an exact hit on a previous fetch) is not stale.
    let isStale: Bool
}

/// Pure, testable bounded cache of DEM elevation readings keyed by coordinate
/// rounded to 4 dp (≈11 m). Besides exact hits it offers a nearest-neighbour
/// lookup, so a dropped network can still show an approximate height from
/// somewhere we've already been instead of blanking the readout.
struct ElevationCache {
    struct Entry {
        let coordinate: CLLocationCoordinate2D
        let metres: Double
    }

    /// Oldest first, most-recently-written last.
    private(set) var entries: [Entry] = []
    let capacity: Int

    init(capacity: Int = 256) { self.capacity = max(1, capacity) }

    private static func key(_ c: CLLocationCoordinate2D) -> String {
        String(format: "%.4f,%.4f", c.latitude, c.longitude)
    }

    /// Insert (or refresh) a reading. Refreshing moves it to most-recent and
    /// evicts the oldest entries once over capacity.
    mutating func insert(_ coordinate: CLLocationCoordinate2D, metres: Double) {
        let k = Self.key(coordinate)
        entries.removeAll { Self.key($0.coordinate) == k }
        entries.append(Entry(coordinate: coordinate, metres: metres))
        if entries.count > capacity {
            entries.removeFirst(entries.count - capacity)
        }
    }

    /// Exact hit on the rounded key (a previously-fetched DEM value).
    func exact(_ coordinate: CLLocationCoordinate2D) -> Double? {
        let k = Self.key(coordinate)
        return entries.last { Self.key($0.coordinate) == k }?.metres
    }

    /// Nearest cached reading within `maxMetres`, or nil if none is close
    /// enough. The offline fallback.
    func nearest(to coordinate: CLLocationCoordinate2D, within maxMetres: Double) -> Double? {
        var best: (metres: Double, dist: Double)?
        for e in entries {
            let d = Self.distanceMetres(coordinate, e.coordinate)
            guard d <= maxMetres else { continue }
            if best == nil || d < best!.dist { best = (e.metres, d) }
        }
        return best?.metres
    }

    /// Equirectangular approximation — plenty accurate at the few-km scale of
    /// the offline fallback.
    static func distanceMetres(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> Double {
        let R = 6_371_000.0
        let dLat = (b.latitude - a.latitude) * .pi / 180
        let meanLat = (a.latitude + b.latitude) / 2 * .pi / 180
        let dLon = (b.longitude - a.longitude) * .pi / 180 * cos(meanLat)
        return R * (dLat * dLat + dLon * dLon).squareRoot()
    }
}

/// Async service that returns terrain elevation (metres above sea level) for a
/// WGS84 coordinate. Backed by Open-Meteo's free elevation endpoint, which
/// serves the Copernicus DEM (~30 m resolution globally) and requires no API key.
///
/// Offline-resilient: successful readings are cached, and when the network is
/// unavailable the nearest cached reading (within `staleFallbackMetres`) is
/// returned marked `isStale`, so a field user with no signal still sees an
/// approximate height instead of a blank "—".
actor ElevationService {

    private struct Response: Decodable { let elevation: [Double] }

    private var cache = ElevationCache()
    private var inFlight: Task<Double?, Never>?

    /// How far the offline nearest-cache fallback will reach (metres). Beyond
    /// this we honestly report "unknown" rather than a misleadingly distant value.
    private let staleFallbackMetres: Double

    init(staleFallbackMetres: Double = 2_000) {
        self.staleFallbackMetres = staleFallbackMetres
    }

    /// Fetch an elevation reading. Returns nil only when the value is genuinely
    /// unknown (no network *and* nothing close enough cached).
    func reading(for coordinate: CLLocationCoordinate2D) async -> ElevationReading? {
        // Skip the well-known sentinel (cameraCentre starts at 0,0).
        if coordinate.latitude == 0 && coordinate.longitude == 0 { return nil }

        // Exact hit on a previous DEM fetch — fresh, the terrain doesn't move.
        if let exact = cache.exact(coordinate) {
            return ElevationReading(metres: exact, isStale: false)
        }

        // Cancel any pending fetch — only the latest position matters.
        inFlight?.cancel()

        let task = Task<Double?, Never> { [coordinate] in
            var components = URLComponents(string: "https://api.open-meteo.com/v1/elevation")
            components?.queryItems = [
                URLQueryItem(name: "latitude",  value: String(coordinate.latitude)),
                URLQueryItem(name: "longitude", value: String(coordinate.longitude))
            ]
            guard let url = components?.url else { return nil }

            var request = URLRequest(url: url)
            request.timeoutInterval = 6

            do {
                let (data, _) = try await URLSession.shared.data(for: request)
                if Task.isCancelled { return nil }
                let decoded = try JSONDecoder().decode(Response.self, from: data)
                return decoded.elevation.first
            } catch {
                return nil
            }
        }
        inFlight = task

        if let value = await task.value {
            cache.insert(coordinate, metres: value)
            return ElevationReading(metres: value, isStale: false)
        }

        // Network failed — fall back to the nearest height we already know.
        if let near = cache.nearest(to: coordinate, within: staleFallbackMetres) {
            return ElevationReading(metres: near, isStale: true)
        }
        return nil
    }
}
