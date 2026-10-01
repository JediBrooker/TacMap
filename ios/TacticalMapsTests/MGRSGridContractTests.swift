import XCTest
import CoreLocation
import UIKit
@testable import TacticalMaps

/// Pins the MGRS grid overlay to testdata/mgrs_grid.json, the same fixture the
/// Android suite reads (expected values from PROJ, see testdata/README.md).
final class MGRSGridContractTests: XCTestCase {

    private typealias R = MGRSGridRenderer

    // MARK: fixture

    private static var cached: [String: Any]?

    private func fixture() throws -> [String: Any] {
        if let c = Self.cached { return c }
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0 ..< 8 {
            let candidate = dir.appendingPathComponent("testdata/mgrs_grid.json")
            if FileManager.default.fileExists(atPath: candidate.path) {
                let root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: candidate)) as? [String: Any])
                Self.cached = root
                return root
            }
            dir = dir.deletingLastPathComponent()
        }
        XCTFail("Could not locate testdata/mgrs_grid.json")
        throw CocoaError(.fileNoSuchFile)
    }

    private func cases(_ section: String) throws -> [[String: Any]] {
        try XCTUnwrap(try fixture()[section] as? [[String: Any]], section)
    }

    private func dbl(_ a: Any?) -> Double { (a as? NSNumber)?.doubleValue ?? .nan }
    private func int(_ a: Any?) -> Int { (a as? NSNumber)?.intValue ?? Int.min }

    private func level(_ name: Any?) -> R.Level? {
        R.Level.allCases.first { $0.name == name as? String }
    }

    private func levels(_ a: Any?) -> [R.Level] {
        ((a as? [String]) ?? []).compactMap { level($0) }
    }

    private func hemisphere(_ a: Any?) -> R.Hemisphere { (a as? String) == "S" ? .south : .north }
    private func axis(_ a: Any?) -> R.Axis { (a as? String) == "easting" ? .easting : .northing }

    private func box(_ a: Any?) -> R.GeoBox {
        let b = a as? [String: Any] ?? [:]
        return R.GeoBox(south: dbl(b["south"]), west: dbl(b["west"]), north: dbl(b["north"]), east: dbl(b["east"]))
    }

    private func key(_ d: [String: Any]) -> R.LineKey {
        R.LineKey(zone: int(d["zone"]), hemisphere: hemisphere(d["hemisphere"]),
                  axis: axis(d["axis"]), value: int(d["value"]))
    }

    private func latLon(_ a: Any?) -> (lat: Double, lon: Double) {
        let v = a as? [NSNumber] ?? []
        return (v.first?.doubleValue ?? .nan, v.dropFirst().first?.doubleValue ?? .nan)
    }

    // web mercator metres times cos(lat of the point being measured), like the fixture
    private func mercMetres(_ lat: Double, _ lon: Double) -> (Double, Double) {
        (R.mercX(lon) * 2 * .pi * 6_378_137, R.mercY(lat) * 2 * .pi * 6_378_137)
    }

    private func distanceToPolyline(_ p: (lat: Double, lon: Double), _ line: [(lat: Double, lon: Double)]) -> Double {
        let (px, py) = mercMetres(p.lat, p.lon)
        var best = Double.infinity
        let pts = line.map { mercMetres($0.lat, $0.lon) }
        if pts.count == 1 { return hypot(px - pts[0].0, py - pts[0].1) * cos(p.lat * .pi / 180) }
        for i in 0 ..< max(pts.count - 1, 0) {
            let (ax, ay) = pts[i], (bx, by) = pts[i + 1]
            let dx = bx - ax, dy = by - ay
            let l2 = dx * dx + dy * dy
            let t = l2 == 0 ? 0 : max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / l2))
            best = min(best, hypot(px - (ax + t * dx), py - (ay + t * dy)))
        }
        return best * cos(p.lat * .pi / 180)
    }

    private func coords(_ piece: R.Piece) -> [(lat: Double, lon: Double)] {
        piece.points.map { ($0.lat, $0.lon) }
    }

    // MARK: policy block

    func testPolicyConstantsMatchFixture() throws {
        let p = try XCTUnwrap(try fixture()["policy"] as? [String: Any])
        XCTAssertEqual(dbl(p["lineMinDp"]), R.lineMinDp)
        XCTAssertEqual(dbl(p["labelMinDp"]), R.labelMinDp)
        XCTAssertEqual(dbl(p["labelInsetDp"]), R.labelInsetDp)
        XCTAssertEqual(dbl(p["squareLabelMinDp"]), R.squareLabelMinDp)
        XCTAssertEqual(dbl(p["squareLabelOffsetDp"]), R.squareLabelOffsetDp)
        XCTAssertEqual(dbl(p["maxSagittaPx"]), R.maxSagittaPx)
        XCTAssertEqual(dbl(p["tileSizeDp"]), R.tileSizeDp)
        let levelsJSON = try XCTUnwrap(p["levels"] as? [[String: Any]])
        XCTAssertEqual(levelsJSON.map { $0["name"] as? String }, R.Level.allCases.map { $0.name })
        XCTAssertEqual(levelsJSON.map { int($0["metres"]) }, R.Level.allCases.map { $0.metres })
        let cache = try XCTUnwrap(p["cache"] as? [String: Any])
        XCTAssertEqual(dbl(cache["rebuildZoomDelta"]), R.rebuildZoomDelta)
        XCTAssertEqual(dbl(cache["rebuildCentreMoveDp"]), R.rebuildCentreMoveDp)
        XCTAssertEqual(dbl(cache["coverageMarginDp"]), R.coverageMarginDp)
        let style = try XCTUnwrap(p["style"] as? [String: Any])
        let widths = try XCTUnwrap(style["lineWidthDp"] as? [String: Any])
        let sizes = try XCTUnwrap(style["labelTextSize"] as? [String: Any])
        for l in R.Level.allCases {
            XCTAssertEqual(Double(R.lineWidth(for: l)), dbl(widths[l.name]), accuracy: 1e-9)
            XCTAssertEqual(Double(R.labelFontSize(for: l)), dbl(sizes[l.name]), accuracy: 1e-9)
        }
        // every cell span, exceptions included
        let exceptions = try XCTUnwrap(p["zoneSpanExceptions"] as? [String: Any])
        for (gzd, span) in exceptions {
            let zone = Int(gzd.dropLast())!, band = gzd.last!
            if let s = span as? [NSNumber] {
                let c = try XCTUnwrap(R.cell(zone: zone, band: band), gzd)
                XCTAssertEqual(c.lonW, s[0].doubleValue, gzd)
                XCTAssertEqual(c.lonE, s[1].doubleValue, gzd)
            } else {
                XCTAssertNil(R.cell(zone: zone, band: band), gzd)
            }
        }
    }

    // MARK: lod

    func testLevelOfDetail() throws {
        for c in try cases("lod") {
            let name = c["name"] as? String ?? "?"
            let lod = R.lod(zoom: dbl(c["zoom"]), latitude: dbl(c["lat"]))
            let spacing = try XCTUnwrap(c["spacingDp"] as? [String: Any])
            for l in R.Level.allCases {
                XCTAssertEqual(lod.spacing(l), dbl(spacing[l.name]), accuracy: 1e-3, "\(name) \(l.name)")
            }
            XCTAssertEqual(lod.drawn, levels(c["drawn"]), name)
            XCTAssertEqual(lod.labelled, levels(c["labelled"]), name)
        }
    }

    // MARK: label text

    func testLineLabelTextIsTheLinesOwnValue() throws {
        for c in try cases("lineLabels") {
            let note = c["note"] as? String ?? "?"
            let finest = level(c["finestLabelledLevel"])
            let L: R.Level? = finest == .km100 ? nil : finest
            XCTAssertEqual(R.lineLabelText(value: int(c["value"]), lineLabelLevel: L), c["text"] as? String, note)
        }
    }

    func testSquareLetters() throws {
        for c in try cases("squareLabels") {
            XCTAssertEqual(R.squareLetters(zone: int(c["zone"]), easting: int(c["easting"]), northing: int(c["northing"])),
                           c["text"] as? String, c["note"] as? String ?? "?")
        }
    }

    // MARK: TM accuracy against the PROJ samples

    func testKrugerUTMMatchesProjWithin5mm() throws {
        for c in try cases("geometry") {
            let line = try XCTUnwrap(c["line"] as? [String: Any])
            let k = key(line)
            for piece in (c["pieces"] as? [[String: Any]] ?? []) {
                for s in (piece["samples"] as? [[NSNumber]] ?? []) {
                    let p = KrugerUTM.forward(latitude: s[0].doubleValue, longitude: s[1].doubleValue,
                                              zone: k.zone, south: k.hemisphere == .south)
                    let got = k.axis == .easting ? p.easting : p.northing
                    XCTAssertEqual(got, Double(k.value), accuracy: 0.005, c["name"] as? String ?? "?")
                    let back = KrugerUTM.inverse(easting: p.easting, northing: p.northing,
                                                 zone: k.zone, south: k.hemisphere == .south)
                    XCTAssertEqual(back.latitude, s[0].doubleValue, accuracy: 1e-10)
                    XCTAssertEqual(back.longitude, s[1].doubleValue, accuracy: 1e-10)
                }
            }
        }
    }

    // MARK: geometry (densify)

    func testGeometryDensifiedWithinTolerance() throws {
        for c in try cases("geometry") {
            let name = c["name"] as? String ?? "?"
            let line = try XCTUnwrap(c["line"] as? [String: Any])
            let k = key(line)
            let lod = R.lod(zoom: dbl(c["zoom"]), latitude: dbl(c["lodLat"]))
            let grid = R.build(box: box(c["bounds"]), lod: lod, densifyZoom: dbl(c["zoom"]), pxPerDp: dbl(c["pxPerDp"]))
            let mine = grid.pieces.filter { $0.key == k }
            XCTAssertFalse(mine.isEmpty, name)
            for p in mine { XCTAssertEqual(p.level, level(line["level"]), name) }
            let tol = dbl(c["tolMetres"])
            for piece in (c["pieces"] as? [[String: Any]] ?? []) {
                let band = Character(piece["band"] as? String ?? "?")
                let samples: [(lat: Double, lon: Double)] = (piece["samples"] as? [[NSNumber]] ?? [])
                    .map { (lat: $0[0].doubleValue, lon: $0[1].doubleValue) }
                let inBand = mine.filter { $0.cell.band == band }
                XCTAssertFalse(inBand.isEmpty, "\(name) band \(band)")
                // every PROJ sample sits on what we draw
                var worst = 0.0
                for s in samples {
                    let d = inBand.map { distanceToPolyline(s, coords($0)) }.min() ?? .infinity
                    worst = max(worst, d)
                }
                XCTAssertLessThanOrEqual(worst, tol, "\(name) band \(band): sample off by \(worst) m")
                // and every vertex we emit inside the sampled extent sits on the curve
                let lats = samples.map { $0.lat }, lons = samples.map { $0.lon }
                let latR = (lats.min()! - 1e-9) ... (lats.max()! + 1e-9)
                let lonR = (lons.min()! - 1e-9) ... (lons.max()! + 1e-9)
                var worstV = 0.0
                for p in inBand {
                    for v in p.points where latR.contains(v.lat) && lonR.contains(v.lon) {
                        worstV = max(worstV, distanceToPolyline((v.lat, v.lon), samples))
                    }
                }
                XCTAssertLessThanOrEqual(worstV, tol, "\(name) band \(band): vertex off by \(worstV) m")
            }
        }
    }

    // MARK: clip

    func testClippingToZoneAndBand() throws {
        let policy = try XCTUnwrap(try fixture()["policy"] as? [String: Any])
        let eps = dbl(policy["clipEpsilonDegrees"])
        XCTAssertEqual(eps, 1e-9)
        for c in try cases("clip") {
            let name = c["name"] as? String ?? "?"
            let b = box(c["bounds"])
            let lod = R.lod(zoom: dbl(c["zoom"]), latitude: dbl(c["lodLat"]))
            XCTAssertEqual(lod.drawn, levels(c["drawn"]), name)
            let grid = R.build(box: b, lod: lod, densifyZoom: dbl(c["zoom"]), pxPerDp: dbl(c["pxPerDp"]))
            XCTAssertFalse(grid.pieces.isEmpty, name)

            // fixture cells match ours
            for cj in (c["cells"] as? [[String: Any]] ?? []) {
                let gzd = cj["gzd"] as? String ?? "?"
                let cell = try XCTUnwrap(R.cell(zone: int(cj["zone"]), band: Character(cj["band"] as? String ?? "?")), gzd)
                XCTAssertEqual([cell.lonW, cell.lonE], (cj["lon"] as? [NSNumber])?.map(\.doubleValue), gzd)
                XCTAssertEqual([cell.latS, cell.latN], (cj["lat"] as? [NSNumber])?.map(\.doubleValue), gzd)
                XCTAssertEqual(cell.hemisphere, hemisphere(cj["hemisphere"]), gzd)
            }

            // every point inside its own cell (no neighbour-zone intrusion, no diagonals)
            for p in grid.pieces {
                for v in p.points {
                    XCTAssertTrue(v.lon >= p.cell.lonW - eps && v.lon <= p.cell.lonE + eps &&
                                  v.lat >= p.cell.latS - eps && v.lat <= p.cell.latN + eps,
                                  "\(name): \(p.key) in \(p.cell.gzd) has (\(v.lat), \(v.lon))")
                }
            }

            // no overlapping pieces per line (band-edge duplicates)
            var byKey: [R.LineKey: [(Double, Double)]] = [:]
            for p in grid.pieces {
                let span = p.key.axis == .easting ? p.points.map(\.lat) : p.points.map(\.lon)
                byKey[p.key, default: []].append((span.min()!, span.max()!))
            }
            for (k, spans) in byKey {
                let sorted = spans.sorted { $0.0 < $1.0 }
                for i in 1 ..< max(sorted.count, 1) {
                    XCTAssertLessThanOrEqual(sorted[i - 1].1 - sorted[i].0, 1e-9, "\(name): \(k) pieces overlap")
                }
            }

            // expected pieces are there, with the right owner level, reaching both ends
            let expected = c["expectedPieces"] as? [[String: Any]] ?? []
            XCTAssertFalse(expected.isEmpty, name)
            for e in expected {
                let k = key(e)
                let band = Character(e["band"] as? String ?? "?")
                let cands = grid.pieces.filter { $0.key == k && $0.cell.band == band }
                let label = "\(name): \(k) \(band)"
                XCTAssertFalse(cands.isEmpty, "\(label) missing")
                for p in cands { XCTAssertEqual(p.level, level(e["level"]), label) }
                let from = latLon(e["from"]), to = latLon(e["to"])
                let dFrom = cands.map { distanceToPolyline(from, coords($0)) }.min() ?? .infinity
                let dTo = cands.map { distanceToPolyline(to, coords($0)) }.min() ?? .infinity
                XCTAssertLessThanOrEqual(dFrom, dbl(e["fromTolMetres"]), "\(label) from off by \(dFrom) m")
                XCTAssertLessThanOrEqual(dTo, dbl(e["toTolMetres"]), "\(label) to off by \(dTo) m")
            }

            // nothing inside the bounds that the fixture doesn't expect
            struct PieceID: Hashable { let key: R.LineKey; let band: Character }
            let expectedIDs = Set(expected.map { PieceID(key: key($0), band: Character($0["band"] as? String ?? "?")) })
            for p in grid.pieces {
                let inside = p.points.contains {
                    $0.lat > b.south + 1e-7 && $0.lat < b.north - 1e-7 && $0.lon > b.west + 1e-7 && $0.lon < b.east - 1e-7
                }
                guard inside else { continue }
                XCTAssertTrue(expectedIDs.contains(PieceID(key: p.key, band: p.cell.band)),
                              "\(name): unexpected piece \(p.key) in \(p.cell.gzd)")
            }

            for a in (c["absentKeys"] as? [[String: Any]] ?? []) {
                XCTAssertFalse(grid.pieces.contains { $0.key == key(a) }, "\(name): \(key(a)) should never be drawn")
            }
            for kb in (c["keyBounds"] as? [[String: Any]] ?? []) {
                let latMax = dbl(kb["latMax"])
                for p in grid.pieces where p.key == key(kb) {
                    XCTAssertLessThanOrEqual(p.points.map(\.lat).max()!, latMax + eps, "\(name): \(kb["why"] ?? "")")
                }
            }
        }
    }

    // MARK: visible labels

    func testVisibleLabelsLandOnScreen() throws {
        for c in try cases("visibleLabels") {
            let name = c["name"] as? String ?? "?"
            let cam = try XCTUnwrap(c["camera"] as? [String: Any])
            let camera = MapCamera(center: CLLocationCoordinate2D(latitude: dbl(cam["lat"]), longitude: dbl(cam["lon"])),
                                   zoom: dbl(cam["zoom"]), headingDegrees: dbl(cam["headingDegrees"]),
                                   viewportSize: CGSize(width: dbl(cam["widthDp"]), height: dbl(cam["heightDp"])))
            let request = R.BuildRequest(camera: camera, pxPerDp: 3)
            XCTAssertEqual(request.lod.drawn, levels(c["drawn"]), name)
            XCTAssertEqual(request.lod.labelled, levels(c["labelled"]), name)
            let grid = R.build(request)
            let placed = R.layoutLabels(grid, camera: camera) { R.labelTextSize($0, level: $1) }
            let inset = (c["insetRectDp"] as? [NSNumber])?.map(\.doubleValue) ?? []
            let expected = c["labels"] as? [[String: Any]] ?? []

            func matches(_ l: R.PlacedLabel, _ e: [String: Any]) -> Bool {
                guard l.text == e["text"] as? String else { return false }
                switch l.kind {
                case .line(let k):
                    return e["kind"] as? String == "line" && k == key(e)
                case let .square(zone, band, easting, northing):
                    return e["kind"] as? String == "square" && zone == int(e["zone"]) &&
                        String(band) == e["band"] as? String && easting == int(e["easting"]) &&
                        northing == int(e["northing"])
                }
            }

            for l in placed {
                guard let e = expected.first(where: { matches(l, $0) }) else {
                    XCTFail("\(name): unexpected label \(l.kind) '\(l.text)' at \(l.anchor)")
                    continue
                }
                let a = latLon(e["anchor"])   // screen x, y here
                let tol = dbl(e["anchorTolDp"])
                XCTAssertEqual(Double(l.anchor.x), a.lat, accuracy: tol, "\(name): \(l.kind) x")
                XCTAssertEqual(Double(l.anchor.y), a.lon, accuracy: tol, "\(name): \(l.kind) y")
                XCTAssertTrue(Double(l.anchor.x) >= inset[0] - 1e-6 && Double(l.anchor.x) <= inset[2] + 1e-6 &&
                              Double(l.anchor.y) >= inset[1] - 1e-6 && Double(l.anchor.y) <= inset[3] + 1e-6,
                              "\(name): \(l.kind) anchor \(l.anchor) off the inset viewport")
                if case .line = l.kind { XCTAssertEqual(l.level, level(e["level"]), "\(name): \(l.kind) level") }
            }
            for e in expected where (e["mayDrop"] as? Bool) == false {
                XCTAssertTrue(placed.contains { matches($0, e) },
                              "\(name): missing \(e["kind"] ?? "") \(e["text"] ?? "") \(e["value"] ?? e["easting"] ?? "")")
            }
        }
    }
}
