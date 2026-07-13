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
        XCTAssertNotNil(image.cgImage)
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
}
