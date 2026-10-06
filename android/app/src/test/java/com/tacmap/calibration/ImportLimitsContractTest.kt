package com.tacmap.calibration

import com.tacmap.calibration.fiducial.CalibrationCameraAnchor
import com.tacmap.calibration.fiducial.CalibrationFitReport
import com.tacmap.calibration.fiducial.CalibrationState
import com.tacmap.calibration.fiducial.StatusMessage
import com.tacmap.mgrs.CoordinateInputParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** testdata/import_limits.json: constants, pre-checks, inspection rules, ImportDecision, library rows */
class ImportLimitsContractTest {
    private val root = Json.parseToJsonElement(PdfGeorefFixture.file("import_limits.json").readText()).jsonObject

    private fun JsonObject.o(k: String): JsonObject? = this[k]?.takeIf { it !is JsonNull }?.jsonObject
    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.n(k: String): Double = this[k]!!.jsonPrimitive.double
    private fun JsonObject.l(k: String): Long = this[k]!!.jsonPrimitive.long
    private fun JsonObject.i(k: String): Int = this[k]!!.jsonPrimitive.int

    @Test
    fun constantsMatchTheSharedTable() {
        val c = root.o("calibration")!!
        assertEquals(c.i("maxCalibrationPoints"), CalibrationState.MAX_POINTS)
        assertEquals(c.i("maxCalibrationPoints"), ImportLimits.MAX_CALIBRATION_POINTS)
        assertEquals(c.i("undoDepth"), CalibrationState.UNDO_DEPTH)
        assertEquals(c.i("maxDrafts"), ImportLimits.MAX_DRAFTS)
        assertEquals(c.n("gpsMaxAccuracyM"), ImportLimits.GPS_MAX_ACCURACY_M, 0.0)
        assertEquals(c.n("degenerateEigenRatio"), CalibrationFitReport.DEGENERATE_EIGEN_RATIO, 0.0)
        assertEquals(c.n("spreadMinFraction"), CalibrationFitReport.SPREAD_MIN_FRACTION, 0.0)
        assertEquals(c.n("toleranceFloorM"), CalibrationFitReport.TOLERANCE_FLOOR_M, 0.0)
        assertEquals(c.n("toleranceDiagonalFraction"), CalibrationFitReport.TOLERANCE_DIAGONAL_FRACTION, 0.0)
        assertEquals(c.n("gradeFairToleranceMultiple"), CalibrationFitReport.FAIR_MULTIPLE, 0.0)
        assertEquals(c.n("implausibleAnisotropy"), CalibrationFitReport.IMPLAUSIBLE_ANISOTROPY, 0.0)
        assertEquals(c.n("plausibleScaleMin"), CalibrationFitReport.PLAUSIBLE_SCALE_MIN, 0.0)
        assertEquals(c.n("plausibleScaleMax"), CalibrationFitReport.PLAUSIBLE_SCALE_MAX, 0.0)
        assertEquals(c.n("offEarthLatDeg"), CalibrationFitReport.OFF_EARTH_LAT, 0.0)
        assertEquals(c.i("outlierMinPoints"), CalibrationFitReport.OUTLIER_MIN_POINTS)
        assertEquals(c.n("entryWarnSigma"), CalibrationFitReport.ENTRY_WARN_SIGMA, 0.0)
        assertEquals(c.n("entryWarnFloorM"), CalibrationFitReport.ENTRY_WARN_FLOOR_M, 0.0)
        assertEquals(c.n("nextCornerInsetFraction"), CalibrationFitReport.NEXT_CORNER_INSET, 0.0)
        assertEquals(c.n("residualLineMinScreenPt"), ImportLimits.RESIDUAL_LINE_MIN_SCREEN_DP, 0.0)
        assertEquals(c.n("moveToCrosshairMinScreenPt"), ImportLimits.MOVE_TO_CROSSHAIR_MIN_SCREEN_DP, 0.0)
        assertEquals(c.n("markerHitRadiusPt"), ImportLimits.MARKER_HIT_RADIUS_DP, 0.0)
        assertEquals(c.n("zoomHintScreenPtPerPagePt"), CalibrationCameraAnchor.ZOOM_HINT_SCREEN_PT_PER_PAGE_PT, 0.0)
        assertEquals(c.n("cameraZoomMin"), CalibrationCameraAnchor.MIN_ZOOM, 0.0)
        assertEquals(c.n("cameraZoomMax"), CalibrationCameraAnchor.MAX_ZOOM, 0.0)
        assertEquals(c.n("cameraJacobianStepPt"), CalibrationCameraAnchor.JACOBIAN_STEP_PT, 0.0)
        assertEquals(c.l("transitionPrefetchDeadlineMs"), ImportLimits.TRANSITION_PREFETCH_DEADLINE_MS)
        assertEquals(c.n("metresPerPagePointAtUnitScale"), CalibrationFitReport.METRES_PER_PAGE_POINT_AT_UNIT_SCALE, 1e-18)
        assertEquals(c.n("provisionalScaleDenominator"), ImportLimits.PROVISIONAL_SCALE_DENOMINATOR, 0.0)
        assertEquals(
            c.n("provisionalScaleDenominator") * c.n("metresPerPagePointAtUnitScale"),
            PdfGeoreference.PROVISIONAL_METRES_PER_POINT, 1e-12,
        )
        assertEquals(c.i("maxInputUtf16Units"), CoordinateInputParser.MAX_INPUT_UTF16_UNITS)

        val i = root.o("import")!!
        assertEquals(i.l("pdfMaxBytes"), ImportLimits.PDF_MAX_BYTES)
        assertEquals(i.l("mbtilesMaxBytes"), ImportLimits.MBTILES_MAX_BYTES)
        assertEquals(i.i("maxPages"), ImportLimits.MAX_PAGES)
        assertEquals(i.i("georefScanPages"), ImportLimits.GEOREF_SCAN_PAGES)
        assertEquals(i.n("pageSideMinPt"), ImportLimits.PAGE_SIDE_MIN_PT, 0.0)
        assertEquals(i.n("pageSideMaxPt"), ImportLimits.PAGE_SIDE_MAX_PT, 0.0)
        assertEquals(i.l("parseTimeoutMs"), ImportLimits.PARSE_TIMEOUT_MS)
        assertEquals(i.l("freeSpaceMarginBytes"), ImportLimits.FREE_SPACE_MARGIN_BYTES)
        assertEquals(i.i("maxLibraryEntries"), ImportLimits.MAX_LIBRARY_ENTRIES)
        assertEquals(i.l("progressHudDelayMs"), ImportLimits.PROGRESS_HUD_DELAY_MS)
        assertEquals(i.n("thumbnailWidthPt"), ImportLimits.THUMBNAIL_WIDTH_DP, 0.0)
        assertEquals(i.i("thumbnailCacheEntries"), ImportLimits.THUMBNAIL_CACHE_ENTRIES)
        assertEquals(i.l("cancelCopyGranularityBytes"), ImportLimits.CANCEL_COPY_GRANULARITY_BYTES)
    }

    // test-integrity-4: the SQLite vectors run instrumented, the constants are pinned here too
    @Test
    fun mbtilesAdmissionConstantsMatchTheSharedTable() {
        val m = root.o("mbtilesMetadataAdmission")!!
        assertEquals(m.i("maxRows"), MBTilesStore.MAX_METADATA_ROWS)
        assertEquals(m.i("maxKeyCharacters"), MBTilesStore.MAX_METADATA_KEY_CHARACTERS)
        assertEquals(m.i("utf8PrefixBytesPerCharacter"), MBTilesStore.UTF8_BYTES_PER_CHARACTER)
        assertEquals(m.i("consumedBakeExtensionMaxCharacters"), MBTilesStore.MAX_BAKE_EXTENSION_CHARACTERS)
        assertEquals(m.i("tileZoomMin"), 0)
        assertEquals(m.i("tileZoomMax"), MBTilesStore.MAX_ZOOM)
        val known = m.o("knownValueMaxCharacters")!!
        assertEquals(known.keys, MBTilesStore.METADATA_VALUE_LIMITS.keys)
        known.forEach { (key, bound) -> assertEquals(key, bound.jsonPrimitive.int, MBTilesStore.METADATA_VALUE_LIMITS.getValue(key).first) }
        // only key/name/format may truncate, everything else fails closed when too long
        val truncating = m["truncateFields"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        MBTilesStore.METADATA_VALUE_LIMITS.forEach { (key, limit) -> assertEquals(key, key in truncating, limit.second) }
        assertEquals(m["relationTypes"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(), MBTilesStore.RELATION_TYPES)
        assertEquals(m.l("admissionBudgetMs"), MBTilesStore.ADMISSION_BUDGET_MS)
        assertEquals(m.l("viewQueryBudgetMs"), MBTilesStore.VIEW_QUERY_BUDGET_MS)
    }

    @Test
    fun errorsMapToTheSharedMessageKeys() {
        val errors = root.o("errors")!!
        assertEquals(errors.keys, ImportError.entries.map { it.code }.toSet())
        ImportError.entries.forEach { e ->
            val row = errors.o(e.code)!!
            assertEquals(e.code, row.s("key"), e.messageKey)
            assertEquals(e.code, row["args"]!!.jsonArray.map { it.jsonPrimitive.content }, e.argNames)
        }
        val reasons = root["georefRejectReasons"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(reasons, GeorefRejectReason.entries.map { it.code }.toSet())
    }

    private fun assertFailure(label: String, expect: JsonObject?, got: ImportFailure?) {
        if (expect == null) {
            assertNull(label, got)
            return
        }
        assertEquals(label, expect.s("error"), got?.error?.code)
        assertEquals("$label key", expect.s("messageKey"), got!!.error.messageKey)
        val args = expect.o("args")!!
        assertEquals("$label args", args.keys, got.args.keys)
        args.forEach { (k, v) -> assertEquals("$label $k", v.jsonPrimitive.long, (got.args.getValue(k) as Number).toLong()) }
    }

    @Test
    fun prechecksRunInTheSharedOrder() {
        val rows = root["prechecks"]!!.jsonArray.map { it.jsonObject }
        assertTrue(rows.size >= 12)
        for (r in rows) {
            val got = ImportPrecheck.check(
                libraryLoaded = r.s("library") == "loaded",
                entryCount = r.i("entryCount"),
                kind = if (r.s("kind") == "pdf") ImportedMapKind.PDF else ImportedMapKind.MBTILES,
                sizeBytes = r.l("sizeBytes"),
                freeBytes = r.l("freeBytes"),
            )
            assertFailure(r.s("id")!!, r.o("expect"), got)
        }
    }

    @Test
    fun inspectionRulesMatch() {
        val rows = root["inspections"]!!.jsonArray.map { it.jsonObject }
        // a generator change that drops rows shouldn't pass by testing less
        assertTrue("only ${rows.size} inspections", rows.size >= 10)
        for (r in rows) {
            val pages = r["pages"]!!
            val boxes: List<InspectedPageBoxes> = if (pages is JsonArray) {
                pages.map { p -> p.jsonObject.let { InspectedPageBoxes(PdfGeorefFixture.doubles(it["mediaBox"]!!), it["cropBox"]?.takeIf { c -> c !is JsonNull }?.let(PdfGeorefFixture::doubles)) } }
            } else {
                val all = pages.jsonObject.o("allPages")!!
                val one = InspectedPageBoxes(PdfGeorefFixture.doubles(all["mediaBox"]!!), all["cropBox"]?.takeIf { c -> c !is JsonNull }?.let(PdfGeorefFixture::doubles))
                List(pages.jsonObject.i("count")) { one }
            }
            val got = ImportInspectionRules.check(
                openable = (r["openable"] as JsonPrimitive).booleanOrNull!!,
                encrypted = (r["encrypted"] as JsonPrimitive).booleanOrNull!!,
                emptyPasswordOpens = (r["emptyPasswordOpens"] as JsonPrimitive).booleanOrNull!!,
                pageCount = r.i("pageCount"),
                boxes = boxes.asSequence(),
            )
            assertFailure(r.s("id")!!, r.o("expect"), got)
        }
    }

    private fun assertMessage(label: String, want: JsonObject?, have: StatusMessage?) {
        if (want == null) {
            assertNull(label, have)
            return
        }
        assertEquals("$label key", want.s("key"), have?.key)
        val args = want.o("args")!!
        assertEquals("$label args", args.keys, have!!.args.keys)
        args.forEach { (k, v) ->
            val p = v.jsonPrimitive
            if (p.isString) assertEquals("$label $k", p.content, have.args[k].toString())
            else assertEquals("$label $k", p.double, (have.args[k] as Number).toDouble(), 1e-9)
        }
    }

    @Test
    fun importDecisionsMatchTheTable() {
        val rows = root["decisions"]!!.jsonArray.map { it.jsonObject }
        assertTrue(rows.size >= 14)
        for (r in rows) {
            val id = r.s("id")!!
            val pages = (r["pages"] as? JsonArray)?.map { p ->
                val o = p.jsonObject
                when (o.s("state")) {
                    "valid" -> PageGeorefState.Valid
                    "rejected" -> PageGeorefState.Rejected(GeorefRejectReason.entries.first { it.code == o.s("reason") })
                    else -> PageGeorefState.None
                }
            }
            val dup = r.o("duplicate")?.let { DuplicateInfo((it["existingHasGeoref"] as JsonPrimitive).booleanOrNull!!, it.s("name")!!) }
            val got = ImportDecision.decide(r.i("pageCount"), pages, dup)
            val want = r.o("outcome")!!
            val wantPage = (want["page"] as? JsonPrimitive)?.intOrNull
            when (want.s("action")) {
                "addAndActivate" -> {
                    val g = got as ImportOutcome.AddAndActivate
                    assertEquals(id, wantPage, g.page)
                    assertMessage("$id toast", want.o("toast"), g.toast)
                }
                "addRejected" -> {
                    val g = got as ImportOutcome.AddRejected
                    assertEquals(id, wantPage, g.page)
                    val alert = want.o("alert")!!
                    assertEquals(id, alert.o("message")!!.o("args")!!.s("reason"), g.reason.code)
                    assertEquals(id, listOf("map_import_calibrate_now", "map_import_later"), alert["buttons"]!!.jsonArray.map { it.jsonPrimitive.content })
                }
                "pagePicker" -> assertEquals(id, want["pickerBadges"]!!.jsonArray.map { it.jsonPrimitive.int }, (got as ImportOutcome.PagePicker).badges)
                "addAndCalibrate" -> assertEquals(id, wantPage, (got as ImportOutcome.AddAndCalibrate).page)
                "activateExisting" -> assertMessage("$id toast", want.o("toast"), (got as ImportOutcome.ActivateExisting).toast)
                "calibrateExisting" -> assertMessage("$id toast", want.o("toast"), (got as ImportOutcome.CalibrateExisting).toast)
                else -> error("unknown action in $id")
            }
        }
    }

    @Test
    fun libraryRowsMatchTheEntryStateTable() {
        val rows = root["entryStates"]!!.jsonArray.map { it.jsonObject }
        assertTrue("only ${rows.size} entry states", rows.size >= 18)
        assertTrue(rows.any { it.s("id") == "recovered_uninspectable_pdf" })
        // s15.1: every row says whether the pack's open got refused, and openFailed is in there
        assertTrue(rows.all { it["packRefused"] is JsonPrimitive })
        assertTrue(rows.any { it.s("id") == "mbtiles_open_failed" })
        for (r in rows) {
            val id = r.s("id")!!
            val entry = r.o("entry")!!
            val pdf = entry.o("pdf")
            val manual = pdf?.o("manual")
            val facts = EntryFacts(
                kind = if (entry.s("kind") == "pdf") ImportedMapKind.PDF else ImportedMapKind.MBTILES,
                pageCount = (pdf?.get("pageCount") as? JsonPrimitive)?.intOrNull ?: 1,
                hasEmbedded = (pdf?.get("embedded") as? JsonPrimitive)?.booleanOrNull ?: false,
                embeddedIssue = pdf?.s("embeddedIssue"),
                manualPoints = manual?.i("n"),
                manualRmsM = (manual?.get("rmsM") as? JsonPrimitive)?.doubleOrNull,
                derivedFromId = entry.s("derivedFromId"),
                parentName = entry.s("parentName"),
            )
            val file = when (r.s("fileStatus")) {
                "ok" -> EntryFileStatus.OK
                "missing" -> EntryFileStatus.MISSING
                else -> EntryFileStatus.MISMATCH
            }
            val refused = (r["packRefused"] as JsonPrimitive).booleanOrNull!!
            val got = LibraryEntryRules.present(facts, file, (r["draftPoints"] as? JsonPrimitive)?.intOrNull, refused)
            val e = r.o("expect")!!
            assertEquals("$id state", e.s("state"), got.state.code)
            assertEquals("$id effective", e.s("effectiveGeoref"), got.effectiveGeoref)
            assertEquals("$id durable", (e["canBeDurableActive"] as JsonPrimitive).booleanOrNull, got.canBeDurableActive)
            assertMessage("$id subtitle", e.o("subtitle"), got.subtitle)
            assertEquals("$id tap", e.s("rowTap"), got.rowTap.code)
            assertEquals("$id menu", e["menu"]!!.jsonArray.map { it.jsonPrimitive.content }, got.menu.map { it.code })
        }
    }
}
