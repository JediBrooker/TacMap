import Foundation
import UIKit
import PDFKit
import MapKit

/// Bakes a calibrated `PDFMapSource` into an offline MBTiles raster pyramid
/// **on-device** — no desktop GDAL step. Swift mirror of the Android `PdfTiler`:
/// maps each Web-Mercator XYZ tile's WGS84 box back to PDF user-space points via
/// the inverse calibration affine, renders that region with PDFKit, and writes
/// PNG tiles via `MBTilesWriter`.
enum PDFTiler {

    struct Progress { let done: Int; let total: Int }

    /// Returns the written .mbtiles URL, or nil on failure / no calibration.
    static func generate(source: PDFMapSource,
                         progress: @escaping (Progress) -> Void) -> URL? {
        guard let transform = source.placementTransform,
              let inverse = transform.inverted(),
              let region = source.coverage,
              let doc = PDFDocument(url: source.url),
              let page = doc.page(at: 0) else { return nil }

        let mediaBox = page.bounds(for: .mediaBox)
        let minLat = region.center.latitude - region.span.latitudeDelta / 2
        let maxLat = region.center.latitude + region.span.latitudeDelta / 2
        let minLon = region.center.longitude - region.span.longitudeDelta / 2
        let maxLon = region.center.longitude + region.span.longitudeDelta / 2

        let (minZoom, maxZoom) = zoomRange(minLat: minLat, maxLat: maxLat,
                                           minLon: minLon, maxLon: maxLon)

        func range(_ z: Int) -> WebMercatorTiles.Range {
            WebMercatorTiles.tileRange(minLat: minLat, maxLat: maxLat,
                                       minLon: minLon, maxLon: maxLon, z: z)
        }

        var total = 0
        for z in minZoom...maxZoom { total += range(z).count }
        guard total > 0 else { return nil }

        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("offline_tiles", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let outURL = dir.appendingPathComponent("tacmap-\(Int(Date().timeIntervalSince1970)).mbtiles")

        guard let writer = MBTilesWriter(path: outURL.path) else { return nil }
        writer.writeMetadata(name: source.displayName, minZoom: minZoom, maxZoom: maxZoom,
                             minLon: minLon, minLat: minLat, maxLon: maxLon, maxLat: maxLat)

        let fmt = UIGraphicsImageRendererFormat()
        fmt.scale = 1
        fmt.opaque = true
        let imageRenderer = UIGraphicsImageRenderer(size: CGSize(width: 256, height: 256), format: fmt)

        var done = 0
        for z in minZoom...maxZoom {
            let r = range(z)
            guard r.minX <= r.maxX, r.minY <= r.maxY else { continue }
            writer.begin()
            for tx in r.minX...r.maxX {
                for ty in r.minY...r.maxY {
                    if let crop = pdfRect(inverse: inverse,
                                          box: WebMercatorTiles.tileBounds(z, tx, ty),
                                          mediaBox: mediaBox),
                       let data = renderTile(imageRenderer, page: page, crop: crop) {
                        writer.putTile(z: z, x: tx, y: ty, data: data)
                    }
                    done += 1
                    if done % 16 == 0 { progress(Progress(done: done, total: total)) }
                }
            }
            writer.commit()
        }
        progress(Progress(done: total, total: total))
        writer.close()
        return outURL
    }

    /// Map a tile's WGS84 box to a PDF user-space rect (y-up). Off-page areas are
    /// left white by the renderer, so we don't clamp — we only skip tiles that
    /// don't touch the page at all.
    private static func pdfRect(inverse: AffineTransform2D,
                                box: WebMercatorTiles.Box,
                                mediaBox: CGRect) -> CGRect? {
        // inverse.apply(CGPoint(x: lon, y: lat)) -> (.longitude = pdfX, .latitude = pdfY)
        let pts = [
            inverse.apply(CGPoint(x: box.west, y: box.south)),
            inverse.apply(CGPoint(x: box.east, y: box.south)),
            inverse.apply(CGPoint(x: box.east, y: box.north)),
            inverse.apply(CGPoint(x: box.west, y: box.north))
        ]
        let xs = pts.map { $0.longitude }
        let ys = pts.map { $0.latitude }
        guard let left = xs.min(), let right = xs.max(),
              let bottom = ys.min(), let top = ys.max() else { return nil }
        let rect = CGRect(x: left, y: bottom, width: right - left, height: top - bottom)
        guard rect.width > 0.01, rect.height > 0.01, rect.intersects(mediaBox) else { return nil }
        return rect
    }

    private static func renderTile(_ renderer: UIGraphicsImageRenderer,
                                   page: PDFPage, crop: CGRect) -> Data? {
        let size: CGFloat = 256
        let image = renderer.image { rctx in
            let ctx = rctx.cgContext
            UIColor.white.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))
            ctx.saveGState()
            // Map the PDF crop rect (y-up) onto the 256x256 tile (y-down), north up.
            ctx.translateBy(x: 0, y: size)
            ctx.scaleBy(x: size / crop.width, y: -size / crop.height)
            ctx.translateBy(x: -crop.origin.x, y: -crop.origin.y)
            page.draw(with: .mediaBox, to: ctx)
            ctx.restoreGState()
        }
        return image.pngData()
    }

    /// Min zoom (coverage ~fits one tile) accumulating up to a tile budget so a
    /// huge sheet can't generate forever. Resolution-agnostic.
    private static func zoomRange(minLat: Double, maxLat: Double,
                                  minLon: Double, maxLon: Double) -> (Int, Int) {
        func count(_ z: Int) -> Int {
            WebMercatorTiles.tileRange(minLat: minLat, maxLat: maxLat,
                                       minLon: minLon, maxLon: maxLon, z: z).count
        }
        var minZoom = 0
        for z in 0...19 {
            if count(z) <= 4 { minZoom = z } else { break }
        }
        var maxZoom = minZoom
        var total = 0
        for z in minZoom...19 {
            total += count(z)
            if total > 2500 { break }
            maxZoom = z
        }
        return (minZoom, maxZoom)
    }
}
