package com.tacmap.map

import com.tacmap.calibration.GeorefRejectReason
import com.tacmap.calibration.ImportError
import com.tacmap.calibration.ImportFailure
import com.tacmap.calibration.ImportLimits
import com.tacmap.calibration.ImportPrecheck
import com.tacmap.calibration.ImportedMapKind
import com.tacmap.localization.Messages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfImportPreflightContractTest {
    // localization/catalog.json, walked up to from user.dir like the shared vector tests
    private val catalog: JsonObject by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val f = File(dir, "localization/catalog.json")
            if (f.exists()) return@lazy Json.parseToJsonElement(f.readText()).jsonObject
            dir = dir?.parentFile
        }
        error("no localization/catalog.json above ${System.getProperty("user.dir")}")
    }

    private fun shared(key: String): String {
        val entry = catalog[key]!!.jsonObject
        val platforms = entry["platforms"]!!.jsonArray.map { it.jsonPrimitive.content }
        // review 2026-10 (D5-20): one string set for both apps, not two lookalikes
        assertEquals("$key platforms", setOf("ios", "android"), platforms.toSet())
        return entry["en"]!!.jsonPrimitive.content
    }

    @Test
    fun refusedGeoreferenceUsesTheSameCatalogStringsAsIos() {
        // rotated pages are supported now (plan 02 s1), so there's no rotation
        // rejection any more; what must stay loud is a georef we can't use
        val keys = mapOf(
            GeorefRejectReason.LPTS_OUT_OF_RANGE to "pdf_georef_reason_lpts_out_of_range",
            GeorefRejectReason.NON_FINITE to "pdf_georef_reason_non_finite",
            GeorefRejectReason.GPTS_OFF_EARTH to "pdf_georef_reason_off_earth",
            GeorefRejectReason.RMS_GATE to "pdf_georef_reason_rms_gate",
            GeorefRejectReason.DEGENERATE_VIEWPORT to "pdf_georef_reason_degenerate",
            GeorefRejectReason.MALFORMED to "pdf_georef_reason_malformed",
            GeorefRejectReason.UNKNOWN_DATUM to "pdf_georef_reason_unknown_datum",
            GeorefRejectReason.UNSUPPORTED_PROJECTION to "pdf_georef_reason_unsupported_projection",
        )
        assertEquals(GeorefRejectReason.entries.toSet(), keys.keys)
        GeorefRejectReason.entries.forEach { reason ->
            val clause = pdfGeorefRejectionReason(reason)
            assertEquals(reason.code, shared(keys.getValue(reason)), clause)
            assertEquals(shared("pdf_georef_rejected_message").replace("{1}", clause), Messages.pdfGeorefRejectedMessage(clause))
        }
        assertEquals(shared("pdf_georef_rejected_title"), Messages.pdfGeorefRejectedTitle())
        assertEquals(shared("pdf_georef_calibrate_manually"), Messages.pdfGeorefCalibrateManually())
        assertEquals(shared("pdf_map_uncalibrated_label"), Messages.pdfMapUncalibratedLabel())
        assertEquals(shared("pdf_map_uncalibrated_title"), Messages.pdfMapUncalibratedTitle())
        assertEquals(shared("pdf_map_uncalibrated_message"), Messages.pdfMapUncalibratedMessage())
        assertEquals(shared("pdf_map_calibrate_now"), Messages.pdfMapCalibrateNow())
        assertTrue(Messages.pdfGeorefRejectedMessage("x").contains("not been placed on the map"))
    }

    @Test
    fun invalidAndProtectedInputsGetTheSharedImportMessages() {
        // WP5 (D5-20): typed import errors, the same keys and words as iOS, never the
        // exception text (which can carry the picked file's path)
        val invalid = CalibrationText.importFailure(ImportFailure(ImportError.INVALID_PDF)) { it.toString() }
        assertEquals(shared("map_import_invalid_pdf"), invalid)
        val locked = CalibrationText.importFailure(ImportFailure(ImportError.PASSWORD)) { it.toString() }
        assertEquals(shared("map_import_password"), locked)
        assertTrue(!invalid.contains("/"))
    }

    @Test
    fun hugeInputGetsTheSharedLimit() {
        // 512 MiB on both apps now (Android was 256 MB): the limit arg is the byte count
        val failure = ImportPrecheck.check(true, 0, ImportedMapKind.PDF, ImportLimits.PDF_MAX_BYTES + 1, Long.MAX_VALUE)!!
        assertEquals(ImportError.TOO_LARGE, failure.error)
        val text = CalibrationText.importFailure(failure) { "${it / (1024 * 1024)} MiB" }
        assertEquals(shared("map_import_too_large").replace("{1}", "512 MiB"), text)
    }
}
