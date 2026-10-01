package com.tacmap.map

import com.tacmap.calibration.CalibrationDraftStorage
import com.tacmap.calibration.GeoDatums
import com.tacmap.calibration.GeorefOrigin
import com.tacmap.calibration.ImportLimits
import com.tacmap.calibration.PagePoint
import com.tacmap.calibration.PdfGeoreference
import com.tacmap.calibration.PdfGeoreferenceCodec
import com.tacmap.calibration.PdfGeorefFixture
import com.tacmap.calibration.Wgs84Coordinate
import com.tacmap.calibration.fiducial.CalibrationCapture
import com.tacmap.calibration.fiducial.CalibrationDraft
import com.tacmap.calibration.fiducial.CalibrationEdit
import com.tacmap.calibration.fiducial.CalibrationPoint
import com.tacmap.calibration.fiducial.CalibrationPointKind
import com.tacmap.calibration.fiducial.CalibrationReference
import com.tacmap.calibration.fiducial.CalibrationState
import com.tacmap.calibration.fiducial.CalibrationTarget
import com.tacmap.calibration.fiducial.Finishability
import com.tacmap.calibration.fiducial.FitGrade
import com.tacmap.calibration.fiducial.StoredPagePoint
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calibration state machine (contract s2) against import_limits.json
 * lifecycle, with a fake draft store. The controller lives in MapViewModel so
 * none of this needs a composition: MapScreen going away on pause can't touch it.
 */
class CalibrationControllerTest {
    private val limits = Json.parseToJsonElement(PdfGeorefFixture.file("import_limits.json").readText()).jsonObject
    private val lifecycle = limits["lifecycle"]!!.jsonObject

    /** in memory, records every write so "synchronous per mutation" can be counted */
    private class FakeDrafts : CalibrationDraftStorage {
        val drafts = LinkedHashMap<String, CalibrationDraft>()
        var locked = false
        var saves = 0
        override fun load(key: String): CalibrationDraft? = if (locked) null else drafts[key]?.takeIf { it.key == key }
        override fun save(draft: CalibrationDraft): Boolean {
            if (locked) return false
            saves++
            drafts[draft.key] = draft
            return true
        }
        override fun delete(key: String): Boolean = if (locked) false else { drafts.remove(key); true }
        override fun all(): List<CalibrationDraft> = drafts.values.toList()
    }

    // the d2_03_good_4 sheet (calibration_fit_report.json): 1:50k at 45N, 32T, a 4 point grid square
    private val box = listOf(PagePoint(0.0, 0.0), PagePoint(1277.8582677, 0.0), PagePoint(1277.8582677, 1277.8582677), PagePoint(0.0, 1277.8582677))
    private val target = CalibrationTarget("entry-1", "sha256:" + "ab".repeat(32), 0, box, 0)
    private val corners = listOf(
        PagePoint(128.6929134, 1149.1653543) to "32T MQ 91000 94000",
        PagePoint(1149.1653543, 1149.1653543) to "32T NQ 09000 94000",
        PagePoint(128.6929134, 128.6929134) to "32T MQ 91000 76000",
        PagePoint(1149.1653543, 128.6929134) to "32T NQ 09000 76000",
    )
    private val base: PdfGeoreference = requireNotNull(PdfGeoreference.provisional(Wgs84Coordinate(45.0, 9.0), box))

    private fun startParams(
        draft: CalibrationDraft? = null,
        seedPoints: List<CalibrationPoint> = emptyList(),
        seedDatum: String? = null,
        embedded: String? = null,
        silent: Boolean = false,
    ) = CalibrationStart(
        target = target,
        entryName = "Sheet",
        base = base,
        savedEffective = null,
        seedDatumId = seedDatum,
        seedPoints = seedPoints,
        embeddedDatumId = embedded,
        draft = draft,
        isPreview = true,
        resumeSilently = silent,
    )

    private fun capture(p: PagePoint, onSheet: Boolean = true) = CalibrationCapture(p, onSheet, 2.0, false)

    private fun gridPoint(i: Int, number: Int = i + 1): CalibrationPoint {
        val (page, input) = corners[i]
        val parts = input.split(" ")
        val e = (if (parts[1].startsWith("M")) 400_000.0 else 500_000.0) + parts[2].toDouble()
        val n = 4_900_000.0 + parts[3].toDouble()
        return CalibrationPoint(
            id = "p$number", number = number, page = StoredPagePoint.of(page), input = input,
            reference = CalibrationReference.Grid(32, false, e, n),
        )
    }

    /** the real path: Add point at the crosshair, type the reference, Save */
    private fun CalibrationController.typePoint(i: Int) {
        assertTrue(beginAdd(capture(corners[i].first)))
        updateEntryText(corners[i].second)
        assertNotNull("'${corners[i].second}' should parse", state.value!!.entry!!.parsed)
        assertTrue(commitEntry())
    }

    private fun started(drafts: FakeDrafts = FakeDrafts()): Pair<CalibrationController, FakeDrafts> {
        val c = CalibrationController(drafts) { 1_000L }
        assertTrue(c.start(startParams(seedDatum = GeoDatums.WGS84.id)))
        return c to drafts
    }

    @Test
    fun aPlainPdfShowsTheDatumSheetFirstAndAGeoPdfPreselectsItsDatum() {
        // lifecycle.seed row 3: empty, the datum sheet unless the PDF has an embedded datum
        val seedRows = lifecycle["seed"]!!.jsonArray.map { it.jsonObject }
        assertEquals("empty", seedRows.last()["use"]!!.jsonPrimitive.content)
        val plain = CalibrationController(FakeDrafts())
        plain.start(startParams())
        assertEquals(CalibrationPhase.DatumSheet, plain.state.value!!.phase)
        // dismissing it without a pick means WGS84, same as "not sure"
        plain.dismissDatumSheet()
        assertEquals(GeoDatums.WGS84.id, plain.state.value!!.state.datumId)
        assertEquals(CalibrationPhase.Placing, plain.state.value!!.phase)

        val geo = CalibrationController(FakeDrafts())
        geo.start(startParams(embedded = "ED50"))
        assertEquals(CalibrationPhase.Placing, geo.state.value!!.phase)
        assertEquals("ED50", geo.state.value!!.state.datumId)
    }

    @Test
    fun seedAndResumeFollowTheLifecycleTables() {
        val manual = listOf(gridPoint(0), gridPoint(1), gridPoint(2))
        val draftPoints = manual + gridPoint(3)
        for (row in lifecycle["resume"]!!.jsonArray.map { it.jsonObject }) {
            fun flag(k: String) = (row[k] as JsonPrimitive).booleanOrNull!!
            val exists = flag("draftExists")
            val differs = flag("draftDiffersFromSeed")
            val draft = if (!exists) null else CalibrationDraft(
                contentKey = target.contentKey, pageIndex = 0, entryId = target.entryId, datumId = GeoDatums.WGS84.id,
                points = if (differs) draftPoints else manual, nextNumber = 5, active = flag("draftActive"), updatedAtMs = 5L,
            )
            val drafts = FakeDrafts().apply { draft?.let { drafts[it.key] = it } }
            val c = CalibrationController(drafts)
            c.start(startParams(draft = draft, seedPoints = manual, seedDatum = GeoDatums.WGS84.id))
            val s = c.state.value!!
            val label = row.toString()
            when (row["action"]!!.jsonPrimitive.content) {
                "none" -> {
                    assertEquals(label, CalibrationPhase.Placing, s.phase)
                    assertEquals(label, manual, s.state.points)
                }
                "prompt" -> {
                    assertEquals(label, CalibrationPhase.ResumePrompt, s.phase)
                    // nothing applied until they choose; Resume uses the draft
                    assertEquals(label, manual, s.state.points)
                    c.resume()
                    assertEquals(label, draftPoints, c.state.value!!.state.points)
                    // and Start over drops it and keeps the seed
                    val c2 = CalibrationController(drafts)
                    c2.start(startParams(draft = draft, seedPoints = manual, seedDatum = GeoDatums.WGS84.id))
                    c2.startOver()
                    assertEquals(label, manual, c2.state.value!!.state.points)
                    assertNull(label, drafts.drafts[target.draftKey])
                }
                "silentResume" -> {
                    assertEquals(label, CalibrationPhase.Placing, s.phase)
                    assertEquals(label, draftPoints, s.state.points)
                }
                else -> error("unknown action in $label")
            }
        }
        // the E3 toast carries the resumed point count
        assertEquals("calibration_resumed", lifecycle["entryPoints"]!!.jsonArray.map { it.jsonObject }
            .first { it["id"]!!.jsonPrimitive.content == "E3" }["toast"]!!.jsonObject["key"]!!.jsonPrimitive.content)
    }

    @Test
    fun aDraftForAnotherFileOrPageIsNeverApplied() {
        // D5-10: the key has to match, even if someone hands start() the wrong draft
        val other = CalibrationDraft(contentKey = "sha256:" + "cd".repeat(32), pageIndex = 0, entryId = target.entryId,
            points = listOf(gridPoint(0)), active = true)
        val c = CalibrationController(FakeDrafts())
        c.start(startParams(draft = other, seedDatum = GeoDatums.WGS84.id))
        assertTrue(c.state.value!!.state.points.isEmpty())
    }

    @Test
    fun typedPointsNumberUpRefitAtThreeAndWriteTheDraftEveryTime() {
        val (c, drafts) = started()
        val gen0 = c.state.value!!.display.generation
        c.typePoint(0)
        c.typePoint(1)
        assertTrue("still the provisional placement", c.state.value!!.display.provisional)
        assertEquals(gen0, c.state.value!!.display.generation)
        c.typePoint(2)
        // s7.1: a usable fit at n = 3 becomes the shown georef, new generation
        val shown = c.state.value!!.display
        assertTrue(shown.isFit)
        assertTrue(shown.generation > gen0)
        assertEquals(GeorefOrigin.FIDUCIARIES, shown.georef.origin)
        assertTrue(c.state.value!!.report.exact)
        c.typePoint(3)
        val s = c.state.value!!
        assertEquals(listOf(1, 2, 3, 4), s.state.sortedPoints.map { it.number })
        assertEquals(FitGrade.GOOD, s.report.grade)
        assertEquals(Finishability.Ready, s.report.finish)
        // every beginAdd + every mutation wrote the draft synchronously (4 + 4)
        assertEquals(8, drafts.saves)
        val d = drafts.drafts[target.draftKey]!!
        assertEquals(4, d.points.size)
        assertTrue(d.active)
        assertEquals(5, d.nextNumber)
        // the typed text of an open card isn't persisted, only where it's going
        assertTrue(c.beginAdd(capture(PagePoint(600.0, 600.0))))
        c.updateEntryText("32T MQ 99000 85000")
        assertEquals(StoredPagePoint(600.0, 600.0), drafts.drafts[target.draftKey]!!.pending!!.page)
        assertFalse(Json.encodeToString(CalibrationDraft.serializer(), drafts.drafts[target.draftKey]!!).contains("99000"))
    }

    @Test
    fun offSheetCaptureIsRefusedAndATapNeverPlaces() {
        val (c, drafts) = started()
        assertFalse(c.beginAdd(capture(PagePoint(-50.0, 10.0), onSheet = false)))
        assertFalse(c.beginAdd(null))
        assertEquals(CalibrationPhase.Placing, c.state.value!!.phase)
        assertEquals(0, drafts.saves)
        // a marker tap only selects
        c.mutate(CalibrationEdit.Add(corners[0].first, corners[0].second, gridPoint(0).reference, CalibrationPointKind.INTERSECTION))
        val id = c.state.value!!.state.points.single().id
        c.select(id)
        assertEquals(id, c.state.value!!.selectedId)
        assertEquals(1, c.state.value!!.state.points.size)
    }

    @Test
    fun deleteLeavesAGapUndoBringsItBackAndNumbersAreNeverReused() {
        val (c, _) = started()
        (0..3).forEach { c.typePoint(it) }
        val events = ArrayList<CalibrationEvent>()
        runBlocking {
            val job = launch { c.events.collect { events += it } }
            yield()
            val two = c.state.value!!.state.points.first { it.number == 2 }
            assertTrue(c.delete(two.id))
            yield()
            job.cancel()
        }
        assertEquals(listOf(1, 3, 4), c.state.value!!.state.sortedPoints.map { it.number })
        assertEquals(CalibrationEvent.PointDeleted(2), events.single())
        // the fixture's numbering rule: nextNumber++, a delete leaves a gap
        assertTrue(lifecycle["mutations"]!!.jsonObject["numbering"]!!.jsonPrimitive.content.contains("never renumbered"))
        c.typePoint(1)
        assertEquals(listOf(1, 3, 4, 5), c.state.value!!.state.sortedPoints.map { it.number })
        assertTrue(c.undo())
        assertTrue(c.undo())
        assertEquals(listOf(1, 2, 3, 4), c.state.value!!.state.sortedPoints.map { it.number })
    }

    @Test
    fun undoIsCappedAtFiftyAndPointsAtFifty() {
        assertEquals(lifecycle["mutations"]!!.jsonObject["undoDepth"]!!.jsonPrimitive.int, CalibrationState.UNDO_DEPTH)
        assertEquals(ImportLimits.MAX_CALIBRATION_POINTS, CalibrationState.MAX_POINTS)
        val (c, _) = started()
        val ref = gridPoint(0).reference
        repeat(CalibrationState.MAX_POINTS) { i ->
            val p = PagePoint(100.0 + 20 * (i % 10), 100.0 + 20 * (i / 10))
            assertTrue(c.mutate(CalibrationEdit.Add(p, "x", ref, CalibrationPointKind.INTERSECTION)))
        }
        assertEquals(CalibrationState.UNDO_DEPTH, c.undoDepth())
        // the 51st is refused, Add is off and the max-points status shows
        assertFalse(c.mutate(CalibrationEdit.Add(PagePoint(5.0, 5.0), "x", ref, CalibrationPointKind.INTERSECTION)))
        assertFalse(c.beginAdd(capture(PagePoint(5.0, 5.0))))
        assertEquals(CalibrationState.MAX_POINTS, c.state.value!!.state.points.size)
        assertEquals("calibration_max_points", lifecycle["mutations"]!!.jsonObject["maxPointsStatus"]!!.jsonObject["key"]!!.jsonPrimitive.content)
    }

    @Test
    fun aLockedKeyFlagsTheDraftUnsavedAndTheRetryClearsIt() {
        val drafts = FakeDrafts()
        val (c, _) = started(drafts)
        drafts.locked = true
        c.typePoint(0)
        assertTrue("calibration_draft_unsaved chip", c.state.value!!.draftUnsaved)
        // the point itself isn't lost, it's in the VM
        assertEquals(1, c.state.value!!.state.points.size)
        drafts.locked = false
        c.retryDraftWrite()
        assertFalse(c.state.value!!.draftUnsaved)
        assertEquals(1, drafts.drafts[target.draftKey]!!.points.size)
        assertEquals("calibration_draft_unsaved", lifecycle["draft"]!!.jsonObject["writeFailure"]!!.jsonObject["key"]!!.jsonPrimitive.content)
    }

    @Test
    fun leaveFollowsTheLeaveTable() {
        val rows = lifecycle["leave"]!!.jsonArray.map { it.jsonObject }
        // not dirty: ends straight away, no dialog
        val (clean, _) = started()
        assertEquals("endSession", rows.first { !(it["dirty"] as JsonPrimitive).booleanOrNull!! }["action"]!!.jsonPrimitive.content)
        assertTrue(clean.leaveTapped())
        assertNull(clean.state.value)

        val dialog = rows.first { (it["dirty"] as JsonPrimitive).booleanOrNull!! }
        assertEquals(listOf("calibration_leave_keep", "calibration_leave_discard", "calibration_leave_continue"),
            dialog["buttons"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content })

        // dirty -> dialog; Continue goes back
        val (keep, keepDrafts) = started()
        keep.typePoint(0)
        assertFalse(keep.leaveTapped())
        assertEquals(CalibrationPhase.LeaveDialog, keep.state.value!!.phase)
        keep.continueCalibrating()
        assertEquals(CalibrationPhase.Placing, keep.state.value!!.phase)
        // Keep points for later: draft.active = false, end
        keep.leaveTapped()
        keep.leave(keep = true)
        assertNull(keep.state.value)
        assertFalse(keepDrafts.drafts[target.draftKey]!!.active)
        assertEquals(1, keepDrafts.drafts[target.draftKey]!!.points.size)

        // Discard changes: the draft goes
        val (discard, discardDrafts) = started()
        discard.typePoint(0)
        discard.leaveTapped()
        discard.leave(keep = false)
        assertNull(discard.state.value)
        assertNull(discardDrafts.drafts[target.draftKey])
    }

    @Test
    fun backClosesTheCardOrSheetBeforeItOffersToLeave() {
        val (c, _) = started()
        assertTrue(c.beginAdd(capture(corners[0].first)))
        assertFalse(c.leaveTapped())
        assertEquals(CalibrationPhase.Placing, c.state.value!!.phase)
        c.openPoints()
        assertFalse(c.leaveTapped())
        assertEquals(CalibrationPhase.Placing, c.state.value!!.phase)
    }

    @Test
    fun aDifferentMapSuspendsAndKeepsTheDraft() {
        val (c, drafts) = started()
        c.typePoint(0)
        c.onActiveSourceChanged(target.entryId) // same entry, nothing happens
        assertNotNull(c.state.value)
        val events = ArrayList<CalibrationEvent>()
        runBlocking {
            val job = launch { c.events.collect { events += it } }
            yield()
            c.onActiveSourceChanged("another-entry")
            yield()
            job.cancel()
        }
        assertNull(c.state.value)
        assertFalse(drafts.drafts[target.draftKey]!!.active)
        assertEquals(CalibrationEvent.Paused, events.single())
        assertEquals("calibration_paused", lifecycle["suspend"]!!.jsonObject["toast"]!!.jsonObject["key"]!!.jsonPrimitive.content)
    }

    @Test
    fun finishIsBlockedThenConfirmedThenReady() {
        val (c, _) = started()
        c.typePoint(0)
        // blocked: Finish does nothing, the reason's in the status line
        assertTrue(c.state.value!!.report.finish is Finishability.Blocked)
        assertFalse(c.finishTapped())
        assertEquals(CalibrationPhase.Placing, c.state.value!!.phase)
        c.typePoint(1)
        c.typePoint(2)
        // exact 3: confirm, Add more points is the cancel
        assertFalse(c.finishTapped())
        assertEquals(CalibrationPhase.FinishConfirm, c.state.value!!.phase)
        val confirm = lifecycle["finishConfirm"]!!.jsonObject
        assertEquals("calibration_finish_confirm_title", confirm["title"]!!.jsonPrimitive.content)
        c.cancelFinish()
        assertEquals(CalibrationPhase.Placing, c.state.value!!.phase)
        c.typePoint(3)
        assertTrue(c.finishTapped())
    }

    @Test
    fun theCommitCarriesTheFitAndTheDraftGoesAfterIt() {
        val (c, drafts) = started()
        (0..3).forEach { c.typePoint(it) }
        val manual = requireNotNull(c.manualForCommit())
        assertEquals(4, manual.n)
        assertEquals(GeoDatums.WGS84.id, manual.datumId)
        assertEquals("good", manual.grade)
        assertEquals(0.0, manual.rmsM!!, 0.01)
        assertEquals(1_000L, manual.savedAtMs)
        val g = requireNotNull(PdfGeoreferenceCodec.decode(manual.georef))
        assertEquals(GeorefOrigin.FIDUCIARIES, g.origin)
        // the hold-out centre of d2_03 lands on 45.01845 9.0
        val centre = requireNotNull(g.toWGS84(638.9291339, 638.9291339))
        assertEquals(45.01845031784, centre.latitude, 1e-6)
        assertEquals(9.0, centre.longitude, 1e-6)
        val events = ArrayList<CalibrationEvent>()
        runBlocking {
            val job = launch { c.events.collect { events += it } }
            yield()
            c.endAfterCommit()
            yield()
            job.cancel()
        }
        assertNull(c.state.value)
        assertNull(drafts.drafts[target.draftKey])
        assertEquals(CalibrationEvent.Done(4, manual.rmsM!!), events.single())
    }

    @Test
    fun aFailedCommitStaysWithTheDraftKept() {
        val (c, drafts) = started()
        (0..3).forEach { c.typePoint(it) }
        c.commitFailed()
        assertEquals(CalibrationPhase.SaveFailed, c.state.value!!.phase)
        assertEquals(4, drafts.drafts[target.draftKey]!!.points.size)
        val failure = lifecycle["commit"]!!.jsonObject["failure"]!!.jsonObject
        assertTrue((failure["draftKept"] as JsonPrimitive).booleanOrNull!!)
        assertEquals("calibration_save_failed", failure["alert"]!!.jsonObject["key"]!!.jsonPrimitive.content)
    }

    @Test
    fun aDatumChangeIsUndoableAndRereadsEveryPoint() {
        val (c, _) = started()
        (0..3).forEach { c.typePoint(it) }
        val before = c.state.value!!.report.fitGeoref!!.toWGS84(638.9291339, 638.9291339)!!
        c.chooseDatum("ED50")
        val after = c.state.value!!.report.fitGeoref!!.toWGS84(638.9291339, 638.9291339)!!
        assertEquals("ED50", c.state.value!!.state.datumId)
        // ED50 -> WGS84 is ~100 m around northern Italy, the same grid figures land elsewhere
        assertTrue(PdfGeorefFixture.metres(before.latitude, before.longitude, after.latitude, after.longitude) > 50.0)
        assertTrue(c.undo())
        assertEquals(GeoDatums.WGS84.id, c.state.value!!.state.datumId)
    }

    @Test
    fun theEntryCheckWarnsOnASlipOnceThereIsAFitToPredictFrom() {
        val (c, _) = started()
        (0..3).forEach { c.typePoint(it) }
        // centre of the sheet, typed 1 km east of where the fit says it is
        assertTrue(c.beginAdd(capture(PagePoint(638.9291339, 638.9291339))))
        c.updateEntryText("32T NQ 01000 85000")
        val check = c.state.value!!.entry!!.check!!
        assertTrue("1 km off should warn: ${check.distanceM}", check.warn)
        assertEquals("calibration_warn_far", check.message!!.key)
        // the right figures don't
        c.updateEntryText("32T NQ 00000 85000")
        assertFalse(c.state.value!!.entry!!.check!!.warn)
    }

    @Test
    fun gpsFillOnlyAtTwentyMetresAndItsAWgs84Point() {
        val (c, _) = started()
        assertTrue(c.beginAdd(capture(corners[0].first)))
        assertFalse(c.useGps(45.1, 8.9, ImportLimits.GPS_MAX_ACCURACY_M + 0.1))
        assertTrue(c.useGps(45.1, 8.9, ImportLimits.GPS_MAX_ACCURACY_M))
        assertTrue(c.commitEntry())
        assertEquals("WGS84", c.state.value!!.state.points.single().datumOverride)
    }

    @Test
    fun everyLifecycleMessageKeyIsInTheCatalogueForBothPlatforms() {
        val catalog = Json.parseToJsonElement(PdfGeorefFixture.file("../localization/catalog.json").readText()).jsonObject
        val keys = HashSet<String>()
        fun walk(e: kotlinx.serialization.json.JsonElement) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) ->
                    if (k == "key" || k == "title" || k == "message") (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(keys::add)
                    walk(v)
                }
                is kotlinx.serialization.json.JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(lifecycle)
        lifecycle["leave"]!!.jsonArray.forEach { r -> r.jsonObject["buttons"]?.jsonArray?.forEach { b -> keys += b.jsonObject["key"]!!.jsonPrimitive.content } }
        keys += lifecycle["resume"]!!.jsonArray.mapNotNull { it.jsonObject["prompt"]?.jsonObject?.get("buttons")?.jsonObject?.keys }.flatten()
        val messageKeys = keys.filter { it.startsWith("calibration_") || it.startsWith("map_") }
        assertTrue("found only $messageKeys", messageKeys.size >= 15)
        for (k in messageKeys) {
            val row = catalog[k]?.jsonObject
            assertNotNull("$k missing from the catalogue", row)
            val platforms = row!!["platforms"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue("$k isn't on android", "android" in platforms)
            assertTrue("$k has no German", row["de"]!!.jsonPrimitive.content.isNotBlank())
        }
    }
}
