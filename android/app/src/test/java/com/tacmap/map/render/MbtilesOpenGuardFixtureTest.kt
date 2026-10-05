package com.tacmap.map.render

import com.tacmap.calibration.PdfGeorefFixture
import com.tacmap.map.render.pdf.GuardResolution
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** import_limits.json mbtilesOpenGuard (s14.1): the reducer, its file and the first draw window */
class MbtilesOpenGuardFixtureTest {
    private val fx = Json.parseToJsonElement(PdfGeorefFixture.file("import_limits.json").readText())
        .jsonObject["mbtilesOpenGuard"]!!.jsonObject
    private val a = "00000000-0000-4000-8000-000000000001"
    private val b = "00000000-0000-4000-8000-000000000002"

    @Test
    fun constantsAreTheSharedOnes() {
        assertEquals(fx["fileName"]!!.jsonPrimitive.content, MbtilesOpenGuard.FILE_NAME)
        assertEquals(fx["fileVersion"]!!.jsonPrimitive.int, MbtilesOpenGuardState.FILE_VERSION)
        assertEquals(fx["maxInProgress"]!!.jsonPrimitive.int, MbtilesOpenGuardState.MAX_IN_PROGRESS)
        assertEquals(fx["firstDrawQuietMs"]!!.jsonPrimitive.long, MbtilesFirstDraw.FIRST_DRAW_QUIET_MS)
        assertEquals(fx["noReadCompleteMs"]!!.jsonPrimitive.long, MbtilesFirstDraw.NO_READ_COMPLETE_MS)
    }

    @Test
    fun everyGuardCaseStepsTheSharedWay() {
        val cases = fx["cases"]!!.jsonArray.map { it.jsonObject }
        assertTrue("only ${cases.size} cases", cases.size >= 9)
        for (case in cases) {
            val label = case["label"]!!.jsonPrimitive.content
            val g = MbtilesOpenGuardState()
            for ((i, step) in case["steps"]!!.jsonArray.map { it.jsonObject }.withIndex()) {
                val what = "$label step $i"
                assertEquals("$what result", step["result"]!!.jsonObject, apply(g, step["event"]!!.jsonObject))
                assertEquals("$what state", step["state"]!!.jsonObject, g.toJson())
                // what's on disk reads back the same, order included
                val back = MbtilesOpenGuardState.fromJson(Json.parseToJsonElement(g.toJson().toString()))
                assertEquals("$what round trip", g.toJson(), back.toJson())
            }
        }
    }

    private fun apply(g: MbtilesOpenGuardState, ev: JsonObject): JsonObject {
        fun s(k: String) = (ev[k] as? JsonPrimitive)?.contentOrNull
        return when (s("op")) {
            "arm" -> JsonObject(mapOf("armed" to JsonPrimitive(g.arm(s("token")!!, (ev["foreground"] as JsonPrimitive).booleanOrNull!!))))
            "complete" -> { g.complete(s("token")!!); JsonObject(emptyMap()) }
            "disarmBackground" -> { g.disarmBackground(); JsonObject(emptyMap()) }
            "launch" -> JsonObject(mapOf("decision" to JsonPrimitive(g.launch(s("restoredToken")).code)))
            "resolve" -> {
                g.resolve(GuardResolution.entries.first { it.code == s("choice") })
                JsonObject(emptyMap())
            }
            else -> error("unknown op ${s("op")}")
        }
    }

    @Test
    fun theGuardFileSurvivesARelaunchAndOnlyOpenAnywayOrDeleteClearsIt() {
        val dir = Files.createTempDirectory("mbtiles-guard").toFile()
        try {
            val file = File(dir, MbtilesOpenGuard.FILE_NAME)
            // the restore armed it and the process died before the first draw settled
            assertTrue(MbtilesOpenGuard(file).arm(a, foreground = true))
            assertTrue(file.isFile)
            assertFalse(File(dir, MbtilesOpenGuard.FILE_NAME + ".tmp").exists())
            val next = MbtilesOpenGuard(file)
            assertFalse(next.launchDecided)
            assertEquals(MbtilesLaunchDecision.SUPPRESS, next.launchDecision(a))
            // a later restore in the same process still holds it back, another pack restores fine
            assertEquals(MbtilesLaunchDecision.SUPPRESS, next.launchDecision(a))
            assertEquals(MbtilesLaunchDecision.NONE, next.launchDecision(b))
            assertTrue(next.isSuspect(a))
            // Not Now: asked again next launch
            next.resolve(GuardResolution.NOT_NOW)
            val third = MbtilesOpenGuard(file)
            assertEquals(MbtilesLaunchDecision.SUPPRESS, third.launchDecision(a))
            third.resolve(GuardResolution.OPEN_ANYWAY)
            assertEquals(MbtilesLaunchDecision.NONE, third.launchDecision(a))
            assertEquals(MbtilesLaunchDecision.NONE, MbtilesOpenGuard(file).launchDecision(a))
            // a clean open: armed, then done after its first draw, nothing to ask about
            MbtilesOpenGuard(file).run { assertTrue(arm(b, true)); complete(b) }
            assertEquals(MbtilesLaunchDecision.NONE, MbtilesOpenGuard(file).launchDecision(b))
            // background never arms, and going there drops what's armed
            assertFalse(MbtilesOpenGuard(file).arm(a, foreground = false))
            MbtilesOpenGuard(file).run { arm(a, true); disarmBackground() }
            assertEquals(MbtilesLaunchDecision.NONE, MbtilesOpenGuard(file).launchDecision(a))
            // Delete Map clears the suspect too
            MbtilesOpenGuard(file).arm(a, true)
            MbtilesOpenGuard(file).run { assertEquals(MbtilesLaunchDecision.SUPPRESS, launchDecision(a)); resolve(GuardResolution.DELETED) }
            assertEquals(MbtilesLaunchDecision.NONE, MbtilesOpenGuard(file).launchDecision(null))
            // only entry ids go in, never a name or path
            assertFalse(MbtilesOpenGuard(file).arm("my-map.mbtiles", true))
            // junk on disk fails open
            file.writeText("{not json")
            assertEquals(MbtilesLaunchDecision.NONE, MbtilesOpenGuard(file).launchDecision(a))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun decodingDropsJunkAndKeepsTheNewestFour() {
        val ids = (1..6).map { "00000000-0000-4000-8000-00000000000$it" }
        val raw = """{"v":1,"inProgress":["${ids[0]}","not-a-uuid","${ids[1]}","${ids[2]}",7,"${ids[3]}","${ids[4]}","${ids[5]}"],"suspect":"../etc"}"""
        val g = MbtilesOpenGuardState.fromJson(Json.parseToJsonElement(raw))
        assertEquals(ids.takeLast(4), g.armed)
        assertEquals(null, g.suspect)
        assertEquals(emptyList<String>(), MbtilesOpenGuardState.fromJson(Json.parseToJsonElement("""{"v":2,"inProgress":["${ids[0]}"]}""")).armed)
        assertEquals(emptyList<String>(), MbtilesOpenGuardState.fromJson(null as JsonElement?).armed)
    }

    @Test
    fun theFirstDrawSettlesAfterAQuietReadOrWhenNothingIsAsked() {
        var now = 1_000L
        // nothing asked for: done at noReadCompleteMs, not before
        val idle = MbtilesFirstDraw { now }
        now += MbtilesFirstDraw.NO_READ_COMPLETE_MS - 1
        assertFalse(idle.settled())
        now += 1
        assertTrue(idle.settled())

        now = 0L
        val drawing = MbtilesFirstDraw { now }
        drawing.readStarted()
        drawing.readStarted()
        // still reading long after the no read limit: not done, a read in flight is the risky bit
        now += 10_000
        assertFalse(drawing.settled())
        drawing.readEnded(delivered = true)
        now += 1_000
        assertFalse("one still pending", drawing.settled())
        drawing.readEnded(delivered = true)
        now += MbtilesFirstDraw.FIRST_DRAW_QUIET_MS - 1
        assertFalse(drawing.settled())
        now += 1
        assertTrue(drawing.settled())

        // only cancelled reads: nothing came back yet, keep waiting
        now = 0L
        val cancelled = MbtilesFirstDraw { now }
        cancelled.readStarted()
        cancelled.readEnded(delivered = false)
        now += 60_000
        assertFalse(cancelled.settled())
    }
}
