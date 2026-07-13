import Foundation
import CoreLocation

/// A terrain-elevation reading for the crosshair / map-centre coordinate.
struct ElevationReading: Equatable {
    /// Metres above sea level.
    let metres: Double
    /// True when value came from nearby-cache fallback (network down).
    /// UI shows stale readings with "~". Fresh fetch or exact cache
    /// hit = not stale.
    let isStale: Bool
}

/// Bounded cache of DEM elevation readings, keyed by coordinate rounded
/// to 4dp (~11m). Has both exact and nearest-neighbour lookup so when
/// network drops we can still show an approx height from somewhere
/// nearby instead of blanking the readout.
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

    /// Insert or refresh a reading. Refresh moves to most-recent,
    /// evicts oldest when over capacity.
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

    /// Nearest cached reading within `maxMetres`, nil if nothing close
    /// enough. This is the offline fallback.
    func nearest(to coordinate: CLLocationCoordinate2D, within maxMetres: Double) -> Double? {
        var best: (metres: Double, dist: Double)?
        for e in entries {
            let d = Self.distanceMetres(coordinate, e.coordinate)
            guard d <= maxMetres else { continue }
            if best == nil || d < best!.dist { best = (e.metres, d) }
        }
        return best?.metres
    }

    /// Equirectangular approximation, plenty accurate at the few-km scale
    /// we use for the offline fallback.
    static func distanceMetres(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> Double {
        let R = 6_371_000.0
        let dLat = (b.latitude - a.latitude) * .pi / 180
        let meanLat = (a.latitude + b.latitude) / 2 * .pi / 180
        let dLon = (b.longitude - a.longitude) * .pi / 180 * cos(meanLat)
        return R * (dLat * dLat + dLon * dLon).squareRoot()
    }
}

/// Returns terrain elevation (metres ASL) for a WGS84 coordinate.
/// Uses Open-Meteo's free elevation endpoint (Copernicus DEM, ~30m
/// resolution, no API key needed).
///
/// Caches readings so when network drops we fall back to nearest
/// cached value within staleFallbackMetres, marked isStale. Field
/// user with no signal still sees approx height instead of "--".
actor ElevationService {

    private struct Response: Decodable { let elevation: [Double] }

    private var cache = ElevationCache()
    private var inFlight: Task<Double?, Never>?

    /// Max distance (metres) for offline nearest-cache fallback. Beyond
    /// this we just report unknown rather than a misleading value.
    private let staleFallbackMetres: Double

    init(staleFallbackMetres: Double = 2_000) {
        self.staleFallbackMetres = staleFallbackMetres
    }

    /// Fetch elevation reading. nil = genuinely unknown (no network
    /// and nothing close enough in cache).
    func reading(for coordinate: CLLocationCoordinate2D) async -> ElevationReading? {
        // OPSEC: elevation lookups transmit the coordinate to a third party
        // (Open-Meteo), so only proceed when the user has opted in.
        guard OpsecSettings.shared.onlineLookups else { return nil }
        // Skip the well-known sentinel (cameraCentre starts at 0,0).
        if coordinate.latitude == 0 && coordinate.longitude == 0 { return nil }
        // Coarsen to ~110 m (3 dp) so the exact map centre isn't disclosed.
        let coordinate = CLLocationCoordinate2D(
            latitude: (coordinate.latitude * 1000).rounded() / 1000,
            longitude: (coordinate.longitude * 1000).rounded() / 1000)

        // Exact hit on a previous DEM fetch. Terrain doesnt move so its fresh.
        if let exact = cache.exact(coordinate) {
            return ElevationReading(metres: exact, isStale: false)
        }

        // Cancel any pending fetch, only the latest position matters.
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
                request.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
                let (data, response) = try await NetworkSession.data(for: request, maximumBytes: 64 * 1024)
                if Task.isCancelled { return nil }
                guard let http = response as? HTTPURLResponse,
                      (200...299).contains(http.statusCode) else { return nil }
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

        // Network failed, fall back to nearest cached height.
        if let near = cache.nearest(to: coordinate, within: staleFallbackMetres) {
            return ElevationReading(metres: near, isStale: true)
        }
        return nil
    }
}
