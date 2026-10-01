import XCTest
import CoreGraphics
import CoreLocation
import UIKit
@testable import TacticalMaps

/// The georef model wired into the app: raw page space in the raster paths
/// (D1-04 / D3-06), import outcomes that never fall back to a camera box,
/// the best-fit lon/lat stopgap for the current overlay, and calibration.
final class PdfGeorefIntegrationTests: XCTestCase {

    private var scratch: [URL] = []

    override func tearDown() {
        scratch.forEach { try? FileManager.default.removeItem(at: $0) }
        scratch = []
        super.tearDown()
    }

    private func testdata(_ name: String) throws -> URL {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("testdata").appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            dir = dir.deletingLastPathComponent()
        }
        throw XCTSkip("testdata/\(name) missing")
    }

    /// one page, latin-1, content stream drawn in raw user space
    private func writePDF(media: String, extras: String = "", content: String = "") throws -> URL {
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
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("georef-\(UUID().uuidString).pdf")
        try data.write(to: url)
        scratch.append(url)
        return url
    }

    /// bbox of dark pixels, image coords (y down)
    private func darkBox(_ image: CGImage) -> CGRect? {
        guard let ctx = CGContext(data: nil, width: image.width, height: image.height, bitsPerComponent: 8,
                                  bytesPerRow: image.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        guard let p = ctx.data?.assumingMemoryBound(to: UInt8.self) else { return nil }
        var minX = Int.max, minY = Int.max, maxX = -1, maxY = -1
        for y in 0..<image.height {
            for x in 0..<image.width {
                let o = y * image.width * 4 + x * 4
                // opaque and dark (the page is white, off-crop is clear)
                if p[o + 3] > 200 && p[o] < 60 && p[o + 1] < 60 && p[o + 2] < 60 {
                    // bitmap memory row 0 is the top of the drawn image
                    minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                }
            }
        }
        guard maxX >= 0 else { return nil }
        return CGRect(x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1)
    }

    // MARK: - raw user space in the raster paths (D1-04, D3-06)

    func testRasteriserDrawsRawUserSpaceForOffsetBoxesAndRotatedPages() throws {
        // black 20 pt square at raw (200..220, 300..320) whatever the boxes/rotate say
        let square = "0 0 0 rg 200 300 20 20 re f"
        for extras in ["", "/Rotate 90", "/Rotate 270", "/Rotate 180 /CropBox [120 170 480 630]"] {
            let url = try writePDF(media: "100 150 500 650", extras: extras, content: square)
            let crop = CGRect(x: 100, y: 150, width: 400, height: 500)
            let image = try XCTUnwrap(PDFRasteriser.render(url: url, cropRect: crop)?.cgImage, extras)
            XCTAssertEqual(image.width, 400)
            let box = try XCTUnwrap(darkBox(image), extras)
            // pixel x = raw x - 100, pixel row (from top) = 650 - raw y
            XCTAssertEqual(Double(box.minX), 100, accuracy: 1, "\(extras) x")
            XCTAssertEqual(Double(box.maxX), 120, accuracy: 1, "\(extras) x")
            XCTAssertEqual(Double(box.minY), 330, accuracy: 1, "\(extras) y")
            XCTAssertEqual(Double(box.maxY), 350, accuracy: 1, "\(extras) y")
        }
    }

    func testRasteriserLeavesEverythingOutsideTheCropPolygonClear() throws {
        let url = try writePDF(media: "0 0 400 400", content: "0 0 0 rg 0 0 400 400 re f")
        let triangle = [PdfPagePoint(x: 0, y: 0), PdfPagePoint(x: 400, y: 0), PdfPagePoint(x: 0, y: 400)]
        let image = try XCTUnwrap(PDFRasteriser.render(url: url, cropRect: CGRect(x: 0, y: 0, width: 400, height: 400),
                                                       cropPolygon: triangle)?.cgImage)
        let box = try XCTUnwrap(darkBox(image))
        XCTAssertEqual(box.width, 400, accuracy: 1)
        // top-right corner pixel is outside the neatline triangle
        let ctx = try XCTUnwrap(CGContext(data: nil, width: 400, height: 400, bitsPerComponent: 8, bytesPerRow: 1600,
                                          space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: 400, height: 400))
        let p = try XCTUnwrap(ctx.data?.assumingMemoryBound(to: UInt8.self))
        // memory row 0 is the top of the image
        XCTAssertEqual(p[0 * 1600 + 395 * 4 + 3], 0, "outside the crop polygon must stay transparent")
    }

    func testTilerDrawsThePageWhereTheGeorefPutsIt() throws {
        let square = "0 0 0 rg 200 300 20 20 re f"
        for extras in ["", "/Rotate 90"] {
            let url = try writePDF(media: "100 150 500 650", extras: extras, content: square)
            let doc = try XCTUnwrap(CGPDFDocument(url as CFURL))
            let page = try XCTUnwrap(doc.page(at: 1))
            let georef = try XCTUnwrap(PdfGeoreference.provisional(
                pageBox: CGRect(x: 100, y: 150, width: 400, height: 500), rotation: 0,
                centredOn: CLLocationCoordinate2D(latitude: 37.77, longitude: -122.42)))
            // tile holding the square's centre at z16 (~2.4 m/px, square is ~350 m)
            let centre = try XCTUnwrap(georef.toWGS84(x: 210, y: 310))
            let z = 14
            let tx = Int(WebMercatorTiles.lonToTileX(centre.longitude, z))
            let ty = Int(WebMercatorTiles.latToTileY(centre.latitude, z))
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            format.opaque = true
            let renderer = UIGraphicsImageRenderer(size: CGSize(width: 256, height: 256), format: format)
            let png = try XCTUnwrap(PDFTiler.renderTile(renderer, page: page, georef: georef, z: z, x: tx, y: ty))
            let tile = try XCTUnwrap(UIImage(data: png)?.cgImage)
            let box = try XCTUnwrap(darkBox(tile), extras)
            // where the square's centre should be in tile pixels (y down)
            let fx = WebMercatorTiles.lonToTileX(centre.longitude, z) - Double(tx)
            let fy = WebMercatorTiles.latToTileY(centre.latitude, z) - Double(ty)
            XCTAssertEqual(Double(box.midX), fx * 256, accuracy: 1.5, "\(extras) tile x")
            XCTAssertEqual(Double(box.midY), fy * 256, accuracy: 1.5, "\(extras) tile y")
        }
    }

    func testTilerPageToPixelSolvesThreeCorners() throws {
        let m = try XCTUnwrap(PDFTiler.pageToPixel(CGPoint(x: 10, y: 20), CGPoint(x: 110, y: 30), CGPoint(x: 0, y: -80),
                                                   CGPoint(x: 0, y: 0), CGPoint(x: 256, y: 0), CGPoint(x: 0, y: 256)))
        for (p, q) in [(CGPoint(x: 10, y: 20), CGPoint(x: 0, y: 0)), (CGPoint(x: 110, y: 30), CGPoint(x: 256, y: 0)),
                       (CGPoint(x: 0, y: -80), CGPoint(x: 0, y: 256))] {
            let r = p.applying(m)
            XCTAssertEqual(r.x, q.x, accuracy: 1e-9)
            XCTAssertEqual(r.y, q.y, accuracy: 1e-9)
        }
        XCTAssertNil(PDFTiler.pageToPixel(.zero, CGPoint(x: 1, y: 1), CGPoint(x: 2, y: 2), .zero, .zero, .zero))
    }

    func testTilerRefusesToBakeAnUncalibratedPlacement() throws {
        let url = try writePDF(media: "0 0 400 400")
        let georef = try XCTUnwrap(PdfGeoreference.provisional(pageBox: CGRect(x: 0, y: 0, width: 400, height: 400),
                                                               rotation: 0, centredOn: CLLocationCoordinate2D(latitude: -35, longitude: 149)))
        let source = PDFMapSource(url: url, georef: georef)
        XCTAssertTrue(source.isUncalibrated)
        XCTAssertNil(PDFTiler.generate(source: source) { _ in })
    }

    // MARK: - import outcomes (no silent camera box)

    func testImportWorkerKeepsGeorefPlainAndRejectedApart() async throws {
        let geo = try await ImportedMapWorker.preparePDF(url: try testdata("geopdf/tacmap_grid_sf_iso.pdf"))
        scratch.append(geo.destination)
        guard case .georeferenced(let g) = geo.outcome else { return XCTFail("\(geo.outcome)") }
        XCTAssertEqual(g.origin, .adobeVP)
        XCTAssertEqual(g.crs.utmZone?.zone, 10)

        let plain = try await ImportedMapWorker.preparePDF(url: try testdata("geopdf/tacmap_grid_sf_plain.pdf"))
        scratch.append(plain.destination)
        XCTAssertEqual(plain.outcome, .notGeoreferenced)

        // the real USGS LPTS pattern with one value pushed to 1.6: declared, refused, explained
        let refused = try writePDF(media: "0 0 1728 2088", extras: """
        /VP [<< /Type /Viewport /BBox [0 2088 1727.95998 56.69373] /Measure << /Type /Measure /Subtype /GEO
        /GPTS [37.73318 -122.52121 37.88898 -122.52021 37.88818 -122.35264 37.73238 -122.354]
        /LPTS [-0.00708 1 0 -0.00514 1.6 0 1 1.00514] >> >>]
        """)
        let rejected = try await ImportedMapWorker.preparePDF(url: refused)
        scratch.append(rejected.destination)
        XCTAssertEqual(rejected.outcome, .rejected(.lptsOutOfRange))
        guard case .rejected(let reason)? = PDFMapImporter.placement(
            for: rejected.destination, cameraCentre: CLLocationCoordinate2D(latitude: -35, longitude: 149)) else {
            return XCTFail("a refused georef must not turn into a camera-centred placement")
        }
        XCTAssertEqual(reason, .lptsOutOfRange)
        XCTAssertFalse(reason.displayReason.isEmpty)
    }

    func testRotatedGeoPDFImportsAndPlacesInRawPageSpace() throws {
        // rot90_iso: /Rotate 90 page, georef in raw (unrotated) user space
        let g = try XCTUnwrap(GeoPDFReader.read(url: try testdata("geopdf/tacmap_grid_rot90_iso.pdf"))?.georef)
        let corner = try XCTUnwrap(g.toWGS84(x: 979.0866142, y: 72))
        XCTAssertEqual(corner.latitude, 37.73011820966, accuracy: 1e-7)
        XCTAssertEqual(corner.longitude, -122.47797518349, accuracy: 1e-7)
        let source = PDFMapSource(url: try testdata("geopdf/tacmap_grid_rot90_iso.pdf"), georef: g)
        XCTAssertEqual(source.pdfRenderRect, g.cropBoundingRect)
        XCTAssertNotNil(source.renderedImage())
    }

    func testLgiDescriptionMatchesAsNameOrString() throws {
        // D1-07: some producers write /Description /Layers (a name)
        let entry: (String) -> String = { desc in """
        << /Description \(desc) /CTM [8.819444444496 0 0 8.819444444496 545365 4175365]
           /Neatline [72 72 752.314961 72 752.314961 979.086614 72 979.086614]
           /Projection << /ProjectionType (UT) /Zone 10 /Hemisphere (N) /Datum (WGE) >> >>
        """ }
        let collar = """
        << /Description (Collar) /CTM [8.819444444496 0 0 8.819444444496 545415 4175365]
           /Neatline [0 0 824 0 824 1051 0 1051]
           /Projection << /ProjectionType (UT) /Zone 10 /Hemisphere (N) /Datum (WGE) >> >>
        """
        let url = try writePDF(media: "0 0 824.315 1051.087", extras: "/LGIDict [\(collar) \(entry("/Layers"))]")
        let readout = try XCTUnwrap(GeoPDFReader.read(url: url))
        XCTAssertEqual(readout.selection?.index, 1, "Layers beats the bigger collar entry")
        XCTAssertEqual(readout.georef?.affine.c, 545365)
    }

    // MARK: - the stopgap lon/lat affine for the current overlay

    func testBestFitLatLonAffineTracksTheProjectedGeoref() throws {
        let g = try XCTUnwrap(GeoPDFReader.read(url: try testdata("geopdf/tacmap_usgs_sf_north_vp.pdf"))?.georef)
        let source = PDFMapSource(url: try testdata("geopdf/tacmap_usgs_sf_north_vp.pdf"), georef: g)
        XCTAssertEqual(source.kind, .geoPDF)
        XCTAssertFalse(source.isUncalibrated)
        let t = try XCTUnwrap(source.placementTransform)
        let r = g.cropBoundingRect
        var worst = 0.0, sumSq = 0.0
        for i in 0...10 {
            for j in 0...10 {
                let p = CGPoint(x: r.minX + r.width * CGFloat(i) / 10, y: r.minY + r.height * CGFloat(j) / 10)
                let exact = try XCTUnwrap(g.toWGS84(p))
                let approx = t.apply(p)
                let m = PdfGeoreference.metresPerUnit(crs: .geographic, ellipsoid: .wgs84, latitude: exact.latitude)
                let d = hypot((approx.longitude - exact.longitude) * m.east, (approx.latitude - exact.latitude) * m.north)
                worst = max(worst, d)
                sumSq += d * d
            }
        }
        // a 7.5' quad: one lon/lat affine can't follow UTM (D4-02), LS over
        // the crop box keeps it ~3 m rms / ~8 m at the box corners. That's the
        // stopgap until tiles go through toPage, the georef itself is exact.
        XCTAssertLessThan(worst, 10)
        XCTAssertLessThan((sumSq / 121).squareRoot(), 4)
        let box = try XCTUnwrap(source.bounds)
        let centre = try XCTUnwrap(g.toWGS84(x: Double(r.midX), y: Double(r.midY)))
        XCTAssertTrue(box.southWest.latitude < centre.latitude && centre.latitude < box.northEast.latitude)
        XCTAssertTrue(box.southWest.longitude < centre.longitude && centre.longitude < box.northEast.longitude)
    }

    // MARK: - calibration

    func testCalibrationFinishFitsInUTMOfTheFirstPoint() throws {
        let url = try testdata("geopdf/tacmap_grid_sf_plain.pdf")
        let readout = try XCTUnwrap(GeoPDFReader.read(url: url))
        XCTAssertEqual(readout.outcome, .notGeoreferenced)
        let provisional = try XCTUnwrap(PdfGeoreference.provisional(
            pageBox: readout.page.cropBox, rotation: readout.page.rotation,
            centredOn: CLLocationCoordinate2D(latitude: -35, longitude: 149)))
        let source = PDFMapSource(url: url, georef: provisional)
        let session = CalibrationSession()
        session.start(for: source)
        // the sheet's ring targets (testdata/pdf_georef.json sf_plain truth)
        let targets: [(CGPoint, String)] = [
            (CGPoint(x: 185.3858268, y: 185.3858268), "10SEG 47000 77000"),
            (CGPoint(x: 638.9291339, y: 185.3858268), "10SEG 51000 77000"),
            (CGPoint(x: 638.9291339, y: 865.7007874), "10SEG 51000 83000"),
            (CGPoint(x: 185.3858268, y: 865.7007874), "10SEG 47000 83000"),
        ]
        for (page, ref) in targets {
            session.recordTap(pdfPoint: page, screenPoint: .zero)
            XCTAssertTrue(session.confirmFiduciary(mgrs: ref))
        }
        let result = try XCTUnwrap(session.finish())
        XCTAssertEqual(result.georef.origin, .fiduciaries)
        XCTAssertEqual(result.georef.crs.utmZone?.zone, 10)
        XCTAssertEqual(result.georef.crs.utmZone?.south, false)
        XCTAssertTrue(result.crossValidated)
        XCTAssertLessThan(result.rmsMetres, 0.01)
        // a printed grid intersection the user never touched: 548000E 4180000N
        let w = try XCTUnwrap(result.georef.toWGS84(x: 298.7716535, y: 525.5433071))
        XCTAssertEqual(w.latitude, 37.76606703301, accuracy: 2e-7)
        XCTAssertEqual(w.longitude, -122.45501505078, accuracy: 2e-7)

        source.applyCalibration(transform: result.transform, fiduciaries: session.fiduciaries)
        XCTAssertFalse(source.isUncalibrated)
        XCTAssertEqual(source.georef, result.georef)
        XCTAssertEqual(source.kind, .calibratedPDF)
    }

    // MARK: - hostile LGIDict arrays

    /// page with an LGIDict made of the given entries (and a /VP when passed),
    /// plus object 5: one shared array of sharedCount numbers that every row,
    /// neatline or GPTS can point at
    private func hostilePDF(entries: String? = nil, viewports: String? = nil,
                            sharedCount: Int) throws -> CGPDFDocument {
        let shared = "[" + String(repeating: "0 ", count: sharedCount) + "]"
        let extras = (entries.map { "/LGIDict [\($0)] " } ?? "") + (viewports.map { "/VP [\($0)]" } ?? "")
        let objects = ["<< /Type /Catalog /Pages 2 0 R >>",
                       "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                       "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Resources << >> /Contents 4 0 R "
                           + extras + " >>",
                       "<< /Length 0 >>\nstream\n\nendstream",
                       shared]
        var data = Data()
        func append(_ s: String) { data.append(s.data(using: .isoLatin1)!) }
        append("%PDF-1.7\n")
        var offsets: [Int] = []
        for (i, o) in objects.enumerated() { offsets.append(data.count); append("\(i + 1) 0 obj\n\(o)\nendobj\n") }
        let xref = data.count
        append("xref\n0 \(objects.count + 1)\n0000000000 65535 f \n")
        offsets.forEach { append(String(format: "%010d 00000 n \n", $0)) }
        append("trailer\n<< /Size \(objects.count + 1) /Root 1 0 R >>\nstartxref\n\(xref)\n%%EOF\n")
        let provider = try XCTUnwrap(CGDataProvider(data: data as CFData))
        return try XCTUnwrap(CGPDFDocument(provider))
    }

    private let utm10 = "/Projection << /ProjectionType (UT) /Zone 10 /Hemisphere (N) /Datum (WE) >>"

    func testRegistrationRowsPointingAtOneHugeArrayAreRefusedBeforeReading() throws {
        // ~70 KB of PDF that used to make the reader copy 4096 x 8192 doubles (256 MiB) per entry
        let rows = String(repeating: "5 0 R ", count: 4096)
        let entry = "<< /Description (Layers) /Registration [\(rows)] \(utm10) >>"
        let doc = try hostilePDF(entries: String(repeating: entry + " ", count: 8), sharedCount: 8192)
        let start = Date()
        let readout = try XCTUnwrap(GeoPDFReader.read(document: doc))
        XCTAssertEqual(readout.outcome, .rejected(.malformed), "a registration row is [x y X Y], nothing else")
        XCTAssertLessThan(Date().timeIntervalSince(start), 2, "row length has to be checked before any value is read")

        // too many rows is malformed too, even when each one is a fine 4-tuple
        let manyRows = String(repeating: "[1 2 3 4] ", count: PdfGeorefBuilder.maximumRegistrationRows + 1)
        let many = try hostilePDF(entries: "<< /Description (Layers) /Registration [\(manyRows)] \(utm10) >>", sharedCount: 4)
        XCTAssertEqual(GeoPDFReader.read(document: many)?.outcome, .rejected(.malformed))
    }

    func testOnePageCanOnlyMakeTheReaderPullSoManyNumbers() throws {
        // 64 entries whose neatline is the same 8192 number array: the page budget
        // runs out after a handful and the rest read as malformed instead of copied
        let entry = "<< /Description (Inset) /CTM [1 0 0 1 0 0] /Neatline 5 0 R \(utm10) >>"
        let doc = try hostilePDF(entries: String(repeating: entry + " ", count: PdfGeorefBuilder.maximumEntries),
                                 sharedCount: 8192)
        let pageDict = try XCTUnwrap(doc.page(at: 1)?.dictionary)
        guard case .list(let entries)? = GeoPDFReader.extractLgiEntries(in: pageDict) else {
            return XCTFail("64 dict entries should come back as a list")
        }
        XCTAssertEqual(entries.count, PdfGeorefBuilder.maximumEntries)
        let read = entries.filter { $0.neatline.values != nil }.count
        XCTAssertGreaterThan(read, 0)
        XCTAssertLessThanOrEqual(read * 8192, GeoPDFReader.ReadBudget.maximumValuesPerPage)
        XCTAssertEqual(entries.last?.neatline, .malformed)
        if case .georeferenced = GeoPDFReader.read(document: doc)?.outcome { XCTFail("all-zero neatlines aren't a map") }

        let budget = GeoPDFReader.ReadBudget(10)
        XCTAssertTrue(budget.take(6))
        XCTAssertFalse(budget.take(6))
        XCTAssertFalse(budget.take(1), "once blown it stays blown")
    }

    /// a fine geographic viewport over the whole page, exact fit
    private let goodViewport = "<< /Type /Viewport /Name (Map) /BBox [0 0 600 800] /Measure << /Type /Measure "
        + "/Subtype /GEO /LPTS [0 0 1 0 1 1 0 1] /GPTS [37 -122.1 37 -122 37.1 -122 37.1 -122.1] "
        + "/GCS << /Type /GEOGCS /WKT (GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,"
        + "298.257223563]],PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]]) >> >> >>"

    func testRunningOutOfBudgetInTheVPRejectsTheWholeVP() throws {
        // control: the good viewport on its own places the page
        let alone = try hostilePDF(viewports: goodViewport, sharedCount: 4)
        guard case .georeferenced? = GeoPDFReader.read(document: alone)?.outcome else {
            return XCTFail("the control viewport has to work")
        }
        // the same viewport first, then tiny ones whose GPTS/LPTS are the shared 8192
        // array. It used to win (biggest BBox, read before the cut), now the
        // whole /VP is junk like on Android (lgiRules.pageBudget)
        let junk = "<< /Type /Viewport /BBox [0 0 10 10] /Measure << /Type /Measure /Subtype /GEO "
            + "/LPTS 5 0 R /GPTS 5 0 R >> >>"
        let vps = goodViewport + " " + String(repeating: junk + " ", count: 8)
        let doc = try hostilePDF(entries: "<< /Description (Layers) /CTM [1 0 0 1 0 0] \(utm10) >>",
                                 viewports: vps, sharedCount: 8192)
        XCTAssertEqual(GeoPDFReader.read(document: doc)?.outcome, .rejected(.malformed))
    }

    func testRunningOutOfBudgetInTheLgiDictRejectsIt() throws {
        // Layers entry is fine and would be picked, but the insets after it blow the budget
        let layers = "<< /Description (Layers) /CTM [10 0 0 10 500000 4000000] "
            + "/Neatline [0 0 600 0 600 800 0 800] \(utm10) >>"
        let ok = try hostilePDF(entries: layers, sharedCount: 4)
        guard case .georeferenced? = GeoPDFReader.read(document: ok)?.outcome else {
            return XCTFail("the control entry has to work")
        }
        let inset = "<< /Description (Inset) /CTM [1 0 0 1 0 0] /Neatline 5 0 R \(utm10) >>"
        let doc = try hostilePDF(entries: layers + " " + String(repeating: inset + " ", count: 9), sharedCount: 8192)
        XCTAssertEqual(GeoPDFReader.read(document: doc)?.outcome, .rejected(.malformed))
        // a /VP read in full before the LGIDict blew it still stands
        let both = try hostilePDF(entries: String(repeating: inset + " ", count: 9), viewports: goodViewport,
                                  sharedCount: 8192)
        guard case .georeferenced(let g)? = GeoPDFReader.read(document: both)?.outcome else {
            return XCTFail("the /VP was read before the budget ran out")
        }
        XCTAssertEqual(g.origin, .adobeVP)
    }

    func testHugeOrNonFiniteUtmZoneIsMalformedNotATrap() throws {
        // these all used to reach Int(zone) and kill the app, on import and on every launch after
        for zone in ["(1e30)", "(inf)", "(infinity)", "(-1e300)", "1000000000000000000000000000000.0",
                     "9223372036854775808.0", "(nan)", "/10", "10.5", "0", "61"] {
            let doc = try hostilePDF(entries: "<< /Description (Layers) /CTM [10 0 0 10 500000 4000000] "
                                        + "/Projection << /ProjectionType (UT) /Zone \(zone) /Hemisphere (N) /Datum (WGE) >> >>",
                                     sharedCount: 4)
            XCTAssertEqual(GeoPDFReader.read(document: doc)?.outcome, .rejected(.malformed), zone)
        }
        // same straight into the builder, values a pdf can't even carry included
        for v: LgiValue in [.number(.infinity), .number(-.infinity), .number(.nan), .number(1e300),
                            .text("1e30"), .text("Infinity"), .name("10")] {
            let r = PdfGeorefBuilder.lgiCrs(["ProjectionType": .text("UT"), "Zone": v, "Hemisphere": .text("N"),
                                             "Datum": .text("WGE")], display: nil)
            guard case .failure(.malformed) = r else { return XCTFail("\(v) should be malformed, got \(r)") }
        }
        // the decoded-georef side of it: junk lon0 is just not a UTM zone
        XCTAssertNil(GeoCrs.transverseMercator(lat0: 0, lon0: 1e300, k0: 0.9996, fe: 500_000, fn: 0).utmZone)
        XCTAssertNil(GeoCrs.transverseMercator(lat0: 0, lon0: .infinity, k0: 0.9996, fe: 500_000, fn: 0).utmZone)
        XCTAssertEqual(GeoCrs.utm(zone: 33, south: true).utmZone?.zone, 33)
        XCTAssertEqual(FiduciaryFitter.standardZone(lon: 1e300), 60)
        XCTAssertEqual(FiduciaryFitter.standardZone(lon: -1e300), 1)
        XCTAssertEqual(FiduciaryFitter.standardZone(lon: .nan), 1)
        XCTAssertEqual(FiduciaryFitter.standardZone(lon: 151.2), 56)
    }

    func testDeclaredButEmptyLgiDictIsNotAPlainPDF() throws {
        for extras in ["/LGIDict []", "/LGIDict 5", "/LGIDict [5 (junk)]"] {
            let url = try writePDF(media: "0 0 600 800", extras: extras)
            XCTAssertEqual(GeoPDFReader.read(url: url)?.outcome, .rejected(.malformed), extras)
        }
    }
}
