import UIKit

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

    private final class Request: RasterTileRequest {
        let task: Task<Void, Never>
        init(_ task: Task<Void, Never>) { self.task = task }
        func cancel() { task.cancel() }
    }

    func loadTile(_ tile: TileIndex, completion: @escaping (UIImage?) -> Void) -> RasterTileRequest? {
        guard let url = url(for: tile) else { completion(nil); return nil }
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        let task = Task {
            let image: UIImage?
            do {
                let (data, response) = try await NetworkSession.data(for: request, maximumBytes: 4 * 1024 * 1024)
                let acceptable = (response as? HTTPURLResponse).map { (200...299).contains($0.statusCode) } ?? false
                image = acceptable ? UIImage(data: data) : nil
            } catch { image = nil }
            if !Task.isCancelled { await MainActor.run { completion(image) } }
        }
        return Request(task)
    }

    /// {z}/{x}/{y} substitution + Esri token, matching OnlineRasterBasemapSource.
    private func url(for tile: TileIndex) -> URL? {
        if style.requiresEsriKey && !EsriKey.isAvailable { return nil }
        var s = style.urlTemplate
            .replacingOccurrences(of: "{z}", with: String(tile.z))
            .replacingOccurrences(of: "{x}", with: String(tile.x))
            .replacingOccurrences(of: "{y}", with: String(tile.y))
        if style.requiresEsriKey { s += "?token=\(EsriKey.token)" }
        return URL(string: s)
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
