import Foundation
import CoreGraphics

/// WP5 contract s3 import limits. import_limits.json "import" pins them on
/// both platforms (ImportLimitsContractTests).
enum ImportLimits {
    static let pdfMaxBytes: Int64 = 512 * 1024 * 1024
    static let mbtilesMaxBytes: Int64 = 4 * 1024 * 1024 * 1024
    static let maxPages = 500
    static let georefScanPages = 50
    static let pageSideMinPt = 36.0
    static let pageSideMaxPt = 14_400.0
    static let parseTimeoutMs = 30_000
    static let freeSpaceMarginBytes: Int64 = 64 * 1024 * 1024
    static let maxLibraryEntries = 100
    static let progressHudDelayMs = 300
    static let thumbnailWidthPt = 120.0
    static let thumbnailCacheEntries = 24
    static let cancelCopyGranularityBytes = 1024 * 1024
}

/// Every import failure the user can see, mapped to its catalogue key (import_limits.json errors).
enum MapImportError: Error, Equatable {
    case tooLarge(limit: Int64)
    case noSpace(size: Int64)
    case password
    case tooManyPages(limit: Int)
    case pageSize
    case tooComplex
    case invalidPdf
    case invalidMbtiles
    case libraryFull(limit: Int)
    case locked
    case interrupted
    case cancelled
    case failed(detail: String)
    /// WP2 import probe couldnt draw the page, the shared render failure copy says why
    case cannotDraw(PDFRenderFailure)

    var code: String {
        switch self {
        case .tooLarge: return "tooLarge"
        case .noSpace: return "noSpace"
        case .password: return "password"
        case .tooManyPages: return "tooManyPages"
        case .pageSize: return "pageSize"
        case .tooComplex: return "tooComplex"
        case .invalidPdf: return "invalidPdf"
        case .invalidMbtiles: return "invalidMbtiles"
        case .libraryFull: return "libraryFull"
        case .locked: return "locked"
        case .interrupted: return "interrupted"
        case .cancelled: return "cancelled"
        case .failed: return "failed"
        case .cannotDraw: return "cannotDraw"
        }
    }

    var messageKey: String {
        switch self {
        case .tooLarge: return "map_import_too_large"
        case .noSpace: return "map_import_no_space"
        case .password: return "map_import_password"
        case .tooManyPages: return "map_import_too_many_pages"
        case .pageSize: return "map_import_page_size"
        case .tooComplex: return "map_import_too_complex"
        case .invalidPdf: return "map_import_invalid_pdf"
        case .invalidMbtiles: return "map_import_invalid_mbtiles"
        case .libraryFull: return "map_import_library_full"
        case .locked: return "map_import_unlock_first"
        case .interrupted: return "map_import_interrupted"
        case .cancelled: return "map_import_cancelled"
        case .failed: return "map_import_read_failed"
        case .cannotDraw(let f): return f.messageKey
        }
    }

    /// resolved at display time so a language switch picks it up
    var text: String {
        switch self {
        case .tooLarge(let limit): return Messages.mapImportTooLarge(Self.bytes(limit))
        case .noSpace(let size): return Messages.mapImportNoSpace(Self.bytes(size))
        case .password: return Messages.mapImportPassword()
        case .tooManyPages(let limit): return Messages.mapImportTooManyPages(DisplayFormat.number(Double(limit), decimals: 0))
        case .pageSize: return Messages.mapImportPageSize()
        case .tooComplex: return Messages.mapImportTooComplex()
        case .invalidPdf: return Messages.mapImportInvalidPdf()
        case .invalidMbtiles: return Messages.mapImportInvalidMbtiles()
        case .libraryFull(let limit): return Messages.mapImportLibraryFull(DisplayFormat.number(Double(limit), decimals: 0))
        case .locked: return Messages.mapImportUnlockFirst()
        case .interrupted: return Messages.mapImportInterrupted()
        case .cancelled: return Messages.mapImportCancelled()
        case .failed(let detail): return Messages.mapImportReadFailed(detail)
        case .cannotDraw(let f): return f.localizedMessage.text
        }
    }

    /// OD-F6: the WP2 bakeFormat size rule, same on both apps. 512 MiB reads
    /// "537 MB", not ByteCountFormatter's "536.9 MB"
    static func bytes(_ n: Int64, _ s: PDFBakeFormat.Separators = PDFBakeFormat.current) -> String {
        PDFBakeFormat.size(n, s)
    }
}

extension MapImportError: LocalizedError {
    var errorDescription: String? { text }
}

/// contract s9.2, in order
enum ImportPrecheck {
    enum LibraryStatus { case loaded, locked, corrupt }
    enum Kind { case pdf, mbtiles }

    static func check(library: LibraryStatus, entryCount: Int, kind: Kind,
                      sizeBytes: Int64, freeBytes: Int64) -> MapImportError? {
        guard library == .loaded else { return .locked }
        if entryCount >= ImportLimits.maxLibraryEntries { return .libraryFull(limit: ImportLimits.maxLibraryEntries) }
        let limit = kind == .pdf ? ImportLimits.pdfMaxBytes : ImportLimits.mbtilesMaxBytes
        if sizeBytes > limit { return .tooLarge(limit: limit) }
        if freeBytes < sizeBytes + ImportLimits.freeSpaceMarginBytes {
            return .noSpace(size: sizeBytes + ImportLimits.freeSpaceMarginBytes)
        }
        return nil
    }
}

/// contract s9.5 structural checks, in order. The georef part is WP1's reader.
enum PDFInspectionRules {
    struct PageBoxes: Equatable {
        var mediaBox: CGRect
        var cropBox: CGRect?
    }

    static func check(openable: Bool, encrypted: Bool, emptyPasswordOpens: Bool,
                      pageCount: Int, pages: [PageBoxes]) -> MapImportError? {
        guard openable else { return .invalidPdf }
        if encrypted && !emptyPasswordOpens { return .password }
        if pageCount > ImportLimits.maxPages { return .tooManyPages(limit: ImportLimits.maxPages) }
        for p in pages where !sideOK(p.mediaBox) || !sideOK(p.cropBox ?? p.mediaBox) {
            return .pageSize
        }
        return nil
    }

    static func sideOK(_ r: CGRect) -> Bool {
        let s = r.standardized
        let range = ImportLimits.pageSideMinPt...ImportLimits.pageSideMaxPt
        return range.contains(Double(s.width)) && range.contains(Double(s.height))
    }
}

/// contract s9.4 + s9.6, pure and identical on both platforms (import_limits.json decisions)
enum ImportDecision {
    enum PageState: Equatable {
        case valid
        case rejected(reason: String)
        case none
    }

    struct Duplicate: Equatable {
        var existingHasGeoref: Bool
        var name: String
        /// E9: its own file is missing or changed
        var existingUnavailable = false
    }

    enum Action: String, Equatable {
        case addAndActivate, addRejected, pagePicker, addAndCalibrate, activateExisting, calibrateExisting
    }

    struct Alert: Equatable {
        var message: CalibrationMessage
        var buttons: [String]
    }

    struct Outcome: Equatable {
        var action: Action
        var page: Int?
        var toast: CalibrationMessage?
        var alert: Alert?
        var pickerBadges: [Int]?
        /// E9: keep the new copy and point the existing entry at it
        var relink = false
    }

    /// pages = what the inspector found on the scanned pages (0..<georefScanPages)
    static func decide(pageCount: Int, pages: [PageState], duplicate: Duplicate?) -> Outcome {
        if let duplicate {
            return Outcome(action: duplicate.existingHasGeoref ? .activateExisting : .calibrateExisting, page: nil,
                           toast: CalibrationMessage(key: "map_import_duplicate", args: ["name": .text(duplicate.name)]),
                           alert: nil, pickerBadges: nil, relink: duplicate.existingUnavailable)
        }
        if let i = pages.firstIndex(of: .valid) {
            let toast = i != 0
                ? CalibrationMessage(key: "map_import_page_used", args: ["page": .number(i + 1), "pages": .number(pageCount)])
                : nil
            return Outcome(action: .addAndActivate, page: i, toast: toast, alert: nil, pickerBadges: nil)
        }
        let declared = pages.indices.filter { if case .rejected = pages[$0] { return true } else { return false } }
        if !declared.isEmpty {
            if pageCount == 1 || declared == [0] {
                var reason = ""
                if case .rejected(let r) = pages[0] { reason = r }
                return Outcome(action: .addRejected, page: 0, toast: nil,
                               alert: Alert(message: CalibrationMessage(key: "map_import_georef_rejected", args: ["reason": .text(reason)]),
                                            buttons: ["map_import_calibrate_now", "map_import_later"]),
                               pickerBadges: nil)
            }
            return Outcome(action: .pagePicker, page: nil, toast: nil, alert: nil, pickerBadges: declared)
        }
        if pageCount == 1 {
            return Outcome(action: .addAndCalibrate, page: 0, toast: nil, alert: nil, pickerBadges: nil)
        }
        return Outcome(action: .pagePicker, page: nil, toast: nil, alert: nil, pickerBadges: [])
    }
}
