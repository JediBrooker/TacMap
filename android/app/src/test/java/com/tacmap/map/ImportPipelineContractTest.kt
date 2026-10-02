package com.tacmap.map

import com.tacmap.calibration.DuplicateInfo
import com.tacmap.calibration.GeorefRejectReason
import com.tacmap.calibration.ImportDecision
import com.tacmap.calibration.ImportOutcome
import com.tacmap.calibration.ImportedMapEntry
import com.tacmap.calibration.ImportedMapKind
import com.tacmap.calibration.InspectedPage
import com.tacmap.calibration.InspectionResult
import com.tacmap.calibration.PdfGeorefFixture
import com.tacmap.calibration.PdfInspection
import com.tacmap.calibration.fiducial.StatusMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * import_limits.json lifecycle.importPipeline + the E9 relink decision, driven through the
 * real post-copy stages and the real commit (PdfImportStages, PdfImportCommitter). The
 * pre-check and the copy itself need a ContentResolver, so those two only get their place
 * in the order checked here; the instrumented import tests run them for real
 */
class ImportPipelineContractTest {
    private val root = Json.parseToJsonElement(PdfGeorefFixture.file("import_limits.json").readText()).jsonObject
    private val pipeline = root["lifecycle"]!!.jsonObject["importPipeline"]!!.jsonObject
    private val dir: File = Files.createTempDirectory("import-stages").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun strings(k: String, o: JsonObject = pipeline) = o[k]!!.jsonArray.map { it.jsonPrimitive.content }

    private class Journal : DocumentImportCopyStateStore {
        val states = HashMap<String, DocumentImportCopyState>()
        var everMarked = false
        override fun state(operationKey: String) = states[operationKey]
        override fun persist(state: DocumentImportCopyState) {
            if (state.inspectStartedAtEpochMs != null) everMarked = true
            states[state.operationKey] = state
        }
    }

    private class Target : ImportCommitTarget {
        val calls = ArrayList<String>()
        var writeOk = true
        override fun addEntry(entry: ImportedMapEntry, activate: Boolean): Boolean { calls += "add:${entry.id}:$activate"; return writeOk }
        override fun activate(id: String): Boolean { calls += "activate:$id"; return true }
        override fun relink(id: String, file: File): Boolean { calls += "relink:$id:${file.name}"; return writeOk }
        override fun showRejectedPrompt(id: String, reason: GeorefRejectReason) { calls += "rejected:$id" }
        override fun showPagePicker(prepared: PreparedPdfImport) { calls += "picker" }
        override fun calibrate(id: String) { calls += "calibrate:$id" }
    }

    private val op = "pdf:test"
    private val journal = Journal().apply { persist(DocumentImportCopyState(op, DocumentImportCopyPhase.READY)) }
    private val stages = ArrayList<ImportStage>()
    private val notices = ArrayList<ImportNotice>()
    private val target = Target()
    private val released = ArrayList<File>()

    private fun copy(name: String = "import-0123456789abcdef.pdf"): File = File(dir, name).apply { writeText("%PDF-1.4 just bytes") }

    private val plainPage = InspectedPage(0, listOf(0.0, 0.0, 600.0, 400.0), null, 0)

    private fun inspectOk(@Suppress("UNUSED_PARAMETER") f: File, c: () -> Boolean, p: (Int, Int) -> Unit): InspectionResult {
        p(1, 1)
        return InspectionResult.Ok(PdfInspection(1, listOf(plainPage)))
    }

    private fun stages(
        inspect: (File, () -> Boolean, (Int, Int) -> Unit) -> InspectionResult = ::inspectOk,
        cancelled: () -> Boolean = { false },
    ) = PdfImportStages(journal, inspect, { 1L }, { stages += it }, cancelled)

    private fun library(existing: ImportedMapEntry? = null, unavailable: Boolean = false) = LibrarySnapshot(
        loaded = true,
        entryCount = if (existing == null) 0 else 1,
        byContentKey = { k -> existing?.takeIf { it.contentKey == k } },
        isUnavailable = { unavailable },
    )

    private fun committer(probe: suspend (ImportedMapEntry) -> Unit = {}, cancelled: () -> Boolean = { false }) = PdfImportCommitter(
        target = target,
        probe = ImportProbeStep<ImportedMapEntry>(
            sourceFor = { it },
            probe = probe,
            deleteCopy = { it.delete() },
            fallbackReason = { "fallback" },
        ),
        newEntry = { prepared, page -> entry("new", prepared.contentKey, page.index) },
        notify = { notices += it },
        onStage = { stages += it },
        isCancelled = cancelled,
        release = { released += it },
    )

    private fun entry(id: String, key: String, page: Int = 0) = ImportedMapEntry(
        id = id,
        kind = ImportedMapKind.PDF.code,
        fileName = "pdf_maps/import-$id.pdf",
        displayName = "Hut map",
        contentKey = key,
        byteCount = 1,
        fileModifiedAtMs = 1,
        importedAtMs = 1,
        pdf = MapImportPipeline.pdfInfo(1, plainPage.copy(index = page), null),
    )

    private val key = "sha256:" + "a".repeat(64)

    private val georef = com.tacmap.calibration.PdfGeoreferenceCodec.encode(
        com.tacmap.calibration.PdfGeoreference(
            page = 0, crs = com.tacmap.calibration.GeoCrs.utm(56, true), datum = com.tacmap.calibration.GeoDatums.WGS84,
            affine = com.tacmap.calibration.PlaneAffine(17.6, 0.0, 330_000.0, 0.0, 17.6, 6_240_000.0),
            crop = listOf(0.0 to 0.0, 600.0 to 0.0, 600.0 to 400.0, 0.0 to 400.0).map { com.tacmap.calibration.PagePoint(it.first, it.second) },
            origin = com.tacmap.calibration.GeorefOrigin.ADOBE_VP,
        )
    )

    @Test
    fun stagesRunInTheSharedOrder() = runBlocking {
        assertEquals(strings("order"), ImportStage.entries.map { it.code })
        val file = copy()
        val out = stages().afterCopy(file, key, "Hut map", op, library()) {}
        val prepared = (out as PreparedOutcome.Pdf).prepared
        assertTrue(prepared.outcome is ImportOutcome.AddAndCalibrate)
        committer().commit(prepared)
        // precheck and copyAndHash happen in runPdf before afterCopy, see the class note
        assertEquals(strings("order").drop(2), stages.map { it.code })
        assertEquals(listOf("add:new:false", "calibrate:new"), target.calls)
        assertEquals(listOf(file), released)
    }

    @Test
    fun aDuplicateSkipsInspectDecisionAndProbe() = runBlocking {
        val existing = entry("old", key).copy(pdf = entry("old", key).pdf!!.copy(embedded = null))
        val file = copy()
        val out = stages().afterCopy(file, key, "whatever", op, library(existing)) {}
        val prepared = (out as PreparedOutcome.Pdf).prepared
        committer().commit(prepared)
        val skipped = strings("duplicateSkips")
        assertEquals(listOf("inspect", "decision", "probe"), skipped)
        assertTrue(stages.none { it.code in skipped })
        assertFalse("the copy of a duplicate goes", file.exists())
        assertFalse("no inspection, no marker", journal.everMarked)
        // no georef on it: calibration starts on the existing entry
        assertEquals(listOf("calibrate:old"), target.calls)
        assertEquals(StatusMessage("map_import_duplicate", mapOf("name" to "Hut map")), (notices.single() as ImportNotice.Toast).message)
    }

    @Test
    fun aDuplicateOfAnUnavailableEntryKeepsTheCopyAndRelinks() = runBlocking {
        // decisions[] first: relink is exactly "the existing entry is unavailable"
        for (r in root["decisions"]!!.jsonArray.map { it.jsonObject }) {
            val d = r["duplicate"] as? JsonObject ?: continue
            val info = DuplicateInfo(
                (d["existingHasGeoref"] as JsonPrimitive).booleanOrNull!!,
                d["name"]!!.jsonPrimitive.content,
                (d["existingUnavailable"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
            val want = r["outcome"]!!.jsonObject
            val got = ImportDecision.decide(r["pageCount"]!!.jsonPrimitive.content.toInt(), null, info)
            val relink = when (got) {
                is ImportOutcome.ActivateExisting -> got.relink.also { assertEquals("activateExisting", want["action"]!!.jsonPrimitive.content) }
                is ImportOutcome.CalibrateExisting -> got.relink.also { assertEquals("calibrateExisting", want["action"]!!.jsonPrimitive.content) }
                else -> error("duplicate row ${r["id"]} decided $got")
            }
            assertEquals("${r["id"]} relink", (want["relink"] as JsonPrimitive).booleanOrNull, relink)
        }
        assertTrue(root["decisions"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == "duplicate_of_unavailable_entry" })

        val existing = entry("old", key).let { e -> e.copy(pdf = e.pdf!!.copy(embedded = georef)) }
        val file = copy()
        val out = stages().afterCopy(file, key, "whatever", op, library(existing, unavailable = true)) {}
        val prepared = (out as PreparedOutcome.Pdf).prepared
        assertTrue("the verified copy stays for the relink", file.exists())
        assertEquals(true, (prepared.outcome as ImportOutcome.ActivateExisting).relink)
        committer().commit(prepared)
        // one write re-links first, then the duplicate rule (it has a georef: activate)
        assertEquals(listOf("relink:old:${file.name}", "activate:old"), target.calls)
        assertEquals("map_import_duplicate", (notices.single() as ImportNotice.Toast).message.key)
    }

    @Test
    fun aFailedRelinkStopsThere() = runBlocking {
        target.writeOk = false
        val file = copy()
        val prepared = (stages().afterCopy(file, key, "x", op, library(entry("old", key), unavailable = true)) {} as PreparedOutcome.Pdf).prepared
        committer().commit(prepared)
        assertEquals(listOf("relink:old:${file.name}"), target.calls)
        assertTrue(notices.isEmpty())
        assertEquals(listOf(file), released)
    }

    @Test
    fun cancelIsOnlyOfferedWhileCopyingAndReading() {
        val shown = mapOf(
            "copying" to ImportProgress.Copying(1, 2),
            "reading" to ImportProgress.Reading(1, 2),
            "saving" to ImportProgress.Saving,
        ).filterValues { it.cancelVisible }.keys.toList()
        assertEquals(strings("cancelVisible"), shown)
    }

    @Test
    fun cancelIsRecheckedAfterTheCopyTheInspectionAndTheProbe() = runBlocking {
        assertEquals(listOf("after copyAndHash", "after inspect", "after probe"), strings("cancelRechecked"))
        val onCancel = pipeline["onCancel"]!!.jsonObject
        assertEquals(true, (onCancel["discardCopy"] as JsonPrimitive).booleanOrNull)
        assertEquals(false, (onCancel["write"] as JsonPrimitive).booleanOrNull)
        assertEquals("map_import_cancelled", onCancel["toast"]!!.jsonObject["key"]!!.jsonPrimitive.content)

        // after the copy: nothing else runs, not even the dedupe
        val a = copy("a.pdf")
        assertCancelled { stages(cancelled = { true }).afterCopy(a, key, "x", op, library()) {} }
        assertFalse(a.exists())
        assertTrue(stages.isEmpty())
        assertFalse(journal.everMarked)

        // during the inspection: the copy goes once it's back, no decision, marker cleared
        var flag = false
        val b = copy("b.pdf")
        val st = stages(inspect = { f, c, p -> inspectOk(f, c, p).also { flag = true } }, cancelled = { flag })
        assertCancelled { st.afterCopy(b, key, "x", op, library()) {} }
        assertFalse(b.exists())
        assertEquals(listOf(ImportStage.DEDUPE, ImportStage.INSPECT), stages)
        assertEquals(null, journal.state(op)!!.inspectStartedAtEpochMs)

        // during the probe: no write
        stages.clear()
        flag = false
        val c = copy("c.pdf")
        val prepared = (stages().afterCopy(c, key, "x", op, library()) {} as PreparedOutcome.Pdf).prepared
        assertCancelled { committer(probe = { flag = true }, cancelled = { flag }).commit(prepared) }
        assertFalse(c.exists())
        assertTrue(target.calls.none { it.startsWith("add") })
        assertFalse(stages.contains(ImportStage.WRITE))
    }

    @Test
    fun aProbeFailureCommitsNothingAndSaysWhyInAnAlert() = runBlocking {
        // M12: the WP2 reason copy, as an alert (OD-F5), and the copy is gone
        val file = copy()
        val prepared = (stages().afterCopy(file, key, "x", op, library()) {} as PreparedOutcome.Pdf).prepared
        committer(probe = { throw PdfImportRejectedException("TacMap couldn't draw this page") }).commit(prepared)
        assertTrue(target.calls.isEmpty())
        assertEquals(ImportNotice.Alert("TacMap couldn't draw this page"), notices.single())
        assertFalse(file.exists())
        assertEquals(listOf(file), released)
        assertFalse(stages.contains(ImportStage.WRITE))
    }

    @Test
    fun pageUsedToastOnlyAfterTheWriteTookIt() = runBlocking {
        val toast = StatusMessage("map_import_page_used", mapOf("page" to 2, "pages" to 2))
        val two = PdfInspection(2, listOf(plainPage, plainPage.copy(index = 1)))
        val prepared = PreparedPdfImport(copy(), key, "x", two, ImportOutcome.AddAndActivate(1, toast))
        target.writeOk = false
        committer().commit(prepared)
        assertTrue("no toast for a write that didn't happen", notices.isEmpty())
        target.writeOk = true
        committer().commit(prepared.copy(file = copy("again.pdf")))
        assertEquals(ImportNotice.Toast(toast), notices.single())
        assertEquals("add:new:true", target.calls.last())
    }

    private suspend fun assertCancelled(block: suspend () -> Unit) {
        try {
            block()
            fail("expected the import to stop")
        } catch (_: CancellationException) {
        }
    }
}
