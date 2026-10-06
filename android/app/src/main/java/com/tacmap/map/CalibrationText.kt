package com.tacmap.map

import com.tacmap.calibration.GeoDatums
import com.tacmap.calibration.ImportError
import com.tacmap.calibration.ImportFailure
import com.tacmap.calibration.fiducial.StatusMessage
import com.tacmap.localization.DisplayFormat
import com.tacmap.localization.Messages
import com.tacmap.mgrs.CoordinateParseError
import com.tacmap.mgrs.ParseOutcome

/**
 * StatusMessage (key + named args from the pure layer) -> catalogue text. Counts go
 * through the plural families, metres through DisplayFormat.distance, so the
 * German build reads right without the pure code knowing about locales.
 */
internal object CalibrationText {
    private fun num(v: Any?): Double = (v as? Number)?.toDouble() ?: 0.0
    private fun int(v: Any?): Int = (v as? Number)?.toInt() ?: 0
    private fun dist(v: Any?): String = DisplayFormat.distance(num(v))
    private fun rms(v: Any?): String = rmsText(num(v))
    private fun cell(v: Any?): String = cellSizeText(num(v))
    private fun points(v: Any?): String = Messages.calibrationPointCount(int(v))

    /**
     * the {rms} arg (OD-F12): one decimal under 9.95 m so a sub metre fit doesn't read
     * "RMS 0 m", plain DisplayFormat.distance from there. calibration_fit_report.json rmsDisplay
     */
    fun rmsText(metres: Double, locale: java.util.Locale = DisplayFormat.currentLocale): String =
        if (metres < 9.95) DisplayFormat.number(metres, 1, locale) + " m" else DisplayFormat.distance(metres, locale)

    /**
     * grid cell sizes for calibration_cell_* (OD-F11): whole m under a km, whole km from
     * there, "1 km" never "1.00 km". same in every language, it's a size not a measurement
     */
    fun cellSizeText(metres: Double): String {
        val m = Math.round(metres)
        return if (m < 1000) "$m m" else "${m / 1000} km"
    }

    fun gradeWord(key: String?): String = when (key) {
        "calibration_grade_good", "good" -> Messages.calibrationGradeGood()
        "calibration_grade_fair", "fair" -> Messages.calibrationGradeFair()
        else -> Messages.calibrationGradePoor()
    }

    /**
     * the ONE datum name table (A1), in datum sheet order (contract s10). chip, sheet rows,
     * interpretation line, datum toast and the old lettering error all read it. technical
     * names, not localised. calibration_input.json display.datumDisplayNames pins it
     */
    private val DATUM_NAMES: LinkedHashMap<String, String> = linkedMapOf(
        "WGS84" to "WGS84",
        "GDA2020" to "GDA2020",
        "GDA94" to "GDA94",
        "NAD83" to "NAD83",
        "ETRS89" to "ETRS89",
        "ED50" to "ED50",
        "NAD27" to "NAD27",
        "NAD27_CONUS_EAST" to "NAD27 CONUS East",
        "NAD27_CONUS_WEST" to "NAD27 CONUS West",
        "NAD27_ALASKA" to "NAD27 Alaska",
        "NAD27_CANADA" to "NAD27 Canada",
        "OSGB36" to "OSGB36",
        "SK42" to "SK-42",
        "TOKYO" to "Tokyo",
        "CH1903" to "CH1903",
        "NTF" to "NTF",
    )

    fun datumName(id: String?): String = DATUM_NAMES[id] ?: id ?: GeoDatums.WGS84.id

    /** contract s10 datum sheet order */
    val DATUM_ORDER: List<String> = DATUM_NAMES.keys.toList()

    fun text(m: StatusMessage): String {
        val a = m.args
        return when (m.key) {
            "calibration_intro" -> Messages.calibrationIntro()
            "calibration_need_points" -> Messages.calibrationNeedPoints(DisplayFormat.number(num(a["placed"]), 0))
            "calibration_exact_fit" -> Messages.calibrationExactFit()
            "calibration_fit_summary" -> Messages.calibrationFitSummary(points(a["points"]), rms(a["rms"]), gradeWord(a["grade"] as? String))
            "calibration_outlier" -> Messages.calibrationOutlier(int(a["number"]).toString(), dist(a["distance"]))
            "calibration_ambiguous" -> Messages.calibrationAmbiguous(int(a["first"]).toString(), int(a["second"]).toString())
            "calibration_disagree" -> Messages.calibrationDisagree(dist(a["distance"]))
            "calibration_disagree_add_fifth" -> Messages.calibrationDisagreeAddFifth(dist(a["distance"]))
            "calibration_degenerate" -> Messages.calibrationDegenerate()
            "calibration_invalid" -> Messages.calibrationInvalid()
            "calibration_implausible" -> Messages.calibrationImplausible()
            "calibration_spread_low" -> Messages.calibrationSpreadLow()
            "calibration_off_sheet" -> Messages.calibrationOffSheet()
            "calibration_zoom_hint" -> Messages.calibrationZoomHint()
            "calibration_next_corner_top_left" -> Messages.calibrationNextCornerTopLeft()
            "calibration_next_corner_top_right" -> Messages.calibrationNextCornerTopRight()
            "calibration_next_corner_bottom_right" -> Messages.calibrationNextCornerBottomRight()
            "calibration_next_corner_bottom_left" -> Messages.calibrationNextCornerBottomLeft()
            "calibration_finish_confirm_exact" -> Messages.calibrationFinishConfirmExact()
            "calibration_finish_confirm_poor" -> Messages.calibrationFinishConfirmPoor(rms(a["rms"]))
            "calibration_finish_confirm_spread" -> Messages.calibrationFinishConfirmSpread()
            "calibration_warn_far" -> Messages.calibrationWarnFar(dist(a["distance"]))
            "calibration_reads_as" -> Messages.calibrationReadsAs(a["text"].toString())
            "calibration_cell_intersection" -> Messages.calibrationCellIntersection(cell(a["sizeM"]))
            "calibration_cell_feature" -> Messages.calibrationCellFeature(cell(a["sizeM"]), cell(a["halfM"]))
            "calibration_completed_from" -> Messages.calibrationCompletedFrom(int(a["number"]).toString())
            "calibration_completed_from_map" -> Messages.calibrationCompletedFromMap()
            "map_import_page_used" -> Messages.mapImportPageUsed(int(a["page"]).toString(), int(a["pages"]).toString())
            "map_import_duplicate" -> Messages.mapImportDuplicate(a["name"].toString())
            "map_state_geopdf" -> Messages.mapStateGeopdf()
            "map_state_calibrated" -> Messages.mapStateCalibrated(points(a["points"]), rms(a["rms"]))
            "map_state_calibrated_exact" -> Messages.mapStateCalibratedExact(points(a["points"]))
            "map_state_needs_calibration" -> Messages.mapStateNeedsCalibration()
            "map_state_rejected" -> Messages.mapStateRejected()
            "map_state_draft" -> Messages.mapStateDraft(points(a["points"]))
            "map_state_offline_tiles" -> Messages.mapStateOfflineTiles()
            "map_state_derived" -> Messages.mapStateDerived(a["name"].toString())
            "map_state_unavailable" -> Messages.mapStateUnavailable()
            "map_state_open_failed" -> Messages.mapStateOpenFailed()
            else -> m.key
        }
    }

    /** the error line under the entry field, null for empty (Save just stays off) */
    fun parseError(outcome: ParseOutcome.Err): String? {
        val a = outcome.args
        return when (outcome.error) {
            CoordinateParseError.EMPTY -> null
            CoordinateParseError.UNRECOGNISED -> Messages.calibrationErrUnrecognised()
            CoordinateParseError.TOO_COARSE -> Messages.calibrationErrTooCoarse()
            CoordinateParseError.UNEQUAL_DIGITS -> Messages.calibrationErrOddDigits()
            CoordinateParseError.NEEDS_FULL_REFERENCE -> Messages.calibrationErrNeedsFull()
            CoordinateParseError.INVALID_SQUARE -> Messages.calibrationErrSquare(a["square"].orEmpty(), a["zone"].orEmpty())
            CoordinateParseError.BAND_MISMATCH -> Messages.calibrationErrBand(a["band"].orEmpty())
            CoordinateParseError.POLAR_UNSUPPORTED -> Messages.calibrationErrPolar()
            CoordinateParseError.OUT_OF_RANGE -> Messages.calibrationErrRange()
            CoordinateParseError.OLD_LETTERING -> Messages.calibrationErrOldLettering(datumName(a["datumId"]))
        }
    }

    /** an import that stopped -> its shared message (byte counts as file sizes) */
    fun importFailure(f: ImportFailure, sizeText: (Long) -> String): String {
        val a = f.args
        return when (f.error) {
            ImportError.TOO_LARGE -> Messages.mapImportTooLarge(sizeText((a["limit"] as Number).toLong()))
            ImportError.NO_SPACE -> Messages.mapImportNoSpace(sizeText((a["size"] as Number).toLong()))
            ImportError.PASSWORD -> Messages.mapImportPassword()
            ImportError.TOO_MANY_PAGES -> Messages.mapImportTooManyPages(int(a["limit"]).toString())
            ImportError.PAGE_SIZE -> Messages.mapImportPageSize()
            ImportError.TOO_COMPLEX -> Messages.mapImportTooComplex()
            ImportError.INVALID_PDF -> Messages.mapImportInvalidPdf()
            ImportError.INVALID_MBTILES -> Messages.mapImportInvalidMbtiles()
            ImportError.LIBRARY_FULL -> Messages.mapImportLibraryFull(int(a["limit"]).toString())
            ImportError.LOCKED -> Messages.mapImportUnlockFirst()
            ImportError.INTERRUPTED -> Messages.mapImportInterrupted()
            ImportError.CANCELLED -> Messages.mapImportCancelled()
            ImportError.FAILED -> Messages.mapImportReadFailed(a["detail"]?.toString().orEmpty())
        }
    }
}
