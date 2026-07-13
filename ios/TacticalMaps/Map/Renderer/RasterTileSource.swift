import UIKit
import Combine

/// Coordinate-free provider health for user feedback. No URL, tile address, or
/// map position is retained or logged.
@MainActor final class OnlineTileHealth: ObservableObject {
    static let shared = OnlineTileHealth()
    @Published private(set) var temporarilyUnavailable = false
    private var consecutiveFailures = 0

    func succeeded() {
        consecutiveFailures = 0
        temporarilyUnavailable = false
    }

    func failed() {
        consecutiveFailures += 1
        if consecutiveFailures >= 8 { temporarilyUnavailable = true }
    }
}

/// Supplies tile images to the custom `TileMapView`. Each concrete source knows
/// how to turn a `TileIndex` into a `UIImage` (online fetch, MBTiles read, or a
/// synthesised blank), plus its zoom range and pixel size. This is the seam that
/// lets one renderer draw every basemap the app supports.
protocol RasterTileSource: AnyObject {
    var minZoom: Int { get }
    var maxZoom: Int { get }
    /// Native tile pixel size (256 for XYZ / OpenTopoMap, 512 for Esri static).
    var tilePixelSize: Int { get }
    /// Load a tile. `completion` is called on the main thread with the image or
    /// nil (miss / cancelled / error). Returns a token the loader can cancel.
    func loadTile(_ tile: TileIndex, completion: @escaping (UIImage?) -> Void) -> RasterTileRequest?
}

/// Cancellable handle for an in-flight tile load.
protocol RasterTileRequest: AnyObject {
    func cancel()
}

// MARK: - Online raster (Esri / OpenTopoMap) via URLSession

/// Online XYZ raster source. Builds the URL from a `BasemapStyle` (with the Esri
/// token when needed) and fetches over HTTPS. No MapKit involved.
final class OnlineRasterTileSource: RasterTileSource {
    private let style: BasemapStyle

    var minZoom: Int { 0 }
    var maxZoom: Int { style.maximumZ }
    var tilePixelSize: Int { style.tileSize }

    init(_ style: BasemapStyle) {
        self.style = style
    }

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        // Coordinates in tile URLs are operationally sensitive. Keep useful
        // caching in RAM only; never leave an AO trail in the disk URL cache.
        config.urlCache = URLCache(memoryCapacity: 32 * 1024 * 1024, diskCapacity: 0)
        config.requestCachePolicy = .useProtocolCachePolicy
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        config.urlCredentialStorage = nil
        config.timeoutIntervalForRequest = 10
        config.timeoutIntervalForResource = 20
        config.httpMaximumConnectionsPerHost = 2
        return URLSession(configuration: config)
    }()

    private final class Request: RasterTileRequest {
        let task: Task<Void, Never>
        init(_ task: Task<Void, Never>) { self.task = task }
        func cancel() { task.cancel() }
    }

    func loadTile(_ tile: TileIndex, completion: @escaping (UIImage?) -> Void) -> RasterTileRequest? {
        guard let url = url(for: tile) else { completion(nil); return nil }
        var request = URLRequest(url: url)
        request.cachePolicy = .useProtocolCachePolicy
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        let task = Task {
            let image: UIImage?
            image = await Self.fetch(request)
            if !Task.isCancelled { await MainActor.run { completion(image) } }
        }
        return Request(task)
    }

    /// {z}/{x}/{y} substitution + Esri token, matching OnlineRasterBasemapSource.
    private func url(for tile: TileIndex) -> URL? {
        if style.requiresEsriKey && !EsriKey.isAvailable { return nil }
        var s = style.urlTemplate
            .replacingOccurrences(of: "{s}", with: Self.openTopoHost(for: tile))
            .replacingOccurrences(of: "{z}", with: String(tile.z))
            .replacingOccurrences(of: "{x}", with: String(tile.x))
            .replacingOccurrences(of: "{y}", with: String(tile.y))
        if style.requiresEsriKey { s += "?token=\(EsriKey.token)" }
        return URL(string: s)
    }


    private static var userAgent: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown"
        return "TacMap/\(version) (iOS; https://tacticalmaps.app)"
    }

    /// Stable sharding: the same tile always uses the same documented host,
    /// preserving cache locality while distributing a viewport across a/b/c.
    static func openTopoHost(for tile: TileIndex) -> String {
        ["a", "b", "c"][abs(tile.x &+ tile.y &+ tile.z) % 3]
    }

    private static func fetch(_ request: URLRequest) async -> UIImage? {
        for attempt in 0..<3 {
            if Task.isCancelled { return nil }
            do {
                let (data, response) = try await boundedData(for: request, maximumBytes: 4 * 1024 * 1024)
                guard data.count <= 4 * 1024 * 1024,
                      let http = response as? HTTPURLResponse else { return nil }
                if (200...299).contains(http.statusCode), let image = UIImage(data: data) {
                    await OnlineTileHealth.shared.succeeded()
                    return image
                }
                guard http.statusCode == 429 || http.statusCode == 503 else {
                    NSLog("TacMap basemap request failed (HTTP %d)", http.statusCode)
                    await OnlineTileHealth.shared.failed()
                    return nil
                }
                NSLog("TacMap basemap provider temporarily unavailable (HTTP %d)", http.statusCode)
            } catch is CancellationError { return nil }
            catch {
                NSLog("TacMap basemap request failed (%@)", String(describing: type(of: error)))
            }
            if attempt < 2 {
                try? await Task.sleep(nanoseconds: UInt64(350 * (1 << attempt)) * 1_000_000)
            }
        }
        await OnlineTileHealth.shared.failed()
        return nil
    }

    private static func boundedData(for request: URLRequest, maximumBytes: Int) async throws -> (Data, URLResponse) {
        let (bytes, response) = try await session.bytes(for: request)
        if response.expectedContentLength > Int64(maximumBytes) { throw NetworkSession.LimitError.responseTooLarge }
        var data = Data()
        data.reserveCapacity(min(maximumBytes, max(0, Int(response.expectedContentLength))))
        for try await byte in bytes {
            if data.count >= maximumBytes { throw NetworkSession.LimitError.responseTooLarge }
            data.append(byte)
        }
        return (data, response)
    }
}

// MARK: - Offline MBTiles (sideloaded raster, zero network)

/// Reads tiles from a local MBTiles file via MBTilesStore. Decodes off the main
/// thread so scrolling stays smooth. Never touches the network.
final class OfflineRasterTileSource: RasterTileSource {
    private let store: MBTilesStore
    private let queue = DispatchQueue(label: "tacmap.offline-tiles", qos: .userInitiated)

    var minZoom: Int { store.metadata.minZoom ?? 0 }
    var maxZoom: Int { store.metadata.maxZoom ?? 19 }
    var tilePixelSize: Int { 256 }

    init(_ source: OfflineTileMapSource) { self.store = source.store }

    private final class Request: RasterTileRequest {
        var cancelled = false
        func cancel() { cancelled = true }
    }

    func loadTile(_ tile: TileIndex, completion: @escaping (UIImage?) -> Void) -> RasterTileRequest? {
        let req = Request()
        queue.async {
            if req.cancelled { DispatchQueue.main.async { completion(nil) }; return }
            let image = self.store.tileData(z: tile.z, x: tile.x, y: tile.y).flatMap { UIImage(data: $0) }
            DispatchQueue.main.async { completion(req.cancelled ? nil : image) }
        }
        return req
    }
}
