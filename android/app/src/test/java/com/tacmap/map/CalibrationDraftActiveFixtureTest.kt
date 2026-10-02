package com.tacmap.map

import com.tacmap.calibration.CalibrationDraftStorage
import com.tacmap.calibration.GeoDatums
import com.tacmap.calibration.PagePoint
import com.tacmap.calibration.PdfGeoreference
import com.tacmap.calibration.PdfGeorefFixture
import com.tacmap.calibration.Wgs84Coordinate
import com.tacmap.calibration.fiducial.CalibrationCapture
import com.tacmap.calibration.fiducial.CalibrationDraft
import com.tacmap.calibration.fiducial.CalibrationPoint
import com.tacmap.calibration.fiducial.CalibrationReference
import com.tacmap.calibration.fiducial.CalibrationTarget
import com.tacmap.calibration.fiducial.StoredPagePoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * import_limits.json lifecycle.draftActive (T3; C1, C5, C6): each row's steps through the
 * real controller and a draft store, then the draft's active flag, the session and the toast.
 */
class CalibrationDraftActiveFixtureTest {
    private val rows = Json.parseToJsonElement(PdfGeorefFixture.file("import_limits.json").readText())
        .jsonObject["lifecycle"]!!.jsonObject["draftActive"]!!.jsonArray.map { it.jsonObject }

    private class Drafts : CalibrationDraftStorage {
        val drafts = LinkedHashMap<String, CalibrationDraft>()
        var everSaved = false
        override fun load(key: String): CalibrationDraft? = drafts[key]?.takeIf { it.key == key }
        override fun save(draft: CalibrationDraft): Boolean {
            everSaved = true
            drafts[draft.key] = draft
            return true
        }
        override fun delete(key: String): Boolean { drafts.remove(key); return true }
        override fun all(): List<CalibrationDraft> = drafts.values.toList()
    }

    // same 1:50k grid square sheet the controller test uses
    private val box = listOf(PagePoint(0.0, 0.0), PagePoint(1277.8582677, 0.0), PagePoint(1277.8582677, 1277.8582677), PagePoint(0.0, 1277.8582677))
    private val target = CalibrationTarget("entry-1", "sha256:" + "ab".repeat(32), 0, box, 0)
    private val corners = listOf(
        PagePoint(128.6929134, 1149.1653543) to "32T MQ 91000 94000",
        PagePoint(1149.1653543, 1149.1653543) to "32T NQ 09000 94000",
        PagePoint(128.6929134, 128.6929134) to "32T MQ 91000 76000",
        PagePoint(1149.1653543, 128.6929134) to "32T NQ 09000 76000",
    )
    private val base: PdfGeoreference = requireNotNull(PdfGeoreference.provisional(Wgs84Coordinate(45.0, 9.0), box))

    private fun params(draft: CalibrationDraft?, datum: String?, silent: Boolean = false) = CalibrationStart(
        target = target, entryName = "Sheet", base = base, savedEffective = null,
        seedDatumId = datum, seedPoints = emptyList(), embeddedDatumId = null,
        draft = draft, isPreview = true, resumeSilently = silent,
    )

    private fun point(i: Int): CalibrationPoint {
        val (page, input) = corners[i]
        return CalibrationPoint(
            id = "d$i", number = i + 1, page = StoredPagePoint.of(page), input = input,
            reference = CalibrationReference.Grid(32, false, 491_000.0, 4_994_000.0),
        )
    }

    /** draftBefore: an inactive draft has a point the seed doesn't (so the prompt asks), an active one is empty */
    private fun draftBefore(kind: String): CalibrationDraft? = when (kind) {
        "none" -> null
        "inactive" -> CalibrationDraft(target.contentKey, 0, target.entryId, GeoDatums.WGS84.id, listOf(point(0)), 2, null, false, 500L)
        "active" -> CalibrationDraft(target.contentKey, 0, target.entryId, GeoDatums.WGS84.id, emptyList(), 1, null, true, 500L)
        else -> error("draftBefore $kind")
    }

    private fun capture(p: PagePoint) = CalibrationCapture(p, true, 2.0, false)

    private fun CalibrationController.save() {
        val n = state.value!!.state.points.size
        if (state.value!!.phase !is CalibrationPhase.Entering) assertTrue(beginAdd(capture(corners[n].first)))
        updateEntryText(corners[n].second)
        assertNotNull(state.value!!.entry!!.parsed)
        assertTrue(commitEntry())
    }

    @Test
    fun everyDraftActiveRowHoldsOnAndroid() {
        assertTrue("draftActive rows", rows.size >= 18)
        for (row in rows) {
            val id = row["id"]!!.jsonPrimitive.content
            val drafts = Drafts()
            draftBefore(row["draftBefore"]!!.jsonPrimitive.content)?.let { drafts.drafts[it.key] = it }
            drafts.everSaved = drafts.drafts.isNotEmpty()
            val c = CalibrationController(drafts) { 1_000L }
            var died = false
            val events = ArrayList<CalibrationEvent>()
            runBlocking {
                val job = launch { c.events.collect { events += it } }
                yield()
                for (step in row["steps"]!!.jsonArray.map { it.jsonPrimitive.content }) {
                    when (step) {
                        "start" -> assertTrue(id, c.start(params(drafts.load(target.draftKey), GeoDatums.WGS84.id)))
                        "startPlainPdf" -> {
                            assertTrue(id, c.start(params(drafts.load(target.draftKey), null)))
                            assertEquals(id, CalibrationPhase.DatumSheet, c.state.value!!.phase)
                        }
                        "launchAutoResume" -> assertTrue(id, c.start(params(drafts.load(target.draftKey), GeoDatums.WGS84.id, silent = true)))
                        "chooseDatumFromInitialSheet" -> c.chooseDatum("ED50")
                        "beginAdd" -> assertTrue(id, c.beginAdd(capture(corners[c.state.value!!.state.points.size].first)))
                        "cancelEntry" -> c.cancelEntry()
                        "savePoint" -> c.save()
                        "savePoint3" -> repeat(3) { c.save() }
                        "undo" -> assertTrue(id, c.undo())
                        "leave" -> c.leaveTapped()
                        "leaveKeep" -> c.leave(keep = true)
                        "leaveDiscard" -> c.leave(keep = false)
                        "leaveContinue" -> c.continueCalibrating()
                        "suspend" -> c.suspend()
                        "resumePromptResume" -> {
                            assertEquals(id, CalibrationPhase.ResumePrompt, c.state.value!!.phase)
                            c.resume()
                        }
                        "resumePromptStartOver" -> {
                            assertEquals(id, CalibrationPhase.ResumePrompt, c.state.value!!.phase)
                            c.startOver()
                        }
                        "setDatum" -> c.chooseDatum("ED50")
                        // what MapViewModel.finishCalibration does once the library write lands
                        "finish" -> {
                            c.finishTapped()
                            assertNotNull(id, c.manualForCommit())
                            c.endAfterCommit()
                        }
                        // the process goes, only what's on disk is left
                        "processDeath" -> died = true
                        else -> error("$id: step $step")
                    }
                    yield()
                }
                yield()
                job.cancel()
            }
            val expect = row["expect"]!!.jsonObject
            val d = drafts.load(target.draftKey)
            val draft = when {
                d == null -> if (drafts.everSaved) "deleted" else "none"
                d.active -> "active"
                else -> "inactive"
            }
            assertEquals("$id draft", expect["draft"]!!.jsonPrimitive.content, draft)
            assertEquals("$id session", expect["session"]!!.jsonPrimitive.content, if (c.isActive && !died) "running" else "ended")
            val toast = expect["toast"]
            val last = events.lastOrNull()?.let(::toastOf)
            if (toast == null || toast is JsonNull) {
                assertEquals("$id toast", null, last)
            } else {
                val t = toast.jsonObject
                val args = (t["args"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
                assertEquals("$id toast", t["key"]!!.jsonPrimitive.content to args, last)
            }
        }
    }

    /** the toast MapScreen shows for an event, as key + args */
    private fun toastOf(e: CalibrationEvent): Pair<String, Map<String, String>> = when (e) {
        is CalibrationEvent.PointDeleted -> "calibration_point_deleted" to mapOf("number" to e.number.toString())
        is CalibrationEvent.DatumChanged -> "calibration_datum_changed" to mapOf("datum" to CalibrationText.datumName(e.datumId))
        is CalibrationEvent.Resumed -> "calibration_resumed" to mapOf("points" to e.points.toString())
        CalibrationEvent.Paused -> "calibration_paused" to emptyMap()
        is CalibrationEvent.Done -> "calibration_done" to emptyMap()
        CalibrationEvent.DoneExact -> "calibration_done_exact" to emptyMap()
        CalibrationEvent.MaxPoints -> "calibration_max_points" to emptyMap()
    }
}
