package com.tacmap.map.render.pdf

import com.tacmap.calibration.GeorefOrigin
import com.tacmap.calibration.PagePoint
import com.tacmap.calibration.PdfBox
import com.tacmap.calibration.PdfGeorefFixture
import com.tacmap.calibration.PdfGeorefFixture.arr
import com.tacmap.calibration.PdfGeorefFixture.obj
import com.tacmap.calibration.PdfGeorefFixture.str
import com.tacmap.calibration.PdfGeoreference
import com.tacmap.calibration.PlaneAffine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * testdata/pdf_tile_render.json plus the georefs it borrows from pdf_georef.json.
 * Reads through PdfGeorefFixture.readBytes so the instrumented tests can point it
 * at the test apk assets, same as the WP1 fixture.
 */
internal object PdfTileRenderFixture {
    val root: JsonObject by lazy {
        Json.parseToJsonElement(PdfGeorefFixture.readBytes("pdf_tile_render.json").decodeToString()).jsonObject
    }

    private val sheets: Map<String, JsonObject> by lazy {
        PdfGeorefFixture.root.arr("sheets").map { it.jsonObject }.associateBy { it.str("id") }
    }

    fun sheet(id: String): JsonObject = sheets.getValue(id)

    /** a georef straight from the expected numbers (crs, datum, affine, crop), no parsing */
    fun georef(o: JsonObject, origin: GeorefOrigin = GeorefOrigin.ADOBE_VP): PdfGeoreference = PdfGeoreference(
        page = 0,
        crs = PdfGeorefFixture.crsOf(o.obj("crs")),
        datum = PdfGeorefFixture.datumOf(o.obj("datum")),
        affine = PlaneAffine.of(PdfGeorefFixture.doubles(o["affine"]!!))!!,
        crop = o.arr("crop").map(PdfGeorefFixture::point),
        origin = origin,
    )

    /** CropBox ∩ MediaBox, MediaBox alone when there's no CropBox */
    fun pageBox(o: JsonObject): PdfBox {
        val m = PdfGeorefFixture.doubles(o["mediaBox"]!!)
        val c = o["cropBox"]?.takeIf { it !is JsonNull }?.let(PdfGeorefFixture::doubles) ?: m
        return PdfBox(maxOf(c[0], m[0]), maxOf(c[1], m[1]), minOf(c[2], m[2]), minOf(c[3], m[3]))
    }

    class Sheet(val id: String, val georef: PdfGeoreference, val pageBox: PdfBox) {
        val footprint: PdfFootprint by lazy { PdfFootprint.build(georef, pageBox) }
        val policy: PdfZoomPolicy by lazy { PdfZoomPolicy.of(georef, footprint) }
    }

    private val cache = HashMap<String, Sheet>()

    /** fixture sheet ids from pdf_georef.json, plus the inline wide_tm_1m and render_markers */
    @Synchronized
    fun sheetById(id: String): Sheet = cache.getOrPut(id) {
        when (id) {
            "render_markers" -> {
                val m = root.obj("markers")
                Sheet(id, georef(m.obj("georef")), pageBox(m))
            }
            "render_blank" -> {
                val b = root.obj("blank")
                Sheet(id, georef(b.obj("georef")), pageBox(b))
            }
            "render_dense" -> {
                val d = root.obj("dense")
                Sheet(id, georef(d.obj("georef")), pageBox(d))
            }
            "render_blank_corner" -> {
                val b = root.arr("stagedRegion").map { it.jsonObject }.first { it.str("kind") == "blankCheck" }
                Sheet(id, georef(b.obj("georef")), pageBox(b))
            }
            else -> {
                val synth = root.arr("warpSyntheticGeorefs").map { it.jsonObject }.firstOrNull { it.str("id") == id }
                if (synth != null) {
                    Sheet(id, georef(synth), pageBox(synth))
                } else {
                    val s = sheet(id)
                    Sheet(id, georef(s.obj("expected")), pageBox(s))
                }
            }
        }
    }

    fun ints(e: JsonElement): IntArray = PdfGeorefFixture.doubles(e).map { it.toInt() }.toIntArray()

    fun points(e: JsonElement): List<PagePoint> = (e as kotlinx.serialization.json.JsonArray).map(PdfGeorefFixture::point)
}
