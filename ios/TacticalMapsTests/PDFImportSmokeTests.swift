import Combine
import CoreLocation
import MapKit
import PDFKit
import UIKit
import XCTest
@testable import TacticalMaps

final class PDFImportSmokeTests: XCTestCase {
    func testRasterSizingDoesNotUpscaleSmallPagesAndBoundsHugeAllocations() throws {
        XCTAssertEqual(
            boundedPDFRasterSize(width: 612, height: 792),
            PDFRasterSize(width: 612, height: 792)
        )
        let huge = try XCTUnwrap(boundedPDFRasterSize(width: 12_000, height: 6_000))
        XCTAssertLessThanOrEqual(huge.width, 4096)
        XCTAssertLessThanOrEqual(huge.height, 4096)
        XCTAssertLessThanOrEqual(huge.byteCount, 32 * 1024 * 1024)
        XCTAssertEqual(Double(huge.width) / Double(huge.height), 2, accuracy: 0.002)
        XCTAssertNil(boundedPDFRasterSize(width: .infinity, height: 100))
    }

    func testGeneratedPDFImportsAndRendersAsMapSource() throws {
        let fixture = FileManager.default.temporaryDirectory
            .appendingPathComponent("tacmap-pdf-smoke-\(UUID().uuidString).pdf")
        try makePDF(at: fixture)
        defer { try? FileManager.default.removeItem(at: fixture) }

        let imported = try PDFMapImporter.copyAndValidate(fixture)
        defer { try? FileManager.default.removeItem(at: imported) }

        let camera = CLLocationCoordinate2D(latitude: -35.2809, longitude: 149.1300)
        let source = PDFMapImporter.makeMapSource(from: imported, cameraCentre: camera)

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

        let image = try XCTUnwrap(source.renderedImage())
        XCTAssertGreaterThan(image.size.width, 0)
        XCTAssertGreaterThan(image.size.height, 0)
        let raster = try XCTUnwrap(image.cgImage)
        XCTAssertEqual(raster.width, 200, "small pages must not be device-scale upsampled")
        XCTAssertEqual(raster.height, 300, "small pages must not be device-scale upsampled")
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

    func testFallbackBoundsSanitiseInvalidPolarAndAntimeridianInputs() {
        let cases = [
            (CLLocationCoordinate2D(latitude: .nan, longitude: .infinity), Double.nan),
            (CLLocationCoordinate2D(latitude: 90, longitude: 180), 5_000),
            (CLLocationCoordinate2D(latitude: -90, longitude: -180), -1),
            (CLLocationCoordinate2D(latitude: 0, longitude: 721), 1_000_000),
        ]

        for (camera, halfWidth) in cases {
            let bounds = GeoPDFReader.fallbackBounds(
                centeredOn: camera,
                halfWidthMetres: halfWidth
            )
            XCTAssertTrue(isValidEarthCoordinate(bounds.southWest))
            XCTAssertTrue(isValidEarthCoordinate(bounds.northEast))
            XCTAssertLessThan(bounds.southWest.latitude, bounds.northEast.latitude)
            XCTAssertLessThan(bounds.southWest.longitude, bounds.northEast.longitude)
        }
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
            let source = PDFMapImporter.makeMapSource(
                from: imported,
                cameraCentre: CLLocationCoordinate2D(latitude: -34, longitude: 150)
            )

            let raster = try XCTUnwrap(source.renderedImage()?.cgImage)
            XCTAssertGreaterThan(raster.width, 0)
            XCTAssertGreaterThan(raster.height, 0)
            XCTAssertLessThanOrEqual(Int64(raster.width) * Int64(raster.height) * 4,
                                     32 * 1024 * 1024)
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

        let bounds = try XCTUnwrap(GeoPDFReader.bounds(from: fixture))
        XCTAssertEqual(bounds.southWest.latitude, -34, accuracy: 1e-9)
        XCTAssertEqual(bounds.southWest.longitude, 150, accuracy: 1e-9)
        XCTAssertEqual(bounds.northEast.latitude, -33, accuracy: 1e-9)
        XCTAssertEqual(bounds.northEast.longitude, 151, accuracy: 1e-9)
        XCTAssertEqual(bounds.pdfCropRect, CGRect(x: 0, y: 0, width: 600, height: 400))
        XCTAssertNotNil(bounds.placementAffine)
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

        let bounds = try XCTUnwrap(GeoPDFReader.bounds(from: fixture))
        XCTAssertEqual(bounds.southWest.latitude, -34, accuracy: 1e-9)
        XCTAssertEqual(bounds.southWest.longitude, 150, accuracy: 1e-9)
        XCTAssertEqual(bounds.northEast.latitude, -33, accuracy: 1e-9)
        XCTAssertEqual(bounds.northEast.longitude, 151, accuracy: 1e-9)
        XCTAssertNotNil(bounds.placementAffine)
    }

    func testLargerMalformedDeclaredAdobeViewportDoesNotPublishValidInset() throws {
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
            XCTAssertNil(
                GeoPDFReader.bounds(from: fixture),
                "a valid inset superseded a larger malformed declared-GEO map body"
            )
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

        let bounds = try XCTUnwrap(GeoPDFReader.bounds(from: fixture))
        XCTAssertEqual(bounds.southWest.latitude, -34, accuracy: 1e-9)
        XCTAssertEqual(bounds.southWest.longitude, 150, accuracy: 1e-9)
        XCTAssertEqual(bounds.northEast.latitude, -33, accuracy: 1e-9)
        XCTAssertEqual(bounds.northEast.longitude, 151, accuracy: 1e-9)
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

        let bounds = try XCTUnwrap(GeoPDFReader.bounds(from: fixture))
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

        let bounds = try XCTUnwrap(GeoPDFReader.bounds(from: fixture))
        let affine = try XCTUnwrap(bounds.placementAffine)
        let expectedSouthWest = try XCTUnwrap(
            Projection.utm(zone: 56, hemisphere: .SOUTH)
                .inverse(easting: 334_000, northing: 6_250_000)
        )
        let expectedNorthEast = try XCTUnwrap(
            Projection.utm(zone: 56, hemisphere: .SOUTH)
                .inverse(easting: 340_000, northing: 6_254_000)
        )
        let placedSouthWest = affine.apply(CGPoint(x: 0, y: 0))
        let placedNorthEast = affine.apply(CGPoint(x: 600, y: 400))

        XCTAssertEqual(placedSouthWest.latitude, expectedSouthWest.lat, accuracy: 0.0001)
        XCTAssertEqual(placedSouthWest.longitude, expectedSouthWest.lon, accuracy: 0.0001)
        XCTAssertEqual(placedNorthEast.latitude, expectedNorthEast.lat, accuracy: 0.0001)
        XCTAssertEqual(placedNorthEast.longitude, expectedNorthEast.lon, accuracy: 0.0001)
        XCTAssertEqual(bounds.pdfCropRect, CGRect(x: 0, y: 0, width: 600, height: 400))
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

        XCTAssertNil(GeoPDFReader.bounds(from: fixture))
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
            XCTAssertNil(GeoPDFReader.bounds(from: fixture), "malformed Adobe case \(index) published bounds")
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

        let bounds = try XCTUnwrap(GeoPDFReader.bounds(from: fixture))
        XCTAssertEqual(bounds.southWest.latitude, -34, accuracy: 1e-9)
        XCTAssertEqual(bounds.southWest.longitude, 150, accuracy: 1e-9)
        XCTAssertEqual(bounds.northEast.latitude, -33, accuracy: 1e-9)
        XCTAssertEqual(bounds.northEast.longitude, 151, accuracy: 1e-9)
        XCTAssertEqual(bounds.pdfCropRect, CGRect(x: 0, y: 0, width: 600, height: 400))
        let affine = try XCTUnwrap(bounds.placementAffine)
        XCTAssertEqual(affine.apply(CGPoint(x: 0, y: 0)).latitude, -34, accuracy: 1e-9)
        XCTAssertEqual(affine.apply(CGPoint(x: 0, y: 400)).latitude, -33, accuracy: 1e-9)
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
        XCTAssertNil(GeoPDFReader.bounds(from: adobeFixture))

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
        XCTAssertNil(GeoPDFReader.bounds(from: lgiFixture))
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
            XCTAssertNil(GeoPDFReader.bounds(from: fixture), "malformed LGIDict case \(index) published bounds")
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
            let bounds = try XCTUnwrap(GeoPDFReader.bounds(from: fixture), "complete projected case \(index) was rejected")
            XCTAssertNotNil(bounds.placementAffine)
            try? FileManager.default.removeItem(at: fixture)
        }
    }

    func testUTMProjectionUsesDeclaredSourceEllipsoid() throws {
        let wgs84 = try XCTUnwrap(
            Projection.utm(zone: 56, hemisphere: .SOUTH, ellipsoid: .wgs84)
                .inverse(easting: 334_000, northing: 6_250_000)
        )
        let legacy = try XCTUnwrap(
            Projection.utm(zone: 56, hemisphere: .SOUTH, ellipsoid: .airy1830)
                .inverse(easting: 334_000, northing: 6_250_000)
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
