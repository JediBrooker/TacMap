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

    /// a georef whose crop is the whole box, just to drive the renderer
    private func boxGeoref(_ box: CGRect, crop: [PdfPagePoint]? = nil) throws -> PdfGeoreference {
        var g = try XCTUnwrap(PdfGeoreference.provisional(pageBox: box, rotation: 0,
                                                          centredOn: CLLocationCoordinate2D(latitude: 37.77, longitude: -122.42)))
        if let crop { g.crop = crop }
        return g
    }

    func testRendererDrawsRawUserSpaceForOffsetBoxesAndRotatedPages() throws {
        PDFTileRenderer.assertsOffMainThread = false
        defer { PDFTileRenderer.assertsOffMainThread = true }
        // black 20 pt square at raw (200..220, 300..320) whatever the boxes/rotate say
        let square = "0 0 0 rg 200 300 20 20 re f"
        for extras in ["", "/Rotate 90", "/Rotate 270", "/Rotate 180 /CropBox [120 170 480 630]"] {
            let url = try writePDF(media: "100 150 500 650", extras: extras, content: square)
            let (_, page) = try PDFTileRenderer.openPage(url: url, pageIndex: 0)
            let region = CGRect(x: 100, y: 150, width: 400, height: 500)
            let fp = try PDFFootprint(georef: try boxGeoref(region), pageBox: PDFTileRenderer.pageBox(page))
            let image = try XCTUnwrap(try PDFTileRenderer.renderRegion(page: page, region: region, width: 400, height: 500,
                                                                         footprint: fp).makeImage(), extras)
            XCTAssertEqual(image.width, 400)
            let box = try XCTUnwrap(darkBox(image), extras)
            // pixel x = raw x - 100, pixel row (from top) = 650 - raw y
            XCTAssertEqual(Double(box.minX), 100, accuracy: 1, "\(extras) x")
            XCTAssertEqual(Double(box.maxX), 120, accuracy: 1, "\(extras) x")
            XCTAssertEqual(Double(box.minY), 330, accuracy: 1, "\(extras) y")
            XCTAssertEqual(Double(box.maxY), 350, accuracy: 1, "\(extras) y")
        }
    }

    func testRendererLeavesEverythingOutsideTheCropPolygonClear() throws {
        PDFTileRenderer.assertsOffMainThread = false
        defer { PDFTileRenderer.assertsOffMainThread = true }
        let url = try writePDF(media: "0 0 400 400", content: "0 0 0 rg 0 0 400 400 re f")
        let (_, page) = try PDFTileRenderer.openPage(url: url, pageIndex: 0)
        let triangle = [PdfPagePoint(x: 0, y: 0), PdfPagePoint(x: 400, y: 0), PdfPagePoint(x: 0, y: 400)]
        let region = CGRect(x: 0, y: 0, width: 400, height: 400)
        let fp = try PDFFootprint(georef: try boxGeoref(region, crop: triangle), pageBox: PDFTileRenderer.pageBox(page))
        let image = try XCTUnwrap(try PDFTileRenderer.renderRegion(page: page, region: region, width: 400, height: 400,
                                                                     footprint: fp).makeImage())
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

    func testTileWarpDrawsThePageWhereTheGeorefPutsIt() throws {
        PDFTileRenderer.assertsOffMainThread = false
        defer { PDFTileRenderer.assertsOffMainThread = true }
        let square = "0 0 0 rg 200 300 20 20 re f"
        for extras in ["", "/Rotate 90"] {
            let url = try writePDF(media: "100 150 500 650", extras: extras, content: square)
            let (_, page) = try PDFTileRenderer.openPage(url: url, pageIndex: 0)
            let georef = try boxGeoref(CGRect(x: 100, y: 150, width: 400, height: 500))
            let fp = try PDFFootprint(georef: georef, pageBox: PDFTileRenderer.pageBox(page))
            let centre = try XCTUnwrap(georef.toWGS84(x: 210, y: 310))
            let z = 14
            let tx = Int(WebMercatorTiles.lonToTileX(centre.longitude, z))
            let ty = Int(WebMercatorTiles.latToTileY(centre.latitude, z))
            let job = TileJob(z: z, x0: tx, y0: ty, cols: 1, rows: 1)
            let plan = PDFTileWarp.plan(job: job, tilePx: 256, footprint: fp, georef: georef)
            let out = try PDFTileRenderer.renderJob(job, tilePx: 256, plan: plan, footprint: fp, source: .vector(page))
            guard case .image(let tile)? = out[TileIndex(z: z, x: tx, y: ty)] else { return XCTFail("\(extras) no tile") }
            let box = try XCTUnwrap(darkBox(tile), extras)
            // where the square's centre should be in tile pixels (y down)
            let fx = WebMercatorTiles.lonToTileX(centre.longitude, z) - Double(tx)
            let fy = WebMercatorTiles.latToTileY(centre.latitude, z) - Double(ty)
            XCTAssertEqual(Double(box.midX), fx * 256, accuracy: 1.5, "\(extras) tile x")
            XCTAssertEqual(Double(box.midY), fy * 256, accuracy: 1.5, "\(extras) tile y")
        }
    }

    func testBakeRefusesAnUncalibratedPlacement() throws {
        let url = try writePDF(media: "0 0 400 400")
        let georef = try XCTUnwrap(PdfGeoreference.provisional(pageBox: CGRect(x: 0, y: 0, width: 400, height: 400),
                                                               rotation: 0, centredOn: CLLocationCoordinate2D(latitude: -35, longitude: 149)))
        let source = PDFMapSource(url: url, georef: georef)
        XCTAssertTrue(source.isUncalibrated)
        let bake = PDFBakeController()
        bake.prepare(pdf: source, runtime: nil)
        XCTAssertEqual(bake.state, .failed(.notCalibrated))
    }

    // MARK: - import outcomes (no silent camera box)

    /// WP5: the import pipeline inspects the PDF once and ImportDecision keeps
    /// georeferenced, plain and declared-but-refused apart (no camera box)
    func testImportWorkerKeepsGeorefPlainAndRejectedApart() async throws {
        let original = ImportedMapStorage.applicationSupportProvider
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("georef-import-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        ImportedMapStorage.applicationSupportProvider = { root }
        scratch.append(root)
        defer { ImportedMapStorage.applicationSupportProvider = original }
        func prepare(_ url: URL) async throws -> PreparedPDFImport {
            try await MapImportPipeline.preparePDF(url: url, entryCount: 0, libraryLoaded: true,
                                                   isCancelled: { false }, progress: { _ in })
        }
        func decide(_ p: PreparedPDFImport) -> ImportDecision.Outcome {
            ImportDecision.decide(pageCount: p.inspection.pageCount, pages: p.inspection.scanned.map(\.decisionState),
                                  duplicate: nil)
        }

        let geo = try await prepare(try testdata("geopdf/tacmap_grid_sf_iso.pdf"))
        let g = try XCTUnwrap(geo.inspection.page(0)?.georef)
        XCTAssertEqual(g.origin, .adobeVP)
        XCTAssertEqual(g.crs.utmZone?.zone, 10)
        XCTAssertEqual(decide(geo).action, .addAndActivate)
        XCTAssertEqual(ImportedMapStates.state(try XCTUnwrap(geo.entry(pageIndex: 0)), file: .ok), .geoPDF)

        let plain = try await prepare(try testdata("geopdf/tacmap_grid_sf_plain.pdf"))
        XCTAssertNil(plain.inspection.page(0)?.georef)
        XCTAssertNil(plain.inspection.page(0)?.issue)
        XCTAssertEqual(decide(plain).action, .addAndCalibrate)

        // the real USGS LPTS pattern with one value pushed to 1.6: declared, refused, explained.
        // something on the page, a blank page now fails the import probe first
        let refused = try writePDF(media: "0 0 1728 2088", extras: """
        /VP [<< /Type /Viewport /BBox [0 2088 1727.95998 56.69373] /Measure << /Type /Measure /Subtype /GEO
        /GPTS [37.73318 -122.52121 37.88898 -122.52021 37.88818 -122.35264 37.73238 -122.354]
        /LPTS [-0.00708 1 0 -0.00514 1.6 0 1 1.00514] >> >>]
        """, content: "0 0 0 rg 100 100 400 400 re f")
        let rejected = try await prepare(refused)
        XCTAssertEqual(rejected.inspection.page(0)?.issue, .lptsOutOfRange)
        let outcome = decide(rejected)
        XCTAssertEqual(outcome.action, .addRejected, "a refused georef must not turn into a camera-centred placement")
        let entry = try XCTUnwrap(rejected.entry(pageIndex: 0))
        XCTAssertEqual(ImportedMapStates.state(entry, file: .ok), .rejected)
        XCTAssertNil(entry.pdf?.embedded)
        XCTAssertFalse(PdfGeorefRejectReason.lptsOutOfRange.displayReason.isEmpty)
        XCTAssertFalse(try XCTUnwrap(outcome.alert).message.text.isEmpty)
    }

    func testRotatedGeoPDFImportsAndPlacesInRawPageSpace() throws {
        // rot90_iso: /Rotate 90 page, georef in raw (unrotated) user space
        let g = try XCTUnwrap(GeoPDFReader.read(url: try testdata("geopdf/tacmap_grid_rot90_iso.pdf"))?.georef)
        let corner = try XCTUnwrap(g.toWGS84(x: 979.0866142, y: 72))
        XCTAssertEqual(corner.latitude, 37.73011820966, accuracy: 1e-7)
        XCTAssertEqual(corner.longitude, -122.47797518349, accuracy: 1e-7)
        let source = PDFMapSource(url: try testdata("geopdf/tacmap_grid_rot90_iso.pdf"), georef: g)
        XCTAssertEqual(source.pdfRenderRect, g.cropBoundingRect)
        XCTAssertFalse(try renderPDFBaseRaster(source).blank)
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

    /// The crosshair flow end to end on the sf plain sheet: each ring target
    /// captured through the displayed georef (provisional first, then the live
    /// fit), typed as MGRS, fitted in UTM of the first point.
    func testCalibrationFinishFitsInUTMOfTheFirstPoint() throws {
        let url = try testdata("geopdf/tacmap_grid_sf_plain.pdf")
        let readout = try XCTUnwrap(GeoPDFReader.read(url: url))
        XCTAssertEqual(readout.outcome, .notGeoreferenced)
        let provisional = try XCTUnwrap(PdfGeoreference.provisional(
            pageBox: readout.page.cropBox, rotation: readout.page.rotation,
            centredOn: CLLocationCoordinate2D(latitude: -35, longitude: 149)))
        let target = CalibrationTarget(entryID: UUID(), contentKey: try XCTUnwrap(PDFSessionStore.contentKey(for: url)),
                                       pageIndex: 0, pageBox: CalibrationTarget.box(readout.page.cropBox),
                                       rotate: readout.page.rotation)
        let session = CalibrationSession(drafts: InMemoryCalibrationDraftStore())
        session.start(target: target, entryName: "sf", base: provisional, seed: CalibrationState(),
                      embeddedDatumID: nil, wasPreview: true)
        XCTAssertEqual(session.phase, .datumSheet, "plain PDF, no datum yet")
        session.chooseDatum("WGS84")
        // the sheet's ring targets (testdata/pdf_georef.json sf_plain truth)
        let targets: [(PdfPagePoint, String)] = [
            (PdfPagePoint(x: 185.3858268, y: 185.3858268), "10SEG 47000 77000"),
            (PdfPagePoint(x: 638.9291339, y: 185.3858268), "10SEG 51000 77000"),
            (PdfPagePoint(x: 638.9291339, y: 865.7007874), "10SEG 51000 83000"),
            (PdfPagePoint(x: 185.3858268, y: 865.7007874), "10SEG 47000 83000"),
        ]
        for (page, ref) in targets {
            // put the camera right on the target through whatever is displayed now
            let d = try XCTUnwrap(session.display)
            let centre = try XCTUnwrap(d.georef.toWGS84(x: page.x, y: page.y))
            let camera = MapCamera(center: centre, zoom: 17, headingDegrees: 0, viewportSize: CGSize(width: 390, height: 844))
            let capture = CalibrationCapture.capture(georef: d.georef, pageBox: target.pageBox, camera: camera,
                                                     generation: d.generation)
            XCTAssertEqual(session.beginAdd(capture: capture), .ok)
            session.entryText = ref
            XCTAssertTrue(session.commitEntry(), ref)
        }
        let manual = try XCTUnwrap(session.finishTapped(), "4 good points spread over the sheet are ready")
        let g = manual.georef
        XCTAssertEqual(g.origin, .fiduciaries)
        XCTAssertEqual(g.crs.utmZone?.zone, 10)
        XCTAssertEqual(g.crs.utmZone?.south, false)
        XCTAssertEqual(g.fit?.crossValidated, true)
        XCTAssertLessThan(try XCTUnwrap(manual.rmsM), 0.01)
        XCTAssertEqual(manual.grade, .good)
        // a printed grid intersection the user never touched: 548000E 4180000N
        let w = try XCTUnwrap(g.toWGS84(x: 298.7716535, y: 525.5433071))
        XCTAssertEqual(w.latitude, 37.76606703301, accuracy: 2e-7)
        XCTAssertEqual(w.longitude, -122.45501505078, accuracy: 2e-7)

        let source = PDFMapSource(url: url, georef: g)
        XCTAssertFalse(source.isUncalibrated)
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
