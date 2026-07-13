import CoreLocation
import Foundation
import PDFKit

enum PDFMapImportError: LocalizedError, Equatable {
    case invalidPDF

    var errorDescription: String? {
        switch self {
        case .invalidPDF:
            return "Couldn't import this file as a valid PDF map."
        }
    }
}

enum PDFMapImporter {
    static func copyAndValidate(_ source: URL) throws -> URL {
        let destination = try ImportedMapFileCopier.copyToImportedMaps(
            source,
            maximumBytes: ImportedMapFileCopier.maxPDFBytes
        )
        guard PDFDocument(url: destination) != nil else {
            try? FileManager.default.removeItem(at: destination)
            throw PDFMapImportError.invalidPDF
        }
        return destination
    }

    static func makeMapSource(
        from importedURL: URL,
        cameraCentre: CLLocationCoordinate2D
    ) -> PDFMapSource {
        let parsedBounds = GeoPDFReader.bounds(from: importedURL)
        let bounds = parsedBounds ?? GeoPDFReader.fallbackBounds(centeredOn: cameraCentre)
        return PDFMapSource(
            url: importedURL,
            bounds: bounds,
            fromGeoPDF: parsedBounds != nil
        )
    }
}
