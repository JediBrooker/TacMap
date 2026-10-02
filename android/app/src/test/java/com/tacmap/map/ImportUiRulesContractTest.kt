package com.tacmap.map

import com.tacmap.calibration.ActiveRef
import com.tacmap.calibration.ImportPrecheck
import com.tacmap.calibration.ImportedMapEntry
import com.tacmap.calibration.ImportedMapKind
import com.tacmap.calibration.LibraryState
import com.tacmap.calibration.ManualCalibration
import com.tacmap.calibration.PdfEntryInfo
import com.tacmap.calibration.PdfGeorefFixture
import com.tacmap.calibration.PersistedPdfBake
import com.tacmap.calibration.GeoCrs
import com.tacmap.calibration.GeoDatums
import com.tacmap.calibration.GeorefOrigin
import com.tacmap.calibration.PagePoint
import com.tacmap.calibration.PdfGeoreference
import com.tacmap.calibration.PdfGeoreferenceCodec
import com.tacmap.calibration.PlaneAffine
import com.tacmap.calibration.fiducial.StatusMessage
import com.tacmap.localization.Messages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** import_limits.json sizeDisplay, prechecks[].expect.argText, lifecycle.choosePage + the Layers bits around them */
class ImportUiRulesContractTest {
    private val root = Json.parseToJsonElement(PdfGeorefFixture.file("import_limits.json").readText()).jsonObject
    private val locales = mapOf("en" to Locale.ENGLISH, "de" to Locale.GERMAN)

    @Test
    fun byteArgsUseTheSharedSizeRule() {
        val cases = root["sizeDisplay"]!!.jsonObject["cases"]!!.jsonArray.map { it.jsonObject }
        assertEquals(5, cases.size)
        for (c in cases) {
            val bytes = c["bytes"]!!.jsonPrimitive.long
            locales.forEach { (tag, loc) -> assertEquals("$bytes $tag", c[tag]!!.jsonPrimitive.content, ImportUiRules.byteText(bytes, loc)) }
        }
    }

    @Test
    fun precheckArgTextMatches() {
        var withText = 0
        for (r in root["prechecks"]!!.jsonArray.map { it.jsonObject }) {
            val got = ImportPrecheck.check(
                libraryLoaded = r["library"]!!.jsonPrimitive.content == "loaded",
                entryCount = r["entryCount"]!!.jsonPrimitive.int,
                kind = if (r["kind"]!!.jsonPrimitive.content == "pdf") ImportedMapKind.PDF else ImportedMapKind.MBTILES,
                sizeBytes = r["sizeBytes"]!!.jsonPrimitive.long,
                freeBytes = r["freeBytes"]!!.jsonPrimitive.long,
            )
            val argText = (r["expect"] as? JsonObject)?.get("argText") as? JsonObject ?: continue
            withText++
            locales.forEach { (tag, loc) ->
                val want = argText[tag]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
                assertEquals("${r["id"]} $tag", want, ImportUiRules.failureArgText(got!!, loc))
            }
        }
        // pdf + mbtiles over the limit, one byte short of space
        assertEquals(3, withText)
    }

    @Test
    fun choosePageRowsMatch() {
        val cp = root["lifecycle"]!!.jsonObject["choosePage"]!!.jsonObject
        val picker = cp["pickerMessage"]!!.jsonObject
        assertEquals(picker["key"]!!.jsonPrimitive.content, ChoosePageRules.PICKER_MESSAGE_KEY)
        val pages = picker["args"]!!.jsonObject["pages"]!!.jsonPrimitive.int
        // E14: the Layers wording, not the import one that says none has a georef
        assertTrue(Messages.mapChoosePageMessage(pages.toString()).contains(pages.toString()))
        assertTrue(Messages.mapChoosePageMessage("3") != Messages.mapImportChoosePageMessage("3"))
        val rows = cp["rows"]!!.jsonArray.map { it.jsonObject }
        assertEquals(5, rows.size)
        for (r in rows) {
            val id = r["id"]!!.jsonPrimitive.content
            val d = ChoosePageRules.decide(
                currentPage = r["currentPage"]!!.jsonPrimitive.int,
                pickedPage = r["pickedPage"]!!.jsonPrimitive.int,
                hasManual = (r["hasManual"] as JsonPrimitive).booleanOrNull!!,
                pickedHasValidGeoref = r["pickedPageGeoref"]!!.jsonPrimitive.content == "valid",
                entryIsActive = (r["entryIsActive"] as JsonPrimitive).booleanOrNull!!,
            )
            val e = r["expect"]!!.jsonObject
            assertEquals("$id confirm", (e["confirm"] as? JsonPrimitive)?.takeIf { it.isString }?.content, d.confirm)
            assertEquals("$id write", (e["write"] as JsonPrimitive).booleanOrNull, d.write)
            assertEquals("$id then", e["then"]!!.jsonPrimitive.content, d.then.code)
            assertEquals("$id activeOnline", (e["activeOnline"] as? JsonPrimitive)?.booleanOrNull ?: false, d.activeOnline)
        }
    }

    private fun pdfEntry(id: String, bytes: Long, bake: PersistedPdfBake? = null, manual: ManualCalibration? = null) = ImportedMapEntry(
        id = id, kind = ImportedMapKind.PDF.code, fileName = "pdf_maps/import-$id.pdf", displayName = id,
        contentKey = "sha256:" + "b".repeat(64), byteCount = bytes, fileModifiedAtMs = 1, importedAtMs = 1,
        pdf = PdfEntryInfo(1, 0, 0, listOf(listOf(0.0, 0.0), listOf(1.0, 0.0), listOf(1.0, 1.0), listOf(0.0, 1.0)), bake = bake, manual = manual),
    )

    @Test
    fun footerCountsBakeFiles() {
        val bake = PersistedPdfBake("tacmap-bake-0123456789abcdef.mbtiles", "c".repeat(64), 10, 16, 256, 5_000_000)
        val st = LibraryState(
            active = ActiveRef.online("OSM_TOPO"), preferredOnlineStyle = "OSM_TOPO",
            entries = listOf(pdfEntry("a", 100_000_000, bake), pdfEntry("b", 2_000_000)),
        )
        assertEquals(107_000_000L, ImportUiRules.footerBytes(st))
        // a record we'd never have written doesn't count, same as the sweep
        val junk = st.copy(entries = listOf(pdfEntry("c", 1, bake.copy(fileName = "../x.mbtiles"))))
        assertEquals(1L, ImportUiRules.footerBytes(junk))
    }

    @Test
    fun calibratedBlockUsesTheStateLabel() {
        assertNull(ImportUiRules.calibratedBlockLabel(null))
        val g = PdfGeoreferenceCodec.encode(
            PdfGeoreference(
                page = 0, crs = GeoCrs.utm(56, true), datum = GeoDatums.WGS84,
                affine = PlaneAffine(17.6, 0.0, 330_000.0, 0.0, 17.6, 6_240_000.0),
                crop = listOf(PagePoint(0.0, 0.0), PagePoint(1.0, 0.0), PagePoint(1.0, 1.0), PagePoint(0.0, 1.0)),
                origin = GeorefOrigin.FIDUCIARIES,
            )
        )
        val fit = ManualCalibration("WGS84", emptyList(), g, n = 5, rmsM = 4.2, grade = "good", savedAtMs = 1)
        assertEquals(StatusMessage("map_state_calibrated", mapOf("points" to 5, "rms" to 4.2)), ImportUiRules.calibratedBlockLabel(fit))
        assertEquals(
            StatusMessage("map_state_calibrated_exact", mapOf("points" to 3)),
            ImportUiRules.calibratedBlockLabel(fit.copy(n = 3, rmsM = null, grade = null)),
        )
    }

    @Test
    fun bakeConfirmAndGenerateGating() {
        assertEquals("pdf_bake_confirm_message", ImportUiRules.bakeConfirmKey(onScreen = true))
        assertEquals("pdf_bake_confirm_message_inactive", ImportUiRules.bakeConfirmKey(onScreen = false))
        assertTrue(Messages.pdfBakeConfirmMessageInactive("Hut", "5 min").contains("Hut"))
        assertEquals(ImportUiRules.GenerateMenu.CANCEL, ImportUiRules.generateMenu(bakeBusy = true, bakeRunningForEntry = true, onScreenAndFailed = false))
        assertEquals(ImportUiRules.GenerateMenu.DISABLED, ImportUiRules.generateMenu(bakeBusy = true, bakeRunningForEntry = false, onScreenAndFailed = false))
        assertEquals(ImportUiRules.GenerateMenu.DISABLED, ImportUiRules.generateMenu(bakeBusy = false, bakeRunningForEntry = false, onScreenAndFailed = true))
        assertEquals(ImportUiRules.GenerateMenu.ENABLED, ImportUiRules.generateMenu(bakeBusy = false, bakeRunningForEntry = false, onScreenAndFailed = false))
    }

    @Test
    fun tourCornerNeverThrowsOnANegativeHole() {
        // a hidden tour target / spring overshoot used to crash coerceIn (max < min)
        assertEquals(0f, tourCornerRadius(18f, -4f, 30f))
        assertEquals(0f, tourCornerRadius(18f, Float.NaN, 30f))
        assertEquals(10f, tourCornerRadius(18f, 20f, 30f))
        assertEquals(5f, tourCornerRadius(5f, 20f, 30f))
        assertEquals(0f, tourCornerRadius(-3f, 20f, 30f))
    }
}
