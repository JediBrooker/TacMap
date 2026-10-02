import SwiftUI

/// What the header says about the imported PDF. failed is sticky until Try Again.
enum PDFRenderStatus: Equatable {
    case none
    case preparing
    case ready
    case failed(PDFRenderFailure)

    var failure: PDFRenderFailure? {
        if case .failed(let f) = self { return f }
        return nil
    }
}

enum PDFRenderStatusColors {
    static let preparingHex = "#FFC247"
    static let failedHex = "#FF5A5A"
    static let readyHex = "#74E38A"

    static let preparing = Color(red: 1.0, green: 0xC2 / 255.0, blue: 0x47 / 255.0)
    static let failed = Color(red: 1.0, green: 0x5A / 255.0, blue: 0x5A / 255.0)
    static let ready = Color(red: 0x74 / 255.0, green: 0xE3 / 255.0, blue: 0x8A / 255.0)
}
