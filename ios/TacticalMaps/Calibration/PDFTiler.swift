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

        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = appSupport.appendingPathComponent("offline_tiles", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true,
                                                  attributes: [.protectionKey: FileProtectionType.complete])
        let outURL = dir.appendingPathComponent("tacmap-\(Int(Date().timeIntervalSince1970)).mbtiles")
        // Bake into a .partial temp and only publish to the real path on full
        // success, so an interrupted run can't leave a truncated file that later
        // loads as a "valid" (but incomplete) basemap.
        let tmpURL = outURL.appendingPathExtension("partial")

        guard let writer = MBTilesWriter(path: tmpURL.path) else { return nil }
        writer.writeMetadata(name: source.displayName, minZoom: minZoom, maxZoom: maxZoom,
                             minLon: minLon, minLat: minLat, maxLon: maxLon, maxLat: maxLat)

        let fmt = UIGraphicsImageRendererFormat()
        fmt.scale = 1
        fmt.opaque = true
        let imageRenderer = UIGraphicsImageRenderer(size: CGSize(width: 256, height: 256), format: fmt)

        var done = 0
        for z in minZoom...maxZoom {
            // Honour cancellation (Cancel button) — stop and clean up the temp.
            if Task.isCancelled {
                writer.close()
                try? FileManager.default.removeItem(at: tmpURL)
                return nil
            }
            let r = range(z)
            guard r.minX <= r.maxX, r.minY <= r.maxY else { continue }
            writer.begin()
            for tx in r.minX...r.maxX {
                for ty in r.minY...r.maxY {
                    let box = WebMercatorTiles.tileBounds(z, tx, ty)
                    if pdfRect(inverse: inverse, box: box, mediaBox: mediaBox) != nil,
                       let data = renderTile(imageRenderer, page: page,
                                             z: z, x: tx, y: ty,
                                             inverse: inverse, mediaBox: mediaBox) {
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

        // A tile/metadata write failed mid-bake (e.g. disk full) — don't pass a
        // half-baked file off as a complete basemap.
        guard !writer.hadError else {
            try? FileManager.default.removeItem(at: tmpURL)
            return nil
        }
        // Atomic publish.
        do {
            try? FileManager.default.removeItem(at: outURL)
            try FileManager.default.moveItem(at: tmpURL, to: outURL)
        } catch {
            try? FileManager.default.removeItem(at: tmpURL)
            return nil
        }
        return outURL
    }

    /// Map a tile's WGS84 box to a PDF user-space rect (y-up). Used only as the
    /// off-page skip gate — a tile whose PDF rect doesn't touch the page is not
    /// baked at all. The actual render (`renderTile`) re-derives per-strip rects.
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

    /// PDF-space bounding rect of the sub-band whose *Web-Mercator* tile-Y runs
    /// `[yTop, yBottom]` (tile units). Longitude is linear in both Mercator and
    /// the affine, so only the latitude edges are re-derived per strip.
    private static func stripRect(inverse: AffineTransform2D,
                                  west: Double, east: Double,
                                  yTop: Double, yBottom: Double, z: Int) -> CGRect? {
        let north = WebMercatorTiles.tileYToLat(yTop, z)
        let south = WebMercatorTiles.tileYToLat(yBottom, z)
        let pts = [
            inverse.apply(CGPoint(x: west, y: south)),
            inverse.apply(CGPoint(x: east, y: south)),
            inverse.apply(CGPoint(x: east, y: north)),
            inverse.apply(CGPoint(x: west, y: north))
        ]
        let xs = pts.map { $0.longitude }, ys = pts.map { $0.latitude }
        guard let left = xs.min(), let right = xs.max(),
              let bottom = ys.min(), let top = ys.max() else { return nil }
        return CGRect(x: left, y: bottom, width: right - left, height: top - bottom)
    }

    /// Render one 256×256 tile north-up. To stay Web-Mercator-correct we split the
    /// tile into horizontal strips that are each linear in tile-Y (Mercator) and
    /// map each strip through the calibration affine at its *true* latitude edges.
    /// A single linear scale over the whole tile would warp any tile spanning more
    /// than ~2° of latitude (large sheets at low zoom); strips reduce the residual
    /// to sub-pixel. Small high-zoom tiles span <¼° → one strip → zero extra cost.
    private static func renderTile(_ renderer: UIGraphicsImageRenderer,
                                   page: PDFPage, z: Int, x: Int, y: Int,
                                   inverse: AffineTransform2D, mediaBox: CGRect) -> Data? {
        let size: CGFloat = 256
        let box = WebMercatorTiles.tileBounds(z, x, y)
        // One strip per ≤¼° of latitude, capped at 16 (residual warp ≪ 1 px).
        let strips = max(1, min(16, Int(ceil((box.north - box.south) / 0.25))))

        let image = renderer.image { rctx in
            let ctx = rctx.cgContext
            UIColor.white.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))

            for i in 0..<strips {
                // Strip i covers tile pixel rows [pixelTop, pixelBottom] from the
                // top (north). Rounded integer edges so adjacent bands abut with
                // no hairline seam.
                let pixelTop = CGFloat((Double(i) * Double(size) / Double(strips)).rounded())
                let pixelBottom = CGFloat((Double(i + 1) * Double(size) / Double(strips)).rounded())
                let bandH = pixelBottom - pixelTop
                guard bandH > 0 else { continue }
                let yTop = Double(y) + Double(i) / Double(strips)
                let yBottom = Double(y) + Double(i + 1) / Double(strips)
                guard let r = stripRect(inverse: inverse, west: box.west, east: box.east,
                                        yTop: yTop, yBottom: yBottom, z: z),
                      r.width > 0.01, r.height > 0.01, r.intersects(mediaBox) else { continue }
                ctx.saveGState()
                ctx.clip(to: CGRect(x: 0, y: pixelTop, width: size, height: bandH))
                // Map strip rect (y-up PDF) onto pixel band [pixelTop, pixelBottom] (y-down).
                ctx.translateBy(x: 0, y: pixelTop)
                ctx.scaleBy(x: size / r.width, y: -bandH / r.height)
                ctx.translateBy(x: -r.minX, y: -r.maxY)
                page.draw(with: .mediaBox, to: ctx)
                ctx.restoreGState()
            }
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
