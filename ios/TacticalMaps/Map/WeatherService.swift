import Foundation
import CoreLocation

/// Current-conditions reading for a coordinate, from Open-Meteo (same provider
/// as elevation; no API key). Units: °C, m/s, metres.
struct WeatherReading: Equatable {
    let temperatureC: Double?
    let windSpeedMs: Double?
    let windGustsMs: Double?
    let visibilityM: Double?
    let weatherCode: Int?
}

/// Drone/UAV flight-safety risk derived from a `WeatherReading` against
/// configurable thresholds. The overall level is the worst of the components,
/// so a single dangerous factor (e.g. high gusts) flags the whole assessment.
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

/// Default UAV thresholds (small/consumer-drone oriented). Tunable later.
struct UAVThresholds {
    var windCautionMs = 7.0,  windDangerMs = 10.0
    var gustCautionMs = 8.0,  gustDangerMs = 12.0
    var visCautionM   = 5000.0, visDangerM = 1500.0
    var tempLowDangerC = -10.0, tempLowCautionC = 0.0
    var tempHighCautionC = 40.0, tempHighDangerC = 45.0
    static let `default` = UAVThresholds()
}

enum UAVAssessment {
    /// Worst-of-components risk for the reading. Missing values don't raise risk.
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

/// Fetches current conditions from Open-Meteo's forecast endpoint.
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
        if coordinate.latitude == 0 && coordinate.longitude == 0 { return nil }

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

    /// Pick the hourly visibility for the current hour (match on the hour
    /// prefix of `current.time`), falling back to the first entry.
    private func visibilityNow(_ r: Response, currentTime: String?) -> Double? {
        guard let times = r.hourly?.time, let vis = r.hourly?.visibility, !vis.isEmpty else { return nil }
        if let currentTime, let idx = times.firstIndex(of: currentTime), idx < vis.count {
            return vis[idx]
        }
        return vis.first
    }
}
