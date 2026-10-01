package com.tacmap.calibration

import com.tacmap.calibration.PdfGeorefFixture.arr
import com.tacmap.calibration.PdfGeorefFixture.obj
import com.tacmap.calibration.PdfGeorefFixture.str
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How /VP and LGIDict combine on one page, the bits the fixture doesn't spell out
 * but iOS does the same way: a bad /VP doesn't hide a good LGIDict, oversized
 * metadata is a loud rejection, the first rejection is the one reported, and a
 * CTM entry without a neatline crops to the visible page box.
 */
class GeoPdfPrecedenceTest {
    private val sheets = PdfGeorefFixture.root.arr("sheets").map { it.jsonObject }.associateBy { it.str("id") }
    private val goodLgi = PdfGeorefFixture.pageData(sheets.getValue("sf_lgictm"), sheets.getValue("sf_lgictm").obj("written"))
    private val goodVp = PdfGeorefFixture.pageData(sheets.getValue("sf_iso"), sheets.getValue("sf_iso").obj("written"))
    private val offEarthVp = goodVp.viewports.first().copy(gpts = goodVp.viewports.first().gpts!!.mapIndexed { i, v -> if (i == 0) 91.0 else v })

    @Test
    fun brokenViewportDoesNotHideAGoodLgiDict() {
        val page = goodLgi.copy(viewports = listOf(offEarthVp))
        val result = GeoPdfGeoreferencer.build(page) as GeoPdfGeorefResult.Georeferenced
        assertEquals(GeorefOrigin.LGI_DICT, result.georef.origin)
    }

    @Test
    fun oversizedMetadataIsALoudRejectionUnlessTheOtherDictIsGood() {
        assertEquals(
            GeoPdfGeorefResult.Rejected(GeorefRejectReason.MALFORMED),
            GeoPdfGeoreferencer.build(goodVp.copy(viewports = emptyList(), viewportsOversized = true)),
        )
        assertEquals(
            GeoPdfGeorefResult.Rejected(GeorefRejectReason.MALFORMED),
            GeoPdfGeoreferencer.build(goodLgi.copy(lgiEntries = emptyList(), lgiOversized = true)),
        )
        assertTrue(GeoPdfGeoreferencer.build(goodLgi.copy(viewportsOversized = true)) is GeoPdfGeorefResult.Georeferenced)
    }

    @Test
    fun firstRejectionWins() {
        val badLgi = goodLgi.lgiEntries.first().copy(
            projection = goodLgi.lgiEntries.first().projection!!.copy(datum = LgiDatumData.Code("ZZZ")),
        )
        val page = goodLgi.copy(viewports = listOf(offEarthVp), lgiEntries = listOf(badLgi))
        assertEquals(GeoPdfGeorefResult.Rejected(GeorefRejectReason.GPTS_OFF_EARTH), GeoPdfGeoreferencer.build(page))
        assertEquals(
            GeoPdfGeorefResult.Rejected(GeorefRejectReason.UNKNOWN_DATUM),
            GeoPdfGeoreferencer.build(goodLgi.copy(lgiEntries = listOf(badLgi))),
        )
    }

    @Test
    fun ctmWithoutNeatlineCropsToTheVisiblePageBox() {
        val entry = goodLgi.lgiEntries.first().copy(neatline = null)
        val page = goodLgi.copy(
            mediaBox = listOf(0.0, 0.0, 824.315, 1051.087),
            cropBox = listOf(36.0, 36.0, 900.0, 1015.0),
            lgiEntries = listOf(entry),
        )
        val g = (GeoPdfGeoreferencer.build(page) as GeoPdfGeorefResult.Georeferenced).georef
        assertEquals(
            listOf(PagePoint(36.0, 36.0), PagePoint(824.315, 36.0), PagePoint(824.315, 1015.0), PagePoint(36.0, 1015.0)),
            g.crop,
        )
    }

    @Test
    fun layersHasToBeSpelledLayers() {
        // exact (trimmed) match like iOS; anything else falls to the biggest neatline
        val entries = goodLgi.lgiEntries
        val inset = entries.first().copy(description = "layers", neatline = listOf(8.0, 8.0, 64.0, 8.0, 64.0, 64.0, 8.0, 64.0))
        val main = entries.first().copy(description = "Main Map")
        val result = GeoPdfGeoreferencer.build(goodLgi.copy(lgiEntries = listOf(inset, main))) as GeoPdfGeorefResult.Georeferenced
        assertEquals(1, result.selection.index)
        val named = GeoPdfGeoreferencer.build(goodLgi.copy(lgiEntries = listOf(main, inset.copy(description = " Layers ")))) as GeoPdfGeorefResult.Georeferenced
        assertEquals(1, named.selection.index)
    }
}
