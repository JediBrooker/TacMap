import Foundation
import CoreLocation

/// Async service that returns terrain elevation (metres above sea level) for a
/// WGS84 coordinate. Backed by Open-Meteo's free elevation endpoint, which
/// serves the Copernicus DEM (~30m resolution globally) and requires no API key.
///
/// In-memory LRU-ish cache keyed by the coordinate rounded to 4 decimal places
/// (≈11m at the equator) so panning around a small area doesn’t re-hit the
/// network.
actor ElevationService {

    private struct Response: Decodable { let elevation: [Double] }

    private var cache: [String: Double] = [:]
    private var inFlight: Task<Double?, Never>?

    /// Fetch elevation in metres. Returns nil on network/parse failure (caller
    /// should treat as “unknown”).
    func elevation(for coordinate: CLLocationCoordinate2D) async -> Double? {
        // Skip the well-known sentinel (cameraCentre starts at 0,0).
        if coordinate.latitude == 0 && coordinate.longitude == 0 { return nil }

        let key = String(format: "%.4f,%.4f", coordinate.latitude, coordinate.longitude)
        if let cached = cache[key] { return cached }

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

        let value = await task.value
        if let value { cache[key] = value }
        return value
    }
}
