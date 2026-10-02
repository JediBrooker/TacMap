package com.tacmap.map.render.pdf

import com.tacmap.calibration.PdfGeorefFixture.arr
import com.tacmap.calibration.PdfGeorefFixture.d
import com.tacmap.calibration.PdfGeorefFixture.obj
import com.tacmap.calibration.PdfGeorefFixture.str
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class PdfDisplayedWarpPrecisionTest {
    @Test fun canberraOverzoomStaysWithinOnePhysicalPixelOfIndependentProjReference() {
        val vectors = PdfTileRenderFixture.root.arr("overzoomWarpReference")
        assertTrue(vectors.isNotEmpty())
        for (vector in vectors) {
            val row = vector.jsonObject
            val sheet = PdfTileRenderFixture.sheetById(row.str("sheet"))
            val job = row.obj("job")
            val plan = PdfTileWarp.plan(TileJob(job["z"]!!.jsonPrimitive.int,
                job["x0"]!!.jsonPrimitive.int, job["y0"]!!.jsonPrimitive.int,
                job["cols"]!!.jsonPrimitive.int, job["rows"]!!.jsonPrimitive.int),
                row["tilePx"]!!.jsonPrimitive.int, sheet.footprint, sheet.georef)
            // Shared reference is independently constructed through PROJ, not native planner cells.
            val page = com.tacmap.calibration.PdfGeorefFixture.doubles(row["page"]!!)
            val expected = com.tacmap.calibration.PdfGeorefFixture.doubles(row["expectedPx"]!!)
            val cell = plan.cells.single {
                expected[0] >= it.left && expected[0] < it.right && expected[1] >= it.top && expected[1] < it.bottom
            }
            assertEquals(0, plan.dropped)
            val error = row.d("physicalScale") * hypot(cell.pageToPx.mapX(page[0], page[1]) - expected[0],
                cell.pageToPx.mapY(page[0], page[1]) - expected[1])
            assertTrue("${row.str("sheet")} physical residual $error px",
                error.isFinite() && error <= row.d("maxPhysicalError"))
        }
    }
}
