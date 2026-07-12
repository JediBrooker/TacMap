import Foundation
import UIKit
import MapKit

/// Auto terrain heatmap - samples a grid of elevations from Open-Meteo's
/// Copernicus DEM (same source as elevation readout) and colours them
/// blue (low) to red (high). No user-uploaded file needed, unlike the
/// competitor's manual elevation-file import. Swift mirror of Android
/// TerrainHeatmapService.
actor TerrainHeatmapService {

    private struct Response: Decodable { let elevation: [Double] }

    /// Sample a gridxgrid DEM over region and return a coloured image.
    func generate(region: MKCoordinateRegion, grid: Int = 24) async -> UIImage? {
        // OPSEC: the heat-map samples the region's DEM from a third party
        // (Open-Meteo), transmitting the coordinates. Opt-in only.
        guard OpsecSettings.shared.onlineLookups else { return nil }
        guard grid >= 2 else { return nil }
        let north = region.center.latitude + region.span.latitudeDelta / 2
        let south = region.center.latitude - region.span.latitudeDelta / 2
        let west = region.center.longitude - region.span.longitudeDelta / 2
        let east = region.center.longitude + region.span.longitudeDelta / 2

        var lat = [Double](repeating: 0, count: grid * grid)
        var lon = [Double](repeating: 0, count: grid * grid)
        for r in 0..<grid {           // row 0 = north edge
            let y = north - (north - south) * Double(r) / Double(grid - 1)
            for c in 0..<grid {       // col 0 = west edge
                let idx = r * grid + c
                lat[idx] = y
                lon[idx] = west + (east - west) * Double(c) / Double(grid - 1)
            }
        }

        var elev = [Double?](repeating: nil, count: grid * grid)
        var i = 0
        while i < elev.count {
            guard OpsecSettings.shared.onlineLookups, !Task.isCancelled else { return nil }
            let end = min(i + 100, elev.count)   // Open-Meteo: <=100 points/request
            let latStr = (i..<end).map { String(lat[$0]) }.joined(separator: ",")
            let lonStr = (i..<end).map { String(lon[$0]) }.joined(separator: ",")
            guard var comps = URLComponents(string: "https://api.open-meteo.com/v1/elevation") else { return nil }
            comps.queryItems = [
                URLQueryItem(name: "latitude", value: latStr),
                URLQueryItem(name: "longitude", value: lonStr)
            ]
            guard let url = comps.url else { return nil }
            var req = URLRequest(url: url); req.timeoutInterval = 8
            do {
                req.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
                let (data, response) = try await NetworkSession.data(for: req, maximumBytes: 256 * 1024)
                guard OpsecSettings.shared.onlineLookups, !Task.isCancelled,
                      let http = response as? HTTPURLResponse,
                      (200...299).contains(http.statusCode) else { return nil }
                let decoded = try JSONDecoder().decode(Response.self, from: data)
                for k in decoded.elevation.indices where i + k < elev.count { elev[i + k] = decoded.elevation[k] }
            } catch {
                return nil
            }
            i = end
        }

        let known = elev.compactMap { $0 }
        guard let lo = known.min(), let hi = known.max() else { return nil }
        let range = (hi - lo) > 1e-6 ? (hi - lo) : 1.0

        return renderImage(grid: grid, elev: elev, lo: lo, range: range)
    }

    /// Render into a grid x grid image. UIKit/MapKit handles the upscaling.
    private func renderImage(grid: Int, elev: [Double?], lo: Double, range: Double) -> UIImage? {
        let fmt = UIGraphicsImageRendererFormat()
        fmt.scale = 1
        fmt.opaque = false
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: grid, height: grid), format: fmt)
        return renderer.image { rctx in
            let ctx = rctx.cgContext
            for r in 0..<grid {
                for c in 0..<grid {
                    guard let e = elev[r * grid + c] else { continue }
                    let t = max(0.0, min(1.0, (e - lo) / range))
                    // Blue (low) -> red (high), ~45% alpha.
                    let hue = CGFloat((1.0 - t) * 240.0 / 360.0)
                    UIColor(hue: hue, saturation: 0.85, brightness: 0.95, alpha: 0.45).setFill()
                    ctx.fill(CGRect(x: c, y: r, width: 1, height: 1))
                }
            }
        }
    }
}
