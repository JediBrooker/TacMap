import CoreLocation
import Foundation
import PDFKit

enum PDFMapImportError: LocalizedMessageError, LocalizedError, Equatable {
    case invalidPDF

    var errorDescription: String? { localizedMessage.text }
        var localizedMessage: LocalizedMessage {
        switch self {
        case .invalidPDF:
            return Messages.displayCouldnTImportThisFileAsAValidPdfMessage()
        }
    }
}

enum PDFMapImporter {
    static func copyAndValidate(_ source: URL) throws -> URL {
        let destination = try ImportedMapFileCopier.copyToImportedMaps(
            source,
            maximumBytes: ImportedMapFileCopier.maxPDFBytes,
            preferredExtension: "pdf"
        )
        guard let document = PDFDocument(url: destination),
              !document.isLocked,
              document.pageCount > 0,
              document.page(at: 0) != nil else {
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
            fromGeoPDF: parsedBounds != nil,
            contentKey: PDFSessionStore.contentKey(for: importedURL)
        )
    }
}
