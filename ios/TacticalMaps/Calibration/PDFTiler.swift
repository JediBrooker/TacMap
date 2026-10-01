import Foundation
import UIKit
import MapKit

/// Bakes a georeferenced PDFMapSource into an offline MBTiles raster pyramid
/// on-device, no desktop GDAL step. For every tile strip the page points of
/// its corners come from georef.toPage (true projection, not a lat/lon box)
/// and the page is drawn in RAW user space via drawPDFPage with that CTM, so
/// rotation/shear of the sheet survive and /Rotate + box origins can't
/// double up (D1-04). The adaptive cell-warp renderer replaces this next.
enum PDFTiler {

    struct Progress { let done: Int; let total: Int }

    /// Returns the written .mbtiles URL, or nil on failure / no calibration.
    static func generate(source: PDFMapSource,
                         progress: @escaping (Progress) -> Void) -> URL? {
        // a provisional placement is a guess, baking it would launder it into
        // an "offline basemap" with no uncalibrated label on it
        let georef = source.georef
        guard !source.isUncalibrated,
              let region = source.coverage,
              let doc = CGPDFDocument(source.url as CFURL),
              let page = doc.page(at: georef.page + 1) else { return nil }

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
        let outURL = dir.appendingPathComponent("tacmap-\(UUID().uuidString).mbtiles")
        // Bake into a .partial temp and only publish to real path on full
        // success. An interrupted run can't leave a truncated file that later
        // loads as a "valid" but incomplete basemap.
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
            // Honour cancellation (Cancel button) - stop and clean up temp.
            if Task.isCancelled {
                writer.close()
                removeMBTilesArtifacts(at: tmpURL)
                return nil
            }
            let r = range(z)
            guard r.minX <= r.maxX, r.minY <= r.maxY else { continue }
            writer.begin()
            for tx in r.minX...r.maxX {
                for ty in r.minY...r.maxY {
                    if Task.isCancelled {
                        writer.close()
                        removeMBTilesArtifacts(at: tmpURL)
                        return nil
                    }
                    if touchesCrop(georef, z: z, x: tx, y: ty),
                       let data = renderTile(imageRenderer, page: page, georef: georef,
                                             z: z, x: tx, y: ty) {
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

        // A tile/metadata write failed mid-bake (e.g. disk full). Don't pass a
        // half-baked file off as a complete basemap.
        guard !writer.hadError else {
            removeMBTilesArtifacts(at: tmpURL)
            return nil
        }
        // Atomic publish.
        do {
            removeMBTilesSidecars(at: tmpURL)
            try FileManager.default.moveItem(at: tmpURL, to: outURL)
            try FileManager.default.setAttributes(
                [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                ofItemAtPath: outURL.path
            )
        } catch {
            removeMBTilesArtifacts(at: tmpURL)
            removeMBTilesArtifacts(at: outURL)
            return nil
        }
        return outURL
    }

    private static func removeMBTilesSidecars(at file: URL) {
        for suffix in ["-wal", "-shm", "-journal"] {
            try? FileManager.default.removeItem(
                at: URL(fileURLWithPath: file.path + suffix, isDirectory: false)
            )
        }
    }

    private static func removeMBTilesArtifacts(at file: URL) {
        try? FileManager.default.removeItem(at: file)
        removeMBTilesSidecars(at: file)
    }

    /// Off-sheet skip gate: does the tile's page-space quad touch the crop?
    private static func touchesCrop(_ georef: PdfGeoreference, z: Int, x: Int, y: Int) -> Bool {
        let corners = [(0.0, 0.0), (256.0, 0.0), (256.0, 256.0), (0.0, 256.0), (128.0, 128.0)]
            .compactMap { PdfTileWarp.pagePoint(georef, z: z, x: x, y: y, px: $0.0, py: $0.1) }
        guard corners.count == 5 else { return false }
        let xs = corners.map(\.x), ys = corners.map(\.y)
        let quad = CGRect(x: xs.min()!, y: ys.min()!, width: xs.max()! - xs.min()!, height: ys.max()! - ys.min()!)
        return quad.width > 0.01 && quad.height > 0.01 && quad.intersects(georef.cropBoundingRect)
    }

    /// page -> tile pixel affine that sends page points p0, p1, p2 to pixels
    /// q0, q1, q2. nil when the page points are collinear.
    static func pageToPixel(_ p0: CGPoint, _ p1: CGPoint, _ p2: CGPoint,
                            _ q0: CGPoint, _ q1: CGPoint, _ q2: CGPoint) -> CGAffineTransform? {
        let ux = p1.x - p0.x, uy = p1.y - p0.y
        let vx = p2.x - p0.x, vy = p2.y - p0.y
        let det = ux * vy - vx * uy
        guard det.isFinite, abs(det) > 1e-12 else { return nil }
        // L [u v] = [q1-q0 q2-q0]  ->  L = Q [u v]^-1
        let qux = q1.x - q0.x, quy = q1.y - q0.y
        let qvx = q2.x - q0.x, qvy = q2.y - q0.y
        let a = (qux * vy - qvx * uy) / det
        let c = (qvx * ux - qux * vx) / det
        let b = (quy * vy - qvy * uy) / det
        let d = (qvy * ux - quy * vx) / det
        let t = CGAffineTransform(a: a, b: b, c: c, d: d,
                                  tx: q0.x - (a * p0.x + c * p0.y),
                                  ty: q0.y - (b * p0.x + d * p0.y))
        return [t.a, t.b, t.c, t.d, t.tx, t.ty].allSatisfy(\.isFinite) ? t : nil
    }

    /// Render one 256x256 tile north-up. Each horizontal strip gets its own
    /// page -> pixel affine through three toPage'd corners, which keeps the
    /// Mercator/projection curvature under a pixel for any sane sheet.
    static func renderTile(_ renderer: UIGraphicsImageRenderer, page: CGPDFPage,
                           georef: PdfGeoreference, z: Int, x: Int, y: Int) -> Data? {
        let size: CGFloat = 256
        let box = WebMercatorTiles.tileBounds(z, x, y)
        // one strip per <=0.25 deg of latitude, capped at 16
        let strips = max(1, min(16, Int(ceil((box.north - box.south) / 0.25))))
        let crop = georef.crop.map(\.cgPoint)

        let image = renderer.image { rctx in
            let ctx = rctx.cgContext
            UIColor.white.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))

            for i in 0..<strips {
                // rounded integer edges so adjacent bands abut with no hairline seam
                let pixelTop = CGFloat((Double(i) * Double(size) / Double(strips)).rounded())
                let pixelBottom = CGFloat((Double(i + 1) * Double(size) / Double(strips)).rounded())
                let bandH = pixelBottom - pixelTop
                guard bandH > 0,
                      let pTL = PdfTileWarp.pagePoint(georef, z: z, x: x, y: y, px: 0, py: Double(pixelTop)),
                      let pTR = PdfTileWarp.pagePoint(georef, z: z, x: x, y: y, px: 256, py: Double(pixelTop)),
                      let pBL = PdfTileWarp.pagePoint(georef, z: z, x: x, y: y, px: 0, py: Double(pixelBottom)),
                      let m = pageToPixel(pTL, pTR, pBL,
                                          CGPoint(x: 0, y: pixelTop), CGPoint(x: size, y: pixelTop),
                                          CGPoint(x: 0, y: pixelBottom)) else { continue }
                ctx.saveGState()
                ctx.clip(to: CGRect(x: 0, y: pixelTop, width: size, height: bandH))
                ctx.concatenate(m)
                // neatline/viewport clip in page space, collar stays white
                if crop.count >= 3 {
                    ctx.addLines(between: crop)
                    ctx.closePath()
                    ctx.clip()
                }
                ctx.drawPDFPage(page)
                ctx.restoreGState()
            }
        }
        return image.pngData()
    }

    /// Min zoom (coverage ~fits one tile) accumulating up to a tile budget so
    /// huge sheets can't generate forever. Resolution-agnostic.
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
