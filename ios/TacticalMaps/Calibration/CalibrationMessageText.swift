import Foundation

/// Turns the fixture-pinned CalibrationMessage (key + raw args) into the
/// current language's text. Distances go through DisplayFormat.distance,
/// counts through the calibration_point plural, keys get resolved too.
/// Called from view bodies so a language switch picks it up straight away.
extension CalibrationMessage {

    private func metres(_ k: String) -> String {
        if case .metres(let m)? = args[k] { return DisplayFormat.distance(m) }
        return ""
    }

    private func rms(_ k: String) -> String {
        if case .metres(let m)? = args[k] { return DisplayFormat.rms(m) }
        return ""
    }

    private func cell(_ k: String) -> String {
        if case .metres(let m)? = args[k] { return DisplayFormat.cellSize(m) }
        return ""
    }

    private func count(_ k: String) -> Int {
        if case .number(let n)? = args[k] { return n }
        return 0
    }

    private func number(_ k: String) -> String { DisplayFormat.number(Double(count(k)), decimals: 0) }

    private func string(_ k: String) -> String {
        switch args[k] {
        case .text(let s)?: return s
        case .key(let s)?: return CalibrationMessage(key: s).text
        case .number(let n)?: return DisplayFormat.number(Double(n), decimals: 0)
        case .metres(let m)?: return DisplayFormat.distance(m)
        case nil: return ""
        }
    }

    var text: String { Self.resolve(self) }

    /// key -> accessor table. The strings in here are catalogue IDs and arg
    /// names, not copy, so they live outside the display property
    private static func resolve(_ m: CalibrationMessage) -> String {
        func metres(_ k: String) -> String { m.metres(k) }
        func rms(_ k: String) -> String { m.rms(k) }
        func cell(_ k: String) -> String { m.cell(k) }
        func count(_ k: String) -> Int { m.count(k) }
        func number(_ k: String) -> String { m.number(k) }
        func string(_ k: String) -> String { m.string(k) }
        switch m.key {
        // fit panel
        case "calibration_intro": return Messages.calibrationIntro()
        case "calibration_need_points": return Messages.calibrationNeedPoints(number("placed"))
        case "calibration_exact_fit": return Messages.calibrationExactFit()
        case "calibration_fit_summary":
            return Messages.calibrationFitSummary(Messages.calibrationPointCount(count("points")), rms("rms"), string("grade"))
        case "calibration_grade_good": return Messages.calibrationGradeGood()
        case "calibration_grade_fair": return Messages.calibrationGradeFair()
        case "calibration_grade_poor": return Messages.calibrationGradePoor()
        case "calibration_outlier": return Messages.calibrationOutlier(number("number"), metres("distance"))
        case "calibration_ambiguous": return Messages.calibrationAmbiguous(number("first"), number("second"))
        case "calibration_disagree": return Messages.calibrationDisagree(metres("distance"))
        case "calibration_disagree_add_fifth": return Messages.calibrationDisagreeAddFifth(metres("distance"))
        case "calibration_degenerate": return Messages.calibrationDegenerate()
        case "calibration_invalid": return Messages.calibrationInvalid()
        case "calibration_implausible": return Messages.calibrationImplausible()
        case "calibration_spread_low": return Messages.calibrationSpreadLow()
        case "calibration_off_sheet": return Messages.calibrationOffSheet()
        case "calibration_zoom_hint": return Messages.calibrationZoomHint()
        case "calibration_next_corner_top_left": return Messages.calibrationNextCornerTopLeft()
        case "calibration_next_corner_top_right": return Messages.calibrationNextCornerTopRight()
        case "calibration_next_corner_bottom_right": return Messages.calibrationNextCornerBottomRight()
        case "calibration_next_corner_bottom_left": return Messages.calibrationNextCornerBottomLeft()
        case "calibration_max_points": return Messages.calibrationMaxPoints(number("max"))
        // finish confirm
        case "calibration_finish_confirm_exact": return Messages.calibrationFinishConfirmExact()
        case "calibration_finish_confirm_poor": return Messages.calibrationFinishConfirmPoor(rms("rms"))
        case "calibration_finish_confirm_spread": return Messages.calibrationFinishConfirmSpread()
        // entry interpretation line
        case "calibration_reads_as": return Messages.calibrationReadsAs(string("text"))
        case "calibration_cell_intersection": return Messages.calibrationCellIntersection(cell("sizeM"))
        case "calibration_cell_feature": return Messages.calibrationCellFeature(cell("sizeM"), cell("halfM"))
        case "calibration_completed_from": return Messages.calibrationCompletedFrom(number("number"))
        case "calibration_completed_from_map": return Messages.calibrationCompletedFromMap()
        case "calibration_warn_far": return Messages.calibrationWarnFar(metres("distance"))
        // library rows
        case "map_state_geopdf": return Messages.mapStateGeopdf()
        case "map_state_calibrated":
            return Messages.mapStateCalibrated(Messages.calibrationPointCount(count("points")), rms("rms"))
        case "map_state_calibrated_exact": return Messages.mapStateCalibratedExact(Messages.calibrationPointCount(count("points")))
        case "map_state_needs_calibration": return Messages.mapStateNeedsCalibration()
        case "map_state_rejected": return Messages.mapStateRejected()
        case "map_state_draft": return Messages.mapStateDraft(Messages.calibrationPointCount(count("points")))
        case "map_state_offline_tiles": return Messages.mapStateOfflineTiles()
        case "map_state_derived": return Messages.mapStateDerived(string("name"))
        case "map_state_unavailable": return Messages.mapStateUnavailable()
        case "map_state_open_failed": return Messages.mapStateOpenFailed()
        // import
        case "map_import_duplicate": return Messages.mapImportDuplicate(string("name"))
        case "map_import_page_used": return Messages.mapImportPageUsed(number("page"), number("pages"))
        case "map_import_georef_rejected":
            let reason = PdfGeorefRejectReason(rawValue: string("reason"))?.displayReason ?? string("reason")
            return Messages.mapImportGeorefRejected(reason)
        default:
            assertionFailure("no copy for \(m.key)")
            return m.key
        }
    }
}

extension DisplayFormat {
    /// OD-F12: the {rms} arg. One decimal under 9.95 m (a sub-metre fit read
    /// "RMS 0 m"), the usual distance from there
    static func rms(_ metres: Double, locale: Locale = currentLocale) -> String {
        metres < 9.95 ? number(metres, decimals: 1, locale: locale) + " m" : distance(metres, locale: locale)
    }

    /// OD-F11: a grid cell's {size}/{half}, whole metres under 1 km and whole km
    /// from there, no decimals, the same in every language ("1 km", never "1.00 km")
    static func cellSize(_ metres: Double) -> String {
        metres >= 1000 ? "\(Int((metres / 1000).rounded())) km" : "\(Int(metres.rounded())) m"
    }
}

extension CoordinateParseError {
    /// the error line under the entry field, nil for empty (Save just stays off)
    var text: String? {
        switch self {
        case .empty: return nil
        case .unrecognised: return Messages.calibrationErrUnrecognised()
        case .tooCoarse: return Messages.calibrationErrTooCoarse()
        case .unequalDigits: return Messages.calibrationErrOddDigits()
        case .needsFullReference: return Messages.calibrationErrNeedsFull()
        case let .invalidSquare(square, zone): return Messages.calibrationErrSquare(square, String(zone))
        case .bandMismatch(let band): return Messages.calibrationErrBand(band)
        case .polarUnsupported: return Messages.calibrationErrPolar()
        case .outOfRange: return Messages.calibrationErrRange()
        case .oldLettering(let d): return Messages.calibrationErrOldLettering(CalibrationDatumChoice.displayName(d))
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
