package com.tacmap.map

import com.tacmap.calibration.Calibration
import com.tacmap.calibration.Fiduciary
import com.tacmap.calibration.MapSourceKind
import com.tacmap.calibration.PagePoint
import com.tacmap.calibration.PdfGeoreference
import com.tacmap.calibration.Wgs84Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The georeferencing status Layers shows under an imported PDF, as on iOS. */
class ImportedMapGeoreferencingTest {
    // any valid georef will do, the status only looks at the calibration kind + fids
    private val georef: PdfGeoreference = requireNotNull(
        PdfGeoreference.provisional(
            Wgs84Coordinate(-35.3, 149.1),
            listOf(PagePoint(0.0, 0.0), PagePoint(612.0, 0.0), PagePoint(612.0, 792.0), PagePoint(0.0, 792.0)),
        ),
    )

    private fun fid(mgrs: String) = Fiduciary(pdfX = 1.0, pdfY = 2.0, mgrs = mgrs, latitude = -35.3, longitude = 149.1)

    @Test
    fun uncalibratedPdfUsesTheMapCentreFallback() {
        assertEquals(PdfGeoreferencing.NONE, pdfGeoreferencing(MapSourceKind.CALIBRATED_PDF, null))
        assertNull(manualFiduciaryCount(MapSourceKind.CALIBRATED_PDF, null))
    }

    @Test
    fun geoPdfCorrespondencesReadAsGeoreferenced() {
        // The importer stores parsed GeoPDF correspondences without an MGRS.
        val parsed = Calibration.Fiduciaries(List(4) { fid("") }, georef)
        assertEquals(PdfGeoreferencing.GEOREFERENCED, pdfGeoreferencing(MapSourceKind.CALIBRATED_PDF, parsed))
        assertEquals(PdfGeoreferencing.GEOREFERENCED, pdfGeoreferencing(MapSourceKind.GEO_PDF, parsed))
        assertEquals(
            PdfGeoreferencing.GEOREFERENCED,
            pdfGeoreferencing(MapSourceKind.GEO_PDF, Calibration.Parsed(georef)),
        )
        assertNull(manualFiduciaryCount(MapSourceKind.CALIBRATED_PDF, parsed))
    }

    @Test
    fun userFiduciariesReadAsManuallyPlacedWithTheirCount() {
        val manual = Calibration.Fiduciaries(
            listOf(fid("55HFA 12345 67890"), fid("55HFA 22345 67890"), fid("55HFA 12345 77890")),
            georef,
        )
        assertEquals(PdfGeoreferencing.MANUAL, pdfGeoreferencing(MapSourceKind.CALIBRATED_PDF, manual))
        assertEquals(3, manualFiduciaryCount(MapSourceKind.CALIBRATED_PDF, manual))
        // Refining a GeoPDF by hand keeps its parsed points in the fit.
        val refined = Calibration.Fiduciaries(manual.fids + fid("") + fid(""), georef)
        assertEquals(5, manualFiduciaryCount(MapSourceKind.CALIBRATED_PDF, refined))
    }
}
