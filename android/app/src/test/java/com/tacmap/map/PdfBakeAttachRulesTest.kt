package com.tacmap.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M13 / F1: a bake published during calibration never lands on the calibration display */
class PdfBakeAttachRulesTest {
    private val saved = """{"page":0,"affine":[1,0,0,0,1,0]}"""
    private val refit = """{"page":0,"affine":[1.01,0,0,0,1,0]}"""

    @Test
    fun onlyTheEntrysOwnGeorefOutsideCalibrationGetsTheBake() {
        assertTrue(PdfBakeAttachRules.mayAttach(calibrating = false, sourceGeorefCanonical = saved, entryGeorefCanonical = saved))
        // the refit (or preview) source isn't the entry's georef
        assertFalse(PdfBakeAttachRules.mayAttach(calibrating = false, sourceGeorefCanonical = refit, entryGeorefCanonical = saved))
        // even a GeoPDF refine still on the saved georef doesn't get it while calibrating
        assertFalse(PdfBakeAttachRules.mayAttach(calibrating = true, sourceGeorefCanonical = saved, entryGeorefCanonical = saved))
        assertFalse(PdfBakeAttachRules.mayAttach(calibrating = true, sourceGeorefCanonical = refit, entryGeorefCanonical = saved))
    }
}
