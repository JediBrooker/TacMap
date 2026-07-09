import Foundation
import CoreLocation

/// Current conditions for a coordinate, pulled from Open-Meteo (same as
/// elevation lookups, no API key needed). Units: °C, m/s, metres.
struct WeatherReading: Equatable {
    let temperatureC: Double?
    let windSpeedMs: Double?
    let windGustsMs: Double?
    let visibilityM: Double?
    let weatherCode: Int?
}

/// UAV flight risk from a WeatherReading vs configurable thresholds.
/// Takes the worst-of component risk, so one bad factor (e.g. high gusts)
/// flags the whole thing.
enum UAVRisk: Int, Comparable {
    case safe = 0, caution = 1, danger = 2
    static func < (l: UAVRisk, r: UAVRisk) -> Bool { l.rawValue < r.rawValue }

    var label: String {
        switch self {
        case .safe:    return "Safe to fly"
        case .caution: return "Marginal — caution"
        case .danger:  return "Do not fly"
        }
    }
}

/// Default thresholds for small/consumer drones. Prob make these configurable later.
struct UAVThresholds {
    var windCautionMs = 7.0,  windDangerMs = 10.0
    var gustCautionMs = 8.0,  gustDangerMs = 12.0
    var visCautionM   = 5000.0, visDangerM = 1500.0
    var tempLowDangerC = -10.0, tempLowCautionC = 0.0
    var tempHighCautionC = 40.0, tempHighDangerC = 45.0
    static let `default` = UAVThresholds()
}

enum UAVAssessment {
    /// Worst-of risk across all components. Missing values just get skipped.
    static func risk(for r: WeatherReading, _ t: UAVThresholds = .default) -> UAVRisk {
        var level: UAVRisk = .safe
        func bump(_ x: UAVRisk) { if x > level { level = x } }

        if let w = r.windSpeedMs {
            if w >= t.windDangerMs { bump(.danger) } else if w >= t.windCautionMs { bump(.caution) }
        }
        if let g = r.windGustsMs {
            if g >= t.gustDangerMs { bump(.danger) } else if g >= t.gustCautionMs { bump(.caution) }
        }
        if let v = r.visibilityM {
            if v <= t.visDangerM { bump(.danger) } else if v <= t.visCautionM { bump(.caution) }
        }
        if let temp = r.temperatureC {
            if temp <= t.tempLowDangerC || temp >= t.tempHighDangerC { bump(.danger) }
            else if temp <= t.tempLowCautionC || temp >= t.tempHighCautionC { bump(.caution) }
        }
        return level
    }
}

/// Fetch current conditions from Open-Meteo forecast endpoint.
actor WeatherService {

    private struct Response: Decodable {
        struct Current: Decodable {
            let time: String?
            let temperature_2m: Double?
            let wind_speed_10m: Double?
            let wind_gusts_10m: Double?
            let weather_code: Int?
        }
        struct Hourly: Decodable {
            let time: [String]?
            let visibility: [Double]?
        }
        let current: Current?
        let hourly: Hourly?
    }

    func reading(for coordinate: CLLocationCoordinate2D) async -> WeatherReading? {
        // OPSEC: this sends the coordinate to Open-Meteo, so bail out
        // unless user opted into online lookups.
        guard OpsecSettings.shared.onlineLookups else { return nil }
        if coordinate.latitude == 0 && coordinate.longitude == 0 { return nil }
        // Coarsen to ~110 m (3 dp) so the exact position isn't disclosed.
        let coordinate = CLLocationCoordinate2D(
            latitude: (coordinate.latitude * 1000).rounded() / 1000,
            longitude: (coordinate.longitude * 1000).rounded() / 1000)

        var components = URLComponents(string: "https://api.open-meteo.com/v1/forecast")
        components?.queryItems = [
            URLQueryItem(name: "latitude",  value: String(coordinate.latitude)),
            URLQueryItem(name: "longitude", value: String(coordinate.longitude)),
            URLQueryItem(name: "current",   value: "temperature_2m,wind_speed_10m,wind_gusts_10m,weather_code"),
            URLQueryItem(name: "hourly",    value: "visibility"),
            URLQueryItem(name: "wind_speed_unit", value: "ms"),
            URLQueryItem(name: "forecast_days", value: "1"),
            URLQueryItem(name: "timezone", value: "auto")
        ]
        guard let url = components?.url else { return nil }

        var request = URLRequest(url: url)
        request.timeoutInterval = 8
        do {
            let (data, _) = try await URLSession.shared.data(for: request)
            let decoded = try JSONDecoder().decode(Response.self, from: data)
            guard let c = decoded.current else { return nil }
            return WeatherReading(
                temperatureC: c.temperature_2m,
                windSpeedMs:  c.wind_speed_10m,
                windGustsMs:  c.wind_gusts_10m,
                visibilityM:  visibilityNow(decoded, currentTime: c.time),
                weatherCode:  c.weather_code
            )
        } catch {
            return nil
        }
    }

    /// Grab hourly visibility matching current hour, falls back
    /// to first entry if we can't find a match.
    private func visibilityNow(_ r: Response, currentTime: String?) -> Double? {
        guard let times = r.hourly?.time, let vis = r.hourly?.visibility, !vis.isEmpty else { return nil }
        if let currentTime, let idx = times.firstIndex(of: currentTime), idx < vis.count {
            return vis[idx]
        }
        return vis.first
    }
}
