package com.tacmap.map

import com.tacmap.calibration.GeoDatums
import com.tacmap.calibration.PdfGeorefFixture
import com.tacmap.calibration.fiducial.CalibrationPointKind
import com.tacmap.calibration.fiducial.StatusMessage
import com.tacmap.mgrs.CoordinateInputParser
import com.tacmap.mgrs.CoordinateParseContext
import com.tacmap.mgrs.ParseOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The display bits both platforms pin in the shared fixtures (r1): datum names (A1),
 * grid cell sizes in the interpretation line (OD-F11) and the {rms} arg (OD-F12).
 * German comes off the values-de strings the app actually ships
 */
class CalibrationDisplayTextTest {
    private val input = Json.parseToJsonElement(PdfGeorefFixture.file("calibration_input.json").readText()).jsonObject
    private val fit = Json.parseToJsonElement(PdfGeorefFixture.file("calibration_fit_report.json").readText()).jsonObject
    private val display = input["display"]!!.jsonObject

    private fun JsonObject.s(k: String): String = this[k]!!.jsonPrimitive.content

    @Test
    fun datumNamesAreTheOneSharedTable() {
        val rows = display["datumDisplayNames"]!!.jsonArray.map { it.jsonObject }
        assertEquals(16, rows.size)
        // same order as the datum sheet
        assertEquals(rows.map { it.s("id") }, CalibrationText.DATUM_ORDER)
        rows.forEach { r ->
            assertEquals(r.s("id"), r.s("name"), CalibrationText.datumName(r.s("id")))
            // every row is a datum the app can actually pick
            assertTrue(r.s("id"), GeoDatums.byId(r.s("id")) != null)
        }
        assertEquals("Tokyo", CalibrationText.datumName("TOKYO"))
    }

    @Test
    fun cellSizesAreWholeMetresOrKm() {
        val rows = display["cellSizeText"]!!.jsonArray.map { it.jsonObject }
        assertTrue(rows.size >= 7)
        rows.forEach { r ->
            val m = r["sizeM"]!!.jsonPrimitive.double
            assertEquals("$m en", r.s("en"), CalibrationText.cellSizeText(m))
            assertEquals("$m de", r.s("de"), CalibrationText.cellSizeText(m))
        }
    }

    @Test
    fun interpretationCellsReadTheSameInBothLanguages() {
        val cases = input["cases"]!!.jsonArray.map { it.jsonObject }.associateBy { it.s("id") }
        val rows = display["interpretationCells"]!!.jsonArray.map { it.jsonObject }
        assertTrue(rows.size >= 4)
        val de = germanStrings()
        for (r in rows) {
            val c = cases.getValue(r.s("fromCase"))
            val ctx = c["context"]!!.jsonObject
            val got = CoordinateInputParser.parse(
                c.s("input"),
                CoordinateParseContext(
                    datum = requireNotNull(GeoDatums.byId(ctx.s("datumId"))),
                    isFirstPoint = ctx["isFirstPoint"]!!.jsonPrimitive.content.toBoolean(),
                    kind = requireNotNull(CalibrationPointKind.fromCode(ctx.s("kind"))),
                ),
            ) as ParseOutcome.Ok
            val key = r.s("key")
            val msg: StatusMessage = got.reference.messages.first { it.key == key }
            val argText = r["argText"]!!.jsonObject
            argText.forEach { (k, v) ->
                assertEquals("${r.s("fromCase")} $k", v.jsonPrimitive.content, CalibrationText.cellSizeText((msg.args.getValue(k) as Number).toDouble()))
            }
            // en: the real accessor (JVM falls back to the catalogue English)
            assertEquals("${r.s("fromCase")} en", r.s("en"), CalibrationText.text(msg))
            // de: the shipped format with the same arg text
            val args = listOfNotNull(argText["sizeM"], argText["halfM"]).map { it.jsonPrimitive.content }.toTypedArray()
            assertEquals("${r.s("fromCase")} de", r.s("de"), String.format(Locale.GERMAN, de.getValue(key), *args))
        }
    }

    @Test
    fun rmsReadsOneDecimalUnderTenMetres() {
        val rows = fit["rmsDisplay"]!!.jsonObject["cases"]!!.jsonArray.map { it.jsonObject }
        assertTrue(rows.size >= 11)
        rows.forEach { r ->
            val m = r["rmsM"]!!.jsonPrimitive.double
            assertEquals("$m en", r.s("en"), CalibrationText.rmsText(m, Locale.ENGLISH))
            assertEquals("$m de", r.s("de"), CalibrationText.rmsText(m, Locale.GERMAN))
        }
        // and it's what the fit summary actually gets
        val summary = CalibrationText.text(
            StatusMessage("calibration_fit_summary", mapOf("points" to 4, "rms" to 0.37, "grade" to "calibration_grade_good")),
        )
        assertTrue(summary, summary.contains("RMS " + CalibrationText.rmsText(0.37)))
    }

    /** values-de/localized_strings.xml, name -> java format string */
    private fun germanStrings(): Map<String, String> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var xml: File? = null
        repeat(5) {
            listOf("src/main/res", "app/src/main/res", "android/app/src/main/res").forEach { p ->
                val f = File(dir, "$p/values-de/localized_strings.xml")
                if (xml == null && f.exists()) xml = f
            }
            dir = dir?.parentFile
        }
        val text = requireNotNull(xml) { "no values-de strings" }.readText()
        return Regex("<string name=\"([^\"]+)\">\"(.*)\"</string>").findAll(text).associate { m ->
            m.groupValues[1] to m.groupValues[2].replace("\\'", "'").replace("\\\"", "\"")
        }
    }
}
