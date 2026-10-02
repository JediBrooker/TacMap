import XCTest
import CoreLocation
import UIKit
@testable import TacticalMaps

/// The audit proving tests for the grid overlay (D4-04, D4-06, D4-11, D4-12,
/// D4-13 + verifier notes) that aren't a straight fixture replay.
final class MGRSGridBehaviourTests: XCTestCase {

    private typealias R = MGRSGridRenderer

    private func camera(_ lat: Double, _ lon: Double, zoom: Double, heading: Double = 0,
                        size: CGSize = CGSize(width: 393, height: 852)) -> MapCamera {
        MapCamera(center: CLLocationCoordinate2D(latitude: lat, longitude: lon), zoom: zoom,
                  headingDegrees: heading, viewportSize: size)
    }

    // D4-12 + verifier: nothing from zone 33 west of 12E, nothing from 32 on/over it,
    // so no first-column diagonals back across the boundary
    func testZoneBoundaryIsClippedWithNoFirstColumnDiagonals() {
        let box = R.GeoBox(south: 47.95, west: 11.9, north: 48.1, east: 12.1)
        let grid = R.build(box: box, lod: R.lod(zoom: 13, latitude: 48), densifyZoom: 13, pxPerDp: 3)
        XCTAssertTrue(grid.pieces.contains { $0.key.zone == 33 })
        XCTAssertTrue(grid.pieces.contains { $0.key.zone == 32 })
        for p in grid.pieces {
            for v in p.points {
                if p.key.zone == 33 { XCTAssertGreaterThanOrEqual(v.lon, 12.0, "\(p.key)") }
                if p.key.zone == 32 { XCTAssertLessThanOrEqual(v.lon, 12.0, "\(p.key)") }
            }
            if p.key.zone == 32 {
                // touching 12E at an end is fine, running along it is not
                XCTAssertFalse(p.points.allSatisfy { $0.lon == 12.0 }, "\(p.key) runs along the zone edge")
            }
            if p.key.axis == .easting {
                // an easting piece only climbs, a diagonal back to the edge would show up as a big lon jump
                let lons = p.points.map(\.lon)
                XCTAssertLessThan(lons.max()! - lons.min()!, 0.02, "\(p.key) wanders like a diagonal")
            }
        }
    }

    // verifier: NGA emitted band-edge rows twice, stroked twice at 0.85 alpha
    func testBandEdgeSegmentsAreNotDuplicated() {
        let box = R.GeoBox(south: 47.95, west: 8.5, north: 48.1, east: 8.7)
        let grid = R.build(box: box, lod: R.lod(zoom: 13, latitude: 48), densifyZoom: 13, pxPerDp: 3)
        var seen = Set<String>()
        for p in grid.pieces {
            for i in 1 ..< p.points.count {
                let a = p.points[i - 1], b = p.points[i]
                let s = [String(format: "%.12f,%.12f", a.lat, a.lon), String(format: "%.12f,%.12f", b.lat, b.lon)]
                    .sorted().joined(separator: "|")
                XCTAssertFalse(seen.contains(s), "duplicate segment \(s) in \(p.key)")
                seen.insert(s)
            }
        }
    }

    // D4-04 + D4-06: every line label reads its own line and sits on it, on screen
    func testLineLabelsSitOnTheirOwnLine() {
        for heading in [0.0, 30.0, 200.0] {
            let cam = camera(37.8, -122.45, zoom: 13, heading: heading)
            let grid = R.build(R.BuildRequest(camera: cam, pxPerDp: 3))
            let labels = R.layoutLabels(grid, camera: cam) { R.labelTextSize($0, level: $1) }
            var eastings = 0, northings = 0
            for l in labels {
                XCTAssertTrue(l.anchor.x >= 16 - 1e-6 && l.anchor.x <= 377 + 1e-6 &&
                              l.anchor.y >= 16 - 1e-6 && l.anchor.y <= 836 + 1e-6,
                              "heading \(heading): \(l.text) at \(l.anchor) is off screen")
                guard case .line(let k) = l.kind else { continue }
                if k.axis == .easting { eastings += 1 } else { northings += 1 }
                XCTAssertEqual(l.text, String(format: "%02d", (k.value % 100_000) / 1_000))
                let c = cam.coordinate(for: l.anchor)
                let utm = KrugerUTM.forward(latitude: c.latitude, longitude: c.longitude,
                                            zone: k.zone, south: k.hemisphere == .south)
                let onLine = k.axis == .easting ? utm.easting : utm.northing
                // 0.1 dp of chord sagitta at ~15 m/dp, plus rounding
                XCTAssertEqual(onLine, Double(k.value), accuracy: 2.0, "heading \(heading): \(k)")
            }
            // old code got 1 of 7 eastings and 1 of 15 northings on screen here
            XCTAssertGreaterThanOrEqual(eastings, 5, "heading \(heading)")
            XCTAssertGreaterThanOrEqual(northings, 10, "heading \(heading)")
        }
    }

    // D4-13: LOD hides dense levels when zoomed out, and the build stays bounded
    func testZoomedOutDrawsNothingAndBuildSizeIsBounded() {
        XCTAssertTrue(R.build(R.BuildRequest(camera: camera(38, -100, zoom: 4), pxPerDp: 3)).isEmpty,
                      "iOS start zoom must not draw a 13 pt 100 km mesh")
        XCTAssertTrue(R.build(R.BuildRequest(camera: camera(38, -100, zoom: 2), pxPerDp: 3)).isEmpty)

        var worstVertices = 0, worstPieces = 0, worstSquares = 0, worstRing = 0
        for size in [CGSize(width: 393, height: 852), CGSize(width: 1366, height: 1024)] {
            for lat in [0.0, 38.0, 60.0, -33.9, 78.0, 83.5] {
                var z = 0.0
                while z <= 22 {
                    let cam = camera(lat, lat > 70 ? 15 : 9, zoom: z, size: size)
                    let request = R.BuildRequest(camera: cam, pxPerDp: 3)
                    let grid = R.build(request)
                    for l in request.lod.drawn {
                        XCTAssertGreaterThanOrEqual(request.lod.spacing(l), R.lineMinDp)
                    }
                    for p in grid.pieces { XCTAssertTrue(request.lod.drawn.contains(p.level)) }
                    worstRing = max(worstRing, grid.squares.reduce(0) { $0 + $1.ring.count })
                    worstVertices = max(worstVertices, grid.vertexCount)
                    worstPieces = max(worstPieces, grid.pieces.count)
                    worstSquares = max(worstSquares, grid.squares.count)
                    z += 0.5
                }
            }
        }
        // old code: 11k segments at z4, 148k at z2, everywhere. Now the worst is an
        // iPad zoomed right out over the pole (~76k vertices, 17k pieces), where
        // the coverage square is most of the hemisphere; mid latitudes are a few k
        XCTAssertLessThan(worstVertices, 100_000)
        XCTAssertLessThan(worstPieces, 25_000)
        XCTAssertLessThan(worstSquares, 15_000)
        XCTAssertLessThan(worstRing, 60_000)

        // a phone at mid latitude stays small at every zoom
        for z in stride(from: 0.0, through: 22.0, by: 0.25) {
            let grid = R.build(R.BuildRequest(camera: camera(38, -100, zoom: z), pxPerDp: 3))
            XCTAssertLessThan(grid.vertexCount, 6_000, "z\(z)")
            XCTAssertLessThan(grid.pieces.count, 1_000, "z\(z)")
        }
    }

    // D4-11: a pan or zoom that doesn't trigger a rebuild never exposes an area
    // with no geometry, at any heading
    func testCoverageHoldsUntilTheNextRebuild() {
        var rng = SystemRandomNumberGenerator()
        let builds = [camera(37.8126, -122.45, zoom: 18), camera(48, 12, zoom: 12.5, size: CGSize(width: 411, height: 914)),
                      camera(-33.86, 151.21, zoom: 9), camera(70, 20, zoom: 14, size: CGSize(width: 1366, height: 1024)),
                      camera(0, 3, zoom: 22)]
        for built in builds {
            let request = R.BuildRequest(camera: built, pxPerDp: 3)
            let cov = request.coverage
            var checked = 0
            for _ in 0 ..< 400 {
                var cam = built
                cam.zoom = built.zoom + Double.random(in: -0.49 ... 0.49, using: &rng)
                cam.headingDegrees = Double.random(in: 0 ..< 360, using: &rng)
                let move = Double.random(in: 0 ..< 63.9, using: &rng)
                let dir = Double.random(in: 0 ..< 2 * .pi, using: &rng)
                let size = R.tileSizeDp * pow(2, cam.zoom)
                let cx = R.mercX(built.center.longitude) + move * cos(dir) / size
                let cy = R.mercY(built.center.latitude) + move * sin(dir) / size
                cam.center = CLLocationCoordinate2D(latitude: R.latitude(mercY: cy), longitude: cx * 360 - 180)
                guard !R.needsRebuild(request, camera: cam, pxPerDp: 3) else { continue }
                checked += 1
                let w = cam.viewportSize.width, h = cam.viewportSize.height
                for i in 0 ... 8 {
                    let f = CGFloat(i) / 8
                    for p in [CGPoint(x: f * w, y: 0), CGPoint(x: f * w, y: h), CGPoint(x: 0, y: f * h), CGPoint(x: w, y: f * h)] {
                        let c = cam.coordinate(for: p)
                        XCTAssertTrue(c.latitude >= cov.south && c.latitude <= cov.north &&
                                      c.longitude >= cov.west && c.longitude <= cov.east,
                                      "viewport point \(c) escapes coverage \(cov) at z\(cam.zoom)")
                    }
                }
            }
            XCTAssertGreaterThan(checked, 100)
        }

        // the auditor's case: z18, pan ~89 m north used to stay inside one %.3f bucket
        let start = camera(37.8126, -122.45, zoom: 18)
        let req = R.BuildRequest(camera: start, pxPerDp: 3)
        var moved = start
        moved.center.latitude += 0.0008
        XCTAssertTrue(R.needsRebuild(req, camera: moved, pxPerDp: 3))
        // and the rules themselves
        var zoomed = start
        zoomed.zoom += 0.5
        XCTAssertTrue(R.needsRebuild(req, camera: zoomed, pxPerDp: 3))
        var turned = start
        turned.headingDegrees = 90
        XCTAssertFalse(R.needsRebuild(req, camera: turned, pxPerDp: 3), "compass turns only reproject")
        var resized = start
        resized.viewportSize = CGSize(width: 852, height: 393)
        XCTAssertTrue(R.needsRebuild(req, camera: resized, pxPerDp: 3))
        XCTAssertTrue(R.needsRebuild(nil, camera: start, pxPerDp: 3))
    }

    // densify target is buildZoom + 0.5, so the most zoomed-in camera a build
    // serves still sees <= 0.25 px of sagitta
    func testBuildDensifiesForHalfAZoomPastTheCamera() {
        let req = R.BuildRequest(camera: camera(60, 9.4, zoom: 16), pxPerDp: 3)
        XCTAssertEqual(req.densifyZoom, 16.5)
        XCTAssertEqual(R.coverageHalfSideDp(viewportSize: CGSize(width: 393, height: 852)),
                       (hypot(393, 852) / 2 + 64) * pow(2, 0.5) + 32, accuracy: 1e-9)
    }

    // off-main build lands in the overlay; turning the grid off clears it
    func testCoordinatorBuildsGridInBackgroundAndInstallsIt() {
        let cam = camera(37.8, -122.45, zoom: 13)
        let view = TileMapView(camera: cam)
        view.frame = CGRect(origin: .zero, size: cam.viewportSize)
        let coordinator = TileMapContainer.Coordinator()
        coordinator.attach(view: view, mapVM: MapViewModel())
        let gridView = view.subviews.compactMap { $0 as? MGRSGridOverlayView }.first
        XCTAssertNotNil(gridView)

        coordinator.updateOverlays(drawings: [], gridVisible: true,
                                   decorations: DrawingDecorationsOverlayView.Model(), handles: [],
                                   graphicsLocked: false)
        let landed = expectation(description: "grid installed")
        func poll(_ n: Int) {
            if let g = gridView?.grid, !g.pieces.isEmpty { landed.fulfill(); return }
            guard n > 0 else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { poll(n - 1) }
        }
        poll(100)
        wait(for: [landed], timeout: 10)
        XCTAssertEqual(gridView?.grid.lod.drawn, [.km100, .km10, .km1])

        coordinator.updateOverlays(drawings: [], gridVisible: false,
                                   decorations: DrawingDecorationsOverlayView.Model(), handles: [],
                                   graphicsLocked: false)
        XCTAssertTrue(gridView?.grid.isEmpty ?? false)
    }

    // the view actually strokes something through the camera
    func testOverlayDrawsGridIntoContext() {
        let cam = camera(48, 12, zoom: 12.5, size: CGSize(width: 200, height: 300))
        let view = MGRSGridOverlayView()
        view.frame = CGRect(origin: .zero, size: cam.viewportSize)
        view.camera = { cam }
        view.update(grid: R.build(R.BuildRequest(camera: cam, pxPerDp: 1)))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: cam.viewportSize, format: format).image { _ in
            view.draw(view.bounds)
        }
        guard let cg = image.cgImage, let data = cg.dataProvider?.data,
              let bytes = CFDataGetBytePtr(data) else { return XCTFail("no bitmap") }
        var inked = 0
        let bpp = cg.bitsPerPixel / 8
        for i in stride(from: 0, to: CFDataGetLength(data), by: bpp) where (0 ..< bpp).contains(where: { bytes[i + $0] > 0 }) {
            inked += 1
        }
        XCTAssertGreaterThan(inked, 500)
    }
}
