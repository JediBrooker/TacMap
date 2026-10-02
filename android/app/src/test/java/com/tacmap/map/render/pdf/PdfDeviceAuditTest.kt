package com.tacmap.map.render.pdf

import com.tacmap.app.DebugLaunchHooks
import com.tacmap.calibration.PagePoint
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PdfDeviceAuditTest {
    private fun plan(x: Int = 1, cells: Int = 1): PdfWarpPlan = PdfWarpPlan(
        TileJob(16, x, 2, 1, 1), 672, intArrayOf(0, 0, 672, 672), 4,
        List(cells) { PdfWarpCell(0, 0, 672, 672, Affine2.IDENTITY, 0.125, TileCoverage.INSIDE, listOf(PagePoint(1.0, 2.0)), 0) }, 0,
    )

    @Test fun explicitOptInIsIndependentOfScreenshotOverride() {
        assertNull(DebugLaunchHooks.parse { null })
        assertNull(DebugLaunchHooks.parse { if (it == DebugLaunchHooks.EXTRA_DEVICE_AUDIT) "0" else null })
        val request = DebugLaunchHooks.parse { if (it == DebugLaunchHooks.EXTRA_DEVICE_AUDIT) "1" else null }!!
        assertTrue(request.deviceAudit)
        assertFalse(request.allowScreenshots)
        assertFalse(request.grid)
        assertNull(request.camera)
        assertNull(request.importPdfPath)
    }

    @Test fun defaultObserverRetainsNoRenderInformation() {
        assertFalse(DebugLaunchHooks.deviceAudit)
        PdfDeviceAudit.completed("opaque", plan(), 16, 14, "vector-VISIBLE", "runtime-opaque")
        val snapshot = PdfDeviceAudit.snapshot()
        assertFalse(snapshot["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(0, snapshot["completed"]!!.jsonObject["records"]!!.jsonArray.size)
        assertEquals(JsonNull, snapshot["frame"])
    }

    @Test fun recordCapturesExistingCellGeometryWithoutContentIdentifiers() {
        val b = PdfDeviceAuditBuffer()
        b.completed("opaque", plan(), 16, 14, "vector-VISIBLE")
        val r = b.snapshot()["records"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("sourceId", "renderSourceId", "path", "detailZoom", "baseMaxZoom", "job", "tilePx", "dropped", "maxDepth", "root", "cells"), r.keys)
        assertEquals(672, r["tilePx"]!!.jsonPrimitive.int)
        val cell = r["cells"]!!.jsonArray.single().jsonObject
        assertEquals(0.125, cell["errorPx"]!!.jsonPrimitive.double, 0.0)
        assertEquals(listOf(0, 0, 672, 672), cell["rect"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0), cell["pageToPx"]!!.jsonArray.map { it.jsonPrimitive.double })
    }

    @Test fun recordCountAndCellBudgetsEvictWithExplicitAccounting() {
        for (b in listOf(PdfDeviceAuditBuffer(2, 10), PdfDeviceAuditBuffer(10, 2))) {
            for (x in 1..3) b.completed("opaque", plan(x), 16, 14, "raster-live")
            val s = b.snapshot()
            assertEquals(listOf(2, 3), s["records"]!!.jsonArray.map { it.jsonObject["job"]!!.jsonObject["x"]!!.jsonPrimitive.int })
            assertEquals(2, s["retainedCells"]!!.jsonPrimitive.int)
            assertEquals(1, s["evictedRecords"]!!.jsonPrimitive.int)
        }
    }

    @Test fun oversizedPlanIsReportedAsOmittedRatherThanPartialEvidence() {
        val b = PdfDeviceAuditBuffer(2, 2)
        b.completed("opaque", plan(cells = 3), 16, 14, "raster-live")
        assertTrue(b.snapshot()["records"]!!.jsonArray.isEmpty())
        assertEquals(1, b.snapshot()["omittedRecords"]!!.jsonPrimitive.int)
        assertEquals(0, b.snapshot()["retainedCells"]!!.jsonPrimitive.int)
    }

    @Test fun laterPlanMutationCannotChangeCapturedEvidence() {
        val b = PdfDeviceAuditBuffer()
        val p = plan()
        b.completed("opaque", p, 16, 14, "raster-live")
        p.root!![0] = 100
        assertEquals(0, b.snapshot()["records"]!!.jsonArray.single().jsonObject["root"]!!.jsonArray.first().jsonPrimitive.int)
    }
}
