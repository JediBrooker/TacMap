import CoreLocation
import Foundation
import PDFKit

enum PDFMapImportError: LocalizedMessageError, LocalizedError, Equatable {
    case invalidPDF
    /// the import probe couldnt draw it, the shared render failure copy says why
    case cannotDraw(PDFRenderFailure)

    var errorDescription: String? { localizedMessage.text }
        var localizedMessage: LocalizedMessage {
        switch self {
        case .invalidPDF:
            return Messages.displayCouldnTImportThisFileAsAValidPdfMessage()
        case .cannotDraw(let f):
            return f.localizedMessage
        }
    }
}

extension PdfGeorefRejectReason {
    /// Short clause for the "can't use this georeference" alert.
    var displayReason: String {
        switch self {
        case .lptsOutOfRange: return Messages.pdfGeorefReasonLptsOutOfRange()
        case .nonFinite: return Messages.pdfGeorefReasonNonFinite()
        case .gptsOffEarth: return Messages.pdfGeorefReasonOffEarth()
        case .rmsGate: return Messages.pdfGeorefReasonRmsGate()
        case .degenerateViewport: return Messages.pdfGeorefReasonDegenerate()
        case .malformed: return Messages.pdfGeorefReasonMalformed()
        case .unknownDatum: return Messages.pdfGeorefReasonUnknownDatum()
        case .unsupportedProjection: return Messages.pdfGeorefReasonUnsupportedProjection()
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

    enum Placement {
        case source(PDFMapSource)
        /// declared georef we won't use; nothing placed
        case rejected(PdfGeorefRejectReason)
    }

    /// Georeferenced PDFs get their own placement, plain ones a provisional
    /// (uncalibrated) one at the camera. A refused georef is NOT quietly
    /// turned into a camera box, the caller has to surface it.
    static func placement(
        for importedURL: URL,
        cameraCentre: CLLocationCoordinate2D
    ) -> Placement? {
        guard let readout = GeoPDFReader.read(url: importedURL) else { return nil }
        let key = PDFSessionStore.contentKey(for: importedURL)
        switch readout.outcome {
        case .georeferenced(let g):
            return .source(PDFMapSource(url: importedURL, georef: g, contentKey: key))
        case .rejected(let reason):
            return .rejected(reason)
        case .notGeoreferenced:
            guard let g = PdfGeoreference.provisional(pageBox: readout.page.cropBox,
                                                      rotation: readout.page.rotation,
                                                      centredOn: cameraCentre) else { return nil }
            return .source(PDFMapSource(url: importedURL, georef: g, contentKey: key))
        }
    }
}
