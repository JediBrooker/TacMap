import XCTest
import UIKit
import CoreLocation
@testable import TacticalMaps

/// The CALayer tile view: contentsRect orientation, seams at a heading,
/// parent fallback across a zoom change, plus the view's own contract
/// (pinch overzoom, sync completions, hidden, source swap, underzoom).
final class TileLayerCompositorTests: XCTestCase {

    static func solid(_ color: UIColor, size: Int = 64) -> CGImage {
        let f = UIGraphicsImageRendererFormat.default()
        f.scale = 1
        f.opaque = true
        return UIGraphicsImageRenderer(size: CGSize(width: size, height: size), format: f).image { ctx in
            color.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))
        }.cgImage!
    }

    /// TL red, TR green, BL blue, BR yellow
    static func quadrants(size: Int = 64) -> CGImage {
        let f = UIGraphicsImageRendererFormat.default()
        f.scale = 1
        f.opaque = true
        let h = CGFloat(size) / 2
        return UIGraphicsImageRenderer(size: CGSize(width: size, height: size), format: f).image { ctx in
            UIColor.red.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: h, height: h))
            UIColor.green.setFill(); ctx.fill(CGRect(x: h, y: 0, width: h, height: h))
            UIColor.blue.setFill(); ctx.fill(CGRect(x: 0, y: h, width: h, height: h))
            UIColor.yellow.setFill(); ctx.fill(CGRect(x: h, y: h, width: h, height: h))
        }.cgImage!
    }

    /// render a layer tree over a magenta background, top row first RGBA
    func snapshot(_ layer: CALayer, size: CGSize) -> (data: [UInt8], w: Int, h: Int) {
        let w = Int(size.width), h = Int(size.height)
        let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.setFillColor(UIColor.magenta.cgColor)
        ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        // CALayer.render draws y down, flip the bitmap to match
        ctx.translateBy(x: 0, y: CGFloat(h))
        ctx.scaleBy(x: 1, y: -1)
        ctx.setShouldAntialias(false)
        ctx.setAllowsAntialiasing(false)
        layer.render(in: ctx)
        let p = ctx.data!.assumingMemoryBound(to: UInt8.self)
        return (Array(UnsafeBufferPointer(start: p, count: w * h * 4)), w, h)
    }

    func pixel(_ s: (data: [UInt8], w: Int, h: Int), _ x: Int, _ y: Int) -> (Int, Int, Int) {
        let i = (y * s.w + x) * 4
        return (Int(s.data[i]), Int(s.data[i + 1]), Int(s.data[i + 2]))
    }

    func isMagenta(_ p: (Int, Int, Int)) -> Bool { p.0 > 200 && p.1 < 60 && p.2 > 200 }

    func camera(_ lat: Double = 37.77, _ lon: Double = -122.44, zoom: Double, heading: Double = 0,
                size: CGSize = CGSize(width: 300, height: 300)) -> MapCamera {
        MapCamera(center: CLLocationCoordinate2D(latitude: lat, longitude: lon), zoom: zoom,
                  headingDegrees: heading, viewportSize: size)
    }

    // MARK: compositor

    func testAncestorContentsRectPicksTheRightQuadrantYDown() {
        let comp = TileLayerCompositor()
        comp.layoutRoot(bounds: CGRect(x: 0, y: 0, width: 300, height: 300), headingDegrees: 0)
        let cam = camera(zoom: 13)
        let grid = TileGrid(camera: cam, tileZoom: 13)
        let parent = TileIndex(z: 12, x: 655, y: 1583)
        // every child of the parent, each should show its own quadrant
        let want: [(TileIndex, (Int, Int, Int))] = [
            (TileIndex(z: 13, x: 1310, y: 3166), (255, 0, 0)),
            (TileIndex(z: 13, x: 1311, y: 3166), (0, 255, 0)),
            (TileIndex(z: 13, x: 1310, y: 3167), (0, 0, 255)),
            (TileIndex(z: 13, x: 1311, y: 3167), (255, 255, 0)),
        ]
        let img = Self.quadrants()
        for (child, rgb) in want {
            let dest = VisibleTile(index: child, col: child.x, row: child.y)
            comp.apply(items: [TileDrawItem(kind: .ancestor, source: parent, dest: dest,
                                            unitRect: child.unitRect(inAncestor: parent),
                                            destRect: CGRect(x: 0, y: 0, width: 1, height: 1), order: 0)],
                       grid: grid) { _ in img }
            let f = grid.frame(dest)
            let snap = snapshot(comp.tileRoot, size: CGSize(width: 300, height: 300))
            let cx = Int(f.midX), cy = Int(f.midY)
            guard cx >= 0, cy >= 0, cx < 300, cy < 300 else { continue }
            let p = pixel(snap, cx, cy)
            XCTAssertEqual(p.0 > 128, rgb.0 > 128, "\(child) red")
            XCTAssertEqual(p.1 > 128, rgb.1 > 128, "\(child) green")
            XCTAssertEqual(p.2 > 128, rgb.2 > 128, "\(child) blue")
        }
    }

    /// D4-16: a 3x3 block of opaque tiles at 17 degrees and a fractional
    /// origin leaves no background showing through the seams
    func testRotatedGridHasNoSeams() {
        let comp = TileLayerCompositor()
        let size = CGSize(width: 300, height: 300)
        comp.layoutRoot(bounds: CGRect(origin: .zero, size: size), headingDegrees: 17)
        let cam = camera(37.7712345, -122.4387654, zoom: 15.37, heading: 17, size: size)
        let tz = 15
        let grid = TileGrid(camera: cam, tileZoom: tz)
        let img = Self.solid(.darkGray)
        var items: [TileDrawItem] = []
        for dr in -1...1 { for dc in -1...1 {
            let v = VisibleTile(index: TileIndex(z: tz, x: grid.col0 + dc, y: grid.row0 + dr),
                                col: grid.col0 + dc, row: grid.row0 + dr)
            items.append(TileDrawItem(kind: .own, source: v.index, dest: v, unitRect: CGRect(x: 0, y: 0, width: 1, height: 1),
                                      destRect: CGRect(x: 0, y: 0, width: 1, height: 1), order: items.count))
        } }
        comp.apply(items: items, grid: grid) { _ in img }
        let snap = snapshot(comp.tileRoot, size: size)
        // the 3x3 block is ~970 pt across, the 300 pt view is well inside it
        var magenta = 0
        for y in 0..<300 { for x in 0..<300 where isMagenta(pixel(snap, x, y)) { magenta += 1 } }
        XCTAssertEqual(magenta, 0, "background showing through tile seams")
    }

    /// D4-16 proving test: at z18.37 every tile layer sits exactly on its
    /// camera frame, no 0.5 pt inflate and no pixel rounding (the old draw()
    /// grew each tile by half a point, so baked grid lines breathed on pinch)
    func testTileLayersSitOnTheirExactFloatFrames() {
        let src = FakeSource()
        src.maxZoom = 18
        src.serveZooms = [18]
        let view = makeView(camera(37.7712345, -122.4387654, zoom: 18.37))
        view.source = src
        spin()
        let grid = TileGrid(camera: view.camera, tileZoom: 18)
        let items = view.lastPlan.items.filter { $0.kind == .own }
        XCTAssertFalse(items.isEmpty)
        let layers = (view.tileRootLayer.sublayers ?? []).filter { $0.contents != nil }
        XCTAssertEqual(layers.count, items.count)
        var fractional = false
        for item in items {
            let f = grid.frame(item.dest)
            // the camera projection of the tile corner, independent of TileGrid
            let n = pow(2.0, 18.0)
            let lon = Double(item.dest.col) / n * 360 - 180
            let lat = atan(sinh(Double.pi * (1 - 2 * Double(item.dest.row) / n))) * 180 / .pi
            let corner = view.camera.screenPoint(for: CLLocationCoordinate2D(latitude: lat, longitude: lon))
            XCTAssertEqual(Double(f.minX), Double(corner.x), accuracy: 1e-6)
            XCTAssertEqual(Double(f.minY), Double(corner.y), accuracy: 1e-6)
            XCTAssertEqual(Double(f.width), 256 * pow(2, 0.37), accuracy: 1e-9)
            if f.minX != f.minX.rounded() || f.minY != f.minY.rounded() { fractional = true }
            let match = layers.contains { l in
                abs(l.frame.minX - f.minX) < 1e-9 && abs(l.frame.minY - f.minY) < 1e-9
                    && abs(l.frame.width - f.width) < 1e-9 && abs(l.frame.height - f.height) < 1e-9
            }
            XCTAssertTrue(match, "no layer on the exact frame of \(item.dest.index)")
        }
        XCTAssertTrue(fractional, "the case only means something with fractional origins")
        XCTAssertTrue(layers.allSatisfy { !$0.allowsEdgeAntialiasing && $0.magnificationFilter == .linear })
    }

    // MARK: tile view

    final class FakeSource: RasterTileSource {
        var minZoom = 0
        var maxZoom = 16
        var tilePixelSize: Int { 256 }
        var serveZooms: Set<Int> = []
        var synchronous = false
        var content: (TileIndex) -> Bool = { _ in true }
        var requested: [TileIndex] = []
        var cancelled = 0
        var wanted: [TileIndex] = []
        let image = UIImage(cgImage: TileLayerCompositorTests.solid(.darkGray))

        final class Req: RasterTileRequest {
            let onCancel: () -> Void
            init(_ c: @escaping () -> Void) { onCancel = c }
            func cancel() { onCancel() }
        }

        func hasContent(_ tile: TileIndex) -> Bool { content(tile) }
        var wantedCalls = 0
        var lastCentre: CGPoint?
        func wantedTilesDidChange(_ ordered: [TileIndex], tileZoom: Int, centreUnit: CGPoint) {
            wanted = ordered
            wantedCalls += 1
            lastCentre = centreUnit
        }

        func loadTile(_ tile: TileIndex, completion: @escaping (UIImage?) -> Void) -> RasterTileRequest? {
            requested.append(tile)
            guard serveZooms.contains(tile.z) else { return Req { [weak self] in self?.cancelled += 1 } }
            if synchronous { completion(image) } else { DispatchQueue.main.async { completion(self.image) } }
            return Req { [weak self] in self?.cancelled += 1 }
        }
    }

    func spin(_ s: Double = 0.05) { RunLoop.main.run(until: Date().addingTimeInterval(s)) }

    func makeView(_ cam: MapCamera) -> TileMapView {
        let v = TileMapView(camera: cam, cacheBytes: 64 << 20)
        v.frame = CGRect(origin: .zero, size: cam.viewportSize)
        v.layoutIfNeeded()
        return v
    }

    /// D3-11: zooming from 13 to 13.6 with only z13 cached draws parents,
    /// never the background
    func testZoomingInShowsParentsInsteadOfHoles() {
        let src = FakeSource()
        src.serveZooms = [13]
        let view = makeView(camera(zoom: 13))
        view.source = src
        spin()
        XCTAssertGreaterThan(view.cachedTileCount, 0)
        var cam = view.camera
        cam.zoom = 13.6
        view.camera = cam
        XCTAssertFalse(view.lastPlan.items.isEmpty)
        XCTAssertTrue(view.lastPlan.items.allSatisfy { $0.kind == .ancestor && $0.source.z == 13 })
        let snap = snapshot(view.tileRootLayer, size: view.bounds.size)
        var magenta = 0
        for y in stride(from: 0, to: snap.h, by: 2) { for x in stride(from: 0, to: snap.w, by: 2) where isMagenta(pixel(snap, x, y)) { magenta += 1 } }
        XCTAssertEqual(magenta, 0)
    }

    func testZoomingOutShowsChildren() {
        let src = FakeSource()
        src.serveZooms = [14]
        let view = makeView(camera(zoom: 14))
        view.source = src
        spin()
        var cam = view.camera
        cam.zoom = 13.2
        view.camera = cam
        XCTAssertTrue(view.lastPlan.items.contains { $0.kind == .child })
    }

    func testPinchPastTheSourcesMaxZoomOverzooms() {
        let src = FakeSource()
        src.maxZoom = 16
        let view = makeView(camera(zoom: 18))
        view.source = src
        view.applyPinch(scale: 1.01, focal: CGPoint(x: 150, y: 150))
        XCTAssertEqual(view.camera.zoom, 18 + log2(1.01), accuracy: 1e-9)
        XCTAssertEqual(view.lastTileZoom, 16)
        // and the global camera range still holds
        view.applyPinch(scale: 64, focal: CGPoint(x: 150, y: 150))
        XCTAssertEqual(view.camera.zoom, 22)
        view.applyPinch(scale: 1e-9, focal: CGPoint(x: 150, y: 150))
        XCTAssertEqual(view.camera.zoom, 2)
    }

    func testProgrammaticZoomIsClamped() {
        let view = makeView(camera(zoom: 10))
        var cam = view.camera
        cam.zoom = 30
        view.camera = cam
        XCTAssertEqual(view.camera.zoom, 22)
        cam.zoom = 0.5
        view.camera = cam
        XCTAssertEqual(view.camera.zoom, 2)
    }

    func testSynchronousCompletionDoesNotWedgeTheTile() {
        let src = FakeSource()
        src.serveZooms = [12]
        src.synchronous = true
        let view = makeView(camera(zoom: 12))
        view.source = src
        XCTAssertEqual(view.inFlightCount, 0, "sync completions must clear their in-flight slot")
        XCTAssertGreaterThan(view.cachedTileCount, 0)
        spin()
        XCTAssertTrue(view.lastPlan.items.allSatisfy { $0.kind == .own })
    }

    func testHiddenDrawsNothingAndCancelsLoads() {
        let src = FakeSource()
        let view = makeView(camera(zoom: 12))
        view.source = src
        XCTAssertGreaterThan(view.inFlightCount, 0)
        view.tilesHidden = true
        XCTAssertEqual(view.inFlightCount, 0)
        XCTAssertGreaterThan(src.cancelled, 0)
        XCTAssertTrue(view.tileRootLayer.isHidden)
        view.tilesHidden = false
        XCTAssertFalse(view.tileRootLayer.isHidden)
        XCTAssertGreaterThan(view.inFlightCount, 0)
    }

    func testSourceSwapClearsTheCache() {
        let src = FakeSource()
        src.serveZooms = [12]
        let view = makeView(camera(zoom: 12))
        view.source = src
        spin()
        XCTAssertGreaterThan(view.cachedTileCount, 0)
        view.source = FakeSource()
        XCTAssertEqual(view.cachedTileCount, 0)
    }

    func testUnderzoomedSourceRequestsNothing() {
        let src = FakeSource()
        src.minZoom = 10
        let view = makeView(camera(zoom: 5))
        view.source = src
        XCTAssertTrue(src.requested.isEmpty, "minZoom 10 at camera z5 must not ask for anything")
        XCTAssertTrue(view.lastPlan.items.isEmpty)
    }

    func testKnownEmptyTilesAreNeverRequested() {
        let src = FakeSource()
        src.content = { $0.x % 2 == 0 }
        let view = makeView(camera(zoom: 12))
        view.source = src
        XCTAssertFalse(src.requested.isEmpty)
        XCTAssertTrue(src.requested.allSatisfy { $0.x % 2 == 0 })
    }

    func testMemoryWarningTrimsToWhatsOnScreen() {
        let src = FakeSource()
        src.serveZooms = [12, 13]
        let view = makeView(camera(zoom: 12))
        view.source = src
        spin()
        var cam = view.camera
        cam.zoom = 13
        view.camera = cam
        spin()
        let before = view.cachedTileCount
        view.didReceiveMemoryWarning()
        XCTAssertLessThan(view.cachedTileCount, before)
        XCTAssertEqual(view.cachedTileCount, Set(view.lastPlan.items.map(\.source)).count)
    }

    /// E3: the source gets the real viewport centre every layout, and nothing
    /// at all while hidden or underzoomed (loads still get cancelled)
    func testWantedCallbacksCarryTheViewportCentreAndSkipHiddenOrUnderzoomed() {
        let src = FakeSource()
        let view = makeView(camera(zoom: 13, heading: 30))
        view.source = src
        XCTAssertGreaterThan(src.wantedCalls, 0)
        let want = TileMath.viewportCentreUnit(camera: view.camera)
        let w = PDFTileMath.world0(lat: 37.77, lon: -122.44)
        XCTAssertEqual(Double(want.x), w.x / 256, accuracy: 1e-12)
        XCTAssertEqual(Double(src.lastCentre?.x ?? 0), Double(want.x), accuracy: 1e-12)
        XCTAssertEqual(Double(src.lastCentre?.y ?? 0), Double(want.y), accuracy: 1e-12)

        view.tilesHidden = true
        let calls = src.wantedCalls
        var cam = view.camera
        cam.zoom = 13.4
        view.camera = cam
        view.layoutTiles()
        XCTAssertEqual(src.wantedCalls, calls, "hidden: ignored")
        XCTAssertEqual(view.inFlightCount, 0)

        view.tilesHidden = false
        XCTAssertGreaterThan(src.wantedCalls, calls)
        // a source that tops out at z5 seen from z13 is underzoomed
        src.maxZoom = 5
        src.minZoom = 5
        let before = src.wantedCalls
        cam.zoom = 2.5
        view.camera = cam
        view.layoutTiles()
        XCTAssertEqual(src.wantedCalls, before, "underzoomed: ignored")
        XCTAssertEqual(view.inFlightCount, 0)
    }

    func testByteLruEvictsOldestFirst() {
        let img = Self.solid(.red, size: 32)           // 4 KiB
        let cache = TileImageCache(byteLimit: img.bytesPerRow * img.height * 3)
        for i in 0..<5 { cache.insert(.image(img), for: TileIndex(z: 1, x: i, y: 0)) }
        XCTAssertEqual(cache.count, 3)
        XCTAssertNil(cache.peek(TileIndex(z: 1, x: 0, y: 0)))
        XCTAssertNotNil(cache.peek(TileIndex(z: 1, x: 4, y: 0)))
        _ = cache.entry(for: TileIndex(z: 1, x: 2, y: 0))     // touch
        cache.insert(.image(img), for: TileIndex(z: 1, x: 9, y: 0))
        XCTAssertNotNil(cache.peek(TileIndex(z: 1, x: 2, y: 0)))
        XCTAssertNil(cache.peek(TileIndex(z: 1, x: 3, y: 0)))
        cache.insert(.empty, for: TileIndex(z: 2, x: 0, y: 0))
        XCTAssertEqual(TileCacheEntry.empty.cost, 64)
        XCTAssertEqual(TileImageCache.byteLimit(physicalMemory: 4 << 30), 128 << 20)
    }
}
