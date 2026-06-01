import XCTest
import Foundation
import CoreLocation
@testable import TacticalMaps

/// Loads the shared golden vectors in `testdata/` — the SAME files the Android
/// suite reads — and asserts the iOS implementations match them. This is the
/// guard against the two native ports of the affine solve, MGRS formatting, and
/// GeoJSON geometry silently drifting apart. See `testdata/README.md`.
final class SharedVectorsTests: XCTestCase {

    // MARK: fixture loading (walk up from this source file to repo-root testdata/)

    private func fixture(_ name: String) throws -> Any {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("testdata").appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: candidate.path) {
                let data = try Data(contentsOf: candidate)
                return try JSONSerialization.jsonObject(with: data)
            }
            dir = dir.deletingLastPathComponent()
        }
        XCTFail("Could not locate testdata/\(name) from \(#filePath)")
        throw CocoaError(.fileNoSuchFile)
    }

    private func dbl(_ a: Any?) -> Double { (a as? NSNumber)?.doubleValue ?? .nan }

    // MARK: affine solve

    func testSharedAffineVectors() throws {
        let root = try fixture("affine_fits.json") as! [String: Any]
        let cases = root["cases"] as! [[String: Any]]
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let tf = c["transform"] as! [String: Any]
            let fids = (c["fiduciaries"] as! [[String: Any]]).map {
                Fiduciary(pdfX: dbl($0["pdfX"]), pdfY: dbl($0["pdfY"]), mgrs: "",
                          latitude: dbl($0["lat"]), longitude: dbl($0["lon"]))
            }
            let tol = dbl(c["coeffTolerance"])
            let rmsMax = dbl(c["rmsMaxMetres"])
            let r = try AffineFitter.fit(fids)
            XCTAssertEqual(r.transform.a, dbl(tf["a"]), accuracy: tol, name)
            XCTAssertEqual(r.transform.b, dbl(tf["b"]), accuracy: tol, name)
            XCTAssertEqual(r.transform.c, dbl(tf["c"]), accuracy: tol, name)
            XCTAssertEqual(r.transform.d, dbl(tf["d"]), accuracy: tol, name)
            XCTAssertEqual(r.transform.e, dbl(tf["e"]), accuracy: tol, name)
            XCTAssertEqual(r.transform.f, dbl(tf["f"]), accuracy: tol, name)
            XCTAssertLessThan(r.rmsMetres, rmsMax, name)
        }
    }

    // MARK: MGRS formatting + parsing

    func testSharedMGRSVectors() throws {
        let root = try fixture("mgrs_samples.json") as! [String: Any]
        for c in (root["coordinates"] as! [[String: Any]]) {
            let lat = dbl(c["lat"]); let lon = dbl(c["lon"])
            let coord = CLLocationCoordinate2D(latitude: lat, longitude: lon)
            XCTAssertEqual(MGRSFormatter.string(from: coord, spaced: true), c["spaced"] as! String)
            XCTAssertEqual(MGRSFormatter.string(from: coord, spaced: false), c["compact"] as! String)
            let back = try XCTUnwrap(MGRSFormatter.coordinate(from: c["spaced"] as! String))
            XCTAssertEqual(back.latitude, lat, accuracy: 1e-3)
            XCTAssertEqual(back.longitude, lon, accuracy: 1e-3)
        }
        for s in (root["invalid"] as! [String]) {
            XCTAssertNil(MGRSFormatter.coordinate(from: s), "should reject: \(s)")
        }
    }

    // MARK: GeoJSON geometry

    private func exportedGeometries(waypoints: [Waypoint], drawings: [DrawingShape]) throws -> [[String: Any]] {
        let json = try GeoJSONExporter.export(waypoints: waypoints, drawings: drawings)
        let root = try JSONSerialization.jsonObject(with: Data(json.utf8)) as! [String: Any]
        return (root["features"] as! [[String: Any]]).map { $0["geometry"] as! [String: Any] }
    }

    /// Numeric-aware deep comparison (so `2` and `2.0` match).
    private func geomEqual(_ a: Any?, _ b: Any?) -> Bool {
        if let na = a as? NSNumber, let nb = b as? NSNumber { return abs(na.doubleValue - nb.doubleValue) < 1e-9 }
        if let sa = a as? String, let sb = b as? String { return sa == sb }
        if let aa = a as? [Any], let bb = b as? [Any] {
            return aa.count == bb.count && zip(aa, bb).allSatisfy { geomEqual($0, $1) }
        }
        if let da = a as? [String: Any], let db = b as? [String: Any] {
            return Set(da.keys) == Set(db.keys) && da.allSatisfy { geomEqual($0.value, db[$0.key]) }
        }
        return false
    }

    func testSharedGeoJSONGeometry() throws {
        let root = try fixture("geojson_geometry.json") as! [String: Any]

        let pt = root["point"] as! [String: Any]
        let pin = pt["input"] as! [String: Any]
        let wp = Waypoint(name: "p", latitude: dbl(pin["lat"]), longitude: dbl(pin["lon"]), kind: .generic)
        XCTAssertTrue(geomEqual(try exportedGeometries(waypoints: [wp], drawings: [])[0], pt["geometry"]), "point")

        let ln = root["line"] as! [String: Any]
        let lcoords = (ln["input"] as! [[String: Any]]).map { Coordinate2D(latitude: dbl($0["lat"]), longitude: dbl($0["lon"])) }
        let line = DrawingShape(kind: .polyline, coordinates: lcoords)
        XCTAssertTrue(geomEqual(try exportedGeometries(waypoints: [], drawings: [line])[0], ln["geometry"]), "line")

        let pg = root["polygon"] as! [String: Any]
        let pcoords = (pg["input"] as! [[String: Any]]).map { Coordinate2D(latitude: dbl($0["lat"]), longitude: dbl($0["lon"])) }
        let poly = DrawingShape(kind: .polygon, coordinates: pcoords)
        XCTAssertTrue(geomEqual(try exportedGeometries(waypoints: [], drawings: [poly])[0], pg["geometry"]), "polygon")
    }
}
