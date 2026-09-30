import Foundation

/// Fetches terrain heights for elevation-profile samples from Open-Meteo's
/// elevation endpoint (Copernicus DEM, no API key), at most 100 points per
/// request. Like the other elevation lookups it only runs while the user has
/// turned on online lookups, and it rounds every point to 4 decimal places
/// (about 11 m) before it leaves the device. Android mirrors this in
/// `ElevationProfileService.kt`.
struct ElevationProfileService {
    enum Failure: Error, Equatable {
        /// Online lookups are off in Privacy & OPSEC.
        case lookupsOff
        /// No usable answer: offline, a server error, or a malformed reply.
        case network
    }

    typealias Fetch = (URLRequest) async throws -> (Data, URLResponse)

    /// Open-Meteo accepts up to 100 coordinates per request.
    static let batchSize = 100

    private struct Response: Decodable { let elevation: [Double?] }

    private let onlineLookups: () -> Bool
    private let fetch: Fetch

    init(onlineLookups: @escaping () -> Bool = { OpsecSettings.shared.onlineLookups },
         fetch: @escaping Fetch = { try await NetworkSession.data(for: $0, maximumBytes: 64 * 1024) }) {
        self.onlineLookups = onlineLookups
        self.fetch = fetch
    }

    /// Terrain height in metres for every sample, in order.
    func elevations(for samples: [ElevationProfile.Sample]) async throws -> [Double] {
        if let fake = Self.fakeTerrain(for: samples) { return fake }
        guard onlineLookups() else { throw Failure.lookupsOff }
        var heights: [Double] = []
        heights.reserveCapacity(samples.count)
        for url in Self.requestURLs(for: samples) {
            try Task.checkCancellation()
            var request = URLRequest(url: url)
            request.timeoutInterval = 10
            request.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
            let reply: (Data, URLResponse)
            do {
                reply = try await fetch(request)
            } catch {
                try Task.checkCancellation()
                throw Failure.network
            }
            try Task.checkCancellation()
            // The setting can be turned off while a request is in flight.
            guard onlineLookups() else { throw Failure.lookupsOff }
            guard let http = reply.1 as? HTTPURLResponse, (200...299).contains(http.statusCode),
                  let decoded = try? JSONDecoder().decode(Response.self, from: reply.0) else {
                throw Failure.network
            }
            for height in decoded.elevation {
                guard let height, height.isFinite else { throw Failure.network }
                heights.append(height)
            }
        }
        guard heights.count == samples.count else { throw Failure.network }
        return heights
    }

    /// One request per 100 samples, coordinates rounded to 4 decimal places.
    static func requestURLs(for samples: [ElevationProfile.Sample]) -> [URL] {
        let locale = Locale(identifier: "en_US_POSIX")
        return stride(from: 0, to: samples.count, by: batchSize).compactMap { start in
            let batch = samples[start..<min(start + batchSize, samples.count)]
            var components = URLComponents(string: "https://api.open-meteo.com/v1/elevation")
            components?.queryItems = [
                URLQueryItem(name: "latitude", value: batch.map {
                    String(format: "%.4f", locale: locale, $0.latitude)
                }.joined(separator: ",")),
                URLQueryItem(name: "longitude", value: batch.map {
                    String(format: "%.4f", locale: locale, $0.longitude)
                }.joined(separator: ",")),
            ]
            return components?.url
        }
    }

    /// Simulator UI tests draw a fixed ridge instead of calling the network,
    /// so the profile can be screenshotted offline. Never in release builds.
    static func fakeTerrain(for samples: [ElevationProfile.Sample],
                            environment: [String: String] = ProcessInfo.processInfo.environment) -> [Double]? {
#if DEBUG && targetEnvironment(simulator)
        guard environment["TACMAP_UITEST_FAKE_TERRAIN"] == "1",
              let length = samples.last?.distance, length > 0 else { return nil }
        return samples.map { sample in
            let x = sample.distance / length
            return 180 + 40 * sin(x * 7) + 90 * exp(-pow((x - 0.45) / 0.08, 2))
        }
#else
        return nil
#endif
    }
}
