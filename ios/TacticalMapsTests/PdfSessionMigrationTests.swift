import XCTest
import CoreGraphics
import CoreLocation
import PDFKit
import UIKit
@testable import TacticalMaps

/// plans/02 s1 persistence: sealed session gains a v2 georef, v1 entries
/// migrate on load (GeoPDF re-parse, fiduciary refit in UTM, legacy camera
/// box -> uncalibrated).
final class PdfSessionMigrationTests: XCTestCase {
    private let testKey = Data((0..<32).map { UInt8(200 - $0) })
    private var root: URL!
    private var imported: URL!
    private var suite: String!
    private var originalDefaults: (() -> UserDefaults)!
    private var originalImported: (() throws -> URL)!
    private var originalLegacy: (() -> URL?)!

    override func setUp() {
        super.setUp()
        originalDefaults = PDFSessionStore.defaultsProvider
        originalImported = PDFSessionStore.importedMapsDirectoryProvider
        originalLegacy = PDFSessionStore.legacyDocumentsDirectoryProvider
        root = FileManager.default.temporaryDirectory.appendingPathComponent("pdf-migration-\(UUID().uuidString)")
        imported = root.appendingPathComponent("ImportedMaps", isDirectory: true)
        try? FileManager.default.createDirectory(at: imported, withIntermediateDirectories: true)
        suite = "PdfSessionMigrationTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        let dir = imported!
        PDFSessionStore.defaultsProvider = { defaults }
        PDFSessionStore.importedMapsDirectoryProvider = { dir }
        PDFSessionStore.legacyDocumentsDirectoryProvider = { nil }
        SafeStore.keyProvider = { [testKey] in testKey }
        SealedMigrationPolicy.resetForTests(key: testKey)
    }

    override func tearDown() {
        PDFSessionStore.clear()
        PDFSessionStore.defaultsProvider().removePersistentDomain(forName: suite)
        PDFSessionStore.defaultsProvider = originalDefaults
        PDFSessionStore.importedMapsDirectoryProvider = originalImported
        PDFSessionStore.legacyDocumentsDirectoryProvider = originalLegacy
        SafeStore.keyProvider = { try DataKey.key() }
        SealedMigrationPolicy.resetForTests(key: testKey)
        try? FileManager.default.removeItem(at: root)
        super.tearDown()
    }

    private func importFixture(_ name: String) throws -> URL {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("testdata/geopdf").appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: candidate.path) {
                let dest = imported.appendingPathComponent("imported-\(UUID().uuidString).pdf")
                try FileManager.default.copyItem(at: candidate, to: dest)
                return dest
            }
            dir = dir.deletingLastPathComponent()
        }
        throw XCTSkip("testdata/geopdf/\(name) missing")
    }

    /// what a pre-georef build wrote: box + crop + kind (+ fiduciaries)
    private func storeV1(file: URL, kind: MapSourceKind, sw: (Double, Double), ne: (Double, Double),
                         crop: CGRect, calibration: [String: Any]? = nil,
                         placementAffine: [String: Double]? = nil) throws {
        var d: [String: Any] = [
            "fileName": file.lastPathComponent,
            "contentKey": try XCTUnwrap(PDFSessionStore.contentKey(for: file)),
            "swLat": sw.0, "swLng": sw.1, "neLat": ne.0, "neLng": ne.1,
            "cropX": Double(crop.minX), "cropY": Double(crop.minY),
            "cropW": Double(crop.width), "cropH": Double(crop.height),
            "kind": kind.rawValue,
        ]
        if let calibration { d["calibration"] = calibration }
        if let placementAffine { d["placementAffine"] = placementAffine }
        let plaintext = try JSONSerialization.data(withJSONObject: d)
        let sealed = try SealedEnvelope.sealFile(key: testKey, plaintext: plaintext, label: "pdf_session/active_pdf")
        PDFSessionStore.defaultsProvider().set(sealed, forKey: "active_pdf_v1")
    }

    private func storedJSON() throws -> [String: Any] {
        let sealed = try XCTUnwrap(PDFSessionStore.defaultsProvider().data(forKey: "active_pdf_v1"))
        let plain = try XCTUnwrap(SealedEnvelope.openFile(key: testKey, blob: sealed, label: "pdf_session/active_pdf"))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: plain) as? [String: Any])
    }

    private func wgs84UTM10(_ e: Double, _ n: Double) throws -> CLLocationCoordinate2D {
        let g = try XCTUnwrap(GeoCrs.utm(zone: 10, south: false).inverse(x: e, y: n, ellipsoid: .wgs84))
        return CLLocationCoordinate2D(latitude: g.lat, longitude: g.lon)
    }

    // MARK: -

    func testV1GeoPDFSessionIsReparsedIntoTheV2Georef() throws {
        let file = try importFixture("tacmap_grid_sf_iso.pdf")
        // the old lon/lat affine + box, ~5 m off the printed grid (D1-02)
        try storeV1(file: file, kind: .geoPDF, sw: (37.7298, -122.478), ne: (37.8022, -122.4093),
                    crop: CGRect(x: 72, y: 72, width: 680.314961, height: 907.086614),
                    placementAffine: ["a": 1e-4, "b": 0, "c": -122.48, "d": 0, "e": 8e-5, "f": 37.72])
        let restored = try XCTUnwrap(PDFSessionStore.load())
        let expected = try XCTUnwrap(GeoPDFReader.read(url: file)?.georef)
        XCTAssertEqual(restored.georef, expected)
        XCTAssertEqual(restored.georef.origin, .adobeVP)
        XCTAssertEqual(restored.kind, .geoPDF)
        // rewritten as v2 so the next launch doesn't re-migrate
        let json = try storedJSON()
        XCTAssertEqual(json["version"] as? Int, 2)
        XCTAssertNotNil(json["georef"])
        XCTAssertEqual(try XCTUnwrap(PDFSessionStore.load()).georef, expected)
    }

    func testV1FiduciarySessionIsRefitInUTMOfTheFirstPoint() throws {
        let file = try importFixture("tacmap_grid_sf_plain.pdf")
        // stored fiduciaries are WGS84 lat/lon of the sheet's ring targets
        let targets: [(Double, Double, Double, Double)] = [
            (185.3858268, 185.3858268, 547_000, 4_177_000), (638.9291339, 185.3858268, 551_000, 4_177_000),
            (638.9291339, 865.7007874, 551_000, 4_183_000), (185.3858268, 865.7007874, 547_000, 4_183_000),
        ]
        var fids: [[String: Any]] = []
        var plain: [Fiduciary] = []
        for (i, t) in targets.enumerated() {
            let w = try wgs84UTM10(t.2, t.3)
            fids.append(["id": "00000000-0000-0000-0000-00000000000\(i + 1)", "pdfX": t.0, "pdfY": t.1,
                         "mgrs": "f\(i)", "latitude": w.latitude, "longitude": w.longitude])
            plain.append(Fiduciary(pdfX: t.0, pdfY: t.1, mgrs: "", latitude: w.latitude, longitude: w.longitude))
        }
        let old = try AffineFitter.fit(plain).transform
        try storeV1(file: file, kind: .calibratedPDF, sw: (37.72, -122.48), ne: (37.81, -122.40),
                    crop: CGRect(x: 0, y: 0, width: 824.315, height: 1051.087),
                    calibration: ["fids": fids, "transform": ["a": old.a, "b": old.b, "c": old.c,
                                                              "d": old.d, "e": old.e, "f": old.f]])
        let restored = try XCTUnwrap(PDFSessionStore.load())
        XCTAssertEqual(restored.georef.origin, .fiduciaries)
        XCTAssertEqual(restored.georef.crs.utmZone?.zone, 10)
        XCTAssertEqual(restored.fiduciaries?.count, 4)
        // a grid intersection between the targets lands on the true UTM grid
        // (548000E 4180000N), which the old lon/lat affine couldn't do
        let w = try XCTUnwrap(restored.georef.toWGS84(x: 298.7716535, y: 525.5433071))
        let truth = try wgs84UTM10(548_000, 4_180_000)
        XCTAssertEqual(w.latitude, truth.latitude, accuracy: 1e-8)
        XCTAssertEqual(w.longitude, truth.longitude, accuracy: 1e-8)
        XCTAssertEqual(try storedJSON()["version"] as? Int, 2)
        // the library carries the v2 georef too, a re-import adopts it as is
        let again = PDFMapSource(url: file, georef: try XCTUnwrap(PdfGeoreference.provisional(
            pageBox: CGRect(x: 0, y: 0, width: 824.315, height: 1051.087), rotation: 0,
            centredOn: CLLocationCoordinate2D(latitude: 0, longitude: 0))),
            contentKey: PDFSessionStore.contentKey(for: file))
        PDFSessionStore.applyCalibrationIfKnown(to: again)
        XCTAssertEqual(again.georef, restored.georef)
    }

    func testV1CameraFallbackForAPlainPDFComesBackUncalibrated() throws {
        let file = try importFixture("tacmap_grid_sf_plain.pdf")
        try storeV1(file: file, kind: .calibratedPDF, sw: (-35.05, 148.95), ne: (-34.95, 149.05),
                    crop: CGRect(x: 0, y: 0, width: 824.315, height: 1051.087))
        let restored = try XCTUnwrap(PDFSessionStore.load())
        XCTAssertTrue(restored.isUncalibrated, "a made-up camera box is not a calibration")
        XCTAssertNil(restored.calibration)
        let centre = try XCTUnwrap(restored.georef.toWGS84(x: 824.315 / 2, y: 1051.087 / 2))
        XCTAssertEqual(centre.latitude, -35, accuracy: 1e-9)
        XCTAssertEqual(centre.longitude, 149, accuracy: 1e-9)
        let json = try storedJSON()
        XCTAssertEqual((json["georef"] as? [String: Any])?["origin"] as? String, "provisional")
    }

    func testV1CameraFallbackForAWronglyRejectedGeoPDFNowPlacesIt() throws {
        // D1-01: US Topo LPTS just outside [0,1] used to land in the camera box
        let file = try importFixture("tacmap_usgs_sf_north_vp.pdf")
        try storeV1(file: file, kind: .calibratedPDF, sw: (-35.05, 148.95), ne: (-34.95, 149.05),
                    crop: CGRect(x: 0, y: 0, width: 1728, height: 2088))
        let restored = try XCTUnwrap(PDFSessionStore.load())
        XCTAssertFalse(restored.isUncalibrated)
        XCTAssertEqual(restored.georef.origin, .adobeVP)
        XCTAssertEqual(restored.georef.datum.id, "NAD83")
        XCTAssertEqual(restored.kind, .geoPDF)
    }

    func testV2RoundTripKeepsTheGeorefExactly() throws {
        for name in ["tacmap_grid_sf_lgictm.pdf", "tacmap_grid_lcc_lgile.pdf", "tacmap_grid_geog_iso.pdf",
                     "tacmap_grid_sf27_iso.pdf", "tacmap_grid_offset_iso.pdf"] {
            let file = try importFixture(name)
            let g = try XCTUnwrap(GeoPDFReader.read(url: file)?.georef, name)
            let source = PDFMapSource(url: file, georef: g, contentKey: PDFSessionStore.contentKey(for: file))
            XCTAssertTrue(PDFSessionStore.save(source), name)
            let restored = try XCTUnwrap(PDFSessionStore.load(), name)
            XCTAssertEqual(restored.georef, g, name)
            XCTAssertEqual(restored.georef.toWGS84(x: 300, y: 400)?.latitude, g.toWGS84(x: 300, y: 400)?.latitude, name)
            XCTAssertEqual(restored.georef.toWGS84(x: 300, y: 400)?.longitude, g.toWGS84(x: 300, y: 400)?.longitude, name)
        }
    }

    // MARK: - v1 fiduciaries sat in PDFKit's display space

    /// one page PDF straight into ImportedMaps, content in raw user space
    private func writeImportedPDF(media: String, extras: String, content: String) throws -> URL {
        let objects = ["<< /Type /Catalog /Pages 2 0 R >>",
                       "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                       "<< /Type /Page /Parent 2 0 R /MediaBox [\(media)] /Resources << >> /Contents 4 0 R \(extras) >>",
                       "<< /Length \(content.utf8.count) >>\nstream\n\(content)\nendstream"]
        var data = Data()
        func append(_ s: String) { data.append(s.data(using: .isoLatin1)!) }
        append("%PDF-1.7\n")
        var offsets: [Int] = []
        for (i, o) in objects.enumerated() { offsets.append(data.count); append("\(i + 1) 0 obj\n\(o)\nendobj\n") }
        let xref = data.count
        append("xref\n0 \(objects.count + 1)\n0000000000 65535 f \n")
        offsets.forEach { append(String(format: "%010d 00000 n \n", $0)) }
        append("trailer\n<< /Size \(objects.count + 1) /Root 1 0 R >>\nstartxref\n\(xref)\n%%EOF\n")
        let url = imported.appendingPathComponent("legacy-\(UUID().uuidString).pdf")
        try data.write(to: url)
        return url
    }

    private static let squareColours: [(r: UInt8, g: UInt8, b: UInt8, pdf: String)] = [
        (255, 0, 0, "1 0 0 rg"), (0, 255, 0, "0 1 0 rg"), (0, 0, 255, "0 0 1 rg"), (0, 0, 0, "0 0 0 rg"),
    ]

    /// 10 pt squares, one colour each, lower-left corners in raw user space
    private func squares(_ corners: [(Double, Double)]) -> String {
        zip(corners, Self.squareColours).map { c, col in "\(col.pdf) \(c.0) \(c.1) 10 10 re f" }.joined(separator: "\n")
    }

    /// The HEAD render + tap path, kept verbatim: PDFRasteriser drew the media
    /// box with PDFKit draw(with: .mediaBox) under its own CTM, and
    /// PDFImageOverlayView.pdfPoint mapped a tap back through pdfRenderRect.
    /// Returns what a v1 tap on the middle of each coloured square recorded.
    private func legacyRecordedTaps(_ url: URL) throws -> (renderRect: CGRect, taps: [CGPoint]) {
        let page = try XCTUnwrap(PDFDocument(url: url)?.page(at: 0))
        let renderRect = page.bounds(for: .mediaBox)
        let size = CGSize(width: renderRect.width, height: renderRect.height)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true
        let image = UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            let cg = ctx.cgContext
            cg.saveGState()
            cg.translateBy(x: 0, y: size.height)
            cg.scaleBy(x: 1, y: -1)
            cg.translateBy(x: -renderRect.minX, y: -renderRect.minY)
            page.draw(with: .mediaBox, to: cg)
            cg.restoreGState()
        }
        let cgImage = try XCTUnwrap(image.cgImage)
        let w = cgImage.width, h = cgImage.height
        let ctx = try XCTUnwrap(CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                          space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        ctx.draw(cgImage, in: CGRect(x: 0, y: 0, width: w, height: h))
        let px = try XCTUnwrap(ctx.data?.assumingMemoryBound(to: UInt8.self))
        var taps: [CGPoint] = []
        for col in Self.squareColours {
            var minX = Int.max, minY = Int.max, maxX = -1, maxY = -1
            for y in 0..<h {
                for x in 0..<w {
                    let o = y * w * 4 + x * 4
                    if abs(Int(px[o]) - Int(col.r)) < 40, abs(Int(px[o + 1]) - Int(col.g)) < 40,
                       abs(Int(px[o + 2]) - Int(col.b)) < 40 {
                        minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                    }
                }
            }
            XCTAssertGreaterThanOrEqual(maxX, 0, "square \(col.pdf) not on the legacy raster")
            // view-local tap at the square's middle (y down), then HEAD's pdfPoint(forScreenTap:)
            let local = CGPoint(x: Double(minX + maxX + 1) / 2, y: Double(minY + maxY + 1) / 2)
            taps.append(CGPoint(x: renderRect.minX + local.x / size.width * renderRect.width,
                                y: renderRect.maxY - local.y / size.height * renderRect.height))
        }
        return (renderRect, taps)
    }

    /// made-up truth: raw page point -> UTM 10N at 10 m/pt, so a refit in
    /// zone 10 is exact and any page-space slip shows up as metres
    private func truth(_ p: CGPoint) throws -> CLLocationCoordinate2D {
        try wgs84UTM10(546_000 + 10 * Double(p.x), 4_176_000 + 10 * Double(p.y))
    }

    private func v1Calibration(taps: [CGPoint], raw: [CGPoint]) throws -> [String: Any] {
        var fids: [[String: Any]] = []
        var plain: [Fiduciary] = []
        for (i, (tap, r)) in zip(taps, raw).enumerated() {
            let w = try truth(r)
            fids.append(["id": "00000000-0000-0000-0000-00000000010\(i)", "pdfX": Double(tap.x), "pdfY": Double(tap.y),
                         "mgrs": "f\(i)", "latitude": w.latitude, "longitude": w.longitude])
            plain.append(Fiduciary(pdfX: Double(tap.x), pdfY: Double(tap.y), mgrs: "",
                                   latitude: w.latitude, longitude: w.longitude))
        }
        // the old lon/lat affine, fitted in the same (display) space as the taps
        let old = try AffineFitter.fit(plain).transform
        return ["fids": fids, "transform": ["a": old.a, "b": old.b, "c": old.c, "d": old.d, "e": old.e, "f": old.f]]
    }

    private func assertRawPlacement(_ restored: PDFMapSource, raw: [CGPoint], _ ctx: String) throws {
        XCTAssertEqual(restored.georef.origin, .fiduciaries, ctx)
        XCTAssertFalse(restored.isUncalibrated, ctx)
        let fids = try XCTUnwrap(restored.fiduciaries, ctx)
        XCTAssertEqual(fids.count, raw.count, ctx)
        for (f, r) in zip(fids, raw) {
            XCTAssertEqual(f.pdfX, Double(r.x), accuracy: 1e-9, "\(ctx) fiduciary back in raw space")
            XCTAssertEqual(f.pdfY, Double(r.y), accuracy: 1e-9, "\(ctx) fiduciary back in raw space")
        }
        // the sheet lands where the raw-space renderer draws it, anywhere on the page
        for p in raw + [CGPoint(x: 300, y: 300), CGPoint(x: 150, y: 420)] {
            let got = try XCTUnwrap(restored.georef.toWGS84(x: Double(p.x), y: Double(p.y)), ctx)
            let want = try truth(p)
            XCTAssertEqual(got.latitude, want.latitude, accuracy: 1e-8, "\(ctx) \(p)")
            XCTAssertEqual(got.longitude, want.longitude, accuracy: 1e-8, "\(ctx) \(p)")
        }
    }

    func testV1FiduciariesOnAnOffsetMediaBoxAreMovedBackToRawSpace() throws {
        let raw = [CGPoint(x: 105, y: 125), CGPoint(x: 565, y: 135), CGPoint(x: 545, y: 765), CGPoint(x: 115, y: 745)]
        let file = try writeImportedPDF(media: "50 50 650 850", extras: "",
                                        content: squares(raw.map { (Double($0.x) - 5, Double($0.y) - 5) }))
        let legacy = try legacyRecordedTaps(file)
        // what HEAD recorded: the box origin came off twice
        for (t, r) in zip(legacy.taps, raw) {
            XCTAssertEqual(Double(t.x), Double(r.x) - 50, accuracy: 1e-9)
            XCTAssertEqual(Double(t.y), Double(r.y) - 50, accuracy: 1e-9)
        }
        try storeV1(file: file, kind: .calibratedPDF, sw: (37.72, -122.48), ne: (37.81, -122.40),
                    crop: legacy.renderRect, calibration: try v1Calibration(taps: legacy.taps, raw: raw))
        let restored = try XCTUnwrap(PDFSessionStore.load())
        try assertRawPlacement(restored, raw: raw, "offset box")
        let json = try storedJSON()
        XCTAssertEqual(json["version"] as? Int, 2)
        XCTAssertEqual((json["calibration"] as? [String: Any])?["rawPageSpace"] as? Bool, true)
        // second launch reads the v2 record, no second undo
        try assertRawPlacement(try XCTUnwrap(PDFSessionStore.load()), raw: raw, "offset box reload")
    }

    func testV1FiduciariesOnARotate90PageAreMovedBackToRawSpace() throws {
        let raw = [CGPoint(x: 105, y: 105), CGPoint(x: 505, y: 115), CGPoint(x: 485, y: 525), CGPoint(x: 95, y: 505)]
        let file = try writeImportedPDF(media: "0 0 600 800", extras: "/Rotate 90",
                                        content: squares(raw.map { (Double($0.x) - 5, Double($0.y) - 5) }))
        let legacy = try legacyRecordedTaps(file)
        // HEAD drew the page turned: raw (x, y) showed up at (y, 600 - x)
        for (t, r) in zip(legacy.taps, raw) {
            XCTAssertEqual(Double(t.x), Double(r.y), accuracy: 1e-9)
            XCTAssertEqual(Double(t.y), 600 - Double(r.x), accuracy: 1e-9)
        }
        try storeV1(file: file, kind: .calibratedPDF, sw: (37.72, -122.48), ne: (37.81, -122.40),
                    crop: legacy.renderRect, calibration: try v1Calibration(taps: legacy.taps, raw: raw))
        let restored = try XCTUnwrap(PDFSessionStore.load())
        try assertRawPlacement(restored, raw: raw, "rotate 90")
        try assertRawPlacement(try XCTUnwrap(PDFSessionStore.load()), raw: raw, "rotate 90 reload")
    }

    func testLegacyLibraryCalibrationIsMovedBackToRawSpaceOnReimport() throws {
        let raw = [CGPoint(x: 105, y: 125), CGPoint(x: 565, y: 135), CGPoint(x: 545, y: 765), CGPoint(x: 115, y: 745)]
        let file = try writeImportedPDF(media: "50 50 650 850", extras: "",
                                        content: squares(raw.map { (Double($0.x) - 5, Double($0.y) - 5) }))
        let legacy = try legacyRecordedTaps(file)
        let key = try XCTUnwrap(PDFSessionStore.contentKey(for: file))
        // a HEAD library record: no georef, no page-space flag
        try storeLibrary(["version": 2, "byContentHash": [key: try v1Calibration(taps: legacy.taps, raw: raw)],
                          "legacyByFileName": [String: Any]()])
        let again = PDFMapSource(url: file, georef: try XCTUnwrap(PdfGeoreference.provisional(
            pageBox: legacy.renderRect, rotation: 0, centredOn: CLLocationCoordinate2D(latitude: 0, longitude: 0))),
            contentKey: key)
        PDFSessionStore.applyCalibrationIfKnown(to: again)
        try assertRawPlacement(again, raw: raw, "library")
    }

    func testOneUndecodableGeorefDoesNotTakeTheLibraryOrSessionDown() throws {
        let raw = [CGPoint(x: 105, y: 125), CGPoint(x: 565, y: 135), CGPoint(x: 545, y: 765), CGPoint(x: 115, y: 745)]
        let good = try writeImportedPDF(media: "50 50 650 850", extras: "",
                                        content: squares(raw.map { (Double($0.x) - 5, Double($0.y) - 5) }))
        let other = try importFixture("tacmap_grid_sf_plain.pdf")
        let goodKey = try XCTUnwrap(PDFSessionStore.contentKey(for: good))
        let otherKey = try XCTUnwrap(PDFSessionStore.contentKey(for: other))
        // points already raw (written next to a georef), but that georef no longer decodes
        var rawCal = try v1Calibration(taps: raw, raw: raw)
        rawCal["georef"] = ["page": 0, "crop": [[0, 0], [1, 1]], "origin": "fiduciaries"]
        let legacy = try legacyRecordedTaps(good)
        try storeLibrary(["version": 2,
                          "byContentHash": [otherKey: try v1Calibration(taps: [CGPoint(x: 185.3858268, y: 185.3858268),
                                                                                CGPoint(x: 638.9291339, y: 185.3858268),
                                                                                CGPoint(x: 638.9291339, y: 865.7007874)],
                                                                         raw: [CGPoint(x: 1, y: 1), CGPoint(x: 2, y: 1),
                                                                               CGPoint(x: 2, y: 2)]),
                                            goodKey: rawCal],
                          "legacyByFileName": [String: Any]()])
        let again = PDFMapSource(url: good, georef: try XCTUnwrap(PdfGeoreference.provisional(
            pageBox: legacy.renderRect, rotation: 0, centredOn: CLLocationCoordinate2D(latitude: 0, longitude: 0))),
            contentKey: goodKey)
        PDFSessionStore.applyCalibrationIfKnown(to: again)
        try assertRawPlacement(again, raw: raw, "library entry with a dud georef gets refit, not dropped")

        // same for the active session: v2, dud georef, raw fiduciaries -> refit, no undo, not deleted
        var d: [String: Any] = [
            "fileName": good.lastPathComponent, "contentKey": goodKey,
            "swLat": 37.72, "swLng": -122.48, "neLat": 37.81, "neLng": -122.40,
            "cropX": 50.0, "cropY": 50.0, "cropW": 600.0, "cropH": 800.0,
            "kind": MapSourceKind.calibratedPDF.rawValue, "version": 2,
            "georef": ["origin": "fiduciaries"],
        ]
        d["calibration"] = rawCal
        let sealed = try SealedEnvelope.sealFile(key: testKey, plaintext: try JSONSerialization.data(withJSONObject: d),
                                                 label: "pdf_session/active_pdf")
        PDFSessionStore.defaultsProvider().set(sealed, forKey: "active_pdf_v1")
        try assertRawPlacement(try XCTUnwrap(PDFSessionStore.load(), "a dud georef must not delete the session"),
                               raw: raw, "active v2 with a dud georef")
    }

    func testV1SessionOfAHugeZoneLgiDictDoesNotCrashEveryLaunch() throws {
        // HEAD's range checked dictInt saved these with its camera box. The v2
        // re-parse at launch used to Int() the zone and trap, every single launch
        for zone in ["(1e30)", "1000000000000000000000000000000.0", "(inf)"] {
            let file = try writeImportedPDF(
                media: "0 0 600 800",
                extras: "/LGIDict << /CTM [1 0 0 1 500000 4000000] /Projection << /ProjectionType (UT) "
                    + "/Zone \(zone) /Hemisphere (N) /Datum (WGE) >> >>",
                content: "")
            try storeV1(file: file, kind: .calibratedPDF, sw: (-35.05, 148.95), ne: (-34.95, 149.05),
                        crop: CGRect(x: 0, y: 0, width: 600, height: 800))
            let restored = try XCTUnwrap(PDFSessionStore.load(), zone)
            XCTAssertTrue(restored.isUncalibrated, zone)
            XCTAssertEqual(restored.georef.origin, .provisional, zone)
        }
    }

    func testRefusedV1RefitKeepsThePointsPendingForTheNextCalibration() throws {
        let file = try importFixture("tacmap_grid_sf_plain.pdf")
        // a long thin strip: fine for the old rank check, eigen ratio ~0.004 < 0.02 so the UTM refit refuses
        let raw = [CGPoint(x: 100, y: 100), CGPoint(x: 600, y: 100), CGPoint(x: 600, y: 130), CGPoint(x: 100, y: 130)]
        try storeV1(file: file, kind: .calibratedPDF, sw: (37.72, -122.48), ne: (37.81, -122.40),
                    crop: CGRect(x: 0, y: 0, width: 824.315, height: 1051.087),
                    calibration: try v1Calibration(taps: raw, raw: raw))
        let restored = try XCTUnwrap(PDFSessionStore.load())
        XCTAssertTrue(restored.isUncalibrated)
        XCTAssertNil(restored.calibration)
        func assertPending(_ source: PDFMapSource, _ ctx: String) {
            XCTAssertEqual(source.pendingFiduciaries.map(\.pdfPoint), raw, ctx)
            XCTAssertEqual(source.pendingFiduciaries.map(\.mgrs), ["f0", "f1", "f2", "f3"], ctx)
        }
        assertPending(restored, "migrated")
        // the v2 record carries them, a second launch still has them
        let json = try storedJSON()
        XCTAssertEqual(json["version"] as? Int, 2)
        XCTAssertNil(json["calibration"])
        XCTAssertEqual((json["pendingFiduciaries"] as? [Any])?.count, 4)
        let again = try XCTUnwrap(PDFSessionStore.load())
        assertPending(again, "reloaded")
        // and calibration opens with them placed (Android seeds the same way)
        let session = CalibrationSession()
        session.start(for: again)
        XCTAssertEqual(session.fiduciaries.map(\.pdfPoint), raw)
        // a real calibration replaces them
        let good = [CGPoint(x: 185, y: 185), CGPoint(x: 639, y: 185), CGPoint(x: 639, y: 866), CGPoint(x: 185, y: 866)]
        let fids = try good.map { p -> Fiduciary in
            let w = try truth(p)
            return Fiduciary(pdfX: Double(p.x), pdfY: Double(p.y), mgrs: "", latitude: w.latitude, longitude: w.longitude)
        }
        again.applyCalibration(transform: try AffineFitter.fit(fids).transform, fiduciaries: fids)
        XCTAssertNotNil(again.calibration)
        XCTAssertTrue(again.pendingFiduciaries.isEmpty)
        XCTAssertTrue(PDFSessionStore.save(again))
        XCTAssertNil(try storedJSON()["pendingFiduciaries"])
    }

    func testV1MigrationRunsOffMainAndLeavesTheFastPath() throws {
        let file = try importFixture("tacmap_grid_sf_iso.pdf")
        try storeV1(file: file, kind: .geoPDF, sw: (37.7298, -122.478), ne: (37.8022, -122.4093),
                    crop: CGRect(x: 72, y: 72, width: 680.314961, height: 907.086614))
        XCTAssertTrue(PDFSessionStore.needsMigration)
        let done = expectation(description: "migrated")
        DispatchQueue.global(qos: .userInitiated).async {
            XCTAssertFalse(Thread.isMainThread)
            PDFSessionStore.migrateStoredSession()
            done.fulfill()
        }
        wait(for: [done], timeout: 30)
        XCTAssertFalse(PDFSessionStore.needsMigration, "rewritten as v2, the main thread load doesn't parse")
        XCTAssertEqual(try storedJSON()["version"] as? Int, 2)
        let expected = try XCTUnwrap(GeoPDFReader.read(url: file)?.georef)
        XCTAssertEqual(try XCTUnwrap(PDFSessionStore.load()).georef, expected)
        // nothing saved, nothing to migrate
        PDFSessionStore.clear()
        XCTAssertFalse(PDFSessionStore.needsMigration)
    }

    private func storeLibrary(_ library: [String: Any]) throws {
        let plaintext = try JSONSerialization.data(withJSONObject: library)
        let sealed = try SealedEnvelope.sealFile(key: testKey, plaintext: plaintext, label: "pdf_session/pdf_calibrations")
        PDFSessionStore.defaultsProvider().set(sealed, forKey: "pdf_calibrations_v1")
    }

    func testLegacyDisplayTransformMatchesWhatPDFKitDrew() throws {
        // hand-worked: offset box shifts by the origin, /Rotate 90 turns (x, y) into (y, W - x)
        let offset = try writeImportedPDF(media: "50 50 650 850", extras: "", content: "")
        let inv = try XCTUnwrap(PDFSessionStore.legacyDisplayToRaw(url: offset))
        XCTAssertEqual(CGPoint(x: 55, y: 75).applying(inv).x, 105, accuracy: 1e-9)
        XCTAssertEqual(CGPoint(x: 55, y: 75).applying(inv).y, 125, accuracy: 1e-9)
        let rotated = try writeImportedPDF(media: "0 0 600 800", extras: "/Rotate 90", content: "")
        let rinv = try XCTUnwrap(PDFSessionStore.legacyDisplayToRaw(url: rotated))
        XCTAssertEqual(CGPoint(x: 125, y: 495).applying(rinv).x, 105, accuracy: 1e-9)
        XCTAssertEqual(CGPoint(x: 125, y: 495).applying(rinv).y, 125, accuracy: 1e-9)
        // origin 0, unrotated: nothing moves (the existing sf_plain migration relies on this)
        let plain = try importFixture("tacmap_grid_sf_plain.pdf")
        XCTAssertTrue(try XCTUnwrap(PDFSessionStore.legacyDisplayToRaw(url: plain)).isIdentity)
    }
}

// MARK: - WP2 session fields

extension PdfSessionMigrationTests {
    func testGuardTokenIsStableAcrossLaunchesAndMintedForOldSessions() throws {
        let file = try importFixture("tacmap_grid_sf_iso.pdf")
        let g = try XCTUnwrap(GeoPDFReader.read(url: file)?.georef)
        let source = PDFMapSource(url: file, georef: g, contentKey: PDFSessionStore.contentKey(for: file))
        XCTAssertNotNil(UUID(uuidString: source.renderGuardToken))
        XCTAssertTrue(PDFSessionStore.save(source))
        let a = try XCTUnwrap(PDFSessionStore.load())
        let b = try XCTUnwrap(PDFSessionStore.load())
        XCTAssertEqual(a.renderGuardToken, source.renderGuardToken)
        XCTAssertEqual(b.renderGuardToken, source.renderGuardToken)
        // a v1 session has none: it gets one, and keeps it next launch
        try storeV1(file: file, kind: .geoPDF, sw: (37.73, -122.48), ne: (37.80, -122.41),
                    crop: CGRect(x: 72, y: 72, width: 680, height: 907))
        let migrated = try XCTUnwrap(PDFSessionStore.load())
        let again = try XCTUnwrap(PDFSessionStore.load())
        XCTAssertEqual(migrated.renderGuardToken, again.renderGuardToken)
        XCTAssertNotEqual(migrated.renderGuardToken, source.renderGuardToken)
    }
}
