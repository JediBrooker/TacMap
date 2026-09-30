package com.tacmap.map

import com.tacmap.calibration.AffineTransform2D
import com.tacmap.calibration.Calibration
import com.tacmap.calibration.Fiduciary
import com.tacmap.calibration.MapSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The georeferencing status Layers shows under an imported PDF, as on iOS. */
class ImportedMapGeoreferencingTest {
    private val transform = AffineTransform2D(1e-4, 0.0, 149.0, 0.0, 1e-4, -35.3)

    private fun fid(mgrs: String) = Fiduciary(pdfX = 1.0, pdfY = 2.0, mgrs = mgrs, latitude = -35.3, longitude = 149.1)

    @Test
    fun uncalibratedPdfUsesTheMapCentreFallback() {
        assertEquals(PdfGeoreferencing.NONE, pdfGeoreferencing(MapSourceKind.CALIBRATED_PDF, null))
        assertNull(manualFiduciaryCount(MapSourceKind.CALIBRATED_PDF, null))
    }

    @Test
    fun geoPdfCorrespondencesReadAsGeoreferenced() {
        // The importer stores parsed GeoPDF correspondences without an MGRS.
        val parsed = Calibration.Fiduciaries(List(4) { fid("") }, transform)
        assertEquals(PdfGeoreferencing.GEOREFERENCED, pdfGeoreferencing(MapSourceKind.CALIBRATED_PDF, parsed))
        assertEquals(PdfGeoreferencing.GEOREFERENCED, pdfGeoreferencing(MapSourceKind.GEO_PDF, parsed))
        assertEquals(
            PdfGeoreferencing.GEOREFERENCED,
            pdfGeoreferencing(MapSourceKind.GEO_PDF, Calibration.Parsed("EPSG:4326", transform)),
        )
        assertNull(manualFiduciaryCount(MapSourceKind.CALIBRATED_PDF, parsed))
    }

    @Test
    fun userFiduciariesReadAsManuallyPlacedWithTheirCount() {
        val manual = Calibration.Fiduciaries(
            listOf(fid("55HFA 12345 67890"), fid("55HFA 22345 67890"), fid("55HFA 12345 77890")),
            transform,
        )
        assertEquals(PdfGeoreferencing.MANUAL, pdfGeoreferencing(MapSourceKind.CALIBRATED_PDF, manual))
        assertEquals(3, manualFiduciaryCount(MapSourceKind.CALIBRATED_PDF, manual))
        // Refining a GeoPDF by hand keeps its parsed points in the fit.
        val refined = Calibration.Fiduciaries(manual.fids + fid("") + fid(""), transform)
        assertEquals(5, manualFiduciaryCount(MapSourceKind.CALIBRATED_PDF, refined))
    }
}
