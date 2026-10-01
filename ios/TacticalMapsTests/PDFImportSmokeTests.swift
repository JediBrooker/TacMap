import Combine
import CoreLocation
import MapKit
import PDFKit
import UIKit
import XCTest
@testable import TacticalMaps

final class PDFImportSmokeTests: XCTestCase {
    func testGeneratedPDFImportsAndRendersAsMapSource() throws {
        let fixture = FileManager.default.temporaryDirectory
            .appendingPathComponent("tacmap-pdf-smoke-\(UUID().uuidString).pdf")
        try makePDF(at: fixture)
        defer { try? FileManager.default.removeItem(at: fixture) }

        let imported = try PDFMapImporter.copyAndValidate(fixture)
        defer { try? FileManager.default.removeItem(at: imported) }

        let camera = CLLocationCoordinate2D(latitude: -35.2809, longitude: 149.1300)
        guard case .source(let source)? = PDFMapImporter.placement(for: imported, cameraCentre: camera) else {
            return XCTFail("a plain PDF still gets a (provisional) placement for calibration")
        }
        XCTAssertTrue(source.isUncalibrated, "no georef means uncalibrated, never a trusted basemap")

        XCTAssertTrue(FileManager.default.fileExists(atPath: source.url.path))
        XCTAssertEqual(source.displayName, imported.deletingPathExtension().lastPathComponent)
        XCTAssertNotNil(source.bounds)
        XCTAssertNotNil(source.coverage)

        let mapViewModel = MapViewModel()
        var framedRegion: MKCoordinateRegion?
        let cameraRequest = mapViewModel.cameraRequests.sink { region in
            framedRegion = region
        }
        mapViewModel.frameCamera(for: source, userLocation: nil)
        withExtendedLifetime(cameraRequest) {}

        let frame = try XCTUnwrap(framedRegion)
        XCTAssertEqual(frame.center.latitude, camera.latitude, accuracy: 0.001)
        XCTAssertEqual(frame.center.longitude, camera.longitude, accuracy: 0.001)

        // drawn by the tile renderer now: base raster of the whole page,
        // capped at 4x for a small page and inside the 6 Mpx budget
        let raster = try renderPDFBaseRaster(source)
        XCTAssertEqual(raster.levels[0].width, 800)
        XCTAssertEqual(raster.levels[0].height, 1200)
        XCTAssertLessThanOrEqual(raster.levels[0].width * raster.levels[0].height, PDFTileConstants.baseBudgetPx)
    }

    func testInvalidPDFIsRejectedAndRemoved() throws {
        let fixture = FileManager.default.temporaryDirectory
            .appendingPathComponent("tacmap-invalid-pdf-\(UUID().uuidString).pdf")
        try Data("not a pdf".utf8).write(to: fixture)
        defer { try? FileManager.default.removeItem(at: fixture) }

        XCTAssertThrowsError(try PDFMapImporter.copyAndValidate(fixture)) { error in
            XCTAssertEqual(error as? PDFMapImportError, .invalidPDF)
        }
    }

    func testValidPDFWithUntrustedSourceExtensionUsesManagedPDFExtension() throws {
        let fixture = FileManager.default.temporaryDirectory
            .appendingPathComponent("tacmap-pdf-disguised-\(UUID().uuidString).payload")
        try makePDF(at: fixture)
        defer { try? FileManager.default.removeItem(at: fixture) }

        let imported = try PDFMapImporter.copyAndValidate(fixture)
        defer { try? FileManager.default.removeItem(at: imported) }

        XCTAssertEqual(imported.pathExtension.lowercased(), "pdf")
        XCTAssertNotNil(PDFDocument(url: imported))
    }

    func testProvisionalPlacementSanitisesInvalidPolarAndAntimeridianCameras() throws {
        // replaces the old camera-box fallback: same junk cameras, the
        // provisional georef has to stay on the earth and drawable
        let cases = [
            CLLocationCoordinate2D(latitude: .nan, longitude: .infinity),
            CLLocationCoordinate2D(latitude: 90, longitude: 180),
            CLLocationCoordinate2D(latitude: -90, longitude: -180),
            CLLocationCoordinate2D(latitude: 0, longitude: 721),
        ]
        for camera in cases {
            for rotation in [0, 90, 180, 270] {
                let g = try XCTUnwrap(PdfGeoreference.provisional(
                    pageBox: CGRect(x: 0, y: 0, width: 600, height: 400), rotation: rotation, centredOn: camera))
                XCTAssertEqual(g.origin, .provisional)
                let bounds = try XCTUnwrap(g.wgs84Bounds())
                XCTAssertTrue(isValidEarthCoordinate(bounds.southWest))
                XCTAssertTrue(isValidEarthCoordinate(bounds.northEast))
                XCTAssertLessThan(bounds.southWest.latitude, bounds.northEast.latitude)
                XCTAssertLessThan(bounds.southWest.longitude, bounds.northEast.longitude)
            }
        }
    }

    func testProvisionalPlacementIsNominalScaleNorthUpAsViewed() throws {
        let camera = CLLocationCoordinate2D(latitude: -35, longitude: 149)
        let box = CGRect(x: 100, y: 150, width: 600, height: 400)
        // /Rotate 90: the viewer's "up" is raw -x, so raw -x has to head north
        let g = try XCTUnwrap(PdfGeoreference.provisional(pageBox: box, rotation: 90, centredOn: camera))
        let centre = try XCTUnwrap(g.toWGS84(x: Double(box.midX), y: Double(box.midY)))
        XCTAssertEqual(centre.latitude, camera.latitude, accuracy: 1e-9)
        XCTAssertEqual(centre.longitude, camera.longitude, accuracy: 1e-9)
        let up = try XCTUnwrap(g.toWGS84(x: Double(box.midX) - 100, y: Double(box.midY)))
        XCTAssertGreaterThan(up.latitude, centre.latitude)
        XCTAssertEqual(up.longitude, centre.longitude, accuracy: 1e-9)
        // 100 pt at 1:50,000 is ~1.76 km
        let metresPerDegLat = PdfGeoreference.metresPerUnit(crs: .geographic, ellipsoid: .wgs84,
                                                            latitude: camera.latitude).north
        XCTAssertEqual((up.latitude - centre.latitude) * metresPerDegLat, 1763.9, accuracy: 0.5)
    }

    func testPasswordProtectedPDFIsRejectedBeforeMapSourceCreation() throws {
        let fixture = FileManager.default.temporaryDirectory
            .appendingPathComponent("tacmap-protected-pdf-\(UUID().uuidString).pdf")
        try makePasswordProtectedPDF(at: fixture)
        defer { try? FileManager.default.removeItem(at: fixture) }

        XCTAssertThrowsError(try PDFMapImporter.copyAndValidate(fixture)) { error in
            XCTAssertEqual(error as? PDFMapImportError, .invalidPDF)
        }
    }

    func testRightAnglePageRotationsImportAndRenderOnIOS() throws {
        for rotation in [90, 180, 270] {
            let fixture = FileManager.default.temporaryDirectory
                .appendingPathComponent("tacmap-rotated-\(rotation)-\(UUID().uuidString).pdf")
            try makeRawPDF(at: fixture, pageExtras: "/Rotate \(rotation)")
            defer { try? FileManager.default.removeItem(at: fixture) }
            let imported = try PDFMapImporter.copyAndValidate(fixture)
            defer { try? FileManager.default.removeItem(at: imported) }
            guard case .source(let source)? = PDFMapImporter.placement(
                for: imported,
                cameraCentre: CLLocationCoordinate2D(latitude: -34, longitude: 150)
            ) else { return XCTFail("rotated plain page should still import") }

            let raster = try renderPDFBaseRaster(source).levels[0]
            XCTAssertGreaterThan(raster.width, 0)
            XCTAssertGreaterThan(raster.height, 0)
            XCTAssertLessThanOrEqual(raster.width * raster.height, PDFTileConstants.baseBudgetPx)
        }
    }

    func testPDFWorkerCopiesAndPreflightsOffMainThread() async throws {
        let fixture = FileManager.default.temporaryDirectory
            .appendingPathComponent("tacmap-pdf-worker-\(UUID().uuidString).pdf")
        try makePDF(at: fixture)
        defer { try? FileManager.default.removeItem(at: fixture) }

        let payload = try await ImportedMapWorker.preparePDF(url: fixture)
        defer { try? FileManager.default.removeItem(at: payload.destination) }
        XCTAssertTrue(payload.performedWorkOffMainThread)
        XCTAssertTrue(FileManager.default.fileExists(atPath: payload.destination.path))
        XCTAssertEqual(payload.mediaBox.width, 200, accuracy: 0.01)
        XCTAssertEqual(payload.mediaBox.height, 300, accuracy: 0.01)
    }

    func testGeneratedAdobeViewportUsesLargestGeospatialMapBody() throws {
        let fixture = FileManager.default.temporaryDirectory
            .appendingPathComponent("tacmap-adobe-geopdf-\(UUID().uuidString).pdf")
        defer { try? FileManager.default.removeItem(at: fixture) }
        let inset = adobeViewport(
            bbox: "0 0 50 50",
            gpts: "10 10 10 11 11 11 11 10"
        )
        let mapBody = adobeViewport(
            bbox: "0 0 600 400",
            gpts: "-34 150 -34 151 -33 151 -33 150"
        )
        try makeRawPDF(at: fixture, pageExtras: "/VP [\(inset) \(mapBody)]")

        let readout = try XCTUnwrap(GeoPDFReader.read(url: fixture))
        XCTAssertEqual(readout.selection?.index, 1, "the map body, not the inset")
        try assertOneDegreeBody(readout)
    }

    /// The map body GPTS (-34..-33, 150..151) with no /GCS: local TM fallback,
    /// so the corners land within the TM-vs-lat/lon-box residual (~270 m).
    private func assertOneDegreeBody(_ readout: GeoPDFReader.Readout,
                                     file: StaticString = #filePath, line: UInt = #line) throws {
        let g = try XCTUnwrap(readout.georef, "\(readout.outcome)", file: file, line: line)
        XCTAssertEqual(g.cropBoundingRect, CGRect(x: 0, y: 0, width: 600, height: 400), file: file, line: line)
        let sw = try XCTUnwrap(g.toWGS84(x: 0, y: 0), file: file, line: line)
        let ne = try XCTUnwrap(g.toWGS84(x: 600, y: 400), file: file, line: line)
        XCTAssertEqual(sw.latitude, -34, accuracy: 0.005, file: file, line: line)
        XCTAssertEqual(sw.longitude, 150, accuracy: 0.005, file: file, line: line)
        XCTAssertEqual(ne.latitude, -33, accuracy: 0.005, file: file, line: line)
        XCTAssertEqual(ne.longitude, 151, accuracy: 0.005, file: file, line: line)
        XCTAssertNotNil(GeoPDFReader.Bounds(georef: g)?.placementAffine, file: file, line: line)
    }

    func testCatalogAdobeViewportRemainsSupportedWhenPageHasNoViewport() throws {
        let fixture = FileManager.default.temporaryDirectory
            .appendingPathComponent("tacmap-adobe-catalog-geopdf-\(UUID().uuidString).pdf")
        defer { try? FileManager.default.removeItem(at: fixture) }
        let mapBody = adobeViewport(
            bbox: "0 0 600 400",
            gpts: "-34 150 -34 151 -33 151 -33 150"
        )
        try makeRawPDF(
            at: fixture,
            pageExtras: "",
            catalogExtras: "/VP [\(mapBody)]"
        )

        try assertOneDegreeBody(try XCTUnwrap(GeoPDFReader.read(url: fixture)))
    }

    /// Plan 02 s1: the largest viewport that yields a VALID georef wins, so a
    /// broken map body falls back to the inset (was: fail closed). The fixture
    /// flags this rule for review (largest_viewport_malformed_inset_valid).
    func testLargerMalformedDeclaredAdobeViewportFallsBackToLargestValidInset() throws {
        let validInset = adobeViewport(
            bbox: "0 0 50 50",
            gpts: "10 10 10 11 11 11 11 10"
        )
        let malformedMapBody = """
        << /BBox [0 0 600 400]
           /Measure << /Subtype /GEO
                       /GPTS [-34 150 -34 151 -33 151 -33 150]
                       /LPTS [0 0 1 0 1 1 0 1.1] >> >>
        """

        for (index, viewports) in [
            "\(validInset) \(malformedMapBody)",
            "\(malformedMapBody) \(validInset)",
        ].enumerated() {
            let fixture = FileManager.default.temporaryDirectory
                .appendingPathComponent("tacmap-adobe-dominant-malformed-\(index)-\(UUID().uuidString).pdf")
            try makeRawPDF(at: fixture, pageExtras: "/VP [\(viewports)]")
            let readout = try XCTUnwrap(GeoPDFReader.read(url: fixture))
            XCTAssertEqual(readout.selection?.index, index == 0 ? 0 : 1, "the valid inset is the only usable viewport")
            let g = try XCTUnwrap(readout.georef)
            XCTAssertEqual(g.cropBoundingRect, CGRect(x: 0, y: 0, width: 50, height: 50))
            let corner = try XCTUnwrap(g.toWGS84(x: 0, y: 0))
            XCTAssertEqual(corner.latitude, 10, accuracy: 0.005)
            // the body alone (LPTS 1.1 contradicts its GPTS) is refused loudly
            try makeRawPDF(at: fixture, pageExtras: "/VP [\(malformedMapBody)]")
            XCTAssertEqual(GeoPDFReader.read(url: fixture)?.outcome, .rejected(.rmsGate))
            try? FileManager.default.removeItem(at: fixture)
        }
    }

    func testSmallerMalformedDeclaredAdobeViewportDoesNotHideValidMapBody() throws {
        let malformedInset = """
        << /BBox [0 0 50 50]
           /Measure << /Subtype /GEO
                       /GPTS [10 10 10 11 11 11 11 10]
                       /LPTS [0 0 1 0 1 1 0 1.1] >> >>
        """
        let validMapBody = adobeViewport(
            bbox: "0 0 600 400",
            gpts: "-34 150 -34 151 -33 151 -33 150"
        )
        let fixture = FileManager.default.temporaryDirectory
            .appendingPathComponent("tacmap-adobe-small-malformed-\(UUID().uuidString).pdf")
        defer { try? FileManager.default.removeItem(at: fixture) }
        try makeRawPDF(at: fixture, pageExtras: "/VP [\(malformedInset) \(validMapBody)]")

        let readout = try XCTUnwrap(GeoPDFReader.read(url: fixture))
        XCTAssertEqual(readout.selection?.index, 1)
        try assertOneDegreeBody(readout)
    }

    func testGeneratedLegacyLGIDictProducesGeographicBoundsAndCrop() throws {
        let fixture = FileManager.default.temporaryDirectory
            .appendingPathComponent("tacmap-lgi-geopdf-\(UUID().uuidString).pdf")
        defer { try? FileManager.default.removeItem(at: fixture) }
        let lgi = """
        /LGIDict <<
          /Description (Layers)
          /CTM [0.001 0 0 0.001 150 -34]
          /Neatline [0 0 600 0 600 400 0 400]
          /Projection << /ProjectionType /LL >>
        >>
        """
        try makeRawPDF(at: fixture, pageExtras: lgi)

        let georef = try XCTUnwrap(GeoPDFReader.read(url: fixture)?.georef)
        XCTAssertEqual(georef.crs, .geographic)
        let bounds = try XCTUnwrap(GeoPDFReader.Bounds(georef: georef))
        XCTAssertEqual(bounds.southWest.latitude, -34, accuracy: 1e-9)
        XCTAssertEqual(bounds.southWest.longitude, 150, accuracy: 1e-9)
        XCTAssertEqual(bounds.northEast.latitude, -33.6, accuracy: 1e-9)
        XCTAssertEqual(bounds.northEast.longitude, 150.6, accuracy: 1e-9)
        XCTAssertEqual(bounds.pdfCropRect, CGRect(x: 0, y: 0, width: 600, height: 400))
        let affine = try XCTUnwrap(bounds.placementAffine)
        let southWest = affine.apply(CGPoint(x: 0, y: 0))
        let northEast = affine.apply(CGPoint(x: 600, y: 400))
        XCTAssertEqual(southWest.latitude, -34, accuracy: 1e-9)
        XCTAssertEqual(southWest.longitude, 150, accuracy: 1e-9)
        XCTAssertEqual(northEast.latitude, -33.6, accuracy: 1e-9)
        XCTAssertEqual(northEast.longitude, 150.6, accuracy: 1e-9)
    }

    func testGeneratedProjectedLGIDictProducesTrueUTMPlacement() throws {
        let fixture = FileManager.default.temporaryDirectory
            .appendingPathComponent("tacmap-lgi-utm-\(UUID().uuidString).pdf")
        defer { try? FileManager.default.removeItem(at: fixture) }
        let lgi = """
        /LGIDict <<
          /Description (Layers)
          /CTM [10 0 0 10 334000 6250000]
          /Neatline [0 0 600 0 600 400 0 400]
          /Projection << /ProjectionType /UT /Zone 56 /Hemisphere /S /Datum /WE >>
        >>
        """
        try makeRawPDF(at: fixture, pageExtras: lgi)

        let georef = try XCTUnwrap(GeoPDFReader.read(url: fixture)?.georef)
        // the CTM IS the page -> UTM map now, no lat/lon refit in between
        XCTAssertEqual(georef.affine, PlaneAffine(a: 10, b: 0, c: 334_000, d: 0, e: 10, f: 6_250_000))
        XCTAssertEqual(georef.crs.utmZone?.zone, 56)
        let expectedSouthWest = try XCTUnwrap(
            GeoCrs.utm(zone: 56, south: true).inverse(x: 334_000, y: 6_250_000, ellipsoid: .wgs84)
        )
        let expectedNorthEast = try XCTUnwrap(
            GeoCrs.utm(zone: 56, south: true).inverse(x: 340_000, y: 6_254_000, ellipsoid: .wgs84)
        )
        let placedSouthWest = try XCTUnwrap(georef.toWGS84(x: 0, y: 0))
        let placedNorthEast = try XCTUnwrap(georef.toWGS84(x: 600, y: 400))

        XCTAssertEqual(placedSouthWest.latitude, expectedSouthWest.lat, accuracy: 1e-12)
        XCTAssertEqual(placedSouthWest.longitude, expectedSouthWest.lon, accuracy: 1e-12)
        XCTAssertEqual(placedNorthEast.latitude, expectedNorthEast.lat, accuracy: 1e-12)
        XCTAssertEqual(placedNorthEast.longitude, expectedNorthEast.lon, accuracy: 1e-12)
        XCTAssertEqual(georef.cropBoundingRect, CGRect(x: 0, y: 0, width: 600, height: 400))
    }

    func testUnsupportedLGIDictProjectionDoesNotPublishApproximateBounds() throws {
        let fixture = FileManager.default.temporaryDirectory
            .appendingPathComponent("tacmap-lgi-unsupported-\(UUID().uuidString).pdf")
        defer { try? FileManager.default.removeItem(at: fixture) }
        let lgi = """
        /LGIDict <<
          /Description (Layers)
          /CTM [10 0 0 10 334000 6250000]
          /Neatline [0 0 600 0 600 400 0 400]
          /Projection << /ProjectionType /UNKNOWN >>
        >>
        """
        try makeRawPDF(at: fixture, pageExtras: lgi)

        guard case .rejected? = GeoPDFReader.read(url: fixture)?.outcome else {
            return XCTFail("a declared but unusable LGIDict must be a loud rejection")
        }
    }

    func testAdobeGeoMeasureFailsClosedOnMalformedControlMetadata() throws {
        let validGPTS = "-34 150 -34 151 -33 151 -33 150"
        let validLPTS = "0 0 1 0 1 1 0 1"
        let cases = [
            // /Subtype is mandatory and must be exactly /GEO.
            "/VP [<< /BBox [0 0 600 400] /Measure << /GPTS [\(validGPTS)] /LPTS [\(validLPTS)] >> >>]",
            "/VP [<< /BBox [0 0 600 400] /Measure << /Subtype /RL /GPTS [\(validGPTS)] /LPTS [\(validLPTS)] >> >>]",
            // GPTS must contain complete finite Earth-valid pairs.
            "/VP [<< /BBox [0 0 600 400] /Measure << /Subtype /GEO /GPTS [-34 150 -34 151] /LPTS [0 0 1 0] >> >>]",
            "/VP [<< /BBox [0 0 600 400] /Measure << /Subtype /GEO /GPTS [-34 150 -34 151 -33 151 -33] /LPTS [0 0 1 0 1 1 0] >> >>]",
            "/VP [<< /BBox [0 0 600 400] /Measure << /Subtype /GEO /GPTS [\(validGPTS) /Bad] /LPTS [\(validLPTS) 0] >> >>]",
            "/VP [<< /BBox [0 0 600 400] /Measure << /Subtype /GEO /GPTS [91 150 -34 151 -33 151 -33 150] /LPTS [\(validLPTS)] >> >>]",
            // GPTS/LPTS cardinality must agree and LPTS must stay normalised.
            "/VP [<< /BBox [0 0 600 400] /Measure << /Subtype /GEO /GPTS [\(validGPTS)] /LPTS [0 0 1 0 1 1] >> >>]",
            "/VP [<< /BBox [0 0 600 400] /Measure << /Subtype /GEO /GPTS [\(validGPTS)] /LPTS [0 0 1 0 1 1 0 1.1] >> >>]",
            "/VP [<< /BBox [0 0 600 400] /Measure << /Subtype /GEO /GPTS [\(validGPTS)] /LPTS [0 0 1 0 1 1 0 /Bad] >> >>]",
            // Explicit prime-meridian metadata may not silently become Greenwich.
            "/VP [<< /BBox [0 0 600 400] /Measure << /Subtype /GEO /GPTS [\(validGPTS)] /LPTS [\(validLPTS)] /GCS << /WKT (GEOGCS[\"x\",PRIMEM[\"bad\",not-a-number]]) >> >> >>]",
            "/VP [<< /BBox [0 0 600 400] /Measure << /Subtype /GEO /GPTS [\(validGPTS)] /LPTS [\(validLPTS)] /GCS /Malformed >> >>]",
            "/VP [<< /BBox [0 0 600 400] /Measure << /Subtype /GEO /GPTS [\(validGPTS)] /LPTS [\(validLPTS)] /GCS << /WKT /Malformed >> >> >>]",
        ]

        for (index, pageExtras) in cases.enumerated() {
            let fixture = FileManager.default.temporaryDirectory
                .appendingPathComponent("tacmap-adobe-malformed-\(index)-\(UUID().uuidString).pdf")
            try makeRawPDF(at: fixture, pageExtras: pageExtras)
            let outcome = GeoPDFReader.read(url: fixture)?.outcome
            if index < 2 {
                // no /Subtype /GEO = not a geo viewport at all, plain PDF
                XCTAssertEqual(outcome, .notGeoreferenced, "case \(index)")
            } else {
                guard case .rejected? = outcome else {
                    XCTFail("malformed Adobe case \(index) published a georef: \(String(describing: outcome))")
                    continue
                }
            }
            try? FileManager.default.removeItem(at: fixture)
        }
    }

    func testAdobeGeoMeasureSupportsPrimeMeridianExponentAndReversedBBoxAxis() throws {
        let fixture = FileManager.default.temporaryDirectory
            .appendingPathComponent("tacmap-adobe-prime-axis-\(UUID().uuidString).pdf")
        defer { try? FileManager.default.removeItem(at: fixture) }
        let viewport = """
        << /BBox [0 400 600 0]
           /Measure << /Subtype /GEO
                       /GPTS [-33 5 -33 6 -34 6 -34 5]
                       /LPTS [0 0 1 0 1 1 0 1]
                       /GCS << /WKT (GEOGCS["x",PRIMEM["offset",+1.45e2]]) >> >> >>
        """
        try makeRawPDF(at: fixture, pageExtras: "/VP [\(viewport)]")

        // GEOGCS with no DATUM is unreadable -> local TM fallback, but the
        // PRIMEM (+1.45e2) still moves the GPTS 145 deg east and the reversed
        // BBox y axis still maps LPTS y=0 to page y=400
        let readout = try XCTUnwrap(GeoPDFReader.read(url: fixture))
        try assertOneDegreeBody(readout)
        let g = try XCTUnwrap(readout.georef)
        XCTAssertTrue(g.datumAssumed)
        XCTAssertEqual(try XCTUnwrap(g.toWGS84(x: 0, y: 400)).latitude, -33, accuracy: 0.005)
    }

    func testGeoPDFMetadataEntryCountsAreBounded() throws {
        let viewport = adobeViewport(
            bbox: "0 0 600 400",
            gpts: "-34 150 -34 151 -33 151 -33 150"
        )
        let adobeFixture = FileManager.default.temporaryDirectory
            .appendingPathComponent("tacmap-adobe-entry-limit-\(UUID().uuidString).pdf")
        defer { try? FileManager.default.removeItem(at: adobeFixture) }
        try makeRawPDF(
            at: adobeFixture,
            pageExtras: "/VP [\(Array(repeating: viewport, count: 65).joined(separator: " "))]"
        )
        XCTAssertEqual(GeoPDFReader.read(url: adobeFixture)?.outcome, .rejected(.malformed))

        let lgiEntry = """
        << /Description (Layers) /CTM [0.001 0 0 0.001 150 -34]
           /Neatline [0 0 600 0 600 400 0 400]
           /Projection << /ProjectionType /LL >> >>
        """
        let lgiFixture = FileManager.default.temporaryDirectory
            .appendingPathComponent("tacmap-lgi-entry-limit-\(UUID().uuidString).pdf")
        defer { try? FileManager.default.removeItem(at: lgiFixture) }
        try makeRawPDF(
            at: lgiFixture,
            pageExtras: "/LGIDict [\(Array(repeating: lgiEntry, count: 65).joined(separator: " "))]"
        )
        XCTAssertEqual(GeoPDFReader.read(url: lgiFixture)?.outcome, .rejected(.malformed))
    }

    func testLGIDictFailsClosedOnIncompleteProjectionDatumAndNeatline() throws {
        let prefix = "/LGIDict << /Description (Layers) /CTM [10 0 0 10 334000 6250000]"
        let neatline = "/Neatline [0 0 600 0 600 400 0 400]"
        let cases = [
            // A CTM is not geographic without an explicit projection definition.
            "\(prefix) \(neatline) >>",
            "\(prefix) \(neatline) /Projection << /Datum /WE >> >>",
            // Projected definitions require an understood, complete datum.
            "\(prefix) \(neatline) /Projection << /ProjectionType /UT /Zone 56 /Hemisphere /S >> >>",
            "\(prefix) \(neatline) /Projection << /ProjectionType /UT /Zone 56 /Hemisphere /S /Datum /ZZ >> >>",
            "\(prefix) \(neatline) /Projection << /ProjectionType /UT /Zone 61 /Hemisphere /S /Datum /WE >> >>",
            "\(prefix) \(neatline) /Projection << /ProjectionType /UT /Zone 56 /Datum /WE >> >>",
            "\(prefix) \(neatline) /Projection << /ProjectionType /UT /Zone 56 /Hemisphere /X /Datum /WE >> >>",
            "\(prefix) \(neatline) /Projection << /ProjectionType /TC /CentralMeridian 153 /OriginLatitude 0 /FalseEasting 500000 /FalseNorthing 10000000 /Datum /WE >> >>",
            "\(prefix) \(neatline) /Projection << /ProjectionType /TC /CentralMeridian 153 /OriginLatitude 91 /FalseEasting 500000 /FalseNorthing 10000000 /ScaleFactor .9996 /Datum /WE >> >>",
            "\(prefix) \(neatline) /Projection << /ProjectionType /LC /OriginLatitude 0 /CentralMeridian 0 /FalseEasting 0 /FalseNorthing 0 /Datum /WE >> >>",
            "\(prefix) \(neatline) /Projection << /ProjectionType /LC /StandardParallelOne 90 /OriginLatitude 0 /CentralMeridian 0 /FalseEasting 0 /FalseNorthing 0 /Datum /WE >> >>",
            // Explicit structural/numeric corruption may not fall back to a valid prefix/media box.
            "/LGIDict << /Description (Layers) /CTM [10 0 0 10 334000 6250000 /Bad] \(neatline) /Projection << /ProjectionType /UT /Zone 56 /Hemisphere /S /Datum /WE >> >>",
            "\(prefix) /Neatline [0 0 600 0 600 400 0] /Projection << /ProjectionType /UT /Zone 56 /Hemisphere /S /Datum /WE >> >>",
            "\(prefix) /Neatline [0 0 600 0 600 400 0 /Bad] /Projection << /ProjectionType /UT /Zone 56 /Hemisphere /S /Datum /WE >> >>",
            "\(prefix) /Neatline [0 0 10000000000 0 10000000000 400 0 400] /Projection << /ProjectionType /UT /Zone 56 /Hemisphere /S /Datum /WE >> >>",
            // Inline datum objects require a safe ellipsoid and a complete shift
            // unless they are demonstrably modern WGS84.
            "\(prefix) \(neatline) /Projection << /ProjectionType /UT /Zone 56 /Hemisphere /S /Datum 42 >> >>",
            "\(prefix) \(neatline) /Projection << /ProjectionType /UT /Zone 56 /Hemisphere /S /Datum << >> >> >>",
            "\(prefix) \(neatline) /Projection << /ProjectionType /UT /Zone 56 /Hemisphere /S /Datum << /Ellipsoid << /SemiMajorAxis 1 /InvFlattening 298.3 >> >> >> >>",
            "\(prefix) \(neatline) /Projection << /ProjectionType /UT /Zone 56 /Hemisphere /S /Datum << /Ellipsoid << /SemiMajorAxis 6377563.396 /InvFlattening 299.3249646 >> >> >> >>",
            "\(prefix) \(neatline) /Projection << /ProjectionType /UT /Zone 56 /Hemisphere /S /Datum << /Ellipsoid << /SemiMajorAxis 6377563.396 /InvFlattening 299.3249646 >> /ToWGS84 << /dx 446.448 /dy -125.157 >> >> >> >>",
        ]

        for (index, pageExtras) in cases.enumerated() {
            let fixture = FileManager.default.temporaryDirectory
                .appendingPathComponent("tacmap-lgi-malformed-\(index)-\(UUID().uuidString).pdf")
            try makeRawPDF(at: fixture, pageExtras: pageExtras)
            guard case .rejected? = GeoPDFReader.read(url: fixture)?.outcome else {
                XCTFail("malformed LGIDict case \(index) published a georef")
                continue
            }
            try? FileManager.default.removeItem(at: fixture)
        }
    }

    func testCompleteTCAndLCCLGIDictMetadataRemainSupported() throws {
        let cases = [
            """
            /LGIDict << /Description (Layers) /CTM [10 0 0 10 334000 6250000]
              /Neatline [0 0 600 0 600 400 0 400]
              /Projection << /ProjectionType /TC /CentralMeridian 153 /OriginLatitude 0
                /FalseEasting 500000 /FalseNorthing 10000000 /ScaleFactor .9996 /Datum /WE >> >>
            """,
            """
            /LGIDict << /Description (Layers) /CTM [10 0 0 10 0 0]
              /Neatline [0 0 600 0 600 400 0 400]
              /Projection << /ProjectionType /LC /StandardParallelOne 20 /StandardParallelTwo 40
                /OriginLatitude 0 /CentralMeridian 0 /FalseEasting 0 /FalseNorthing 0 /Datum /WE >> >>
            """,
        ]

        for (index, pageExtras) in cases.enumerated() {
            let fixture = FileManager.default.temporaryDirectory
                .appendingPathComponent("tacmap-lgi-complete-\(index)-\(UUID().uuidString).pdf")
            try makeRawPDF(at: fixture, pageExtras: pageExtras)
            let georef = try XCTUnwrap(GeoPDFReader.read(url: fixture)?.georef, "complete projected case \(index) was rejected")
            XCTAssertNotNil(GeoPDFReader.Bounds(georef: georef)?.placementAffine)
            try? FileManager.default.removeItem(at: fixture)
        }
    }

    func testUTMProjectionUsesDeclaredSourceEllipsoid() throws {
        let wgs84 = try XCTUnwrap(
            GeoCrs.utm(zone: 56, south: true).inverse(x: 334_000, y: 6_250_000, ellipsoid: .wgs84)
        )
        let legacy = try XCTUnwrap(
            GeoCrs.utm(zone: 56, south: true).inverse(x: 334_000, y: 6_250_000, ellipsoid: .airy1830)
        )

        XCTAssertGreaterThan(abs(wgs84.lat - legacy.lat), 1e-7)
        XCTAssertGreaterThan(abs(wgs84.lon - legacy.lon), 1e-7)
    }

    private func makePDF(at url: URL) throws {
        let bounds = CGRect(x: 0, y: 0, width: 200, height: 300)
        let renderer = UIGraphicsPDFRenderer(bounds: bounds)
        try renderer.writePDF(to: url) { context in
            context.beginPage()
            UIColor.black.setFill()
            context.cgContext.fill(bounds)
            let text = "TacMap iOS PDF import smoke test"
            text.draw(
                at: CGPoint(x: 12, y: 18),
                withAttributes: [
                    .foregroundColor: UIColor.white,
                    .font: UIFont.systemFont(ofSize: 12),
                ]
            )
        }
    }

    private func makePasswordProtectedPDF(at url: URL) throws {
        guard let consumer = CGDataConsumer(url: url as CFURL) else {
            throw CocoaError(.fileWriteUnknown)
        }
        var mediaBox = CGRect(x: 0, y: 0, width: 200, height: 300)
        let options: [CFString: Any] = [
            kCGPDFContextUserPassword: "secret",
            kCGPDFContextOwnerPassword: "owner-secret",
            kCGPDFContextEncryptionKeyLength: 128,
        ]
        guard let context = CGContext(
            consumer: consumer,
            mediaBox: &mediaBox,
            options as CFDictionary
        ) else { throw CocoaError(.fileWriteUnknown) }
        context.beginPDFPage(nil)
        context.setFillColor(UIColor.black.cgColor)
        context.fill(mediaBox)
        context.endPDFPage()
        context.closePDF()
    }

    private func adobeViewport(bbox: String, gpts: String) -> String {
        """
        << /BBox [\(bbox)]
           /Measure << /Subtype /GEO
                       /GPTS [\(gpts)]
                       /LPTS [0 0 1 0 1 1 0 1] >> >>
        """
    }

    /// Small standards-compliant PDF writer used to exercise the actual
    /// CoreGraphics dictionary parser rather than a mocked metadata model.
    private func makeRawPDF(
        at url: URL,
        pageExtras: String,
        catalogExtras: String = ""
    ) throws {
        let objects = [
            "<< /Type /Catalog /Pages 2 0 R \(catalogExtras) >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            """
            << /Type /Page /Parent 2 0 R /MediaBox [0 0 600 400]
               /Resources << >> /Contents 4 0 R \(pageExtras) >>
            """,
            "<< /Length 0 >>\nstream\n\nendstream",
        ]
        var data = Data()
        func append(_ text: String) { data.append(contentsOf: text.utf8) }
        append("%PDF-1.7\n")
        var offsets = [Int]()
        for (index, object) in objects.enumerated() {
            offsets.append(data.count)
            append("\(index + 1) 0 obj\n\(object)\nendobj\n")
        }
        let xrefOffset = data.count
        append("xref\n0 \(objects.count + 1)\n")
        append("0000000000 65535 f \n")
        offsets.forEach { append(String(format: "%010d 00000 n \n", $0)) }
        append("trailer\n<< /Size \(objects.count + 1) /Root 1 0 R >>\n")
        append("startxref\n\(xrefOffset)\n%%EOF\n")
        try data.write(to: url, options: .atomic)
    }
}
